package com.xinhongru.cms.siteservice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinhongru.security.preview.PreviewAccessProperties;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 独立进程站点服务管理器。
 *
 * <p>独立进程模式下，每个站点的站点服务由管理节点拉起一个独立子 JVM（同一个 jar）， 子进程不开放管理端口（--server.port=-1），只启动指定站点的服务端口。
 *
 * <p>管理节点仅在内存中跟踪子进程句柄；管理节点重启后恢复 RUNNING 站点，已存活的服务通过控制接口确认，停止时回退到按端口杀进程。
 */
@Slf4j
@Service
public class StandaloneSiteProcessManager {
    private static final String STATUS_RUNNING = SiteServiceStatus.STATUS_RUNNING;
    private static final String STATUS_STOPPED = SiteServiceStatus.STATUS_STOPPED;

    private static final int MIN_PORT = 8080;
    private static final int MAX_PORT = 9000;

    private static final String INTERNAL_ROUTES = "__internal/routes";
    private static final String INTERNAL_RELOAD_ROUTES = "__internal/reload-routes";

    private final SiteProjectMapper projectMapper;
    private final SiteServiceModeConfig modeConfig;
    private final Environment environment;
    private final PreviewAccessProperties previewAccessProperties;

    private final Map<Long, Process> processes = new ConcurrentHashMap<>();

    @Value("${cms.site-service.standalone.enabled:false}")
    private boolean standaloneEnabled;

    public StandaloneSiteProcessManager(
            SiteProjectMapper projectMapper,
            SiteServiceModeConfig modeConfig,
            Environment environment,
            PreviewAccessProperties previewAccessProperties) {
        this.projectMapper = projectMapper;
        this.modeConfig = modeConfig;
        this.environment = environment;
        this.previewAccessProperties = previewAccessProperties;
    }

    /** 管理节点重启时恢复 RUNNING 站点；仍存活的子进程继续使用，不重复拉起。 */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshStatusesOnStartup() {
        if (standaloneEnabled || !modeConfig.isIndependent()) {
            return;
        }

        List<SiteProjectEntity> sites =
                projectMapper.selectList(
                        new LambdaQueryWrapper<SiteProjectEntity>()
                                .eq(SiteProjectEntity::getSiteServiceStatus, STATUS_RUNNING));
        for (SiteProjectEntity site : sites) {
            try {
                Integer port = site.getSiteServicePort();
                if (!ServicePortRange.isValid(port)) {
                    log.error("站点端口无效，无法恢复独立进程: siteId={}, port={}", site.getId(), port);
                    continue;
                }
                if (isPortListening(port)) {
                    // 控制接口认证成功才视为站点服务，不能把任意端口监听者当作已恢复。
                    callInternal(site, false);
                    log.info("站点独立进程仍在运行: siteId={}, port={}", site.getId(), port);
                    continue;
                }
                SiteServiceStatus status = start(site, true);
                if (!STATUS_RUNNING.equals(status.status())) {
                    log.error(
                            "恢复站点独立进程失败: siteId={}, error={}", site.getId(), status.errorMessage());
                }
            } catch (Exception e) {
                log.error("恢复站点独立进程失败: siteId={}", site.getId(), e);
            }
        }
    }

    /** 启动站点的独立进程。 */
    public SiteServiceStatus start(SiteProjectEntity site) {
        return start(site, false);
    }

    private SiteServiceStatus start(SiteProjectEntity site, boolean recovering) {
        Long siteId = site.getId();
        Integer port = site.getSiteServicePort();
        if (port != null && !ServicePortRange.isValid(port)) {
            return new SiteServiceStatus(STATUS_STOPPED, port, null, ServicePortRange.MESSAGE);
        }
        if (port == null) {
            port = allocatePort(siteId);
            if (port == null) {
                return new SiteServiceStatus(STATUS_STOPPED, null, null, "没有可用端口");
            }
            site.setSiteServicePort(port);
            projectMapper.updateById(site);
        }

        Process existing = processes.get(siteId);
        if (existing != null && existing.isAlive()) {
            return new SiteServiceStatus(STATUS_RUNNING, port, buildAccessUrl(site, port), null);
        }

        if (isPortListening(port)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED, port, null, "端口 " + port + " 已被占用，无法启动独立进程");
        }

        SiteServiceModeConfig.LaunchParams params = modeConfig.resolveLaunchParams(siteId);
        List<String> command = buildCommand(siteId, port, params);

        try {
            Path logFile = logFileFor(siteId);
            Files.createDirectories(logFile.getParent());

            ProcessBuilder pb = new ProcessBuilder(command);
            // 传递管理节点当前环境的有效总开关；站点开关和密码仍从数据库读取。
            pb.environment()
                    .put(
                            "CMS_PREVIEWACCESS_ENABLED",
                            Boolean.toString(previewAccessProperties.isEnabled()));
            File workDir = new File(params.workDir());
            if (workDir.isDirectory()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            log.info("启动站点独立进程: siteId={}, port={}, jar={}", siteId, port, params.jarPath());
            Process process = pb.start();
            processes.put(siteId, process);

            if (!waitForPort(port, 60)) {
                boolean alive = process.isAlive();
                stopProcess(process, siteId);
                processes.remove(siteId);
                if (!recovering) {
                    site.setSiteServiceStatus(STATUS_STOPPED);
                    projectMapper.updateById(site);
                }
                return new SiteServiceStatus(
                        STATUS_STOPPED,
                        port,
                        null,
                        (alive ? "独立进程启动超时" : "独立进程启动失败") + "，详见日志 " + logFile);
            }

            site.setSiteServiceStatus(STATUS_RUNNING);
            projectMapper.updateById(site);
            return new SiteServiceStatus(STATUS_RUNNING, port, buildAccessUrl(site, port), null);
        } catch (Exception e) {
            processes.remove(siteId);
            log.error("启动站点独立进程失败: siteId={}", siteId, e);
            return new SiteServiceStatus(STATUS_STOPPED, port, null, "启动失败: " + e.getMessage());
        }
    }

    /** 停止站点的独立进程。 */
    public SiteServiceStatus stop(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        Integer port = site.getSiteServicePort();
        // 仅当数据库标记为运行中时才按端口兜底清理，避免误杀恰好占用该端口的无关进程
        boolean wasRunning = STATUS_RUNNING.equals(site.getSiteServiceStatus());

        Process process = processes.remove(siteId);
        stopProcess(process, siteId);

        if (wasRunning && port != null && isPortListening(port)) {
            PortProcessKiller.killByPort(port);
        }

        site.setSiteServiceStatus(STATUS_STOPPED);
        projectMapper.updateById(site);
        return new SiteServiceStatus(STATUS_STOPPED, port, null, null);
    }

    /** 设置站点服务端口：运行中则先停子进程，改端口后按需重启。 */
    public SiteServiceStatus setPort(Long siteId, int port) {
        SiteProjectEntity site = requireSite(siteId);
        if (!ServicePortRange.isValid(port)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED, site.getSiteServicePort(), null, ServicePortRange.MESSAGE);
        }

        boolean wasRunning = STATUS_RUNNING.equals(getStatus(site).status());
        if (wasRunning) {
            stop(siteId);
            site = requireSite(siteId);
        }

        if (isPortListening(port)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED, site.getSiteServicePort(), null, "端口 " + port + " 已被占用");
        }

        site.setSiteServicePort(port);
        projectMapper.updateById(site);

        if (wasRunning) {
            return start(site);
        }
        return new SiteServiceStatus(STATUS_STOPPED, port, null, null);
    }

    /** 获取站点独立进程状态（进程句柄存活或端口可达即视为运行中）。 */
    public SiteServiceStatus getStatus(SiteProjectEntity site) {
        Long siteId = site.getId();
        Integer port = site.getSiteServicePort();

        Process process = processes.get(siteId);
        boolean alive = process != null && process.isAlive();
        if (port != null && (alive || isPortListening(port))) {
            return new SiteServiceStatus(STATUS_RUNNING, port, buildAccessUrl(site, port), null);
        }

        String status = site.getSiteServiceStatus();
        if (STATUS_RUNNING.equals(status)) {
            status = STATUS_STOPPED;
        }
        return new SiteServiceStatus(status == null ? STATUS_STOPPED : status, port, null, null);
    }

    public Long getPid(Long siteId) {
        Process process = processes.get(siteId);
        return process != null && process.isAlive() ? process.pid() : null;
    }

    /** 调用子进程内部接口（路由查询/重载），携带控制令牌。 */
    public String callInternal(SiteProjectEntity site, boolean reload) {
        Integer port = site.getSiteServicePort();
        if (port == null) {
            throw new IllegalStateException("站点服务端口未配置");
        }
        String token = modeConfig.getOrCreateControlToken();
        String path = reload ? INTERNAL_RELOAD_ROUTES : INTERNAL_ROUTES;
        String url = "http://127.0.0.1:" + port + "/" + path;

        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("X-Site-Service-Token", token);
            if (reload) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<String> response =
                    client.send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("子进程内部接口返回 " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("调用子进程内部接口失败: " + e.getMessage(), e);
        }
    }

    /** 分配一个未被其它站点占用且系统可用的端口。 */
    private Integer allocatePort(Long siteId) {
        Set<Integer> used = new HashSet<>();
        for (SiteProjectEntity other : projectMapper.selectList(null)) {
            if (!other.getId().equals(siteId) && other.getSiteServicePort() != null) {
                used.add(other.getSiteServicePort());
            }
        }
        for (int candidate = MIN_PORT; candidate <= MAX_PORT; candidate++) {
            if (!used.contains(candidate) && !isPortListening(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> buildCommand(
            Long siteId, int port, SiteServiceModeConfig.LaunchParams params) {
        List<String> command = new ArrayList<>();
        command.add(params.javaPath());
        command.addAll(splitArgs(params.jvmArgs()));
        command.add("-jar");
        command.add(params.jarPath());
        command.add("--server.port=-1");
        command.add("--cms.site-service.standalone.enabled=true");
        command.add("--cms.site-service.standalone.site-id=" + siteId);
        command.add("--cms.site-service.standalone.port=" + port);
        command.add("--cms.site-service.standalone.control-token=" + params.controlToken());

        String[] profiles = environment.getActiveProfiles();
        if (profiles.length > 0) {
            command.add("--spring.profiles.active=" + String.join(",", profiles));
        }
        command.addAll(splitArgs(params.programArgs()));
        return command;
    }

    private static List<String> splitArgs(String args) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isBlank(args)) {
            return result;
        }
        for (String token : args.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private Path logFileFor(Long siteId) {
        return Paths.get(System.getProperty("user.dir"), "logs", "site-service-" + siteId + ".log");
    }

    private void stopProcess(Process process, Long siteId) {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("停止站点独立进程: siteId={}, pid={}", siteId, process.pid());
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitForPort(int port, int maxSeconds) {
        for (int i = 0; i < maxSeconds * 2; i++) {
            if (isPortListening(port)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isPortListening(port);
    }

    /** 探测端口是否有服务监听（TCP 连接成功即视为监听）。 */
    private boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildAccessUrl(SiteProjectEntity site, int port) {
        String host =
                environment.acceptsProfiles(Profiles.of("dev"))
                        ? "127.0.0.1"
                        : StringUtils.isNotBlank(site.getSiteDomain())
                                ? site.getSiteDomain()
                                : "localhost";
        return "http://" + host + ":" + port + "/";
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点 ID 不能为空");
        }
        Optional<SiteProjectEntity> site = Optional.ofNullable(projectMapper.selectById(siteId));
        return site.orElseThrow(() -> new IllegalArgumentException("站点不存在"));
    }
}

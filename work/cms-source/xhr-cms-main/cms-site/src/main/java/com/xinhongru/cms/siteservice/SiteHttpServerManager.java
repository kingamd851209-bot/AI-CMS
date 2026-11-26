package com.xinhongru.cms.siteservice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinhongru.cms.template.TemplatePreviewService;
import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.preview.PreviewAccessGuard;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.site.storage.SiteStoragePathResolver;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点 HTTP 服务管理器
 *
 * <p>管理多个站点的独立 HTTP 服务，支持： - 端口池管理 (8080-9000) - 服务生命周期 (启动/停止/重启) - 应用启动时自动恢复 RUNNING 状态的服务
 */
@Slf4j
@Service
public class SiteHttpServerManager {
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSearchRoutes publicSearchRoutes;

    private static final int MIN_PORT = 8080;
    private static final int MAX_PORT = 9000;
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STOPPED = "STOPPED";

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.PublicBindingRootRoutes publicRootRoutes;

    private final SiteProjectMapper projectMapper;
    private final UiPackageStorageService storageService;
    private final TemplatePreviewService previewService;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;
    private final TemplateStorageService templateStorageService;
    private final SiteServiceModeConfig modeConfig;
    private final Environment environment;
    private final com.xinhongru.cms.integration.SiteExternalIntegrationOutput
            externalIntegrationOutput;
    private final PreviewAccessGuard previewAccessGuard;

    @Value("${cms.site-service.standalone.enabled:false}")
    private boolean standaloneEnabled;

    private final SiteRouteRegistry routeRegistry;

    // 站点ID -> 服务实例
    private final Map<Long, SiteHttpServer> servers;

    // 已使用的端口
    private final Map<Integer, Long> usedPorts = new ConcurrentHashMap<>();

    public SiteHttpServerManager(
            SiteProjectMapper projectMapper,
            UiPackageStorageService storageService,
            TemplatePreviewService previewService,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            TemplateStorageService templateStorageService,
            SiteServiceModeConfig modeConfig,
            SiteRouteRegistry routeRegistry,
            SiteServerRegistry serverRegistry,
            Environment environment,
            com.xinhongru.cms.integration.SiteExternalIntegrationOutput externalIntegrationOutput,
            PreviewAccessGuard previewAccessGuard) {
        this.servers = serverRegistry.servers();
        this.projectMapper = projectMapper;
        this.storageService = storageService;
        this.previewService = previewService;
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
        this.templateStorageService = templateStorageService;
        this.modeConfig = modeConfig;
        this.routeRegistry = routeRegistry;
        this.environment = environment;
        this.externalIntegrationOutput = externalIntegrationOutput;
        this.previewAccessGuard = previewAccessGuard;
    }

    /** 应用启动后自动恢复 RUNNING 状态的服务 */
    @EventListener(ApplicationReadyEvent.class)
    public void autoRecoverServices() {
        // 独立进程模式下，站点服务由各自子进程提供；子进程自身（standalone）也不做统一进程恢复
        if (standaloneEnabled || modeConfig.isIndependent()) {
            log.info("当前为独立进程模式，跳过统一进程站点服务自动恢复");
            return;
        }
        log.info("开始自动恢复站点服务...");

        // 查询所有 RUNNING 状态的站点
        List<SiteProjectEntity> runningSites =
                projectMapper.selectList(
                        new LambdaQueryWrapper<SiteProjectEntity>()
                                .eq(SiteProjectEntity::getSiteServiceStatus, STATUS_RUNNING)
                                .isNotNull(SiteProjectEntity::getSiteServicePort));

        for (SiteProjectEntity site : runningSites) {
            try {
                if (servers.containsKey(site.getId()) && servers.get(site.getId()).isRunning()) {
                    continue;
                }
                Integer port = site.getSiteServicePort();
                if (!ServicePortRange.isValid(port)) {
                    log.error("站点端口无效，无法恢复站点服务: siteId={}, port={}", site.getId(), port);
                    continue;
                }
                if (!isPortAvailable(port)) {
                    log.error("端口被占用，无法恢复站点服务: siteId={}, port={}", site.getId(), port);
                    continue;
                }
                if (storageService.getActivePath(site.getProjectCode()).isEmpty()) {
                    storageService.activate(site.getProjectCode(), site.getUiPackageVersion());
                }
                startSiteInternal(site);
                log.info("自动恢复站点服务: siteId={}, port={}", site.getId(), port);
            } catch (Exception e) {
                log.error("自动恢复站点服务失败: siteId={}", site.getId(), e);
            }
        }

        log.info("站点服务自动恢复完成，恢复数量: {}", servers.size());
    }

    /** 启动站点服务 */
    public SiteServiceStatus startSite(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);

        // 检查是否已在运行
        if (servers.containsKey(siteId)) {
            SiteHttpServer existing = servers.get(siteId);
            return new SiteServiceStatus(
                    STATUS_RUNNING, existing.getPort(), existing.getAccessUrl(), null);
        }

        // 检查 UI 包是否存在
        if (storageService.getActivePath(site.getProjectCode()).isEmpty()) {
            return new SiteServiceStatus(STATUS_STOPPED, null, null, "UI Package 未上传或未激活");
        }

        // 分配端口
        Integer port = site.getSiteServicePort();
        if (port != null && !ServicePortRange.isValid(port)) {
            return new SiteServiceStatus(STATUS_STOPPED, port, null, ServicePortRange.MESSAGE);
        }
        if (port == null) {
            port = allocatePort();
            if (port == null) {
                return new SiteServiceStatus(STATUS_STOPPED, null, null, "没有可用端口");
            }
            site.setSiteServicePort(port);
        } else if (!isPortAvailable(port)) {
            // 如果指定的端口被占用，重新分配
            port = allocatePort();
            if (port == null) {
                return new SiteServiceStatus(STATUS_STOPPED, null, null, "没有可用端口");
            }
            site.setSiteServicePort(port);
        }

        try {
            SiteHttpServer server = startSiteInternal(site);

            // 更新数据库状态
            site.setSiteServiceStatus(STATUS_RUNNING);
            projectMapper.updateById(site);

            return new SiteServiceStatus(STATUS_RUNNING, port, server.getAccessUrl(), null);
        } catch (Exception e) {
            log.error("启动站点服务失败: siteId={}", siteId, e);
            return new SiteServiceStatus(STATUS_STOPPED, port, null, "启动失败: " + e.getMessage());
        }
    }

    /** 内部启动站点服务 */
    private SiteHttpServer startSiteInternal(SiteProjectEntity site) {
        Long siteId = site.getId();
        int port = site.getSiteServicePort();

        SiteHttpServer server =
                new SiteHttpServer(
                        siteId,
                        site.getProjectCode(),
                        port,
                        site.getSiteDomain(),
                        storageService,
                        previewService,
                        oneShotMapper,
                        contentMapper,
                        templateStorageService,
                        null,
                        routeRegistry,
                        environment.acceptsProfiles(Profiles.of("dev")),
                        previewAccessGuard,
                        environment.getProperty("cms.site-service.bind-host", "0.0.0.0"),
                        publishedOutputPath(site.getProjectCode()));

        server.setExternalIntegrationOutput(externalIntegrationOutput);
        server.setPublicSearchRoutes(publicSearchRoutes);
        server.setPublicRootRoutes(publicRootRoutes);
        server.start();
        servers.put(siteId, server);
        usedPorts.put(port, siteId);

        return server;
    }

    Path publishedOutputPath(String projectCode) {
        String root = environment.getProperty("cms.html.save-root", System.getProperty("user.dir"));
        return root.isBlank() ? null : Path.of(new SiteStoragePathResolver().siteRootPath(root, projectCode));
    }

    /**
     * 注册 standalone 子进程内自行启动的站点服务实例。
     *
     * <p>独立进程模式下，渲染时资源 URL 解析（CommonUtils.resolveUiUrl）需要能拿到本进程的站点服务地址， 因此子进程启动的 SiteHttpServer
     * 也登记到管理器。
     */
    public void registerStandaloneServer(Long siteId, SiteHttpServer server) {
        servers.put(siteId, server);
        usedPorts.put(server.getPort(), siteId);
    }

    /** 停止站点服务 */
    public SiteServiceStatus stopSite(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);

        SiteHttpServer server = servers.remove(siteId);
        if (server != null) {
            server.stop();
            usedPorts.remove(server.getPort());
        }

        // 更新数据库状态
        site.setSiteServiceStatus(STATUS_STOPPED);
        projectMapper.updateById(site);

        return new SiteServiceStatus(STATUS_STOPPED, site.getSiteServicePort(), null, null);
    }

    /**
     * 设置站点服务端口
     *
     * <p>校验端口范围与占用情况；运行中则先停止旧服务再重启到新端口，否则仅更新配置。
     */
    public SiteServiceStatus setPort(Long siteId, int port) {
        SiteProjectEntity site = requireSite(siteId);

        if (!ServicePortRange.isValid(port)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED, site.getSiteServicePort(), null, ServicePortRange.MESSAGE);
        }

        Long ownerSiteId = usedPorts.get(port);
        if (ownerSiteId != null && !ownerSiteId.equals(siteId)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED,
                    site.getSiteServicePort(),
                    null,
                    "端口 " + port + " 已被站点 " + ownerSiteId + " 占用");
        }

        // 端口未被本管理器占用时，检查系统端口是否可用
        if (ownerSiteId == null && !isPortAvailable(port)) {
            return new SiteServiceStatus(
                    STATUS_STOPPED, site.getSiteServicePort(), null, "端口 " + port + " 不可用");
        }

        boolean wasRunning = servers.containsKey(siteId);

        // 运行中先停止旧服务
        if (wasRunning) {
            SiteHttpServer oldServer = servers.remove(siteId);
            if (oldServer != null) {
                oldServer.stop();
                usedPorts.remove(oldServer.getPort());
            }
        }

        site.setSiteServicePort(port);

        if (wasRunning) {
            try {
                SiteHttpServer server = startSiteInternal(site);
                site.setSiteServiceStatus(STATUS_RUNNING);
                projectMapper.updateById(site);
                return new SiteServiceStatus(STATUS_RUNNING, port, server.getAccessUrl(), null);
            } catch (Exception e) {
                log.error("重启站点服务失败: siteId={}", siteId, e);
                site.setSiteServiceStatus(STATUS_STOPPED);
                projectMapper.updateById(site);
                return new SiteServiceStatus(STATUS_STOPPED, port, null, "重启失败: " + e.getMessage());
            }
        }

        projectMapper.updateById(site);
        String status =
                site.getSiteServiceStatus() == null ? STATUS_STOPPED : site.getSiteServiceStatus();
        return new SiteServiceStatus(status, port, null, null);
    }

    /** 获取站点服务状态 */
    public SiteServiceStatus getStatus(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        return getStatus(site);
    }

    /** 获取站点服务状态（复用已加载的站点实体，避免重复查询数据库） */
    public SiteServiceStatus getStatus(SiteProjectEntity site) {
        Long siteId = site.getId();

        SiteHttpServer server = servers.get(siteId);
        if (server != null && server.isRunning()) {
            return new SiteServiceStatus(
                    STATUS_RUNNING, server.getPort(), server.getAccessUrl(), null);
        }

        String status = site.getSiteServiceStatus();
        if (status == null) {
            status = STATUS_STOPPED;
        }

        return new SiteServiceStatus(status, site.getSiteServicePort(), null, null);
    }

    /** 获取所有运行中的服务 */
    public Map<Long, SiteHttpServer> getRunningServers() {
        return new ConcurrentHashMap<>(servers);
    }

    /** 重新加载站点路由 */
    public boolean reloadRoutes(Long siteId) {
        SiteHttpServer server = servers.get(siteId);
        if (server != null && server.isRunning()) {
            server.reloadRoutes();
            return true;
        }
        return false;
    }

    /** 获取站点路由列表 */
    public List<SiteHttpServer.RouteInfo> getRoutes(Long siteId) {
        SiteHttpServer server = servers.get(siteId);
        if (server != null && server.isRunning()) {
            return server.getRoutes();
        }
        return List.of();
    }

    /** 获取站点路由冲突列表 */
    public List<SiteHttpServer.RouteConflict> getRouteConflicts(Long siteId) {
        SiteHttpServer server = servers.get(siteId);
        if (server != null && server.isRunning()) {
            return server.getRouteConflicts();
        }
        return List.of();
    }

    /** 分配可用端口 */
    private Integer allocatePort() {
        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            if (!usedPorts.containsKey(port) && isPortAvailable(port)) {
                return port;
            }
        }
        return null;
    }

    /** 检查端口是否可用 */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 应用关闭时停止所有服务 */
    @PreDestroy
    public void destroy() {
        log.info("停止所有站点服务...");

        for (Map.Entry<Long, SiteHttpServer> entry : servers.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                log.error("停止站点服务失败: siteId={}", entry.getKey(), e);
            }
        }

        servers.clear();
        usedPorts.clear();
        log.info("所有站点服务已停止");
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点 ID 不能为空");
        }
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在");
        }
        return site;
    }
}

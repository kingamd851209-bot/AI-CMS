package com.xinhongru.ai.agent.opencode;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenCode 服务管理 负责启动、停止和监控 opencode serve 进程 */
@Service
public class OpenCodeService {
    private static final Logger LOG = LoggerFactory.getLogger(OpenCodeService.class);

    @Value("${cms.ai.agent.opencode.path:opencode}")
    private String opencodePath;

    @Value("${cms.ai.agent.opencode.port:3001}")
    private int opencodePort;

    @Value("${cms.ai.agent.opencode.host:127.0.0.1}")
    private String opencodeHost;

    @Value("${cms.ai.agent.opencode.workdir:./}")
    private String opencodeWorkdir;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    /** 启动 OpenCode 服务 */
    public synchronized ServiceStatus startService() {
        if (running.get()) {
            return ServiceStatus.running(getServiceUrl(), "服务已在运行中");
        }

        // 先检查是否已有服务在运行
        if (isServiceHealthy()) {
            running.set(true);
            return ServiceStatus.running(getServiceUrl(), "服务已在运行中");
        }

        try {
            LOG.info(
                    "启动 OpenCode 服务: {} serve --port {} --workdir {}",
                    opencodePath,
                    opencodePort,
                    opencodeWorkdir);

            String workDir =
                    opencodeWorkdir != null && !opencodeWorkdir.isBlank() ? opencodeWorkdir : "./";

            ProcessBuilder pb =
                    new ProcessBuilder(
                            opencodePath,
                            "serve",
                            "--port",
                            String.valueOf(opencodePort),
                            "--hostname",
                            opencodeHost);

            // 设置工作目录
            File workDirFile = new File(workDir);
            if (workDirFile.exists() && workDirFile.isDirectory()) {
                pb.directory(workDirFile);
                LOG.info("OpenCode 工作目录: {}", workDirFile.getAbsolutePath());
            } else {
                LOG.warn("工作目录不存在，使用当前目录: {}", workDir);
            }

            pb.redirectErrorStream(true);

            Process process = pb.start();
            processRef.set(process);

            // 启动日志读取线程
            Thread logThread =
                    new Thread(
                            () -> {
                                try (BufferedReader reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        process.getInputStream(),
                                                        StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        LOG.info("[OpenCode] {}", line);
                                    }
                                } catch (Exception e) {
                                    if (running.get()) {
                                        LOG.error("读取 OpenCode 输出失败", e);
                                    }
                                }
                            },
                            "opencode-log-reader");
            logThread.setDaemon(true);
            logThread.start();

            // 等待服务启动
            int maxWait = 30;
            for (int i = 0; i < maxWait; i++) {
                Thread.sleep(1000);
                if (isServiceHealthy()) {
                    running.set(true);
                    lastError.set(null);
                    LOG.info("OpenCode 服务启动成功: {}", getServiceUrl());
                    return ServiceStatus.running(getServiceUrl(), "服务启动成功");
                }
            }

            // 启动超时
            stopProcess();
            String error = "OpenCode 服务启动超时";
            lastError.set(error);
            return ServiceStatus.failed(error);
        } catch (Exception e) {
            LOG.error("启动 OpenCode 服务失败", e);
            lastError.set(e.getMessage());
            return ServiceStatus.failed("启动失败: " + e.getMessage());
        }
    }

    /** 停止 OpenCode 服务 */
    public synchronized ServiceStatus stopService() {
        // 先停止我们自己启动的进程
        stopProcess();
        running.set(false);

        // 等待服务真正停止
        int maxWait = 10;
        for (int i = 0; i < maxWait; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!isServiceHealthy()) {
                LOG.info("OpenCode 服务已确认停止");
                return ServiceStatus.stopped("服务已停止");
            }
        }

        // 如果服务仍在运行，说明是外部进程
        if (isServiceHealthy()) {
            LOG.warn("外部 OpenCode 服务仍在运行，请手动关闭端口 {}", opencodePort);
            return ServiceStatus.stopped("本地进程已停止，但检测到外部服务仍在运行，请手动关闭端口 " + opencodePort);
        }

        return ServiceStatus.stopped("服务已停止");
    }

    /** 获取服务状态 */
    public ServiceStatus getStatus() {
        if (running.get() && isServiceHealthy()) {
            return ServiceStatus.running(getServiceUrl(), null);
        }

        if (processRef.get() != null && processRef.get().isAlive()) {
            // 进程存在但健康检查失败
            return ServiceStatus.starting(getServiceUrl(), lastError.get());
        }

        // 检查是否有外部服务在运行
        if (isServiceHealthy()) {
            running.set(true);
            return ServiceStatus.running(getServiceUrl(), "外部服务");
        }

        String error = lastError.get();
        if (error != null) {
            return ServiceStatus.failed(error);
        }

        return ServiceStatus.stopped(null);
    }

    /** 获取服务 URL */
    public String getServiceUrl() {
        return "http://" + opencodeHost + ":" + opencodePort;
    }

    /** 检查服务是否健康 */
    public boolean isServiceHealthy() {
        try {
            URL url = new URL(getServiceUrl() + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            // 尝试另一个端点
            try {
                URL url = new URL(getServiceUrl() + "/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                conn.disconnect();
                return code < 500;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private void stopProcess() {
        Process process = processRef.getAndSet(null);

        if (process != null && process.isAlive()) {
            LOG.info("停止 OpenCode 服务...");

            // 先尝试正常停止
            process.destroy();
            try {
                if (process.waitFor(3, TimeUnit.SECONDS)) {
                    LOG.info("OpenCode 服务已正常停止");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 强制停止
            process.destroyForcibly();
            try {
                if (process.waitFor(2, TimeUnit.SECONDS)) {
                    LOG.info("OpenCode 服务已强制停止");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 只处理processRef记录的本服务子进程；绝不按端口探测并终止外部进程。
        LOG.info("自有 OpenCode 进程停止处理完成");
    }

    @PreDestroy
    public void shutdown() {
        stopProcess();
    }

    /** 服务状态记录 */
    public record ServiceStatus(String status, String url, String message, String error) {
        public static ServiceStatus running(String url, String message) {
            return new ServiceStatus("running", url, message, null);
        }

        public static ServiceStatus starting(String url, String error) {
            return new ServiceStatus("starting", url, null, error);
        }

        public static ServiceStatus stopped(String message) {
            return new ServiceStatus("stopped", null, message, null);
        }

        public static ServiceStatus failed(String error) {
            return new ServiceStatus("failed", null, null, error);
        }

        public boolean isRunning() {
            return "running".equals(status);
        }
    }
}

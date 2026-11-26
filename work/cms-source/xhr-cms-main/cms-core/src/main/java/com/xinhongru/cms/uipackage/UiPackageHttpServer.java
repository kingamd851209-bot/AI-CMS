package com.xinhongru.cms.uipackage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.entity.OptionsEntity;
import com.xinhongru.properties.ResourceProperties;
import com.xinhongru.security.preview.PreviewAccessGuard;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.service.option.OptionService;
import com.xinhongru.site.config.ServicePortRange;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UiPackageHttpServer {
    private final UiPackageStorageService storageService;
    private final OptionService optionService;
    private final String externalHost;
    private final int defaultPort;
    private final boolean devProfile;
    private final PreviewAccessGuard previewAccessGuard;
    private HttpServer server;
    private int port;
    private Integer configuredPort;
    private final Map<String, String> activeSites = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private static final String PORT_OPTION_NAME = "ui-package.port";

    @Autowired
    public UiPackageHttpServer(
            UiPackageStorageService storageService,
            OptionService optionService,
            @Value("${cms.ui-package.external-host:}") String externalHost,
            @Value("${cms.ui-package.port:7187}") int defaultPort,
            Environment environment,
            PreviewAccessGuard previewAccessGuard) {
        this.previewAccessGuard = Objects.requireNonNull(previewAccessGuard);
        this.storageService = storageService;
        this.optionService = optionService;
        this.externalHost = externalHost;
        this.defaultPort = ServicePortRange.requireValid(defaultPort);
        this.devProfile = environment != null && environment.acceptsProfiles(Profiles.of("dev"));
    }

    public UiPackageHttpServer(
            UiPackageStorageService storageService,
            String externalHost,
            PreviewAccessGuard previewAccessGuard) {
        this(storageService, null, externalHost, 7187, null, previewAccessGuard);
    }

    public synchronized void start() throws IOException {
        if (running) {
            log.debug("HTTP server already running on port {}", port);
            return;
        }

        int selectedPort = getEffectivePort();
        server = createServer(selectedPort);
        server.start();
        port = selectedPort;

        running = true;
        log.info("UI Package HTTP 服务器启动于端口 {}", port);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }

        server.stop(0);
        running = false;
        activeSites.clear();
        log.info("UI Package HTTP 服务器已停止");
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public void activateSite(String siteCode) {
        activeSites.put(siteCode, siteCode);
        log.info("站点 {} 已激活 UI Package 服务", siteCode);
    }

    public void deactivateSite(String siteCode) {
        activeSites.remove(siteCode);
        log.info("站点 {} 已停用 UI Package 服务", siteCode);
    }

    public int getPort() {
        return port;
    }

    /** API 保存的端口优先于配置文件；运行中返回实际监听端口。 */
    public synchronized int getEffectivePort() {
        if (running) return port;
        if (configuredPort != null) return configuredPort;
        Integer persisted = loadPersistedPort();
        return persisted != null ? persisted : defaultPort;
    }

    public boolean isRunning() {
        return running;
    }

    /** 修改共享预览服务端口；新端口不可用时保持原服务运行。 */
    public synchronized void setPort(int newPort) throws IOException {
        ServicePortRange.requireValid(newPort);
        if (running && newPort == port) {
            persistPort(newPort);
            configuredPort = newPort;
            return;
        }
        // 先绑定新端口，再保存和切换，避免占用冲突中断原服务。
        HttpServer candidate = createServer(newPort);
        try {
            // A stopped service must only probe the port, never serve business resources.
            if (!running) {
                candidate.removeContext("/");
                candidate.createContext(
                        "/",
                        exchange -> {
                            try {
                                exchange.sendResponseHeaders(503, -1);
                            } finally {
                                exchange.close();
                            }
                        });
            }
            // HttpServer.stop does not release a bound, never-started listener.
            candidate.start();
            persistPort(newPort);
        } catch (RuntimeException ex) {
            candidate.stop(0);
            throw ex;
        }
        if (running) {
            server.stop(0);
            server = candidate;
            port = newPort;
        } else {
            candidate.stop(0);
        }
        configuredPort = newPort;
        log.info("UI Package HTTP 服务器端口已设置为 {}", newPort);
    }

    private HttpServer createServer(int selectedPort) throws IOException {
        HttpServer candidate;
        try {
            candidate = HttpServer.create(new InetSocketAddress("0.0.0.0", selectedPort), 0);
        } catch (BindException ex) {
            throw new IOException(
                    "UI Package 预览端口 "
                            + selectedPort
                            + " 已被占用或存在绑定冲突，请通过后台/API 修改预览端口，或调整 cms.ui-package.port 默认端口",
                    ex);
        }
        candidate.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        candidate.createContext("/", this::handleRequest);
        return candidate;
    }

    private Integer loadPersistedPort() {
        if (optionService == null) {
            return null;
        }
        try {
            OptionsEntity option = optionService.getOptionByName(PORT_OPTION_NAME);
            if (option != null && option.getValue() != null && !option.getValue().isBlank()) {
                int p = Integer.parseInt(option.getValue().trim());
                if (ServicePortRange.isValid(p)) {
                    return p;
                }
                log.warn("持久化端口 {} 超出合法范围，忽略", p);
            }
        } catch (Exception e) {
            log.warn("读取持久化 UI Package 端口失败: {}", e.getMessage());
        }
        return null;
    }

    private void persistPort(int port) {
        if (optionService != null) {
            optionService.updateOptionByName(PORT_OPTION_NAME, String.valueOf(port));
        }
    }

    /** 素材访问不依赖 UI 包激活；不隐式启动服务。 */
    public String getResourceBaseUrl(String siteCode) {
        return getResourceBaseUrl(siteCode, null);
    }

    public String getResourceBaseUrl(String siteCode, String siteHost) {
        if (!running) return null;
        PublicStorageRoots.requireSiteCode(siteCode);
        String host =
                devProfile
                        ? "127.0.0.1"
                        : siteHost != null
                                        && !siteHost.isBlank()
                                        && !ResourceProperties.isExplicitDomain(siteHost)
                                ? siteHost
                                : externalHost == null || externalHost.isBlank()
                                        ? "localhost"
                                        : externalHost;
        return "http://" + host + ":" + port + "/" + siteCode + "/";
    }

    public String getAccessUrl(String siteCode) {
        return getAccessUrl(siteCode, null);
    }

    public String getAccessUrl(String siteCode, String siteServiceIp) {
        return activeSites.containsKey(siteCode)
                ? getResourceBaseUrl(siteCode, siteServiceIp)
                : null;
    }

    private static final Pattern SSI_INCLUDE_PATTERN =
            Pattern.compile(
                    "<!--\\s*#include\\s+(file|virtual)\\s*=\\s*[\"']([^\"']+)[\"']\\s*-->",
                    Pattern.CASE_INSENSITIVE);
    private static final int MAX_SSI_DEPTH = 10;

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (previewAccessGuard.isLoginSubmission(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toASCIIString(),
                        previewLoginPath(exchange))
                && handlePreviewAccess(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();

        if (path.startsWith("/assets/global/")) {
            Path root = storageService.publicAssetsRoot();
            Path file = root.resolve(path.substring("/assets/global/".length())).normalize();
            if (!file.startsWith(root) || !UploadDelivery.within(root, file)) {
                sendError(exchange, 404, "File not found");
                return;
            }
            serveFile(exchange, file, null, false);
            return;
        }

        try {
            // 解析路径: /{siteCode}/...
            String[] parts = path.split("/", 3);
            if (parts.length < 2) {
                sendError(exchange, 400, "Invalid path");
                return;
            }

            String siteCode = parts[1];
            if (!siteCode.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
                sendError(exchange, 400, "Invalid site code");
                return;
            }
            String filePath = parts.length > 2 ? parts[2] : "";

            if (filePath.startsWith("assets/upload/")) {
                Path root = storageService.publicSiteAssetsRoot(siteCode);
                Path file = root.resolve(filePath.substring("assets/upload/".length())).normalize();
                if (!file.startsWith(root) || !UploadDelivery.within(root, file)) {
                    sendError(exchange, 404, "File not found");
                    return;
                }
                serveFile(exchange, file, null, false);
                return;
            }

            // 检查站点是否激活
            if (!activeSites.containsKey(siteCode)) {
                sendError(exchange, 404, "Site not active");
                return;
            }

            Path siteRoot = storageService.root().resolve(siteCode).resolve("active");

            // 构建文件路径
            Path file;
            if (filePath.isEmpty() || filePath.endsWith("/")) {
                // 目录请求，查找默认首页
                String indexPath = filePath.isEmpty() ? "index.html" : filePath + "index.html";
                file = siteRoot.resolve(indexPath);
                // 如果 index.html 不存在，尝试 index.aspx
                if (!Files.exists(file)) {
                    indexPath = filePath.isEmpty() ? "index.aspx" : filePath + "index.aspx";
                    file = siteRoot.resolve(indexPath);
                }
            } else {
                file = siteRoot.resolve(filePath);
            }

            // 如果文件不存在，尝试替代扩展名或默认首页
            if (!Files.exists(file)) {
                String filename = file.getFileName().toString().toLowerCase();

                // .aspx/.asp 不存在时，尝试 .html
                if (filename.endsWith(".aspx") || filename.endsWith(".asp")) {
                    String htmlPath = filePath.replaceAll("\\.(aspx|asp)$", ".html");
                    Path htmlFile = siteRoot.resolve(htmlPath);
                    if (Files.exists(htmlFile)) {
                        file = htmlFile;
                    }
                }

                // 仍不存在，尝试作为目录处理
                if (!Files.exists(file)) {
                    if (filePath.isEmpty() || filePath.endsWith("/")) {
                        sendError(exchange, 404, "Index not found");
                        return;
                    }
                    Path indexFile = siteRoot.resolve(filePath).resolve("index.html");
                    if (Files.exists(indexFile)) {
                        file = indexFile;
                    } else {
                        indexFile = siteRoot.resolve(filePath).resolve("index.aspx");
                        if (Files.exists(indexFile)) {
                            file = indexFile;
                        } else {
                            sendError(exchange, 404, "File not found");
                            return;
                        }
                    }
                }
            }

            // 如果是目录
            if (Files.isDirectory(file)) {
                Path indexFile = file.resolve("index.html");
                if (Files.exists(indexFile)) {
                    file = indexFile;
                } else {
                    indexFile = file.resolve("index.aspx");
                    if (Files.exists(indexFile)) {
                        file = indexFile;
                    } else {
                        sendError(exchange, 404, "Index not found");
                        return;
                    }
                }
            }

            serveFile(exchange, file, siteRoot, true);
        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                log.debug("客户端断开连接，跳过响应: {} ({})", path, e.getMessage());
                return;
            }
            log.error("处理 UI Package 请求失败: {}", path, e);
            sendError(exchange, 500, "Internal error");
        } catch (Exception e) {
            log.error("处理 UI Package 请求失败: {}", path, e);
            sendError(exchange, 500, "Internal error");
        }
    }

    private String previewLoginPath(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getRawPath().split("/", 3);
        return (parts.length > 1 ? "/" + parts[1] : "") + PreviewAccessGuard.LOGIN_PATH;
    }

    /** 仅在 HTML 文件输出或密码提交时执行门禁，其他静态资源直接访问。 */
    private boolean handlePreviewAccess(HttpExchange exchange) throws IOException {
        String siteCode = exchange.getRequestURI().getPath().split("/", 3)[1];
        var policy = previewAccessGuard.forSiteCode(siteCode);
        if (policy.enabled()) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String target = exchange.getRequestURI().toASCIIString();
            byte[] body =
                    previewAccessGuard.isLoginSubmission(
                                    exchange.getRequestMethod(), target, previewLoginPath(exchange))
                            ? exchange.getRequestBody()
                                    .readNBytes(PreviewAccessGuard.MAX_FORM_BYTES + 1)
                            : null;
            var cookies = exchange.getRequestHeaders().get("Cookie");
            var response =
                    previewAccessGuard.check(
                            policy,
                            previewLoginPath(exchange),
                            exchange.getRequestMethod(),
                            target,
                            cookies == null ? null : String.join("; ", cookies),
                            port,
                            exchange instanceof HttpsExchange,
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            body);
            if (response != null) {
                response.headers()
                        .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                byte[] content = response.body().getBytes(StandardCharsets.UTF_8);
                boolean noBody = "HEAD".equals(exchange.getRequestMethod()) || content.length == 0;
                exchange.sendResponseHeaders(response.status(), noBody ? -1 : content.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    if (!noBody) output.write(content);
                }
                exchange.close();
                return true;
            }
        }
        return false;
    }

    /**
     * 读取文件内容并返回响应。
     *
     * @param file 要返回的文件
     * @param siteRoot 站点根目录（active 目录），用于 SSI virtual 路径解析；null 表示不处理 SSI
     */
    private void serveFile(HttpExchange exchange, Path file, Path siteRoot, boolean processSsi)
            throws IOException {
        boolean uploaded = siteRoot == null && !processSsi;
        boolean video = UploadDelivery.isVideo(file.getFileName().toString());
        if (video && siteRoot != null && !UploadDelivery.within(siteRoot, file)) {
            sendError(exchange, 404, "File not found");
            return;
        }
        if (uploaded || video) {
            var delivery =
                    UploadDelivery.prepare(file, exchange.getRequestHeaders().getFirst("Range"));
            delivery.headers()
                    .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (!uploaded) exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            exchange.sendResponseHeaders(
                    delivery.status(), head || delivery.length() == 0 ? -1 : delivery.length());
            try (var output = exchange.getResponseBody()) {
                if (!head) delivery.write(output);
            }
            return;
        }
        if (isHtmlFile(file) && handlePreviewAccess(exchange)) {
            return;
        }
        byte[] content = Files.readAllBytes(file);
        String contentType = getContentType(file);

        // 对 HTML/ASP 文件处理 SSI 包含指令
        if (processSsi && siteRoot != null && isHtmlFile(file)) {
            String htmlContent = new String(content, StandardCharsets.UTF_8);
            htmlContent = processSsiIncludes(htmlContent, file.getParent(), siteRoot, 0);
            content = htmlContent.getBytes(StandardCharsets.UTF_8);
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, content.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private boolean isHtmlFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return filename.endsWith(".html")
                || filename.endsWith(".htm")
                || filename.endsWith(".aspx")
                || filename.endsWith(".asp");
    }

    private String processSsiIncludes(String content, Path currentDir, Path siteRoot, int depth) {
        if (depth >= MAX_SSI_DEPTH) {
            log.warn("SSI 包含深度超过限制");
            return content;
        }

        Matcher matcher = SSI_INCLUDE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(content, lastEnd, matcher.start());
            String type = matcher.group(1);
            String includePath = matcher.group(2);

            try {
                Path includeFile;
                if ("file".equalsIgnoreCase(type)) {
                    // file: 相对于当前文件所在目录
                    includeFile = currentDir.resolve(includePath).normalize();
                } else {
                    // virtual: 相对于站点根目录
                    includeFile = siteRoot.resolve(includePath).normalize();
                }

                if (Files.exists(includeFile) && includeFile.startsWith(siteRoot)) {
                    String includeContent = Files.readString(includeFile, StandardCharsets.UTF_8);
                    // 递归处理包含文件中的 SSI 指令
                    includeContent =
                            processSsiIncludes(
                                    includeContent, includeFile.getParent(), siteRoot, depth + 1);
                    result.append(includeContent);
                } else {
                    // 文件不存在，保留原始指令（作为注释）
                    result.append(matcher.group(0));
                }
            } catch (Exception e) {
                log.warn("处理 SSI 包含失败: {}", includePath, e);
                result.append(matcher.group(0));
            }

            lastEnd = matcher.end();
        }

        result.append(content.substring(lastEnd));
        return result.toString();
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] content = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private boolean isClientDisconnect(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("Broken pipe")
                        || message.contains("Connection reset by peer"));
    }

    private String getContentType(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        if (filename.endsWith(".html")
                || filename.endsWith(".htm")
                || filename.endsWith(".aspx")
                || filename.endsWith(".asp")) {
            return "text/html; charset=utf-8";
        } else if (filename.endsWith(".css")) {
            return "text/css; charset=utf-8";
        } else if (filename.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        } else if (filename.endsWith(".json")) {
            return "application/json; charset=utf-8";
        } else if (filename.endsWith(".png")) {
            return "image/png";
        } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (filename.endsWith(".gif")) {
            return "image/gif";
        } else if (filename.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (filename.endsWith(".ico")) {
            return "image/x-icon";
        } else if (filename.endsWith(".woff")) {
            return "font/woff";
        } else if (filename.endsWith(".woff2")) {
            return "font/woff2";
        } else if (filename.endsWith(".ttf")) {
            return "font/ttf";
        } else {
            return "application/octet-stream";
        }
    }
}

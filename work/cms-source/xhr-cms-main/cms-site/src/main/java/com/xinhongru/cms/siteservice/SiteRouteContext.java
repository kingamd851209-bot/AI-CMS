package com.xinhongru.cms.siteservice;

import com.alibaba.fastjson.JSON;
import com.xinhongru.cms.template.TemplatePreviewService;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

import org.thymeleaf.context.Context;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 站点路由上下文 —— 自定义路由处理器与站点服务之间的桥梁。
 *
 * <p>封装了站点信息、模板渲染、响应输出等能力，自定义处理器通过此对象与站点服务交互， 无需直接操作 Undertow API。
 *
 * <h3>使用示例</h3>
 *
 * <pre>{@code
 * // 在 CustomRouteHandler 中：
 * String html = ctx.render("list_news", context -> {
 *     context.setVariable("newsList", myService.getNews());
 *     context.setVariable("title", "新闻列表");
 * });
 * ctx.sendHtml(exchange, html);
 * }</pre>
 */
public class SiteRouteContext {
    private static final HttpString ACCESS_CONTROL_ALLOW_ORIGIN =
            new HttpString("Access-Control-Allow-Origin");

    private final com.xinhongru.cms.integration.SiteExternalIntegrationOutput
            externalIntegrationOutput;
    private final Long siteId;
    private final String projectCode;
    private final TemplatePreviewService previewService;
    private final Predicate<HttpServerExchange> pageAccessAllowed;

    public SiteRouteContext(
            Long siteId,
            String projectCode,
            TemplatePreviewService previewService,
            Predicate<HttpServerExchange> pageAccessAllowed) {
        this(siteId, projectCode, previewService, pageAccessAllowed, null);
    }

    public SiteRouteContext(
            Long siteId,
            String projectCode,
            TemplatePreviewService previewService,
            Predicate<HttpServerExchange> pageAccessAllowed,
            com.xinhongru.cms.integration.SiteExternalIntegrationOutput externalIntegrationOutput) {
        this.externalIntegrationOutput = externalIntegrationOutput;
        this.siteId = siteId;
        this.projectCode = projectCode;
        this.previewService = previewService;
        this.pageAccessAllowed = Objects.requireNonNull(pageAccessAllowed);
    }

    /** 当前站点 ID */
    public Long siteId() {
        return siteId;
    }

    /** 当前站点编码（对应模板目录 runtime/template/{projectCode}/） */
    public String projectCode() {
        return projectCode;
    }

    /** 获取模板目录绝对路径（runtime/template/{projectCode}/）。 自定义控制器可据此读取模板文件内容或进行其他文件操作。 */
    public Path templateDir() {
        return previewService.getPublicTemplateDir(projectCode);
    }

    // ==================== 模板渲染 ====================

    /**
     * 使用完全自定义的 Context 渲染模板 —— 这是自定义路由的核心扩展方法。
     *
     * <p>模板路径自动从当前站点的模板目录解析（runtime/template/{projectCode}/{templateName}.html）。 Context
     * 已预填站点基础变量（siteId, site, siteConfig, commonUtils, CommonUtils, projectCode,
     * ThymeleafEvaluationContext），调用方只需在 {@code contextBuilder} 中添加业务变量即可。
     *
     * <h3>自动注入的基础变量</h3>
     *
     * <ul>
     *   <li>{@code siteId} — Long
     *   <li>{@code site} / {@code siteConfig} — SiteProjectEntity
     *   <li>{@code commonUtils} / {@code CommonUtils} — CommonUtils 实例（支持模板中 @CommonUtils.method()
     *       调用）
     *   <li>{@code projectCode} — String
     *   <li>ThymeleafEvaluationContext — 支持 @beanName 语法
     * </ul>
     *
     * <pre>{@code
     * String html = ctx.render("column-3/index", context -> {
     *     // 复杂数据绑定：跨多表查询、聚合计算等
     *     context.setVariable("newsList", newsMapper.getByCategory(100, 1, 10));
     *     context.setVariable("readCounts", statsMapper.getReadCounts(...));
     *     context.setVariable("hotTags", tagMapper.getHotTags(100, 20));
     *     context.setVariable("title", "自定义标题");
     *     context.setVariable("keywords", "关键词");
     * });
     * }</pre>
     *
     * @param templateName 模板名（不含 .html 后缀），如 "list_news"、"column-3/index"
     * @param contextBuilder 自定义 Context 构建逻辑，在基础变量之上添加业务变量
     * @return 渲染后的 HTML 字符串
     */
    public String render(String templateName, Consumer<Context> contextBuilder) {
        return previewService.renderWithCustomContext(siteId, templateName, contextBuilder);
    }

    // ==================== 响应输出（封装 Undertow 细节） ====================

    /** 在任何公开页面数据查询之前复用站点预发布访问门禁。 */
    public boolean allowPageAccess(HttpServerExchange exchange) {
        return pageAccessAllowed.test(exchange);
    }

    /** 发送 HTML 响应（Content-Type: text/html; charset=UTF-8） */
    public void sendHtml(HttpServerExchange exchange, String html) {
        if (!pageAccessAllowed.test(exchange)) {
            return;
        }
        if (externalIntegrationOutput != null)
            html = externalIntegrationOutput.decorate(siteId, html, exchange.getStatusCode());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        exchange.getResponseSender().send(html);
    }

    /** 发送 JSON 响应（Content-Type: application/json; charset=UTF-8） */
    public void sendJson(HttpServerExchange exchange, Object data) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=UTF-8");
        exchange.getResponseHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        exchange.getResponseSender().send(JSON.toJSONString(data));
    }

    /** 发送 404 */
    public void sendNotFound(HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.NOT_FOUND);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=UTF-8");
        exchange.getResponseSender().send("Not Found");
    }

    /** 302 重定向 */
    public void redirect(HttpServerExchange exchange, String location) {
        exchange.setStatusCode(StatusCodes.FOUND);
        exchange.getResponseHeaders().put(Headers.LOCATION, location);
    }

    /** 发送错误响应 */
    public void sendError(HttpServerExchange exchange, int statusCode, String message) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        String errorHtml =
                "<!DOCTYPE html><html><head><meta"
                        + " charset=\"UTF-8\"><title>Error</title></head><body"
                        + " style=\"margin:0;padding:20px;background:#f5f5f5;\"><div"
                        + " style=\"padding:20px;background:#fff3f3;border:1px solid"
                        + " #ffcdd2;border-radius:8px;\"><h3 style=\"color:#c62828;margin:0 0"
                        + " 10px;\">Error "
                        + statusCode
                        + "</h3>"
                        + "<p style=\"color:#666;margin:0;\">"
                        + escapeHtml(message)
                        + "</p>"
                        + "</div></body></html>";
        exchange.getResponseSender().send(errorHtml);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public String toString() {
        return "SiteRouteContext{siteId=" + siteId + ", projectCode='" + projectCode + "'}";
    }
}

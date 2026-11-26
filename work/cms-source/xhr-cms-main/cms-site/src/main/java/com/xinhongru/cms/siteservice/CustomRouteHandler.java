package com.xinhongru.cms.siteservice;

import io.undertow.server.HttpServerExchange;

/**
 * 自定义路由处理器 —— 函数式接口。
 *
 * <p>在默认路由（静态文件、栏目列表页、内容详情页）之前执行。 返回 {@code true} 表示已处理该请求，返回 {@code false} 则继续走默认路由链。
 *
 * <pre>{@code
 * // Lambda 方式注册：
 * registry.register("SITE_A", "/custom/{id}", (path, exchange, ctx) -> {
 *     String html = ctx.render("custom_page", context -> {
 *         context.setVariable("data", myService.getData());
 *     });
 *     ctx.sendHtml(exchange, html);
 *     return true;
 * });
 * }</pre>
 */
@FunctionalInterface
public interface CustomRouteHandler {
    /**
     * @param normalizedPath 规范化路径（无前后 /），如 "news/123"
     * @param exchange Undertow 请求交换对象
     * @param ctx 站点路由上下文（提供模板渲染、响应输出等能力）
     * @return true=已处理该请求；false=未匹配，交给默认路由
     */
    boolean handle(String normalizedPath, HttpServerExchange exchange, SiteRouteContext ctx)
            throws Exception;
}

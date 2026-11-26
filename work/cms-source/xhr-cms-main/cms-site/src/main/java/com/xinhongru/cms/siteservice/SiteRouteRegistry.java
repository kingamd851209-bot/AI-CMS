package com.xinhongru.cms.siteservice;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自定义路由注册表 —— 管理所有自定义路由处理器。
 *
 * <p>支持三种注册方式：
 *
 * <ul>
 *   <li><b>站点专属</b>：{@code register("SITE_A", "/news/{id}", handler)} — 仅对指定站点生效
 *   <li><b>多站点批量</b>：{@code register(new String[]{"SITE_A","SITE_B"}, "/news", handler)} —
 *       对多个指定站点生效
 *   <li><b>全局</b>：{@code registerGlobal("/api/status", handler)} — 对所有站点生效
 * </ul>
 *
 * URL 模式支持占位符：{@code {id}}, {@code {classId}}, {@code {pageIndex}}, {@code {pageSize}}, {@code
 * {parentId}}。 支持运行时动态注册/注销。
 *
 * <h3>多站点场景说明</h3>
 *
 * <p>同一个 URL 模式可以为不同站点注册不同的处理器，实现"同 URL，不同站点，不同数据/模板"的效果。 例如站点 A 和站点 B 都有 {@code /news/{pageIndex}}
 * 路由，但查询的数据源和渲染的模板各不相同。
 *
 * <h3>使用示例</h3>
 *
 * <pre>{@code
 * @Component
 * public class MyController {
 *     @Resource
 *     private SiteRouteRegistry routeRegistry;
 *
 *     @PostConstruct
 *     public void init() {
 *         // 1. 注册到单个站点
 *         routeRegistry.register("SITE_A", "/news/{pageIndex}", this::siteNews);
 *
 *         // 2. 注册到多个站点
 *         routeRegistry.register(new String[]{"SITE_A", "SITE_B"}, "/about", this::aboutPage);
 *
 *         // 3. 注册到所有站点
 *         routeRegistry.registerGlobal("/api/status", this::statusApi);
 *     }
 * }
 * }</pre>
 *
 * @see CustomRouteHandler
 * @see RouteHandlerEntry
 */
@Slf4j
@Component
public class SiteRouteRegistry {
    /** 站点专属处理器：siteProjectCode → 处理器列表 */
    private final Map<String, List<RouteHandlerEntry>> siteHandlers = new ConcurrentHashMap<>();

    /** 全局处理器（对所有站点生效） */
    private final List<RouteHandlerEntry> globalHandlers = new CopyOnWriteArrayList<>();

    /**
     * 注册自定义路由处理器到指定站点。
     *
     * <p>处理器仅对 projectCode 匹配的站点生效。当请求到达时， {@link SiteHttpServer} 会根据当前站点的 projectCode
     * 从注册表取出对应的处理器列表。
     *
     * <h3>多站点隔离示例</h3>
     *
     * <pre>{@code
     * // 站点 A 的新闻页：查询 A 的数据，渲染 A 的模板
     * routeRegistry.register("SITE_A", "/news/{pageIndex}", (path, ex, ctx) -> {
     *     String html = ctx.render("site_a_news", c -> c.setVariable("data", aService.getNews()));
     *     ctx.sendHtml(ex, html);
     *     return true;
     * });
     *
     * // 站点 B 的新闻页：查询 B 的数据，渲染 B 的模板（同一个 URL 模式，不同实现）
     * routeRegistry.register("SITE_B", "/news/{pageIndex}", (path, ex, ctx) -> {
     *     String html = ctx.render("site_b_news", c -> c.setVariable("data", bService.getNews()));
     *     ctx.sendHtml(ex, html);
     *     return true;
     * });
     * }</pre>
     *
     * @param siteProjectCode 站点 projectCode（如 "SITE_A"），不能为空
     * @param urlPattern URL 模式（如 "/news/{pageIndex}"），支持占位符
     * @param handler 处理器
     */
    public void register(String siteProjectCode, String urlPattern, CustomRouteHandler handler) {
        if (siteProjectCode == null || siteProjectCode.isBlank()) {
            throw new IllegalArgumentException("siteProjectCode 不能为空");
        }
        RouteHandlerEntry entry = new RouteHandlerEntry(urlPattern, handler);
        siteHandlers.computeIfAbsent(siteProjectCode, k -> new CopyOnWriteArrayList<>()).add(entry);
        log.info("注册自定义路由: site={}, pattern={}", siteProjectCode, urlPattern);
    }

    /**
     * 批量注册自定义路由处理器到多个指定站点。
     *
     * <p>适用于多个站点共用同一套处理器逻辑、但不想对所有站点生效的场景。 等价于对每个 siteProjectCode 分别调用 {@link #register}。
     *
     * <pre>{@code
     * // "关于我们"页面在多个站点中逻辑相同
     * routeRegistry.register(new String[]{"SITE_A", "SITE_B", "SITE_C"},
     *     "/about", this::aboutPage);
     * }</pre>
     *
     * @param siteProjectCodes 站点 projectCode 数组，不能为空
     * @param urlPattern URL 模式，支持占位符
     * @param handler 处理器
     */
    public void register(String[] siteProjectCodes, String urlPattern, CustomRouteHandler handler) {
        if (siteProjectCodes == null || siteProjectCodes.length == 0) {
            throw new IllegalArgumentException("siteProjectCodes 不能为空");
        }
        for (String code : siteProjectCodes) {
            register(code, urlPattern, handler);
        }
    }

    /**
     * 注册全局自定义路由处理器（对所有站点生效）。
     *
     * @param urlPattern URL 模式，支持占位符
     * @param handler 处理器
     */
    public void registerGlobal(String urlPattern, CustomRouteHandler handler) {
        RouteHandlerEntry entry = new RouteHandlerEntry(urlPattern, handler);
        globalHandlers.add(entry);
        log.info("注册全局自定义路由: pattern={}", urlPattern);
    }

    /**
     * 注销指定站点的自定义路由。
     *
     * @param siteProjectCode 站点 projectCode
     * @param urlPattern 要注销的 URL 模式
     */
    public void unregister(String siteProjectCode, String urlPattern) {
        List<RouteHandlerEntry> handlers = siteHandlers.get(siteProjectCode);
        if (handlers != null) {
            handlers.removeIf(entry -> entry.getUrlPattern().equals(urlPattern));
            log.info("注销自定义路由: site={}, pattern={}", siteProjectCode, urlPattern);
        }
    }

    /**
     * 注销全局自定义路由。
     *
     * @param urlPattern 要注销的 URL 模式
     */
    public void unregisterGlobal(String urlPattern) {
        globalHandlers.removeIf(entry -> entry.getUrlPattern().equals(urlPattern));
        log.info("注销全局自定义路由: pattern={}", urlPattern);
    }

    /**
     * 获取指定站点的处理器列表（全局处理器 + 站点专属处理器）。
     *
     * @param siteProjectCode 站点 projectCode
     * @return 处理器列表（全局处理器在前，站点专属在后）
     */
    public List<RouteHandlerEntry> getHandlers(String siteProjectCode) {
        List<RouteHandlerEntry> result = new ArrayList<>(globalHandlers);
        List<RouteHandlerEntry> siteSpecific = siteHandlers.get(siteProjectCode);
        if (siteSpecific != null) {
            result.addAll(siteSpecific);
        }
        return result;
    }

    /** 获取已注册站点专属处理器的站点数量。 */
    public int getSiteCount() {
        return siteHandlers.size();
    }

    /** 获取全局处理器数量。 */
    public int getGlobalCount() {
        return globalHandlers.size();
    }

    /**
     * 获取指定站点的专属处理器数量。
     *
     * @param siteProjectCode 站点编码
     * @return 处理器数量（不含全局处理器）
     */
    public int getSiteHandlerCount(String siteProjectCode) {
        List<RouteHandlerEntry> handlers = siteHandlers.get(siteProjectCode);
        return handlers == null ? 0 : handlers.size();
    }

    /**
     * 获取所有已注册站点专属处理器的站点编码列表。 用于调试和运维：查看哪些站点配置了自定义路由。
     *
     * @return 站点编码集合（不可修改）
     */
    public Set<String> getRegisteredSites() {
        return Collections.unmodifiableSet(siteHandlers.keySet());
    }

    /**
     * 判断指定站点是否注册了站点专属处理器。
     *
     * @param siteProjectCode 站点编码
     * @return true=有专属处理器
     */
    public boolean hasSiteHandlers(String siteProjectCode) {
        List<RouteHandlerEntry> handlers = siteHandlers.get(siteProjectCode);
        return handlers != null && !handlers.isEmpty();
    }

    /**
     * 获取指定站点的专属处理器列表（不含全局处理器）。 主要用于调试和运维查看。
     *
     * @param siteProjectCode 站点编码
     * @return 处理器列表（可能为空，不可修改）
     */
    public List<RouteHandlerEntry> getSiteHandlers(String siteProjectCode) {
        List<RouteHandlerEntry> handlers = siteHandlers.get(siteProjectCode);
        if (handlers == null) {
            return List.of();
        }
        return List.copyOf(handlers);
    }
}

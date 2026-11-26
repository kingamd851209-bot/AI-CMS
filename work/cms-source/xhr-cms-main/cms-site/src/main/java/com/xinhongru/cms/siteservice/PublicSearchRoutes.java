package com.xinhongru.cms.siteservice;

import com.xinhongru.cms.search.*;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 固定本站搜索表单和JSON入口；不读取请求中的站点身份。 */
@Component
public class PublicSearchRoutes {
    private final PublicSearchService search;

    public PublicSearchRoutes(PublicSearchService search) {
        this.search = search;
    }

    public boolean handle(String path, HttpServerExchange exchange, SiteRouteContext ctx) {
        if (!path.equals("__cms/search") && !path.equals("__cms/search.json")) return false;
        if (!ctx.allowPageAccess(exchange)) return true;
        if (!Methods.GET.equals(exchange.getRequestMethod())) {
            exchange.setStatusCode(405);
            ctx.sendJson(exchange, Map.of("code", 405, "message", "搜索只接受GET"));
            return true;
        }
        try {
            String requested = parameter(exchange, "siteId", null);
            Long requestedSite = requested == null ? null : Long.valueOf(requested);
            if (requestedSite != null && !requestedSite.equals(ctx.siteId()))
                throw new IllegalArgumentException("搜索站点不匹配");
            String keyword = parameter(exchange, "q", "").trim();
            int page = Integer.parseInt(parameter(exchange, "page", "1"));
            int size = Integer.parseInt(parameter(exchange, "size", "10"));
            String kind = parameter(exchange, "kind", "all");
            if (!kind.equals("all") && !kind.equals("content"))
                throw new IllegalArgumentException("结果类型无效");
            Integer menu = integerParameter(exchange, "menuId");
            if (menu != null && menu <= 0) throw new IllegalArgumentException("栏目无效");
            String legacyValue = parameter(exchange, "legacy", "0");
            if (!legacyValue.equals("0") && !legacyValue.equals("1"))
                throw new IllegalArgumentException("兼容模式无效");
            boolean legacy = legacyValue.equals("1");
            if (legacy && !kind.equals("content"))
                throw new IllegalArgumentException("兼容模式只返回内容记录");
            var query =
                    (legacy
                                    ? PublicSearchQuery.forLegacy(
                                            ctx.siteId(), requestedSite, keyword, page, size)
                                    : PublicSearchQuery.of(
                                            ctx.siteId(),
                                            requestedSite,
                                            keyword.isEmpty() ? "_" : keyword,
                                            page,
                                            size))
                            .withDates(
                                    integerParameter(exchange, "year"),
                                    integerParameter(exchange, "month"));
            com.xinhongru.common.page.PageResult<PublicSearchService.Hit> result;
            if (keyword.isEmpty() && !legacy)
                result = new com.xinhongru.common.page.PageResult<>(List.of(), 0, 1, query.size());
            else result = search.search(query, menu, kind.equals("content"));
            if (path.endsWith(".json")) ctx.sendJson(exchange, json(result));
            else ctx.sendHtml(exchange, html(keyword, result, query, menu, kind));
        } catch (IllegalArgumentException invalid) {
            exchange.setStatusCode(400);
            ctx.sendJson(exchange, Map.of("code", 400, "message", "搜索参数无效"));
        }
        return true;
    }

    private static String parameter(HttpServerExchange ex, String name, String fallback) {
        var values = ex.getQueryParameters().get(name);
        if (values == null) return fallback;
        if (values.size() != 1) throw new IllegalArgumentException("参数重复");
        return values.getFirst();
    }

    private static Integer integerParameter(HttpServerExchange ex, String name) {
        String value = parameter(ex, name, null);
        return value == null ? null : Integer.valueOf(value);
    }

    private static Map<String, Object> json(
            com.xinhongru.common.page.PageResult<PublicSearchService.Hit> result) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var hit : result.list()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", hit.kind());
            row.put("id", hit.id());
            row.put("menuId", hit.menuId());
            row.put("title", hit.title());
            row.put("url", hit.url());
            row.put(
                    "snippet",
                    hit.snippet().stream()
                            .map(p -> Map.of("text", p.text(), "hit", p.hit()))
                            .toList());
            rows.add(row);
        }
        return Map.of(
                "code",
                0,
                "data",
                Map.of(
                        "list",
                        rows,
                        "total",
                        result.total(),
                        "pageNum",
                        result.pageNum(),
                        "pageSize",
                        result.pageSize(),
                        "pages",
                        result.getPages()));
    }

    private static String html(
            String keyword,
            com.xinhongru.common.page.PageResult<PublicSearchService.Hit> result,
            PublicSearchQuery query,
            Integer menu,
            String kind) {
        Document doc =
                Jsoup.parse(
                        "<!doctype html><html lang=zh-CN><head><meta"
                                + " charset=UTF-8><title>站内搜索</title></head><body></body></html>");
        var body = doc.body();
        body.appendElement("h1").text("站内搜索");
        var form = body.appendElement("form").attr("method", "get").attr("action", "/__cms/search");
        var label = form.appendElement("label").text("关键词 ");
        label.appendElement("input")
                .attr("name", "q")
                .attr("value", keyword)
                .attr("maxlength", "100");
        form.appendElement("input").attr("type", "hidden").attr("name", "kind").attr("value", kind);
        form.appendElement("input")
                .attr("type", "hidden")
                .attr("name", "size")
                .attr("value", Integer.toString(query.size()));
        if (menu != null)
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "menuId")
                    .attr("value", menu.toString());
        if (query.year() != null)
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "year")
                    .attr("value", query.year().toString());
        if (query.month() != null)
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "month")
                    .attr("value", query.month().toString());
        if (query.keyword().isEmpty())
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "legacy")
                    .attr("value", "1");
        form.appendElement("button").attr("type", "submit").text("搜索");
        body.appendElement("p").text("共" + result.total() + "条结果");
        for (var hit : result.list()) {
            var article = body.appendElement("article");
            article.appendElement("p").text(hit.kind().equals("singlePage") ? "单页" : "内容");
            article.appendElement("h2")
                    .appendElement("a")
                    .attr("href", hit.url())
                    .text(hit.title() == null ? "" : hit.title());
            var snippet = article.appendElement("p");
            for (var part : hit.snippet())
                if (part.hit()) snippet.appendElement("mark").text(part.text());
                else snippet.appendText(part.text());
        }
        var nav = body.appendElement("nav").attr("aria-label", "搜索分页");
        if (result.pageNum() > 1)
            nav.appendElement("a")
                    .attr("href", pageUrl(query, menu, kind, result.pageNum() - 1))
                    .text("上一页");
        if ((long) result.pageNum() * result.pageSize() < result.total())
            nav.appendElement("a")
                    .attr("href", pageUrl(query, menu, kind, result.pageNum() + 1))
                    .text("下一页");
        return doc.outerHtml();
    }

    private static String pageUrl(PublicSearchQuery query, Integer menu, String kind, int page) {
        String url =
                "/__cms/search?q="
                        + URLEncoder.encode(query.keyword(), StandardCharsets.UTF_8)
                        + "&page="
                        + page
                        + "&size="
                        + query.size()
                        + "&kind="
                        + kind;
        if (query.keyword().isEmpty()) url += "&legacy=1";
        if (menu != null) url += "&menuId=" + menu;
        if (query.year() != null) url += "&year=" + query.year();
        if (query.month() != null) url += "&month=" + query.month();
        return url;
    }
}

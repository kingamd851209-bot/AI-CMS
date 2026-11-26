package com.xinhongru.cms.siteservice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/** 将已审核的旧页头搜索控件接入本站的公开搜索。 */
final class ReviewedHeaderSearch {
    private static final Set<String> LEGACY_ACTIONS = Set.of(
            "", "window.location.href = '../other/search.aspx'",
            "window.location.href = 'other/search.aspx'", "window.location.href = '/other/search.aspx'");
    private final JsonNode releases;

    ReviewedHeaderSearch(Path packageRoot, String projectCode) {
        Path config = packageRoot.resolve(".reviewed-header-search.json");
        releases = Files.isRegularFile(config)
                ? new ObjectMapper().readTree(config.toFile()).path(projectCode)
                : MissingNode.getInstance();
    }

    boolean configured() {
        return releases.isObject() && !releases.isEmpty();
    }

    String apply(String html, Path activePackage, String route) throws IOException {
        if (!configured()) return html;
        var rules = releases.path(activePackage.toRealPath().getFileName().toString());
        if (!rules.path("reason").isString() || rules.path("reason").asString().isBlank()) return html;
        boolean approved = false;
        for (var path : rules.path("routes")) approved |= route.equals(path.asString());
        if (!approved) return html;

        var doc = Parser.htmlParser().setTrackPosition(true).parseInput(html, "");
        var boxes = doc.select("header .search_wrap");
        if (boxes.size() != 1) return html;
        var box = boxes.first();
        if (!box.is("div") || box.childrenSize() != 2 || !box.className().equals("search_wrap")) return html;
        Element input = box.child(0);
        Element button = box.child(1);
        if (!input.is("input.search_box_text[type=text]") || input.hasAttr("name")
                || !button.is("button.search_box_button") || button.childrenSize() != 1
                || !button.child(0).is("i.icon-sousuo") || !LEGACY_ACTIONS.contains(button.attr("onclick"))) {
            return html;
        }
        var start = box.sourceRange();
        var end = box.endSourceRange();
        if (!start.isTracked() || !end.isTracked()) return html;
        String form = "<form class=\"search_wrap\" action=\"/__cms/search\" method=\"get\" data-cms-header-search>"
                + "<input name=\"q\" type=\"search\" class=\"search_box_text\" placeholder=\"请输入内容\""
                + " aria-label=\"搜索关键词\" maxlength=\"100\" required>"
                + "<button type=\"submit\" class=\"search_box_button fnt_20\" aria-label=\"搜索\">"
                + "<i class=\"icon-sousuo\" aria-hidden=\"true\"></i></button></form>";
        if (doc.select("script[src$=/js/common.js]").isEmpty()) {
            form += "<script src=\"/js/header-search.js\" defer></script>";
        }
        return html.substring(0, start.start().pos()) + form + html.substring(end.end().pos());
    }
}

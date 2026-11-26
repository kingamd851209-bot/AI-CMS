package com.xinhongru.cms.content.record;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.Locale;

/** 扩展正文只保留正文排版；不允许脚本、页面布局、事件及可执行资源。 */
public final class ContentExtensionHtml {
    private ContentExtensionHtml() {}

    public static final String LAYOUT_ATTRIBUTE = "data-cms-editor-layout";

    public static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        if (html.length() > 200000) throw new IllegalArgumentException("正文超过200000字限制");
        Safelist allowed =
                Safelist.none()
                        .addTags(
                                "p",
                                "div",
                                "span",
                                "br",
                                "hr",
                                "h2",
                                "h3",
                                "h4",
                                "h5",
                                "h6",
                                "strong",
                                "b",
                                "em",
                                "i",
                                "u",
                                "s",
                                "blockquote",
                                "pre",
                                "code",
                                "ul",
                                "ol",
                                "li",
                                "table",
                                "thead",
                                "tbody",
                                "tfoot",
                                "tr",
                                "th",
                                "td",
                                "a",
                                "img")
                        .addAttributes(":all", "style")
                        .addAttributes("p", LAYOUT_ATTRIBUTE)
                        .addAttributes("a", "href", "title")
                        .addAttributes("img", "src", "alt", "title", "width", "height")
                        .addAttributes("td", "colspan", "rowspan")
                        .addAttributes("th", "colspan", "rowspan")
                        .addProtocols("a", "href", "http", "https", "mailto", "assets", "ui", "#")
                        .addProtocols("img", "src", "http", "https", "assets", "ui")
                        .preserveRelativeLinks(true);
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        String safe = Jsoup.clean(html, "https://cms.invalid/", allowed, settings);
        Document doc = Jsoup.parseBodyFragment(safe);
        doc.outputSettings().prettyPrint(false);
        for (var paragraph : doc.select("p[" + LAYOUT_ATTRIBUTE + "]")) {
            if (!paragraph.attr(LAYOUT_ATTRIBUTE).matches("[0-9a-f]{32}"))
                paragraph.removeAttr(LAYOUT_ATTRIBUTE);
        }
        for (var element : doc.select("[style]")) {
            var declarations = new ArrayList<String>();
            for (String declaration : element.attr("style").split(";")) {
                String[] parts = declaration.split(":", 2);
                if (parts.length != 2) continue;
                String name = parts[0].trim().toLowerCase(Locale.ROOT);
                String value = parts[1].trim().toLowerCase(Locale.ROOT);
                boolean accepted =
                        switch (name) {
                            case "text-align" -> value.matches("left|right|center|justify");
                            case "font-weight" -> value.matches("normal|bold|[1-9]00");
                            case "font-style" -> value.matches("normal|italic");
                            case "text-decoration" -> value.matches("none|underline|line-through");
                            case "color", "background-color" ->
                                    value.matches("#[a-f0-9]{3,8}|[a-z]{1,20}");
                            case "font-size" -> value.matches("[1-9][0-9]?(px|pt)");
                            default -> false;
                        };
                if (accepted) declarations.add(name + ": " + value);
            }
            if (declarations.isEmpty()) element.removeAttr("style");
            else element.attr("style", String.join("; ", declarations));
        }
        for (var element : doc.select("[width],[height],[colspan],[rowspan]"))
            for (String name : new String[] {"width", "height", "colspan", "rowspan"})
                if (element.hasAttr(name) && !element.attr(name).matches("[1-9][0-9]{0,2}"))
                    element.removeAttr(name);
        doc.select("img:not([src])").remove();
        doc.select("a[href]").attr("rel", "nofollow noopener noreferrer");
        if (doc.body().text().isBlank() && doc.select("img[src]").isEmpty()) return "";
        return doc.body().html();
    }
}

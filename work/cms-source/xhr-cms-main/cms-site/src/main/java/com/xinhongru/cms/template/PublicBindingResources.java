package com.xinhongru.cms.template;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.*;
import java.util.regex.*;

/** 将原页面资源位置转为当前包逻辑引用，最终仍由现ResourceHtml按本站解析。 */
public final class PublicBindingResources {
    private PublicBindingResources() {}

    private static final Pattern CSS =
            Pattern.compile(
                    "url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s)]*))\\s*\\)",
                    Pattern.CASE_INSENSITIVE);
    private static final Set<String> RESOURCE_LINK_RELATIONS =
            Set.of(
                    "stylesheet",
                    "icon",
                    "preload",
                    "modulepreload",
                    "apple-touch-icon",
                    "apple-touch-icon-precomposed",
                    "mask-icon",
                    "manifest");

    public static String rebase(String html, String sourcePath) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (!document.select("base[href]").isEmpty())
            throw new IllegalArgumentException("源页面base地址仍待人工规范化，不能隐式改变公开路由");
        rebaseLinks(document, sourcePath);
        for (var element : document.getAllElements()) {
            for (String name : List.of("src", "poster"))
                if (element.hasAttr(name))
                    element.attr(name, reference(sourcePath, element.attr(name)));
            if (element.hasAttr("srcset")) {
                if (element.attr("srcset").toLowerCase(Locale.ROOT).contains("data:"))
                    throw new IllegalArgumentException("data URI srcset仍待人工处理，不能拆分改写");
                var values = new ArrayList<String>();
                for (String item : element.attr("srcset").split(",")) {
                    String[] parts = item.trim().split("\\s+", 2);
                    if (parts.length > 0 && !parts[0].isBlank())
                        values.add(
                                reference(sourcePath, parts[0])
                                        + (parts.length == 2 ? " " + parts[1] : ""));
                }
                element.attr("srcset", String.join(", ", values));
            }
            if (element.hasAttr("style"))
                element.attr("style", css(sourcePath, element.attr("style")));
            if (element.tagName().equals("style")) element.html(css(sourcePath, element.data()));
        }
        return document.outerHtml();
    }

    static void rebaseLinks(Document document, String sourcePath) {
        for (var link : document.select("link[href]")) {
            String href = link.attr("href");
            var relations = Arrays.asList(link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"));
            // 旧浏览器的图标模板也在 link 元素上使用 rel=bookmark。
            boolean bookmarkIcon = relations.contains("bookmark")
                    && href.strip().split("[?#]", 2)[0].toLowerCase(Locale.ROOT).endsWith(".ico");
            if (bookmarkIcon || relations.stream().anyMatch(RESOURCE_LINK_RELATIONS::contains))
                link.attr("href", reference(sourcePath, href));
        }
    }

    static boolean onlyResourceLinkPathsChanged(String previous, String next, String sourcePath) {
        var before = Jsoup.parse(previous);
        var after = Jsoup.parse(next);
        before.outputSettings().prettyPrint(false);
        after.outputSettings().prettyPrint(false);
        rebaseLinks(before, sourcePath);
        rebaseLinks(after, sourcePath);
        return before.outerHtml().equals(after.outerHtml());
    }

    private static String css(String source, String value) {
        var matcher = CSS.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String original =
                    matcher.group(1) != null
                            ? matcher.group(1)
                            : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement("url('" + reference(source, original) + "')"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String reference(String source, String value) {
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.isEmpty()
                || text.startsWith("#")
                || text.startsWith("assets:/")
                || text.startsWith("/assets/")
                || lower.startsWith("https://")
                || lower.startsWith("http://")
                || text.startsWith("//")
                || lower.startsWith("data:image/")
                || lower.startsWith("data:font/")) return value;
        return EditorContentPreparation.packageReference(source, text);
    }
}

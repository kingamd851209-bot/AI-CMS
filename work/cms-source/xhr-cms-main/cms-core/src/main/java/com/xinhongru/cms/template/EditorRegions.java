package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionHtml;

import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 有界正文区域检查；候选不是自动确认的业务正文。 */
public final class EditorRegions {
    private EditorRegions() {}

    private static final Pattern INLINE_ITEM_LAYOUT =
            Pattern.compile(
                    "\\s*\\.([a-zA-Z_][a-zA-Z0-9_-]{0,63})\\s+span\\s*\\{"
                            + "\\s*float\\s*:\\s*left\\s*;\\s*margin-right\\s*:\\s*"
                            + "([1-9][0-9]?rem)\\s*;?\\s*}\\s*");

    public record FixedLayout(String bodyHtml, String originalCss, String templateCss) {}

    /** 将唯一的内联排版样式留在模板内，正文只保留对应段落标记。 */
    public static FixedLayout fixedLayout(Element region) {
        var styles = region.select("style");
        if (styles.size() != 1) return null;
        var style = styles.first();
        var paragraph = style.parent();
        if (paragraph == null || paragraph.parent() != region || !paragraph.tagName().equals("p")
                || !style.attributes().isEmpty()
                || !paragraph.attr("style").matches("\\s*overflow\\s*:\\s*hidden\\s*;?\\s*")
                || paragraph.select("span").isEmpty()
                || paragraph.children().stream()
                        .anyMatch(child -> !Set.of("span", "br", "style").contains(child.tagName())))
            return null;
        var rule = INLINE_ITEM_LAYOUT.matcher(style.data());
        if (!rule.matches()
                || !paragraph.classNames().contains(rule.group(1))
                || region.root().getAllElements().stream()
                        .filter(node -> node.classNames().contains(rule.group(1))).count() != 1)
            return null;
        String key = UUID.nameUUIDFromBytes(style.data().getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        String attribute = ContentExtensionHtml.LAYOUT_ATTRIBUTE;
        if (!region.select("[" + attribute + "]").isEmpty()) return null;
        var body = region.clone();
        var bodyStyle = body.selectFirst("style");
        bodyStyle.parent().removeAttr("style").attr(attribute, key);
        bodyStyle.remove();
        if (problem(body) != null) return null;
        String selector = "p[" + attribute + "=\"" + key + "\"]";
        return new FixedLayout(
                body.html(),
                style.data(),
                selector + " { overflow: hidden; }\n"
                        + selector + " span { float: left; margin-right: " + rule.group(2) + "; }");
    }

    private static final Set<String> CONTENT =
            Set.of(
                    "article",
                    "section",
                    "div",
                    "p",
                    "span",
                    "strong",
                    "b",
                    "em",
                    "i",
                    "u",
                    "s",
                    "small",
                    "br",
                    "hr",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
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
                    "td",
                    "th",
                    "img",
                    "a");

    public static boolean candidate(Element element) {
        if (!Set.of("article", "section", "div").contains(element.tagName())) return false;
        for (Element parent = element; parent != null; parent = parent.parent())
            if (Set.of("nav", "header", "footer", "aside", "form").contains(parent.tagName())
                    || parent.hasAttr("hidden")
                    || "true".equals(parent.attr("aria-hidden"))) return false;
        var pending = new ArrayDeque<Element>();
        pending.push(element);
        int count = 0, paragraphs = 0, media = 0;
        while (!pending.isEmpty()) {
            Element node = pending.pop();
            if (++count > 256) return false;
            if (node.tagName().equals("p")) paragraphs++;
            if (Set.of("img", "a").contains(node.tagName())) media++;
            for (var child : node.children()) pending.push(child);
        }
        return paragraphs >= 2
                || paragraphs >= 1 && (media > 0 || element.tagName().equals("article"));
    }

    public static String problem(Element element) {
        var pending = new ArrayDeque<Element>();
        pending.push(element);
        int count = 0;
        boolean fixedLayoutStyle = false;
        while (!pending.isEmpty()) {
            Element node = pending.pop();
            if (++count > 256) return "正文区域超过256节点，未覆盖部分待处理";
            String tag = node.tagName();
            if (tag.equals("style")) return "正文内嵌固定样式表，保留原模板待处理";
            if (!CONTENT.contains(tag)) return "区域含布局、导航或交互节点，待人工确认独立正文";
            String style = node.attr("style").toLowerCase(java.util.Locale.ROOT);
            if (node.className()
                    .toLowerCase(java.util.Locale.ROOT)
                    .matches("(?s).*(^|[-_\\s])(grid|carousel|slider|cards?)([-_\\s]|$).*"))
                return "区域含网格或交互卡片布局特征，待处理";
            if (style.matches(
                    "(?s).*(display\\s*:\\s*(grid|flex)|position\\s*:|float\\s*:|overflow\\s*:).*"))
                fixedLayoutStyle = true;
            if (node != element && tag.equals("div")) return "区域含嵌套布局div，请选择更内层独立正文";
            if (tag.equals("li")
                    && node.children().stream().anyMatch(child -> child.tagName().equals("div")))
                return "区域含重复卡片结构，不能整块维护为正文";
            for (var child : node.children()) pending.push(child);
        }
        return fixedLayoutStyle ? "正文含固定布局样式，保留原模板待处理" : null;
    }
}

package com.xinhongru.cms.template;

import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** 详情取证和模板绑定共用的前后篇结构识别。 */
final class DetailNeighborMarkup {
    private static final Pattern INLINE_LABEL =
            Pattern.compile("^((上一篇|上一条|上一则|下一篇|下一条|下一则)\\s*[：:])\\s*(\\S.*)$");

    private DetailNeighborMarkup() {}

    record Edge(Element anchor, String title, String inlinePrefix, Element inlineLabel) {}

    static boolean candidateGroup(Element group) {
        return group.childrenSize() == 2
                && group.closest("header,footer,aside,.header,.footer,[hidden],[aria-hidden=true]") == null;
    }

    static Edge neighborEdge(Element row, boolean previous, boolean compiled) {
        if (!row.is("span,p,li,div") || row.hasAttr("hidden") || row.hasAttr("aria-hidden")
                || row.hasAttr("aria-disabled") || row.childNodeSize() > 20) return null;
        var links = row.select("a[href]");
        if (links.size() != 1 || row.getAllElements().stream().anyMatch(DetailNeighborMarkup::hasEvent))
            return null;
        var anchor = links.first();
        if (anchor.parent() != row
                || row.children().stream().anyMatch(child -> child != anchor
                        && (!child.is("em,strong,span,b,label") || child.childrenSize() != 0))) return null;
        var label = row.clone();
        label.selectFirst("a[href]").remove();
        if (anchor.childrenSize() == 0 && labelMatches(label.text(), previous))
            return new Edge(anchor, anchor.text(), null, null);
        if (!label.text().isEmpty() || anchor.hasAttr("aria-disabled")) return null;

        var inline = INLINE_LABEL.matcher(anchor.text());
        if (anchor.childrenSize() == 0 && inline.matches() && labelMatches(inline.group(2), previous))
            return new Edge(anchor, inline.group(3), inline.group(1), null);

        var inlineLabel = anchor.firstElementChild();
        if (anchor.childrenSize() != (compiled ? 2 : 1) || inlineLabel == null
                || !inlineLabel.is("i,em,strong,span,b,label") || inlineLabel.childrenSize() != 0
                || !inlineLabel.text().matches(".*[：:]$") || !labelMatches(inlineLabel.text(), previous)
                || (inlineLabel.previousSibling() != null
                        && !(inlineLabel.previousSibling() instanceof TextNode leading && leading.isBlank())))
            return null;
        var title = inlineLabel.nextSibling();
        if (title == null || title.nextSibling() != null) return null;
        if (compiled) {
            if (!(title instanceof Element block) || !block.tagName().equals("th:block")
                    || block.attributesSize() != 1 || block.childNodeSize() != 1) return null;
            title = block.childNode(0);
        }
        return title instanceof TextNode text && !text.isBlank()
                ? new Edge(anchor, text.text().strip(), null, inlineLabel) : null;
    }

    private static boolean labelMatches(String text, boolean previous) {
        String label = text.strip().replaceFirst("\\s*[：:]$", "").strip();
        return label.matches(previous ? "上一篇|上一条|上一则" : "下一篇|下一条|下一则");
    }

    static boolean hasEvent(Element element) {
        return element.attributes().asList().stream()
                .anyMatch(attribute -> attribute.getKey().startsWith("on"));
    }
}

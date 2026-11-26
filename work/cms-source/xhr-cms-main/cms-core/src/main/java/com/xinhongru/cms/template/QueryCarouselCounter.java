package com.xinhongru.cms.template;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.*;
import java.util.regex.Pattern;

/** 从完整的原轮播组提取可审核的卡片内序号。 */
public record QueryCarouselCounter(
        String currentSelector,
        String totalSelector,
        String currentSample,
        String totalSample,
        int currentWidth,
        int totalWidth,
        List<Integer> scopePath,
        List<String> sourceIds) {
    private static final Pattern PAIR = Pattern.compile("([0-9]{1,3})(\\s*[/／]\\s*)([0-9]{1,3})");
    private static final Pattern CAROUSEL =
            Pattern.compile(
                    "(?i)(?:^|[-_\\s])(swiper|carousel|slider|slick|splide|glide)(?:$|[-_\\s])");

    private static final Pattern COUNTER =
            Pattern.compile(
                    "(?i)(?:^|[-_\\s])(number|num|count|counter|fraction|current|total|index)(?:$|[-_\\s])");

    private static final Pattern BUSINESS_NUMBER =
            Pattern.compile(
                    "(?i)(?:rating|score|price|date|birthday|quantity|stock|money|currency|valuation|"
                        + "评分|星级|分数|价格|售价|金额|日期|时间|库存|数量)");

    static QueryCarouselCounter inspectStructure(List<Element> cards) {
        return cards.isEmpty() ? null : inspect(cards, cards.getFirst(), null);
    }

    static QueryCarouselCounter inspect(
            List<Element> cards,
            Element selected,
            List<TemplateContentUnitsService.Source> sources) {
        if (cards.size() < 2
                || !cards.contains(selected)
                || !cards.stream().allMatch(card -> card.parent() == selected.parent())
                || selected.parents().stream().limit(3).noneMatch(QueryCarouselCounter::carousel)
                        && !cards.stream().allMatch(QueryCarouselCounter::carousel)) return null;

        QueryCarouselCounter candidate = null;
        boolean found = false;
        var first = cards.getFirst();
        for (var scope : first.getAllElements()) {
            if (scope == first) continue;
            var parts = parts(scope);
            if (parts == null
                    || !counterHint(scope)
                            && !counterHint(parts.current())
                            && !counterHint(parts.total())) continue;
            int currentWidth = width(parts.currentSample());
            int totalWidth = width(parts.totalSample());
            var path = path(first, scope);
            boolean matches = true;
            for (int index = 0; index < cards.size(); index++) {
                var other = locate(cards.get(index), path);
                var otherParts = parts(other);
                if (otherParts == null
                        || !scope.tagName().equals(other.tagName())
                        || !scope.classNames().equals(other.classNames())
                        || !parts.current().tagName().equals(otherParts.current().tagName())
                        || !parts.total().tagName().equals(otherParts.total().tagName())
                        || !parts.current().classNames().equals(otherParts.current().classNames())
                        || !parts.total().classNames().equals(otherParts.total().classNames())
                        || !Objects.equals(parts.separator(), otherParts.separator())
                        || !formatted(index + 1, currentWidth).equals(otherParts.currentSample())
                        || !formatted(cards.size(), totalWidth).equals(otherParts.totalSample())) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            if (found) return null;
            found = true;
            var selectedScope = locate(selected, path);
            var selectedParts = parts(selectedScope);
            var selectors = new HashSet<String>();
            for (var node : selectedScope.getAllElements()) selectors.add(node.cssSelector());
            String currentSelector = selectedParts.current().cssSelector();
            String totalSelector = selectedParts.total().cssSelector();
            var text =
                    sources == null ? List.<TemplateContentUnitsService.Source>of() : sources.stream()
                            .filter(
                                    source ->
                                            "TEXT".equals(source.kind())
                                                    && selectors.contains(source.selector()))
                            .toList();
            if (sources != null && text.stream()
                            .noneMatch(
                                    source -> source.selector().equals(selectedScope.cssSelector()))
                    && (text.stream().noneMatch(source -> source.selector().equals(currentSelector))
                            || text.stream()
                                    .noneMatch(source -> source.selector().equals(totalSelector))))
                continue;
            candidate =
                    new QueryCarouselCounter(
                            currentSelector,
                            totalSelector,
                            selectedParts.currentSample(),
                            selectedParts.totalSample(),
                            currentWidth,
                            totalWidth,
                            path,
                            text.stream().map(TemplateContentUnitsService.Source::id).toList());
        }
        return candidate;
    }

    public Map<Element, String> expressions(Element card) {
        var scope = locate(card, scopePath);
        var parts = parts(scope);
        if (parts == null
                || !currentSample.equals(parts.currentSample())
                || !totalSample.equals(parts.totalSample())
                || currentWidth < 1
                || currentWidth > 3
                || totalWidth < 1
                || totalWidth > 3) throw new IllegalArgumentException("卡片轮播序号位置或原文已变化");
        String current = "#numbers.formatInteger(cmsBoundRowStatus.count, " + currentWidth + ")";
        String total = "#numbers.formatInteger(cmsBoundRowStatus.size, " + totalWidth + ")";
        if (parts.current() == parts.total())
            return Map.of(
                    parts.current(),
                    "${" + current + " + '" + parts.separator() + "' + " + total + "}");
        return Map.of(parts.current(), "${" + current + "}", parts.total(), "${" + total + "}");
    }

    static Element locate(Element root, List<Integer> path) {
        var element = root;
        for (int index : path) {
            if (index < 0 || index >= element.childrenSize()) return null;
            element = element.child(index);
        }
        return element;
    }

    private static List<Integer> path(Element card, Element scope) {
        var indexes = new ArrayList<Integer>();
        for (var node = scope; node != card; node = node.parent())
            indexes.add(node.elementSiblingIndex());
        return List.copyOf(indexes.reversed());
    }

    private static boolean carousel(Element element) {
        return "carousel".equalsIgnoreCase(element.attr("aria-roledescription"))
                || CAROUSEL.matcher(element.className() + " " + element.id()).find();
    }

    private static boolean counterHint(Element element) {
        return COUNTER.matcher(element.className() + " " + element.id()).find()
                || element.hasAttr("data-carousel-counter");
    }

    private static int width(String value) {
        return value.startsWith("0") ? value.length() : 1;
    }

    private static String formatted(int value, int width) {
        String text = Integer.toString(value);
        return "0".repeat(Math.max(0, width - text.length())) + text;
    }

    private record Parts(
            Element current,
            Element total,
            String currentSample,
            String totalSample,
            String separator) {}

    private static Parts parts(Element scope) {
        if (scope == null
                || !passive(scope)
                || scope.childrenSize() > 3
                || scope.childNodeSize() > 9) return null;

        var source = new StringBuilder();
        for (var node : scope.childNodes()) {
            if (node instanceof TextNode text) source.append(text.getWholeText());
            else if (node instanceof Element child
                    && passive(child)
                    && child.childNodeSize() == 1
                    && child.childNode(0) instanceof TextNode text)
                source.append(text.getWholeText());
            else return null;
        }
        var matcher = PAIR.matcher(source.toString().strip());
        if (!matcher.matches()
                || scope.parents().stream()
                        .anyMatch(
                                element ->
                                        element.is("button,time,label,summary,[contenteditable]")
                                                || businessNumber(element))) return null;
        if (scope.childrenSize() == 0
                && scope.childNodeSize() == 1
                && matcher.group(2).matches("[ \t]*[/／][ \t]*"))
            return new Parts(scope, scope, matcher.group(1), matcher.group(3), matcher.group(2));
        if (scope.childrenSize() >= 2
                && scope.child(0).text().equals(matcher.group(1))
                && scope.child(scope.childrenSize() - 1).text().equals(matcher.group(3)))
            return new Parts(
                    scope.child(0),
                    scope.child(scope.childrenSize() - 1),
                    matcher.group(1),
                    matcher.group(3),
                    null);
        return null;
    }

    private static boolean passive(Element element) {
        return element.is("span,p,div,small,strong,b,em,i")
                && !businessNumber(element)
                && element.attributes().asList().stream()
                        .noneMatch(attribute -> attribute.getKey().startsWith("on"));
    }

    static boolean businessNumber(Element element) {
        return BUSINESS_NUMBER
                        .matcher(
                                element.attr("itemprop")
                                        + " "
                                        + element.attr("aria-label")
                                        + " "
                                        + element.attr("title"))
                        .find()
                || BUSINESS_NUMBER.matcher(element.className() + " " + element.id()).find();
    }
}

package com.xinhongru.cms.template;

import org.jsoup.nodes.*;

import java.util.*;
import java.util.function.Predicate;

/** 分页只接受已确认的有限DOM位置和角色，不解析或运行原JS。 */
public final class QueryPagerControls {
    public static final Set<String> LINKS = Set.of("first", "prev", "next", "last");
    public static final Set<String> COUNTERS = Set.of("page", "pageCount", "total");
    private static final Set<String> ACTIVE_STYLES = Set.of("on", "active", "current", "selected",
            "is-active", "is-current", "is-selected");
    private static final Set<String> DISABLED_STYLES = Set.of("disabled", "disable", "is-disabled");

    private QueryPagerControls() {}

    static QueryListPresentation.Pagination suggest(Document doc, List<Element> cards) {
        var scopes = new LinkedHashSet<Element>();
        for (var anchor : doc.select("a[href]")) {
            var scope = anchor.parent();
            if (scope != null && "prev".equals(linkRole(anchor))
                    && scope.children().stream().anyMatch(node -> "next".equals(linkRole(node)))
                    && cards.stream().noneMatch(card -> scope.parents().contains(card) || scope.equals(card)))
                scopes.add(scope);
        }
        if (scopes.size() != 1) return null;
        var scope = scopes.getFirst();
        var children = scope.children();
        if (!scope.ownText().isBlank() || children.stream().anyMatch(node -> !node.is("a[href]")
                || !placeholder(node.attr("href")))) return null;
        var prev = selectOne(children, node -> "prev".equals(linkRole(node)));
        var next = selectOne(children, node -> "next".equals(linkRole(node)));
        var numbers = children.stream().filter(node -> node.childrenSize() == 0
                && node.text().matches("[1-9][0-9]{0,5}")).toList();
        var ellipses = children.stream().filter(node -> node.childrenSize() == 0
                && Set.of("…", "⋯", "...", "···").contains(node.text())).toList();
        if (prev == null || next == null || numbers.size() < 2 || numbers.size() > 20
                || ellipses.size() > 1 || children.size() != numbers.size() + ellipses.size() + 2
                || numbers.stream().map(Element::text).distinct().count() != numbers.size()) return null;
        if (children.first() != prev || children.last() != next) return null;
        for (int index = 1; index < numbers.size(); index++)
            if (Integer.parseInt(numbers.get(index - 1).text()) >= Integer.parseInt(numbers.get(index).text()))
                return null;
        var active = selectOne(numbers, node -> node.classNames().stream().anyMatch(ACTIVE_STYLES::contains));
        if (active == null) return null;
        String activeClass = uniqueStyle(active, ACTIVE_STYLES);
        var boundary = active == numbers.getFirst() && "1".equals(active.text()) ? prev : next;
        String disabledClass = uniqueStyle(boundary, DISABLED_STYLES);
        if (activeClass == null || disabledClass == null
                || boundary == next && active != numbers.getLast()
                || children.stream().anyMatch(node -> node != boundary && node.hasClass(disabledClass)))
            return null;
        String container = classSelector(doc, List.of(scope), Set.of());
        if (cssClass(scope.id()) && doc.select("#" + scope.id()).equals(List.of(scope)))
            container = "#" + scope.id();
        String number = classSelector(scope, numbers, Set.of(activeClass, disabledClass));
        String previous = classSelector(scope, List.of(prev), Set.of(disabledClass));
        String following = classSelector(scope, List.of(next), Set.of(disabledClass));
        String ellipsis = ellipses.isEmpty() ? null : classSelector(scope, ellipses, Set.of());
        if (container == null || number == null || previous == null || following == null
                || !ellipses.isEmpty() && ellipsis == null) return null;
        var draft = new QueryListPresentation.Pagination(container, number, activeClass, disabledClass,
                Map.of("prev", previous, "next", following), Map.of(), ellipsis == null ? Map.of() : Map.of(
                        ellipsis, "省略号是原页占位，接通真实页码及前后页后移除此占位。"));
        try {
            validate(doc, draft);
            return draft;
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static String linkRole(Element element) {
        if (!element.is("a[href]")) return "";
        var roles = new HashSet<String>();
        for (var label : List.of(element.attr("rel"), element.attr("aria-label"),
                element.attr("title"), element.text())) {
            switch (label.strip().toLowerCase(Locale.ROOT)) {
                case "prev", "previous", "previous page", "上一页", "上页" -> roles.add("prev");
                case "next", "next page", "下一页", "下页" -> roles.add("next");
                default -> {}
            }
        }
        return roles.size() == 1 ? roles.iterator().next() : "";
    }

    private static Element selectOne(List<Element> elements, Predicate<Element> matches) {
        var results = elements.stream().filter(matches).toList();
        return results.size() == 1 ? results.getFirst() : null;
    }

    private static String uniqueStyle(Element element, Set<String> knownStyles) {
        var styles = element.classNames().stream().filter(knownStyles::contains).toList();
        return styles.size() == 1 ? styles.getFirst() : null;
    }

    private static String classSelector(Element root, List<Element> matches, Set<String> excluded) {
        var node = matches.getFirst();
        var selectors = node.classNames().stream()
                .filter(name -> cssClass(name) && !excluded.contains(name))
                .map(name -> node.tagName() + "." + name)
                .filter(selector -> root.select(selector).equals(matches))
                .toList();
        return selectors.size() == 1 ? selectors.getFirst() : null;
    }

    public static Element validate(Document doc, QueryListPresentation.Pagination paging) {
        if (paging == null
                || paging.links() == null
                || paging.links().size() > 4
                || paging.counters() == null
                || paging.counters().size() > 3
                || paging.ignoredControls() == null
                || paging.ignoredControls().size() > 20
                || !cssClass(paging.activeClass())
                || !cssClass(paging.disabledClass()))
            throw new IllegalArgumentException("请明确分页角色、样式与待忽略控件");
        var scope = one(doc, paging.containerSelector());
        if (scope.getAllElements().size() > 200)
            throw new IllegalArgumentException("分页区域超过200节点，仍待缩小范围");
        for (var node : scope.getAllElements())
            for (var attr : node.attributes())
                if (attr.getKey().toLowerCase(Locale.ROOT).startsWith("on"))
                    throw new IllegalArgumentException("分页含未知JS事件，不能自动接通");
        selector(paging.numberSelector());
        var numbers = scope.select(paging.numberSelector());
        if (numbers.isEmpty() || numbers.size() > 20)
            throw new IllegalArgumentException("请明确1至20个原数字页锚点，不能猜跳页事件");
        var covered = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        covered.addAll(numbers);
        for (var number : numbers)
            if (!number.tagName().equals("a")
                    || number.childrenSize() != 0
                    || !number.text().trim().matches("[0-9]{1,6}")
                    || !placeholder(number.attr("href")))
                throw new IllegalArgumentException("数字页模板含混合query、布局或未知JS，仍待处理");
        for (var entry : paging.links().entrySet()) {
            if (!LINKS.contains(entry.getKey())) throw new IllegalArgumentException("分页链接角色无效");
            var anchor = one(scope, entry.getValue());
            if (!anchor.tagName().equals("a")
                    || !covered.add(anchor)
                    || !placeholder(anchor.attr("href")))
                throw new IllegalArgumentException("分页链接需明确唯一的占位锚点，未知query/JS仍待处理");
        }
        if (!paging.links().keySet().containsAll(List.of("prev", "next")))
            throw new IllegalArgumentException("请至少明确上一页和下一页");
        var ranges = new IdentityHashMap<Element, List<QueryListPresentation.Counter>>();
        for (var entry : paging.counters().entrySet()) {
            if (!COUNTERS.contains(entry.getKey())) throw new IllegalArgumentException("分页计数角色无效");
            var part = entry.getValue();
            if (part == null) throw new IllegalArgumentException("分页计数位置无效");
            var el = one(scope, part.selector());
            if (!ranges.containsKey(el) && covered.contains(el))
                throw new IllegalArgumentException("同一分页节点不能同时绑定链接与计数");
            var previous = ranges.computeIfAbsent(el, ignored -> new ArrayList<>());
            for (var range : previous)
                if (part.start() < range.end() && part.end() > range.start())
                    throw new IllegalArgumentException("分页计数区间重叠");
            previous.add(part);
            covered.add(el);
            if (el.childNodeSize() != 1
                    || !(el.childNode(0) instanceof TextNode text)
                    || part.start() < 0
                    || part.end() <= part.start()
                    || part.end() > text.getWholeText().length()
                    || !text.getWholeText().substring(part.start(), part.end()).matches("[0-9]+"))
                throw new IllegalArgumentException("分页计数须明确原文数字区间，不能吞掉单位或内联样式");
        }
        for (var entry : paging.ignoredControls().entrySet()) {
            selector(entry.getKey());
            if (entry.getValue() == null
                    || entry.getValue().isBlank()
                    || entry.getValue().length() > 300)
                throw new IllegalArgumentException("忽略分页控件须明确说明影响");
            var ignored = scope.select(entry.getKey());
            if (ignored.isEmpty() || ignored.size() > 20)
                throw new IllegalArgumentException("忽略范围无效");
            for (var el : ignored) {
                if (covered.stream().anyMatch(control -> el.getAllElements().contains(control)))
                    throw new IllegalArgumentException("控件不能同时绑定和忽略");
                covered.addAll(el.getAllElements());
            }
        }
        for (var control : scope.select("a,input,button,select,form"))
            if (!covered.contains(control))
                throw new IllegalArgumentException("分页仍有未确认控件，不能静默忽略跳页或未知链接");
        return scope;
    }

    public static Element one(Element root, String query) {
        selector(query);
        var elements = root.select(query);
        if (elements.size() != 1) throw new IllegalArgumentException("分页位置不唯一或已失效");
        return elements.first();
    }

    private static void selector(String query) {
        if (query == null || !query.matches("[a-zA-Z0-9_.# -]{1,200}") || query.isBlank())
            throw new IllegalArgumentException("分页仅支持简单ID/class位置");
    }

    private static boolean cssClass(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_-]{0,49}");
    }

    private static boolean placeholder(String href) {
        return Set.of("", "#", "##", "javascript:void(0);", "javascript:void(0)")
                .contains(href.trim().toLowerCase(Locale.ROOT));
    }

    public static QueryListPresentation.Pagination immutable(
            QueryListPresentation.Pagination paging) {
        return new QueryListPresentation.Pagination(
                paging.containerSelector(),
                paging.numberSelector(),
                paging.activeClass(),
                paging.disabledClass(),
                Map.copyOf(paging.links()),
                Map.copyOf(paging.counters()),
                Map.copyOf(paging.ignoredControls()));
    }
}

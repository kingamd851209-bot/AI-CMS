package com.xinhongru.cms.template;

import com.xinhongru.cms.template.QueryTimeline.Selection;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/** 固定分组模型与同页键盘导航，原脚本和样本文字均不会进入输出。 */
final class TimelineTemplateCompiler {
    private TimelineTemplateCompiler() {}

    private static final String MARKER = "data-cms-timeline";
    private static final String GROUPS = "group, state : ${cmsBoundTimeline}";
    private static final String LABEL = "${group.label}";
    private static final String ACTIVE = "${state.first ? 'on' : ''}";
    private static final String STYLE =
            """
            [data-cms-timeline=panel][hidden]{display:none!important}
            [data-cms-timeline=viewport],[data-cms-timeline=navigation][data-scroll]{height:auto!important;max-height:320px;overflow:auto!important}
            [data-cms-timeline=navigation]{display:block!important;height:auto!important;transform:none!important}
            [data-cms-timeline=tab]{cursor:pointer!important;height:auto!important;min-height:44px}
            [data-cms-timeline=previous]::before,[data-cms-timeline=next]::before{font-size:16px;line-height:1}
            [data-cms-timeline=previous]::before{content:'↑'}
            [data-cms-timeline=next]::before{content:'↓'}
            [data-cms-timeline=previous]>i,[data-cms-timeline=next]>i{display:none}
            [data-cms-timeline][aria-disabled=true]{opacity:.5}
            [data-cms-timeline=row]{white-space:pre-line}
            """
                    .strip();
    private static final String SHARED_STYLE = STYLE + "\n" + """
            [data-cms-timeline=tab-label]{margin:0}
            [data-cms-timeline=controls]{display:flex!important;justify-content:center;gap:12px}
            [data-cms-timeline=controls]>[data-cms-timeline]{position:static!important;margin:0!important;transform:none!important;width:auto!important;min-width:32px;min-height:28px}
            [data-cms-timeline=controls]>[aria-disabled=true]{cursor:default!important}
            """.strip();
    private static final String SCRIPT =
            """
            (() => {
              const part = name => document.querySelectorAll('[data-cms-timeline="' + name + '"]');
              const tabs = [...part('tab')], panels = [...part('panel')], groups = part('groups')[0];
              const buttons = [...part('previous'), ...part('next')];
              if (!tabs.length || tabs.length !== panels.length || !groups) return;
              let active = 0;
              function select(index, focus, scroll) {
                if (index < 0 || index >= tabs.length) return;
                active = index;
                tabs.forEach((tab, position) => {
                  const on = position === index;
                  tab.classList.toggle('on', on);
                  tab.setAttribute('aria-selected', String(on));
                  tab.tabIndex = on ? 0 : -1;
                  panels[position].hidden = !on;
                  panels[position].classList.toggle('on', on);
                });
                groups.dataset.year = tabs[index].textContent.trim();
                buttons.forEach(button => button.setAttribute('aria-disabled', String(
                  button.dataset.cmsTimeline === 'previous' ? index === 0 : index === tabs.length - 1)));
                if (focus) tabs[index].focus({preventScroll: true});
                if (scroll) tabs[index].scrollIntoView({block: 'nearest', inline: 'nearest'});
              }
              tabs.forEach((tab, index) => {
                tab.addEventListener('click', () => select(index, true, true));
                tab.addEventListener('keydown', event => {
                  const target = {ArrowUp: index - 1, ArrowLeft: index - 1, ArrowDown: index + 1,
                    ArrowRight: index + 1, Home: 0, End: tabs.length - 1, Enter: index, ' ': index}[event.key];
                  if (target === undefined) return;
                  event.preventDefault();
                  select(target, true, true);
                });
              });
              buttons.forEach(button => {
                function move() { select(active + (button.dataset.cmsTimeline === 'previous' ? -1 : 1), false, true); }
                button.addEventListener('click', move);
                button.addEventListener('keydown', event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  move();
                });
              });
              select(0, false, false);
            })();
            """
                    .strip();

    static QueryTemplateCompiler.Compiled compile(
            String source, TemplateQueryBindingService.Snapshot snapshot) {
        var choice = snapshot.presentation().request().timeline();
        var html = new TextBindingCompiler().compile(source, List.of(), List.of(), 1).html();
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (!document.select("[" + MARKER + "],form,[id^=cms-timeline-]").isEmpty())
            throw new IllegalArgumentException("时间轴页面含保留标记或未审核表单");
        document.select("script").remove();
        for (var element : document.getAllElements())
            for (var attribute : element.attributes().asList())
                if (attribute.getKey().startsWith("on")) element.removeAttr(attribute.getKey());

        var navigation =
                unique(document, choice.navigationSelector())
                        .attr(MARKER, "navigation")
                        .attr("role", "tablist")
                        .attr("aria-label", "时间轴分组");
        var groups = unique(document, choice.groupSelector());
        boolean sharedPanel = sharedPanel(choice);
        if (groups.parents().contains(navigation.parent())) navigation.attr("data-scroll", "true");
        else navigation.parent().attr(MARKER, "viewport");
        var tab = retainPrototype(navigation, choice.navigationItemSelector())
                .removeClass("on")
                .removeClass("active")
                .attr(MARKER, "tab")
                .attr("role", "tab")
                .attr("th:each", GROUPS)
                .attr("th:classappend", ACTIVE)
                .attr("th:id", "${'cms-timeline-tab-' + state.index}")
                .attr("th:aria-controls", "${'cms-timeline-panel-' + state.index}")
                .attr("th:aria-selected", "${state.first ? 'true' : 'false'}")
                .attr("th:tabindex", "${state.first ? 0 : -1}");
        if (sharedPanel) {
            tab.removeAttr("data-src");
            clearAnimation(navigation.parent().parent());
            clearAnimation(groups);
        }
        if (sharedPanel && tab.childrenSize() > 0) {
            if (tab.childrenSize() != 1 || !tab.ownText().isBlank() || tab.child(0).childrenSize() != 0)
                throw new IllegalArgumentException("时间轴导航标签原型已变化");
            tab.child(0).empty().attr(MARKER, "tab-label").attr("th:text", LABEL);
        } else {
            tab.empty().attr("th:text", LABEL);
        }
        if (navigation.parent() != null && navigation.parent().parent() != null) {
            var controls = navigation.parent().parent();
            wireControl(controls, ".swiper-button-prev", "previous", "上一组", sharedPanel);
            wireControl(controls, ".swiper-button-next", "next", "下一组", sharedPanel);
        }
        groups.attr(MARKER, "groups")
                .removeAttr("data-year")
                .attr(
                        "th:data-year",
                        "${cmsBoundTimeline.isEmpty() ? '' : cmsBoundTimeline[0].label}");
        var panel =
                retainPrototype(groups, choice.groupItemSelector())
                        .removeClass("on")
                        .removeClass("active")
                        .removeClass("wow")
                        .removeClass("fadeInUp")
                        .attr(MARKER, "panel")
                        .attr("role", "tabpanel")
                        .attr("th:each", GROUPS)
                        .attr("th:classappend", ACTIVE)
                        .attr("th:id", "${'cms-timeline-panel-' + state.index}")
                        .attr("th:aria-labelledby", "${'cms-timeline-tab-' + state.index}")
                        .attr("th:hidden", "${!state.first}");
        if (choice.groupTitleSelector() != null) {
            var title = panel.child(0);
            if (!title.is(choice.groupTitleSelector()))
                throw new IllegalArgumentException("时间轴标题原型已变化");
            title.empty().attr(MARKER, "title").attr("th:text", LABEL);
        }
        var wrapper = sharedPanel ? panel : panel.child(choice.groupTitleSelector() == null ? 0 : 1);
        var rows =
                panel.select(choice.rowSelector()).stream()
                        .filter(element -> element.parent() == wrapper)
                        .toList();
        if (rows.isEmpty() || rows.size() != wrapper.childrenSize())
            throw new IllegalArgumentException("时间轴行原型已变化");
        var row =
                choice.rowImageSelector() == null
                        ? rows.getFirst()
                        : rows.stream()
                                .filter(
                                        element ->
                                                !element.select(choice.rowImageSelector())
                                                        .isEmpty())
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("记录图片原型已变化"));
        rows.stream().filter(element -> element != row).forEach(Element::remove);
        row.attr(MARKER, "row").attr("th:each", "event : ${group.rows}");
        if (sharedPanel) clearAnimation(row);
        if (choice.rowTextSelector() == null) {
            row.empty().attr("th:text", "${event}");
        } else {
            boolean imageSelected = choice.imageField() != null;
            boolean hasSecondary = choice.secondaryField() != null;
            var primary = requireOne(row.select(choice.rowTextSelector()), "记录文本");
            primary.empty()
                    .attr(MARKER, "row-text")
                    .attr("th:text", imageSelected || hasSecondary ? "${event.text}" : "${event}");
            if (hasSecondary) {
                var secondary = requireOne(row.select(choice.rowSecondarySelector()), "记录辅助文本");
                if (secondary == primary || secondary.parents().contains(primary) || primary.parents().contains(secondary))
                    throw new IllegalArgumentException("时间轴记录文本原型重叠");
                secondary.empty().attr(MARKER, "row-secondary").attr("th:text", "${event.secondary}");
            }
            var image = requireOne(row.select(choice.rowImageSelector()), "记录图片");
            if (image.parent() != row) image.parent().removeAttr("style");
            if (imageSelected) {
                for (var attribute : image.attributes().asList())
                    if (!Set.of("class", "width", "height", "loading", "decoding")
                            .contains(attribute.getKey())) image.removeAttr(attribute.getKey());
                image.attr(MARKER, "row-image")
                        .attr("th:if", "${!event.image.isEmpty()}")
                        .attr("th:src", "${event.image}")
                        .attr("alt", "");
            } else {
                image.remove();
            }
        }
        groups.appendElement("p")
                .attr(MARKER, "empty")
                .attr("th:if", "${cmsBoundTimeline.isEmpty()}")
                .text("暂无可展示的内容");
        document.head()
                .appendElement("style")
                .attr(MARKER, "style")
                .appendChild(new DataNode(sharedPanel ? SHARED_STYLE : STYLE));
        document.body()
                .appendElement("script")
                .attr(MARKER, "script")
                .appendChild(new DataNode(SCRIPT));
        var result =
                new QueryTemplateCompiler.Compiled(
                        document.outerHtml(),
                        snapshot,
                        List.of("时间轴使用站内固定分组联动；原样文不导入，原页面脚本不执行"),
                        true);
        validate(result.html(), choice);
        return result;
    }

    private static void wireControl(
            Element controls, String selector, String marker, String label, boolean sharedPanel) {
        var matches = controls.select(selector);
        if (matches.isEmpty()) return;
        var control = requireOne(matches, "切换按钮");
        control.attr(MARKER, marker)
                .attr("role", "button")
                .attr("aria-label", label)
                .attr("tabindex", "0")
                .attr("th:if", "${cmsBoundTimeline.size() > 1}");
        if (sharedPanel) {
            control.removeClass("disabled").removeClass("swiper-button-disabled");
            if (control.parent() != controls) control.parent().attr(MARKER, "controls");
        }
    }

    private static boolean sharedPanel(Selection choice) {
        return choice != null && !choice.rowSelector().contains(">");
    }

    private static void clearAnimation(Element element) {
        if (element != null) element.removeClass("wow").removeClass("fadeInUp");
    }

    private static Element retainPrototype(Element container, String selector) {
        var children = container.children();
        if (children.isEmpty() || children.stream().anyMatch(child -> !child.is(selector)))
            throw new IllegalArgumentException("时间轴重复原型已变化");
        children.stream().skip(1).forEach(Element::remove);
        return children.first();
    }

    private static Element unique(Document document, String selector) {
        return requireOne(document.select(selector), "容器");
    }

    private static Element requireOne(List<Element> matches, String label) {
        if (matches.size() != 1) throw new IllegalArgumentException("时间轴" + label + "不唯一或已变化");
        return matches.getFirst();
    }

    static void validate(String html) {
        validate(html, null);
    }

    static void validate(String html, Selection choice) {
        var document = Jsoup.parse(html);
        var style = unique(document, "style[" + MARKER + "=style]");
        var script = unique(document, "script[" + MARKER + "=script]");
        boolean sharedPanel = sharedPanel(choice);
        if (!(sharedPanel ? SHARED_STYLE : STYLE).equals(style.data())
                || !SCRIPT.equals(script.data())
                || style.attributesSize() != 1
                || script.attributesSize() != 1
                || document.select("script").size() != 1)
            throw new IllegalArgumentException("时间轴模板脚本或样式已变化");
        boolean card = choice != null && choice.rowTextSelector() != null;
        boolean image = card && choice.imageField() != null;
        boolean secondary = card && choice.secondaryField() != null;
        boolean title = choice == null || choice.groupTitleSelector() != null;
        var tabLabels = document.select("[" + MARKER + "=tab-label]");
        boolean nestedTabLabel = sharedPanel && !tabLabels.isEmpty();
        var tabDirectives = new HashMap<>(Map.of(
                "th:each", GROUPS,
                "th:classappend", ACTIVE,
                "th:id", "${'cms-timeline-tab-' + state.index}",
                "th:aria-controls", "${'cms-timeline-panel-' + state.index}",
                "th:aria-selected", "${state.first ? 'true' : 'false'}",
                "th:tabindex", "${state.first ? 0 : -1}"));
        if (!nestedTabLabel) tabDirectives.put("th:text", LABEL);
        var allowed =
                Map.ofEntries(
                        Map.entry("tab", tabDirectives),
                        Map.entry("tab-label", Map.of("th:text", LABEL)),
                        Map.entry(
                                "groups",
                                Map.of(
                                        "th:data-year",
                                        "${cmsBoundTimeline.isEmpty() ? '' :"
                                            + " cmsBoundTimeline[0].label}")),
                        Map.entry(
                                "panel",
                                Map.of(
                                        "th:each",
                                        GROUPS,
                                        "th:classappend",
                                        ACTIVE,
                                        "th:id",
                                        "${'cms-timeline-panel-' + state.index}",
                                        "th:aria-labelledby",
                                        "${'cms-timeline-tab-' + state.index}",
                                        "th:hidden",
                                        "${!state.first}")),
                        Map.entry("title", Map.of("th:text", LABEL)),
                        Map.entry(
                                "row",
                                card
                                        ? Map.of("th:each", "event : ${group.rows}")
                                        : Map.of(
                                                "th:each",
                                                "event : ${group.rows}",
                                                "th:text",
                                                "${event}")),
                        Map.entry(
                                "row-text",
                                Map.of("th:text", image || secondary ? "${event.text}" : "${event}")),
                        Map.entry("row-secondary", Map.of("th:text", "${event.secondary}")),
                        Map.entry(
                                "row-image",
                                Map.of(
                                        "th:if",
                                        "${!event.image.isEmpty()}",
                                        "th:src",
                                        "${event.image}")),
                        Map.entry("empty", Map.of("th:if", "${cmsBoundTimeline.isEmpty()}")),
                        Map.entry("previous", Map.of("th:if", "${cmsBoundTimeline.size() > 1}")),
                        Map.entry("next", Map.of("th:if", "${cmsBoundTimeline.size() > 1}")));
        for (String marker : List.of("tab", "groups", "panel", "row", "empty", "navigation"))
            unique(document, "[" + MARKER + "=" + marker + "]");
        requireMarker(document, "title", title);
        requireMarker(document, "tab-label", nestedTabLabel);
        requireMarker(document, "row-text", card);
        requireMarker(document, "row-secondary", secondary);
        requireMarker(document, "row-image", image);
        var row = unique(document, "[" + MARKER + "=row]");
        if (sharedPanel) {
            var tab = unique(document, "[" + MARKER + "=tab]");
            var panel = unique(document, "[" + MARKER + "=panel]");
            if (tab.hasAttr("data-src") || tab.childrenSize() != (nestedTabLabel ? 1 : 0)
                    || (nestedTabLabel && tabLabels.first() != tab.child(0)))
                throw new IllegalArgumentException("时间轴导航标签与审核结果不一致");
            if (row.parent() != panel)
                throw new IllegalArgumentException("时间轴记录原型与审核结果不一致");
        }
        if (card) {
            requireOne(row.select("[" + MARKER + "=row-text]"), "记录文本");
            if (secondary)
                requireOne(row.select("[" + MARKER + "=row-secondary]"), "记录辅助文本");
            if (image)
                requireOne(row.select("img[" + MARKER + "=row-image]:not([src],[srcset])"), "记录图片");
        }
        for (var element : document.getAllElements()) {
            var expected = allowed.getOrDefault(element.attr(MARKER), Map.of());
            var found = new HashMap<String, String>();
            for (var attribute : element.attributes()) {
                String name = attribute.getKey();
                if (name.startsWith("th:") || name.startsWith("data-th-"))
                    found.put(name, attribute.getValue());
                if (name.startsWith("on")) throw new IllegalArgumentException("时间轴模板含源页面事件");
            }
            if (!found.equals(expected)) throw new IllegalArgumentException("时间轴模板含非受控指令");
        }
    }

    private static void requireMarker(Document document, String marker, boolean required) {
        if (document.select("[" + MARKER + "=" + marker + "]").size() != (required ? 1 : 0))
            throw new IllegalArgumentException("时间轴模板标记与审核结果不一致");
    }
}

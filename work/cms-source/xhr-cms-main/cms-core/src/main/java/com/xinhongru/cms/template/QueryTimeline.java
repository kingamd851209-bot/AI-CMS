package com.xinhongru.cms.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.entity.OneshotEntity;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/** 根据同页重复文字和一一对应的导航标题识别分组，源样文仅用于确认结构。 */
public final class QueryTimeline {
    private QueryTimeline() {}

    public record Selection(
            String navigationSelector,
            String navigationItemSelector,
            String groupSelector,
            String groupItemSelector,
            String groupTitleSelector,
            String rowSelector,
            String groupField,
            String rowField,
            boolean ascending,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowTextSelector,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowImageSelector,
            @JsonInclude(JsonInclude.Include.NON_NULL) String imageField,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowSecondarySelector,
            @JsonInclude(JsonInclude.Include.NON_NULL) String secondaryField) {
        public Selection(String navigationSelector, String navigationItemSelector, String groupSelector,
                String groupItemSelector, String groupTitleSelector, String rowSelector, String groupField,
                String rowField, boolean ascending, String rowTextSelector, String rowImageSelector, String imageField) {
            this(navigationSelector, navigationItemSelector, groupSelector, groupItemSelector, groupTitleSelector,
                    rowSelector, groupField, rowField, ascending, rowTextSelector, rowImageSelector, imageField, null, null);
        }
        public Selection(
                String navigationSelector,
                String navigationItemSelector,
                String groupSelector,
                String groupItemSelector,
                String groupTitleSelector,
                String rowSelector,
                String groupField,
                String rowField,
                boolean ascending) {
            this(
                    navigationSelector,
                    navigationItemSelector,
                    groupSelector,
                    groupItemSelector,
                    groupTitleSelector,
                    rowSelector,
                    groupField,
                    rowField,
                    ascending,
                    null,
                    null,
                    null);
        }
    }

    public record Layout(
            String navigationSelector,
            String navigationItemSelector,
            String groupSelector,
            String groupItemSelector,
            String groupTitleSelector,
            String rowSelector,
            List<String> labels,
            int sourceRows,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowTextSelector,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowImageSelector,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rowSecondarySelector) {
        boolean matches(Selection choice) {
            return Objects.equals(navigationSelector, choice.navigationSelector())
                    && Objects.equals(navigationItemSelector, choice.navigationItemSelector())
                    && Objects.equals(groupSelector, choice.groupSelector())
                    && Objects.equals(groupItemSelector, choice.groupItemSelector())
                    && Objects.equals(groupTitleSelector, choice.groupTitleSelector())
                    && Objects.equals(rowSelector, choice.rowSelector())
                    && Objects.equals(rowTextSelector, choice.rowTextSelector())
                    && Objects.equals(rowImageSelector, choice.rowImageSelector())
                    && Objects.equals(rowSecondarySelector, choice.rowSecondarySelector());
        }
    }

    public record Evidence(
            TemplateQueryBindingService.Candidates candidates,
            List<Layout> layouts,
            List<QueryListPresentation.Field> fields) {}

    public static List<QueryListPresentation.Field> fields(OneshotEntity menu) {
        return ContentModelCodec.read(menu.getModelField()).stream()
                .filter(field -> ContentFieldDefinition.enabled(field.getIsOpen()))
                .filter(
                        field ->
                                imageField(field)
                                        || (("SingleLine".equals(field.getFieldType())
                                                        || "MultiLine".equals(field.getFieldType()))
                                                && field.getFieldName() != null
                                                && (ContentExtensionFields.isName(
                                                                field.getFieldName())
                                                        || field.getFieldName()
                                                                .matches(
                                                                        "title|singleLine(?:[1-9]|1[0-9]|20)|note(?:[1-9]|1[0-5])"))))
                .map(
                        field ->
                                new QueryListPresentation.Field(
                                        field.getFieldName(),
                                        field.getTitle(),
                                        field.getFieldType()))
                .toList();
    }

    public static boolean imageField(ContentFieldDefinition field) {
        return NativeImageFields.supports(field)
                || ("Image".equals(field.getFieldType())
                        && ContentExtensionFields.isName(field.getFieldName())
                        && ContentFieldDefinition.enabled(field.getIsOpen())
                        && !Boolean.TRUE.equals(field.getInputMultiple()));
    }

    public static List<Layout> layouts(
            Document document, SiteModelEvidenceService.Region approved) {
        var all = document.body().getAllElements();
        if (all.size() > 5_000 || approved.records().isEmpty()) return List.of();
        var reviewed = new HashSet<Element>();
        for (var record : approved.records()) {
            var matches = document.select(record.selector());
            if (matches.size() != 1) return List.of();
            reviewed.add(matches.first());
        }
        var layouts = new ArrayList<Layout>();
        for (var group : all) {
            var groups = group.children();
            if (groups.size() < 2
                    || groups.size() > 100
                    || !group.ownText().isBlank()
                    || shared(group)
                    || groups.stream().anyMatch(el -> el.hasAttr("id") || !el.ownText().isBlank()))
                continue;
            boolean hasTitle =
                    groups.stream().allMatch(el -> el.childrenSize() == 2 && plain(el.child(0)));
            if (!hasTitle
                    && (!group.hasAttr("data-year")
                            || groups.stream().anyMatch(el -> el.childrenSize() != 1))) continue;
            var titles =
                    hasTitle ? groups.stream().map(el -> el.child(0)).toList() : List.<Element>of();
            var labels = titles.stream().map(Element::text).toList();
            var wrappers = groups.stream().map(el -> el.child(hasTitle ? 1 : 0)).toList();
            var rows = wrappers.stream().flatMap(el -> el.children().stream()).toList();
            if (wrappers.stream()
                            .anyMatch(
                                    el ->
                                            el.childrenSize() == 0
                                                    || el.hasAttr("id")
                                                    || !el.ownText().isBlank())
                    || !new HashSet<>(rows).containsAll(reviewed)) continue;
            String groupItem = commonTag(groups),
                    groupTitle = hasTitle ? commonTag(titles) : null,
                    rowWrapper = commonTag(wrappers),
                    rowItem = commonTag(rows);
            if (groupItem == null
                    || (hasTitle && groupTitle == null)
                    || rowWrapper == null
                    || rowItem == null) continue;
            var card = imageCard(rows, wrappers.getFirst());
            if (card == null && (!hasTitle || rows.stream().anyMatch(el -> !plain(el)))) continue;
            for (var navigation : all) {
                var entries = navigation.children();
                if (entries.size() != groups.size()
                        || !navigation.ownText().isBlank()
                        || shared(navigation)
                        || overlaps(group, navigation)
                        || entries.stream().anyMatch(el -> !plain(el))) continue;
                var navigationLabels = entries.stream().map(Element::text).toList();
                if (navigationLabels.stream().distinct().count() != groups.size()) continue;
                if (hasTitle
                        ? !navigationLabels.equals(labels)
                        : !navigation.parents().contains(group.parent())
                                || !Objects.equals(
                                        group.attr("data-year"), navigationLabels.getFirst()))
                    continue;
                String navigationItem = commonTag(entries);
                if (navigationItem != null)
                    layouts.add(
                            new Layout(
                                    navigation.cssSelector(),
                                    navigationItem,
                                    group.cssSelector(),
                                    groupItem,
                                    groupTitle,
                                    rowWrapper + " > " + rowItem,
                                    navigationLabels,
                                    rows.size(),
                                    card == null ? null : card.textSelector(),
                                    card == null ? null : card.imageSelector(), null));
            }
        }
        return List.copyOf(layouts);
    }

    private record Card(String textSelector, String imageSelector) {}

    private static Card imageCard(List<Element> rows, Element firstWrapper) {
        var texts = new ArrayList<Element>();
        var images = new ArrayList<Element>();
        var covers = new ArrayList<Element>();
        for (var row : rows) {
            if (row.hasAttr("id") || !row.ownText().isBlank() || row.childrenSize() != 2)
                return null;
            var labels = row.children().stream().filter(QueryTimeline::plain).toList();
            if (labels.size() != 1) return null;
            var text = labels.getFirst();
            var cover = row.child(0) == text ? row.child(1) : row.child(0);
            if (!cover.is("img")
                    && (cover.hasAttr("id")
                            || !cover.ownText().isBlank()
                            || !cover.is("div,span,figure")
                            || cover.childrenSize() > 1)) return null;
            if (!cover.is("img")) covers.add(cover);
            if (cover.is("img") || cover.childrenSize() != 0) {
                var image = cover.is("img") ? cover : cover.child(0);
                if (!image.is("img[src]") || image.hasAttr("id") || image.attr("src").isBlank())
                    return null;
                images.add(image);
            }
            texts.add(text);
        }
        if (images.isEmpty()
                || images.stream().noneMatch(image -> image.parents().contains(firstWrapper)))
            return null;
        String textSelector = commonTag(texts), imageSelector = commonTag(images);
        if (textSelector == null || imageSelector == null) return null;
        if (!covers.isEmpty()) {
            if (covers.size() != rows.size()) return null;
            String coverSelector = commonTag(covers);
            if (coverSelector == null) return null;
            imageSelector = coverSelector + " > " + imageSelector;
        }
        return new Card(textSelector, imageSelector);
    }

    private static boolean plain(Element el) {
        return el.childrenSize() == 0
                && !el.text().isBlank()
                && !el.hasAttr("id")
                && !el.is("a,button,input,select,textarea,style,script,template,noscript");
    }

    private static String commonTag(List<Element> items) {
        var first = items.getFirst();
        if (items.stream().anyMatch(el -> !first.tagName().equals(el.tagName()))) return null;
        var sharedClasses = new TreeSet<>(first.classNames());
        for (var item : items) sharedClasses.retainAll(item.classNames());
        sharedClasses.remove("on");
        sharedClasses.remove("active");
        return first.tagName()
                + sharedClasses.stream()
                        .filter(name -> name.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                        .map(name -> "." + name)
                        .reduce("", String::concat);
    }

    private static boolean overlaps(Element a, Element b) {
        return a == b || a.parents().contains(b) || b.parents().contains(a);
    }

    private static boolean shared(Element el) {
        return el.closest("header,footer,nav,.header,.footer,[role=banner],[role=contentinfo]")
                != null;
    }
}

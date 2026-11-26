package com.xinhongru.cms.template;

import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 仅编译已确认的普通文本引用，不写模板、不写内容或发布。 */
public final class TextBindingCompiler {
    public record Pending(String unitId, String reason) {}

    public record DomChange(String unitId, String before, String after, String reason) {}

    public record Compilation(
            String html,
            List<TemplateFieldPlanService.Binding> bound,
            List<Pending> pending,
            List<DomChange> domChanges) {
        public Compilation(
                String html, List<TemplateFieldPlanService.Binding> bound, List<Pending> pending) {
            this(html, bound, pending, List.of());
        }
    }

    private record Edit(
            TextNode node,
            int start,
            int end,
            String expression,
            TemplateFieldPlanService.Binding binding) {}

    private record TextRange(TextNode node, int start, int end) {}

    private static final Pattern LABELLED_HEADING =
            Pattern.compile("([^:：\\r\\n]+)[:：]([^:：\\r\\n]+)");

    /** Only exact text, limited inline descendants, and a single target text node are eligible. */
    private static TextRange mixedRange(
            Element element, String original, TemplateContentUnitsService.Reference reference) {
        if (reference.start() < 0 || reference.end() <= reference.start()
                || reference.end() > original.length()) return null;
        List<TextNode> nodes = new ArrayList<>();
        if (!inlineNodes(element, nodes, 0, new int[] {0}))
            return decoratedHeadingRange(element, original, reference);
        String actual = nodes.stream().map(TextNode::getWholeText).collect(Collectors.joining());
        if (!actual.equals(original)) return null;
        for (int index : new int[] {reference.start(), reference.end()})
            if (index > 0
                    && index < actual.length()
                    && Character.isHighSurrogate(actual.charAt(index - 1))
                    && Character.isLowSurrogate(actual.charAt(index))) return null;
        int offset = 0;
        for (var node : nodes) {
            int end = offset + node.getWholeText().length();
            if (reference.start() >= offset && reference.end() <= end)
                return new TextRange(node, reference.start() - offset, reference.end() - offset);
            offset = end;
        }
        return null;
    }

    private static TextRange multilineValueRange(
            Element element, String original, TemplateContentUnitsService.Reference reference) {
        if (!List.of("div", "p", "span", "li", "dd", "dt", "strong", "b", "em", "i", "u", "s", "small")
                        .contains(element.tagName())
                || element.closest("a,button,label,[contenteditable]") != null) return null;
        return mixedRange(element, original, reference);
    }

    private static TextRange decoratedHeadingRange(
            Element element, String original, TemplateContentUnitsService.Reference reference) {
        if (!element.normalName().matches("h[1-6]")
                || element.closest("a, button, label, [contenteditable]") != null
                || element.attributes().asList().stream()
                        .anyMatch(attribute -> !List.of("class", "id").contains(attribute.getKey()))
                || element.childNodeSize() != 2
                || !(element.childNode(0) instanceof Element icon)
                || !icon.normalName().equals("i")
                || icon.attributes().size() != 0
                || icon.childNodeSize() != 1
                || !(icon.childNode(0) instanceof Element image)
                || !image.normalName().equals("img")
                || image.childNodeSize() != 0
                || image.attributes().size() != 2
                || !image.hasAttr("alt")
                || !image.attr("alt").isEmpty()
                || !image.attr("src").matches("[A-Za-z0-9_./-]+\\.(?i:png|jpe?g|gif|webp|avif|ico)")
                || image.attr("src").startsWith("//")
                || !(element.childNode(1) instanceof TextNode text)
                || !original.equals(text.getWholeText())) return null;
        var match = LABELLED_HEADING.matcher(original);
        if (!match.matches()
                || match.group(1).isBlank()
                || match.group(2).isBlank()
                || reference.start() != match.start(2)
                || reference.end() != original.length()) return null;
        return new TextRange(text, reference.start(), reference.end());
    }

    private static boolean inlineNodes(
            Element element, List<TextNode> nodes, int depth, int[] visited) {
        if (depth > 4 || ++visited[0] > 32) return false;
        for (var child : element.childNodes()) {
            if (child instanceof TextNode text) {
                if (++visited[0] > 32) return false;
                nodes.add(text);
            } else if (child instanceof Element inline
                    && List.of("span", "em", "strong", "b", "i", "u", "s", "small", "sup", "sub")
                            .contains(inline.tagName())) {
                if (!inlineNodes(inline, nodes, depth + 1, visited)) return false;
            } else return false;
        }
        return true;
    }

    private record MediaEdit(
            Element node, String attribute, TemplateFieldPlanService.Binding binding) {}

    private record HtmlEdit(
            Element region, TemplateFieldPlanService.Binding binding, EditorRegions.FixedLayout layout) {}

    private record CounterEdit(
            Element node, CounterScriptInputs.NumericPart part, String unitId, String before) {}

    private record MultiEdit(
            Element region,
            Element target,
            String prefix,
            String suffix,
            String original,
            String expression,
            TemplateFieldPlanService.Binding binding) {}

    public Compilation compile(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType) {
        return compile(html, bindings, sources, pageType, true);
    }

    Compilation compile(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType,
            boolean allowLinkedBackground) {
        return compile(html, bindings, sources, pageType, allowLinkedBackground, false);
    }

    Compilation compile(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType,
            boolean allowLinkedBackground,
            boolean allowMixedText) {
        return compile(html, bindings, sources, pageType, allowLinkedBackground, allowMixedText,
                null, Map.of());
    }

    Compilation compile(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType,
            boolean allowLinkedBackground,
            boolean allowMixedText,
            String reviewedCardSelector,
            Map<String, String> reviewedDetailLinks) {
        if (html == null
                || html.isBlank()
                || html.length() > 2 * 1024 * 1024
                || bindings == null
                || sources == null
                || bindings.size() > 200
                || sources.size() > 1000
                || (pageType != 0 && pageType != 1))
            throw new IllegalArgumentException("文本绑定输入无效或超限");
        rejectDynamicComments(html);
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (document.getAllElements().size() > 5000)
            throw new IllegalArgumentException("页面DOM超过5000节点，需分页面处理");
        for (var element : document.getAllElements()) {
            int depth = 0;
            for (var node = element; node != null; node = node.parent())
                if (++depth > 64) throw new IllegalArgumentException("页面层级超限，请人工处理");
            if (element.tagName().toLowerCase(java.util.Locale.ROOT).startsWith("th:"))
                throw new IllegalArgumentException("源包含未经确认的动态模板语法，请先人工处理");
            for (var attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("th:") || name.startsWith("data-th-"))
                    throw new IllegalArgumentException("源包含未经确认的动态模板语法，请先人工处理");
            }
        }
        String normalized = document.outerHtml();
        for (String marker : List.of("${", "*{", "@{", "#{", "~{", "[[", "[("))
            if (normalized.contains(marker))
                throw new IllegalArgumentException("源包含未经确认的动态模板语法，请先人工处理");
        var detailAnchors = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        if (!reviewedDetailLinks.isEmpty()) {
            var cards = document.select(reviewedCardSelector);
            if (cards.size() != 1) throw new IllegalArgumentException("已确认模板卡片定位变化");
            for (var entry : reviewedDetailLinks.entrySet()) {
                var anchors = document.select(entry.getKey());
                if (anchors.size() != 1 || !anchors.first().is("a[href]")
                        || !cards.first().getAllElements().contains(anchors.first())
                        || !anchors.first().attr("href").equals(entry.getValue()))
                    throw new IllegalArgumentException("已确认详情链接定位或原值变化");
                detailAnchors.add(anchors.first());
            }
        }
        Map<String, TemplateContentUnitsService.Source> byId =
                sources.stream()
                        .collect(
                                Collectors.toMap(
                                        TemplateContentUnitsService.Source::id, source -> source));
        var edits = new ArrayList<Edit>();
        var pending = new ArrayList<Pending>();
        var multiline = new ArrayList<MultiEdit>();
        var rich = new ArrayList<HtmlEdit>();
        var media = new ArrayList<MediaEdit>();
        var counters = new ArrayList<CounterEdit>();
        var scriptTargets =
                sources.stream()
                        .filter(source -> source.scriptInput() != null)
                        .flatMap(source -> document.select(source.selector()).stream())
                        .distinct()
                        .toList();
        var unverifiedScriptTargets =
                sources.stream()
                        .filter(
                                source ->
                                        source.scriptInput() != null
                                                && !CounterScriptInputs.knownCounter(source))
                        .flatMap(source -> document.select(source.selector()).stream())
                        .distinct()
                        .toList();
        var verifiedScriptTargets =
                sources.stream()
                        .filter(CounterScriptInputs::knownCounter)
                        .flatMap(source -> document.select(source.selector()).stream())
                        .distinct()
                        .toList();
        for (var binding : bindings) {
            if (binding.action().equals("IGNORED")) continue;
            boolean aliasesScriptTarget =
                    binding.sources().stream()
                            .anyMatch(
                                    reference -> {
                                        var source = byId.get(reference.sourceId());
                                        return source != null
                                                && !CounterScriptInputs.knownCounter(source)
                                                && document.select(source.selector()).stream()
                                                        .anyMatch(
                                                                element -> {
                                                                    var range =
                                                                            allowMixedText
                                                                                            && "SingleLine"
                                                                                                    .equals(
                                                                                                            binding
                                                                                                                    .fieldType())
                                                                                            && binding.sources()
                                                                                                            .size()
                                                                                                    == 1
                                                                                            && "TEXT"
                                                                                                    .equals(
                                                                                                            source
                                                                                                                    .kind())
                                                                                    ? mixedRange(
                                                                                            element,
                                                                                            source
                                                                                                    .rawValue(),
                                                                                            reference)
                                                                                    : null;
                                                                    if (range != null
                                                                            && scriptTargets
                                                                                    .stream()
                                                                                    .filter(
                                                                                            target ->
                                                                                                    inside(
                                                                                                                    element,
                                                                                                                    target)
                                                                                                            || inside(
                                                                                                                    target,
                                                                                                                    element))
                                                                                    .allMatch(
                                                                                            target ->
                                                                                                    verifiedScriptTargets
                                                                                                                    .contains(
                                                                                                                            target)
                                                                                                            && !unverifiedScriptTargets
                                                                                                                    .contains(
                                                                                                                            target))
                                                                            && scriptTargets
                                                                                    .stream()
                                                                                    .noneMatch(
                                                                                            target ->
                                                                                                    inside(
                                                                                                            range.node()
                                                                                                                    .parent(),
                                                                                                            target)))
                                                                        return false;
                                                                    return scriptTargets.stream()
                                                                            .anyMatch(
                                                                                    target ->
                                                                                            inside(
                                                                                                            element,
                                                                                                            target)
                                                                                                    || inside(
                                                                                                            target,
                                                                                                            element));
                                                                });
                                    });
            if (aliasesScriptTarget) {
                pending.add(
                        new Pending(binding.unitId(), "来源与脚本统计目标重叠，须从明确取证的数值入口绑定；不能用父级普通文本绕过统计约束"));
                continue;
            }
            if (List.of("Image", "Video").contains(binding.fieldType())) {
                if (binding.sources().size() != 1
                        || !List.of("CREATE", "REUSE").contains(binding.action())) {
                    pending.add(new Pending(binding.unitId(), "媒体须单完整来源与确认映射"));
                    continue;
                }
                var ref = binding.sources().getFirst();
                var evidence = byId.get(ref.sourceId());
                if (evidence == null
                        || ref.start() != 0
                        || ref.end() != evidence.rawValue().length())
                    throw new IllegalArgumentException("媒体来源区间无效");
                if ("BACKGROUND_IMAGE".equals(evidence.kind())) {
                    var matches = document.select(evidence.selector());
                    if (matches.size() != 1) throw new IllegalArgumentException("背景图定位不唯一或失效");
                    Element element = matches.first();
                    if (!"Image".equals(binding.fieldType())
                            || !LinkedImageBackground.standalone(element, evidence.rawValue())) {
                        pending.add(new Pending(binding.unitId(), "背景包含其他样式或联动图片，需核对原页"));
                        continue;
                    }
                    if (media.stream().anyMatch(edit -> edit.node() == element && "style".equals(edit.attribute())))
                        throw new IllegalArgumentException("同一背景重复绑定");
                    media.add(new MediaEdit(element, "style", binding));
                    continue;
                }
                String attribute =
                        "VIDEO_POSTER".equals(evidence.kind())
                                        && "Image".equals(binding.fieldType())
                                ? "poster"
                                : "Image".equals(binding.fieldType())
                                                        && "IMAGE".equals(evidence.kind())
                                                || "Video".equals(binding.fieldType())
                                                        && List.of("VIDEO_SRC", "VIDEO_SOURCE")
                                                                .contains(evidence.kind())
                                        ? "src"
                                        : null;
                var selected = document.select(evidence.selector());
                if (selected.size() != 1) throw new IllegalArgumentException("媒体定位不唯一或失效");
                Element node = selected.first();
                String expectedTag =
                        "IMAGE".equals(evidence.kind())
                                ? "img"
                                : "VIDEO_SOURCE".equals(evidence.kind()) ? "source" : "video";
                boolean responsive =
                        node.hasAttr("srcset")
                                || node.parent() != null
                                        && node.parent().tagName().equals("picture");
                boolean sharedBackground =
                        "Image".equals(binding.fieldType())
                                && ((node.attr("style")
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .contains("background")
                                                && node.attr("style").contains(evidence.rawValue()))
                                        || node.parent() != null
                                                && node.parent()
                                                        .attr("style")
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .contains("background")
                                                && node.parent()
                                                        .attr("style")
                                                        .contains(evidence.rawValue()));
                boolean alternate =
                        "VIDEO_SOURCE".equals(evidence.kind())
                                        && (node.parent() == null
                                                || !node.parent().tagName().equals("video")
                                                || node.parent().hasAttr("src")
                                                || node.parent().select("source[src]").size() != 1)
                                || "VIDEO_SRC".equals(evidence.kind())
                                        && !node.select("source[src]").isEmpty();
                if (attribute == null
                        || !node.tagName().equals(expectedTag)
                        || !node.hasAttr(attribute)
                        || responsive
                        || alternate
                        || sharedBackground
                                && (!allowLinkedBackground
                                        || LinkedImageBackground.target(node, evidence.rawValue())
                                                == null)) {
                    pending.add(new Pending(binding.unitId(), "脚本、响应式、共享背景或多资源媒体结构待处理，未绑定"));
                    continue;
                }
                if (!node.attr(attribute).equals(evidence.rawValue()))
                    throw new IllegalArgumentException("媒体原地址与定位不一致");
                mediaExpression(binding);
                if (media.stream()
                        .anyMatch(
                                edit -> edit.node() == node && edit.attribute().equals(attribute)))
                    throw new IllegalArgumentException("媒体属性重复绑定");
                media.add(new MediaEdit(node, attribute, binding));
                continue;
            }
            if ("Editor".equals(binding.fieldType())) {
                if (binding.sources().size() != 1
                        || !List.of("CREATE", "REUSE").contains(binding.action())) {
                    pending.add(new Pending(binding.unitId(), "Editor须一个已确认完整HTML区域，多个来源仍待处理"));
                    continue;
                }
                var reference = binding.sources().getFirst();
                var evidence = byId.get(reference.sourceId());
                if (evidence == null
                        || !evidence.kind().startsWith("HTML_REGION")
                        || reference.start() != 0
                        || reference.end() != evidence.rawValue().length()) {
                    pending.add(new Pending(binding.unitId(), "Editor缺少完整HTML区域证据"));
                    continue;
                }
                var selected = document.select(evidence.selector());
                if (selected.size() != 1) throw new IllegalArgumentException("正文来源定位不唯一或已失效");
                var region = selected.first();
                var layout = EditorRegions.fixedLayout(region);
                if (layout != null && !layout.bodyHtml().equals(evidence.rawValue())) layout = null;
                String reason = layout == null ? EditorRegions.problem(region) : null;
                if (reason != null || evidence.kind().equals("HTML_REGION_PENDING")) {
                    pending.add(
                            new Pending(
                                    binding.unitId(), reason != null ? reason : "正文布局或固定样式依赖待处理"));
                    continue;
                }
                if (layout == null && !region.html().equals(evidence.rawValue()))
                    throw new IllegalArgumentException("正文HTML原值与定位不一致");
                htmlExpression(binding.fieldName(), pageType);
                rich.add(new HtmlEdit(region, binding, layout));
                continue;
            }
            if (!List.of("SingleLine", "MultiLine", "DateTime").contains(binding.fieldType())
                    || binding.action().equals("PENDING_TYPE")) {
                pending.add(new Pending(binding.unitId(), "仅支持普通单行/多行文本，其他类型待处理"));
                continue;
            }
            if (!MenuNameBinding.applies(binding)
                    && !List.of("CREATE", "REUSE").contains(binding.action()))
                throw new IllegalArgumentException("字段映射尚未确认或存在冲突");
            if (binding.sources().size() != 1) {
                pending.add(new Pending(binding.unitId(), "多个来源不能自动整块替换，需人工拆分"));
                continue;
            }
            if ("DateTime".equals(binding.fieldType()))
                TemplateDateValues.original(binding, sources);
            var reference = binding.sources().get(0);
            var source = byId.get(reference.sourceId());
            var counterPart =
                    source == null ? null : CounterScriptInputs.numericPart(source, reference);
            if (source == null
                    || (MenuNameBinding.applies(binding) && counterPart != null)
                    || (!source.kind().equals("TEXT") && counterPart == null)
                    || reference.start() < 0
                    || reference.end() <= reference.start()
                    || reference.end() > source.rawValue().length())
                throw new IllegalArgumentException("来源证据或字符区间无效");
            var selected = document.select(source.selector());
            if (selected.size() != 1) throw new IllegalArgumentException("来源定位不唯一或已失效");
            var element = selected.first();
            if ("DateTime".equals(binding.fieldType())
                    && (element.hasAttr("datetime")
                            || element.hasAttr("href")
                            || element.parents().stream()
                                    .anyMatch(
                                            parent ->
                                                    ("time".equals(parent.tagName())
                                                                    && parent.hasAttr("datetime"))
                                                            || ("a".equals(parent.tagName())
                                                                    && parent.hasAttr("href")
                                                                    && !detailAnchors.contains(parent))))) {
                pending.add(new Pending(binding.unitId(), "日期关联datetime/href属性，不能仅更新可见文本；保留待处理"));
                continue;
            }
            if (counterPart != null
                    && (!"SingleLine".equals(binding.fieldType())
                            || !"span".equals(element.tagName())
                            || element.attributes().asList().stream()
                                    .anyMatch(
                                            attribute ->
                                                    !List.of("id", "class")
                                                            .contains(attribute.getKey()))
                            || !document.select("style,link[rel=stylesheet]").isEmpty())) {
                pending.add(new Pending(binding.unitId(), "固定限定符涉及特殊样式或非简单span，DOM移位效果须人工适配，未绑定"));
                continue;
            }
            if (binding.fieldType().equals("MultiLine")) {
                if (reference.start() != 0 || reference.end() != source.rawValue().length()) {
                    var range = allowMixedText && pageType == 1
                            ? multilineValueRange(element, source.rawValue(), reference) : null;
                    if (range == null) {
                        pending.add(new Pending(binding.unitId(),
                                "多行值区间须位于已审核详情的单一文本节点，不能跨节点或拆分UTF-16字符"));
                        continue;
                    }
                    edits.add(new Edit(range.node(), range.start(), range.end(),
                            expression(binding, pageType), binding));
                    continue;
                }
                Element target = multilineTarget(element);
                if (target == null
                        || reference.start() != 0
                        || reference.end() != source.rawValue().length()) {
                    pending.add(
                            new Pending(
                                    binding.unitId(), "多行只支持完整纯文本/br容器与统一内联包装；混合样式、图标、图片或布局保留待处理"));
                    continue;
                }
                String text = multilineText(target);
                int offset = text.indexOf(source.rawValue());
                if (offset < 0 || text.indexOf(source.rawValue(), offset + 1) >= 0)
                    throw new IllegalArgumentException("多行原文与完整来源定位不一致或有歧义");
                boolean heading =
                        List.of("h1", "h2", "h3", "h4", "h5", "h6").contains(target.tagName());
                if (heading && !text.equals(source.rawValue())) {
                    pending.add(new Pending(binding.unitId(), "分行标题须有完整文本/br来源证据，不能局部替换"));
                    continue;
                }
                multiline.add(
                        new MultiEdit(
                                element,
                                target,
                                heading ? "" : text.substring(0, offset),
                                heading ? "" : text.substring(offset + source.rawValue().length()),
                                heading ? headingText(target) : source.rawValue(),
                                expression(binding, pageType),
                                binding));
                continue;
            }
            if (element.childNodeSize() != 1 || !(element.childNode(0) instanceof TextNode node)) {
                var range =
                        allowMixedText
                                        && "SingleLine".equals(binding.fieldType())
                                        && counterPart == null
                                ? mixedRange(element, source.rawValue(), reference)
                                : null;
                if (range != null) {
                    edits.add(
                            new Edit(
                                    range.node(),
                                    range.start(),
                                    range.end(),
                                    expression(binding, pageType),
                                    binding));
                    continue;
                }
                pending.add(new Pending(binding.unitId(), "来源含子元素或跨文本节点，需人工处理以保留原布局"));
                continue;
            }
            String text = node.getWholeText();
            int offset = text.indexOf(source.rawValue());
            if (offset < 0 || text.indexOf(source.rawValue(), offset + 1) >= 0)
                throw new IllegalArgumentException("来源原文与定位不一致或有歧义");
            if (counterPart != null) {
                if (!text.equals(source.rawValue()))
                    throw new IllegalArgumentException("统计原文范围不完整");
                counters.add(
                        new CounterEdit(
                                element, counterPart, binding.unitId(), element.outerHtml()));
            }
            edits.add(
                    new Edit(
                            node,
                            offset + reference.start(),
                            offset + reference.end(),
                            "DateTime".equals(binding.fieldType())
                                    ? dateExpression(binding.fieldName())
                                    : expression(binding, pageType),
                            binding));
        }
        for (var edit : media) {
            for (var region : rich)
                if (inside(edit.node(), region.region()))
                    throw new IllegalArgumentException("媒体与正文来源重叠");
            for (var region : multiline)
                if (inside(edit.node(), region.region()))
                    throw new IllegalArgumentException("媒体与多行来源重叠");
            edit.node().attr("th:" + edit.attribute(), "style".equals(edit.attribute())
                    ? LinkedImageBackground.expression(edit.binding().fieldName())
                    : mediaExpression(edit.binding()));
            if (allowLinkedBackground
                    && "Image".equals(edit.binding().fieldType())
                    && "src".equals(edit.attribute())) {
                var background = LinkedImageBackground.target(edit.node(), edit.node().attr("src"));
                if (background != null)
                    background.attr(
                            "th:style",
                            LinkedImageBackground.expression(edit.binding().fieldName()));
            }
        }
        for (int i = 0; i < rich.size(); i++) {
            Element region = rich.get(i).region();
            for (var edit : edits)
                if (inside(edit.node(), region)) throw new IllegalArgumentException("正文与文本来源重叠");
            for (var edit : multiline)
                if (inside(edit.region(), region) || inside(region, edit.region()))
                    throw new IllegalArgumentException("正文与多行来源重叠");
            for (int j = 0; j < i; j++)
                if (inside(region, rich.get(j).region()) || inside(rich.get(j).region(), region))
                    throw new IllegalArgumentException("正文来源重叠");
        }
        for (int i = 0; i < multiline.size(); i++) {
            var current = multiline.get(i);
            for (var edit : edits)
                if (inside(edit.node().parent(), current.region()))
                    throw new IllegalArgumentException("单行与多行来源区域重叠，请先人工修正");
            for (int j = 0; j < i; j++)
                if (inside(current.region(), multiline.get(j).region())
                        || inside(multiline.get(j).region(), current.region()))
                    throw new IllegalArgumentException("多行来源区域重叠，请先人工修正");
        }
        var groups = new IdentityHashMap<TextNode, List<Edit>>();
        for (var edit : edits)
            groups.computeIfAbsent(edit.node(), ignored -> new ArrayList<>()).add(edit);
        List<String> sourceConflicts = new ArrayList<>();
        for (var group : groups.values()) {
            if (group.size() != 1
                    && counters.stream()
                            .anyMatch(
                                    counter -> counter.node() == group.getFirst().node().parent()))
                throw new IllegalArgumentException("统计限定符不能同时映射为另一字段");
            group.sort(Comparator.comparingInt(Edit::start));
            for (int i = 0; i < group.size(); i++) {
                var left = group.get(i);
                for (int j = i + 1; j < group.size() && group.get(j).start() < left.end(); j++) {
                    var right = group.get(j);
                    String original = right.node().getWholeText().substring(
                            right.start(), Math.min(left.end(), right.end()));
                    sourceConflicts.add(describeOverlap(left.binding(), right.binding(), original));
                }
            }
        }
        if (!sourceConflicts.isEmpty()) {
            sourceConflicts.sort(String::compareTo);
            throw new IllegalArgumentException("来源绑定区间重叠，请先人工修正："
                    + String.join("；", sourceConflicts));
        }
        for (var group : groups.values()) {
            group.sort(Comparator.comparingInt(Edit::start).reversed());
            for (var edit : group) {
                String original = edit.node().getWholeText().substring(edit.start(), edit.end());
                if (edit.end() < edit.node().getWholeText().length())
                    edit.node().splitText(edit.end());
                TextNode middle =
                        edit.start() == 0 ? edit.node() : edit.node().splitText(edit.start());
                Element block =
                        new Element("MultiLine".equals(edit.binding().fieldType()) ? "span" : "th:block")
                                .attr("th:text", edit.expression()).text(original);
                if ("MultiLine".equals(edit.binding().fieldType()))
                    block.attr("style", "white-space: pre-wrap").attr("data-cms-multiline", "true");
                middle.replaceWith(block);
            }
        }
        var domChanges = new ArrayList<DomChange>();
        for (var counter : counters) {
            // 只移动编译器刚拆出的固定文本；原元素及父子元素关系不变。
            for (var child : List.copyOf(counter.node().childNodes()))
                if (child instanceof TextNode) child.remove();
            if (!counter.part().prefix().isEmpty())
                counter.node().before(new TextNode(counter.part().prefix()));
            if (!counter.part().suffix().isEmpty())
                counter.node().after(new TextNode(counter.part().suffix()));
            domChanges.add(
                    new DomChange(
                            counter.unitId(),
                            counter.before(),
                            org.jsoup.nodes.Entities.escape(counter.part().prefix())
                                    + counter.node().outerHtml()
                                    + org.jsoup.nodes.Entities.escape(counter.part().suffix()),
                            "固定限定符移到相邻文本，原标签/class/id与元素层级保留；请核对原JS效果与样式"));
        }
        for (var edit : multiline) {
            edit.target().empty();
            edit.target().appendChild(new TextNode(edit.prefix()));
            edit.target()
                    .appendChild(
                            new Element("span")
                                    .attr("style", "white-space: pre-wrap")
                                    .attr("data-cms-multiline", "true")
                                    .attr("th:text", edit.expression())
                                    .text(edit.original()));
            edit.target().appendChild(new TextNode(edit.suffix()));
        }
        for (var edit : rich) {
            var layout = edit.layout();
            String original = layout == null ? edit.region().html() : layout.bodyHtml();
            edit.region().empty();
            if (layout != null) {
                edit.region().appendChild(new Element("style").appendChild(new DataNode(layout.templateCss())));
                domChanges.add(new DomChange(edit.binding().unitId(), layout.originalCss(),
                        layout.templateCss(), "原正文的固定排版样式移入模板，通过独立段落标记继续生效"));
            }
            edit.region()
                    .appendChild(
                            new Element("th:block")
                                    .attr(
                                            "th:utext",
                                            htmlExpression(edit.binding().fieldName(), pageType))
                                    .html(original));
        }
        var bound = new ArrayList<TemplateFieldPlanService.Binding>();
        for (var binding : bindings)
            if (edits.stream().anyMatch(edit -> edit.binding() == binding)
                    || multiline.stream().anyMatch(edit -> edit.binding() == binding)
                    || rich.stream().anyMatch(edit -> edit.binding() == binding)
                    || media.stream().anyMatch(edit -> edit.binding() == binding))
                bound.add(binding);
        return new Compilation(
                document.outerHtml(),
                List.copyOf(bound),
                List.copyOf(pending),
                List.copyOf(domChanges));
    }

    private static String describeOverlap(
            TemplateFieldPlanService.Binding left,
            TemplateFieldPlanService.Binding right,
            String original) {
        String preview = original.codePointCount(0, original.length()) > 80
                ? original.substring(0, original.offsetByCodePoints(0, 80)) + "…"
                : original;
        return fieldLabel(left) + " 与 " + fieldLabel(right) + " 共同引用原文“" + preview + "”";
    }

    private static String fieldLabel(TemplateFieldPlanService.Binding binding) {
        return "“" + binding.name() + "”"
                + (binding.fieldName() == null ? "" : "（" + binding.fieldName() + "）")
                + " [" + binding.sources().getFirst().sourceId() + "]";
    }

    static List<TemplateFieldPlanService.Binding> publicBindings(
            TemplateFieldPlanService.Plan plan) {
        if (plan.origins() == null || plan.origins().isEmpty()) return plan.bindings();
        return plan.bindings().stream()
                .filter(
                        binding ->
                                binding.sources().stream()
                                        .noneMatch(ref -> ref.sourceId().startsWith("LIST:")))
                .toList();
    }

    /** 公开模板使用独立的本次请求值槽；其余绑定/选择器规则与私有编译完全一致。 */
    public Compilation compilePublic(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType) {
        return compilePublic(html, bindings, sources, pageType, false);
    }

    Compilation compilePublic(
            String html,
            List<TemplateFieldPlanService.Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            int pageType,
            boolean allowMixedText) {
        var draft = compile(html, bindings, sources, pageType, true, allowMixedText);
        return new Compilation(
                draft.html()
                        .replace("${cmsDraftHtml[", "${cmsBoundHtml[")
                        .replace("${cmsDraftMedia[", "${cmsBoundMedia[")
                        .replace(
                                "${'background-image: url(&quot;' + cmsDraftMedia[",
                                "${'background-image: url(&quot;' + cmsBoundMedia[")
                        .replace(
                                "#uris.escapePath(cmsDraftMedia[",
                                "#uris.escapePath(cmsBoundMedia[")
                        .replace("${cmsDraftDates[", "${cmsBoundDates[")
                        .replace("${cmsDraftMenuNames[", "${cmsBoundMenuNames["),
                draft.bound(),
                draft.pending(),
                draft.domChanges());
    }

    /** 仅变换本编译器的两个输出槽后复用原严格校验，不接受私有槽或任意公开表达式。 */
    public void validatePublicControlled(Compilation compilation, int pageType) {
        if (compilation == null
                || compilation.html() == null
                || compilation.html().contains("${cmsDraftHtml[")
                || compilation.html().contains("cmsDraftMedia[")
                || compilation.html().contains("${cmsDraftDates[")
                || compilation.html().contains("${cmsDraftMenuNames["))
            throw new IllegalArgumentException("公开模板包含私有或无效模型引用");
        validateControlled(
                new Compilation(
                        compilation
                                .html()
                                .replace("${cmsBoundHtml[", "${cmsDraftHtml[")
                                .replace("${cmsBoundMedia[", "${cmsDraftMedia[")
                                .replace(
                                        "${'background-image: url(&quot;' + cmsBoundMedia[",
                                        "${'background-image: url(&quot;' + cmsDraftMedia[")
                                .replace(
                                        "#uris.escapePath(cmsBoundMedia[",
                                        "#uris.escapePath(cmsDraftMedia[")
                                .replace("${cmsBoundDates[", "${cmsDraftDates[")
                                .replace("${cmsBoundMenuNames[", "${cmsDraftMenuNames["),
                        compilation.bound(),
                        compilation.pending(),
                        compilation.domChanges()),
                pageType,
                true);
    }

    /** 在真实引擎前再次检查仅有编译器生成的字段输出指令，禁止片段/内联/Bean路径。 */
    public void validateControlled(Compilation compilation, int pageType) {
        validateControlled(compilation, pageType, false);
    }

    private void validateControlled(Compilation compilation, int pageType, boolean publicResources) {
        if (compilation == null
                || compilation.html() == null
                || compilation.html().length() > 2 * 1024 * 1024
                || compilation.bound() == null
                || compilation.bound().isEmpty()
                || compilation.bound().size() > 200)
            throw new IllegalArgumentException("受控草稿为空或超限");
        var expected =
                compilation.bound().stream()
                        .filter(
                                binding ->
                                        !List.of("Editor", "Image", "Video")
                                                .contains(binding.fieldType()))
                        .map(
                                binding ->
                                        "DateTime".equals(binding.fieldType())
                                                ? dateExpression(binding.fieldName())
                                                : expression(binding, pageType))
                        .collect(Collectors.toSet());
        var htmlExpressions =
                compilation.bound().stream()
                        .filter(binding -> "Editor".equals(binding.fieldType()))
                        .map(binding -> htmlExpression(binding.fieldName(), pageType))
                        .collect(Collectors.toSet());
        var multilineExpressions =
                compilation.bound().stream()
                        .filter(binding -> "MultiLine".equals(binding.fieldType()))
                        .map(binding -> expression(binding, pageType))
                        .collect(Collectors.toSet());
        rejectDynamicComments(compilation.html());
        var document = Jsoup.parse(compilation.html());
        int instructions = 0;
        for (var element : document.getAllElements()) {
            String tag = element.tagName().toLowerCase(java.util.Locale.ROOT);
            if (tag.startsWith("th:") && !tag.equals("th:block"))
                throw new IllegalArgumentException("草稿包含非受控模板标签");
            for (var attribute : new ArrayList<>(element.attributes().asList())) {
                String key = attribute.getKey().toLowerCase(java.util.Locale.ROOT);
                boolean controlledText =
                        tag.equals("th:block")
                                        && !multilineExpressions.contains(attribute.getValue())
                                || tag.equals("span")
                                        && "true".equals(element.attr("data-cms-multiline"))
                                        && "white-space: pre-wrap".equals(element.attr("style"))
                                        && multilineExpressions.contains(attribute.getValue());
                boolean allowed =
                        key.equals("th:text")
                                        && controlledText
                                        && expected.contains(attribute.getValue())
                                || key.equals("th:utext")
                                        && tag.equals("th:block")
                                        && htmlExpressions.contains(attribute.getValue());
                boolean imageReference =
                        compilation.bound().stream()
                                .anyMatch(
                                        binding ->
                                                "Image".equals(binding.fieldType())
                                                        && mediaExpression(binding)
                                                                .equals(attribute.getValue()));
                boolean videoReference =
                        compilation.bound().stream()
                                .anyMatch(
                                        binding ->
                                                "Video".equals(binding.fieldType())
                                                        && mediaExpression(binding)
                                                                .equals(attribute.getValue()));
                allowed |=
                        key.equals("th:src")
                                        && (tag.equals("img") && imageReference
                                                || List.of("video", "source").contains(tag)
                                                        && videoReference)
                                || key.equals("th:poster") && tag.equals("video") && imageReference;
                boolean linkedBackground =
                        key.equals("th:style")
                                && compilation.bound().stream()
                                        .anyMatch(
                                                binding ->
                                                        "Image".equals(binding.fieldType())
                                                                && LinkedImageBackground.controlled(element, binding.fieldName()));
                boolean standaloneBackground = key.equals("th:style") && compilation.bound().stream()
                        .anyMatch(binding -> "Image".equals(binding.fieldType())
                                && LinkedImageBackground.controlledStandalone(
                                        element, binding, publicResources));
                allowed |= linkedBackground || standaloneBackground;
                if (linkedBackground) element.removeAttr(key);
                if (key.startsWith("data-th-") || key.startsWith("th:") && !allowed)
                    throw new IllegalArgumentException("草稿包含非受控模板指令");
                if (standaloneBackground || List.of("th:text", "th:utext", "th:src", "th:poster").contains(key)) {
                    instructions++;
                    element.removeAttr(key);
                }
            }
        }
        if (instructions != compilation.bound().size())
            throw new IllegalArgumentException("草稿绑定数量不一致");
        String remaining = document.outerHtml();
        for (String marker : List.of("${", "*{", "@{", "#{", "~{", "[[", "[("))
            if (remaining.contains(marker)) throw new IllegalArgumentException("草稿包含非受控内联表达式");
    }

    private static boolean inside(org.jsoup.nodes.Node node, Element region) {
        for (var parent = node; parent != null; parent = parent.parent())
            if (parent == region) return true;
        return false;
    }

    private static Element multilineTarget(Element region) {
        if (List.of("h1", "h2", "h3", "h4", "h5", "h6").contains(region.tagName())) {
            // 标题保留原标签和样式，只替换直接文本/br；不沿内联、交互或布局子树下钻。
            if (region.attributes().asList().stream()
                    .anyMatch(attribute -> !List.of("id", "class").contains(attribute.getKey())))
                return null;
            return region.childNodes().stream()
                            .allMatch(
                                    node ->
                                            node instanceof TextNode
                                                    || node instanceof Element element
                                                            && element.tagName().equals("br")
                                                            && element.attributes().size() == 0)
                    ? region
                    : null;
        }
        if (!List.of("div", "p", "span", "strong", "b", "em", "i", "u", "s", "small")
                .contains(region.tagName())) return null;
        Element current = region;
        while (true) {
            if (current.childNodes().stream()
                    .allMatch(
                            node ->
                                    node instanceof TextNode
                                            || node instanceof Element element
                                                    && element.tagName().equals("br")))
                return current;
            if (current.childrenSize() != 1) return null;
            Element child = current.child(0);
            if (!List.of("span", "strong", "b", "em", "i", "u", "s", "small")
                    .contains(child.tagName())) return null;
            for (var node : current.childNodes())
                if (node != child
                        && (!(node instanceof TextNode text) || !text.getWholeText().isBlank()))
                    return null;
            current = child;
        }
    }

    private static String multilineText(Element target) {
        var result = new StringBuilder();
        for (var node : target.childNodes()) {
            if (node instanceof TextNode text) result.append(text.getWholeText());
            else result.append('\n');
        }
        return result.toString();
    }

    /** 仅派生首次CMS值，编译模板继续保留旧fallback字节以维护所有已有凭据。 */
    static String initialMultilineText(
            Element region,
            TemplateContentUnitsService.Source source,
            TemplateContentUnitsService.Reference reference,
            String controlledFallback) {
        return initialMultilineText(region, source, reference, controlledFallback, false);
    }

    static String initialMultilineText(
            Element region,
            TemplateContentUnitsService.Source source,
            TemplateContentUnitsService.Reference reference,
            String controlledFallback,
            boolean reviewedDetail) {
        if (region == null
                || source == null
                || !"TEXT".equals(source.kind()))
            throw new IllegalArgumentException("多行初值缺少TEXT来源");
        if (reference.start() != 0 || reference.end() != source.rawValue().length()) {
            var range = reviewedDetail ? multilineValueRange(region, source.rawValue(), reference) : null;
            if (range == null) throw new IllegalArgumentException("多行初值缺少已审核的单节点值区间");
            String value = range.node().getWholeText().substring(range.start(), range.end());
            if (!value.equals(controlledFallback))
                throw new IllegalArgumentException("多行值区间与受控编译初值不一致");
            return value;
        }
        Element target = multilineTarget(region);
        if (target == null) throw new IllegalArgumentException("多行初值原DOM已不符合安全容器");
        String text = multilineText(target), raw = source.rawValue();
        int offset = text.indexOf(raw);
        if (offset < 0 || text.indexOf(raw, offset + 1) >= 0)
            throw new IllegalArgumentException("多行初值原DOM与来源不一致或有歧义");
        if (preformattedText(target)) return raw;
        return normalBrText(target, text, offset, offset + raw.length())
                ? headingText(target)
                : controlledFallback;
    }

    /** 只规范普通HTML的完整br文字；不把范围外br/内容纳入字段，也不清预格式化原文。 */
    private static boolean normalBrText(Element target, String text, int start, int end) {
        if (!text.substring(0, start).matches("[\\t\\n\\f\\r ]*")
                || !text.substring(end).matches("[\\t\\n\\f\\r ]*")) return false;
        if (preformattedText(target)) return false;
        int at = 0;
        boolean br = false;
        for (var node : target.childNodes()) {
            if (node instanceof TextNode value) at += value.getWholeText().length();
            else {
                if (at < start || at >= end) return false;
                br = true;
                at++;
            }
        }
        return br;
    }

    private static boolean preformattedText(Element target) {
        for (Element node = target; node != null; node = node.parent()) {
            if (List.of("pre", "textarea").contains(node.tagName())
                    || "preserve".equalsIgnoreCase(node.attr("xml:space"))) return true;
            var declarations =
                    java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*white-space\\s*:\\s*([^;]+)")
                            .matcher(node.attr("style"));
            while (declarations.find()) {
                String mode =
                        declarations
                                .group(1)
                                .replaceAll("(?i)\\s*!important\\s*", "")
                                .trim()
                                .toLowerCase(java.util.Locale.ROOT);
                if (!List.of("normal", "nowrap").contains(mode)) return true;
            }
        }
        return false;
    }

    private static String headingText(Element target) {
        var result = new StringBuilder();
        var line = new StringBuilder();
        for (var node : target.childNodes()) {
            if (node instanceof TextNode text) {
                line.append(text.getWholeText().replaceAll("[\\t\\n\\f\\r ]+", " "));
            } else {
                result.append(line.toString().replaceAll("^ +| +$", "")).append('\n');
                line.setLength(0);
            }
        }
        return result.append(line.toString().replaceAll("^ +| +$", "")).toString();
    }

    private void rejectDynamicComments(String html) {
        if (java.util.regex.Pattern.compile("<!--\\s*/\\*").matcher(html).find())
            throw new IllegalArgumentException("源包含未经确认的模板特殊注释，请先人工处理");
    }

    private String mediaExpression(TemplateFieldPlanService.Binding binding) {
        String name = binding.fieldName();
        if (!ContentExtensionFields.isName(name)
                && !("Image".equals(binding.fieldType()) && NativeImageFields.isName(name)))
            throw new IllegalArgumentException("目标不是允许的模板媒体字段");
        return "${cmsDraftMedia['" + name + "']}";
    }

    private String htmlExpression(String name, int pageType) {
        if (!ContentExtensionFields.isName(name)) {
            if (name == null || !name.matches("content[0-9]+|note[0-9]+"))
                throw new IllegalArgumentException("目标不是允许的正文字段");
            try {
                if (EntityFields.find(
                                        pageType == 0 ? OneshotEntity.class : ContentEntity.class,
                                        name)
                                .getType()
                        != String.class) throw new IllegalArgumentException("正文字段类型无效");
            } catch (NoSuchFieldException error) {
                throw new IllegalArgumentException("正文字段不存在");
            }
        }
        return "${cmsDraftHtml['" + name + "']}";
    }

    private String dateExpression(String name) {
        if (!ContentExtensionFields.isName(name))
            throw new IllegalArgumentException("日期仅支持明确扩展DateTime字段");
        return "${cmsDraftDates['" + name + "']}";
    }

    private String expression(TemplateFieldPlanService.Binding binding, int pageType) {
        if (MenuNameBinding.applies(binding))
            return "${cmsDraftMenuNames['" + MenuNameBinding.id(binding) + "']}";
        String fieldName = binding.fieldName();
        String owner = pageType == 0 ? "currentMenu" : "content";
        if (ContentExtensionFields.isName(fieldName))
            return "${" + owner + ".extensionValues['" + fieldName + "']}";
        if (fieldName == null || !fieldName.matches("title|singleLine[0-9]+|note[0-9]+"))
            throw new IllegalArgumentException("目标字段不在普通文本绑定范围");
        try {
            var field =
                    EntityFields.find(
                            pageType == 0 ? OneshotEntity.class : ContentEntity.class, fieldName);
            if (field.getType() != String.class) throw new IllegalArgumentException("目标不是文本字段");
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("目标文本字段不存在");
        }
        return "${" + owner + "." + fieldName + "}";
    }
}

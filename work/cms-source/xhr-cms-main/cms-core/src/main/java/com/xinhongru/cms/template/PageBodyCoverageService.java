package com.xinhongru.cms.template;

import com.xinhongru.cms.template.TemplateContentUnitsService.Reference;
import com.xinhongru.cms.template.TemplateContentUnitsService.Source;
import com.xinhongru.cms.template.TemplateContentUnitsService.Unit;
import com.xinhongru.cms.template.TemplateContentUnitsService.Workspace;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PageBodyCoverageService {
    private static final Set<String> KINDS =
            Set.of("TEXT", "IMAGE", "BACKGROUND_IMAGE", "HTML_REGION", "HTML_REGION_PENDING",
                    "VIDEO_SRC", "VIDEO_SOURCE", "VIDEO_POSTER");
    private final TemplateContentUnitsService content;

    public PageBodyCoverageService(TemplateContentUnitsService content) {
        this.content = content;
    }

    public record Range(int start, int end) {}

    public record Item(
            Source source,
            String state,
            String unitId,
            String reason,
            List<Range> uncoveredRanges,
            String relatedSourceId,
            boolean bindable,
            boolean recoveryAllowed) {}

    public record Coverage(
            String sourcePath,
            String packageVersion,
            String digest,
            long revision,
            List<Item> items,
            List<String> warnings) {}

    public Coverage read(
            String code, String version, String sourcePath, String digest, long revision) {
        if (sourcePath == null
                || sourcePath.isBlank()
                || sourcePath.length() > 1024
                || digest == null
                || !digest.matches("[a-f0-9]{64}")
                || revision < 0) throw new IllegalArgumentException("请选择有效原页及审核修订");
        Workspace workspace = content.load(code, version, sourcePath);
        if (!Objects.equals(digest, workspace.digest()) || revision != workspace.revision())
            throw changed();
        var ancestors = content.sourceAncestors(code, version, workspace);
        var items = items(workspace, ancestors);
        var current = content.load(code, version, sourcePath);
        if (!Objects.equals(digest, current.digest()) || revision != current.revision())
            throw changed();
        return new Coverage(sourcePath, version, digest, revision, items, workspace.warnings());
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "原页或已保存内容单元已变化，请重新读取再核对正文");
    }

    private record Use(Unit unit, Reference reference) {}

    private record Editor(Source source, Unit unit, Document html) {}

    static List<Item> items(Workspace workspace, Map<String, Set<String>> ancestors) {
        return items(
                workspace.sources(),
                workspace.revision() > 0 ? workspace.units() : List.of(),
                ancestors);
    }

    static List<Item> items(
            List<Source> sources, List<Unit> units, Map<String, Set<String>> ancestors) {
        var byId = sources.stream().collect(Collectors.toMap(Source::id, Function.identity()));
        var references = new HashMap<String, List<Use>>();
        var editors = new ArrayList<Editor>();
        for (var unit : units) {
            for (var reference : unit.sources()) {
                var source = byId.get(reference.sourceId());
                references
                        .computeIfAbsent(reference.sourceId(), key -> new ArrayList<>())
                        .add(new Use(unit, reference));
                if (!unit.ignored()
                        && "Editor".equals(unit.fieldType())
                        && "HTML_REGION".equals(source.kind())
                        && reference.start() == 0
                        && reference.end() == source.rawValue().length())
                    editors.add(
                            new Editor(source, unit, Jsoup.parseBodyFragment(source.rawValue())));
            }
        }
        var comparable =
                sources.stream()
                        .filter(PageBodyCoverageService::businessSource)
                        .filter(source -> !"HTML_REGION".equals(source.kind()))
                        .collect(Collectors.groupingBy(Source::rawValue));
        var activeSources =
                references.entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue().stream()
                                                .anyMatch(use -> !use.unit().ignored()))
                        .map(entry -> byId.get(entry.getKey()))
                        .toList();
        return sources.stream()
                .map(
                        source ->
                                classify(
                                        source,
                                        references,
                                        editors,
                                        comparable.getOrDefault(source.rawValue(), List.of()),
                                        activeSources,
                                        ancestors))
                .toList();
    }

    private static Item classify(
            Source source,
            Map<String, List<Use>> references,
            List<Editor> editors,
            List<Source> comparable,
            List<Source> activeSources,
            Map<String, Set<String>> ancestors) {
        if (!businessSource(source)) {
            String reason =
                    Boolean.TRUE.equals(source.nonBodyMetadata())
                            ? "原页面元数据或无障碍说明，保留原始语义"
                            : source.publicRegion() != null
                                            || Boolean.TRUE.equals(source.publicNavigation())
                                    ? "公共头尾或导航证据"
                                    : Boolean.TRUE.equals(source.interactionPending())
                                            ? "交互区域及其内容待业务核对，当前沿用原包"
                                            : "控件、脚本、地址或其他类型保留原文供专项核对";
            return new Item(source, "OUTSIDE_BODY", null, reason, List.of(), null, false, false);
        }
        var uses = references.getOrDefault(source.id(), List.of());
        var active = uses.stream().filter(use -> !use.unit().ignored()).toList();
        var remaining = uncovered(source.rawValue().length(), active);
        boolean bindable =
                !"HTML_REGION_PENDING".equals(source.kind())
                        && (!"BACKGROUND_IMAGE".equals(source.kind())
                                || Boolean.TRUE.equals(source.backgroundBindable()));
        if (!active.isEmpty() && remaining.isEmpty())
            return new Item(
                    source,
                    "COVERED_BY_UNIT",
                    active.getFirst().unit().id(),
                    "现有单元覆盖完整来源",
                    List.of(),
                    null,
                    bindable,
                    false);
        var ignored = uses.stream().filter(use -> use.unit().ignored()).findFirst();
        if (ignored.isPresent()) {
            boolean recoverable =
                    bindable
                            && uses.size() == 1
                            && mayRecover(
                                    source,
                                    ignored.get(),
                                    editors,
                                    comparable,
                                    activeSources,
                                    ancestors);
            return new Item(
                    source,
                    "IGNORED",
                    ignored.get().unit().id(),
                    "此来源已有保存的暂不绑定判断",
                    List.of(),
                    null,
                    bindable,
                    recoverable);
        }

        for (var editor : editors) {
            if (!source.id().equals(editor.source().id())
                    && inside(source, editor.source(), ancestors)
                    && editorContains(source, editor.html()))
                return new Item(
                        source,
                        "COVERED_BY_REGION",
                        editor.unit().id(),
                        "原值已由完整正文区域维护",
                        List.of(),
                        editor.source().id(),
                        bindable,
                        false);
        }
        String related = null;
        for (var other : comparable) {
            if (source.id().equals(other.id())
                    || !sameEvidenceType(source, other)
                    || !inside(source, other, ancestors) && !inside(other, source, ancestors))
                continue;
            var owner =
                    references.getOrDefault(other.id(), List.of()).stream()
                            .filter(
                                    use ->
                                            !use.unit().ignored()
                                                    && use.reference().start() == 0
                                                    && use.reference().end()
                                                            == other.rawValue().length())
                            .findFirst();
            if (owner.isPresent() && source.kind().equals(other.kind()))
                return new Item(
                        source,
                        "COVERED_BY_REGION",
                        owner.get().unit().id(),
                        "同一原值由嵌套位置的现有单元覆盖",
                        List.of(),
                        other.id(),
                        bindable,
                        false);
            if (related == null
                    && (inside(other, source, ancestors)
                            || "BACKGROUND_IMAGE".equals(source.kind())
                                    && "IMAGE".equals(other.kind()))) related = other.id();
        }
        String reason =
                !active.isEmpty()
                        ? "部分原文区间尚未进入当前草稿"
                        : related != null
                                ? "同一嵌套位置存在相同原值，核对代表来源后再加入"
                                : source.repeatGroup() != null
                                        ? "重复候选区域，先核对列表及记录归属"
                                        : "当前草稿尚未覆盖此主体来源";
        return new Item(source, "UNBOUND", null, reason, remaining, related, bindable, false);
    }

    private static boolean mayRecover(
            Source source,
            Use ignored,
            List<Editor> editors,
            List<Source> comparable,
            List<Source> activeSources,
            Map<String, Set<String>> ancestors) {
        if (ignored.unit().sources().size() != 1
                || ignored.reference().start() != 0
                || ignored.reference().end() != source.rawValue().length()
                || source.scriptInput() != null) return false;
        boolean compatible =
                switch (source.kind()) {
                    case "TEXT" ->
                            Set.of("SingleLine", "MultiLine", "DateTime")
                                    .contains(ignored.unit().fieldType());
                    case "IMAGE", "BACKGROUND_IMAGE" -> "Image".equals(ignored.unit().fieldType());
                    default -> false;
                };
        return compatible
                && editors.stream().noneMatch(editor -> inside(source, editor.source(), ancestors))
                && activeSources.stream()
                        .noneMatch(
                                active ->
                                        "TEXT".equals(source.kind())
                                                && "TEXT".equals(active.kind())
                                                && (inside(source, active, ancestors)
                                                                && active.rawValue()
                                                                        .contains(source.rawValue())
                                                        || inside(active, source, ancestors)
                                                                && source.rawValue()
                                                                        .contains(
                                                                                active.rawValue())))
                && comparable.stream()
                        .noneMatch(
                                other ->
                                        !source.id().equals(other.id())
                                                && sameEvidenceType(source, other)
                                                && (inside(source, other, ancestors)
                                                        || inside(other, source, ancestors)));
    }

    private static boolean businessSource(Source source) {
        return KINDS.contains(source.kind())
                && source.publicRegion() == null
                && !Boolean.TRUE.equals(source.publicNavigation())
                && !Boolean.TRUE.equals(source.nonBodyMetadata())
                && !Boolean.TRUE.equals(source.interactionPending())
                && !source.rawValue().isBlank()
                && (!"TEXT".equals(source.kind())
                        || source.rawValue().codePoints().anyMatch(Character::isLetterOrDigit));
    }

    private static boolean sameEvidenceType(Source source, Source other) {
        return source.kind().equals(other.kind())
                || Set.of("IMAGE", "BACKGROUND_IMAGE").contains(source.kind())
                        && Set.of("IMAGE", "BACKGROUND_IMAGE").contains(other.kind());
    }

    private static boolean inside(
            Source source, Source container, Map<String, Set<String>> ancestors) {
        return ancestors.getOrDefault(source.selector(), Set.of()).contains(container.selector());
    }

    private static boolean editorContains(Source source, Document document) {
        return switch (source.kind()) {
            case "TEXT" ->
                    document.body()
                            .text()
                            .contains(source.rawValue().replaceAll("\\s+", " ").strip());
            case "IMAGE" ->
                    document.select("img").stream()
                            .anyMatch(img -> source.rawValue().equals(img.attr("src")));
            default -> false;
        };
    }

    private static List<Range> uncovered(int length, List<Use> active) {
        List<Range> gaps = new ArrayList<>();
        int start = 0;
        for (Use use :
                active.stream()
                        .sorted(Comparator.comparingInt(row -> row.reference().start()))
                        .toList()) {
            if (start < use.reference().start())
                gaps.add(new Range(start, use.reference().start()));
            start = Math.max(start, use.reference().end());
        }
        if (start < length) gaps.add(new Range(start, length));
        return List.copyOf(gaps);
    }
}

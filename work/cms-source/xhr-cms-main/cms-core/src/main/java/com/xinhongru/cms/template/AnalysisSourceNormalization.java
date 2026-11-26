package com.xinhongru.cms.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 新AI草稿的来源证据规范化；不改原来源、不执行绑定、不修改人工确认。 */
final class AnalysisSourceNormalization {
    private static final Pattern SEGMENT =
            Pattern.compile(
                    "[a-z][a-z0-9-]*(?:\\.[\\p{L}\\p{N}_-]+)*(?::nth-child\\([1-9][0-9]*\\))?");
    private static final Pattern ID_ANCHOR = Pattern.compile("#[A-Za-z_][A-Za-z0-9_-]*");

    private AnalysisSourceNormalization() {}

    static boolean canonicalPath(String path) {
        if (path == null || path.length() > 4000) return false;
        String[] parts = path.split(" > ", -1);
        if (parts.length < 3
                || !parts[0].equals("html")
                || !(parts[1].equals("body") || parts[1].startsWith("body."))) return false;
        for (String part : parts) if (!SEGMENT.matcher(part).matches()) return false;
        return true;
    }

    static boolean within(String child, String parent) {
        return canonicalPath(child)
                && canonicalPath(parent)
                && (child.equals(parent) || child.startsWith(parent + " > "));
    }

    private static boolean canonicalTextPath(String path) {
        if (canonicalPath(path)) return true;
        if (path == null || path.length() > 4000) return false;
        String[] parts = path.split(" > ", -1);
        // 原DOM的cssSelector只在ID唯一时生成裸锚点；只扩TEXT，不扩大Editor区域资格。
        if (!ID_ANCHOR.matcher(parts[0]).matches()) return false;
        for (int i = 1; i < parts.length; i++)
            if (!SEGMENT.matcher(parts[i]).matches()) return false;
        return true;
    }

    private static boolean withinText(String child, String parent) {
        return canonicalTextPath(child)
                && canonicalTextPath(parent)
                && (child.equals(parent) || child.startsWith(parent + " > "));
    }

    static boolean independentFields(
            TemplateContentUnitsService.Source region,
            List<TemplateContentUnitsService.Unit> units,
            Map<String, TemplateContentUnitsService.Source> byId) {
        return units.stream()
                .anyMatch(
                        u ->
                                !u.ignored()
                                        && !Set.of("Editor", "MultiLine", "Image")
                                                .contains(u.fieldType())
                                        && u.sources().stream()
                                                .anyMatch(
                                                        ref -> {
                                                            var s = byId.get(ref.sourceId());
                                                            return s != null
                                                                    && within(
                                                                            s.selector(),
                                                                            region.selector());
                                                        }));
    }

    static List<TemplateContentUnitsService.Unit> normalize(
            List<TemplateContentUnitsService.Unit> units,
            List<TemplateContentUnitsService.Source> sources,
            List<String> warnings) {
        var byId = new HashMap<String, TemplateContentUnitsService.Source>();
        var paths = new HashMap<String, Integer>();
        sources.forEach(
                s -> {
                    byId.put(s.id(), s);
                    paths.merge(s.kind() + "\n" + s.selector(), 1, Integer::sum);
                });
        var collapsed =
                units.stream()
                        .map(unit -> unit.ignored() ? unit : collapse(unit, byId, paths, warnings))
                        .toList();
        var selected = collapseAcrossUnits(collapsed, byId, paths, warnings);
        var result = new ArrayList<TemplateContentUnitsService.Unit>();
        var seen = new HashMap<String, String>();
        int extraBudget = Math.max(0, 200 - units.size());
        for (var original : selected) {
            if (original.ignored()) {
                result.add(original);
                continue;
            }
            var unit = original;
            if (unit.fieldType().equals("SingleLine")
                    && unit.sources().stream()
                            .allMatch(r -> byId.get(r.sourceId()).kind().equals("TEXT"))
                    && unit.sources().stream()
                            .anyMatch(
                                    r -> {
                                        String value =
                                                byId.get(r.sourceId())
                                                        .rawValue()
                                                        .substring(r.start(), r.end());
                                        return value.lines()
                                                        .filter(line -> !line.isBlank())
                                                        .limit(2)
                                                        .count()
                                                > 1;
                                    }))
                unit =
                        changed(
                                unit,
                                unit.name(),
                                "MultiLine",
                                unit.sources(),
                                false,
                                "真实TEXT来源内含换行，SingleLine修为MultiLine；保留原始空白、顺序及限定词。",
                                warnings);
            if (Set.of("SingleLine", "MultiLine", "DateTime").contains(unit.fieldType())
                    && unit.sources().stream()
                            .anyMatch(ref -> !byId.get(ref.sourceId()).kind().equals("TEXT"))) {
                unit =
                        changed(
                                unit,
                                unit.name(),
                                unit.fieldType(),
                                unit.sources(),
                                false,
                                "来源类型待处理：普通文本/日期仅可引用TEXT；HTML_REGION只能作为Editor候选，LINK/媒体不能混入文本初值。不推测替代来源，不视为绑定已修复。",
                                warnings);
            }
            var expanded = new ArrayList<TemplateContentUnitsService.Unit>();
            if (unit.fieldType().equals("Editor")
                    && unit.sources().size() > 1
                    && completeDisjointRegions(unit, byId, paths)) {
                int index = 0;
                String audit =
                        "多个不重叠HTML_REGION拆为独立区域建议；原建议="
                                + unit.id()
                                + "，原来源="
                                + unit.sources().stream()
                                        .map(TemplateContentUnitsService.Reference::sourceId)
                                        .toList()
                                + "。相同文字不等于同记录，区域顺序仅定位，不推断业务阶段。";
                for (var ref : unit.sources()) {
                    String name = unit.name() + "（独立区域 " + (++index) + "）";
                    if (name.length() > 100 || notes(unit, audit).length() > 2000) {
                        expanded.clear();
                        break;
                    }
                    expanded.add(
                            new TemplateContentUnitsService.Unit(
                                    "unit-" + UUID.randomUUID(),
                                    name,
                                    "Editor",
                                    List.of(ref),
                                    false,
                                    notes(unit, audit)));
                }
                if (expanded.size() - 1 > extraBudget) {
                    expanded.clear();
                    warnings.add("独立区域拆分超过既有200单元上限，保留原建议待人工拆分。");
                } else if (!expanded.isEmpty()) {
                    extraBudget -= expanded.size() - 1;
                    warnings.add(audit + "各区域完整原值独立保留，待人工确认归属。");
                }
            }
            if (expanded.isEmpty()) {
                if (unit.fieldType().equals("Editor") && unit.sources().size() > 1)
                    unit =
                            changed(
                                    unit,
                                    unit.name(),
                                    unit.fieldType(),
                                    unit.sources(),
                                    false,
                                    "多来源Editor待处理：缺少唯一、完整且不重叠的安全区域证据，或拆分超出现有审计/名称/数量上限；不猜测合并初值与业务归属。",
                                    warnings);
                expanded.add(unit);
            }
            for (var current : expanded) {
                if (current.fieldType().equals("Editor")) {
                    boolean conflict =
                            current.sources().stream()
                                    .anyMatch(
                                            ref -> {
                                                var s = byId.get(ref.sourceId());
                                                return s.kind().equals("HTML_REGION")
                                                        && independentFields(s, units, byId);
                                            });
                    if (conflict)
                        current =
                                changed(
                                        current,
                                        current.name(),
                                        current.fieldType(),
                                        current.sources(),
                                        false,
                                        "结构化字段范围待处理：区域内仍有独立SingleLine/DateTime等字段候选；安全HTML不等于完整业务正文，不能用整体Editor掩盖结构化属性，也不自动忽略子项。请人工确认范围与字段粒度。",
                                        warnings);
                }
                if (current.sources().stream()
                        .allMatch(ref -> full(ref, byId.get(ref.sourceId())))) {
                    String key = current.fieldType() + "\n" + current.sources();
                    String first = seen.putIfAbsent(key, current.id());
                    if (first != null)
                        current =
                                changed(
                                        current,
                                        current.name(),
                                        current.fieldType(),
                                        current.sources(),
                                        true,
                                        "相同位置重复建议：同类型、同完整来源与范围，保留首项="
                                                + first
                                                + "；本项保留原业务名与来源并标忽略，可人工撤销。不是按全页文字合并。",
                                        warnings);
                }
                result.add(current);
            }
        }
        return result;
    }

    /** 仅在本次AI建议已选中的单一完整TEXT位置间收敛；不是全页按文字合并。 */
    private static List<TemplateContentUnitsService.Unit> collapseAcrossUnits(
            List<TemplateContentUnitsService.Unit> units,
            Map<String, TemplateContentUnitsService.Source> byId,
            Map<String, Integer> paths,
            List<String> warnings) {
        var groups = new HashMap<String, Map<String, TemplateContentUnitsService.Source>>();
        for (var unit : units) {
            if (unit.ignored() || unit.sources().size() != 1) continue;
            var ref = unit.sources().getFirst();
            var source = byId.get(ref.sourceId());
            if (!plainFull(ref, source, paths)) continue;
            groups.computeIfAbsent(
                            unit.fieldType() + "\n" + source.rawValue(), key -> new HashMap<>())
                    .put(source.id(), source);
        }
        var result = new ArrayList<TemplateContentUnitsService.Unit>();
        for (var unit : units) {
            if (unit.ignored() || unit.sources().size() != 1) {
                result.add(unit);
                continue;
            }
            var ref = unit.sources().getFirst();
            var source = byId.get(ref.sourceId());
            if (!plainFull(ref, source, paths)) {
                result.add(unit);
                continue;
            }
            var peers =
                    groups.get(unit.fieldType() + "\n" + source.rawValue()).values().stream()
                            .filter(
                                    other ->
                                            withinText(other.selector(), source.selector())
                                                    || withinText(
                                                            source.selector(), other.selector()))
                            .toList();
            boolean chain = true;
            for (var a : peers)
                for (var b : peers)
                    if (!withinText(a.selector(), b.selector())
                            && !withinText(b.selector(), a.selector())) chain = false;
            var leaf =
                    peers.stream()
                            .max(java.util.Comparator.comparingInt(s -> s.selector().length()))
                            .orElse(source);
            if (chain
                    && !leaf.id().equals(source.id())
                    && withinText(leaf.selector(), source.selector())) {
                unit =
                        changed(
                                unit,
                                unit.name(),
                                unit.fieldType(),
                                List.of(
                                        new TemplateContentUnitsService.Reference(
                                                leaf.id(), 0, leaf.rawValue().length())),
                                false,
                                "跨建议同DOM祖先链同值TEXT收敛：原来源="
                                        + source.id()
                                        + "，最内来源="
                                        + leaf.id()
                                        + "；最内来源已由本次同类型建议明确选中。保留原业务名、原建议ID和完整来源快照；不跨不同位置/分叉按文字合并，仍待人工核对。",
                                warnings);
            }
            result.add(unit);
        }
        return result;
    }

    private static TemplateContentUnitsService.Unit collapse(
            TemplateContentUnitsService.Unit unit,
            Map<String, TemplateContentUnitsService.Source> byId,
            Map<String, Integer> paths,
            List<String> warnings) {
        var removed = new HashSet<String>();
        for (var ref : unit.sources()) {
            var parent = byId.get(ref.sourceId());
            if (!plainFull(ref, parent, paths)) continue;
            var peers =
                    unit.sources().stream()
                            .filter(
                                    other -> {
                                        var s = byId.get(other.sourceId());
                                        return plainFull(other, s, paths)
                                                && s.rawValue().equals(parent.rawValue());
                                    })
                            .toList();
            boolean chain = true;
            for (var a : peers)
                for (var b : peers) {
                    String x = byId.get(a.sourceId()).selector(),
                            y = byId.get(b.sourceId()).selector();
                    if (!withinText(x, y) && !withinText(y, x)) chain = false;
                }
            if (chain
                    && peers.stream()
                            .anyMatch(
                                    other ->
                                            !other.sourceId().equals(ref.sourceId())
                                                    && !byId.get(other.sourceId())
                                                            .selector()
                                                            .equals(parent.selector())
                                                    && withinText(
                                                            byId.get(other.sourceId()).selector(),
                                                            parent.selector())))
                removed.add(ref.sourceId());
        }
        if (removed.isEmpty()) return unit;
        var refs =
                unit.sources().stream().filter(ref -> !removed.contains(ref.sourceId())).toList();
        return changed(
                unit,
                unit.name(),
                unit.fieldType(),
                refs,
                false,
                "同DOM祖先链同值TEXT去重，保留明确最内来源；原来源="
                        + unit.sources().stream()
                                .map(TemplateContentUnitsService.Reference::sourceId)
                                .toList()
                        + "，保留来源="
                        + refs.stream()
                                .map(TemplateContentUnitsService.Reference::sourceId)
                                .toList()
                        + "。来源快照未删除，不跨位置按文字合并。",
                warnings);
    }

    private static boolean plainFull(
            TemplateContentUnitsService.Reference ref,
            TemplateContentUnitsService.Source s,
            Map<String, Integer> paths) {
        return s != null
                && s.kind().equals("TEXT")
                && full(ref, s)
                && canonicalTextPath(s.selector())
                && paths.get(s.kind() + "\n" + s.selector()) == 1;
    }

    private static boolean full(
            TemplateContentUnitsService.Reference ref, TemplateContentUnitsService.Source source) {
        return source != null && ref.start() == 0 && ref.end() == source.rawValue().length();
    }

    private static boolean completeDisjointRegions(
            TemplateContentUnitsService.Unit unit,
            Map<String, TemplateContentUnitsService.Source> byId,
            Map<String, Integer> paths) {
        for (var a : unit.sources()) {
            var s = byId.get(a.sourceId());
            if (s == null
                    || !s.kind().equals("HTML_REGION")
                    || !full(a, s)
                    || !canonicalPath(s.selector())
                    || paths.get(s.kind() + "\n" + s.selector()) != 1) return false;
            for (var b : unit.sources())
                if (a != b) {
                    var t = byId.get(b.sourceId());
                    if (t == null
                            || within(s.selector(), t.selector())
                            || within(t.selector(), s.selector())) return false;
                }
        }
        return true;
    }

    private static String notes(TemplateContentUnitsService.Unit unit, String reason) {
        return reason
                + (unit.doubts() == null || unit.doubts().isEmpty()
                        ? ""
                        : " 原模型说明：" + unit.doubts());
    }

    private static TemplateContentUnitsService.Unit changed(
            TemplateContentUnitsService.Unit unit,
            String name,
            String type,
            List<TemplateContentUnitsService.Reference> refs,
            boolean ignored,
            String reason,
            List<String> warnings) {
        String notes = notes(unit, reason);
        if (notes.length() > 2000) {
            warnings.add("建议“" + unit.name() + "”审计说明已满，保留原建议待处理：" + reason);
            return unit;
        }
        warnings.add("建议“" + unit.name() + "”：" + reason + "仍须人工确认，不代表绑定完成。");
        return new TemplateContentUnitsService.Unit(unit.id(), name, type, refs, ignored, notes);
    }
}

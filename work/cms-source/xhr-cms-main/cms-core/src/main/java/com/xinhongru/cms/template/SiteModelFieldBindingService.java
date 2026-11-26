package com.xinhongru.cms.template;

import com.xinhongru.mapper.OneShotMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 仅将整站模型已审核的原文来源导出为现有单记录字段计划。 */
@Service
public class SiteModelFieldBindingService {
    private final SiteModelPlanService plans;
    private final SiteModelEvidenceService evidence;
    private final TemplateSeedService seeds;
    private final TemplateContentUnitsService units;
    private final TemplateFieldPlanService fields;
    @Autowired private SiteModelNavigationService navigation;
    @Autowired private OneShotMapper menus;

    public SiteModelFieldBindingService(
            SiteModelPlanService plans,
            SiteModelEvidenceService evidence,
            TemplateSeedService seeds,
            TemplateContentUnitsService units,
            TemplateFieldPlanService fields) {
        this.plans = plans;
        this.evidence = evidence;
        this.seeds = seeds;
        this.units = units;
        this.fields = fields;
    }

    public TemplateFieldPlanService.Plan preview(
            Long siteId,
            String code,
            String version,
            String sitePlanId,
            long revision,
            String modelKey,
            TemplateFieldPlanService.Request request,
            Runnable guard) {
        Objects.requireNonNull(guard).run();
        if (request == null || request.choices() == null)
            throw new IllegalArgumentException("请选择已确认模型及页面字段");
        var proposal = plans.confirmed(siteId, code, version, sitePlanId, revision).proposal();
        var model =
                proposal.models().stream()
                        .filter(item -> item.key().equals(modelKey))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("模型不属于此整站结构"));
        requireTarget(siteId, code, version, sitePlanId, revision, model, request.menuId(), guard);
        var uses =
                proposal.regions().stream()
                        .filter(
                                use ->
                                        use.maintenance() == null
                                                && use.sourcePath().equals(request.sourcePath())
                                                && use.modelKey().equals(modelKey))
                        .toList();
        if (uses.stream()
                .anyMatch(
                        use ->
                                SiteModelEvidenceService.publicRegion(
                                        use.sourcePath(), use.regionId())))
            throw new IllegalArgumentException("公共信息区域请在工作台进行公共信息审核与初值初始化");
        if (uses.size() != 1
                || !Set.of("SINGLE", "DETAIL").contains(uses.getFirst().mode())
                || uses.getFirst().records().size() != 1)
            throw new IllegalArgumentException("仅支持导出一个已确认的单记录区域，请选择单页或详情区域");

        var workspace = units.requireSaved(code, version, request.sourcePath());
        var page = evidence.page(code, version, request.sourcePath());
        if (workspace.revision() <= 0
                || workspace.revision() != request.unitsRevision()
                || !Objects.equals(workspace.digest(), request.digest())
                || !Objects.equals(page.digest(), request.digest()))
            throw new IllegalArgumentException("页面来源或人工修正已变化，请重新读取");

        var allowed = allowedBindings(model, uses.getFirst(), page);
        var sources =
                workspace.sources().stream()
                        .collect(
                                Collectors.toMap(
                                        TemplateContentUnitsService.Source::id,
                                        Function.identity()));
        var selectors = new LinkedHashSet<String>();
        var matches = new LinkedHashMap<String, SourceMatch>();
        var assigned = new LinkedHashSet<String>();
        for (var unit : workspace.units()) {
            if (unit.ignored()) continue;
            String choice = request.choices().get(unit.id());
            var binding = choice == null ? null : allowed.get(choice);
            if (binding == null
                    || !binding.field().type().equals(unit.fieldType())
                    || !assigned.add(choice))
                throw new IllegalArgumentException("内容项未对应已审核的唯一实际字段：" + unit.name());
            if (unit.sources().size() != 1)
                throw new IllegalArgumentException("整站字段绑定要求一个已审核原文来源：" + unit.name());
            var reference = unit.sources().getFirst();
            var source = sources.get(reference.sourceId());
            if (source == null
                    || !source.rawValue().equals(binding.value().rawValue())
                    || !matchesKind(source, binding.value())
                    || !matchesRange(uses.getFirst(), unit, source, reference, binding.value()))
                throw new IllegalArgumentException("内容项原文、范围或类型与已审核字段来源不一致：" + unit.name());
            selectors.add(source.selector());
            selectors.add(binding.value().selector());
            matches.put(
                    unit.id(),
                    new SourceMatch(unit.name(), source.selector(), binding.value().selector()));
        }
        if (matches.isEmpty() || !matches.keySet().equals(request.choices().keySet()))
            throw new IllegalArgumentException("字段映射须与当前页面未忽略的内容项一致");

        var ancestors =
                seeds.sourceAncestors(
                        code,
                        version,
                        request.sourcePath(),
                        request.digest(),
                        List.copyOf(selectors));
        for (var match : matches.values()) {
            if (!ancestors
                            .getOrDefault(match.workspaceSelector(), Set.of())
                            .contains(match.evidenceSelector())
                    || !ancestors
                            .getOrDefault(match.evidenceSelector(), Set.of())
                            .contains(match.workspaceSelector()))
                throw new IllegalArgumentException("内容项位置与已审核字段来源不一致：" + match.unitName());
        }

        return plans.withConfirmedPlan(
                siteId,
                code,
                version,
                sitePlanId,
                revision,
                () ->
                        fields.previewWithOrigin(
                                siteId,
                                code,
                                version,
                                request,
                                derived -> {
                                    if (!derived.canConfirm()
                                            || derived.bindings().stream()
                                                    .anyMatch(
                                                            row ->
                                                                    !"IGNORED".equals(row.action())
                                                                            && (!"REUSE"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .action())
                                                                                    || !Objects
                                                                                            .equals(
                                                                                                    row
                                                                                                            .fieldName(),
                                                                                                    request.choices()
                                                                                                            .get(
                                                                                                                    row
                                                                                                                            .unitId())))))
                                        throw new IllegalArgumentException("整站模型字段须先在对应栏目建立并能直接复用");
                                    guard.run();
                                    requireTarget(
                                            siteId,
                                            code,
                                            version,
                                            sitePlanId,
                                            revision,
                                            model,
                                            request.menuId(),
                                            guard);
                                    plans.registerDerivedPlan(
                                            siteId,
                                            code,
                                            version,
                                            sitePlanId,
                                            revision,
                                            derived.id());
                                }));
    }

    record ApprovedSlots(Map<String, String> choices, Set<String> reserved) {}

    ApprovedSlots approvedSlots(SiteModelPlanService.Plan plan, SiteModelPlanService.Model model,
            SiteModelPlanService.RegionUse use, TemplateContentUnitsService.Workspace workspace) {
        var page = evidence.page(plan.code(), plan.packageVersion(), workspace.sourcePath());
        if (!Objects.equals(page.digest(), workspace.digest()))
            throw new IllegalArgumentException("已审核模型区域原页已变化");
        var allowed = allowedBindings(model, use, page);
        var byId = workspace.sources().stream().collect(Collectors.toMap(
                TemplateContentUnitsService.Source::id, Function.identity()));
        var selectors = new LinkedHashSet<String>();
        var possible = new LinkedHashMap<String, Map<String, SourceMatch>>();
        for (var unit : workspace.units()) {
            if (unit.ignored() || unit.sources().size() != 1) continue;
            var ref = unit.sources().getFirst();
            var source = byId.get(ref.sourceId());
            var same = new LinkedHashMap<String, SourceMatch>();
            for (var entry : allowed.entrySet()) {
                var binding = entry.getValue();
                if (!unit.fieldType().equals(binding.field().type()) || source == null
                        || !source.rawValue().equals(binding.value().rawValue()) || !matchesKind(source, binding.value())
                        || !matchesRange(use, unit, source, ref, binding.value())) continue;
                selectors.add(source.selector());
                selectors.add(binding.value().selector());
                same.put(entry.getKey(), new SourceMatch(
                        unit.name(), source.selector(), binding.value().selector()));
            }
            if (!same.isEmpty()) possible.put(unit.id(), same);
        }
        if (possible.isEmpty()) return new ApprovedSlots(Map.of(), Set.copyOf(allowed.keySet()));
        var ancestors = seeds.sourceAncestors(plan.code(), plan.packageVersion(), workspace.sourcePath(),
                workspace.digest(), List.copyOf(selectors));
        var slots = new LinkedHashMap<String, String>();
        var claimed = new LinkedHashSet<String>();
        for (var entry : possible.entrySet()) {
            var same = entry.getValue().entrySet().stream().filter(candidate -> {
                var match = candidate.getValue();
                return ancestors.getOrDefault(match.workspaceSelector(), Set.of()).contains(match.evidenceSelector())
                        && ancestors.getOrDefault(match.evidenceSelector(), Set.of()).contains(match.workspaceSelector());
            }).toList();
            if (same.size() > 1 || same.size() == 1 && !claimed.add(same.getFirst().getKey()))
                throw new IllegalArgumentException("多个内容项占用同一已审核字段或同位置有多个已审核字段，请先核对原模型");
            if (same.size() == 1) slots.put(entry.getKey(), same.getFirst().getKey());
        }
        return new ApprovedSlots(Map.copyOf(slots), Set.copyOf(allowed.keySet()));
    }

    private void requireTarget(
            Long siteId,
            String code,
            String version,
            String planId,
            long revision,
            SiteModelPlanService.Model model,
            Long menuId,
            Runnable guard) {
        if (model.menuId() == null || menuId == null)
            throw new IllegalArgumentException("请先将模型关联到本站实际内容栏目");
        if (navigation == null && Objects.equals(model.menuId(), menuId)) return;
        if (navigation == null || menus == null) throw new IllegalStateException("栏目模型适用范围尚未配置");
        var menu = menus.selectActiveMenuModelBySite(siteId, Math.toIntExact(menuId));
        if (menu == null
                || menu.getSiteRootId() == null
                || !Objects.equals(siteId, menu.getSiteProjectId()))
            throw new IllegalArgumentException("目标栏目不属于本站或已删除");
        var scope =
                new SiteModelNavigationService.Scope(siteId, code, version, menu.getSiteRootId());
        if (!navigation
                .modelMenuIds(scope, planId, revision, model.key(), guard)
                .contains(menu.getId()))
            throw new IllegalArgumentException("目标栏目未获此内容模型的导航审核，请先核对整站结构");
    }

    private static Map<String, AllowedBinding> allowedBindings(
            SiteModelPlanService.Model model,
            SiteModelPlanService.RegionUse use,
            SiteModelEvidenceService.PageEvidence page) {
        var recordUse = use.records().getFirst();
        var record =
                page.regions().stream()
                        .filter(region -> use.regionId().equals(region.id()))
                        .flatMap(region -> region.records().stream())
                        .filter(source -> recordUse.recordId().equals(source.id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("页面区域或记录证据已变化"));
        var values =
                record.values().stream()
                        .collect(
                                Collectors.toMap(
                                        SiteModelEvidenceService.Value::id, Function.identity()));
        var result = new LinkedHashMap<String, AllowedBinding>();
        for (var field : model.fields()) {
            String id = recordUse.bindings().get(field.key());
            if (id == null || field.fieldName() == null || field.fieldName().isBlank()) continue;
            var value = values.get(id);
            if (value == null
                    || result.putIfAbsent(field.fieldName(), new AllowedBinding(field, value))
                            != null) throw new IllegalArgumentException("已审核字段的来源缺失或重复");
        }
        return result;
    }

    private static boolean matchesRange(
            SiteModelPlanService.RegionUse use,
            TemplateContentUnitsService.Unit unit,
            TemplateContentUnitsService.Source source,
            TemplateContentUnitsService.Reference reference,
            SiteModelEvidenceService.Value value) {
        if (reference.start() == 0 && reference.end() == source.rawValue().length()) return true;
        // 部分范围取自已保存且已校验的人工内容单元，仍须匹配完整审核原文及同一 DOM。
        return "DETAIL".equals(use.mode())
                && Set.of("SingleLine", "MultiLine").contains(unit.fieldType())
                && "TEXT".equals(source.kind())
                && "TEXT".equals(value.typeHint())
                && reference.start() >= 0
                && reference.end() > reference.start()
                && reference.end() <= source.rawValue().length();
    }

    private static boolean matchesKind(
            TemplateContentUnitsService.Source source, SiteModelEvidenceService.Value value) {
        if (source.scriptInput() != null) return false;
        return switch (source.kind()) {
            case "TEXT" ->
                    "text".equals(value.attribute())
                            && Set.of("TEXT", "DATE").contains(value.typeHint());
            case "HTML_REGION" ->
                    "html".equals(value.attribute()) && "HTML_REGION".equals(value.typeHint());
            case "LINK" -> "href".equals(value.attribute()) && "LINK".equals(value.typeHint());
            case "IMAGE" ->
                    Set.of("src", "data-src").contains(value.attribute())
                            && "IMAGE".equals(value.typeHint());
            case "VIDEO_POSTER" ->
                    "poster".equals(value.attribute()) && "IMAGE".equals(value.typeHint());
            case "VIDEO_SRC", "VIDEO_SOURCE" ->
                    "src".equals(value.attribute()) && "VIDEO".equals(value.typeHint());
            default -> false;
        };
    }

    private record SourceMatch(
            String unitName, String workspaceSelector, String evidenceSelector) {}

    private record AllowedBinding(
            SiteModelPlanService.Field field, SiteModelEvidenceService.Value value) {}
}

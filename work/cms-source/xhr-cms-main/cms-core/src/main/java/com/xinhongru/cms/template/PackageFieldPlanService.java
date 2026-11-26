package com.xinhongru.cms.template;

import com.xinhongru.cms.template.PackageDraftAnalysisService.Scope;
import com.xinhongru.cms.template.SiteModelPlanService.PackageFieldOrigin;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PackageFieldPlanService {
    private final PackageDraftAnalysisService packages;
    private final SiteModelPlanService plans;
    private final TemplateContentUnitsService units;
    private final TemplateFieldPlanService fields;
    private final SiteModelFieldBindingService structuralFields;

    public PackageFieldPlanService(
            PackageDraftAnalysisService packages,
            SiteModelPlanService plans,
            TemplateContentUnitsService units,
            TemplateFieldPlanService fields,
            SiteModelFieldBindingService structuralFields) {
        this.packages = packages;
        this.plans = plans;
        this.units = units;
        this.fields = fields;
        this.structuralFields = structuralFields;
    }

    public record Request(
            String packageVersion,
            Long packageRevision,
            Long fieldRevision,
            String structurePlanId,
            Long structurePlanRevision,
            Long navigationRevision,
            String nodeKey,
            TemplateFieldPlanService.Request request) {}

    public TemplateFieldPlanService.Plan preview(
            Scope scope, Integer rootMenuId, Request input, Runnable guard) {
        if (input == null
                || input.request() == null
                || input.packageRevision() == null
                || input.fieldRevision() == null
                || input.structurePlanId() == null
                || input.structurePlanRevision() == null
                || input.navigationRevision() == null
                || input.nodeKey() == null
                || input.request().sourcePath() == null
                || input.request().digest() == null
                || input.request().menuId() == null
                || rootMenuId == null
                || input.request().menuId() <= 0
                || input.request().menuId() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("请选择已确认页面、当前导航及真实栏目，再提供已保存的字段来源");
        guard.run();
        var request = input.request();
        var origin =
                new PackageFieldOrigin(
                        input.nodeKey(),
                        input.navigationRevision(),
                        rootMenuId,
                        request.sourcePath(),
                        request.menuId(),
                        input.fieldRevision(),
                        request.digest(),
                        request.unitsRevision());
        return plans.withConfirmedPlan(
                scope.siteId(),
                scope.code(),
                scope.packageVersion(),
                input.structurePlanId(),
                input.structurePlanRevision(),
                () -> {
                    var plan =
                            plans.confirmed(
                                    scope.siteId(),
                                    scope.code(),
                                    scope.packageVersion(),
                                    input.structurePlanId(),
                                    input.structurePlanRevision());
                    var evidence = requireEvidence(scope, plan, origin, input.packageRevision());
                    var modelByKey = new HashMap<String, SiteModelPlanService.Model>();
                    plan.proposal().models().forEach(model -> modelByKey.put(model.key(), model));
                    var homeModel = Objects.equals(evidence.node().menuId(), origin.rootMenuId())
                            ? SiteModelPlanService.homePackageFieldModel(plan, origin) : null;
                    var uses =
                            plan.proposal().regions().stream()
                                    .filter(use -> use.sourcePath().equals(origin.sourcePath()))
                                    .filter(
                                            use ->
                                                    use.maintenance() == null
                                                            && Set.of("SINGLE", "DETAIL")
                                                                    .contains(use.mode()))
                                    .filter(
                                            use ->
                                                    !SiteModelEvidenceService.publicRegion(
                                                            use.sourcePath(), use.regionId()))
                                    .filter(
                                            use ->
                                                    homeModel != null
                                                            ? Objects.equals(use.modelKey(), homeModel.key())
                                                            : Objects.equals(
                                                                    use.modelKey(),
                                                                    evidence.node().modelKey())
                                                            || modelByKey.get(use.modelKey())
                                                                            != null
                                                                    && Objects.equals(
                                                                            modelByKey
                                                                                    .get(
                                                                                            use
                                                                                                    .modelKey())
                                                                                    .menuId(),
                                                                            origin.menuId()))
                                    .toList();
                    if (uses.size() > 1) throw conflict("此页有多个已审核模型区域，请先核对对应区域后生成字段计划");
                    var choices =
                            new HashMap<>(
                                    request.choices() == null
                                            ? Map.<String, String>of()
                                            : request.choices());
                    Set<String> lockedUnits = Set.of();
                    if (!uses.isEmpty() && uses.getFirst().records().size() == 1) {
                        var use = uses.getFirst();
                        var model = modelByKey.get(use.modelKey());
                        var approved =
                                structuralFields.approvedSlots(
                                        plan, model, use, evidence.workspace());
                        lockedUnits = approved.choices().keySet();
                        for (var choice : choices.entrySet())
                            if (choice.getValue() != null
                                    && approved.reserved().contains(choice.getValue())
                                    && !Objects.equals(
                                            approved.choices().get(choice.getKey()),
                                            choice.getValue())) throw conflict("已审核模型字段仅可复用其原证据单元");
                        approved.choices()
                                .forEach(
                                        (unit, field) -> {
                                            if (choices.containsKey(unit)
                                                    && !Objects.equals(field, choices.get(unit)))
                                                throw conflict("此单元已有审核通过的底层字段，请保留原有映射");
                                            choices.put(unit, field);
                                        });
                        long active =
                                evidence.workspace().units().stream()
                                        .filter(unit -> !unit.ignored())
                                        .count();
                        if (active == approved.choices().size())
                            return structuralFields.preview(
                                    scope.siteId(),
                                    scope.code(),
                                    scope.packageVersion(),
                                    plan.id(),
                                    plan.revision(),
                                    model.key(),
                                    withChoices(request, choices),
                                    guard);
                    }
                    return fields.previewWithExplicitCreation(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            withChoices(request, choices),
                            lockedUnits,
                            draft -> {
                                guard.run();
                                requireEvidence(scope, plan, origin, input.packageRevision());
                                plans.registerPackageFieldPlan(plan, draft.id(), origin);
                            });
                });
    }

    public void assertCurrent(TemplateFieldPlanService.Plan fieldPlan) {
        var origin =
                plans.packageFieldOrigin(
                        fieldPlan.siteId(),
                        fieldPlan.code(),
                        fieldPlan.packageVersion(),
                        fieldPlan.id());
        if (origin == null) return;
        if (!Objects.equals(origin.sourcePath(), fieldPlan.sourcePath())
                || !Objects.equals(origin.menuId(), fieldPlan.menuId())
                || !Objects.equals(origin.digest(), fieldPlan.digest())
                || origin.unitsRevision() != fieldPlan.unitsRevision())
            throw conflict("字段计划与整包审核回执不一致");
        var structure =
                plans.confirmedDerivedPlan(
                        fieldPlan.siteId(),
                        fieldPlan.code(),
                        fieldPlan.packageVersion(),
                        fieldPlan.id());
        var scope = new Scope(fieldPlan.siteId(), fieldPlan.code(), fieldPlan.packageVersion());
        requireEvidence(scope, structure, origin, null);
    }

    private record Evidence(
            SiteModelNavigationService.Node node,
            TemplateContentUnitsService.Workspace workspace) {}

    private static TemplateFieldPlanService.Request withChoices(
            TemplateFieldPlanService.Request original, Map<String, String> choices) {
        return new TemplateFieldPlanService.Request(
                original.sourcePath(),
                original.digest(),
                original.unitsRevision(),
                original.menuId(),
                choices);
    }

    private Evidence requireEvidence(
            Scope scope, Plan plan, PackageFieldOrigin origin, Long packageRevision) {
        var state = packages.recoveredApplicationState(scope);
        if (plan == null
                || !Objects.equals(state.structurePlanId(), plan.id())
                || !Objects.equals(state.structurePlanRevision(), plan.revision())
                || packageRevision != null && state.revision() != packageRevision)
            throw conflict("整包审核或结构版本已变化，请重新读取当前整包字段");
        var node = plans.requirePackageFieldNode(plan, origin);
        var detail = packages.page(scope, origin.sourcePath());
        if (!"READY".equals(detail.page().state())
                || !"CONFIRMED".equals(detail.page().review())
                || !Objects.equals(detail.page().reviewMenuId(), origin.menuId())
                || detail.fieldRevision() != origin.fieldRevision())
            throw conflict("请在整包先确认本页及实际维护栏目，再读取最新字段修订");
        var workspace =
                units.requireSaved(scope.code(), scope.packageVersion(), origin.sourcePath());
        if (workspace.revision() <= 0
                || workspace.revision() != origin.unitsRevision()
                || !Objects.equals(workspace.digest(), origin.digest())
                || detail.results().isEmpty()
                || detail.results().stream()
                        .anyMatch(
                                result ->
                                        !Objects.equals(workspace.digest(), result.digest())
                                                || result.revision() > workspace.revision()))
            throw conflict("原页或已保存人工单元版本已变化，请核对后保存当前页面");
        var byId = new HashMap<String, TemplateContentUnitsService.Source>();
        workspace.sources().forEach(source -> byId.put(source.id(), source));
        TemplateContentUnitsService.validate(workspace.units(), workspace.sources());
        if (workspace.units().stream().noneMatch(unit -> !unit.ignored()))
            throw new IllegalArgumentException("请先采纳并保存至少一项可维护单元");
        if (workspace.units().stream()
                .filter(unit -> !unit.ignored())
                .flatMap(unit -> unit.sources().stream())
                .anyMatch(
                        ref ->
                                !TemplateContentUnitsService.businessCandidate(
                                        byId.get(ref.sourceId()))))
            throw new IllegalArgumentException("公共或交互来源须走专用审核，不能作为本单页的字段");
        return new Evidence(node, workspace);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

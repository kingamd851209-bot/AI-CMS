package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelNavigationService.Navigation;
import com.xinhongru.cms.template.SiteModelNavigationService.Node;
import com.xinhongru.cms.template.SiteModelNavigationService.Scope;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.mapper.ContentMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WorkbenchChecklistEvidenceService {
    private final SiteModelPlanService plans;
    private final SiteModelNavigationService navigation;
    private final TemplateFieldPlanService fields;
    private final TextBindingDraftService drafts;
    private final PublicNavigationLinkCompiler links;
    private final ContentMapper content;

    public WorkbenchChecklistEvidenceService(
            SiteModelPlanService plans,
            SiteModelNavigationService navigation,
            TemplateFieldPlanService fields,
            TextBindingDraftService drafts,
            PublicNavigationLinkCompiler links,
            ContentMapper content) {
        this.plans = plans;
        this.navigation = navigation;
        this.fields = fields;
        this.drafts = drafts;
        this.links = links;
        this.content = content;
    }

    public record Request(
            String packageVersion,
            long planRevision,
            long navigationRevision,
            List<Target> targets) {}

    public record Target(Long menuId, String sourcePath, String nodeKey, String kind) {}

    public record Evidence(
            Long menuId,
            String sourcePath,
            String nodeKey,
            String kind,
            String state,
            Integer recordCount,
            String listSampleOwnership,
            Long sampleOwnerMenuId,
            String fieldPlanId,
            String fieldPhase,
            Integer landingCandidateCount,
            String reason) {}

    public record Result(List<Evidence> items) {}

    public Result read(Scope scope, String planId, Request request, Runnable guard) {
        if (request == null || request.targets() == null || request.targets().size() > 100)
            throw new IllegalArgumentException("证据查询每次最多 100 项");
        if (!Objects.equals(scope.packageVersion(), request.packageVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "页面包版本已变化");
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
        var nav = navigation.get(scope, planId, guard);
        if (!"CONFIRMED".equals(plan.status())
                || !navigation.hasOwnAppliedRevision(scope, planId, nav, plan.revision())
                || plan.revision() != request.planRevision()
                || nav.revision() != request.navigationRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已应用导航或结构审核版本已变化");
        if (request.targets().stream()
                .anyMatch(
                        target ->
                                target == null
                                        || target.menuId() == null
                                        || target.menuId() <= 0
                                        || target.menuId() > Integer.MAX_VALUE
                                        || target.sourcePath() == null
                                        || target.sourcePath().isBlank()
                                        || target.sourcePath().length() > 1024
                                        || target.nodeKey() != null
                                                && target.nodeKey().length() > 128
                                        || !Set.of("list", "field", "landing")
                                                .contains(Objects.toString(target.kind(), ""))))
            throw new IllegalArgumentException("清单证据查询目标无效");
        var results =
                request.targets().stream()
                        .distinct()
                        .map(target -> readTarget(scope, plan, nav, target))
                        .toList();
        guard.run();
        if (plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId).revision()
                != plan.revision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "结构审核版本已变化");
        return new Result(results);
    }

    private Evidence readTarget(Scope scope, Plan plan, Navigation nav, Target target) {
        var node =
                nav.nodes().stream()
                        .filter(
                                item ->
                                        item.menuId() != null
                                                && item.menuId().longValue() == target.menuId()
                                                && (target.nodeKey() == null
                                                        || target.nodeKey().equals(item.key()))
                                                && !item.drift())
                        .findFirst()
                        .orElse(null);
        if (node == null || target.menuId().longValue() == scope.rootMenuId().longValue())
            return unavailable(target, "目标尚未对应已应用栏目");
        try {
            return switch (target.kind()) {
                case "field" -> fieldEvidence(scope, target, plan, node);
                case "list" ->
                        matchingListSource(node, target)
                                ? listEvidence(scope, target, plan, nav)
                                : unavailable(target, "原页或用途与此栏目不一致");
                default ->
                        matchingListSource(node, target)
                                ? new Evidence(
                                        target.menuId(),
                                        target.sourcePath(),
                                        target.nodeKey(),
                                        target.kind(),
                                        "VERIFIED",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        links.landingCandidates(
                                                        scope,
                                                        plan.id(),
                                                        target.sourcePath(),
                                                        node.key())
                                                .size(),
                                        null)
                                : unavailable(target, "原页或用途与此栏目不一致");
            };
        } catch (IllegalArgumentException
                | IllegalStateException
                | ResponseStatusException unavailable) {
            return unavailable(target, "此项证据暂不可核实，请打开对应面板检查");
        }
    }

    private static boolean matchingListSource(Node node, Target target) {
        return node.kind() == SiteModelNavigationService.Kind.LIST
                && Objects.equals(node.sourcePath(), target.sourcePath());
    }

    private Evidence listEvidence(Scope scope, Target target, Plan plan, Navigation nav) {
        return listResult(scope, target, listSampleOwner(plan, nav, target.sourcePath()));
    }

    static Long listSampleOwner(Plan plan, Navigation nav, String sourcePath) {
        if (sourcePath == null) return null;
        var owners =
                plan.proposal().regions().stream()
                        .filter(
                                region ->
                                        "LIST".equals(region.mode())
                                                && region.maintenance() == null
                                                && sourcePath.equals(region.sourcePath())
                                                && !region.records().isEmpty())
                        .flatMap(
                                region ->
                                        plan.proposal().models().stream()
                                                .filter(
                                                        model ->
                                                                region.modelKey()
                                                                                .equals(model.key())
                                                                        && model.menuId() != null))
                        .map(SiteModelPlanService.Model::menuId)
                        .distinct()
                        .toList();
        Long owner = owners.size() == 1 ? owners.getFirst() : null;
        if (owner != null
                && nav.nodes().stream()
                        .noneMatch(
                                node ->
                                        !node.drift()
                                                && node.menuId() != null
                                                && node.menuId().longValue() == owner
                                                && sourcePath.equals(node.sourcePath()))) {
            return null;
        }
        return owner;
    }

    private Evidence listResult(Scope scope, Target target, Long owner) {
        int count = content.countNavigationRecords(scope.siteId(), target.menuId().intValue());
        String ownership =
                owner == null
                        ? "UNKNOWN"
                        : owner.equals(target.menuId()) ? "CURRENT_MENU" : "OTHER_MENU";
        return new Evidence(
                target.menuId(),
                target.sourcePath(),
                target.nodeKey(),
                target.kind(),
                "VERIFIED",
                count,
                ownership,
                owner,
                null,
                null,
                null,
                null);
    }

    private Evidence fieldEvidence(Scope scope, Target target, Plan plan, Node node) {
        if (!Objects.equals(node.sourcePath(), target.sourcePath())
                && plan.proposal().regions().stream()
                        .noneMatch(
                                region ->
                                        target.sourcePath().equals(region.sourcePath())
                                                && Set.of("SINGLE", "DETAIL")
                                                        .contains(region.mode())
                                                && plan.proposal().models().stream()
                                                        .anyMatch(
                                                                model ->
                                                                        model.key()
                                                                                        .equals(
                                                                                                region
                                                                                                        .modelKey())
                                                                                && target.menuId()
                                                                                        .equals(
                                                                                                model
                                                                                                        .menuId()))))
            return unavailable(target, "原页未对应此栏目的审核计划");
        var recent =
                fields.recent(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        target.sourcePath(),
                        target.menuId());
        boolean stale = false;
        for (var item : recent.plans()) {
            if (!Set.of("CONFIRMED", "CONFIRMED_WITH_PENDING").contains(item.status())) continue;
            try {
                var stored =
                        fields.read(
                                scope.siteId(), scope.code(), scope.packageVersion(), item.id());
                if (!target.menuId().equals(stored.menuId())
                        || !target.sourcePath().equals(stored.sourcePath())) continue;
                var recovery =
                        drafts.recovery(
                                scope.siteId(), scope.code(), scope.packageVersion(), item.id());
                int conflicts = sourceConflicts(stored);
                String phase =
                        "INITIALIZED".equals(recovery.phase())
                                        && item.id().equals(recovery.activePlanId())
                                ? "INITIALIZED"
                                : conflicts > 0 ? "SOURCE_CONFLICT" : "CONFIRMED";
                return new Evidence(
                        target.menuId(),
                        target.sourcePath(),
                        target.nodeKey(),
                        target.kind(),
                        "VERIFIED",
                        null,
                        null,
                        null,
                        item.id(),
                        phase,
                        null,
                        "SOURCE_CONFLICT".equals(phase) ? "处理 " + conflicts + " 处原文冲突" : null);
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | ResponseStatusException unreadable) {
                stale = true;
            }
        }
        if (stale || recent.truncated() || !recent.warnings().isEmpty())
            return unavailable(target, "字段计划证据不完整或已变化");
        return new Evidence(
                target.menuId(),
                target.sourcePath(),
                target.nodeKey(),
                target.kind(),
                "VERIFIED",
                null,
                null,
                null,
                null,
                "NONE",
                null,
                null);
    }

    private static int sourceConflicts(TemplateFieldPlanService.Plan plan) {
        if (plan.origins() != null && !plan.origins().isEmpty()) return 0;
        var textSources =
                plan.sources().stream()
                        .filter(source -> "TEXT".equals(source.kind()))
                        .map(TemplateContentUnitsService.Source::id)
                        .collect(Collectors.toSet());
        var bySource =
                plan.bindings().stream()
                        .filter(
                                row ->
                                        !"IGNORED".equals(row.action())
                                                && !"PENDING_TYPE".equals(row.action()))
                        .flatMap(row -> row.sources().stream())
                        .filter(ref -> textSources.contains(ref.sourceId()))
                        .collect(
                                Collectors.groupingBy(
                                        TemplateContentUnitsService.Reference::sourceId));
        int count = 0;
        for (var ranges : bySource.values()) {
            ranges.sort(Comparator.comparingInt(TemplateContentUnitsService.Reference::start));
            int end = -1;
            boolean counted = false;
            for (var range : ranges) {
                if (range.start() >= end) {
                    end = range.end();
                    counted = false;
                } else {
                    if (!counted) count++;
                    counted = true;
                    end = Math.max(end, range.end());
                }
            }
        }
        return count;
    }

    private static Evidence unavailable(Target target, String reason) {
        return new Evidence(
                target.menuId(),
                target.sourcePath(),
                target.nodeKey(),
                target.kind(),
                "UNVERIFIED",
                null,
                null,
                null,
                null,
                null,
                null,
                reason);
    }
}

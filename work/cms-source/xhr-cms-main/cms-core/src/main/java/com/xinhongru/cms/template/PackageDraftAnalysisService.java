package com.xinhongru.cms.template;

import com.xinhongru.mapper.OneShotMapper;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PackageDraftAnalysisService {
    private static final int MAX_RESULT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = 8 * 1024 * 1024;
    private static final String APPLIED_NAVIGATION_REASON = "已应用导航的唯一原页对应当前有效栏目，待人工审核";
    static final int WHOLE_PAGE_SOURCE_LIMIT = 120;
    private final TemplateSeedService seeds;
    private final SiteModelPlanService plans;
    private final TemplateDomainAgentService agent;
    private final TemplateContentUnitsService units;
    private final OneShotMapper menus;
    private final Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Scope running;

    public record Scope(long siteId, String code, String packageVersion) {}

    public record State(
            String packageVersion,
            long revision,
            String state,
            String structurePlanId,
            Long structurePlanRevision,
            String structureStatus,
            String structureMessage,
            List<Page> pages,
            String updatedAt) {}

    public record Page(
            String sourcePath,
            String state,
            Long menuId,
            String candidateName,
            List<String> candidateNames,
            String reason,
            String review,
            Long reviewMenuId,
            String reviewNote,
            int observedSources,
            int totalSources,
            int unitCount,
            Long recommendedMenuId,
            List<String> structureCandidateNames) {
        public Page(
                String sourcePath,
                String state,
                Long menuId,
                String candidateName,
                List<String> candidateNames,
                String reason,
                String review,
                Long reviewMenuId,
                String reviewNote,
                int observedSources,
                int totalSources,
                int unitCount) {
            this(
                    sourcePath,
                    state,
                    menuId,
                    candidateName,
                    candidateNames,
                    reason,
                    review,
                    reviewMenuId,
                    reviewNote,
                    observedSources,
                    totalSources,
                    unitCount,
                    null,
                    null);
        }
    }

    public record Detail(
            Page page,
            List<TemplateDomainAgentService.Result> results,
            long fieldRevision,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<TemplateDomainAgentService.Result> originalResults) {}

    public record FieldEdit(
            Integer resultIndex,
            String unitId,
            String name,
            String fieldType,
            Boolean ignored,
            String doubts) {}

    private record FieldKey(int resultIndex, String unitId) {}

    private record FieldEdits(long revision, List<FieldEdit> edits) {}

    public record ImportPage(
            String sourcePath,
            List<TemplateDomainAgentService.Result> results,
            String reason,
            List<String> suggestedOwners) {}

    private record Results(List<TemplateDomainAgentService.Result> items) {}

    public PackageDraftAnalysisService(
            TemplateSeedService seeds,
            SiteModelPlanService plans,
            TemplateDomainAgentService agent,
            TemplateContentUnitsService units,
            OneShotMapper menus,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-package-analysis")
                    String root) {
        this.seeds = seeds;
        this.plans = plans;
        this.agent = agent;
        this.units = units;
        this.menus = menus;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    public synchronized State get(Scope scope) {
        return withRecommendations(scope, recoveredState(scope));
    }

    private State recoveredState(Scope scope) {
        State data = load(scope, true);
        if (!"RUNNING".equals(data.state()) || scope.equals(running)) return data;
        return snapshot(
                data,
                data.revision(),
                "PAUSED",
                data.structurePlanId(),
                data.structurePlanRevision(),
                "RUNNING".equals(data.structureStatus()) ? "PENDING" : data.structureStatus(),
                "已保留完成页面，可继续分析",
                data.pages().stream()
                        .map(
                                page ->
                                        "RUNNING".equals(page.state())
                                                ? progress(page, "PENDING", page.reason())
                                                : page)
                        .toList());
    }

    private State withRecommendations(Scope scope, State state) {
        if (state.structurePlanId() == null) return state;
        SiteModelPlanService.Plan plan;
        try {
            plan =
                    plans.get(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            state.structurePlanId());
        } catch (ResponseStatusException missing) {
            if (missing.getStatusCode().value() != 404) throw missing;
            return state;
        }
        if (plan == null || plan.revision() != state.structurePlanRevision()) return state;
        var navigationNames = plans.savedNavigationNames(plan);
        var applied = appliedRecommendations(scope, plan);
        var pages =
                state.pages().stream()
                        .map(
                                page -> {
                                    var recommendation =
                                            recommend(scope, plan, page, navigationNames, applied);
                                    if (recommendation.menuId() == null
                                            && recommendation.candidateNames().isEmpty())
                                        return page;
                                    return new Page(
                                            page.sourcePath(),
                                            page.state(),
                                            page.menuId(),
                                            page.candidateName(),
                                            page.candidateNames(),
                                            APPLIED_NAVIGATION_REASON.equals(
                                                            recommendation.reason())
                                                    ? recommendation.reason()
                                                    : page.reason(),
                                            page.review(),
                                            page.reviewMenuId(),
                                            page.reviewNote(),
                                            page.observedSources(),
                                            page.totalSources(),
                                            page.unitCount(),
                                            recommendation.menuId(),
                                            recommendation.candidateNames());
                                })
                        .toList();
        return new State(
                state.packageVersion(),
                state.revision(),
                state.state(),
                state.structurePlanId(),
                state.structurePlanRevision(),
                state.structureStatus(),
                state.structureMessage(),
                pages,
                state.updatedAt());
    }

    public State attachModelDraft(
            Scope scope, long revision, String planId, Long planRevision, Runnable guard) {
        synchronized (plans) {
            synchronized (this) {
                guard.run();
                var current = recoveredState(scope);
                if (scope.equals(running)) throw conflict("当前整包仍在分析，请完成后接续模型草稿");
                var draft = selected(scope, planId, planRevision);
                if (draft == null) throw new IllegalArgumentException("请选择已保存的新模型草稿");
                if (Objects.equals(current.structurePlanId(), planId)
                        && Objects.equals(current.structurePlanRevision(), planRevision))
                    return current;
                checkRevision(current, revision);
                var origin = plans.modelDraftOrigin(draft);
                boolean navigationAppend = plans.isNavigationAppend(draft);
                boolean samePlan = Objects.equals(current.structurePlanId(), planId);
                if (origin == null && (!samePlan || navigationAppend))
                    throw conflict("模型草稿的父结构或版本不同，请重新读取整包结构");
                SiteModelPlanService.Plan source;
                if (samePlan) {
                    var previousRevision = current.structurePlanRevision();
                    if (previousRevision == null || previousRevision >= draft.revision())
                        throw conflict("只能接续同一模型草稿的新修订");
                    source =
                            plans.get(
                                    scope.siteId(),
                                    scope.code(),
                                    scope.packageVersion(),
                                    planId,
                                    previousRevision);
                    if (origin == null) {
                        if (!confirmedSupplement(source, draft))
                            throw conflict("同一整站计划只能接续保留原审核的已确认结构增补");
                        plans.confirmed(
                                scope.siteId(), scope.code(), scope.packageVersion(), planId,
                                draft.revision());
                    } else {
                        var parent =
                                plans.get(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        origin.sitePlanId(),
                                        origin.revision());
                        if (!navigationAppend
                                && !Objects.equals(parent.catalogDigest(), source.catalogDigest()))
                            throw conflict("模型草稿与派生时的原页目录不一致");
                    }
                } else {
                    if (!Objects.equals(current.structurePlanId(), origin.sitePlanId())
                            || !Objects.equals(current.structurePlanRevision(), origin.revision()))
                        throw conflict("模型草稿的父结构或版本不同，请重新读取整包结构");
                    source =
                            plans.continuationBase(
                                    scope.siteId(),
                                    scope.code(),
                                    scope.packageVersion(),
                                    origin.sitePlanId(),
                                    origin.revision());
                }
                if (!navigationAppend
                        && !Objects.equals(source.catalogDigest(), draft.catalogDigest()))
                    throw conflict("新模型草稿与整包原页目录不一致");
                var pages = current.pages();
                String status = current.state();
                if (navigationAppend) {
                    plans.confirmed(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            planId,
                            draft.revision());
                    var present =
                            pages.stream()
                                    .map(Page::sourcePath)
                                    .collect(java.util.stream.Collectors.toSet());
                    var pending =
                            catalogPages(scope).stream()
                                    .filter(page -> !present.contains(page.sourcePath()))
                                    .toList();
                    if (!pending.isEmpty()) {
                        var merged = new ArrayList<>(pages);
                        merged.addAll(pending);
                        pages = List.copyOf(merged);
                        status = stateOf(pages, true);
                    }
                }
                guard.run();
                return save(scope, current, status, draft, "READY", null, pages);
            }
        }
    }

    private static boolean confirmedSupplement(
            SiteModelPlanService.Plan previous, SiteModelPlanService.Plan next) {
        if (!"CONFIRMED".equals(previous.status()) || !"CONFIRMED".equals(next.status())
                || !Objects.equals(previous.catalogDigest(), next.catalogDigest())) return false;
        var before = previous.proposal();
        var after = next.proposal();
        return (after.models().size() > before.models().size()
                        || after.regions().size() > before.regions().size())
                && Objects.equals(before.summary(), after.summary())
                && preservedPrefix(before.models(), after.models())
                && preservedPrefix(before.regions(), after.regions())
                && preservedPrefix(before.relations(), after.relations())
                && preservedPrefix(before.issues(), after.issues())
                && preservedPrefix(before.pages(), after.pages());
    }

    private static boolean preservedPrefix(List<?> before, List<?> after) {
        return before == null || before.isEmpty()
                || after != null && before.size() <= after.size()
                        && before.equals(after.subList(0, before.size()));
    }

    synchronized State recoveredApplicationState(Scope scope) {
        return recoveredState(scope);
    }

    synchronized State checkWorkbenchApplication(Scope scope, String planId, long revision) {
        var current = recoveredState(scope);
        if (scope.equals(running)) throw conflict("整包仍在分析，请等任务完成后应用结构");
        checkRevision(current, revision);
        if (!Objects.equals(planId, current.structurePlanId()))
            throw conflict("工作台尚未接续本结构计划，请先接续确认后的计划");
        return current;
    }

    synchronized State syncWorkbenchApplication(Scope scope, SiteModelPlanService.Plan applied,
                                                long revision, Runnable guard) {
        var current = recoveredState(scope);
        if (Objects.equals(applied.id(), current.structurePlanId())
                && Objects.equals(applied.revision(), current.structurePlanRevision())) return current;
        checkWorkbenchApplication(scope, applied.id(), revision);
        guard.run();
        return save(scope, current, current.state(), applied, "READY", null, current.pages());
    }

    public synchronized State start(
            Scope scope,
            long revision,
            String planId,
            Long planRevision,
            boolean retrySkipped,
            Runnable guard) {
        guard.run();
        State data = load(scope, true);
        if (scope.equals(running)) return data;
        checkRevision(data, revision);
        if (running != null) throw conflict("另一个整包草稿正在分析");
        var plan = selected(scope, planId, planRevision);
        if (plan == null && data.structurePlanId() != null)
            plan =
                    plans.get(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            data.structurePlanId(),
                            data.structurePlanRevision());
        if (plan == null) plan = plans.latest(scope.siteId(), scope.code(), scope.packageVersion());
        var pages =
                data.pages().stream()
                        .map(
                                page ->
                                        (Set.of("RUNNING", "FAILED").contains(page.state())
                                                        || retrySkipped
                                                                && "SKIPPED".equals(page.state()))
                                                ? progress(page, "PENDING", null)
                                                : page)
                        .toList();
        State next =
                save(scope, data, "RUNNING", plan, plan == null ? "RUNNING" : "READY", null, pages);
        var selected = plan;
        running = scope;
        worker.execute(() -> execute(scope, selected, guard));
        return next;
    }

    public synchronized Detail page(Scope scope, String sourcePath) {
        State state = get(scope);
        var page = requirePage(state, sourcePath);
        return "READY".equals(page.state())
                ? detail(page, originals(scope, sourcePath), readFieldEdits(scope, sourcePath))
                : new Detail(page, List.of(), 0, null);
    }

    synchronized Detail currentPage(Scope scope, String path) {
        var detail = page(scope, path);
        requireReady(detail.page());
        checkEvidence(scope, path, detail.results());
        return detail;
    }

    public synchronized Detail editFields(
            Scope scope, String path, long revision, List<FieldEdit> edits, Runnable guard) {
        var page = requirePage(get(scope), path);
        requireReady(page);
        var originals = originals(scope, path);
        var workspace = checkEvidence(scope, path, originals);
        var current = readFieldEdits(scope, path);
        checkFieldRevision(current, revision);
        if (edits == null
                || edits.isEmpty()
                || edits.size() > TemplateContentUnitsService.MAX_UNITS)
            throw new IllegalArgumentException("每次须提交1至200条字段修正");
        var bySource = new HashMap<String, TemplateContentUnitsService.Source>();
        workspace.sources().forEach(source -> bySource.put(source.id(), source));
        var merged = indexEdits(current.edits());
        var seen = new HashSet<FieldKey>();
        for (var edit : edits) {
            if (edit == null
                    || edit.resultIndex() == null
                    || edit.resultIndex() < 0
                    || edit.resultIndex() >= originals.size()
                    || edit.unitId() == null
                    || edit.ignored() == null
                    || edit.doubts() == null) throw new IllegalArgumentException("字段修正的分段、单元或属性无效");
            var key = new FieldKey(edit.resultIndex(), edit.unitId());
            if (!seen.add(key)) throw new IllegalArgumentException("同次不能重复修改字段单元");
            var before =
                    originals.get(key.resultIndex()).units().stream()
                            .filter(unit -> unit.id().equals(key.unitId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("字段单元不属于该原始分段"));
            var after = patchedUnit(before, edit);
            TemplateContentUnitsService.validate(List.of(after), workspace.sources());
            if (!before.fieldType().equals(after.fieldType())
                    && ("Editor".equals(after.fieldType()) && after.sources().size() != 1
                            || after.sources().stream()
                                    .anyMatch(
                                            ref ->
                                                    !TemplateDomainAgentService.acceptsSourceKind(
                                                                    after.fieldType(),
                                                                    bySource.get(ref.sourceId())
                                                                            .kind())
                                                            || CounterScriptInputs.knownCounter(
                                                                            bySource.get(
                                                                                    ref.sourceId()))
                                                                    && !"SingleLine"
                                                                            .equals(
                                                                                    after
                                                                                            .fieldType()))))
                throw new IllegalArgumentException("字段类型与当前原文来源不兼容");
            if (before.equals(after)) merged.remove(key);
            else merged.put(key, edit);
        }
        guard.run();
        recheckEditedWorkspace(scope, path, workspace);
        if (merged.equals(indexEdits(current.edits()))) return detail(page, originals, current);
        var next = new FieldEdits(current.revision() + 1, List.copyOf(merged.values()));
        write(editsFile(scope, path), encode(next, MAX_RESULT_BYTES));
        return detail(page, originals, next);
    }

    public synchronized TemplateDomainAgentService.Result adoption(
            Scope scope, String path, long revision, List<Integer> resultIndexes, Runnable guard) {
        var page = requirePage(get(scope), path);
        requireReady(page);
        if (!"CONFIRMED".equals(page.review())) throw conflict("请先确认本页审核");
        var originals = originals(scope, path);
        var workspace = checkEvidence(scope, path, originals);
        var current = readFieldEdits(scope, path);
        checkFieldRevision(current, revision);
        var results = detail(page, originals, current).results();
        var indexes =
                resultIndexes == null
                        ? java.util.stream.IntStream.range(0, results.size()).boxed().toList()
                        : resultIndexes;
        if (indexes.isEmpty()
                || indexes.stream()
                        .anyMatch(index -> index == null || index < 0 || index >= results.size())
                || new HashSet<>(indexes).size() != indexes.size())
            throw new IllegalArgumentException("采纳的分段范围无效");
        if (indexes.stream().mapToInt(index -> results.get(index).units().size()).sum()
                > TemplateContentUnitsService.MAX_UNITS) throw conflict("所选分段超过200项单页容量，请选择可归并的分段");
        var distinctRoles =
                indexes.stream().map(index -> results.get(index).pageRole()).distinct().toList();
        var target = page.reviewMenuId();
        if (target != null) requireMenu(scope, target);
        var proposed = new ArrayList<TemplateContentUnitsService.Unit>();
        var mappings = new ArrayList<TemplateDomainAgentService.Mapping>();
        var allActiveRefs = new ArrayList<TemplateContentUnitsService.Reference>();
        var byId = new HashMap<String, TemplateContentUnitsService.Source>();
        workspace.sources().forEach(source -> byId.put(source.id(), source));
        var ancestry =
                indexes.size() > 1
                        ? units.sourceAncestors(scope.code(), scope.packageVersion(), workspace)
                        : Map.<String, Set<String>>of();
        var fields = new HashSet<String>();
        for (int index : indexes.stream().sorted().toList()) {
            var result = results.get(index);
            var segmentRefs = new ArrayList<TemplateContentUnitsService.Reference>();
            for (int i = 0; i < result.units().size(); i++) {
                var unit = result.units().get(i);
                String id =
                        results.size() == 1 ? unit.id() : "segment-" + index + "-unit-" + (i + 1);
                if (!unit.ignored()) {
                    for (var ref : unit.sources()) {
                        if (allActiveRefs.stream()
                                .anyMatch(
                                        old ->
                                                TemplateDomainAgentService.overlaps(
                                                        ref, old, byId, ancestry)))
                            throw conflict("所选分段包含重复来源或互相覆盖的区域，请分别审核");
                        segmentRefs.add(ref);
                    }
                }
                proposed.add(
                        new TemplateContentUnitsService.Unit(
                                id,
                                unit.name(),
                                unit.fieldType(),
                                unit.sources(),
                                unit.ignored(),
                                unit.doubts()));
                var mapping = result.mappings().get(i);
                if (!Objects.equals(result.menuId(), target)
                        || unit.ignored()
                        || mapping.fieldName() != null && !fields.add(mapping.fieldName()))
                    mappings.add(
                            new TemplateDomainAgentService.Mapping(
                                    id, null, "PENDING", "进入单页后核对字段映射"));
                else
                    mappings.add(
                            new TemplateDomainAgentService.Mapping(
                                    id, mapping.fieldName(), mapping.action(), mapping.reason(), mapping.menuId()));
            }
            allActiveRefs.addAll(segmentRefs);
        }
        if (proposed.isEmpty()) throw new IllegalArgumentException("所选范围无内容单元可采纳");
        TemplateContentUnitsService.validate(proposed, workspace.sources());
        guard.run();
        recheckEditedWorkspace(scope, path, workspace);
        var first = results.get(indexes.getFirst());
        return new TemplateDomainAgentService.Result(
                path,
                scope.packageVersion(),
                workspace.digest(),
                workspace.revision(),
                target,
                distinctRoles.size() == 1 ? distinctRoles.getFirst() : "UNKNOWN",
                "整包草稿中已修正的候选，送入单页审核。",
                List.copyOf(proposed),
                List.copyOf(mappings),
                List.of(),
                List.of("仍需核对并保存单页内容单元，再确认字段映射。"),
                indexes.stream().mapToInt(index -> results.get(index).observedSources()).sum(),
                first.totalSources(),
                indexes.size() == 1 ? first.chunk() : null, first.recognition());
    }

    private static void requireReady(Page page) {
        if (!"READY".equals(page.state())) throw conflict("页面没有可修正的建议草稿");
    }

    private void recheckEditedWorkspace(
            Scope scope, String path, TemplateContentUnitsService.Workspace before) {
        var current = units.load(scope.code(), scope.packageVersion(), path);
        if (current.revision() != before.revision() || !current.digest().equals(before.digest()))
            throw conflict("原页或单页草稿已变化，请重新读取");
    }

    private List<TemplateDomainAgentService.Result> originals(Scope scope, String path) {
        return read(file(scope, path), Results.class, MAX_RESULT_BYTES).items();
    }

    private Path editsFile(Scope scope, String path) {
        Path result = file(scope, path);
        return result.resolveSibling("fields-" + result.getFileName());
    }

    private FieldEdits readFieldEdits(Scope scope, String path) {
        var file = editsFile(scope, path);
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                ? read(file, FieldEdits.class, MAX_RESULT_BYTES)
                : new FieldEdits(0, List.of());
    }

    private static void checkFieldRevision(FieldEdits current, long revision) {
        if (current.revision() != revision) throw conflict("字段建议草稿已更新，请重新读取");
    }

    private static Map<FieldKey, FieldEdit> indexEdits(List<FieldEdit> edits) {
        var map = new LinkedHashMap<FieldKey, FieldEdit>();
        for (var edit : edits) map.put(new FieldKey(edit.resultIndex(), edit.unitId()), edit);
        return map;
    }

    private static TemplateContentUnitsService.Unit patchedUnit(
            TemplateContentUnitsService.Unit before, FieldEdit edit) {
        return new TemplateContentUnitsService.Unit(
                before.id(),
                edit.name(),
                edit.fieldType(),
                before.sources(),
                edit.ignored(),
                edit.doubts());
    }

    private static Detail detail(
            Page page, List<TemplateDomainAgentService.Result> originals, FieldEdits edits) {
        if (edits.edits().isEmpty()) return new Detail(page, originals, edits.revision(), null);
        var indexed = indexEdits(edits.edits());
        var results = new ArrayList<TemplateDomainAgentService.Result>();
        for (int index = 0; index < originals.size(); index++) {
            var origin = originals.get(index);
            var units = new ArrayList<TemplateContentUnitsService.Unit>();
            var mappings = new ArrayList<TemplateDomainAgentService.Mapping>();
            for (int i = 0; i < origin.units().size(); i++) {
                var unit = origin.units().get(i);
                var edit = indexed.get(new FieldKey(index, unit.id()));
                var effective = edit == null ? unit : patchedUnit(unit, edit);
                units.add(effective);
                mappings.add(
                        edit != null
                                        && (!effective.fieldType().equals(unit.fieldType())
                                                || effective.ignored())
                                ? new TemplateDomainAgentService.Mapping(
                                        unit.id(), null, "PENDING", "人工修正后重新核对字段映射")
                                : origin.mappings().get(i));
            }
            results.add(
                    new TemplateDomainAgentService.Result(
                            origin.sourcePath(),
                            origin.packageVersion(),
                            origin.digest(),
                            origin.revision(),
                            origin.menuId(),
                            origin.pageRole(),
                            origin.summary(),
                            List.copyOf(units),
                            List.copyOf(mappings),
                            origin.steps(),
                            origin.warnings(),
                            origin.observedSources(),
                            origin.totalSources(),
                            origin.chunk(), origin.recognition()));
        }
        return new Detail(page, List.copyOf(results), edits.revision(), originals);
    }

    public State review(
            Scope scope,
            long revision,
            String path,
            String decision,
            Long menu,
            String note,
            Runnable guard) {
        return review(scope, revision, path, decision, menu, note, null, null, guard);
    }

    public synchronized State review(
            Scope scope,
            long revision,
            String path,
            String decision,
            Long menu,
            String note,
            Long workspaceRevision,
            String workspaceDigest,
            Runnable guard) {
        State state = load(scope, true);
        checkRevision(state, revision);
        var page = requirePage(state, path);
        if (!Set.of("READY", "SKIPPED").contains(page.state())
                || !Set.of("CONFIRMED", "NEEDS_REVIEW").contains(decision)
                || note != null && note.length() > 1000)
            throw new IllegalArgumentException("页面没有可审核草稿，或审核内容无效");
        boolean continueWorkspace = workspaceRevision != null || workspaceDigest != null;
        if (continueWorkspace
                && (workspaceRevision == null || workspaceRevision <= 0
                        || workspaceDigest == null || workspaceDigest.isBlank()
                        || !"READY".equals(page.state()) || !"CONFIRMED".equals(decision) || menu == null))
            throw new IllegalArgumentException("接续审核须确认维护栏目和已保存工作区");
        if ("READY".equals(page.state()))
            checkEvidence(
                    scope, path, read(file(scope, path), Results.class, MAX_RESULT_BYTES).items(),
                    workspaceRevision, workspaceDigest);
        if (menu != null) requireMenu(scope, menu);
        guard.run();
        var updated =
                new Page(
                        path,
                        page.state(),
                        page.menuId(),
                        page.candidateName(),
                        page.candidateNames(),
                        page.reason(),
                        decision,
                        menu,
                        note,
                        page.observedSources(),
                        page.totalSources(),
                        page.unitCount());
        return savePage(scope, state, updated);
    }

    public synchronized State importPages(
            Scope scope,
            long revision,
            String planId,
            Long planRevision,
            List<ImportPage> incoming,
            Runnable guard) {
        State state = recoveredState(scope);
        checkRevision(state, revision);
        if (scope.equals(running)) throw conflict("请待此包分析停止后导入");
        if (incoming == null || incoming.isEmpty() || incoming.size() > 10)
            throw new IllegalArgumentException("每次导入须包含1至10页");
        var plan = selected(scope, planId, planRevision);
        var updated = new LinkedHashMap<String, Page>();
        var results = new LinkedHashMap<String, byte[]>();
        for (var item : incoming) {
            if (item == null
                    || item.results() == null
                    || item.reason() != null && item.reason().length() > 1000)
                throw new IllegalArgumentException("导入页面资料无效");
            var page = requirePage(state, item.sourcePath());
            if (item.suggestedOwners() != null) {
                if (item.suggestedOwners().size() > 64
                        || item.suggestedOwners().stream()
                                .anyMatch(
                                        name ->
                                                name == null
                                                        || name.isBlank()
                                                        || name.length() > 150))
                    throw new IllegalArgumentException("候选栏目名称无效");
                var names = item.suggestedOwners().stream().map(String::strip).distinct().toList();
                page =
                        new Page(
                                page.sourcePath(),
                                page.state(),
                                page.menuId(),
                                names.size() == 1 ? names.getFirst() : null,
                                names,
                                page.reason(),
                                page.review(),
                                page.reviewMenuId(),
                                page.reviewNote(),
                                page.observedSources(),
                                page.totalSources(),
                                page.unitCount());
            }
            if (Set.of("READY", "SKIPPED").contains(page.state())
                    || updated.containsKey(item.sourcePath()))
                throw conflict("导入将覆盖已保留的页面结果或同批有重复页面");
            if (item.results().isEmpty()) {
                if (item.reason() == null || item.reason().isBlank())
                    throw new IllegalArgumentException("跳过页面需说明依据");
                updated.put(page.sourcePath(), progress(page, "SKIPPED", item.reason()));
            } else {
                checkEvidence(scope, page.sourcePath(), item.results());
                updated.put(page.sourcePath(), resultPage(page, item.results(), item.reason()));
                results.put(
                        page.sourcePath(), encode(new Results(item.results()), MAX_RESULT_BYTES));
            }
        }
        guard.run();
        results.forEach((path, bytes) -> write(file(scope, path), bytes));
        var pages =
                state.pages().stream()
                        .map(page -> updated.getOrDefault(page.sourcePath(), page))
                        .toList();
        String stage = stateOf(pages, plan != null || state.structurePlanId() != null);
        return save(
                scope,
                state,
                stage,
                plan,
                plan != null ? "READY" : state.structureStatus(),
                state.structureMessage(),
                pages);
    }

    private void execute(Scope scope, SiteModelPlanService.Plan plan, Runnable guard) {
        try {
            guard.run();
            if (plan == null)
                plan =
                        plans.analyze(
                                scope.siteId(), scope.code(), scope.packageVersion(), null, guard);
            synchronized (this) {
                guard.run();
                State data = load(scope, false);
                var prepared = new ArrayList<Page>();
                var navigationNames = plans.savedNavigationNames(plan);
                var applied = appliedRecommendations(scope, plan);
                for (Page page : data.pages()) {
                    if ("PENDING".equals(page.state()))
                        prepared.add(recommend(scope, plan, page, navigationNames, applied));
                    else prepared.add(page);
                }
                save(scope, data, "RUNNING", plan, "READY", null, List.copyOf(prepared));
            }
            while (true) {
                Page page;
                synchronized (this) {
                    guard.run();
                    State data = load(scope, false);
                    page =
                            data.pages().stream()
                                    .filter(row -> "PENDING".equals(row.state()))
                                    .findFirst()
                                    .orElse(null);
                    if (page == null) {
                        save(
                                scope,
                                data,
                                stateOf(data.pages(), true),
                                plan,
                                "READY",
                                null,
                                data.pages());
                        return;
                    }
                    savePage(scope, data, progress(page, "RUNNING", page.reason()));
                }
                try {
                    var resultList = analyzePage(scope, page, guard);
                    synchronized (this) {
                        guard.run();
                        State data = load(scope, false);
                        checkEvidence(scope, page.sourcePath(), resultList);
                        write(
                                file(scope, page.sourcePath()),
                                encode(new Results(resultList), MAX_RESULT_BYTES));
                        savePage(scope, data, resultPage(page, resultList, page.reason()));
                    }
                } catch (RuntimeException failure) {
                    guard.run();
                    synchronized (this) {
                        savePage(
                                scope,
                                load(scope, false),
                                progress(page, "FAILED", "页面分析未完成，可继续重试；其他页面保留"));
                    }
                }
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                State data = load(scope, false);
                boolean hasPlan = data.structurePlanId() != null;
                save(
                        scope,
                        data,
                        "PAUSED",
                        null,
                        hasPlan ? "READY" : "FAILED",
                        hasPlan ? "整包任务已暂停，已完成页面仍可查看" : "结构建议未完成，继续后重试",
                        data.pages());
            }
        } finally {
            synchronized (this) {
                running = null;
            }
        }
    }

    private List<TemplateDomainAgentService.Result> analyzePage(
            Scope scope, Page page, Runnable guard) {
        var ws = units.load(scope.code(), scope.packageVersion(), page.sourcePath());
        long total =
                ws.sources().stream()
                        .filter(TemplateContentUnitsService::businessCandidate)
                        .count();
        if (total <= WHOLE_PAGE_SOURCE_LIMIT)
            return List.of(
                    agent.analyze(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            page.sourcePath(),
                            page.menuId(),
                            null,
                            guard));
        var results = new ArrayList<TemplateDomainAgentService.Result>();
        Integer offset = 0;
        String fingerprint = null;
        while (offset != null) {
            guard.run();
            var chunk =
                    new TemplateDomainAgentService.Chunk(
                            offset,
                            24,
                            scope.packageVersion(),
                            ws.digest(),
                            ws.revision(),
                            fingerprint);
            var result =
                    agent.analyze(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            page.sourcePath(),
                            page.menuId(),
                            chunk,
                            null,
                            guard);
            results.add(result);
            fingerprint = result.chunk().fingerprint();
            offset = result.chunk().nextOffset();
        }
        return List.copyOf(results);
    }

    private Map<String, Long> appliedRecommendations(Scope scope, SiteModelPlanService.Plan plan) {
        var nodes = plans.appliedNavigationNodes(plan);
        if (nodes.isEmpty()) return Map.of();
        var roots =
                nodes.stream()
                        .filter(
                                node ->
                                        node.parentKey() == null
                                                && "首页".equals(node.name())
                                                && node.menuId() != null
                                                && node.menuId().equals(node.reuseMenuId()))
                        .toList();
        if (roots.size() != 1) return Map.of();
        Integer root = roots.getFirst().menuId();
        var navScope =
                new SiteModelNavigationService.Scope(
                        scope.siteId(), scope.code(), scope.packageVersion(), root);
        var byKey = new HashMap<String, Integer>();
        var ids = new HashSet<Integer>();
        var paths = new HashMap<String, Integer>();
        for (var node : nodes) {
            if (node.menuId() == null
                    || !ids.add(node.menuId())
                    || byKey.put(node.key(), node.menuId()) != null) return Map.of();
            if (node.sourcePath() != null) paths.merge(node.sourcePath(), 1, Integer::sum);
        }
        var current = new HashMap<Integer, com.xinhongru.entity.OneshotEntity>();
        for (var menu : menus.selectActiveSiteModelMenus(scope.siteId()))
            if (Objects.equals(scope.siteId(), menu.getSiteProjectId())
                    && Objects.equals(root, menu.getSiteRootId())) current.put(menu.getId(), menu);
        var actualRoot = current.get(root);
        if (actualRoot == null || !Integer.valueOf(0).equals(actualRoot.getPageType()))
            return Map.of();
        var recommended = new HashMap<String, Long>();
        for (var node : nodes) {
            if (node.drift()
                    || node.sourcePath() == null
                    || paths.get(node.sourcePath()) != 1
                    || node.kind() == SiteModelNavigationService.Kind.DIRECTORY) continue;
            var actual = current.get(node.menuId());
            if (actual == null) continue;
            if (node.menuId().equals(root)
                    || current.containsKey(actual.getParentId())
                            && SiteModelNavigationService.matchesApplied(
                                    navScope, node, actual, byKey))
                recommended.put(node.sourcePath(), node.menuId().longValue());
        }
        return recommended;
    }

    private Page recommend(
            Scope scope,
            SiteModelPlanService.Plan plan,
            Page page,
            Map<String, List<String>> navigationNames,
            Map<String, Long> applied) {
        var keys = new HashSet<String>();
        plan.proposal().regions().stream()
                .filter(region -> page.sourcePath().equals(region.sourcePath()))
                .forEach(region -> keys.add(region.modelKey()));
        var candidates =
                plan.proposal().models().stream()
                        .filter(model -> keys.contains(model.key()))
                        .toList();
        var names = candidates.stream().map(SiteModelPlanService.Model::name).distinct().toList();
        boolean fromNavigation = names.isEmpty() && navigationNames.containsKey(page.sourcePath());
        if (fromNavigation) names = navigationNames.get(page.sourcePath());
        Long menu = candidates.size() == 1 ? candidates.getFirst().menuId() : null;
        if (!availableMenu(scope, menu)) menu = null;
        boolean fromApplied = candidates.isEmpty() && applied.containsKey(page.sourcePath());
        if (fromApplied) menu = applied.get(page.sourcePath());
        String reason =
                fromApplied
                        ? APPLIED_NAVIGATION_REASON
                        : menu != null
                                ? "整站结构原页区域与同站栏目一致"
                                : fromNavigation
                                        ? "当前保存导航中的原页同址栏目名称；实际栏目归属待核对"
                                        : names.isEmpty()
                                                ? "结构中未找到此原页的确切栏目，待核对"
                                                : "依据整站结构的原页区域推荐；实际栏目归属待核对";
        return new Page(
                page.sourcePath(),
                "PENDING",
                menu,
                names.size() == 1 ? names.getFirst() : null,
                names,
                reason,
                page.review(),
                null,
                null,
                0,
                0,
                0);
    }

    private TemplateContentUnitsService.Workspace checkEvidence(
            Scope scope, String path, List<TemplateDomainAgentService.Result> results) {
        return checkEvidence(scope, path, results, null, null);
    }

    private TemplateContentUnitsService.Workspace checkEvidence(
            Scope scope, String path, List<TemplateDomainAgentService.Result> results,
            Long workspaceRevision, String workspaceDigest) {
        if (results.isEmpty() || results.size() > 500 || results.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("页面结果数量无效");
        var ws = units.load(scope.code(), scope.packageVersion(), path);
        if (workspaceRevision != null
                && (ws.revision() != workspaceRevision || !Objects.equals(ws.digest(), workspaceDigest)))
            throw conflict("已保存工作区已变化，请重新读取并核对");
        int total =
                (int)
                        ws.sources().stream()
                                .filter(TemplateContentUnitsService::businessCandidate)
                                .count();
        var ordered =
                ws.sources().stream()
                        .filter(TemplateContentUnitsService::businessCandidate)
                        .toList();
        var whole = results.size() == 1 && results.getFirst().chunk() == null;
        int previous = 0;
        String fingerprint =
                results.getFirst().chunk() == null
                        ? null
                        : results.getFirst().chunk().fingerprint();
        Long menu = results.getFirst().menuId();
        if (menu != null) requireMenu(scope, menu);
        for (var result : results) {
            if (result == null
                    || !Objects.equals(path, result.sourcePath())
                    || !Objects.equals(scope.packageVersion(), result.packageVersion())
                    || !Objects.equals(ws.digest(), result.digest())
                    || (workspaceRevision == null
                            ? ws.revision() != result.revision()
                            : result.revision() > ws.revision())
                    || !Objects.equals(menu, result.menuId())
                    || result.totalSources() != total
                    || result.observedSources() < 0
                    || result.observedSources() > total) throw conflict("页面结果不属于当前原页、来源或草稿修订");
            var sources = ordered;
            if (!whole) {
                var chunk = result.chunk();
                if (chunk == null
                        || chunk.offset() != previous
                        || chunk.offset() >= total
                        || chunk.limit() < 1
                        || chunk.limit() > 30
                        || chunk.endOffset() != Math.min(total, chunk.offset() + chunk.limit())
                        || !Objects.equals(
                                chunk.nextOffset(),
                                chunk.endOffset() == total ? null : chunk.endOffset())
                        || fingerprint == null
                        || !fingerprint.matches("[0-9a-f]{64}")
                        || !fingerprint.equals(chunk.fingerprint())
                        || result.observedSources() > chunk.endOffset() - chunk.offset())
                    throw new IllegalArgumentException("分段回执范围不连续");
                sources = ordered.subList(chunk.offset(), chunk.endOffset());
                previous = chunk.endOffset();
            }
            TemplateContentUnitsService.validate(result.units(), sources);
            if (result.mappings() == null
                    || !result.units().stream()
                            .map(TemplateContentUnitsService.Unit::id)
                            .toList()
                            .equals(
                                    result.mappings().stream()
                                            .map(TemplateDomainAgentService.Mapping::unitId)
                                            .toList()))
                throw new IllegalArgumentException("建议单元和映射不一致");
        }
        if (!whole && previous != total) throw new IllegalArgumentException("分段回执未到达该页末尾");
        return ws;
    }

    private boolean availableMenu(Scope scope, Long menu) {
        return menu != null
                && menu > 0
                && menu <= Integer.MAX_VALUE
                && menus.selectActiveMenuModelBySite(scope.siteId(), menu.intValue()) != null;
    }

    private void requireMenu(Scope scope, Long menu) {
        if (!availableMenu(scope, menu)) throw new IllegalArgumentException("所选栏目不属于当前站点");
    }

    private SiteModelPlanService.Plan selected(Scope scope, String id, Long revision) {
        if ((id == null) != (revision == null)) throw new IllegalArgumentException("结构计划与修订必须同时指定");
        return id == null
                ? null
                : plans.continuationBase(
                        scope.siteId(), scope.code(), scope.packageVersion(), id, revision);
    }

    private State load(Scope scope, boolean catalog) {
        var file = file(scope, null);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var existing = read(file, State.class, MAX_STATE_BYTES);
            if (!scope.packageVersion().equals(existing.packageVersion()))
                throw conflict("整包草稿版本不同");
            return existing;
        }
        return new State(
                scope.packageVersion(),
                0,
                "IDLE",
                null,
                null,
                "PENDING",
                null,
                catalog ? catalogPages(scope) : List.of(),
                null);
    }

    private List<Page> catalogPages(Scope scope) {
        return seeds.pages(scope.code(), scope.packageVersion()).stream()
                .map(TemplateSeedService.Page::sourcePath)
                .distinct()
                .map(
                        path ->
                                new Page(
                                        path, "PENDING", null, null, List.of(), null, "PENDING",
                                        null, null, 0, 0, 0))
                .toList();
    }

    private static Page requirePage(State state, String path) {
        return state.pages().stream()
                .filter(page -> page.sourcePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("页面不在该包的原页目录内"));
    }

    private static void checkRevision(State state, long revision) {
        if (state.revision() != revision) throw conflict("整包草稿已更新，请重新读取");
    }

    private static Page progress(Page page, String state, String message) {
        return new Page(
                page.sourcePath(),
                state,
                page.menuId(),
                page.candidateName(),
                page.candidateNames(),
                message,
                page.review(),
                page.reviewMenuId(),
                page.reviewNote(),
                page.observedSources(),
                page.totalSources(),
                page.unitCount());
    }

    private static Page resultPage(
            Page page, List<TemplateDomainAgentService.Result> result, String reason) {
        var first = result.getFirst();
        return new Page(
                page.sourcePath(),
                "READY",
                first.menuId(),
                page.candidateName(),
                page.candidateNames(),
                reason,
                page.review(),
                page.reviewMenuId(),
                page.reviewNote(),
                result.stream().mapToInt(TemplateDomainAgentService.Result::observedSources).sum(),
                first.totalSources(),
                result.stream().mapToInt(item -> item.units().size()).sum());
    }

    private static String stateOf(List<Page> pages, boolean structureReady) {
        if (!structureReady || pages.stream().anyMatch(row -> "PENDING".equals(row.state())))
            return "PAUSED";
        return pages.stream().anyMatch(row -> "FAILED".equals(row.state()))
                ? "ATTENTION"
                : "COMPLETED";
    }

    private State savePage(Scope scope, State state, Page item) {
        return save(
                scope,
                state,
                state.state(),
                null,
                state.structureStatus(),
                state.structureMessage(),
                state.pages().stream()
                        .map(page -> page.sourcePath().equals(item.sourcePath()) ? item : page)
                        .toList());
    }

    private State save(
            Scope scope,
            State old,
            String status,
            SiteModelPlanService.Plan plan,
            String structure,
            String message,
            List<Page> pages) {
        var next =
                snapshot(
                        old,
                        old.revision() + 1,
                        status,
                        plan == null ? old.structurePlanId() : plan.id(),
                        plan == null ? old.structurePlanRevision() : Long.valueOf(plan.revision()),
                        structure,
                        message,
                        pages);
        write(file(scope, null), encode(next, MAX_STATE_BYTES));
        return next;
    }

    private static State snapshot(
            State old,
            long revision,
            String state,
            String plan,
            Long planRevision,
            String structure,
            String message,
            List<Page> pages) {
        return new State(
                old.packageVersion(),
                revision,
                state,
                plan,
                planRevision,
                structure,
                message,
                pages,
                Instant.now().toString());
    }

    private Path file(Scope scope, String path) {
        if (scope.siteId() <= 0 || !safe(scope.code()) || !safe(scope.packageVersion()))
            throw new IllegalArgumentException("整包草稿范围无效");
        var base =
                root.resolve(Long.toString(scope.siteId()))
                        .resolve(scope.code())
                        .resolve(scope.packageVersion());
        return base.resolve(
                path == null
                        ? "state.json"
                        : UUID.nameUUIDFromBytes(
                                        path.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                + ".json");
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    }

    private <T> T read(Path path, Class<T> type, int max) {
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(max + 1);
            if (bytes.length > max) throw new IllegalStateException("整包草稿超过读取限制");
            return json.readValue(bytes, type);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取整包草稿", error);
        }
    }

    private byte[] encode(Object value, int max) {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > max) throw new IllegalArgumentException("整包草稿或单页结果超过大小限制");
        return bytes;
    }

    private void write(Path file, byte[] value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "draft-", ".tmp");
            try {
                Files.write(tmp, value);
                Files.move(
                        tmp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法保存整包草稿", error);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

package com.xinhongru.cms.template;

import com.xinhongru.ai.generation.AiGenerationTasks;
import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.cms.template.SiteModelEvidenceService.PageEvidence;
import com.xinhongru.cms.template.SiteModelEvidenceService.SiteCatalog;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.ContentReferences;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class SiteModelPlanService {
    private static final AiUsage USAGE = AiUsage.WORKBENCH_DOMAIN_AGENT;
    private static final List<String> ANALYSIS_ORDER =
            List.of(
                    "SITE_STRUCTURE",
                    "PUBLIC_REGIONS",
                    "COLUMN_LIST_AND_DETAIL",
                    "CHANNEL_AGGREGATION",
                    "HOME_AGGREGATION");
    private static final String RULES = rules();
    private static final Set<String> FIELD_TYPES =
            Set.of(
                    "SingleLine",
                    "MultiLine",
                    "Editor",
                    "Image",
                    "Video",
                    "File",
                    "DateTime",
                    "Sort");
    private static final Set<String> MODEL_KINDS = Set.of("SINGLE", "LIST", "SHARED");
    private static final Set<String> REGION_MODES =
            Set.of("SINGLE", "LIST", "DETAIL", "QUERY", "SHARED");
    private static final int ROUND_LIMIT = 40;
    private static final int TOOL_RESULT_LIMIT = 48000;
    private static final int ISSUE_LIMIT = 300;
    private static final String RECOVERY_ISSUE = "分析任务中断。草案只包含已校验的候选，其余页面与区域待取证。";
    private static final String COVERAGE_ISSUE = "本轮只对部分页面完成详细取证，整站建议仍需结合未覆盖页面和区域审核。";
    private static final String BUDGET_FINISH_ISSUE = "本轮预算收尾，草案需审核，未覆盖页面与未完成字段需继续取证。";

    private final SiteModelEvidenceService evidence;
    private final UiPackageStorageService packages;
    private final AiTextGenerator generator;
    private final OneShotMapper menus;
    @Autowired private SiteModelContentEvidence contentEvidence;
    @Autowired(required = false) private SiteWorkbenchContextService workbenchContext;

    @Autowired(required = false)
    private TemplateDomainPageEvidence pageReviewEvidence;

    private final ObjectMapper json = new ObjectMapper();
    private final SiteModelPlanStore store;

    public SiteModelPlanService(
            SiteModelEvidenceService evidence,
            AiTextGenerator generator,
            OneShotMapper menus,
            String root) {
        this(evidence, generator, menus, null, root);
    }

    @Autowired
    public SiteModelPlanService(
            SiteModelEvidenceService evidence,
            AiTextGenerator generator,
            OneShotMapper menus,
            UiPackageStorageService packages,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model")
                    String root) {
        this.evidence = evidence;
        this.packages = packages;
        this.generator = generator;
        this.menus = menus;
        this.store = new SiteModelPlanStore(root, json);
    }

    public record Plan(
            int schemaVersion,
            String id,
            Long siteId,
            String code,
            String packageVersion,
            String catalogDigest,
            long revision,
            String status,
            Proposal proposal,
            List<PageUse> pages,
            int observedPages,
            int totalPages,
            String updatedAt,
            Coverage coverage) {}

    public record Coverage(
            int catalogReadPages,
            int inspectedPages,
            int inspectedValues,
            int boundRegions,
            List<String> inspectedSourcePaths) {}

    public record Review(long revision, Proposal proposal, boolean confirm) {}

    public record Proposal(
            String summary,
            List<Model> models,
            List<RegionUse> regions,
            List<Relation> relations,
            List<String> issues,
            List<PageUse> pages) {
        public Proposal(
                String summary,
                List<Model> models,
                List<RegionUse> regions,
                List<Relation> relations,
                List<String> issues) {
            this(summary, models, regions, relations, issues, List.of());
        }
    }

    public record Model(String key, String name, String kind, Long menuId, List<Field> fields) {}

    public record Field(String key, String name, String type, String fieldName) {}

    public record RegionUse(
            String sourcePath,
            String regionId,
            String modelKey,
            String mode,
            List<RecordUse> records,
            Maintenance maintenance) {
        public RegionUse(
                String sourcePath,
                String regionId,
                String modelKey,
                String mode,
                List<RecordUse> records) {
            this(sourcePath, regionId, modelKey, mode, records, null);
        }
    }

    public record Maintenance(String mode, String ownerModelKey, String fieldKey, String reason) {}

    public record RecordUse(String recordId, Map<String, String> bindings) {}

    public record Relation(
            String sourceModelKey, String fieldKey, String targetModelKey, String reason) {}

    public record PageUse(String sourcePath, String purpose, String status) {}

    private record Menu(
            Long id,
            Integer parentId,
            String name,
            Integer pageType,
            Boolean subMenu,
            String listPath,
            String detailPath,
            List<ContentFieldDefinition> fields) {}

    private record Snapshot(
            SiteCatalog catalog,
            Map<String, String> pageDigests,
            String digest,
            Map<Long, Menu> menus) {}

    public Plan latest(Long siteId, String code, String version) {
        return store.latest(siteId, code, version, null);
    }

    public Plan get(Long siteId, String code, String version, String id) {
        SiteModelPlanStore.checkId(id);
        Plan plan = store.latest(siteId, code, version, id);
        if (plan == null) throw SiteModelPlanStore.notFound();
        return plan;
    }

    public Plan get(Long siteId, String code, String version, String id, long revision) {
        return store.revision(siteId, code, version, id, revision);
    }

    Map<String, List<String>> savedNavigationNames(Plan plan) {
        var saved = store.navigation(plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        if (saved == null || !navigationMatchesRevision(plan, saved.view())) return Map.of();
        var byPath = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var node : saved.view().nodes()) {
            if (node.sourcePath() == null || node.name() == null || node.name().isBlank() || node.drift())
                continue;
            byPath.computeIfAbsent(node.sourcePath(), path -> new LinkedHashSet<>()).add(node.name());
        }
        var names = new LinkedHashMap<String, List<String>>();
        byPath.forEach((path, values) -> names.put(path, List.copyOf(values)));
        return names;
    }

    List<SiteModelNavigationService.Node> appliedNavigationNodes(Plan plan) {
        var saved = store.navigation(plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        if (saved == null || saved.view().status() != SiteModelNavigationService.Status.APPLIED
                || !navigationMatchesRevision(plan, saved.view()) || !"CONFIRMED".equals(plan.status()))
            return List.of();
        var rootMenuId = navigationRoot(saved.view());
        return rootMenuId == null ? saved.view().nodes()
                : SiteModelNavigationService.withModelLinks(
                        saved.view(), store.navigationModelLinks(plan, saved.view(), rootMenuId)).nodes();
    }

    private static Integer navigationRoot(SiteModelNavigationService.Navigation saved) {
        return saved.nodes().stream()
                .filter(node -> node.parentKey() == null && "首页".equals(node.name())
                        && node.menuId() != null && node.menuId().equals(node.reuseMenuId()))
                .map(SiteModelNavigationService.Node::menuId)
                .findFirst().orElse(null);
    }

    private boolean navigationMatchesRevision(Plan plan, SiteModelNavigationService.Navigation saved) {
        if (saved.planRevision() == plan.revision()) return true;
        if (saved.status() != SiteModelNavigationService.Status.APPLIED) return false;
        var root = navigationRoot(saved);
        return root != null && continuedNavigationMatches(plan, saved, root);
    }

    private boolean continuedNavigationMatches(
            Plan plan, SiteModelNavigationService.Navigation saved, Integer rootMenuId) {
        var continuation = store.navigationContinuation(plan);
        return SiteModelNavigationService.matchesContinuation(plan, saved, rootMenuId, continuation);
    }

    public Plan continuationBase(
            Long siteId, String code, String version, String id, long revision) {
        if (revision <= 0) throw new IllegalArgumentException("整站计划版本无效");
        Plan plan = get(siteId, code, version, id);
        if (plan.revision() != revision) throw conflict("整站计划已更新，请重新加载后继续分析");
        return plan;
    }

    public Plan confirmed(Long siteId, String code, String version, String id, long revision) {
        return confirmed(siteId, code, version, id, revision, null);
    }

    Plan confirmedForNavigation(Long siteId, String id, long revision, SiteModelNavigationEvidence.Sources sources) {
        return confirmed(siteId, sources.code(), sources.version(), id, revision, sources);
    }

    private Plan confirmed(Long siteId, String code, String version, String id, long revision,
            SiteModelNavigationEvidence.Sources sources) {
        Plan plan = requireCurrent(siteId, code, version, id, revision);
        var scope = sources == null ? snapshot(siteId, code, version)
                : snapshot(siteId, code, version, evidence.catalog(sources));
        checkDigest(plan, scope);
        validate(
                plan.proposal(),
                scope,
                loadReferenced(plan.proposal(), scope, code, version, sources),
                null,
                null);
        return plan;
    }

    Map<String, String> datePrecisions(Plan plan, Model model, List<Field> fields) {
        var dateKeys = new HashSet<String>();
        for (var field : fields) if ("DateTime".equals(field.type())) dateKeys.add(field.key());
        if (dateKeys.isEmpty()) return Map.of();

        var paths =
                plan.proposal().regions().stream()
                        .filter(use -> model.key().equals(use.modelKey()))
                        .filter(
                                use ->
                                        use.records().stream()
                                                .anyMatch(
                                                        record ->
                                                                record.bindings().keySet().stream()
                                                                        .anyMatch(
                                                                                dateKeys
                                                                                        ::contains)))
                        .map(RegionUse::sourcePath)
                        .distinct()
                        .toList();
        if (paths.isEmpty()) return Map.of();

        var scope = snapshot(plan.siteId(), plan.code(), plan.packageVersion());
        checkDigest(plan, scope);
        var pages = new LinkedHashMap<String, PageEvidence>();
        for (String path : paths)
            pages.put(path, loadPage(scope, plan.code(), plan.packageVersion(), path));
        return datePrecisions(plan, model, fields, pages);
    }

    static Map<String, String> datePrecisions(
            Plan plan, Model model, List<Field> fields, Map<String, PageEvidence> pages) {
        var dateKeys = new HashSet<String>();
        for (var field : fields) if ("DateTime".equals(field.type())) dateKeys.add(field.key());

        var result = new LinkedHashMap<String, String>();
        var unresolved = new HashSet<String>();
        for (var use : plan.proposal().regions()) {
            if (!model.key().equals(use.modelKey()) || !pages.containsKey(use.sourcePath()))
                continue;
            var region =
                    pages.get(use.sourcePath()).regions().stream()
                            .filter(candidate -> use.regionId().equals(candidate.id()))
                            .findFirst()
                            .orElseThrow();
            var records =
                    index(region.records(), SiteModelEvidenceService.SourceRecord::id, "证据记录");
            for (var record : use.records()) {
                var values =
                        index(
                                records.get(record.recordId()).values(),
                                SiteModelEvidenceService.Value::id,
                                "证据值");
                for (var binding : record.bindings().entrySet()) {
                    var key = binding.getKey();
                    if (!dateKeys.contains(key)) continue;
                    var value = values.get(binding.getValue());
                    if ("DATETIME".equals(value.typeHint())) {
                        result.put(key, "SECOND");
                    } else if ("DATE".equals(value.typeHint())
                            && TemplateDateValues.supported(value.rawValue())) {
                        result.putIfAbsent(key, "DAY");
                    } else {
                        unresolved.add(key);
                    }
                }
            }
        }
        for (String key : unresolved) result.remove(key, "DAY");
        return Map.copyOf(result);
    }

    Plan withStructure(Plan previous, Model applied) {
        Proposal original = previous.proposal();
        Proposal updated =
                new Proposal(
                        original.summary(),
                        original.models().stream()
                                .map(model -> model.key().equals(applied.key()) ? applied : model)
                                .toList(),
                        original.regions(),
                        original.relations(),
                        original.issues(),
                        original.pages());
        var scope = snapshot(previous.siteId(), previous.code(), previous.packageVersion());
        checkDigest(previous, scope);
        validate(
                updated,
                scope,
                loadReferenced(updated, scope, previous.code(), previous.packageVersion()),
                null,
                null);
        Plan next =
                new Plan(
                        previous.schemaVersion(),
                        previous.id(),
                        previous.siteId(),
                        previous.code(),
                        previous.packageVersion(),
                        previous.catalogDigest(),
                        previous.revision() + 1,
                        previous.status(),
                        updated,
                        previous.pages(),
                        previous.observedPages(),
                        previous.totalPages(),
                        Instant.now().toString(),
                        previous.coverage());
        store.checkSave(next);
        return next;
    }

    void saveStructure(Plan plan) {
        store.save(plan);
    }

    public synchronized Plan review(
            Long siteId,
            String code,
            String version,
            String id,
            Review request,
            Runnable authorizationCheck) {
        Objects.requireNonNull(authorizationCheck).run();
        Plan previous = get(siteId, code, version, id);
        if (request == null || request.revision() != previous.revision())
            throw conflict("整站计划已更新，请重新加载后审核");
        var scope = snapshot(siteId, code, version);
        checkDigest(previous, scope);
        Proposal proposal =
                request.proposal() == null ? previous.proposal() : normalize(request.proposal());
        var pages = loadReferenced(proposal, scope, code, version);
        validate(proposal, scope, pages, null, null);
        if (request.confirm() && proposal.models().isEmpty())
            throw new IllegalArgumentException("至少需要一个内容类型才能确认整站结构");
        Plan next =
                plan(
                        id,
                        siteId,
                        code,
                        version,
                        scope,
                        previous.revision() + 1,
                        request.confirm() ? "CONFIRMED" : "DRAFT",
                        proposal,
                        inspectedPaths(previous),
                        previous.coverage() == null ? 0 : previous.coverage().inspectedValues());
        authorizationCheck.run();
        recheck(scope, siteId, code, version);
        authorizationCheck.run();
        store.save(next);
        return next;
    }

    synchronized Plan forkNavigationModelDraft(
            Plan base, Proposal proposal, Runnable guard, java.util.function.Consumer<Plan> navigation,
            String nodeKey, String sourcePath, String modelKey) {
        guard.run();
        continuationBase(base.siteId(), base.code(), base.packageVersion(), base.id(), base.revision());
        var scope = snapshot(base.siteId(), base.code(), base.packageVersion());
        checkDigest(base, scope);
        proposal = normalize(proposal);
        validate(proposal, scope, loadReferenced(proposal, scope, base.code(), base.packageVersion()), null, null);
        Plan next =
                plan(
                        UUID.randomUUID().toString(),
                        base.siteId(),
                        base.code(),
                        base.packageVersion(),
                        scope,
                        1,
                        "DRAFT",
                        proposal,
                        inspectedPaths(base),
                        base.coverage() == null ? 0 : base.coverage().inspectedValues());
        recheck(scope, base.siteId(), base.code(), base.packageVersion());
        guard.run();
        store.checkSave(next);
        navigation.accept(next);
        store.registerModelDraft(base, next, nodeKey, sourcePath, modelKey);
        store.save(next);
        return next;
    }

    Plan navigationAppendPlan(Plan base, String id) {
        var scope = navigationAppendSnapshot(base);
        return plan(
                id,
                base.siteId(),
                base.code(),
                base.packageVersion(),
                scope,
                1,
                "CONFIRMED",
                base.proposal(),
                inspectedPaths(base),
                base.coverage() == null ? 0 : base.coverage().inspectedValues());
    }

    String validateNavigationAppendSource(Plan base) {
        return navigationAppendSnapshot(base).digest();
    }

    private Snapshot navigationAppendSnapshot(Plan base) {
        if (!"CONFIRMED".equals(base.status())) throw conflict("请先确认当前整站结构");
        var scope = snapshot(base.siteId(), base.code(), base.packageVersion());
        if (!base.catalogDigest().equals(scope.digest())) {
            var retainedPages =
                    base.pages().stream()
                            .map(PageUse::sourcePath)
                            .collect(java.util.stream.Collectors.toSet());
            // 对当前包重新按旧页面清单取证，只有旧清单的完整摘要仍相同时接受新增 HTML。
            var baseline = evidence.catalog(base.code(), base.packageVersion(), retainedPages);
            if (!base.catalogDigest().equals(digest(baseline)))
                throw conflict("原页面包证据已变化，请重新审核整站结构");
        }
        var proposal = base.proposal();
        validate(
                proposal,
                scope,
                loadReferenced(proposal, scope, base.code(), base.packageVersion()),
                null,
                null);
        return scope;
    }

    boolean isNavigationAppend(Plan plan) {
        var saved = store.navigation(plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        return saved != null
                && saved.view().status() == SiteModelNavigationService.Status.APPLIED
                && saved.view().originPlanId() != null;
    }

    SiteModelPlanStore.Derived modelDraftOrigin(Plan draft) {
        var origin =
                store.modelDraftOrigin(
                        draft.siteId(), draft.code(), draft.packageVersion(), draft.id());
        if (origin == null) return null;
        if (origin.nodeKey() == null) {
            var saved =
                    store.navigation(
                            draft.siteId(), draft.code(), draft.packageVersion(), draft.id());
            if (saved == null || saved.view().status() != SiteModelNavigationService.Status.APPLIED)
                throw conflict("导航追加尚未签收，请按原预览重试");
        }
        return new SiteModelPlanStore.Derived(origin.sitePlanId(), origin.revision());
    }

    boolean hasNavigationModelEvidence(
            Plan plan, String nodeKey, String sourcePath, String modelKey) {
        String id = plan.id();
        var seen = new HashSet<String>();
        while (seen.size() < 120 && seen.add(id)) {
            var origin = store.modelDraftOrigin(plan.siteId(), plan.code(), plan.packageVersion(), id);
            if (origin == null) return false;
            if (nodeKey.equals(origin.nodeKey()) && sourcePath.equals(origin.sourcePath()))
                return modelKey.equals(origin.modelKey());
            id = origin.sitePlanId();
        }
        return false;
    }

    public synchronized Plan recoverDraft(
            Long siteId, String code, String version, String id, Runnable authorizationCheck) {
        Objects.requireNonNull(authorizationCheck).run();
        SiteModelPlanStore.checkId(id);
        var complete = store.latest(siteId, code, version, id);
        if (complete != null) {
            checkDigest(complete, snapshot(siteId, code, version));
            return complete;
        }
        String recoveredId =
                UUID.nameUUIDFromBytes(
                                ("site-model-recovery\n"
                                                + siteId
                                                + "\n"
                                                + code
                                                + "\n"
                                                + version
                                                + "\n"
                                                + id)
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        var existingRecovery = store.latest(siteId, code, version, recoveredId);
        if (existingRecovery != null) {
            checkDigest(existingRecovery, snapshot(siteId, code, version));
            return existingRecovery;
        }
        var checked = store.checkpoint(siteId, code, version, id);
        if (checked == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "此任务没有可恢复的已校验草案");
        var scope = snapshot(siteId, code, version);
        checkDigest(checked, scope);
        var proposal = withIssue(checked.proposal(), RECOVERY_ISSUE);
        validate(proposal, scope, loadReferenced(proposal, scope, code, version), null, null);
        Plan recovered =
                plan(
                        recoveredId,
                        siteId,
                        code,
                        version,
                        scope,
                        1,
                        "DRAFT",
                        proposal,
                        inspectedPaths(checked),
                        checked.coverage().inspectedValues());
        recheck(scope, siteId, code, version);
        authorizationCheck.run();
        store.save(recovered);
        store.clearCheckpoint(checked);
        return recovered;
    }

    public Plan analyze(
            Long siteId,
            String code,
            String version,
            AiGenerationTasks.Task task,
            Runnable authorizationCheck) {
        return analyze(siteId, code, version, null, null, task, authorizationCheck);
    }

    public Plan analyze(
            Long siteId,
            String code,
            String version,
            String basePlanId,
            AiGenerationTasks.Task task,
            Runnable authorizationCheck) {
        Long revision = null;
        if (basePlanId != null) {
            Objects.requireNonNull(authorizationCheck).run();
            revision = get(siteId, code, version, basePlanId).revision();
        }
        return analyze(siteId, code, version, basePlanId, revision, task, authorizationCheck);
    }

    public Plan analyze(
            Long siteId,
            String code,
            String version,
            String basePlanId,
            Long basePlanRevision,
            AiGenerationTasks.Task task,
            Runnable authorizationCheck) {
        Objects.requireNonNull(authorizationCheck);
        long timeoutMs = task == null ? 300000 : task.timeoutMs;
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        long finishReserve = Duration.ofMillis(Math.min(60_000, timeoutMs / 5)).toNanos();
        guard(task, deadline, authorizationCheck);
        if ((basePlanId == null) != (basePlanRevision == null))
            throw new IllegalArgumentException("继续分析需同时指定草案和修订号");
        Plan prior =
                basePlanId == null
                        ? null
                        : continuationBase(siteId, code, version, basePlanId, basePlanRevision);
        var scope = snapshot(siteId, code, version);
        String id = task == null ? UUID.randomUUID().toString() : task.id.toLowerCase(Locale.ROOT);
        SiteModelPlanStore.checkId(id);
        Plan recovered = store.latest(siteId, code, version, id);
        if (recovered != null) {
            checkDigest(recovered, scope);
            if (task != null)
                task.completeWith(
                        () -> {
                            guard(task, deadline, authorizationCheck);
                            return json.writeValueAsString(recovered);
                        });
            return recovered;
        }
        var engine = generator.forTask();
        engine.checkConfigured(USAGE);
        Proposal base = null;
        Map<String, PageEvidence> inheritedPages = Map.of();
        Set<String> priorInspectedPaths = Set.of();
        int priorInspectedValues = 0;
        if (prior != null) {
            if (id.equals(basePlanId)) throw new IllegalArgumentException("继续分析需使用新的任务标识");
            checkDigest(prior, scope);
            var source = prior.proposal();
            base =
                    new Proposal(
                            source.summary(),
                            source.models(),
                            source.regions(),
                            source.relations(),
                            source.issues().stream()
                                    .filter(
                                            issue ->
                                                    !RECOVERY_ISSUE.equals(issue)
                                                            && !COVERAGE_ISSUE.equals(issue)
                                                            && !BUDGET_FINISH_ISSUE.equals(issue))
                                    .toList(),
                            source.pages());
            inheritedPages = loadReferenced(base, scope, code, version);
            validate(base, scope, inheritedPages, null, null);
            var inspected = new HashSet<>(inspectedPaths(prior));
            inspected.retainAll(scope.pageDigests().keySet());
            priorInspectedPaths = inspected;
            if (prior.coverage() != null) priorInspectedValues = prior.coverage().inspectedValues();
        }
        var session =
                new Analysis(
                        siteId,
                        code,
                        version,
                        scope,
                        prior,
                        base,
                        inheritedPages,
                        priorInspectedPaths,
                        priorInspectedValues);
        restoreInspectedPages(session, task, deadline, authorizationCheck);
        var history = new ArrayDeque<String>();
        int invalid = 0;
        for (int round = 1; round <= ROUND_LIMIT; round++) {
            guard(task, deadline, authorizationCheck);
            if ((round == ROUND_LIMIT || deadline - System.nanoTime() < finishReserve)
                    && session.finishBlocker() == null)
                return finishDraft(id, session, round, true, task, deadline, authorizationCheck);
            var siteRules = workbenchContext == null ? null : workbenchContext.agents(siteId, code);
            String context =
                    RULES
                            + (siteRules == null || !siteRules.present()
                                    ? ""
                                    : "\nCMS自有站点AGENTS.md（仍受系统规则、站点权限及工具边界约束）\n"
                                            + "摘要 "
                                            + siteRules.digest()
                                            + "\n"
                                            + siteRules.content())
                            + "\n站级范围："
                            + json.writeValueAsString(
                                    Map.of(
                                            "pages", scope.pageDigests().size(),
                                            "fragments", scope.catalog().sharedFragments().size(),
                                            "menus", scope.menus().size(),
                                            "continuesDraft", basePlanId != null))
                            + "\n本轮执行余量："
                            + json.writeValueAsString(
                                    Map.of(
                                            "round",
                                            round,
                                            "remainingRounds",
                                            ROUND_LIMIT - round + 1,
                                            "secondsRemaining",
                                            Duration.ofNanos(
                                                            Math.max(
                                                                    0,
                                                                    deadline - System.nanoTime()))
                                                    .toSeconds()))
                            + "\n已校验草案索引："
                            + session.index();
            publish(task, round, "model", "running", "正在等待模型选择下一步操作", session);
            String output;
            try {
                output = nextAction(engine, context, history, task, deadline, authorizationCheck);
            } catch (AiTextGenerator.InputBudgetExceededException error) {
                publish(task, round, "context", "error", error.getMessage(), session);
                throw error;
            }
            guard(task, deadline, authorizationCheck);
            String tool = "parse";
            String status = "ok";
            String message = "工具执行成功";
            String feedback;
            try {
                var action = json.readTree(output);
                requireKeys(action, "tool", "arguments");
                tool = action.path("tool").asText("");
                var args = action.path("arguments");
                if (!args.isObject()) throw new IllegalArgumentException("arguments必须为对象");
                if ("finish".equals(tool)) {
                    requireKeys(args);
                    return finishDraft(id, session, round, false, task, deadline, authorizationCheck);
                }
                if (tool.matches("read_[a-z_]{1,32}|validate_proposal")) {
                    String path = progressPath(args.path("sourcePath").asText(""));
                    publish(
                            task,
                            round,
                            tool,
                            "running",
                            "正在执行 " + tool + (path.isEmpty() ? "" : " · " + path),
                            session);
                }
                var response = session.run(tool, args);
                feedback = json.writeValueAsString(response);
                if (feedback.length() > TOOL_RESULT_LIMIT)
                    throw new IllegalArgumentException("工具结果过大，请缩小分页范围");
                message = progressMessage(tool, args, response, session.valuesRead.size());
                invalid = 0;
            } catch (tools.jackson.core.JacksonException error) {
                status = "error";
                message = "动作或提案必须符合JSON协议";
                feedback = json.writeValueAsString(Map.of("error", message));
                invalid++;
            } catch (IllegalArgumentException error) {
                status = "error";
                message = error.getMessage();
                feedback = json.writeValueAsString(Map.of("error", message));
                invalid++;
            }
            if ("ok".equals(status) && "validate_proposal".equals(tool))
                checkpointDraft(
                        plan(
                                id,
                                siteId,
                                code,
                                version,
                                scope,
                                1,
                                "DRAFT",
                                session.proposal,
                                session.inspectedPaths(),
                                session.inspectedValueCount()),
                        task,
                        deadline,
                        authorizationCheck);
            publish(task, round, tool, status, message, session);
            if (invalid >= 5) throw new IllegalStateException("站级Agent连续无效动作达到上限");
            String turn = "工具 " + tool + " " + status + "\n" + feedback;
            history.addLast(turn);
        }
        throw new IllegalStateException("站级Agent轮次达到上限，方案未完成");
    }

    private Plan finishDraft(
            String id,
            Analysis session,
            int round,
            boolean budgetFinish,
            AiGenerationTasks.Task task,
            long deadline,
            Runnable authorizationCheck) {
        session.canFinish();
        recheck(session.scope, session.siteId, session.code, session.version);
        guard(task, deadline, authorizationCheck);
        Set<String> observed = session.inspectedPaths();
        Proposal proposal = session.proposal;
        if (budgetFinish) proposal = withIssue(proposal, BUDGET_FINISH_ISSUE);
        else if (observed.size() < session.scope.pageDigests().size())
            proposal = withIssue(proposal, COVERAGE_ISSUE);
        Plan result =
                plan(
                        id,
                        session.siteId,
                        session.code,
                        session.version,
                        session.scope,
                        1,
                        "DRAFT",
                        proposal,
                        observed,
                        session.inspectedValueCount());
        publish(
                task,
                round,
                budgetFinish ? "budget_finish" : "finish",
                "ok",
                budgetFinish ? BUDGET_FINISH_ISSUE : "整站结构待审核",
                session);
        return saveDraft(result, task, deadline, authorizationCheck);
    }

    private synchronized void checkpointDraft(
            Plan result, AiGenerationTasks.Task task, long deadline, Runnable authorizationCheck) {
        if (task == null) {
            guard(null, deadline, authorizationCheck);
            store.saveCheckpoint(result);
        } else {
            task.commitIfRunning(
                    () -> {
                        guard(task, deadline, authorizationCheck);
                        store.saveCheckpoint(result);
                        return null;
                    });
        }
    }

    private String nextAction(
            AiTextGenerator engine,
            String context,
            ArrayDeque<String> history,
            AiGenerationTasks.Task task,
            long deadline,
            Runnable authorizationCheck) {
        while (true) {
            guard(task, deadline, authorizationCheck);
            String prompt = context + "\n" + String.join("\n", history);
            try {
                return engine.stream(USAGE, prompt)
                        .reduce(
                                new StringBuilder(),
                                (buffer, part) -> {
                                    guard(task, deadline, () -> {});
                                    if (buffer.length() + part.length() > 90000)
                                        throw new IllegalStateException("站级Agent动作输出超限");
                                    return buffer.append(part);
                                })
                        .map(StringBuilder::toString)
                        .block(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            } catch (AiTextGenerator.InputBudgetExceededException error) {
                // 生成器按实际 token 预算预检，拒绝发生在模型请求前。
                // 只移除最早的工具输出，已读取 ID 与已校验结构仍保留在会话中。
                if (history.pollFirst() == null) throw error;
            }
        }
    }

    private synchronized Plan saveDraft(
            Plan result, AiGenerationTasks.Task task, long deadline, Runnable authorizationCheck) {
        if (task == null) {
            guard(null, deadline, authorizationCheck);
            store.save(result);
            store.clearCheckpoint(result);
        } else {
            task.completeWith(
                    () -> {
                        guard(task, deadline, authorizationCheck);
                        String output = json.writeValueAsString(result);
                        store.save(result);
                        store.clearCheckpoint(result);
                        return output;
                    });
        }
        return result;
    }

    private void publish(
            AiGenerationTasks.Task task,
            int round,
            String tool,
            String status,
            String message,
            Analysis session) {
        if (task == null) return;
        String operation =
                tool.matches(
                                "read_[a-z_]{1,32}|validate_proposal|restore_evidence|finish|budget_finish|model|parse|context")
                        ? tool
                        : "unknown";
        String description = progressPath(message == null ? "" : message);
        if (!"running".equals(status)) {
            if (session.recentSteps.size() >= 20) session.recentSteps.removeFirst();
            session.recentSteps.addLast(Map.of("round", round, "tool", operation, "status", status,
                    "message", description, "at", Instant.now().toString()));
        }
        task.progress(
                json.writeValueAsString(
                        Map.of(
                                "round",
                                round,
                                "tool",
                                operation,
                                "status",
                                status,
                                "message",
                                description,
                                "steps",
                                List.copyOf(session.recentSteps),
                                "observedPages",
                                session.inspectedPaths().size(),
                                "observedValues",
                                session.inspectedValueCount(),
                                "checkpointAvailable",
                                session.validated,
                                "totalPages",
                                session.scope.pageDigests().size())));
    }

    private static String progressMessage(
            String tool, JsonNode args, Map<String, Object> response, int observedValues) {
        return switch (tool) {
            case "read_catalog", "read_fragments", "read_model" -> {
                String label =
                        switch (tool) {
                            case "read_catalog" -> "页面清单";
                            case "read_fragments" -> "共享片段";
                            default -> "CMS栏目字段";
                        };
                int offset = args.path("offset").asInt();
                int next = (Integer) response.get("nextOffset");
                yield label
                        + "本轮读取 "
                        + (next - offset)
                        + " 项，累计分页位置 "
                        + next
                        + " / "
                        + response.get("total");
            }
            case "read_page" -> {
                JsonNode paths = args.path("sourcePaths");
                String first = progressPath(paths.get(0).asText());
                yield "页面取证批次 " + first + (paths.size() > 1 ? " 等 " + paths.size() + " 页" : "");
            }
            case "read_region" ->
                    "读取区域字段证据 "
                            + progressPath(args.path("sourcePath").asText())
                            + "，已完整读取 "
                            + observedValues
                            + " 个证据值";
            case "read_navigation" -> "核对整站导航及栏目的来源证据";
            case "read_page_review" ->
                    "核对页面后续审核证据 " + progressPath(args.path("sourcePath").asText());
            case "read_coverage" -> "查看待审核的区域与字段候选，共 " + response.get("total") + " 项";
            case "read_shared" -> "读取公共信息证据 " + progressPath(args.path("sourcePath").asText());
            case "read_content_candidates" -> "核对已有内容候选";
            case "read_form_actions" ->
                    "读取表单动作证据 " + progressPath(args.path("sourcePath").asText());
            case "validate_proposal" ->
                    "已校验 "
                            + response.get("models")
                            + " 个内容类型、"
                            + response.get("regions")
                            + " 个页面区域";
            default -> "工具执行成功";
        };
    }

    private static String progressPath(String path) {
        return excerpt(path.replaceAll("[\\p{Cc}\\p{Cf}]", " "), 180);
    }

    private static void guard(AiGenerationTasks.Task task, long deadline, Runnable auth) {
        if (Thread.currentThread().isInterrupted()
                || System.nanoTime() >= deadline
                || task != null && !"running".equals(task.state()))
            throw new IllegalStateException("站级Agent已取消或超时");
        auth.run();
    }

    private Plan plan(
            String id,
            Long siteId,
            String code,
            String version,
            Snapshot scope,
            long revision,
            String status,
            Proposal proposal,
            Set<String> observed,
            int inspectedValues) {
        var purposes = index(proposal.pages(), PageUse::sourcePath, "页面用途");
        Set<String> modeled = new HashSet<>();
        proposal.regions().stream().map(RegionUse::sourcePath).forEach(modeled::add);
        List<PageUse> pages =
                scope.catalog().pages().stream()
                        .map(
                                page -> {
                                    PageUse use = purposes.get(page.sourcePath());
                                    return new PageUse(
                                            page.sourcePath(),
                                            use == null ? "用途待确认" : use.purpose(),
                                            modeled.contains(page.sourcePath())
                                                    ? "MODELED"
                                                    : observed.contains(page.sourcePath())
                                                            ? "REVIEW_REQUIRED"
                                                            : "NOT_OBSERVED");
                                })
                        .toList();
        return new Plan(
                1,
                id,
                siteId,
                code,
                version,
                scope.digest(),
                revision,
                status,
                proposal,
                pages,
                observed.size(),
                pages.size(),
                Instant.now().toString(),
                new Coverage(
                        scope.pageDigests().size(),
                        observed.size(),
                        inspectedValues,
                        proposal.regions().size(),
                        pages.stream()
                                .map(PageUse::sourcePath)
                                .filter(observed::contains)
                                .toList()));
    }

    private static Set<String> inspectedPaths(Plan plan) {
        if (plan.coverage() != null && plan.coverage().inspectedSourcePaths() != null)
            return Set.copyOf(plan.coverage().inspectedSourcePaths());
        var paths = new HashSet<String>();
        plan.pages().stream()
                .filter(page -> !"NOT_OBSERVED".equals(page.status()))
                .map(PageUse::sourcePath)
                .forEach(paths::add);
        return paths;
    }

    private static Proposal withIssue(Proposal source, String issue) {
        if (source.issues().contains(issue)) return source;
        var issues = new ArrayList<>(source.issues());
        issues.addFirst(issue);
        return new Proposal(
                source.summary(),
                source.models(),
                source.regions(),
                source.relations(),
                List.copyOf(issues),
                source.pages());
    }

    private Snapshot snapshot(Long siteId, String code, String version) {
        return snapshot(siteId, code, version, evidence.catalog(code, version));
    }

    private Snapshot snapshot(Long siteId, String code, String version, SiteCatalog catalog) {
        if (!code.equals(catalog.packageCode()) || !version.equals(catalog.packageVersion()))
            throw conflict("页面包版本已变化，请重新生成整站计划");
        var pageDigests = new LinkedHashMap<String, String>();
        catalog.pages().forEach(page -> pageDigests.put(page.sourcePath(), page.digest()));
        return new Snapshot(catalog, pageDigests, digest(catalog), readMenus(siteId));
    }

    private Map<Long, Menu> readMenus(Long siteId) {
        var result = new LinkedHashMap<Long, Menu>();
        var existing = menus.selectActiveSiteModelMenus(siteId);
        if (existing.size() > 1000) throw new IllegalArgumentException("站点栏目超过整站建模读取上限");
        for (var menu : existing) {
            if (!siteId.equals(menu.getSiteProjectId())) throw new SecurityException("栏目站点归属无效");
            Long id = menu.getId().longValue();
            result.put(
                    id,
                    new Menu(
                            id,
                            menu.getParentId(),
                            menu.getClassName(),
                            menu.getPageType(),
                            menu.getIsSubMenu(),
                            menu.getListPageUrl(),
                            menu.getDetailPageUrl(),
                            ContentModelCodec.read(
                                    Integer.valueOf(0).equals(menu.getPageType())
                                            ? menu.getMenuField()
                                            : menu.getModelField())));
        }
        return result;
    }

    private void recheck(Snapshot scope, Long siteId, String code, String version) {
        var current = snapshot(siteId, code, version);
        if (!scope.digest().equals(current.digest()) || !scope.menus().equals(current.menus()))
            throw conflict("页面包证据或CMS栏目模型已变化，请重新审核");
    }

    private String digest(SiteCatalog catalog) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(catalog)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void checkDigest(Plan plan, Snapshot scope) {
        if (!scope.digest().equals(plan.catalogDigest())) throw conflict("页面包证据已变化，请重新生成整站计划");
    }

    private Map<String, PageEvidence> loadReferenced(
            Proposal proposal, Snapshot scope, String code, String version) {
        return loadReferenced(proposal, scope, code, version, null);
    }

    private Map<String, PageEvidence> loadReferenced(Proposal proposal, Snapshot scope, String code, String version,
            SiteModelNavigationEvidence.Sources sources) {
        var result = new LinkedHashMap<String, PageEvidence>();
        for (RegionUse region : proposal.regions())
            result.computeIfAbsent(
                    region.sourcePath(), path -> loadPage(scope, code, version, path, sources));
        return result;
    }

    private PageEvidence loadPage(Snapshot scope, String code, String version, String path) {
        return loadPage(scope, code, version, path, null);
    }

    private PageEvidence loadPage(Snapshot scope, String code, String version, String path,
            SiteModelNavigationEvidence.Sources sources) {
        if (path == null || !scope.pageDigests().containsKey(path))
            throw new IllegalArgumentException("页面不在授权包清单");
        String expected = scope.pageDigests().get(path);
        if (expected == null || expected.isBlank())
            throw new IllegalArgumentException("页面原始证据不可读取");
        var page = sources == null ? evidence.page(code, version, path) : evidence.page(sources, path);
        if (!path.equals(page.sourcePath()) || !expected.equals(page.digest()))
            throw conflict("页面证据已变化，请重新生成整站计划");
        return page;
    }

    private void restoreInspectedPages(
            Analysis session,
            AiGenerationTasks.Task task,
            long deadline,
            Runnable authorizationCheck) {
        if (session.priorInspectedPaths.isEmpty()) return;
        var paths =
                session.scope.pageDigests().entrySet().stream()
                        .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                        .map(Map.Entry::getKey)
                        .filter(session.priorInspectedPaths::contains)
                        .filter(path -> !session.inheritedPages.containsKey(path))
                        .toList();
        for (int i = 0; i < paths.size(); i++) {
            guard(task, deadline, authorizationCheck);
            if (i % 10 == 0)
                publish(
                        task,
                        0,
                        "restore_evidence",
                        "ok",
                        "恢复已查页面的候选索引 " + i + " / " + paths.size(),
                        session);
            String path = paths.get(i);
            var page = loadPage(session.scope, session.code, session.version, path);
            guard(task, deadline, authorizationCheck);
            session.inheritedPages.put(path, page);
        }
    }

    public List<Plan> index(Long siteId, String code, String version) {
        return store.index(siteId, code, version);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static String rules() {
        try (var input =
                SiteModelPlanService.class.getResourceAsStream("/ai/domain-agent/site-model.md")) {
            if (input == null) throw new IllegalStateException("站级Agent能力规则缺失");
            byte[] bytes = input.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalStateException("站级Agent能力规则超限");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取站级Agent能力规则", error);
        }
    }

    private final class Analysis {
        private final ArrayDeque<Map<String, Object>> recentSteps = new ArrayDeque<>();
        private final Long siteId;
        private final String code;
        private final String version;
        private final Snapshot scope;
        private final Map<String, SiteModelNavigationService.Node> appliedNavigation;
        private final SiteModelNavigationService.Node appliedRoot;
        private final Set<String> catalogRead = new HashSet<>();
        private final Set<String> fragmentsRead = new HashSet<>();
        private List<SiteModelNavigationEvidence.Entry> navigation = List.of();
        private final Set<Long> menusRead = new HashSet<>();
        private final Map<String, PageEvidence> pages = new LinkedHashMap<>();
        private final Set<String> valuesRead = new HashSet<>();
        private final Map<String, PageEvidence> inheritedPages;
        private final Set<String> inheritedValues = new HashSet<>();
        private final Set<Long> inheritedMenus = new HashSet<>();
        private final Set<String> priorInspectedPaths;
        private final int priorInspectedValues;
        private boolean modelRead;
        private boolean validated;
        private Proposal proposal;

        Analysis(
                Long siteId,
                String code,
                String version,
                Snapshot scope,
                Plan prior,
                Proposal base,
                Map<String, PageEvidence> inheritedPages,
                Set<String> priorInspectedPaths,
                int priorInspectedValues) {
            this.siteId = siteId;
            this.code = code;
            this.version = version;
            this.scope = scope;
            this.appliedNavigation =
                    prior == null
                            ? Map.of()
                            : appliedNavigationNodes(prior).stream()
                                    .filter(node -> node.menuId() != null && !node.drift())
                                    .filter(
                                            node ->
                                                    scope.menus()
                                                            .containsKey(node.menuId().longValue()))
                                    .collect(
                                            java.util.stream.Collectors.toUnmodifiableMap(
                                                    SiteModelNavigationService.Node::key,
                                                    Function.identity()));
            this.appliedRoot =
                    appliedNavigation.values().stream()
                            .filter(
                                    node ->
                                            node.parentKey() == null
                                                    && "首页".equals(node.name())
                                                    && node.menuId().equals(node.reuseMenuId()))
                            .findFirst()
                            .orElse(null);
            this.proposal = base;
            this.inheritedPages = inheritedPages;
            this.priorInspectedPaths = priorInspectedPaths;
            this.priorInspectedValues = priorInspectedValues;
            if (base != null) {
                base.regions()
                        .forEach(
                                region ->
                                        region.records()
                                                .forEach(
                                                        record ->
                                                                inheritedValues.addAll(
                                                                        record.bindings()
                                                                                .values())));
                base.models().stream()
                        .map(Model::menuId)
                        .filter(Objects::nonNull)
                        .forEach(inheritedMenus::add);
            }
        }

        Set<String> inspectedPaths() {
            var observed = new HashSet<>(priorInspectedPaths);
            observed.addAll(pages.keySet());
            return observed;
        }

        int inspectedValueCount() {
            var knownValues = new HashSet<>(inheritedValues);
            knownValues.addAll(valuesRead);
            // 旧版本只记录数量，已知 ID 去重后取下限，避免重复计数。
            return Math.max(priorInspectedValues, knownValues.size());
        }

        String index() {
            if (proposal == null) return "无";
            var modeledPaths = new HashSet<String>();
            proposal.regions().forEach(region -> modeledPaths.add(region.sourcePath()));
            var inspected = inspectedPaths();
            var index = new LinkedHashMap<String, Object>();
            var uninspected = uninspectedInAnalysisOrder(inspected);
            index.put("analysisOrder", ANALYSIS_ORDER);
            index.put("uninspectedSourcePaths", coveragePreview(uninspected, 6000));
            index.put("uninspectedSourcePathCount", uninspected.size());
            var coverage = coverageRows();
            index.put("coverageReviewCount", coverage.size());
            index.put("coverageReview", coveragePreview(coverage.stream().limit(8).toList(), 5000));
            index.put(
                    "pendingIssues",
                    proposal.issues().reversed().stream()
                            .limit(30)
                            .map(issue -> excerpt(issue, 400))
                            .toList());
            index.put(
                    "maintenanceRegions",
                    proposal.regions().reversed().stream()
                            .filter(region -> region.maintenance() != null)
                            .limit(16)
                            .map(
                                    region -> {
                                        var maintenance = region.maintenance();
                                        var row = new LinkedHashMap<String, Object>();
                                        row.put("sourcePath", region.sourcePath());
                                        row.put("regionId", region.regionId());
                                        row.put("modelKey", region.modelKey());
                                        row.put(
                                                "maintenance",
                                                new Maintenance(
                                                        maintenance.mode(),
                                                        maintenance.ownerModelKey(),
                                                        maintenance.fieldKey(),
                                                        excerpt(maintenance.reason(), 150)));
                                        return row;
                                    })
                            .toList());
            index.putAll(
                    Map.of(
                            "summary",
                            proposal.summary(),
                            "models",
                            proposal.models().stream()
                                    .map(
                                            model ->
                                                    Map.of(
                                                            "key",
                                                            model.key(),
                                                            "name",
                                                            model.name(),
                                                            "kind",
                                                            model.kind(),
                                                            "fields",
                                                            model.fields().stream()
                                                                    .map(Field::key)
                                                                    .toList()))
                                    .toList(),
                            "regions",
                            proposal.regions().size(),
                            "pagePurposes",
                            proposal.pages().size(),
                            "boundSourcePaths",
                            scope.pageDigests().keySet().stream()
                                    .filter(modeledPaths::contains)
                                    .toList(),
                            "reviewRequiredSourcePaths",
                            scope.pageDigests().keySet().stream()
                                    .filter(inspected::contains)
                                    .filter(path -> !modeledPaths.contains(path))
                                    .toList(),
                            "inspectedSourcePaths",
                            scope.pageDigests().keySet().stream()
                                    .filter(inspected::contains)
                                    .toList()));
            return excerpt(json.writeValueAsString(index), 24000);
        }

        private List<String> publicFragmentPaths() {
            return scope.catalog().pages().stream()
                    .flatMap(page -> page.includedPaths().stream())
                    .filter(path -> path.matches("(?i).*(?:header|footer)[^/]*\\.[^/]+$"))
                    .distinct()
                    .sorted()
                    .toList();
        }

        private List<String> uninspectedInAnalysisOrder(Set<String> inspected) {
            var priority = new HashMap<String, Integer>();
            var parentKeys = new HashSet<String>();
            var leafPaths = new HashSet<String>();
            for (var entry : navigation) {
                if (entry.parentKey() != null) parentKeys.add(entry.parentKey());
            }
            for (var entry : navigation) {
                if (entry.sourcePath() == null) continue;
                if (!parentKeys.contains(entry.key())) leafPaths.add(entry.sourcePath());
                if (entry.parentKey() == null
                        && ("首页".equals(entry.name()) || "/".equals(entry.sourceHref()))) {
                    priority.put(entry.sourcePath(), 3);
                } else if (parentKeys.contains(entry.key())) {
                    priority.putIfAbsent(entry.sourcePath(), 2);
                }
            }
            leafPaths.forEach(
                    path -> priority.computeIfPresent(path, (key, rank) -> rank == 2 ? 1 : rank));
            for (String path : publicFragmentPaths()) priority.put(path, 0);
            return scope.pageDigests().keySet().stream()
                    .filter(path -> !inspected.contains(path))
                    .sorted(Comparator.comparingInt(path -> priority.getOrDefault(path, 1)))
                    .toList();
        }

        Map<String, Object> run(String tool, JsonNode args) {
            return switch (tool) {
                case "read_catalog" -> {
                    requireKeys(args, "offset", "limit");
                    var window =
                            window(
                                    scope.catalog().pages(),
                                    args,
                                    100,
                                    page -> {
                                        var row = new LinkedHashMap<String, Object>();
                                        row.put("sourcePath", page.sourcePath());
                                        row.put("title", excerpt(page.title(), 180));
                                        row.put(
                                                "headings",
                                                page.headings().stream()
                                                        .limit(4)
                                                        .map(
                                                                value ->
                                                                        excerpt(
                                                                                value.rawValue(),
                                                                                140))
                                                        .toList());
                                        row.put(
                                                "links",
                                                page.links().stream()
                                                        .limit(10)
                                                        .map(
                                                                link ->
                                                                        Objects.toString(
                                                                                link.targetPath(),
                                                                                ""))
                                                        .toList());
                                        row.put(
                                                "includedPaths",
                                                page.includedPaths().stream().limit(10).toList());
                                        row.put(
                                                "asyncTargets",
                                                page.fragmentLinks().stream()
                                                        .limit(10)
                                                        .map(
                                                                link ->
                                                                        Objects.toString(
                                                                                link.targetPath(),
                                                                                ""))
                                                        .toList());
                                        addFragmentLinks(
                                                row, page.sourcePath(), page.fragmentLinks());
                                        row.put("analyzed", page.analyzed());
                                        row.put("warningCount", page.warnings().size());
                                        return row;
                                    });
                    window.items().forEach(page -> catalogRead.add(page.sourcePath()));
                    var response = new LinkedHashMap<>(window.response());
                    response.put("analysisOrder", ANALYSIS_ORDER);
                    response.put(
                            "suggestedUninspectedSourcePaths",
                            coveragePreview(
                                    uninspectedInAnalysisOrder(inspectedPaths()).stream()
                                            .limit(12)
                                            .toList(),
                                    3000));
                    yield response;
                }
                case "read_fragments" -> {
                    requireKeys(args, "offset", "limit");
                    var window =
                            window(
                                    scope.catalog().sharedFragments(),
                                    args,
                                    50,
                                    fragment ->
                                            Map.of(
                                                    "kind",
                                                    fragment.kind(),
                                                    "digest",
                                                    fragment.digest(),
                                                    "occurrences",
                                                    fragment.occurrences().stream()
                                                            .limit(20)
                                                            .toList(),
                                                    "totalOccurrences",
                                                    fragment.occurrences().size()));
                    window.items()
                            .forEach(
                                    fragment ->
                                            fragmentsRead.add(fragment.kind() + fragment.digest()));
                    var response = new LinkedHashMap<>(window.response());
                    response.put(
                            "includedFragments", publicFragmentPaths().stream().limit(50).toList());
                    yield response;
                }
                case "read_model" -> {
                    requireKeys(args, "offset", "limit");
                    var window =
                            window(
                                    new ArrayList<>(scope.menus().values()),
                                    args,
                                    50,
                                    SiteModelPlanService::menuView);
                    modelRead = true;
                    window.items().forEach(menu -> menusRead.add(menu.id()));
                    yield window.response();
                }
                case "read_content_candidates" -> readContentCandidates(args);
                case "read_page" -> readPages(args);
                case "read_page_review" -> readPageReview(args);
                case "read_form_actions" -> readFormActions(args);
                case "read_navigation" -> {
                    requireKeys(args, "offset", "limit");
                    navigation = evidence.navigation(code, version);
                    yield window(
                                    navigation,
                                    args,
                                    100,
                                    entry -> {
                                        var row = new LinkedHashMap<String, Object>();
                                        row.put("key", entry.key());
                                        row.put("parentKey", entry.parentKey());
                                        row.put("name", entry.name());
                                        row.put("sourcePath", entry.sourcePath());
                                        row.put("evidencePath", entry.evidencePath());
                                        row.put("evidenceSelector", entry.evidenceSelector());
                                        row.put("sourceHref", entry.sourceHref());
                                        row.put("observations", entry.observations());
                                        row.put("independentLanding", entry.independentLanding());
                                        var appliedMenuId = appliedMenuId(entry);
                                        if (appliedMenuId != null)
                                            row.put("appliedMenuId", appliedMenuId);
                                        if (entry.listDetailEvidence() != null)
                                            row.put(
                                                    "listDetailEvidence",
                                                    entry.listDetailEvidence());
                                        return row;
                                    })
                            .response();
                }
                case "read_region" -> readRegion(args);
                case "read_coverage" -> {
                    requireKeys(args, "offset", "limit");
                    var result =
                            new LinkedHashMap<>(
                                    window(coverageRows(), args, 60, row -> row).response());
                    result.put("note", "候选仅供审核。静态来源值需通过页面清单、页面证据和区域工具读取。已声明的关联字段仍需另行核对关联对象。");
                    yield result;
                }
                case "read_shared" -> readShared(args);
                case "validate_proposal" -> {
                    if (!modelRead
                            || catalogRead.size() != scope.pageDigests().size()
                            || pages.isEmpty() && !scope.pageDigests().isEmpty())
                        throw new IllegalArgumentException("请先读完整站点清单、CMS模型目录和代表页面");
                    requireKeys(
                            args, "summary", "models", "regions", "relations", "issues", "pages");
                    Proposal patch = normalize(json.treeToValue(args, Proposal.class));
                    Proposal next = merge(proposal, patch);
                    if (next.issues().size() >= ISSUE_LIMIT)
                        throw new IllegalArgumentException("整站候选待确认事项需少于300条");
                    var availablePages = new LinkedHashMap<>(inheritedPages);
                    availablePages.putAll(pages);
                    var availableValues = new HashSet<>(inheritedValues);
                    availableValues.addAll(valuesRead);
                    var availableMenus = new HashSet<>(inheritedMenus);
                    availableMenus.addAll(menusRead);
                    validate(next, scope, availablePages, availableValues, availableMenus);
                    proposal = next;
                    validated = true;
                    yield Map.of(
                            "models",
                            next.models().size(),
                            "regions",
                            next.regions().size(),
                            "pagesWithPurpose",
                            next.pages().size(),
                            "unresolved",
                            next.issues().size());
                }
                default -> throw new IllegalArgumentException("未知或禁止的站级Agent工具");
            };
        }

        private List<Map<String, Object>> coverageRows() {
            var uses = new HashMap<String, RegionUse>();
            var boundFields = new HashMap<String, Set<String>>();
            var relatedFields = new HashSet<String>();
            var modelPaths = new HashMap<String, Set<String>>();
            var titles = new HashMap<String, String>();
            var purposes = new HashMap<String, String>();
            if (scope.catalog() != null)
                scope.catalog()
                        .pages()
                        .forEach(page -> titles.put(page.sourcePath(), page.title()));
            if (proposal != null) {
                proposal.relations().forEach(relation -> relatedFields.add(relationKey(relation)));
                proposal.pages().forEach(page -> purposes.put(page.sourcePath(), page.purpose()));
                for (var use : proposal.regions()) {
                    uses.put(regionKey(use), use);
                    var fields =
                            boundFields.computeIfAbsent(use.modelKey(), key -> new HashSet<>());
                    use.records().forEach(record -> fields.addAll(record.bindings().keySet()));
                    modelPaths
                            .computeIfAbsent(use.modelKey(), key -> new LinkedHashSet<>())
                            .add(use.sourcePath());
                    var maintenance = use.maintenance();
                    if (maintenance != null && "REFERENCE".equals(maintenance.mode()))
                        relatedFields.add(
                                maintenance.ownerModelKey() + "\n" + maintenance.fieldKey());
                }
            }
            var banners = new ArrayList<Map<String, Object>>();
            var editors = new ArrayList<Map<String, Object>>();
            var repeated = new ArrayList<Map<String, Object>>();
            var fields = new ArrayList<Map<String, Object>>();
            var reload = new ArrayList<Map<String, Object>>();
            var unread = new ArrayList<Map<String, Object>>();
            for (String path : scope.pageDigests().keySet()) {
                var page = pages.getOrDefault(path, inheritedPages.get(path));
                if (page == null) {
                    var row = new LinkedHashMap<String, Object>();
                    boolean seen = priorInspectedPaths.contains(path);
                    row.put("kind", seen ? "PAGE_EVIDENCE_REQUIRED" : "UNINSPECTED_PAGE");
                    row.put("sourcePath", path);
                    String title = excerpt(titles.get(path), 160);
                    if (!title.isBlank()) row.put("title", title);
                    String purpose = excerpt(purposes.get(path), 160);
                    if (!purpose.isBlank()) row.put("purpose", purpose);
                    (seen ? reload : unread).add(row);
                    continue;
                }
                var boundPositions = new HashSet<ValuePosition>();
                for (var region : page.regions()) {
                    var use = uses.get(path + "\n" + region.id());
                    if (use == null) continue;
                    var boundIds = new HashSet<String>();
                    use.records().forEach(record -> boundIds.addAll(record.bindings().values()));
                    region.records().stream()
                            .flatMap(record -> record.values().stream())
                            .filter(value -> boundIds.contains(value.id()))
                            .forEach(
                                    value ->
                                            boundPositions.add(
                                                    new ValuePosition(
                                                            value.selector(), value.attribute())));
                }
                for (var region : page.regions()) {
                    if (SiteModelEvidenceService.publicRegion(region)) continue;
                    var use = uses.get(path + "\n" + region.id());
                    if (use == null
                            && ("BANNER".equals(region.kind()) || "REPEATED".equals(region.kind()))
                            && region.records().stream()
                                    .anyMatch(record -> !record.values().isEmpty())) {
                        var candidates = "BANNER".equals(region.kind()) ? banners : repeated;
                        candidates.add(
                                Map.of(
                                        "kind", "UNMAPPED_REGION",
                                        "sourcePath", path,
                                        "regionId", region.id(),
                                        "regionKind", region.kind(),
                                        "recordCount", region.records().size(),
                                        "requiresPageRead", !pages.containsKey(path),
                                        "valueTypes",
                                                region.records().stream()
                                                        .flatMap(record -> record.values().stream())
                                                        .map(
                                                                SiteModelEvidenceService.Value
                                                                        ::typeHint)
                                                        .distinct()
                                                        .sorted()
                                                        .toList(),
                                        "sourceWarnings",
                                                region.warnings().stream()
                                                        .limit(2)
                                                        .map(warning -> excerpt(warning, 200))
                                                        .toList()));
                    }
                    for (var record : region.records()) {
                        for (var value : record.values()) {
                            if ("HTML_REGION".equals(value.typeHint())
                                    && !boundPositions.contains(
                                            new ValuePosition(value.selector(), value.attribute())))
                                editors.add(
                                        Map.of(
                                                "kind", "UNMAPPED_EDITOR",
                                                "sourcePath", path,
                                                "regionId", region.id(),
                                                "recordId", record.id(),
                                                "valueId", value.id(),
                                                "requiresPageRead", !pages.containsKey(path),
                                                "sourceWarnings",
                                                        region.warnings().stream()
                                                                .limit(2)
                                                                .map(
                                                                        warning ->
                                                                                excerpt(
                                                                                        warning,
                                                                                        200))
                                                                .toList(),
                                                "valueReadThisRound",
                                                        valuesRead.contains(value.id())));
                        }
                    }
                }
            }
            if (proposal != null) {
                for (var model : proposal.models()) {
                    var paths =
                            modelPaths.getOrDefault(model.key(), Set.of()).stream()
                                    .limit(4)
                                    .toList();
                    for (var field : model.fields()) {
                        if (!"Sort".equals(field.type())
                                && !relatedFields.contains(model.key() + "\n" + field.key())
                                && !boundFields
                                        .getOrDefault(model.key(), Set.of())
                                        .contains(field.key()))
                            fields.add(
                                    Map.of(
                                            "kind", "UNBOUND_FIELD",
                                            "modelKey", model.key(),
                                            "fieldKey", field.key(),
                                            "fieldLabel", field.name(),
                                            "fieldType", field.type(),
                                            "relatedSourcePaths", paths));
                    }
                }
            }
            banners.addAll(editors);
            banners.addAll(repeated);
            banners.addAll(fields);
            banners.addAll(reload);
            banners.addAll(unread);
            return banners;
        }

        private <T> List<T> coveragePreview(List<T> entries, int maxChars) {
            var preview = new ArrayList<T>();
            int chars = 0;
            for (T entry : entries) {
                int length = json.writeValueAsString(entry).length() + 1;
                if (chars + length > maxChars) break;
                preview.add(entry);
                chars += length;
            }
            return preview;
        }

        private Map<String, Object> readPages(JsonNode args) {
            requireKeys(args, "sourcePaths");
            JsonNode requested = args.path("sourcePaths");
            if (!requested.isArray() || requested.size() < 1 || requested.size() > 12)
                throw new IllegalArgumentException("每次读取1至12个已列出的页面");
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> remaining = new ArrayList<>();
            var loaded = new LinkedHashMap<String, PageEvidence>();
            int chars = 0;
            for (var pathNode : requested) {
                String path = pathNode.asText("");
                if (!pathNode.isTextual() || !catalogRead.contains(path))
                    throw new IllegalArgumentException("页面尚未从站点清单读取");
                var page = pages.getOrDefault(path, inheritedPages.get(path));
                if (page == null) page = loadPage(scope, code, version, path);
                var row =
                        new LinkedHashMap<>(Map.<String, Object>of(
                                "sourcePath",
                                page.sourcePath(),
                                "title",
                                excerpt(page.title(), 240),
                                "headings",
                                page.headings().stream()
                                        .limit(10)
                                        .map(value -> excerpt(value.rawValue(), 160))
                                        .toList(),
                                "links",
                                page.links().stream().limit(20).toList(),
                                "warnings",
                                page.warnings().stream().limit(10).toList(),
                                "regions",
                                page.regions().stream()
                                        .filter(
                                                region ->
                                                        !SiteModelEvidenceService.publicRegion(
                                                                region))
                                        .limit(120)
                                        .map(
                                                region ->
                                                        Map.of(
                                                                "id",
                                                                region.id(),
                                                                "kind",
                                                                region.kind(),
                                                                "selector",
                                                                region.selector(),
                                                                "records",
                                                                region.records().size(),
                                                                "warnings",
                                                                region.warnings().stream()
                                                                        .limit(4)
                                                                        .toList()))
                                        .toList(),
                                "totalRegions",
                                page.regions().stream()
                                        .filter(
                                                region ->
                                                        !SiteModelEvidenceService.publicRegion(
                                                                region))
                                        .count()));
                addFragmentLinks(row, page.sourcePath(), page.fragmentLinks());
                int length = json.writeValueAsString(row).length();
                if (length + chars > TOOL_RESULT_LIMIT - 8000) {
                    remaining.add(path);
                    continue;
                }
                chars += length + 1;
                rows.add(row);
                loaded.put(path, page);
            }
            // 将工具实际返回的页面记为已观察，不能凭目录摘要直接提交区域引用。
            pages.putAll(loaded);
            return Map.of("pages", rows, "remainingPaths", remaining);
        }

        private void addFragmentLinks(
                Map<String, Object> row,
                String sourcePath,
                List<SiteModelEvidenceService.PageLink> links) {
            if (links.isEmpty()) return;
            row.put(
                    "fragmentLinks",
                    links.stream()
                            .limit(10)
                            .map(
                                    link ->
                                            Map.of(
                                                    "sourcePath", sourcePath,
                                                    "selector", link.selector(),
                                                    "rawValue", link.rawValue(),
                                                    "targetPath", link.targetPath()))
                            .toList());
            row.put("fragmentLinkCount", links.size());
            row.put("fragmentLinkNote", "包内异步引用为静态证据。同一目标可供多个业务对象共用，实际脚本行为待核对。");
        }

        private Map<String, Object> readRegion(JsonNode args) {
            requireKeys(args, "sourcePath", "regionId", "offset", "limit");
            var page = pages.get(args.path("sourcePath").asText(""));
            if (page == null) throw new IllegalArgumentException("请先读取页面区域清单");
            var region =
                    page.regions().stream()
                            .filter(item -> !SiteModelEvidenceService.publicRegion(item))
                            .filter(item -> item.id().equals(args.path("regionId").asText("")))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("区域不属于该页面"));
            var rows = new ArrayList<RegionValue>();
            region.records()
                    .forEach(
                            record -> {
                                if (record.values().isEmpty())
                                    rows.add(
                                            new RegionValue(
                                                    record.id(),
                                                    record.order(),
                                                    record.selector(),
                                                    null));
                                else
                                    record.values()
                                            .forEach(
                                                    value ->
                                                            rows.add(
                                                                    new RegionValue(
                                                                            record.id(),
                                                                            record.order(),
                                                                            record.selector(),
                                                                            value)));
                            });
            var window =
                    window(
                            rows,
                            args,
                            150,
                            item -> {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("recordId", item.recordId());
                                row.put("order", item.order());
                                row.put("recordSelector", item.recordSelector());
                                var value = item.value();
                                if (value != null) {
                                    String raw = value.rawValue();
                                    row.put("valueId", value.id());
                                    row.put("selector", value.selector());
                                    row.put("attribute", value.attribute());
                                    row.put("typeHint", value.typeHint());
                                    row.put("excerptTruncated", raw.length() > 12000);
                                    if (value.targetPath() != null)
                                        row.put("targetPath", value.targetPath());
                                    row.put(
                                            "rawValue",
                                            raw.length() > 12000 ? excerpt(raw, 500) : raw);
                                    if (value.valueRangeHint() != null)
                                        row.put("valueRangeHint", value.valueRangeHint());
                                }
                                return row;
                            });
            window.items().stream()
                    .map(RegionValue::value)
                    .filter(Objects::nonNull)
                    .filter(value -> value.rawValue().length() <= 12000)
                    .map(SiteModelEvidenceService.Value::id)
                    .forEach(valuesRead::add);
            var result = new LinkedHashMap<>(window.response());
            result.put("sourcePath", page.sourcePath());
            result.put("regionId", region.id());
            result.put("warnings", region.warnings().stream().limit(12).toList());
            return result;
        }

        private Map<String, Object> readContentCandidates(JsonNode args) {
            requireKeys(args, "menuIds", "keyword", "offset", "limit");
            if (contentEvidence == null) throw new IllegalStateException("已有内容候选读取能力未配置");
            if (!args.path("menuIds").isArray() || args.path("menuIds").size() > 20)
                throw new IllegalArgumentException("请选择已读取模型中的本站列表栏目");
            var ids = new ArrayList<Integer>();
            for (var value : args.path("menuIds")) {
                if (!value.isIntegralNumber() || !value.canConvertToInt())
                    throw new IllegalArgumentException("候选栏目ID无效");
                int id = value.intValue();
                var menu = scope.menus().get((long) id);
                if (!menusRead.contains((long) id)
                        || menu == null
                        || !Integer.valueOf(1).equals(menu.pageType()))
                    throw new IllegalArgumentException("先通过read_model读取对应的本站列表栏目");
                ids.add(id);
            }
            var result =
                    contentEvidence.read(
                            siteId,
                            ids,
                            args.path("keyword").asText(""),
                            args.path("offset").asInt(0),
                            args.path("limit").asInt(20));
            return Map.of(
                    "rows",
                    result.rows(),
                    "offset",
                    result.offset(),
                    "nextOffset",
                    result.nextOffset(),
                    "total",
                    result.total(),
                    "hasMore",
                    result.hasMore());
        }

        private Map<String, Object> readShared(JsonNode args) {
            requireKeys(args, "sourcePath", "offset", "limit");
            String path = args.path("sourcePath").asText("");
            var page = loadPage(scope, code, version, path);
            var shared = evidence.sharedValues(code, version, path);
            if (!page.digest().equals(shared.digest())) throw conflict("公共页面证据已变化，请重新读取");
            var window =
                    window(
                            shared.values(),
                            args,
                            50,
                            value -> {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("valueId", value.id());
                                row.put(
                                        "regionId",
                                        SiteModelEvidenceService.sharedId(
                                                path, value.region(), "region"));
                                row.put(
                                        "recordId",
                                        SiteModelEvidenceService.sharedId(
                                                path, value.region(), "record"));
                                row.put("kind", "PUBLIC_" + value.region());
                                row.put("selector", value.selector());
                                row.put("attribute", value.attribute());
                                row.put("typeHint", value.typeHint());
                                row.put("rawValue", value.rawValue());
                                row.put("start", value.start());
                                row.put("end", value.end());
                                row.put("targetPath", value.targetPath());
                                String fieldType = null;
                                String limitation = null;
                                if ("TEXT".equals(value.typeHint())
                                        && "text".equals(value.attribute())) {
                                    fieldType = "SingleLine";
                                } else if ("IMAGE".equals(value.typeHint())
                                        && "src".equals(value.attribute())) {
                                    fieldType = "Image";
                                    limitation = "图片仅为候选，须核对 img[src] 无 srcset、data-src 或 th:src";
                                    if (value.targetPath() == null)
                                        limitation += "。未解析为包内资源，初值待人工处理";
                                } else if ("LINK".equals(value.typeHint())) {
                                    limitation = "链接地址保留为证据，按导航链接或人工处理";
                                } else {
                                    limitation = "当前公共模板不支持此属性，待人工处理";
                                }
                                row.put("bindableFieldType", fieldType);
                                if (limitation != null) row.put("bindingLimitation", limitation);
                                return row;
                            });
            pages.put(path, page);
            window.items().stream()
                    .map(SiteModelEvidenceService.SharedValue::id)
                    .forEach(valuesRead::add);
            var response = new LinkedHashMap<>(window.response());
            response.put("sourcePath", path);
            response.put("digest", shared.digest());
            response.put("includedPaths", shared.includedPaths());
            var usages =
                    scope.catalog().pages().stream()
                            .filter(item -> item.includedPaths().contains(path))
                            .map(SiteModelEvidenceService.PageSummary::sourcePath)
                            .toList();
            response.put("usedBy", usages.stream().limit(50).toList());
            response.put("totalUses", usages.size());
            response.put("warnings", shared.warnings());
            return response;
        }

        private Long appliedMenuId(SiteModelNavigationEvidence.Entry entry) {
            var applied = appliedNavigation.get(entry.key());
            if (applied == null
                    || entry.sourcePath() == null
                    || !Objects.equals(entry.sourcePath(), applied.sourcePath())
                    || !Objects.equals(entry.parentKey(), applied.parentKey())) return null;
            var menu = scope.menus().get(applied.menuId().longValue());
            if (applied == appliedRoot) return menu.id();
            var parent =
                    entry.parentKey() == null
                            ? appliedRoot
                            : appliedNavigation.get(entry.parentKey());
            return parent != null && Objects.equals(menu.parentId(), parent.menuId())
                    ? menu.id()
                    : null;
        }

        private Map<String, Object> readPageReview(JsonNode args) {
            requireKeys(args, "sourcePath", "offset", "limit");
            var page = pages.get(args.path("sourcePath").asText(""));
            if (page == null
                    || !Objects.equals(scope.pageDigests().get(page.sourcePath()), page.digest()))
                throw new IllegalArgumentException("请先读取当前页面包中的页面区域清单");
            if (pageReviewEvidence == null) throw new IllegalStateException("缺少页面审核取证服务");
            var review =
                    pageReviewEvidence.inspect(code, version, page.sourcePath(), page.digest());
            var result =
                    new LinkedHashMap<>(
                            window(
                                            review.suggestions(),
                                            args,
                                            16,
                                            suggestion -> {
                                                var row = new LinkedHashMap<String, Object>();
                                                row.put("kind", suggestion.kind());
                                                row.put("path", suggestion.path());
                                                row.put("selector", suggestion.selector());
                                                row.put("targetPath", suggestion.targetPath());
                                                row.put("facts", suggestion.facts());
                                                row.put("reason", suggestion.reason());
                                                return row;
                                            })
                                    .response());
            result.put("sourcePath", page.sourcePath());
            result.put("sourceDigest", page.digest());
            result.put("warnings", review.warnings());
            return result;
        }

        private Map<String, Object> readFormActions(JsonNode args) {
            requireKeys(args, "sourcePath", "offset", "limit");
            var page = pages.get(args.path("sourcePath").asText(""));
            if (page == null) throw new IllegalArgumentException("请先读取页面区域清单");
            if (packages == null) throw new IllegalStateException("缺少包内表单动作取证服务");
            var source = packages.readStaticPage(code, version, page.sourcePath());
            if (!page.digest().equals(source.digest()))
                throw new IllegalArgumentException("页面证据已变化，请重新生成整站计划");
            var document = Jsoup.parse(source.html());
            var links =
                    page.links().stream()
                            .filter(link -> !page.sourcePath().equals(link.targetPath()))
                            .filter(link -> scope.pageDigests().containsKey(link.targetPath()))
                            .filter(
                                    link -> {
                                        var matches = document.select(link.selector());
                                        if (matches.size() != 1) return false;
                                        var anchor = matches.first();
                                        return anchor.is("a[href]:not([onclick])")
                                                && anchor.attr("href").equals(link.rawValue())
                                                && anchor.closest(
                                                                "header,nav,footer,.header,.nav,.footer,[role=navigation]")
                                                        == null;
                                    })
                            .toList();
            var candidates =
                    window(
                            links,
                            args,
                            12,
                            link -> {
                                var reference =
                                        new TemplateContentUnitsService.Source(
                                                link.selector(),
                                                "LINK",
                                                link.rawValue(),
                                                link.selector(),
                                                null,
                                                null);
                                var candidate =
                                        FormActionBinding.inspect(
                                                        packages,
                                                        code,
                                                        version,
                                                        page.sourcePath(),
                                                        document,
                                                        reference)
                                                .orElse(null);
                                if (candidate == null
                                        || !link.targetPath().equals(candidate.targetPath()))
                                    return Map.of();
                                if (!Objects.equals(
                                        scope.pageDigests().get(candidate.targetPath()),
                                        candidate.targetDigest()))
                                    throw new IllegalArgumentException("表单目标证据已变化，请重新生成整站计划");
                                return Map.of(
                                        "sourceSelector", link.selector(),
                                        "label", candidate.label(),
                                        "targetPath", candidate.targetPath(),
                                        "targetTitle", excerpt(candidate.targetTitle(), 180),
                                        "targetDigest", candidate.targetDigest(),
                                        "fields", candidate.fields());
                            });
            if (!page.digest()
                    .equals(packages.readStaticPage(code, version, page.sourcePath()).digest()))
                throw new IllegalArgumentException("页面证据已变化，请重新生成整站计划");
            var result = new LinkedHashMap<>(candidates.response());
            result.put("sourcePath", page.sourcePath());
            result.put("sourceDigest", page.digest());
            return result;
        }

        void canFinish() {
            var blocker = finishBlocker();
            if (blocker != null) throw new IllegalArgumentException(blocker);
        }

        String finishBlocker() {
            if (!validated) return "请先校验本轮站级提案";
            if (fragmentsRead.size() != scope.catalog().sharedFragments().size()
                    || menusRead.size() != scope.menus().size())
                return "请继续读取其余共享片段和CMS栏目，再结束站级提案";
            return null;
        }
    }

    private record RegionValue(
            String recordId,
            int order,
            String recordSelector,
            SiteModelEvidenceService.Value value) {}

    private record ValuePosition(String selector, String attribute) {}

    private record Window<T>(List<T> items, Map<String, Object> response) {}

    private <T> Window<T> window(
            List<T> source, JsonNode args, int max, Function<T, Map<String, Object>> project) {
        int offset = integer(args, "offset", 0, source.size());
        int limit = integer(args, "limit", 1, max);
        List<Map<String, Object>> rows = new ArrayList<>();
        int chars = 0;
        int next = offset;
        for (; next < Math.min(source.size(), offset + limit); next++) {
            var row = project.apply(source.get(next));
            if (row.isEmpty()) continue;
            int length = json.writeValueAsString(row).length();
            if (length + chars > TOOL_RESULT_LIMIT - 2000) break;
            rows.add(row);
            chars += length + 1;
        }
        if (next == offset && offset < source.size())
            throw new IllegalArgumentException("单条证据超出工具限制，请保留待处理");
        return new Window<>(
                source.subList(offset, next),
                Map.of(
                        "rows",
                        rows,
                        "nextOffset",
                        next,
                        "total",
                        source.size(),
                        "hasMore",
                        next < source.size()));
    }

    private static Map<String, Object> menuView(Menu menu) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", menu.id());
        result.put("parentId", menu.parentId());
        result.put("subMenu", Boolean.TRUE.equals(menu.subMenu()));
        result.put("name", excerpt(menu.name(), 100));
        result.put("pageType", menu.pageType());
        result.put("listPath", excerpt(menu.listPath(), 200));
        result.put("detailPath", excerpt(menu.detailPath(), 200));
        result.put(
                "fields",
                menu.fields().stream()
                        .filter(field -> ContentFieldDefinition.enabled(field.getIsOpen()))
                        .limit(120)
                        .map(
                                field ->
                                        Map.of(
                                                "fieldName",
                                                excerpt(field.getFieldName(), 80),
                                                "name",
                                                excerpt(
                                                        field.getTitle() == null
                                                                ? field.getFromName()
                                                                : field.getTitle(),
                                                        100),
                                                "type",
                                                excerpt(field.getFieldType(), 50),
                                                "readOnly",
                                                ContentFieldDefinition.enabled(
                                                        field.getIsReadOnly()),
                                                "isRelation",
                                                ContentReferences.applies(field)))
                        .toList());
        return result;
    }

    private static String excerpt(String value, int length) {
        if (value == null) return "";
        return value.length() > length ? value.substring(0, length) : value;
    }

    private static void requireKeys(JsonNode object, String... keys) {
        if (object == null || !object.isObject()) throw new IllegalArgumentException("动作必须为JSON对象");
        var allowed = Set.of(keys);
        object.propertyNames()
                .forEach(
                        name -> {
                            if (!allowed.contains(name))
                                throw new IllegalArgumentException("工具参数超出允许范围");
                        });
    }

    private static int integer(JsonNode args, String key, int minimum, int maximum) {
        JsonNode node = args.path(key);
        if (!node.isIntegralNumber()
                || !node.canConvertToInt()
                || node.asInt() < minimum
                || node.asInt() > maximum) throw new IllegalArgumentException("分页参数超出范围");
        return node.asInt();
    }

    private static Proposal merge(Proposal previous, Proposal patch) {
        if (previous == null) return patch;
        var issues = new ArrayList<>(previous.issues());
        for (String issue : patch.issues()) if (!issues.contains(issue)) issues.add(issue);
        var models = index(previous.models(), Model::key, "内容类型");
        index(patch.models(), Model::key, "本次内容类型")
                .forEach(
                        (key, model) ->
                                models.merge(
                                        key,
                                        model,
                                        (before, after) ->
                                                new Model(
                                                        key,
                                                        after.name(),
                                                        after.kind(),
                                                        after.menuId(),
                                                        mergeRows(
                                                                before.fields(),
                                                                safe(after.fields()),
                                                                Field::key))));
        return new Proposal(
                patch.summary().isBlank() ? previous.summary() : patch.summary(),
                List.copyOf(models.values()),
                mergeRows(previous.regions(), patch.regions(), SiteModelPlanService::regionKey),
                mergeRows(
                        previous.relations(), patch.relations(), SiteModelPlanService::relationKey),
                List.copyOf(issues),
                mergeRows(previous.pages(), patch.pages(), PageUse::sourcePath));
    }

    private static <T, K> List<T> mergeRows(List<T> original, List<T> patch, Function<T, K> key) {
        var rows = index(original, key, "提案");
        rows.putAll(index(patch, key, "本次提案"));
        return List.copyOf(rows.values());
    }

    private static String regionKey(RegionUse region) {
        return region.sourcePath() + "\n" + region.regionId();
    }

    private static String relationKey(Relation relation) {
        return relation.sourceModelKey() + "\n" + relation.fieldKey();
    }

    private static Proposal normalize(Proposal source) {
        return new Proposal(
                source.summary() == null ? "" : source.summary().strip(),
                safe(source.models()),
                safe(source.regions()),
                safe(source.relations()),
                safe(source.issues()),
                safe(source.pages()));
    }

    private static <T> List<T> safe(List<T> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("提案列表不能包含空条目");
        return List.copyOf(values);
    }

    private static <T, K> Map<K, T> index(List<T> rows, Function<T, K> key, String label) {
        var result = new LinkedHashMap<K, T>();
        for (T row : rows) {
            K id = key.apply(row);
            if (id == null || result.putIfAbsent(id, row) != null)
                throw new IllegalArgumentException(label + "标识缺失或重复");
        }
        return result;
    }

    private static void validate(
            Proposal proposal,
            Snapshot scope,
            Map<String, PageEvidence> pages,
            Set<String> valuesRead,
            Set<Long> menusRead) {
        if (proposal.summary().length() > 2500
                || proposal.models().size() > 120
                || proposal.regions().size() > 400
                || proposal.relations().size() > 250
                || proposal.issues().size() > ISSUE_LIMIT
                || proposal.pages().size() > scope.pageDigests().size())
            throw new IllegalArgumentException("整站提案超出范围");
        var models = index(proposal.models(), Model::key, "内容类型");
        var modelFields = new LinkedHashMap<String, Map<String, Field>>();
        var modelNames = new HashSet<String>();
        for (Model model : models.values()) {
            key(model.key());
            required(model.name(), 100, "内容类型名称");
            if (!modelNames.add(model.name().strip().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("同一业务内容类型应合并为一个模型");
            if (model.kind() == null || !MODEL_KINDS.contains(model.kind()))
                throw new IllegalArgumentException("未知内容类型维护方式");
            var fields = safe(model.fields());
            if (fields.isEmpty() || fields.size() > 80)
                throw new IllegalArgumentException("内容类型需要1至80个字段");
            modelFields.put(model.key(), index(fields, Field::key, "模型字段"));
            Menu menu = model.menuId() == null ? null : scope.menus().get(model.menuId());
            if (model.menuId() != null
                    && (menu == null || menusRead != null && !menusRead.contains(model.menuId())))
                throw new IllegalArgumentException("目标栏目不属于已读取的本站栏目");
            if (menu != null
                    && ("LIST".equals(model.kind()) && !Integer.valueOf(1).equals(menu.pageType())
                            || "SINGLE".equals(model.kind())
                                    && !Integer.valueOf(0).equals(menu.pageType())))
                throw new IllegalArgumentException("内容类型与实际栏目类型不匹配");
            var names = new HashSet<String>();
            var slots = new HashSet<String>();
            for (Field field : fields) {
                key(field.key());
                required(field.name(), 100, "字段业务名称");
                if (!names.add(field.name().strip().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("同一模型的字段业务名称必须唯一");
                if (field.type() == null || !FIELD_TYPES.contains(field.type()))
                    throw new IllegalArgumentException("模型包含不支持的字段类型");
                if ("Sort".equals(field.type())) {
                    if (!"LIST".equals(model.kind()) || !"sort".equals(field.fieldName()))
                        throw new IllegalArgumentException("列表排序须使用原生sort");
                } else if (field.fieldName() != null) {
                    required(field.fieldName(), 100, "底层字段标识");
                    if (actualField(menu, field) == null)
                        throw new IllegalArgumentException("底层字段必须是目标栏目中启用的同类型可维护字段");
                }
                if (field.fieldName() != null && !slots.add(field.fieldName()))
                    throw new IllegalArgumentException("同一模型的多个字段不能占用同一个底层字段");
            }
        }
        index(proposal.pages(), PageUse::sourcePath, "页面用途")
                .forEach(
                        (path, page) -> {
                            if (!scope.pageDigests().containsKey(path))
                                throw new IllegalArgumentException("用途引用了包清单之外的页面");
                            required(page.purpose(), 300, "页面用途");
                        });
        var boundFields = new HashMap<String, Set<String>>();
        var linkFields = new HashMap<String, Set<String>>();
        for (RegionUse region :
                index(proposal.regions(), SiteModelPlanService::regionKey, "页面区域").values()) {
            validateRegion(region, pages, models, modelFields, valuesRead, boundFields, linkFields);
        }
        var relations = index(proposal.relations(), SiteModelPlanService::relationKey, "内容关联");
        for (Relation relation : relations.values()) {
            Model model = models.get(relation.sourceModelKey());
            Model target = models.get(relation.targetModelKey());
            Field field =
                    model == null ? null : modelFields.get(model.key()).get(relation.fieldKey());
            if (field == null
                    || !"SingleLine".equals(field.type())
                    || relation.targetModelKey() != null && target == null)
                throw new IllegalArgumentException("关联必须指向已声明的源字段与可选目标内容类型");
            if (linkFields.getOrDefault(model.key(), Set.of()).contains(field.key()))
                throw new IllegalArgumentException(
                        "字段 " + model.key() + "." + field.key() + " 已绑定链接证据，不能用作内容ID关联");
            if (boundFields.getOrDefault(model.key(), Set.of()).contains(field.key()))
                throw new IllegalArgumentException("内容ID关联只能选择实际记录，不能绑定页面原值：" + field.name());
            required(relation.reason(), 500, "关联依据或未决原因");
            if (field.fieldName() != null) {
                var actual = actualField(scope.menus().get(model.menuId()), field);
                if (!ContentReferences.applies(actual)
                        || target != null
                                && target.menuId() != null
                                && !ContentReferences.menuIds(actual)
                                        .contains(target.menuId().intValue()))
                    throw new IllegalArgumentException("现有CMS关联字段与计划目标不匹配");
            }
        }
        for (var model : models.values()) {
            var menu = scope.menus().get(model.menuId());
            var bound = boundFields.getOrDefault(model.key(), Set.of());
            if (model.fields().stream()
                    .anyMatch(
                            field ->
                                    bound.contains(field.key())
                                            && ContentReferences.applies(actualField(menu, field))))
                throw new IllegalArgumentException("已配置的内容ID关联只能选择实际记录，不能绑定页面原值");
        }
        validateMaintenance(proposal, scope, pages, models, modelFields, relations, boundFields);
        proposal.issues().forEach(issue -> required(issue, 500, "待确认项"));
    }

    private static void validateMaintenance(
            Proposal proposal,
            Snapshot scope,
            Map<String, PageEvidence> pages,
            Map<String, Model> models,
            Map<String, Map<String, Field>> fields,
            Map<String, Relation> relations,
            Map<String, Set<String>> boundFields) {
        var ownedModels = new HashMap<String, String>();
        var ownedMenus = new HashMap<Long, String>();
        var singlePageLists = new HashSet<String>();
        var pageSlots = new HashSet<List<String>>();
        for (RegionUse use : proposal.regions()) {
            var maintenance = use.maintenance();
            if (maintenance == null) continue;
            required(maintenance.reason(), 500, "区域维护依据");
            var owner = models.get(maintenance.ownerModelKey());
            var target = models.get(use.modelKey());
            boolean pageList = "PAGE_LIST".equals(maintenance.mode());
            var details =
                    proposal.regions().stream()
                            .filter(
                                    region ->
                                            use.sourcePath().equals(region.sourcePath())
                                                    && (pageList ? "SINGLE" : "DETAIL").equals(region.mode())
                                                    && !SiteModelEvidenceService.publicRegion(
                                                            region.sourcePath(), region.regionId()))
                            .toList();
            if (owner == null
                    || details.size() != 1
                    || !owner.key().equals(details.getFirst().modelKey())
                    || details.getFirst().records().size() != 1)
                throw new IllegalArgumentException("区域维护必须关联同页唯一的主体记录");
            if (!"LIST".equals(target.kind()))
                throw new IllegalArgumentException("区域维护的子条目或引用目标必须为列表内容模型");
            if (pageList) {
                var child = scope.menus().get(target.menuId());
                if (!"SINGLE".equals(owner.kind())
                        || owner.menuId() == null
                        || child == null
                        || !Integer.valueOf(1).equals(child.pageType())
                        || Boolean.TRUE.equals(child.subMenu())
                        || !Objects.equals(child.parentId(), Math.toIntExact(owner.menuId()))
                        || maintenance.fieldKey() != null
                        || !"LIST".equals(use.mode())
                        || target.fields().stream().noneMatch(field ->
                                "Sort".equals(field.type()) && "sort".equals(field.fieldName()))
                        || !singlePageLists.add(target.key())
                        || models.values().stream().anyMatch(model ->
                                !model.key().equals(target.key())
                                        && Objects.equals(model.menuId(), target.menuId())))
                    throw new IllegalArgumentException("单页重复区域须关联其专用列表子栏目及原生排序");
            } else if ("OWNED".equals(maintenance.mode())) {
                if (owner.equals(target)
                        || owner.menuId() != null && owner.menuId().equals(target.menuId())
                        || maintenance.fieldKey() != null
                        || !"LIST".equals(use.mode())
                        || target.fields().stream().noneMatch(field -> "Sort".equals(field.type())))
                    throw new IllegalArgumentException("所属条目须使用列表区域、原生归属和排序");
                String prior = ownedModels.putIfAbsent(target.key(), owner.key());
                if (prior != null && !owner.key().equals(prior))
                    throw new IllegalArgumentException("同一所属条目模型只能对应一个父模型");
                if (!pageSlots.add(List.of(use.sourcePath(), "OWNED", target.key())))
                    throw new IllegalArgumentException("同页多个区域不能共用同一所属条目模型");
                if (target.menuId() != null) {
                    ownedMenus.put(target.menuId(), target.key());
                    var menu = scope.menus().get(target.menuId());
                    if (owner.menuId() == null
                            || !Boolean.TRUE.equals(menu.subMenu())
                            || menu.parentId() == null
                            || owner.menuId().longValue() != menu.parentId())
                        throw new IllegalArgumentException("所属条目栏目必须是主体栏目的原生内容子栏目");
                }
            } else if ("REFERENCE".equals(maintenance.mode())) {
                var field = fields.get(owner.key()).get(maintenance.fieldKey());
                var relation = relations.get(owner.key() + "\n" + maintenance.fieldKey());
                if (field == null
                        || relation == null
                        || !target.key().equals(relation.targetModelKey())
                        || boundFields.getOrDefault(owner.key(), Set.of()).contains(field.key())
                        || !pageSlots.add(List.of(use.sourcePath(), "REFERENCE", field.key())))
                    throw new IllegalArgumentException("引用区域须使用主体独立的内容ID关联字段，关联目标须与区域模型一致");
                if (field.fieldName() != null) {
                    var actual = actualField(scope.menus().get(owner.menuId()), field);
                    if (target.menuId() == null
                            || !ContentReferences.applies(actual)
                            || !Boolean.TRUE.equals(actual.getInputMultiple())
                            || !ContentReferences.menuIds(actual)
                                    .contains(Math.toIntExact(target.menuId()))
                            || ContentReferences.menuIds(actual).stream()
                                    .anyMatch(
                                            id -> {
                                                var menu = scope.menus().get(id.longValue());
                                                return menu == null
                                                        || !Integer.valueOf(1)
                                                                .equals(menu.pageType())
                                                        || Boolean.TRUE.equals(menu.subMenu());
                                            }))
                        throw new IllegalArgumentException("引用区域须配置包含目标栏目的本站有序多选字段");
                }
            } else {
                throw new IllegalArgumentException("未知区域维护方式");
            }
            validateMaintenanceOverlap(use, proposal.regions(), pages.get(use.sourcePath()));
        }
        for (Model model : models.values()) {
            String ownedModel = ownedMenus.get(model.menuId());
            if (ownedModel != null && !ownedModel.equals(model.key()))
                throw new IllegalArgumentException("同一实际所属条目栏目不能对应多个内容模型或作为独立列表");
        }
        for (RegionUse use : proposal.regions()) {
            if (singlePageLists.contains(use.modelKey())
                    && (use.maintenance() == null || !"PAGE_LIST".equals(use.maintenance().mode())))
                throw new IllegalArgumentException("单页重复区域的列表不能同时用于其他区域");
            if (ownedModels.containsKey(use.modelKey())
                    && (use.maintenance() == null || !"OWNED".equals(use.maintenance().mode())))
                throw new IllegalArgumentException("所属条目模型只能在其主体详情中维护");
            if (use.maintenance() != null
                    && ownedModels.containsKey(use.maintenance().ownerModelKey()))
                throw new IllegalArgumentException("详情区域维护不支持嵌套的所属条目");
        }
    }

    private static void validateMaintenanceOverlap(
            RegionUse use, List<RegionUse> uses, PageEvidence page) {
        var selectors =
                page.regions().stream()
                        .filter(region -> use.regionId().equals(region.id()))
                        .flatMap(region -> region.records().stream())
                        .map(SiteModelEvidenceService.SourceRecord::selector)
                        .toList();
        for (RegionUse other : uses) {
            if (other == use || !use.sourcePath().equals(other.sourcePath())) continue;
            var records =
                    page.regions().stream()
                            .filter(region -> other.regionId().equals(region.id()))
                            .flatMap(region -> region.records().stream())
                            .toList();
            var bindings = index(other.records(), RecordUse::recordId, "区域记录");
            for (var record : records) {
                if (other.maintenance() != null
                        && selectors.stream()
                                .anyMatch(selector -> samePosition(record.selector(), selector)))
                    throw new IllegalArgumentException("多个详情集合不能覆盖同一原始位置");
                var binding = bindings.get(record.id());
                if (binding == null) continue;
                var values = Set.copyOf(binding.bindings().values());
                if (record.values().stream()
                        .filter(value -> values.contains(value.id()))
                        .map(SiteModelEvidenceService.Value::selector)
                        .anyMatch(
                                value ->
                                        selectors.stream()
                                                .anyMatch(
                                                        selector -> samePosition(value, selector))))
                    throw new IllegalArgumentException("详情集合和其他区域不能重复绑定同一原始位置");
            }
        }
    }

    private static boolean samePosition(String left, String right) {
        return left.equals(right)
                || left.startsWith(right + " > ")
                || right.startsWith(left + " > ");
    }

    private static void validateRegion(
            RegionUse use,
            Map<String, PageEvidence> pages,
            Map<String, Model> models,
            Map<String, Map<String, Field>> modelFields,
            Set<String> valuesRead,
            Map<String, Set<String>> boundFields,
            Map<String, Set<String>> linkFields) {
        var page = pages.get(use.sourcePath());
        Model model = models.get(use.modelKey());
        if (page == null
                || model == null
                || use.regionId() == null
                || use.mode() == null
                || !REGION_MODES.contains(use.mode()))
            throw new IllegalArgumentException("区域必须关联已观察页面和已声明内容类型");
        var region =
                page.regions().stream()
                        .filter(item -> use.regionId().equals(item.id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("区域不属于该页面"));
        var originals = index(region.records(), SiteModelEvidenceService.SourceRecord::id, "证据记录");
        var records = index(safe(use.records()), RecordUse::recordId, "区域记录");
        if (SiteModelEvidenceService.publicRegion(region)
                && (!"SINGLE".equals(model.kind()) || !"SINGLE".equals(use.mode())))
            throw new IllegalArgumentException("公共信息区域须使用单页内容类型和单记录绑定");
        boolean repeated = "BANNER".equals(region.kind()) || "REPEATED".equals(region.kind());
        boolean reference =
                use.maintenance() != null && "REFERENCE".equals(use.maintenance().mode());
        if (use.maintenance() != null && (!repeated || records.size() < 2))
            throw new IllegalArgumentException("详情区域维护需要可验证的重复记录边界");
        if (repeated
                && (!Set.of("LIST", "QUERY", "SHARED").contains(use.mode())
                        || "SINGLE".equals(model.kind())
                        || !records.keySet().equals(originals.keySet())))
            throw new IllegalArgumentException("重复区域须保留全部原始记录并共用列表字段");
        if (!repeated && Set.of("LIST", "QUERY").contains(use.mode()))
            throw new IllegalArgumentException("单记录证据不能作为重复列表");
        if ("DETAIL".equals(use.mode()) && !"LIST".equals(model.kind()))
            throw new IllegalArgumentException("详情区域须关联其列表内容类型");
        if (records.isEmpty()) throw new IllegalArgumentException("区域需要至少一条可验证的记录");
        var fields = modelFields.get(model.key());
        boolean anyBinding = false;
        for (RecordUse record : records.values()) {
            var original = originals.get(record.recordId());
            if (original == null
                    || record.bindings() == null
                    || record.bindings().size() > fields.size())
                throw new IllegalArgumentException("记录不属于该区域或字段映射无效");
            var values = index(original.values(), SiteModelEvidenceService.Value::id, "证据值");
            var used = new HashSet<String>();
            for (var binding : record.bindings().entrySet()) {
                var field = fields.get(binding.getKey());
                var value = values.get(binding.getValue());
                if (field == null
                        || value == null
                        || valuesRead != null && !valuesRead.contains(value.id())
                        || !used.add(value.id())
                        || !compatible(field.type(), value.typeHint()))
                    throw new IllegalArgumentException("字段绑定必须引用该记录内已完整读取的兼容证据值");
                if ("LINK".equals(value.typeHint()))
                    linkFields
                            .computeIfAbsent(model.key(), key -> new HashSet<>())
                            .add(field.key());
                boundFields.computeIfAbsent(model.key(), key -> new HashSet<>()).add(field.key());
                anyBinding = true;
            }
        }
        if (!anyBinding && !reference) throw new IllegalArgumentException("区域没有可验证的字段绑定");
        // 记录顺序来自 DOM。消费者应直接按证据顺序取值。
        if (!new ArrayList<>(records.keySet())
                .equals(originals.keySet().stream().filter(records::containsKey).toList()))
            throw new IllegalArgumentException("区域记录须按原始页面顺序排列");
    }

    private static ContentFieldDefinition actualField(Menu menu, Field planned) {
        if (menu == null || planned.fieldName() == null) return null;
        return menu.fields().stream()
                .filter(
                        field ->
                                planned.fieldName().equals(field.getFieldName())
                                        && planned.type().equals(field.getFieldType())
                                        && ContentFieldDefinition.enabled(field.getIsOpen())
                                        && !ContentFieldDefinition.enabled(field.getIsReadOnly()))
                .findFirst()
                .orElse(null);
    }

    private static boolean compatible(String type, String hint) {
        if (hint == null) return false;
        return switch (type) {
            case "SingleLine" -> Set.of("TEXT", "LINK").contains(hint);
            case "MultiLine" -> "TEXT".equals(hint);
            case "Editor" -> "HTML_REGION".equals(hint);
            case "Image" -> "IMAGE".equals(hint);
            case "Video" -> "VIDEO".equals(hint);
            case "File" -> "FILE".equals(hint);
            case "DateTime" -> Set.of("DATE", "DATETIME").contains(hint);
            default -> false;
        };
    }

    private static void key(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("模型与字段标识须使用小写字母、数字、下划线或连字符");
    }

    private static void required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(label + "缺失或超出长度限制");
    }

    public record PackageFieldOrigin(String nodeKey, long navigationRevision, Integer rootMenuId,
                                     String sourcePath, Long menuId, long fieldRevision,
                                     String digest, long unitsRevision) {}

    synchronized void registerPackageFieldPlan(Plan plan, String fieldPlanId, PackageFieldOrigin origin) {
        requireCurrent(plan.siteId(), plan.code(), plan.packageVersion(), plan.id(), plan.revision());
        store.register(plan.siteId(), plan.code(), plan.packageVersion(), fieldPlanId,
                new SiteModelPlanStore.Derived(plan.id(), plan.revision(), origin));
    }

    public PackageFieldOrigin packageFieldOrigin(Long siteId, String code, String version, String fieldPlanId) {
        var derived = store.derived(siteId, code, version, fieldPlanId);
        return derived == null ? null : derived.packageOrigin();
    }

    SiteModelNavigationService.Node requirePackageFieldNode(Plan plan, PackageFieldOrigin origin) {
        var saved = store.navigation(plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        if (saved == null) {
            String priorId = store.appliedNavigationPlanId(
                    plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
            if (priorId != null) {
                saved = store.navigation(plan.siteId(), plan.code(), plan.packageVersion(), priorId);
                if (saved != null) {
                    var prior = get(plan.siteId(), plan.code(), plan.packageVersion(), priorId,
                            saved.view().planRevision());
                    if (!"CONFIRMED".equals(prior.status())
                            || !Objects.equals(prior.catalogDigest(), plan.catalogDigest()))
                        throw conflict("原导航回执与当前页面包证据不一致，请核对原整站计划");
                }
            }
        } else if (saved.view().planRevision() != plan.revision()) {
            if (!continuedNavigationMatches(plan, saved.view(), origin.rootMenuId()))
                throw conflict("请先应用本结构计划的当前导航，再核对字段目标");
        }
        if (saved == null || saved.view().status() != SiteModelNavigationService.Status.APPLIED
                || saved.view().revision() != origin.navigationRevision())
            throw conflict("请先应用本结构计划的当前导航，再核对字段目标");
        var nodes = SiteModelNavigationService.withModelLinks(saved.view(),
                store.navigationModelLinks(plan, saved.view(), origin.rootMenuId())).nodes();
        var samePage = nodes.stream().filter(node -> origin.sourcePath().equals(node.sourcePath())).toList();
        var selected = samePage.isEmpty()
                ? confirmedPlaceholderFieldNode(plan, origin, nodes)
                : samePage.getFirst();
        if (selected == null || samePage.size() > 1)
            throw conflict("该原页没有唯一已应用栏目；请在对应模型或详情配对流程核对共享归属");
        if (selected.kind() != SiteModelNavigationService.Kind.SINGLE)
            throw conflict("列表和详情须使用已确认模型区域或列表详情配对流程，不能作为单页初始化");
        if (!Objects.equals(selected.key(), origin.nodeKey()) || selected.menuId() == null || selected.drift())
            throw conflict("选定入口与唯一已应用的原页导航不一致，请重新选择");
        var byKey = new HashMap<String, Integer>();
        var ids = new HashSet<Integer>();
        for (var node : nodes)
            if (node.menuId() == null || !ids.add(node.menuId()) || byKey.put(node.key(), node.menuId()) != null)
                throw conflict("已应用导航栏目回执存在冲突，请先核对");
        var scope = new SiteModelNavigationService.Scope(plan.siteId(), plan.code(), plan.packageVersion(), origin.rootMenuId());
        var menu = menus.getModelById(selected.menuId());
        if (!belongsToPackageSite(scope, menu) || !Objects.equals(menu.getId(), selected.menuId()))
            throw conflict("当前实际栏目的归属已经变化，请核对导航");
        if (Objects.equals(selected.menuId(), origin.rootMenuId())) {
            var target = menus.getModelById(Math.toIntExact(origin.menuId()));
            var model = homePackageFieldModel(plan, origin);
            if (selected.parentKey() != null || !"首页".equals(selected.name())
                    || !Integer.valueOf(0).equals(menu.getPageType())
                    || !belongsToPackageSite(scope, target)
                    || !Objects.equals(target.getParentId(), origin.rootMenuId())
                    || !Objects.equals(target.getId().longValue(), origin.menuId())
                    || !Integer.valueOf(0).equals(target.getPageType())
                    || !Boolean.TRUE.equals(target.getHasManageChannel())
                    || !Objects.equals(target.getIdentifies(), SiteModelStructureService.modelMarker(model.key())))
                throw conflict("首页入口须对应本站根栏目，字段目标须为已审核独立模型的维护栏目");
        } else if (!Objects.equals(selected.menuId().longValue(), origin.menuId())
                || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !SiteModelNavigationService.matchesApplied(scope, selected, menu, byKey)) {
            throw conflict("当前实际栏目的归属、顺序或类型已经变化，请核对导航");
        }
        return selected;
    }

    private static SiteModelNavigationService.Node confirmedPlaceholderFieldNode(
            Plan plan, PackageFieldOrigin origin, List<SiteModelNavigationService.Node> nodes) {
        if (!"CONFIRMED".equals(plan.status()) || origin.menuId() == null
                || origin.rootMenuId() == null || origin.menuId().equals(origin.rootMenuId().longValue()))
            return null;
        var candidates = nodes.stream()
                .filter(node -> node.menuId() != null && origin.menuId().equals(node.menuId().longValue()))
                .toList();
        if (candidates.size() != 1) return null;
        var node = candidates.getFirst();
        if (!Objects.equals(node.key(), origin.nodeKey()) || node.drift()
                || node.kind() != SiteModelNavigationService.Kind.SINGLE
                || node.sourcePath() != null || node.proposedUrl() != null || node.reuseMenuId() != null
                || node.sourceHref() == null || !node.sourceHref().matches("#+")
                || node.observations().isEmpty()
                || node.observations().stream().anyMatch(observation ->
                        !"PLACEHOLDER".equals(observation.linkKind()) || observation.targetPath() != null)
                || nodes.stream().anyMatch(child -> node.key().equals(child.parentKey()))) return null;
        var models = plan.proposal().models().stream()
                .filter(model -> origin.menuId().equals(model.menuId())).toList();
        if (models.size() != 1 || !"SINGLE".equals(models.getFirst().kind())) return null;
        var model = models.getFirst();
        if (node.modelKey() != null && !model.key().equals(node.modelKey())) return null;
        var uses = plan.proposal().regions().stream()
                .filter(use -> use.maintenance() == null && Set.of("SINGLE", "DETAIL").contains(use.mode()))
                .filter(use -> origin.sourcePath().equals(use.sourcePath()) || model.key().equals(use.modelKey()))
                .toList();
        if (uses.size() != 1) return null;
        var region = uses.getFirst();
        return model.key().equals(region.modelKey()) && origin.sourcePath().equals(region.sourcePath())
                && "SINGLE".equals(region.mode()) && region.records().size() == 1
                && !region.records().getFirst().bindings().isEmpty()
                && !SiteModelEvidenceService.publicRegion(region.sourcePath(), region.regionId())
                ? node : null;
    }

    static Model homePackageFieldModel(Plan plan, PackageFieldOrigin origin) {
        if (Objects.equals(origin.menuId(), origin.rootMenuId().longValue()))
            throw conflict("首页根栏目仅用作入口，请选择独立首页模型的维护栏目");
        var candidates = plan.proposal().models().stream()
                .filter(model -> "SINGLE".equals(model.kind()) && Objects.equals(model.menuId(), origin.menuId()))
                .toList();
        if (candidates.size() != 1)
            throw conflict("首页需要唯一已审核的独立内容模型");
        var model = candidates.getFirst();
        var uses = plan.proposal().regions().stream()
                .filter(use -> Objects.equals(use.modelKey(), model.key()))
                .filter(use -> use.maintenance() == null)
                .filter(use -> !SiteModelEvidenceService.publicRegion(use.sourcePath(), use.regionId()))
                .toList();
        if (uses.size() != 1 || !"SINGLE".equals(uses.getFirst().mode())
                || !Objects.equals(uses.getFirst().sourcePath(), origin.sourcePath())
                || uses.getFirst().records().size() != 1)
            throw conflict("独立首页模型须有唯一同源单记录区域，请核对已审核结构");
        return model;
    }

    private static boolean belongsToPackageSite(SiteModelNavigationService.Scope scope, OneshotEntity menu) {
        return menu != null && !Boolean.TRUE.equals(menu.getIsDeleted())
                && Objects.equals(menu.getSiteProjectId(), scope.siteId())
                && Objects.equals(menu.getSiteRootId(), scope.rootMenuId());
    }

    public synchronized void registerDerivedPlan(
            Long siteId,
            String code,
            String version,
            String sitePlanId,
            long revision,
            String fieldPlanId) {
        requireCurrent(siteId, code, version, sitePlanId, revision);
        store.register(
                siteId,
                code,
                version,
                fieldPlanId,
                new SiteModelPlanStore.Derived(sitePlanId, revision));
    }

    public void assertDerivedCurrent(Long siteId, String code, String version, String fieldPlanId) {
        var derived = store.derived(siteId, code, version, fieldPlanId);
        if (derived != null)
            requireCurrent(siteId, code, version, derived.sitePlanId(), derived.revision());
    }

    public Plan confirmedDerivedPlan(Long siteId, String code, String version, String fieldPlanId) {
        var derived = store.derived(siteId, code, version, fieldPlanId);
        return derived == null
                ? null
                : requireCurrent(siteId, code, version, derived.sitePlanId(), derived.revision());
    }

    public void assertDerivedSeedTarget(
            Long siteId,
            String code,
            String version,
            String fieldPlanId,
            String sourcePath,
            Long menuId) {
        var plan = confirmedDerivedPlan(siteId, code, version, fieldPlanId);
        if (plan == null) return;
        var origin = packageFieldOrigin(siteId, code, version, fieldPlanId);
        if (origin != null) {
            if (!Objects.equals(origin.sourcePath(), sourcePath) || !Objects.equals(origin.menuId(), menuId))
                throw conflict("初值目标与已审核整包页面不一致");
            requirePackageFieldNode(plan, origin);
            return;
        }
        var modelKeys =
                plan.proposal().regions().stream()
                        .filter(use -> use.maintenance() == null)
                        .filter(use -> sourcePath.equals(use.sourcePath()))
                        .filter(use -> "SINGLE".equals(use.mode()) || "DETAIL".equals(use.mode()))
                        .map(RegionUse::modelKey)
                        .distinct()
                        .toList();
        if (menuId == null
                || plan.proposal().models().stream()
                        .noneMatch(
                                model ->
                                        modelKeys.contains(model.key())
                                                && Objects.equals(menuId, model.menuId())))
            throw conflict("仅已审核来源的代表栏目可写入原文初值；其他栏目请选择现有详情草稿并保留当前值");
    }

    public <T> T withDerivedPlan(
            Long siteId, String code, String version, String fieldPlanId, Supplier<T> action) {
        var derived = store.derived(siteId, code, version, fieldPlanId);
        if (derived == null) return action.get();
        return withConfirmedPlan(
                siteId, code, version, derived.sitePlanId(), derived.revision(), action);
    }

    synchronized <T> T withConfirmedPlan(
            Long siteId,
            String code,
            String version,
            String planId,
            long revision,
            Supplier<T> action) {
        requireCurrent(siteId, code, version, planId, revision);
        // 调用者在事务代理外进入，在事务提交或回滚完成后释放锁。
        return action.get();
    }

    private Plan requireCurrent(
            Long siteId, String code, String version, String id, long revision) {
        Plan plan = get(siteId, code, version, id);
        if (!"CONFIRMED".equals(plan.status()) || revision != plan.revision())
            throw conflict("整站结构需要使用当前已确认的计划版本");
        return plan;
    }
}

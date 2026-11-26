package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelNavigationEvidence.Entry;
import com.xinhongru.cms.template.SiteModelPlanService.Model;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SiteModelNavigationService {
    public enum Kind {
        DIRECTORY,
        SINGLE,
        LIST
    }

    public enum ContentStatus {
        NOT_REQUIRED,
        HAS_CONTENT,
        PENDING_MATCH,
        MISSING_SOURCE
    }

    public enum Status {
        DRAFT,
        CONFIRMED,
        APPLYING,
        APPLIED
    }

    public record Scope(Long siteId, String code, String packageVersion, Integer rootMenuId) {}

    public record Navigation(
            long revision,
            long planRevision,
            Status status,
            List<Node> nodes,
            List<Entry> supplementalEvidence,
            String originPlanId,
            Long originPlanRevision) {
        public Navigation {
            supplementalEvidence =
                    supplementalEvidence == null ? List.of() : List.copyOf(supplementalEvidence);
        }

        public Navigation(
                long revision,
                long planRevision,
                Status status,
                List<Node> nodes,
                List<Entry> supplementalEvidence) {
            this(revision, planRevision, status, nodes, supplementalEvidence, null, null);
        }

        public Navigation(long revision, long planRevision, Status status, List<Node> nodes) {
            this(revision, planRevision, status, nodes, List.of());
        }
    }

    public record AppendRequest(
            String packageVersion,
            long planRevision,
            long navigationRevision,
            String nodeKey,
            Kind kind,
            String modelKey,
            Integer reuseMenuId,
            boolean confirmReuse,
            String previewDigest,
            boolean confirmed) {}

    public record AppendPreview(
            String originPlanId,
            long originPlanRevision,
            long navigationRevision,
            Node node,
            List<Node> nodes,
            Node parent,
            String previewDigest) {}

    public record AppendResult(String planId, Navigation navigation) {}

    public record KindCorrectionRequest(
            String packageVersion, long planRevision, long navigationRevision,
            String nodeKey, String previewToken, Boolean confirm) {}

    public record KindCorrectionPreview(
            String nodeKey, Integer menuId, String label, String sourcePath, Kind fromKind, Kind toKind,
            boolean eligible, String message, String previewToken, ListDetailRoleEvidence.Match listEvidence) {}

    private record PreparedKindCorrection(
            Plan plan, String sourceId, Navigation source, Node target, OneshotEntity menu,
            KindCorrectionPreview preview) {}

    public record ContinuationRequest(
            String packageVersion,
            long planRevision,
            long navigationRevision,
            String previewDigest,
            Boolean confirmed) {}

    public record ModelLink(String nodeKey, Integer menuId, String oldModelKey, String newModelKey) {}

    public record ModelLinkIssue(String nodeKey, Integer menuId, String candidateModelKey, String message) {}

    public record ContinuationPreview(
            long sourcePlanRevision,
            long planRevision,
            long navigationRevision,
            int nodeCount,
            String previewDigest,
            List<ModelLink> modelLinks,
            List<ModelLinkIssue> modelLinkIssues) {
        public ContinuationPreview {
            modelLinks = modelLinks == null ? List.of() : List.copyOf(modelLinks);
            modelLinkIssues = modelLinkIssues == null ? List.of() : List.copyOf(modelLinkIssues);
        }
    }

    private record ModelLinkReview(List<ModelLink> links, List<ModelLinkIssue> issues) {}

    private record PreparedContinuation(
            Plan plan, Navigation source, Navigation navigation, ContinuationPreview preview) {}

    private record PreparedAppend(
            Plan plan,
            String catalogDigest,
            Navigation previous,
            AppendPreview preview,
            Map<String, Previous> before) {}

    record Previous(Integer parentId, Integer sort) {}

    record SavedNavigation(
            Navigation view, Map<String, Previous> before,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String correctedNodeKey) {
        SavedNavigation(Navigation view, Map<String, Previous> before) {
            this(view, before, null);
        }
    }

    public record Node(
            String key,
            String parentKey,
            String name,
            int order,
            String sourcePath,
            String evidencePath,
            String evidenceSelector,
            String sourceHref,
            Kind kind,
            String modelKey,
            Integer reuseMenuId,
            Integer menuId,
            String proposedUrl,
            ContentStatus contentStatus,
            String cmsName,
            boolean drift,
            boolean confirmReuse,
            List<SiteModelNavigationEvidence.Observation> observations,
            Boolean independentLanding) {
        public Node {
            observations = observations == null ? List.of() : List.copyOf(observations);
            independentLanding = Boolean.TRUE.equals(independentLanding);
        }

        public Node(
                String key,
                String parentKey,
                String name,
                int order,
                String sourcePath,
                String evidencePath,
                String evidenceSelector,
                String sourceHref,
                Kind kind,
                String modelKey,
                Integer reuseMenuId,
                Integer menuId,
                String proposedUrl,
                ContentStatus contentStatus,
                String cmsName,
                boolean drift,
                boolean confirmReuse) {
            this(
                    key,
                    parentKey,
                    name,
                    order,
                    sourcePath,
                    evidencePath,
                    evidenceSelector,
                    sourceHref,
                    kind,
                    modelKey,
                    reuseMenuId,
                    menuId,
                    proposedUrl,
                    contentStatus,
                    cmsName,
                    drift,
                    confirmReuse,
                    List.of(),
                    false);
        }

        public Node(
                String key,
                String parentKey,
                String name,
                int order,
                String sourcePath,
                String evidencePath,
                String evidenceSelector,
                String sourceHref,
                Kind kind,
                String modelKey,
                Integer reuseMenuId,
                Integer menuId,
                String proposedUrl,
                ContentStatus contentStatus) {
            this(
                    key,
                    parentKey,
                    name,
                    order,
                    sourcePath,
                    evidencePath,
                    evidenceSelector,
                    sourceHref,
                    kind,
                    modelKey,
                    reuseMenuId,
                    menuId,
                    proposedUrl,
                    contentStatus,
                    null,
                    false,
                    false);
        }

        boolean matches(Entry entry) {
            return key.equals(entry.key())
                    && Objects.equals(parentKey, entry.parentKey())
                    && name.equals(entry.name())
                    && order == entry.order()
                    && Objects.equals(sourcePath, entry.sourcePath())
                    && evidencePath.equals(entry.evidencePath())
                    && evidenceSelector.equals(entry.evidenceSelector())
                    && sourceHref.equals(entry.sourceHref());
        }
    }

    private final SiteModelPlanService plans;
    private final SiteModelPlanStore store;
    private final SiteModelNavigationEvidence evidence;
    private final OneShotMapper menus;
    private final ContentMapper contents;
    private final SiteProjectMapper projects;
    private final OneShotService menuService;
    private final TransactionTemplate transaction;

    public SiteModelNavigationService(
            SiteModelPlanService plans,
            SiteModelNavigationEvidence evidence,
            OneShotMapper menus,
            ContentMapper contents,
            SiteProjectMapper projects,
            OneShotService menuService,
            PlatformTransactionManager manager,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model")
                    String root) {
        this.plans = plans;
        this.store = new SiteModelPlanStore(root, new ObjectMapper());
        this.evidence = evidence;
        this.menus = menus;
        this.contents = contents;
        this.projects = projects;
        this.menuService = menuService;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Navigation get(Scope scope, String id, Runnable guard) {
        guard.run();
        var sources = evidence.readSources(scope.code(), scope.packageVersion());
        var entries = evidence.read(sources);
        synchronized (plans) {
            guard.run();
            Plan plan = current(scope, id);
            var stored = load(scope, id);
            var saved = stored == null ? null : stored.view();
            if (saved != null) {
                if ((saved.status() == Status.CONFIRMED || saved.status() == Status.DRAFT)
                        && (saved.planRevision() != plan.revision() || saved.nodes().size() != entries.size()))
                    return new Navigation(saved.revision(), plan.revision(), Status.DRAFT,
                            rebaseReviewedNodes(scope, plan, entries, saved));
                var original = originalPlan(scope, plan, saved);
                var receipt = store.navigationContinuation(plan);
                if (matchesContinuation(plan, saved, scope.rootMenuId(), receipt)) {
                    try {
                        plans.confirmedForNavigation(scope.siteId(), id, plan.revision(), sources);
                        var actual = refresh(scope, plan, saved, entries);
                        requireContinuationNodes(scope, saved, actual, false);
                        var links = store.navigationModelLinks(plan, saved, scope.rootMenuId());
                        requireApprovedModelLinks(plan, actual, stored.correctedNodeKey(), entries, links);
                        actual = withModelLinks(actual, links);
                        return continued(plan, legacyCorrectedModel(plan, actual, stored.correctedNodeKey(), entries, receipt));
                    } catch (ResponseStatusException drift) {
                        if (drift.getStatusCode().value() != 409) throw drift;
                        // 已发布入口仍可按原回执独立核对，现行工作台会看到原结构修订。
                    }
                }
                return refresh(scope, original,
                        withModelLinks(saved, store.navigationModelLinks(plan, saved, scope.rootMenuId())), entries);
            }
            if (saved == null) {
                var inherited = inherited(scope, plan, entries);
                if (inherited != null) return inherited;
            }
            return new Navigation(
                    saved == null ? 0 : saved.revision(),
                    plan.revision(),
                    Status.DRAFT,
                    propose(scope, plan, entries));
        }
    }

    Navigation savedApplicationNavigation(Scope scope, String id) {
        var saved = load(scope, id);
        return saved == null ? null : saved.view();
    }

    /** 有来源计划的导航既可能是已保存的修正，也可能只是借用原回执的只读视图。 */
    boolean hasOwnAppliedRevision(Scope scope, String planId, Navigation nav, long planRevision) {
        if (nav == null || nav.status() != Status.APPLIED) return false;
        if (nav.originPlanId() == null) return true;
        if (nav.planRevision() != planRevision) return false;
        var saved = savedApplicationNavigation(scope, planId);
        return saved != null
                && saved.status() == Status.APPLIED
                && saved.revision() == nav.revision()
                && Objects.equals(saved.originPlanId(), nav.originPlanId())
                && Objects.equals(saved.originPlanRevision(), nav.originPlanRevision());
    }

    Set<String> applicationReuseKeys(Scope scope, Plan plan, List<Node> nodes) {
        var entries = evidence.read(scope.code(), scope.packageVersion());
        var models = plan.proposal().models().stream().collect(Collectors.toMap(Model::key, model -> model));
        var keys = new HashSet<String>();
        for (var node : nodes) {
            var model = models.get(node.modelKey());
            if (model != null && entries.stream().filter(node::matches)
                    .anyMatch(entry -> reuseEvidence(plan, entry, entries, model))) keys.add(node.key());
        }
        return keys;
    }

    static Node withApplicationReuse(Node node, Integer menuId) {
        return new Node(node.key(), node.parentKey(), node.name(), node.order(), node.sourcePath(),
                node.evidencePath(), node.evidenceSelector(), node.sourceHref(), node.kind(),
                node.modelKey(), menuId, node.menuId(), node.proposedUrl(), node.contentStatus(),
                node.cmsName(), node.drift(), node.confirmReuse() || menuId != null,
                node.observations(), node.independentLanding());
    }

    static boolean canReuseApplicationMenu(Scope scope, Node node, OneshotEntity menu) {
        try {
            requireReusable(scope, node, menu);
            return true;
        } catch (ResponseStatusException conflict) {
            return false;
        }
    }

    /** 列表确认持有数据库栏目锁，只读取已原子保存的导航回执。 */
    Navigation appliedSnapshot(Scope scope, String id) {
        var saved = originalAppliedSnapshot(scope, id);
        return withModelLinks(saved, store.navigationModelLinks(current(scope, id), saved, scope.rootMenuId()));
    }

    private Navigation originalAppliedSnapshot(Scope scope, String id) {
        requireProject(scope, projects.selectById(scope.siteId()));
        var stored = load(scope, id);
        if (stored == null || stored.view().status() != Status.APPLIED)
            throw conflict("请选择原已应用导航回执");
        var saved = stored.view();
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), id, saved.planRevision());
        if (!"CONFIRMED".equals(plan.status()) || saved.nodes().isEmpty()
                || saved.nodes().stream().anyMatch(node -> node.menuId() == null)
                || saved.nodes().stream().map(Node::menuId).distinct().count() != saved.nodes().size())
            throw conflict("原导航回执或确认版本已变化");
        return saved;
    }

    public List<Integer> modelMenuIds(
            Scope scope, String planId, long revision, String modelKey, Runnable guard) {
        synchronized (plans) {
            guard.run();
            Plan plan =
                    plans.confirmed(
                            scope.siteId(), scope.code(), scope.packageVersion(), planId, revision);
            var selected = model(plan, modelKey);
            if (selected == null) throw new IllegalArgumentException("请选择审核计划中的内容类型");
            var result = modelMenuIds(scope, plan, selected);
            guard.run();
            return result;
        }
    }

    List<Integer> modelMenuIds(Scope scope, Plan plan, Model selected) {
        requireProject(scope, projects.selectById(scope.siteId()));
        var result = new LinkedHashSet<Integer>();
        Integer canonical = selected.menuId() == null ? null : Math.toIntExact(selected.menuId());
        var kind = contentKind(selected);
        if (canonical != null) {
            if (!isModelMenu(scope, kind, canonical)) throw conflict("计划关联的栏目归属、类型或管理状态已变化");
            result.add(canonical);
        }
        var saved = load(scope, plan.id());
        Navigation applied;
        if (saved == null) {
            var originId = origin(scope, plan.id());
            if (originId == null) return List.copyOf(result);
            var entries = evidence.read(scope.code(), scope.packageVersion());
            applied = inherited(scope, plan, entries);
            var source = load(scope, originId);
            var links = store.navigationModelLinks(current(scope, originId), source.view(), scope.rootMenuId());
            requireApprovedModelLinks(plan, source.view(), source.correctedNodeKey(), entries,
                    links.stream().filter(link -> selected.key().equals(link.newModelKey())).toList());
        } else if (saved.view().status() == Status.APPLIED) {
            Plan original = originalPlan(scope, plan, saved.view());
            if (original.revision() > plan.revision()
                    || !"CONFIRMED".equals(original.status())
                    || !Objects.equals(original.catalogDigest(), plan.catalogDigest()))
                throw conflict("原导航回执与当前审核修订不一致");
            var entries = evidence.read(scope.code(), scope.packageVersion());
            var links = store.navigationModelLinks(plan, saved.view(), scope.rootMenuId());
            var actual = refresh(scope, original, saved.view(), entries);
            requireApprovedModelLinks(plan, actual, saved.correctedNodeKey(), entries,
                    links.stream().filter(link -> selected.key().equals(link.newModelKey())).toList());
            applied = withModelLinks(actual, links);
            var receipt = store.navigationContinuation(plan);
            if (matchesContinuation(plan, saved.view(), scope.rootMenuId(), receipt))
                applied = legacyCorrectedModel(plan, applied, saved.correctedNodeKey(), entries, receipt);
        } else {
            applied = null;
        }
        if (applied == null) return List.copyOf(result);
        var appliedByKey =
                applied.nodes().stream().collect(Collectors.toMap(Node::key, node -> node));
        if (applied.nodes().stream().map(Node::menuId).distinct().count() != applied.nodes().size())
            throw conflict("已应用导航回执中的实际栏目重复，请核对导航");
        for (Node node : applied.nodes()) {
            if (node.menuId() == null) continue;
            boolean drift = hasAppliedDrift(scope, node, appliedByKey);
            if (node.menuId().equals(canonical)
                    && (drift || node.kind() != kind || !selected.key().equals(node.modelKey())))
                throw conflict("计划关联的栏目与已应用导航回执不一致");
            if (!selected.key().equals(node.modelKey())
                    || node.kind() != kind
                    || drift
                    || !isModelMenu(scope, kind, node.menuId())) continue;
            if (plan.proposal().models().stream()
                    .anyMatch(
                            other ->
                                    !selected.key().equals(other.key())
                                            && Long.valueOf(node.menuId()).equals(other.menuId())))
                throw conflict("已应用导航栏目与当前审核模型冲突");
            result.add(node.menuId());
        }
        return List.copyOf(result);
    }

    private boolean hasAppliedDrift(Scope scope, Node node, Map<String, Node> byKey) {
        var seen = new HashSet<String>();
        Node current = node;
        while (current != null && seen.add(current.key())) {
            if (current.drift()
                    || current.menuId() == null
                    || scope.rootMenuId().equals(current.menuId()) && !validRoot(scope, current))
                return true;
            if (current.parentKey() == null) return false;
            current = byKey.get(current.parentKey());
        }
        return true;
    }

    private boolean isModelMenu(Scope scope, Kind kind, Integer menuId) {
        var actual = menus.getModelById(menuId);
        return actual != null
                && scope.siteId().equals(actual.getSiteProjectId())
                && scope.rootMenuId().equals(actual.getSiteRootId())
                && !Boolean.TRUE.equals(actual.getIsDeleted())
                && Integer.valueOf(pageType(kind)).equals(actual.getPageType())
                && Boolean.TRUE.equals(actual.getHasManageChannel())
                && !Boolean.TRUE.equals(actual.getIsSubMenu())
                && (!menuId.equals(scope.rootMenuId()) || kind == Kind.SINGLE);
    }

    public AppendPreview previewAppend(Scope scope, String id, AppendRequest body, Runnable guard) {
        synchronized (plans) {
            guard.run();
            store.requireNoOtherPendingNavigationAppend(scope.siteId(), scope.code(), scope.packageVersion(), null);
            return prepareAppend(scope, id, body).preview();
        }
    }

    public KindCorrectionPreview previewKindCorrection(
            Scope scope, String id, KindCorrectionRequest body, Runnable guard) {
        synchronized (plans) {
            guard.run();
            var prepared = prepareKindCorrection(scope, id, body, false);
            String token = prepared.preview().previewToken();
            store.requireNoOtherPendingNavigationAppend(scope.siteId(), scope.code(), scope.packageVersion(),
                    token == null ? null : correctionPlanId(token));
            guard.run();
            return prepared.preview();
        }
    }

    public AppendResult applyKindCorrection(
            Scope scope, String id, KindCorrectionRequest body, HttpServletRequest request, Runnable guard) {
        if (!Boolean.TRUE.equals(body.confirm()) || body.previewToken() == null || !body.previewToken().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("请先预览并确认栏目类型修正");
        String nextId = correctionPlanId(body.previewToken());
        synchronized (plans) {
            guard.run();
            transaction.executeWithoutResult(status -> {
                lockProject(scope);
                guard.run();
                store.requireNoOtherPendingNavigationAppend(scope.siteId(), scope.code(), scope.packageVersion(), nextId);
                var stored = load(scope, nextId);
                validateCorrectionRequest(scope, id, body, stored, nextId);
                if (stored != null && stored.view().status() == Status.APPLIED) return;
                var prepared = prepareKindCorrection(scope, id, body, true);
                if (!prepared.preview().eligible())
                    throw conflict(prepared.preview().message());
                if (!body.previewToken().equals(prepared.preview().previewToken()))
                    throw conflict("栏目类型修正的预览已变化，请重新预览");
                var draft = store.latest(scope.siteId(), scope.code(), scope.packageVersion(), nextId);
                if (draft == null) {
                    draft = plans.navigationAppendPlan(prepared.plan(), nextId);
                    store.checkSave(draft);
                    store.save(draft);
                }
                var source = prepared.source();
                var target = prepared.target();
                var corrected = new Navigation(source.revision() + 1, draft.revision(), Status.APPLYING,
                        source.nodes().stream().map(node -> node.key().equals(target.key())
                                ? correctedKind(node) : node).toList(),
                        source.supplementalEvidence(), prepared.sourceId(), source.planRevision());
                guard.run();
                if (stored == null) {
                    store.registerModelDraft(prepared.plan(), draft, null, null, null);
                    saveKindCorrection(scope, nextId, corrected, target.key());
                } else if (!corrected.equals(stored.view())) {
                    throw conflict("栏目类型修正回执已变化，请核对");
                }
                if (Objects.equals(prepared.menu().getPageType(), pageType(Kind.SINGLE))) {
                    save(new EditOneshotDto().setId(target.menuId()).setPageType(pageType(Kind.LIST))
                            .setUrl("/admin/model_manage/{id}"), request);
                }
            });
            return transaction.execute(status -> {
                lockProject(scope);
                guard.run();
                var stored = load(scope, nextId);
                validateCorrectionRequest(scope, id, body, stored, nextId);
                if (stored == null) throw conflict("栏目类型修正回执已变化，请重试");
                var saved = stored.view();
                if (saved.status() == Status.APPLYING) {
                    var prepared = prepareKindCorrection(scope, id, body, true).preview();
                    if (!body.previewToken().equals(prepared.previewToken()))
                        throw conflict("栏目类型修正的来源或实际栏目已变化，请核对");
                }
                var draft = originalPlan(scope, current(scope, nextId), saved);
                var actual = refresh(scope, draft, saved, evidence.read(scope.code(), scope.packageVersion()));
                requireContinuationNodes(scope, saved, actual, true);
                if (saved.status() == Status.APPLIED) return new AppendResult(nextId, actual);
                var applied = new Navigation(saved.revision(), saved.planRevision(), Status.APPLIED, actual.nodes(),
                        actual.supplementalEvidence(), saved.originPlanId(), saved.originPlanRevision());
                guard.run();
                saveKindCorrection(scope, nextId, applied, stored.correctedNodeKey());
                return new AppendResult(nextId, applied);
            });
        }
    }

    private void validateCorrectionRequest(
            Scope scope, String id, KindCorrectionRequest body, SavedNavigation stored, String nextId) {
        if (stored == null) return;
        var link = store.modelDraftOrigin(scope.siteId(), scope.code(), scope.packageVersion(), nextId);
        if (link == null || !id.equals(link.sitePlanId()) || body.planRevision() != link.revision()
                || !Objects.equals(body.nodeKey(), stored.correctedNodeKey())
                || body.navigationRevision() != stored.view().revision() - 1
                || stored.view().status() != Status.APPLIED && stored.view().status() != Status.APPLYING)
            throw conflict("请求与栏目类型修正回执不一致，请重新预览");
    }

    private void saveKindCorrection(Scope scope, String id, Navigation navigation, String nodeKey) {
        store.saveNavigation(scope.siteId(), scope.code(), scope.packageVersion(), id,
                new SavedNavigation(navigation, Map.of(), nodeKey));
    }

    private PreparedKindCorrection prepareKindCorrection(
            Scope scope, String id, KindCorrectionRequest body, boolean locked) {
        var plan = plans.continuationBase(scope.siteId(), scope.code(), scope.packageVersion(), id, body.planRevision());
        String currentDigest = plans.validateNavigationAppendSource(plan);
        String sourceId = load(scope, id) == null ? origin(scope, id) : id;
        if (sourceId == null || !sourceId.equals(store.latestAppliedNavigationPlanId(
                scope.siteId(), scope.code(), scope.packageVersion())))
            throw conflict("请打开最新的已应用整站导航后修正");
        var old = appliedSnapshot(scope, sourceId);
        if (body.navigationRevision() != old.revision()) throw conflict("导航修订已变化，请重新读取");
        var originPlan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), sourceId, old.planRevision());
        if (!originPlan.catalogDigest().equals(plan.catalogDigest()))
            throw conflict("原导航回执与当前页面包不一致");
        var entries = evidence.read(scope.code(), scope.packageVersion());
        var current = refresh(scope, originPlan, old, entries);
        var selected = current.nodes().stream().filter(node -> node.key().equals(body.nodeKey())).findFirst()
                .orElseThrow(() -> conflict("请选择已应用导航中的栏目"));
        // 始终用原回执构造新回执，确保重试不会把修正过程中的临时查询结果固化。
        var target = old.nodes().stream().filter(node -> node.key().equals(selected.key())).findFirst().orElseThrow();
        var entry = entries.stream().filter(row -> row.key().equals(target.key())).findFirst().orElseThrow();
        var proof = entry.listDetailEvidence();
        String token = proof == null ? null : kindCorrectionToken(scope, plan, currentDigest, sourceId, old, target, proof);
        var pending = token == null ? null : load(scope, correctionPlanId(token));
        var actual = locked ? lock(scope, target.menuId()) : menus.selectPublicBindingMenu(scope.siteId(), target.menuId().longValue());
        boolean alreadyChanged = pending != null && pending.view().status() == Status.APPLYING
                && actual != null && Objects.equals(actual.getPageType(), pageType(Kind.LIST));
        var ids = old.nodes().stream().collect(Collectors.toMap(Node::key, Node::menuId));
        String message = null;
        if (target.kind() != Kind.SINGLE || Objects.equals(scope.rootMenuId(), target.menuId())
                || target.modelKey() != null || target.reuseMenuId() != null
                || entries.stream().anyMatch(row -> target.key().equals(row.parentKey())))
            message = "仅可修正未关联内容模型的导航单页叶子栏目";
        else if (proof == null)
            message = "原页没有可归属到该栏目的重复记录与本地详情正文证据";
        else if (actual == null || !Objects.equals(actual.getSiteRootId(), scope.rootMenuId())
                || !matchesApplied(scope, alreadyChanged ? correctedKind(target) : target, actual, ids)
                || !Objects.equals(actual.getClassName(), target.name())
                || current.nodes().stream().anyMatch(node -> !node.key().equals(target.key()) && node.drift()))
            message = "原栏目的归属、名称、父级、顺序或类型已变化，请核对";
        else if (!Objects.equals(actual.getUrl(), alreadyChanged
                ? "/admin/model_manage/{id}" : "/admin/menu_manage/{id}"))
            message = "原栏目的管理入口已变化，请核对";
        else
            message = kindCorrectionBlocker(actual, target, locked);
        if (message == null) {
            var structural = alreadyChanged
                    ? new Navigation(current.revision(), current.planRevision(), current.status(),
                            current.nodes().stream().map(node -> node.key().equals(target.key())
                                    ? correctedKind(node) : node).toList(), current.supplementalEvidence(),
                            current.originPlanId(), current.originPlanRevision())
                    : current;
            requireContinuationNodes(scope, old, structural, locked);
        }
        return new PreparedKindCorrection(plan, sourceId, old, target, actual, new KindCorrectionPreview(
                target.key(), target.menuId(), target.name(), target.sourcePath(), target.kind(), Kind.LIST,
                message == null, message == null ? "可将原栏目修正为可维护记录的列表栏目" : message,
                message == null ? token : null, proof));
    }

    private String kindCorrectionBlocker(OneshotEntity menu, Node target, boolean locked) {
        if (Boolean.TRUE.equals(menu.getHasFrontChannel()) || Boolean.TRUE.equals(menu.getPublished())
                || Boolean.TRUE.equals(menu.getWebsitePublic()) || Integer.valueOf(3).equals(menu.getStatus())
                || hasPublicRoute(menu)
                || !blank(menu.getListPageTemplate()) || !blank(menu.getDetailPageTemplate()))
            return "栏目已公开或已有路由、模板，无法修正类型";
        if (!Boolean.TRUE.equals(menu.getHasManageChannel()) || Boolean.TRUE.equals(menu.getIsSubMenu())
                || Boolean.TRUE.equals(menu.getIsSystem()) || Boolean.TRUE.equals(menu.getIsModuleMenu())
                || Boolean.TRUE.equals(menu.getIsForm()) || Boolean.TRUE.equals(menu.getIsLoop()))
            return "栏目已有其他管理职责，无法修正类型";
        boolean hasChild = locked ? menus.lockAnyChildForNavigationCorrection(target.menuId()) != null
                : !Objects.equals(0, menus.getChildCount(new IdDto(target.menuId())));
        if (hasChild)
            return "CMS 栏目已有子栏目，无法修正类型";
        boolean hasRecord = locked ? contents.lockAnyRecordForNavigationCorrection(target.menuId()) != null
                : contents.hasAnyRecordForNavigationCorrection(target.menuId());
        if (hasRecord || hasStoredSingleValues(menu))
            return "栏目已有内容，无法修正类型";
        return null;
    }

    private static boolean hasStoredSingleValues(OneshotEntity menu) {
        if (!blank(menu.getExtensionFields()) && !menu.getExtensionFields().strip().equals("{}")) return true;
        for (var field : OneshotEntity.class.getDeclaredFields()) {
            if (!field.getName().matches("(singleLine|note|photo|file|content|media)[0-9]+")) continue;
            try {
                field.setAccessible(true);
                if (!blank((String) field.get(menu))) return true;
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("无法核对单页内容", error);
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String kindCorrectionToken(
            Scope scope, Plan plan, String digest, String sourceId, Navigation old,
            Node target, ListDetailRoleEvidence.Match proof) {
        try {
            var value = new ObjectMapper().writeValueAsBytes(
                    List.of(scope, plan.id(), plan.revision(), digest, sourceId, old, target.key(), proof));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法校验栏目类型修正预览", error);
        }
    }

    private static String correctionPlanId(String token) {
        return UUID.nameUUIDFromBytes(("navigation-kind-correction:" + token)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Node correctedKind(Node node) {
        return new Node(node.key(), node.parentKey(), node.name(), node.order(), node.sourcePath(),
                node.evidencePath(), node.evidenceSelector(), node.sourceHref(), Kind.LIST, node.modelKey(),
                node.reuseMenuId(), node.menuId(), node.proposedUrl(), ContentStatus.PENDING_MATCH,
                node.cmsName(), false, node.confirmReuse(), node.observations(), node.independentLanding());
    }

    ContinuationPreview previewContinuation(Scope scope, String id, ContinuationRequest body, Runnable guard) {
        guard.run();
        var prepared = prepareContinuation(scope, id, body, false);
        guard.run();
        return prepared.preview();
    }

    Navigation applyContinuation(Scope scope, String id, ContinuationRequest body, Runnable guard) {
        if (!Boolean.TRUE.equals(body.confirmed()) || body.previewDigest() == null || !body.previewDigest().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("请先预览并确认导航接续");
        guard.run();
        return transaction.execute(status -> {
            lockProject(scope);
            guard.run();
            var prepared = prepareContinuation(scope, id, body, true);
            if (!body.previewDigest().equals(prepared.preview().previewDigest()))
                throw conflict("导航接续预览已变化，请重新预览");
            var stored = store.navigationContinuation(prepared.plan());
            if (prepared.preview().modelLinks().isEmpty() && !prepared.preview().modelLinkIssues().isEmpty()
                    && (prepared.source().planRevision() == prepared.plan().revision()
                            || stored != null && !stored.equals(prepared.preview())))
                throw conflict("当前导航已接续，待补挂模型缺少已审核来源归属");
            if (stored != null && (!matchesContinuation(prepared.plan(), prepared.source(), scope.rootMenuId(), stored)
                    || !stored.previewDigest().equals(body.previewDigest())
                            && (!stored.modelLinks().isEmpty() || prepared.preview().modelLinks().isEmpty())))
                throw conflict("导航接续回执已变化，请重新读取");
            var result = continued(prepared.plan(), prepared.navigation());
            guard.run();
            if (stored == null || !stored.previewDigest().equals(body.previewDigest()))
                store.saveNavigationContinuation(prepared.plan(), prepared.preview(), stored != null);
            return result;
        });
    }

    private PreparedContinuation prepareContinuation(
            Scope scope, String id, ContinuationRequest body, boolean locked) {
        if (!Objects.equals(scope.packageVersion(), body.packageVersion()))
            throw conflict("原页面包版本已变化，请重新预览");
        store.requireNoOtherPendingNavigationAppend(scope.siteId(), scope.code(), scope.packageVersion(), null);
        var plan = plans.confirmed(scope.siteId(), scope.code(), scope.packageVersion(), id, body.planRevision());
        if (!id.equals(store.latestAppliedNavigationPlanId(scope.siteId(), scope.code(), scope.packageVersion())))
            throw conflict("请接续当前站点最新的已应用导航");
        var saved = originalAppliedSnapshot(scope, id);
        if (saved.revision() != body.navigationRevision() || saved.planRevision() > plan.revision())
            throw conflict("请选择当前结构与待接续的已应用导航修订");
        var source = originalPlan(scope, plan, saved);
        if (!Objects.equals(source.catalogDigest(), plan.catalogDigest()))
            throw conflict("原导航回执与当前页面包证据不一致");
        for (String key : saved.nodes().stream().map(Node::modelKey).filter(Objects::nonNull).distinct().toList()) {
            var original = model(source, key);
            var current = plan.proposal().models().stream()
                    .filter(candidate -> key.equals(candidate.key())).findFirst().orElse(null);
            if (current == null || !original.kind().equals(current.kind())
                    || !Objects.equals(original.menuId(), current.menuId()))
                throw conflict("导航关联的内容模型类型或栏目已变化，请核对");
        }
        var priorLinks = store.navigationModelLinks(plan, saved, scope.rootMenuId(), plan.revision() - 1);
        var entries = evidence.read(scope.code(), scope.packageVersion());
        var actual = refresh(scope, plan, saved, entries);
        requireContinuationNodes(scope, saved, actual, locked);
        var correctedNodeKey = load(scope, id).correctedNodeKey();
        requireApprovedModelLinks(plan, actual, correctedNodeKey, entries, priorLinks);
        actual = withModelLinks(actual, priorLinks);
        var review = pendingModelLinks(plan, actual, correctedNodeKey, entries);
        if (saved.planRevision() == plan.revision() && review.links().isEmpty() && review.issues().isEmpty())
            throw conflict("当前导航已应用且没有可补挂的模型");
        var preview = continuationPreview(plan, saved, scope.rootMenuId(), review.links(), review.issues());
        return new PreparedContinuation(plan, saved, withModelLinks(actual, review.links()), preview);
    }

    private static void requireApprovedModelLinks(
            Plan plan, Navigation applied, String correctedNodeKey, List<Entry> entries, List<ModelLink> links) {
        if (links.isEmpty()) return;
        Set<String> keys = links.stream().map(ModelLink::nodeKey).collect(Collectors.toSet());
        var affected = new Navigation(applied.revision(), applied.planRevision(), applied.status(),
                applied.nodes().stream().filter(node -> keys.contains(node.key())).toList());
        var approved = pendingModelLinks(plan, affected, correctedNodeKey, entries).links();
        if (!new HashSet<>(approved).equals(new HashSet<>(links)))
            throw conflict("已补挂模型的原页或栏目归属已变化，请核对");
    }

    private static ModelLinkReview pendingModelLinks(Plan plan, Navigation applied, String correctedNodeKey, List<Entry> entries) {
        var modelsByMenu = plan.proposal().models().stream().filter(model -> model.menuId() != null)
                .collect(Collectors.groupingBy(Model::menuId));
        var entriesByKey = entries.stream().collect(Collectors.toMap(Entry::key, entry -> entry));
        var regions = plan.proposal().regions().stream()
                .filter(region -> region.maintenance() == null
                        && !SiteModelEvidenceService.publicRegion(region.sourcePath(), region.regionId()))
                .collect(Collectors.groupingBy(SiteModelPlanService.RegionUse::sourcePath));
        var links = new ArrayList<ModelLink>();
        var issues = new ArrayList<ModelLinkIssue>();
        for (var node : applied.nodes()) {
            var candidates = modelsByMenu.getOrDefault(node.menuId().longValue(), List.of());
            if (candidates.isEmpty()) continue;
            if (candidates.size() != 1) throw conflict("同一导航栏目存在多个审核模型：" + node.name());
            var candidate = candidates.getFirst();
            if (candidate.key().equals(node.modelKey())) {
                if (node.kind() != contentKind(candidate))
                    throw conflict("导航关联的内容模型类型已变化：" + node.name());
                continue;
            }
            if (node.modelKey() != null)
                throw conflict("已应用导航已有其他模型，请核对栏目归属：" + node.name());
            var entry = entriesByKey.get(node.key());
            if (node.drift() || node.kind() != contentKind(candidate))
                throw conflict("导航栏目或模型类型已变化：" + node.name());
            if (entry != null && (entry.sourcePath() != null
                    ? approvedModelSource(node, candidate, entry, correctedNodeKey, regions)
                    : approvedPlaceholderModel(node, candidate, entries, regions)))
                links.add(new ModelLink(node.key(), node.menuId(), node.modelKey(), candidate.key()));
            else issues.add(new ModelLinkIssue(node.key(), node.menuId(), candidate.key(),
                    "栏目缺少对应模型的唯一已审核来源归属"));
        }
        return new ModelLinkReview(links, issues);
    }

    private static boolean approvedModelSource(
            Node node, Model model, Entry entry, String correctedNodeKey,
            Map<String, List<SiteModelPlanService.RegionUse>> regions) {
        var page = regions.getOrDefault(entry.sourcePath(), List.of());
        if (node.kind() == Kind.SINGLE) {
            var body = page.stream()
                    .filter(region -> "SINGLE".equals(region.mode()) && region.records().size() == 1)
                    .toList();
            return body.size() == 1 && model.key().equals(body.getFirst().modelKey());
        }
        if (page.stream().anyMatch(region -> "LIST".equals(region.mode()) && model.key().equals(region.modelKey())))
            return true;
        return node.key().equals(correctedNodeKey) && entry.listDetailEvidence() != null
                && regions.getOrDefault(entry.listDetailEvidence().detailPath(), List.of()).stream()
                        .anyMatch(region -> "DETAIL".equals(region.mode()) && region.records().size() == 1
                                && model.key().equals(region.modelKey()));
    }

    private static boolean approvedPlaceholderModel(Node node, Model model, List<Entry> entries,
            Map<String, List<SiteModelPlanService.RegionUse>> regions) {
        if (node.kind() != Kind.SINGLE || node.parentKey() == null || node.sourcePath() != null
                || node.proposedUrl() != null || node.reuseMenuId() != null || node.sourceHref() == null
                || !node.sourceHref().matches("#+") || node.observations().isEmpty()
                || node.observations().stream().anyMatch(item ->
                        !"PLACEHOLDER".equals(item.linkKind()) || item.targetPath() != null)
                || entries.stream().anyMatch(item -> node.key().equals(item.parentKey()))) return false;
        var uses = regions.values().stream().flatMap(List::stream)
                .filter(use -> model.key().equals(use.modelKey())).toList();
        if (uses.size() != 1) return false;
        var use = uses.getFirst();
        return "SINGLE".equals(use.mode()) && use.records().size() == 1
                && !use.records().getFirst().bindings().isEmpty()
                && regions.get(use.sourcePath()).stream().filter(row -> Set.of("SINGLE", "DETAIL").contains(row.mode())).count() == 1
                && entries.stream().noneMatch(item -> use.sourcePath().equals(item.sourcePath()));
    }

    private static Navigation legacyCorrectedModel(
            Plan plan, Navigation navigation, String correctedNodeKey, List<Entry> entries,
            ContinuationPreview receipt) {
        if (correctedNodeKey == null || !receipt.modelLinks().isEmpty()) return navigation;
        var target = navigation.nodes().stream().filter(node -> correctedNodeKey.equals(node.key()))
                .findFirst().orElse(null);
        if (target == null || target.drift() || target.modelKey() != null || target.kind() != Kind.LIST
                || entries.stream().noneMatch(entry -> correctedNodeKey.equals(entry.key())
                        && entry.listDetailEvidence() != null)) return navigation;
        var single = new Navigation(navigation.revision(), navigation.planRevision(), navigation.status(), List.of(target));
        try {
            return withModelLinks(navigation, pendingModelLinks(plan, single, correctedNodeKey, entries).links());
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            return navigation;
        }
    }

    static Navigation withModelLinks(Navigation navigation, List<ModelLink> links) {
        if (links.isEmpty()) return navigation;
        var byKey = links.stream().collect(Collectors.toMap(ModelLink::nodeKey, link -> link));
        var nodes = navigation.nodes().stream().map(node -> {
            var link = byKey.get(node.key());
            if (link == null) return node;
            if (!Objects.equals(node.menuId(), link.menuId()) || !Objects.equals(node.modelKey(), link.oldModelKey()))
                throw conflict("导航模型关联与原已应用栏目不一致");
            return new Node(node.key(), node.parentKey(), node.name(), node.order(), node.sourcePath(),
                    node.evidencePath(), node.evidenceSelector(), node.sourceHref(), node.kind(),
                    link.newModelKey(), node.reuseMenuId(), node.menuId(), node.proposedUrl(),
                    node.contentStatus(), node.cmsName(), node.drift(), node.confirmReuse(),
                    node.observations(), node.independentLanding());
        }).toList();
        return new Navigation(navigation.revision(), navigation.planRevision(), navigation.status(), nodes,
                navigation.supplementalEvidence(), navigation.originPlanId(), navigation.originPlanRevision());
    }

    private void requireContinuationNodes(Scope scope, Navigation saved, Navigation actual, boolean locked) {
        if (actual.supplementalEvidence().stream().anyMatch(entry -> !entry.keepsOriginalLink(actual.supplementalEvidence())))
            throw conflict("页面包存在未经审核的导航证据");
        var ids = saved.nodes().stream().collect(Collectors.toMap(Node::key, Node::menuId));
        for (var node : actual.nodes()) {
            var menu = locked ? lock(scope, node.menuId()) : menus.getModelById(node.menuId());
            if (node.drift() || menu == null || Boolean.TRUE.equals(menu.getIsDeleted())
                    || !Objects.equals(menu.getId(), node.menuId())
                    || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                    || !Objects.equals(menu.getSiteRootId(), scope.rootMenuId())
                    || (Objects.equals(node.menuId(), scope.rootMenuId())
                            ? !validRoot(scope, node, menu)
                            : !matchesApplied(scope, node, menu, ids)
                                    || !Objects.equals(menu.getHasManageChannel(), node.kind() != Kind.DIRECTORY)))
                throw conflict("导航栏目的归属、父级、顺序或类型已变化：" + node.name());
        }
    }

    static boolean matchesContinuation(Plan plan, Navigation saved, Integer rootMenuId, ContinuationPreview receipt) {
        return receipt != null && receipt.equals(
                continuationPreview(plan, saved, rootMenuId, receipt.modelLinks(), receipt.modelLinkIssues()));
    }

    private static ContinuationPreview continuationPreview(
            Plan plan, Navigation saved, Integer rootMenuId, List<ModelLink> links, List<ModelLinkIssue> issues) {
        try {
            var identity = List.of(plan.id(), plan.revision(), plan.catalogDigest(), rootMenuId, saved);
            var value = new ObjectMapper().writeValueAsBytes(links.isEmpty() ? identity : List.of(identity, links));
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            return new ContinuationPreview(
                    saved.planRevision(), plan.revision(), saved.revision(), saved.nodes().size(), digest, links, issues);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法校验导航接续预览", error);
        }
    }

    private static Navigation continued(Plan plan, Navigation actual) {
        return new Navigation(actual.revision(), plan.revision(), Status.APPLIED,
                actual.nodes(), actual.supplementalEvidence(), actual.originPlanId(), actual.originPlanRevision());
    }

    public AppendResult applyAppend(
            Scope scope,
            String id,
            AppendRequest body,
            HttpServletRequest request,
            Runnable guard) {
        if (!body.confirmed()
                || body.previewDigest() == null
                || !body.previewDigest().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("请先预览并确认导航追加");
        String nextId =
                UUID.nameUUIDFromBytes(
                                new ObjectMapper()
                                        .writeValueAsBytes(
                                                new AppendRequest(
                                                        id,
                                                        body.planRevision(),
                                                        body.navigationRevision(),
                                                        body.nodeKey(),
                                                        body.kind(),
                                                        body.modelKey(),
                                                        body.reuseMenuId(),
                                                        body.confirmReuse(),
                                                        body.previewDigest(),
                                                        true)))
                        .toString();
        synchronized (plans) {
            guard.run();
            var next =
                    transaction.execute(
                            status -> {
                                lockProject(scope);
                                guard.run();
                                var stored = load(scope, nextId);
                                if (stored != null && stored.view().status() == Status.APPLIED)
                                    return null;
                                store.requireNoOtherPendingNavigationAppend(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        nextId);
                                var entries = evidence.read(scope.code(), scope.packageVersion());
                                Plan draft = null;
                                if (stored != null && stored.view().status() == Status.APPLYING) {
                                    var base =
                                            plans.get(
                                                    scope.siteId(),
                                                    scope.code(),
                                                    scope.packageVersion(),
                                                    id,
                                                    body.planRevision());
                                    draft =
                                            store.latest(
                                                    scope.siteId(),
                                                    scope.code(),
                                                    scope.packageVersion(),
                                                    nextId);
                                    if (draft == null)
                                        draft = plans.navigationAppendPlan(base, nextId);
                                    var actual = refresh(scope, draft, stored.view(), entries);
                                    if (actual.nodes().stream().noneMatch(Node::drift))
                                        return draft;
                                }
                                var prepared = prepareAppend(scope, id, body);
                                if (!body.previewDigest()
                                        .equals(prepared.preview().previewDigest()))
                                    throw conflict("追加预览已变化，请重新预览");
                                if (draft == null)
                                    draft = plans.navigationAppendPlan(prepared.plan(), nextId);
                                if (!draft.catalogDigest().equals(prepared.catalogDigest()))
                                    throw conflict("追加审核期间页面包已变化，请重新预览");
                                if (store.latest(
                                                scope.siteId(),
                                                scope.code(),
                                                scope.packageVersion(),
                                                nextId)
                                        == null) store.checkSave(draft);
                                var old = prepared.previous();
                                var delta = prepared.preview().nodes();
                                if (stored != null && stored.view().status() == Status.APPLYING) {
                                    var newKeys =
                                            delta.stream()
                                                    .map(Node::key)
                                                    .collect(Collectors.toSet());
                                    var attempted =
                                            stored.view().nodes().stream()
                                                    .filter(node -> newKeys.contains(node.key()))
                                                    .toList();
                                    if (!canReplayAppend(scope, attempted, stored.before()))
                                        throw conflict("追加栏目与保存意图不一致，请核对实际栏目");
                                }
                                var ids =
                                        old.nodes().stream()
                                                .collect(Collectors.toMap(Node::key, Node::menuId));
                                for (var node : old.nodes()) {
                                    var menu = lock(scope, node.menuId());
                                    if (Objects.equals(node.menuId(), scope.rootMenuId())
                                            ? !validRoot(scope, node)
                                            : !matchesApplied(scope, node, menu, ids))
                                        throw conflict("原栏目父级或排序已变化：" + node.name());
                                }
                                var materialized =
                                        applyNodes(
                                                scope,
                                                draft,
                                                delta,
                                                prepared.before(),
                                                request,
                                                ids);
                                var byKey =
                                        old.nodes().stream()
                                                .collect(Collectors.toMap(Node::key, node -> node));
                                materialized.forEach(node -> byKey.put(node.key(), node));
                                var nodes =
                                        entries.stream()
                                                .map(entry -> byKey.get(entry.key()))
                                                .filter(Objects::nonNull)
                                                .toList();
                                var applying =
                                        new Navigation(
                                                old.revision() + 1,
                                                draft.revision(),
                                                Status.APPLYING,
                                                nodes,
                                                List.of(),
                                                prepared.preview().originPlanId(),
                                                old.planRevision());
                                guard.run();
                                saveNavigation(scope, nextId, applying, prepared.before());
                                if (store.latest(
                                                scope.siteId(),
                                                scope.code(),
                                                scope.packageVersion(),
                                                nextId)
                                        == null) store.save(draft);
                                return draft;
                            });
            return transaction.execute(
                    status -> {
                        lockProject(scope);
                        guard.run();
                        var stored = load(scope, nextId);
                        if (stored == null) throw conflict("追加回执已变化，请重新读取");
                        var saved = stored.view();
                        var plan =
                                next == null
                                        ? originalPlan(scope, current(scope, nextId), saved)
                                        : next;
                        var actual =
                                refresh(
                                        scope,
                                        plan,
                                        saved,
                                        evidence.read(scope.code(), scope.packageVersion()));
                        if (actual.nodes().stream()
                                .anyMatch(
                                        node ->
                                                node.drift()
                                                        || Objects.equals(
                                                                        node.menuId(),
                                                                        scope.rootMenuId())
                                                                && !validRoot(scope, node)))
                            throw conflict("追加后栏目证据已变化，请核对");
                        if (saved.status() == Status.APPLIED)
                            return new AppendResult(nextId, actual);
                        var base =
                                plans.get(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        id,
                                        body.planRevision());
                        store.registerModelDraft(base, plan, null, null, null);
                        if (store.latest(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        nextId)
                                == null) store.save(plan);
                        var result =
                                new Navigation(
                                        saved.revision(),
                                        saved.planRevision(),
                                        Status.APPLIED,
                                        actual.nodes(),
                                        actual.supplementalEvidence(),
                                        saved.originPlanId(),
                                        saved.originPlanRevision());
                        saveNavigation(scope, nextId, result, stored.before());
                        return new AppendResult(nextId, result);
                    });
        }
    }

    private PreparedAppend prepareAppend(Scope scope, String id, AppendRequest body) {
        var plan =
                plans.continuationBase(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        id,
                        body.planRevision());
        String catalogDigest = plans.validateNavigationAppendSource(plan);
        var currentOrigin =
                store.latestAppliedNavigationPlanId(
                        scope.siteId(), scope.code(), scope.packageVersion());
        var stored = load(scope, id);
        String sourceId = stored == null ? origin(scope, id) : id;
        if (sourceId == null || !sourceId.equals(currentOrigin))
            throw conflict("请打开最新的已应用整站导航计划后追加");
        var old = appliedSnapshot(scope, sourceId);
        if (old.revision() != body.navigationRevision()) throw conflict("导航修订已变化，请重新读取");
        var source =
                plans.get(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        sourceId,
                        old.planRevision());
        if (!Objects.equals(plan.catalogDigest(), source.catalogDigest()))
            throw conflict("原导航回执与当前页面包不一致");
        var entries = evidence.read(scope.code(), scope.packageVersion());
        var previous = refresh(scope, source, old, entries);
        if (previous.nodes().stream()
                .anyMatch(
                        node ->
                                node.drift()
                                        || Objects.equals(node.menuId(), scope.rootMenuId())
                                                && !validRoot(scope, node)))
            throw conflict("原导航栏目的实际归属或顺序已变化，请核对");
        var existing = previous.nodes().stream().collect(Collectors.toMap(Node::key, node -> node));
        var selected =
                previous.supplementalEvidence().stream()
                        .filter(entry -> entry.key().equals(body.nodeKey()))
                        .findFirst()
                        .orElseThrow(() -> conflict("请选择尚未应用的原页链接"));
        Node parent = existing.get(selected.parentKey());
        if (selected.parentKey() != null && parent == null) throw conflict("请先审核并追加该链接的父入口");
        var defaults =
                propose(scope, plan, entries).stream()
                        .collect(Collectors.toMap(Node::key, node -> node));
        var first = defaults.get(selected.key());
        Kind kind = body.kind() == null ? first.kind() : body.kind();
        Model model =
                model(
                        plan,
                        body.kind() == null && body.modelKey() == null
                                ? first.modelKey()
                                : body.modelKey());
        Integer reuse =
                home(selected)
                        ? scope.rootMenuId()
                        : body.kind() == null
                                        && body.modelKey() == null
                                        && body.reuseMenuId() == null
                                ? first.reuseMenuId()
                                : body.reuseMenuId();
        var branch = new ArrayList<Entry>();
        var proposed = new ArrayList<Node>();
        var keys = new HashSet<String>();
        appendEntry(selected, kind);
        branch.add(selected);
        proposed.add(node(scope, selected, kind, model, reuse, body.confirmReuse()));
        keys.add(selected.key());
        if (kind == Kind.DIRECTORY) {
            for (Entry entry : previous.supplementalEvidence()) {
                if (!keys.contains(entry.parentKey())) continue;
                Node defaultsForChild = defaults.get(entry.key());
                if (!canAppendEntry(entry, defaultsForChild.kind())
                        || defaultsForChild.reuseMenuId() != null
                        || defaultsForChild.modelKey() != null
                                && !reuseEvidence(
                                        plan, entry, entries, model(plan, defaultsForChild.modelKey())))
                    continue;
                branch.add(entry);
                proposed.add(defaultsForChild);
                keys.add(entry.key());
            }
        }
        var reviewed = validate(scope, plan, branch, proposed, entries);
        var owners = previous.nodes().stream().map(Node::menuId).collect(Collectors.toSet());
        var urls =
                previous.nodes().stream()
                        .map(Node::proposedUrl)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        var activeMenus = menus.selectActiveSiteModelMenus(scope.siteId());
        var before = new HashMap<String, Previous>();
        var pending = new ArrayList<Node>();
        for (int i = 0; i < reviewed.size(); i++) {
            var node = reviewed.get(i);
            if (node.modelKey() != null
                    && !home(branch.get(i))
                    && !reuseEvidence(plan, branch.get(i), entries, model(plan, node.modelKey())))
                throw new IllegalArgumentException("所选模型缺少该入口的独立栏目证据：" + node.name());
            var menuId = node.reuseMenuId();
            if (menuId != null && !owners.add(menuId))
                throw conflict("候选复用栏目已在原导航使用：" + node.name());
            if (menuId != null && !menuId.equals(scope.rootMenuId())) {
                var actual = menus.getModelById(menuId);
                if (actual == null
                        || !Objects.equals(actual.getSiteProjectId(), scope.siteId())
                        || !Objects.equals(actual.getSiteRootId(), scope.rootMenuId())
                        || Boolean.TRUE.equals(actual.getIsDeleted()))
                    throw conflict("候选复用栏目不在本站范围：" + node.name());
                requireReusable(scope, node, actual);
                before.put(node.key(), new Previous(actual.getParentId(), actual.getSort()));
            }
            var owner = existing.get(node.parentKey());
            Integer parentId =
                    node.parentKey() == null
                            ? scope.rootMenuId()
                            : owner == null ? null : owner.menuId();
            if (parentId != null
                    && activeMenus.stream()
                            .anyMatch(
                                    menu ->
                                            parentId.equals(menu.getParentId())
                                                    && node.name().equals(menu.getClassName())
                                                    && !Objects.equals(menuId, menu.getId())
                                                    && !("site_nav_" + node.key())
                                                            .equals(menu.getIdentifies())))
                throw conflict("同级存在同名栏目，请先核对：" + node.name());
            String url = node.proposedUrl();
            if (url != null && !urls.add(url)) {
                int slash = node.sourcePath().lastIndexOf('/');
                url =
                        "/"
                                + node.sourcePath().substring(0, slash + 1)
                                + "category-"
                                + node.key()
                                + "/index.aspx";
                if (!urls.add(url)) throw conflict("新增栏目建议路由与原导航重复：" + node.name());
            }
            pending.add(withMenu(node, null, url, node.contentStatus()));
        }
        String digest = appendDigest(plan, catalogDigest, sourceId, old, pending, parent, before);
        var preview =
                new AppendPreview(
                        sourceId,
                        source.revision(),
                        old.revision(),
                        pending.getFirst(),
                        List.copyOf(pending),
                        parent,
                        digest);
        return new PreparedAppend(plan, catalogDigest, previous, preview, Map.copyOf(before));
    }

    private static void appendEntry(Entry entry, Kind kind) {
        if (!canAppendEntry(entry, kind))
            throw new IllegalArgumentException("该外链、锚点或未解析链接应保留原地址，无法创建 CMS 栏目：" + entry.name());
    }

    private static boolean canAppendEntry(Entry entry, Kind kind) {
        return entry.observations().stream().anyMatch(item -> "LOCAL_PAGE".equals(item.linkKind()))
                || kind == Kind.DIRECTORY
                        && entry.observations().stream()
                                .anyMatch(
                                        item ->
                                                "PLACEHOLDER".equals(item.linkKind())
                                                        || "ANCHOR".equals(item.linkKind()));
    }

    private static String appendDigest(
            Plan plan,
            String catalogDigest,
            String sourceId,
            Navigation old,
            List<Node> nodes,
            Node parent,
            Map<String, Previous> before) {
        try {
            var value =
                    new ObjectMapper()
                            .writeValueAsBytes(
                                    java.util.Arrays.asList(
                                            plan.id(),
                                            plan.revision(),
                                            catalogDigest,
                                            sourceId,
                                            old.revision(),
                                            old.nodes(),
                                            nodes,
                                            parent,
                                            new java.util.TreeMap<>(before)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法校验导航追加预览", error);
        }
    }

    private boolean canReplayAppend(
            Scope scope, List<Node> attempted, Map<String, Previous> before) {
        for (var node : attempted) {
            if (Objects.equals(node.menuId(), scope.rootMenuId())) continue;
            if (node.reuseMenuId() == null) {
                if (menus.countNavigationIdentity(
                                scope.siteId(), node.menuId(), "site_nav_" + node.key())
                        != 0) return false;
            } else {
                var existing = lock(scope, node.reuseMenuId());
                requireReusable(scope, node, existing);
                var previous = before.get(node.key());
                if (previous == null
                        || !Objects.equals(previous.parentId(), existing.getParentId())
                        || !Objects.equals(previous.sort(), existing.getSort())) return false;
            }
        }
        return true;
    }

    public Navigation review(
            Scope scope, String id, long revision, List<Node> nodes, Runnable guard) {
        synchronized (plans) {
            guard.run();
            Plan plan = current(scope, id);
            plans.confirmed(
                    scope.siteId(), scope.code(), scope.packageVersion(), id, plan.revision());
            var entries = evidence.read(scope.code(), scope.packageVersion());
            if (entries.isEmpty() || nodes == null || nodes.size() != entries.size())
                throw new IllegalArgumentException("请审核完整的来源导航");
            var reviewed = validate(scope, plan, entries, nodes);
            return transaction.execute(
                    status -> {
                        lockProject(scope);
                        guard.run();
                        var stored = load(scope, id);
                        var saved = stored == null ? null : stored.view();
                        if (saved == null && origin(scope, id) != null)
                            throw conflict("已应用导航由原整站计划维护，不能重新创建");
                        if (saved != null && saved.status() != Status.CONFIRMED && saved.status() != Status.DRAFT)
                            throw conflict("导航结构已经开始应用");
                        if (revision != (saved == null ? 0 : saved.revision()))
                            throw conflict("导航计划已更新，请重新读取");
                        var before = new HashMap<String, Previous>();
                        for (var node : reviewed) {
                            if (node.reuseMenuId() == null
                                    || node.reuseMenuId().equals(scope.rootMenuId())) continue;
                            var menu = lock(scope, node.reuseMenuId());
                            requireReusable(scope, node, menu);
                            before.put(
                                    node.key(), new Previous(menu.getParentId(), menu.getSort()));
                        }
                        var result =
                                new Navigation(
                                        revision + 1, plan.revision(), Status.CONFIRMED, reviewed);
                        saveNavigation(scope, id, result, Map.copyOf(before));
                        return result;
                    });
        }
    }

    public Navigation apply(
            Scope scope, String id, long revision, HttpServletRequest request, Runnable guard) {
        synchronized (plans) {
            guard.run();
            Plan plan = current(scope, id);
            var entries = evidence.read(scope.code(), scope.packageVersion());
            // 审核与应用均锁站点行。提交前持久化意图，后续同修订只核对结果。
            Navigation completed =
                    transaction.execute(
                            status -> {
                                lockProject(scope);
                                guard.run();
                                var stored = load(scope, id);
                                var saved = stored == null ? null : stored.view();
                                if (saved == null || saved.revision() != revision)
                                    throw conflict("请先审核当前导航计划");
                                if (saved.originPlanId() != null
                                        && saved.status() != Status.APPLIED)
                                    throw conflict("请通过导航追加应用入口恢复该修订");
                                if (saved.status() != Status.CONFIRMED) {
                                    var actual =
                                            refresh(
                                                    scope,
                                                    originalPlan(scope, plan, saved),
                                                    saved,
                                                    entries);
                                    if (actual.nodes().stream().noneMatch(Node::drift))
                                        return new Navigation(
                                                revision,
                                                saved.planRevision(),
                                                Status.APPLIED,
                                                actual.nodes(),
                                                actual.supplementalEvidence());
                                    if (saved.status() != Status.APPLYING
                                            || !canReplayUncommitted(scope, stored))
                                        throw conflict("应用结果与实际栏目不一致，请核对后处理");
                                    saved = confirmedBeforeCommit(saved);
                                    saveNavigation(scope, id, saved, stored.before());
                                }
                                if (saved.planRevision() != plan.revision())
                                    throw conflict("整站计划已更新，请重新审核导航");
                                plans.confirmed(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        id,
                                        plan.revision());
                                var nodes = validate(scope, plan, entries, saved.nodes());
                                var result =
                                        applyNodes(scope, plan, nodes, stored.before(), request);
                                guard.run();
                                saveNavigation(
                                        scope,
                                        id,
                                        new Navigation(
                                                revision, plan.revision(), Status.APPLYING, result),
                                        stored.before());
                                restoreOnRollback(scope, id, saved, stored.before());
                                return new Navigation(
                                        revision, plan.revision(), Status.APPLIED, result);
                            });
            try {
                return transaction.execute(
                        status -> {
                            lockProject(scope);
                            guard.run();
                            var stored = load(scope, id);
                            var saved = stored == null ? null : stored.view();
                            if (saved == null
                                    || saved.revision() != revision
                                    || saved.status() == Status.CONFIRMED)
                                throw conflict("导航应用回执已变化");
                            var actual =
                                    refresh(
                                            scope,
                                            originalPlan(scope, plan, saved),
                                            saved,
                                            entries);
                            if (actual.nodes().stream().anyMatch(Node::drift))
                                throw conflict("栏目提交后已有变更，请核对实际父级和顺序");
                            if (saved.status() == Status.APPLIED) return actual;
                            var result =
                                    new Navigation(
                                            completed.revision(),
                                            completed.planRevision(),
                                            Status.APPLIED,
                                            actual.nodes(),
                                            actual.supplementalEvidence());
                            saveNavigation(scope, id, result, stored.before());
                            return result;
                        });
            } catch (IllegalStateException error) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "栏目已保存，导航计划回写失败，请重试同一修订恢复", error);
            }
        }
    }

    private boolean canReplayUncommitted(Scope scope, SavedNavigation stored) {
        boolean hasNewMenu = false;
        for (Node node : stored.view().nodes()) {
            if (node.reuseMenuId() == null) {
                if (node.menuId() == null
                        || menus.countNavigationIdentity(
                                        scope.siteId(), node.menuId(), "site_nav_" + node.key())
                                != 0) return false;
                hasNewMenu = true;
            } else if (!node.reuseMenuId().equals(scope.rootMenuId())) {
                var previous = stored.before().get(node.key());
                var existing = lock(scope, node.reuseMenuId());
                requireReusable(scope, node, existing);
                if (previous == null
                        || !Objects.equals(previous.parentId(), existing.getParentId())
                        || !Objects.equals(previous.sort(), existing.getSort())) return false;
            }
        }
        return hasNewMenu;
    }

    private static Navigation confirmedBeforeCommit(Navigation saved) {
        var nodes =
                saved.nodes().stream()
                        .map(node -> withMenu(node, null, node.proposedUrl(), node.contentStatus()))
                        .toList();
        return new Navigation(saved.revision(), saved.planRevision(), Status.CONFIRMED, nodes);
    }

    private void restoreOnRollback(
            Scope scope, String id, Navigation confirmed, Map<String, Previous> before) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_ROLLED_BACK) return;
                        var current = load(scope, id);
                        if (current != null
                                && current.view().status() == Status.APPLYING
                                && current.view().revision() == confirmed.revision())
                            saveNavigation(scope, id, confirmed, before);
                    }
                });
    }

    private void lockProject(Scope scope) {
        requireProject(scope, projects.selectByIdForUpdate(scope.siteId()));
    }

    private static void requireProject(Scope scope, SiteProjectEntity project) {
        if (project == null
                || !Objects.equals(project.getRootMenuId(), scope.rootMenuId())
                || !Objects.equals(project.getProjectCode(), scope.code())
                || !Objects.equals(project.getUiPackageVersion(), scope.packageVersion())
                || Integer.valueOf(1).equals(project.getDeleted())) throw conflict("站点或页面包已变化");
    }

    private SavedNavigation load(Scope scope, String id) {
        return store.navigation(scope.siteId(), scope.code(), scope.packageVersion(), id);
    }

    void forkModelDraft(Scope scope, Plan draft, Navigation source, String key, String modelKey) {
        if (source.status() == Status.APPLIED) return;
        if (source.status() == Status.APPLYING) throw conflict("导航正在应用，稍后再生成模型计划");
        var nodes = source.nodes().stream().map(node -> !key.equals(node.key()) || modelKey == null ? node
                : new Node(node.key(), node.parentKey(), node.name(), node.order(), node.sourcePath(),
                        node.evidencePath(), node.evidenceSelector(), node.sourceHref(), node.kind(), modelKey,
                        node.reuseMenuId(), node.menuId(), node.proposedUrl(), node.contentStatus(), node.cmsName(),
                        node.drift(), node.confirmReuse(), node.observations(), node.independentLanding())).toList();
        saveNavigation(scope, draft.id(), new Navigation(1, draft.revision(), Status.DRAFT, nodes), Map.of());
    }

    private void saveNavigation(
            Scope scope, String id, Navigation navigation, Map<String, Previous> before) {
        store.saveNavigation(
                scope.siteId(),
                scope.code(),
                scope.packageVersion(),
                id,
                new SavedNavigation(navigation, before));
    }

    private Plan originalPlan(Scope scope, Plan current, Navigation saved) {
        if (saved.planRevision() == current.revision()) return current;
        return plans.get(
                scope.siteId(),
                scope.code(),
                scope.packageVersion(),
                current.id(),
                saved.planRevision());
    }

    Navigation inherited(Scope scope, String id) {
        return inherited(
                scope, current(scope, id), evidence.read(scope.code(), scope.packageVersion()));
    }

    private Navigation inherited(Scope scope, Plan plan, List<Entry> entries) {
        String originId = origin(scope, plan.id());
        if (originId == null) return null;
        var stored = load(scope, originId);
        if (stored == null || stored.view().status() != Status.APPLIED)
            throw conflict("原导航回执已变化，请重新读取");
        var saved = stored.view();
        var original =
                plans.get(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        originId,
                        saved.planRevision());
        if (!"CONFIRMED".equals(original.status())
                || !Objects.equals(original.catalogDigest(), plan.catalogDigest())
                || saved.nodes().isEmpty()
                || saved.nodes().stream().anyMatch(node -> node.menuId() == null)
                || saved.nodes().stream().map(Node::menuId).distinct().count()
                        != saved.nodes().size()) throw conflict("原导航回执与当前站点栏目或页面包不一致，请核对原整站计划");
        var head = current(scope, originId);
        var refreshed = refresh(scope, original, withModelLinks(saved,
                store.navigationModelLinks(head, saved, scope.rootMenuId())), entries);
        if (refreshed.nodes().stream()
                .anyMatch(
                        node ->
                                node.drift()
                                        || Objects.equals(node.menuId(), scope.rootMenuId())
                                                && !validRoot(scope, node)))
            throw conflict("原导航栏目的归属或配置已变化，请核对原整站计划");
        return new Navigation(
                refreshed.revision(),
                plan.revision(),
                Status.APPLIED,
                refreshed.nodes(),
                refreshed.supplementalEvidence(),
                originId,
                original.revision());
    }

    private String origin(Scope scope, String id) {
        return store.appliedNavigationPlanId(
                scope.siteId(), scope.code(), scope.packageVersion(), id);
    }

    private boolean validRoot(Scope scope, Node node) {
        return validRoot(scope, node, menus.getModelById(scope.rootMenuId()));
    }

    private static boolean validRoot(Scope scope, Node node, OneshotEntity root) {
        return node.parentKey() == null
                && "首页".equals(node.name())
                && node.kind() == Kind.SINGLE
                && root != null
                && scope.siteId().equals(root.getSiteProjectId())
                && scope.rootMenuId().equals(root.getSiteRootId())
                && Integer.valueOf(pageType(Kind.SINGLE)).equals(root.getPageType());
    }

    private Navigation refresh(Scope scope, Plan plan, Navigation saved, List<Entry> entries) {
        var byKey = entries.stream().collect(Collectors.toMap(Entry::key, entry -> entry));
        var actualIds =
                saved.nodes().stream()
                        .filter(node -> node.menuId() != null)
                        .collect(Collectors.toMap(Node::key, Node::menuId));
        var models =
                plan.proposal().models().stream()
                        .collect(Collectors.toMap(Model::key, model -> model));
        var refreshed =
                saved.nodes().stream()
                        .map(
                                node -> {
                                    Entry entry = byKey.get(node.key());
                                    if (entry == null || !node.matches(entry))
                                        throw conflict("原始导航证据已变化，请核对页面包");
                                    Integer target =
                                            node.menuId() == null
                                                    ? node.reuseMenuId()
                                                    : node.menuId();
                                    var actual = target == null ? null : menus.getModelById(target);
                                    if (actual != null
                                            && (!scope.siteId().equals(actual.getSiteProjectId())
                                                    || !target.equals(scope.rootMenuId())
                                                            && !scope.rootMenuId()
                                                                    .equals(
                                                                            actual
                                                                                    .getSiteRootId())))
                                        actual = null;
                                    boolean drift =
                                            (saved.status() == Status.APPLIED
                                                            || saved.status() == Status.APPLYING)
                                                    && (actual == null
                                                            || !target.equals(scope.rootMenuId())
                                                                    && !matchesApplied(
                                                                            scope, node, actual,
                                                                            actualIds));
                                    ContentStatus content =
                                            contentStatus(
                                                    scope,
                                                    entry,
                                                    node.kind(),
                                                    node.modelKey() == null
                                                            ? null
                                                            : models.get(node.modelKey()),
                                                    actual);
                                    return new Node(
                                            node.key(),
                                            node.parentKey(),
                                            node.name(),
                                            node.order(),
                                            node.sourcePath(),
                                            node.evidencePath(),
                                            node.evidenceSelector(),
                                            node.sourceHref(),
                                            node.kind(),
                                            node.modelKey(),
                                            node.reuseMenuId(),
                                            node.menuId(),
                                            node.proposedUrl(),
                                            content,
                                            actual == null ? null : actual.getClassName(),
                                            drift,
                                            node.confirmReuse(),
                                            entry.observations(),
                                            entry.independentLanding());
                                })
                        .toList();
        var savedKeys = refreshed.stream().map(Node::key).collect(Collectors.toSet());
        var additional =
                entries.stream().filter(entry -> !savedKeys.contains(entry.key())).toList();
        return new Navigation(
                saved.revision(),
                saved.planRevision(),
                saved.status(),
                refreshed,
                additional,
                saved.originPlanId(),
                saved.originPlanRevision());
    }

    static boolean matchesApplied(
            Scope scope, Node node, OneshotEntity actual, Map<String, Integer> actualIds) {
        Integer parent =
                node.parentKey() == null ? scope.rootMenuId() : actualIds.get(node.parentKey());
        return parent != null
                && parent.equals(actual.getParentId())
                && Objects.equals(actual.getSort(), node.order())
                && Objects.equals(actual.getPageType(), pageType(node.kind()))
                && Objects.equals(
                        actual.getIdentifies(),
                        node.reuseMenuId() == null
                                ? "site_nav_" + node.key()
                                : SiteModelStructureService.modelMarker(node.modelKey()));
    }

    private Plan current(Scope scope, String id) {
        return plans.get(scope.siteId(), scope.code(), scope.packageVersion(), id);
    }

    private static boolean home(Entry entry) {
        return entry.parentKey() == null && "首页".equals(entry.name());
    }

    private List<Node> propose(Scope scope, Plan plan, List<Entry> entries) {
        var result = new ArrayList<Node>();
        var assigned = new HashSet<Integer>();
        for (var entry : entries) {
            var matched =
                    plan.proposal().regions().stream()
                            .filter(
                                    region ->
                                            Objects.equals(region.sourcePath(), entry.sourcePath()))
                            .filter(
                                    region ->
                                            !SiteModelEvidenceService.publicRegion(
                                                    region.sourcePath(), region.regionId()))
                            .filter(region -> !"DETAIL".equals(region.mode()))
                            .map(region -> region.modelKey())
                            .distinct()
                            .toList();
            var model = matched.size() == 1 ? model(plan, matched.getFirst()) : null;
            boolean directory =
                    entry.sourcePath() == null
                            && !home(entry)
                            && entries.stream()
                                    .anyMatch(child -> entry.key().equals(child.parentKey()));
            if (directory) model = null;
            boolean home = home(entry);
            Kind kind =
                    directory
                            ? Kind.DIRECTORY
                            : home ? Kind.SINGLE
                            : model != null ? contentKind(model)
                            : matched.isEmpty() && entry.listDetailEvidence() != null ? Kind.LIST : Kind.SINGLE;
            Integer reuse =
                    home
                            ? scope.rootMenuId()
                            : model == null || model.menuId() == null
                                    ? null
                                    : model.menuId().intValue();
            if (!home && reuse != null && !unambiguousTarget(entry, entries, model)) reuse = null;
            if (reuse != null && !assigned.add(reuse)) reuse = null;
            result.add(node(scope, entry, kind, model, reuse, false));
        }
        return withUrls(entries, result);
    }

    private List<Node> rebaseReviewedNodes(Scope scope, Plan plan, List<Entry> entries, Navigation saved) {
        var prior = saved.nodes().stream().collect(Collectors.toMap(Node::key, row -> row));
        var defaults = propose(scope, plan, entries);
        var models = plan.proposal().models().stream().collect(Collectors.toMap(Model::key, row -> row));
        var occupied = new HashSet<Integer>();
        var result = new ArrayList<Node>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var previous = prior.get(entry.key());
            if (previous == null || !previous.matches(entry)) previous = defaults.get(i);
            var selected = models.get(previous.modelKey());
            if (previous.kind() == Kind.DIRECTORY && entry.independentLanding()
                    || previous.modelKey() != null && (selected == null
                            || !home(entry) && previous.kind() != contentKind(selected))) {
                previous = defaults.get(i);
                selected = model(plan, previous.modelKey());
            }
            Integer reuse = previous.reuseMenuId();
            if (reuse != null && (!home(entry)
                    && (selected == null || !Objects.equals(reuse.longValue(), selected.menuId())
                            || !reuseEvidence(plan, entry, entries, selected))
                    || !occupied.add(reuse))) reuse = null;
            result.add(node(scope, entry, previous.kind(), selected, reuse,
                    reuse != null && previous.confirmReuse()));
        }
        return withUrls(entries, result);
    }

    private static boolean unambiguousTarget(Entry entry, List<Entry> entries, Model model) {
        return entry.sourcePath() != null
                && compatibleName(entry.name(), model.name())
                && entries.stream()
                                .filter(
                                        other ->
                                                Objects.equals(
                                                                other.sourcePath(),
                                                                entry.sourcePath())
                                                        && compatibleName(
                                                                other.name(), model.name()))
                                .count()
                        == 1;
    }

    private static boolean compatibleName(String entry, String model) {
        if (entry.equals(model)) return true;
        if (Math.min(entry.length(), model.length()) < 3) return false;
        return model.startsWith(entry)
                || model.endsWith(entry)
                || java.util.Arrays.equals(
                        entry.codePoints().sorted().toArray(),
                        model.codePoints().sorted().toArray());
    }

    private List<Node> validate(Scope scope, Plan plan, List<Entry> entries, List<Node> proposed) {
        return validate(scope, plan, entries, proposed, entries);
    }

    private List<Node> validate(
            Scope scope, Plan plan, List<Entry> entries, List<Node> proposed, List<Entry> allEntries) {
        if (entries.size() != proposed.size()) throw conflict("来源导航结构已变化，请重新审核");
        var inputs = new HashMap<String, Node>();
        for (var node : proposed) {
            if (node == null || node.key() == null || inputs.putIfAbsent(node.key(), node) != null)
                throw new IllegalArgumentException("导航节点缺少标识或重复");
        }
        var result = new ArrayList<Node>();
        var assigned = new HashSet<Integer>();
        for (var entry : entries) {
            Node input = inputs.get(entry.key());
            if (input == null || !input.matches(entry) || input.kind() == null)
                throw new IllegalArgumentException("导航层级、标签、顺序或原始链接与来源不一致");
            var model = model(plan, input.modelKey());
            boolean home = home(entry);
            Integer reuse = input.reuseMenuId();
            if (home) {
                if (!Objects.equals(reuse, scope.rootMenuId())
                        || input.kind() != Kind.SINGLE
                        || model != null
                                && plan.proposal().regions().stream()
                                        .noneMatch(
                                                region ->
                                                        model.key().equals(region.modelKey())
                                                                && Objects.equals(
                                                                        entry.sourcePath(),
                                                                        region.sourcePath())
                                                                && !SiteModelEvidenceService
                                                                        .publicRegion(
                                                                                region.sourcePath(),
                                                                                region.regionId())))
                    throw new IllegalArgumentException("首页必须保留站点根栏目");
            } else {
                if (input.kind() == Kind.DIRECTORY && entry.independentLanding())
                    throw new IllegalArgumentException("父栏目有独立落地正文，不能设为纯目录：" + entry.name());
                if (input.kind() == Kind.DIRECTORY
                        && (model != null
                                || reuse != null
                                || allEntries.stream()
                                        .noneMatch(child -> entry.key().equals(child.parentKey()))))
                    throw new IllegalArgumentException("目录需要子栏目，且不能占用内容栏目或内容模型");
                if (model != null && input.kind() != contentKind(model))
                    throw new IllegalArgumentException("内容模型与导航栏目类型不一致");
                if (reuse != null
                        && (reuse.equals(scope.rootMenuId())
                                || model == null
                                || !Objects.equals(reuse.longValue(), model.menuId())))
                    throw new IllegalArgumentException("只能复用审核计划中选定模型的已有栏目");
            }
            if (reuse != null && !assigned.add(reuse))
                throw new IllegalArgumentException("同一已有栏目不能挂载在多个导航节点");
            if (!home && reuse != null) {
                if (!reuseEvidence(plan, entry, allEntries, model))
                    throw new IllegalArgumentException("栏目来源未与所选已有模型匹配：" + entry.name());
                if (!unambiguousTarget(entry, allEntries, model) && !input.confirmReuse())
                    throw new IllegalArgumentException("请确认已有栏目及内容的实际归属：" + entry.name());
            }
            result.add(node(scope, entry, input.kind(), model, reuse, input.confirmReuse()));
        }
        return withUrls(entries, result);
    }

    private boolean reuseEvidence(Plan plan, Entry entry, List<Entry> entries, Model model) {
        if (entry.sourcePath() == null) return false;
        var sourcePaths = plan.proposal().regions().stream()
                .filter(region -> model.key().equals(region.modelKey()) && !"DETAIL".equals(region.mode()))
                .filter(region -> !SiteModelEvidenceService.publicRegion(region.sourcePath(), region.regionId()))
                .map(SiteModelPlanService.RegionUse::sourcePath).collect(Collectors.toSet());
        boolean hasNonHomeEvidence = sourcePaths.stream().anyMatch(path ->
                entries.stream().noneMatch(candidate -> home(candidate) && Objects.equals(path, candidate.sourcePath())));
        return sourcePaths.contains(entry.sourcePath()) && hasNonHomeEvidence
                || plans.hasNavigationModelEvidence(plan, entry.key(), entry.sourcePath(), model.key());
    }

    private Node node(
            Scope scope, Entry e, Kind kind, Model model, Integer reuse, boolean confirmReuse) {
        var actual = reuse == null ? null : menus.getModelById(reuse);
        if (actual != null && !scope.siteId().equals(actual.getSiteProjectId())) actual = null;
        return new Node(
                e.key(),
                e.parentKey(),
                e.name(),
                e.order(),
                e.sourcePath(),
                e.evidencePath(),
                e.evidenceSelector(),
                e.sourceHref(),
                kind,
                model == null ? null : model.key(),
                reuse,
                null,
                null,
                contentStatus(scope, e, kind, model, actual),
                actual == null ? null : actual.getClassName(),
                false,
                confirmReuse,
                e.observations(),
                e.independentLanding());
    }

    private ContentStatus contentStatus(
            Scope scope, Entry entry, Kind kind, Model model, OneshotEntity actual) {
        if (kind == Kind.DIRECTORY) return ContentStatus.NOT_REQUIRED;
        if (home(entry) && model != null && model.menuId() != null)
            actual = menus.getModelById(model.menuId().intValue());
        if (actual != null && scope.siteId().equals(actual.getSiteProjectId())) {
            if (Integer.valueOf(1).equals(actual.getPageType())
                    && contents.countNavigationRecords(scope.siteId(), actual.getId()) > 0)
                return ContentStatus.HAS_CONTENT;
            if (Integer.valueOf(0).equals(actual.getPageType()) && hasSingleValues(actual))
                return ContentStatus.HAS_CONTENT;
        }
        if (entry.sourcePath() == null) return ContentStatus.MISSING_SOURCE;
        return ContentStatus.PENDING_MATCH;
    }

    private static boolean hasSingleValues(OneshotEntity menu) {
        for (var definition : ContentModelCodec.read(menu.getMenuField())) {
            String name = definition.getFieldName();
            if ("className".equals(name) || !ContentFieldDefinition.enabled(definition.getIsOpen()))
                continue;
            if (com.xinhongru.content.model.ContentExtensionFields.isName(name)) {
                Object value =
                        com.xinhongru.content.model.ContentExtensionFields.read(
                                        menu.getExtensionFields())
                                .get(name);
                if (value != null && (!(value instanceof String text) || !text.isBlank()))
                    return true;
                continue;
            }
            try {
                var field =
                        com.xinhongru.common.reflect.EntityFields.find(OneshotEntity.class, name);
                field.setAccessible(true);
                if (field.get(menu) instanceof String value && !value.isBlank()) return true;
            } catch (ReflectiveOperationException ignored) {
                // 历史栏目可能保留已退役的字段。
            }
        }
        return false;
    }

    private static List<Node> withUrls(List<Entry> entries, List<Node> nodes) {
        var used = new HashSet<String>();
        var result = new ArrayList<Node>();
        var preferred = new HashMap<String, String>();
        for (var node : nodes) {
            if (node.kind() == Kind.DIRECTORY || node.sourcePath() == null) continue;
            boolean sharedChild =
                    node.parentKey() != null
                            && nodes.stream()
                                    .anyMatch(
                                            parent ->
                                                    node.parentKey().equals(parent.key())
                                                            && parent.kind() == Kind.DIRECTORY
                                                            && Objects.equals(
                                                                    node.sourcePath(),
                                                                    parent.sourcePath()))
                            && nodes.stream()
                                            .filter(
                                                    sibling ->
                                                            Objects.equals(
                                                                            node.parentKey(),
                                                                            sibling.parentKey())
                                                                    && Objects.equals(
                                                                            node.sourcePath(),
                                                                            sibling.sourcePath()))
                                            .count()
                                    > 1;
            if (!sharedChild) preferred.putIfAbsent(node.sourcePath(), node.key());
            used.add("/" + node.sourcePath());
        }
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            String url = null;
            if (node.kind() != Kind.DIRECTORY && node.sourcePath() != null) {
                if (node.key().equals(preferred.get(node.sourcePath())))
                    url = "/" + node.sourcePath();
                else {
                    int slash = node.sourcePath().lastIndexOf('/');
                    String base = slash < 0 ? "" : node.sourcePath().substring(0, slash + 1);
                    url = "/" + base + "category-" + entries.get(i).key() + "/index.aspx";
                    if (!used.add(url)) throw conflict("导航建议路由重复");
                }
            }
            result.add(withMenu(node, node.menuId(), url, node.contentStatus()));
        }
        return List.copyOf(result);
    }

    private List<Node> applyNodes(
            Scope scope,
            Plan plan,
            List<Node> nodes,
            Map<String, Previous> before,
            HttpServletRequest request) {
        return applyNodes(scope, plan, nodes, before, request, new HashMap<>());
    }

    private List<Node> applyNodes(
            Scope scope,
            Plan plan,
            List<Node> nodes,
            Map<String, Previous> before,
            HttpServletRequest request,
            Map<String, Integer> ids) {
        lock(scope, scope.rootMenuId());
        var candidates = menus.selectActiveSiteModelMenus(scope.siteId());
        if (candidates.size() > 1000) throw conflict("站点栏目数量超限");
        var result = new ArrayList<Node>();
        for (Node node : nodes) {
            int parentId =
                    node.parentKey() == null
                            ? scope.rootMenuId()
                            : Objects.requireNonNull(ids.get(node.parentKey()));
            Model model = model(plan, node.modelKey());
            int id;
            if (Objects.equals(node.reuseMenuId(), scope.rootMenuId())) {
                id = scope.rootMenuId();
            } else if (node.reuseMenuId() != null) {
                id = node.reuseMenuId();
                move(scope, node, before.get(node.key()), parentId, request);
            } else {
                id = create(scope, node, model, parentId, candidates, request);
            }
            ids.put(node.key(), id);
            result.add(withMenu(node, id, node.proposedUrl(), node.contentStatus()));
        }
        return List.copyOf(result);
    }

    private void move(
            Scope scope, Node node, Previous previous, int parentId, HttpServletRequest request) {
        var existing = lock(scope, node.reuseMenuId());
        requireReusable(scope, node, existing);
        if (previous == null
                || !Objects.equals(previous.parentId(), existing.getParentId())
                || !Objects.equals(previous.sort(), existing.getSort()))
            throw conflict("栏目父级或排序在审核后已修改，请重新核对：" + node.name());
        assertAcyclic(scope, existing.getId(), parentId);
        if (!Objects.equals(existing.getParentId(), parentId)
                || !Objects.equals(existing.getSort(), node.order()))
            save(
                    new EditOneshotDto()
                            .setId(existing.getId())
                            .setParentId(parentId)
                            .setSort(node.order()),
                    request);
    }

    private static void requireReusable(Scope scope, Node node, OneshotEntity existing) {
        if (node.reuseMenuId().equals(scope.rootMenuId())
                || !Objects.equals(
                        existing.getIdentifies(),
                        SiteModelStructureService.modelMarker(node.modelKey()))
                || !Boolean.TRUE.equals(existing.getHasManageChannel())
                || !Objects.equals(existing.getPageType(), pageType(node.kind()))
                || hasPublicRoute(existing)
                || Boolean.TRUE.equals(existing.getHasFrontChannel()))
            throw conflict("现有栏目已由人工配置或公开，请核对：" + node.name());
    }

    private int create(
            Scope scope,
            Node node,
            Model model,
            int parentId,
            List<OneshotEntity> candidates,
            HttpServletRequest request) {
        String marker = "site_nav_" + node.key();
        var matches =
                candidates.stream().filter(menu -> marker.equals(menu.getIdentifies())).toList();
        if (matches.size() > 1) throw conflict("存在重复导航标识：" + node.name());
        if (matches.size() == 1) {
            var existing = lock(scope, matches.getFirst().getId());
            if (!Objects.equals(existing.getParentId(), parentId)
                    || !Objects.equals(existing.getClassName(), node.name())
                    || !Objects.equals(existing.getSort(), node.order())
                    || !Objects.equals(existing.getPageType(), pageType(node.kind()))
                    || !Objects.equals(
                            existing.getHasManageChannel(), node.kind() != Kind.DIRECTORY)
                    || !Boolean.FALSE.equals(existing.getIsLoop())
                    || Boolean.TRUE.equals(existing.getHasFrontChannel())
                    || hasPublicRoute(existing)) throw conflict("原有导航栏目配置已变化：" + node.name());
            return existing.getId();
        }
        if (candidates.stream()
                .anyMatch(
                        menu ->
                                Objects.equals(parentId, menu.getParentId())
                                        && node.name().equals(menu.getClassName())))
            throw conflict("同级存在同名栏目，请先核对：" + node.name());
        var dto =
                new EditOneshotDto()
                        .setParentId(parentId)
                        .setSiteProjectId(scope.siteId())
                        .setSiteRootId(scope.rootMenuId())
                        .setClassName(node.name())
                        .setIdentifies(marker)
                        .setPageType(pageType(node.kind()))
                        .setHasManageChannel(node.kind() != Kind.DIRECTORY)
                        .setHasFrontChannel(false)
                        .setIsLoop(false)
                        .setIsCanDeleted(true)
                        .setPublished(false)
                        .setUrl(
                                node.kind() == Kind.LIST
                                        ? "/admin/model_manage/{id}"
                                        : "/admin/menu_manage/{id}");
        if (model != null) configure(scope, node, model, dto);
        save(dto, request);
        save(new EditOneshotDto().setId(dto.getId()).setSort(node.order()), request);
        return dto.getId();
    }

    private void configure(Scope scope, Node node, Model model, EditOneshotDto dto) {
        if (model.menuId() == null) throw conflict("请先应用所选内容模型字段：" + node.name());
        var source = lock(scope, model.menuId().intValue());
        if (!Objects.equals(source.getPageType(), pageType(node.kind())))
            throw conflict("已审核字段来源类型已变化：" + node.name());
        Set<String> approved =
                model.fields().stream()
                        .filter(field -> !"Sort".equals(field.type()))
                        .map(SiteModelPlanService.Field::fieldName)
                        .collect(Collectors.toSet());
        if (node.kind() == Kind.SINGLE) {
            dto.setMenuField(selectedFields(source.getMenuField(), approved));
        } else {
            dto.setModelField(selectedFields(source.getModelField(), approved))
                    .setModelList(selectedFields(source.getModelList(), approved))
                    .setModelSearch(selectedFields(source.getModelSearch(), approved));
        }
    }

    private static String selectedFields(String definitions, Set<String> approved) {
        return ContentModelCodec.write(
                ContentModelCodec.read(definitions).stream()
                        .filter(field -> approved.contains(field.getFieldName()))
                        .toList());
    }

    private void assertAcyclic(Scope scope, int menuId, int parentId) {
        var visited = new HashSet<Integer>();
        visited.add(menuId);
        Integer cursor = parentId;
        while (!Objects.equals(cursor, scope.rootMenuId())) {
            if (cursor == null || !visited.add(cursor) || visited.size() > 150)
                throw conflict("导航父级存在循环或不在本站根下");
            cursor = lock(scope, cursor).getParentId();
        }
    }

    private OneshotEntity lock(Scope scope, Integer id) {
        var menu = menus.lockFieldConfiguration(scope.siteId(), id.longValue());
        if (menu == null
                || Boolean.TRUE.equals(menu.getIsDeleted())
                || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                || !Objects.equals(id, scope.rootMenuId())
                        && !Objects.equals(menu.getSiteRootId(), scope.rootMenuId()))
            throw conflict("目标栏目不属于当前站点或已删除");
        return menu;
    }

    private void save(EditOneshotDto dto, HttpServletRequest request) {
        var result = menuService.updateEdit(dto, request);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()))
            throw conflict(result == null ? "栏目保存失败" : result.getMsg());
    }

    private static boolean hasPublicRoute(OneshotEntity menu) {
        return menu.getListPageUrl() != null && !menu.getListPageUrl().isBlank()
                || menu.getDetailPageUrl() != null && !menu.getDetailPageUrl().isBlank();
    }

    private static int pageType(Kind kind) {
        return kind == Kind.LIST ? 1 : 0;
    }

    private static Model model(Plan plan, String key) {
        return key == null
                ? null
                : plan.proposal().models().stream()
                        .filter(candidate -> key.equals(candidate.key()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("内容模型不在当前整站审核计划中"));
    }

    private static Node withMenu(Node n, Integer menuId, String url, ContentStatus status) {
        return new Node(
                n.key(),
                n.parentKey(),
                n.name(),
                n.order(),
                n.sourcePath(),
                n.evidencePath(),
                n.evidenceSelector(),
                n.sourceHref(),
                n.kind(),
                n.modelKey(),
                n.reuseMenuId(),
                menuId,
                url,
                status,
                n.cmsName(),
                n.drift(),
                n.confirmReuse(),
                n.observations(),
                n.independentLanding());
    }

    private static Kind contentKind(Model model) {
        return "SINGLE".equals(model.kind()) ? Kind.SINGLE : Kind.LIST;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

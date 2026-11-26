package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelNavigationService.*;
import com.xinhongru.cms.template.SiteModelPlanService.Model;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.mapper.OneShotMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SiteModelWorkbenchApplyService {
    private final SiteModelPlanService plans;
    private final SiteModelStructureService structure;
    private final SiteModelNavigationService navigation;
    private final PackageDraftAnalysisService packages;
    private final OneShotMapper menus;
    private final SiteModelPlanStore store;
    private final ObjectMapper json = new ObjectMapper();

    public SiteModelWorkbenchApplyService(
            SiteModelPlanService plans,
            SiteModelStructureService structure,
            SiteModelNavigationService navigation,
            PackageDraftAnalysisService packages,
            OneShotMapper menus,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model")
                    String root) {
        this.plans = plans;
        this.structure = structure;
        this.navigation = navigation;
        this.packages = packages;
        this.menus = menus;
        this.store = new SiteModelPlanStore(root, json);
    }

    public record Request(
            String packageVersion,
            long planRevision,
            long navigationRevision,
            long packageRevision,
            Map<String, String> reuseOwners,
            String token,
            String requestId,
            Boolean confirmed) {}

    public record Issue(String code, String message, String nodeKey, String modelKey) {}

    public record Candidate(String nodeKey, String name, String sourcePath) {}

    public record PlannedModel(
            String modelKey,
            String name,
            Long menuId,
            String ownerNodeKey,
            List<Candidate> candidates) {}

    public record Preview(
            long planRevision,
            long navigationRevision,
            long packageRevision,
            int nodeCount,
            List<PlannedModel> models,
            List<Issue> issues,
            boolean ready,
            String token,
            String pendingRequestId,
            String pendingToken) {}

    public record Result(
            String status,
            long planRevision,
            long navigationRevision,
            long packageRevision,
            List<Node> nodes,
            List<Issue> issues,
            String requestId) {}

    record Intent(
            String requestId,
            String token,
            long packageRevision,
            long baseNavigationRevision,
            List<Node> nodes,
            Map<String, String> owners,
            long planRevision,
            String pendingModel,
            Long reviewedRevision,
            Result result) {}

    private record Prepared(Preview preview, Navigation navigation, Map<String, String> owners) {}

    public ContinuationPreview previewNavigationContinuation(
            Scope scope, String id, ContinuationRequest request, Runnable guard) {
        synchronized (plans) {
            synchronized (packages) {
                guard.run();
                requireNavigationContinuation(scope, id, request.planRevision());
                return navigation.previewContinuation(scope, id, request, guard);
            }
        }
    }

    public Navigation applyNavigationContinuation(
            Scope scope, String id, ContinuationRequest request, Runnable guard) {
        synchronized (plans) {
            synchronized (packages) {
                guard.run();
                requireNavigationContinuation(scope, id, request.planRevision());
                return navigation.applyContinuation(scope, id, request, guard);
            }
        }
    }

    private void requireNavigationContinuation(Scope scope, String id, long planRevision) {
        var intent = stored(scope, id);
        if (intent != null && intent.result() == null)
            throw conflict("已有未完成的应用，请用原请求标识继续");
        var state = packages.recoveredApplicationState(packageScope(scope));
        if (!Objects.equals(state.structurePlanId(), id)
                || !Objects.equals(state.structurePlanRevision(), planRevision)
                || !"READY".equals(state.structureStatus())
                || "RUNNING".equals(state.state()))
            throw conflict("请先将整包接续至当前已确认结构，并等待正在运行的分析结束");
        packages.checkWorkbenchApplication(packageScope(scope), id, state.revision());
    }

    public Preview preview(Scope scope, String id, Request request, Runnable guard) {
        synchronized (plans) {
            synchronized (packages) {
                guard.run();
                var intent = stored(scope, id);
                if (intent == null || intent.result() != null)
                    return prepare(scope, id, request, guard).preview();
                return pendingPreview(scope, id, intent, guard);
            }
        }
    }

    private Preview pendingPreview(Scope scope, String id, Intent intent, Runnable guard) {
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), id);
        var actual = navigation.get(scope, id, guard);
        var receipt = navigation.savedApplicationNavigation(scope, id);
        var pkg = packages.recoveredApplicationState(packageScope(scope));
        boolean stale = pkg.revision() != intent.packageRevision() && !alreadySynchronized(id, plan, receipt, pkg);
        var menuRows = menus.selectActiveSiteModelMenus(scope.siteId());
        var applied = new ArrayList<PlannedModel>();
        for (var model : plan.proposal().models()) {
            if (!intent.owners().containsKey(model.key())) continue;
            for (var menu : menuRows) {
                if (Objects.equals(scope.rootMenuId(), menu.getSiteRootId())
                        && SiteModelStructureService.modelMarker(model.key()).equals(menu.getIdentifies())) {
                    applied.add(new PlannedModel(model.key(), model.name(), menu.getId().longValue(),
                            intent.owners().get(model.key()), List.of()));
                }
            }
        }
        var issues = new ArrayList<Issue>();
        if (stale) {
            issues.add(new Issue("CLOSE_STALE_INTENT", "整包修订已从 " + intent.packageRevision() + " 变为 "
                    + pkg.revision() + "。请核对已写栏目后结束旧确认，再重新预览。", null, null));
        } else {
            issues.add(new Issue("CONFIRMED_RETRY", "已有明确确认且尚未完成的应用，可继续原确认", null, null));
        }
        if (receipt != null && intent.reviewedRevision() != null) {
            issues.add(new Issue("NAVIGATION_CHECKPOINT", "原应用已保存导航修订 "
                    + intent.reviewedRevision() + "，当前导航状态为 " + actual.status(), null, null));
        }
        if (intent.pendingModel() != null) {
            issues.add(new Issue("PENDING_MODEL", "此模型的上次写入需要与当前 CMS 栏目核对", null, intent.pendingModel()));
        }
        return new Preview(plan.revision(), actual.revision(), pkg.revision(), intent.nodes().size(),
                List.copyOf(applied), List.copyOf(issues), false, null, intent.requestId(), intent.token());
    }

    public Result closeStale(Scope scope, String id, Request request, Runnable guard) {
        if (!Boolean.TRUE.equals(request.confirmed())) throw new IllegalArgumentException("请先核对并确认结束旧申请");
        SiteModelPlanStore.checkId(request.requestId());
        synchronized (plans) {
            synchronized (packages) {
                guard.run();
                var prior = closed(scope, id, request.requestId());
                if (prior != null) return checkedResult(prior, request);
                var intent = stored(scope, id);
                if (intent == null || intent.result() != null || !intent.requestId().equals(request.requestId())
                        || !Objects.equals(intent.token(), request.token()))
                    throw conflict("待结束的应用确认已变化，请重新核对");
                var preview = pendingPreview(scope, id, intent, guard);
                if (preview.planRevision() != request.planRevision()
                        || preview.navigationRevision() != request.navigationRevision()
                        || preview.packageRevision() != request.packageRevision()
                        || preview.issues().stream().noneMatch(issue -> "CLOSE_STALE_INTENT".equals(issue.code())))
                    throw conflict("应用恢复范围已变化，请重新核对");
                var result = new Result("CLOSED", preview.planRevision(), preview.navigationRevision(),
                        preview.packageRevision(), navigation.get(scope, id, guard).nodes(), preview.issues(), intent.requestId());
                guard.run();
                store.saveClosedWorkbenchApplication(scope.siteId(), scope.code(), scope.packageVersion(), id,
                        new Intent(intent.requestId(), intent.token(), intent.packageRevision(), intent.baseNavigationRevision(),
                                intent.nodes(), intent.owners(), intent.planRevision(), intent.pendingModel(),
                                intent.reviewedRevision(), result));
                return result;
            }
        }
    }

    public Result apply(
            Scope scope, String id, Request request, HttpServletRequest servlet, Runnable guard) {
        if (!Boolean.TRUE.equals(request.confirmed()) || request.token() == null || request.requestId() == null)
            throw new IllegalArgumentException("请先预览并显式确认结构与导航应用");
        SiteModelPlanStore.checkId(request.requestId());
        synchronized (plans) {
            synchronized (packages) {
                guard.run();
                var closed = closed(scope, id, request.requestId());
                if (closed != null) return checkedResult(closed, request);
                Intent intent = stored(scope, id);
                if (intent == null || !intent.requestId().equals(request.requestId())) {
                    if (intent != null && intent.result() == null)
                        throw conflict("已有未完成的应用，请用原请求标识继续");
                    var data = prepare(scope, id, request, guard);
                    if (!data.preview().ready())
                        return result(
                                "BLOCKED", request, data.navigation(), data.preview().issues());
                    if (!Objects.equals(data.preview().token(), request.token()))
                        throw conflict("预览范围已变化，请重新读取后确认");
                    intent =
                            new Intent(
                                    request.requestId(),
                                    request.token(),
                                    request.packageRevision(),
                                    data.navigation().revision(),
                                    data.navigation().nodes(),
                                    data.owners(),
                                    request.planRevision(),
                                    null,
                                    data.navigation().status() == Status.APPLIED || data.navigation().status() == Status.APPLYING
                                            ? data.navigation().revision()
                                            : null,
                                    null);
                    save(scope, id, intent);
                } else if (!Objects.equals(request.token(), intent.token())) {
                    throw conflict("确认回执与原请求不符");
                }
                if (intent.result() != null) return intent.result();
                return execute(scope, id, intent, servlet, guard);
            }
        }
    }

    private Prepared prepare(Scope scope, String id, Request input, Runnable guard) {
        guard.run();
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), id);
        var nav = navigation.get(scope, id, guard);
        var draft = packages.recoveredApplicationState(packageScope(scope));
        var issues = new ArrayList<Issue>();
        if (input.planRevision() != plan.revision()
                || input.navigationRevision() != nav.revision()
                || input.packageRevision() != draft.revision())
            issues.add(new Issue("STALE_REVISION", "结构、导航或整包修订已变化，请重新读取", null, null));
        if (!"CONFIRMED".equals(plan.status()))
            issues.add(new Issue("STRUCTURE_UNCONFIRMED", "请先确认整站结构", null, null));
        if (!id.equals(draft.structurePlanId()) || "RUNNING".equals(draft.state()))
            issues.add(new Issue("PACKAGE_NOT_READY", "请先接续本结构，等待整包任务结束", null, null));
        var original = navigation.savedApplicationNavigation(scope, id);
        if (original != null && original.status() == Status.APPLYING) {
            var previous = store.workbenchApplication(scope.siteId(), scope.code(), scope.packageVersion(), id);
            if (previous != null && closed(scope, id, previous.requestId()) != null
                    && previous.pendingModel() == null && previous.planRevision() == plan.revision()
                    && Objects.equals(previous.reviewedRevision(), original.revision())
                    && sameBlueprint(targetNodes(scope, plan, previous), original.nodes()))
                return prepared(plan, nav, draft.revision(), List.of(), issues, Map.of());
        }
        if (original == null
                || original.status() == Status.DRAFT
                || original.status() == Status.APPLYING
                || !sameSourceNodes(original.nodes(), nav.nodes())
                || nav.supplementalEvidence().stream()
                        .anyMatch(entry -> !entry.keepsOriginalLink(nav.supplementalEvidence())))
            issues.add(new Issue("NAVIGATION_UNCONFIRMED", "请先审核完整导航和新增证据", null, null));
        if (nav.nodes().isEmpty()) issues.add(new Issue("NO_NAVIGATION", "没有可应用的导航节点", null, null));
        if (nav.status() == Status.APPLIED) {
            if (nav.nodes().stream().anyMatch(Node::drift) || nav.planRevision() != plan.revision())
                issues.add(new Issue("APPLIED_DRIFT", "已应用导航与当前结构或实际栏目不一致，请核对", null, null));
            return prepared(plan, nav, draft.revision(), List.of(), issues, Map.of());
        }
        var menuRows = menus.selectActiveSiteModelMenus(scope.siteId());
        if (menuRows.size() > 1000)
            issues.add(new Issue("MENU_LIMIT", "站点现有栏目数量超过一次应用容量", null, null));
        var modelByKey = models(plan);
        var eligible = navigation.applicationReuseKeys(scope, plan, nav.nodes());
        var used =
                nav.nodes().stream()
                        .filter(
                                node ->
                                        node.modelKey() != null
                                                && !Objects.equals(
                                                        scope.rootMenuId(), node.reuseMenuId()))
                        .collect(
                                Collectors.groupingBy(
                                        Node::modelKey, LinkedHashMap::new, Collectors.toList()));
        var choices = input.reuseOwners() == null ? Map.<String, String>of() : input.reuseOwners();
        var owners = new LinkedHashMap<String, String>();
        var modelPreviews = new ArrayList<PlannedModel>();
        var claimedMenus = new HashSet<Long>();
        for (var entry : used.entrySet()) {
            var model = modelByKey.get(entry.getKey());
            if (model == null) {
                issues.add(
                        new Issue("MODEL_UNAVAILABLE", "导航引用的模型在当前计划中不存在", null, entry.getKey()));
                continue;
            }
            var candidates =
                    entry.getValue().stream()
                            .filter(node -> eligible.contains(node.key()))
                            .toList();
            var selected = owner(candidates, choices.get(entry.getKey()));
            if (selected == null) {
                issues.add(
                        new Issue(
                                candidates.isEmpty() ? "NO_OWNER_EVIDENCE" : "REUSE_OWNER_REQUIRED",
                                candidates.isEmpty()
                                        ? "模型没有具备同原页证据的可复用导航节点"
                                        : "同模型有多个候选，请选择实际复用栏目归属",
                                null,
                                model.key()));
            } else {
                owners.put(model.key(), selected.key());
                if (model.menuId() != null) {
                    var target = menus.getModelById(model.menuId().intValue());
                    if (!claimedMenus.add(model.menuId())
                            || target == null
                            || !Objects.equals(scope.siteId(), target.getSiteProjectId())
                            || !Objects.equals(scope.rootMenuId(), target.getSiteRootId())
                            || Boolean.TRUE.equals(target.getIsDeleted())
                            || !SiteModelNavigationService.canReuseApplicationMenu(
                                    scope,
                                    SiteModelNavigationService.withApplicationReuse(
                                            selected, model.menuId().intValue()),
                                    target))
                        issues.add(
                                new Issue(
                                        "MENU_REUSE_CONFLICT",
                                        "现有栏目已重复占用、已公开或与审核模型标识不符",
                                        selected.key(),
                                        model.key()));
                } else if (menuRows.stream()
                        .anyMatch(
                                row ->
                                        model.name().equalsIgnoreCase(row.getClassName())
                                                && !SiteModelStructureService.modelMarker(
                                                                model.key())
                                                        .equals(row.getIdentifies()))) {
                    issues.add(
                            new Issue(
                                    "MODEL_NAME_CONFLICT",
                                    "存在同名的其他栏目，请先确认实际模型归属",
                                    selected.key(),
                                    model.key()));
                }
                if (model.menuId() == null
                        && model.name().codePointCount(0, model.name().length()) > 64)
                    issues.add(
                            new Issue(
                                    "MODEL_NAME_LIMIT",
                                    "栏目业务名超过64字符",
                                    selected.key(),
                                    model.key()));
            }
            modelPreviews.add(
                    new PlannedModel(
                            model.key(),
                            model.name(),
                            model.menuId(),
                            selected == null ? null : selected.key(),
                            candidates.stream()
                                    .map(
                                            node ->
                                                    new Candidate(
                                                            node.key(),
                                                            node.name(),
                                                            node.sourcePath()))
                                    .toList()));
        }
        if (!used.keySet().containsAll(choices.keySet()))
            issues.add(new Issue("INVALID_OWNER", "复用选择引用了当前导航之外的模型", null, null));
        var knownParents = new HashMap<String, Integer>();
        for (var node : nav.nodes()) {
            if (Objects.equals(scope.rootMenuId(), node.reuseMenuId())) {
                knownParents.put(node.key(), scope.rootMenuId());
                var rootModel = modelByKey.get(node.modelKey());
                // 首页入口始终复用根栏目。与其他栏目一样，独立首页内容模型需要有自己的维护栏目。
                if (rootModel != null && !used.containsKey(rootModel.key())
                        && !Objects.equals(rootModel.menuId(), scope.rootMenuId().longValue())) {
                    if (rootModel.menuId() == null && menuRows.stream().anyMatch(row ->
                            rootModel.name().equalsIgnoreCase(row.getClassName())
                            && !SiteModelStructureService.modelMarker(rootModel.key()).equals(row.getIdentifies()))) {
                        issues.add(new Issue("MODEL_NAME_CONFLICT", "存在同名栏目，请先核对首页内容模型归属",
                                node.key(), rootModel.key()));
                    } else if (rootModel.menuId() != null && menuRows.stream().noneMatch(row ->
                            rootModel.menuId().equals(row.getId().longValue())
                            && Objects.equals(row.getSiteRootId(), scope.rootMenuId())
                            && SiteModelStructureService.modelMarker(rootModel.key()).equals(row.getIdentifies()))) {
                        issues.add(new Issue("MENU_REUSE_CONFLICT", "首页维护栏目与当前结构回执不一致",
                                node.key(), rootModel.key()));
                    } else {
                        owners.put(rootModel.key(), node.key());
                        modelPreviews.add(new PlannedModel(rootModel.key(), rootModel.name(),
                                rootModel.menuId(), node.key(), List.of(new Candidate(node.key(),
                                "首页的独立内容栏目", node.sourcePath()))));
                    }
                }
                continue;
            }
            var model = modelByKey.get(node.modelKey());
            if (model != null && Objects.equals(owners.get(model.key()), node.key())) {
                if (model.menuId() != null) knownParents.put(node.key(), model.menuId().intValue());
                continue;
            }
            Integer parent =
                    node.parentKey() == null
                            ? scope.rootMenuId()
                            : knownParents.get(node.parentKey());
            if (parent != null
                    && menuRows.stream()
                            .anyMatch(
                                    row ->
                                            Objects.equals(row.getParentId(), parent)
                                                    && node.name().equals(row.getClassName())
                                                    && !("site_nav_" + node.key())
                                                            .equals(row.getIdentifies())))
                issues.add(
                        new Issue(
                                "NAVIGATION_NAME_CONFLICT",
                                "同级存在非本导航的同名栏目，须先核对",
                                node.key(),
                                node.modelKey()));
        }
        guard.run();
        return prepared(plan, nav, draft.revision(), modelPreviews, issues, owners);
    }

    private static Node owner(List<Node> candidates, String explicit) {
        if (explicit != null)
            return candidates.stream()
                    .filter(node -> explicit.equals(node.key()))
                    .findFirst()
                    .orElse(null);
        var reviewed = candidates.stream().filter(node -> node.reuseMenuId() != null).toList();
        return reviewed.size() == 1
                ? reviewed.getFirst()
                : candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private Prepared prepared(
            Plan plan,
            Navigation nav,
            long packageRevision,
            List<PlannedModel> modelPreviews,
            List<Issue> issues,
            Map<String, String> owners) {
        String token =
                issues.isEmpty()
                        ? UUID.nameUUIDFromBytes(
                                        json.writeValueAsString(
                                                        Arrays.asList(
                                                                plan.id(),
                                                                plan.packageVersion(),
                                                                plan.revision(),
                                                                nav.revision(),
                                                                packageRevision,
                                                                nav.nodes(),
                                                                new TreeMap<>(owners)))
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                        : null;
        return new Prepared(
                new Preview(
                        plan.revision(),
                        nav.revision(),
                        packageRevision,
                        nav.nodes().size(),
                        List.copyOf(modelPreviews),
                        List.copyOf(issues),
                        issues.isEmpty(),
                        token,
                        null,
                        null),
                nav,
                Collections.unmodifiableMap(new LinkedHashMap<>(owners)));
    }

    private Result execute(
            Scope scope, String id, Intent original, HttpServletRequest servlet, Runnable guard) {
        Intent intent = original;
        String phase = "STRUCTURE";
        Long navRevision = intent.reviewedRevision();
        try {
            var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), id);
            var receipt = navigation.savedApplicationNavigation(scope, id);
            var pkg = packages.recoveredApplicationState(packageScope(scope));
            if (!alreadySynchronized(id, plan, receipt, pkg))
                packages.checkWorkbenchApplication(
                        packageScope(scope), id, intent.packageRevision());
            guard.run();
            if (!"CONFIRMED".equals(plan.status())) throw conflict("结构确认状态已变化，请重新核对");
            if (navRevision == null
                    && (receipt == null || receipt.revision() != intent.baseNavigationRevision())
                    && (receipt == null
                            || receipt.revision() != intent.baseNavigationRevision() + 1
                            || intent.pendingModel() != null
                            || receipt.planRevision() != plan.revision()
                            || !sameBlueprint(targetNodes(scope, plan, intent), receipt.nodes())))
                throw conflict("原导航已被其他操作修改，请核对");
            if (intent.pendingModel() != null) {
                validatePending(plan, scope, intent);
                plan =
                        materialize(
                                scope,
                                id,
                                intent.pendingModel(),
                                intent.planRevision(),
                                servlet,
                                guard);
                intent = checkpoint(scope, id, intent, plan.revision(), null, navRevision, null);
            }
            if (plan.revision() != intent.planRevision()) throw conflict("结构计划已被其他操作修改，请核对");
            for (String modelKey : intent.owners().keySet()) {
                if (models(plan).get(modelKey).menuId() != null) continue;
                intent =
                        checkpoint(scope, id, intent, plan.revision(), modelKey, navRevision, null);
                plan = materialize(scope, id, modelKey, plan.revision(), servlet, guard);
                intent = checkpoint(scope, id, intent, plan.revision(), null, navRevision, null);
            }
            phase = "NAVIGATION_REVIEW";
            var nodes = targetNodes(scope, plan, intent);
            if (navRevision == null) {
                var current = navigation.savedApplicationNavigation(scope, id);
                if (current != null
                        && current.revision() == intent.baseNavigationRevision() + 1
                        && current.planRevision() == plan.revision()
                        && sameBlueprint(nodes, current.nodes())) navRevision = current.revision();
                else
                    navRevision =
                            navigation
                                    .review(
                                            scope,
                                            id,
                                            intent.baseNavigationRevision(),
                                            nodes,
                                            guard)
                                    .revision();
                intent = checkpoint(scope, id, intent, plan.revision(), null, navRevision, null);
            }
            phase = "NAVIGATION_APPLY";
            var saved = navigation.savedApplicationNavigation(scope, id);
            if (saved == null
                    || saved.revision() != navRevision
                    || !sameBlueprint(nodes, saved.nodes())) throw conflict("待应用的导航与本次确认回执不一致");
            navigation.apply(scope, id, navRevision, servlet, guard);
            var actual = navigation.get(scope, id, guard);
            if (actual.status() != Status.APPLIED
                    || actual.planRevision() != plan.revision()
                    || !sameBlueprint(nodes, actual.nodes())
                    || actual.nodes().stream()
                            .anyMatch(node -> node.drift() || node.menuId() == null)
                    || actual.nodes().stream().map(Node::menuId).distinct().count()
                            != actual.nodes().size()) throw conflict("已应用导航与实际栏目不同，需要核对");
            phase = "PACKAGE_SYNC";
            var synchronizedState =
                    packages.syncWorkbenchApplication(
                            packageScope(scope), plan, intent.packageRevision(), guard);
            var result =
                    new Result(
                            "APPLIED",
                            plan.revision(),
                            navRevision,
                            synchronizedState.revision(),
                            actual.nodes(),
                            List.of(),
                            intent.requestId());
            checkpoint(scope, id, intent, plan.revision(), null, navRevision, result);
            return result;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 403)
                throw failure;
            return partial(intent, navRevision, phase, failure.getReason());
        } catch (IllegalArgumentException invalid) {
            return partial(intent, navRevision, phase, invalid.getMessage());
        } catch (IllegalStateException failure) {
            return partial(intent, navRevision, phase, "操作未完成，请核对当前模型或导航证据后使用原确认重试");
        }
    }

    private void validatePending(Plan latest, Scope scope, Intent intent) {
        if (latest.revision() == intent.planRevision()) return;
        if (latest.revision() != intent.planRevision() + 1) throw conflict("中断期间结构已修改，请重新核对");
        var old =
                plans.get(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        latest.id(),
                        intent.planRevision());
        var before = old.proposal();
        var after = latest.proposal();
        if (!Objects.equals(old.catalogDigest(), latest.catalogDigest())
                || !Objects.equals(before.summary(), after.summary())
                || !before.regions().equals(after.regions())
                || !before.relations().equals(after.relations())
                || !before.issues().equals(after.issues())
                || !before.pages().equals(after.pages())
                || !before.models().stream()
                        .filter(m -> !m.key().equals(intent.pendingModel()))
                        .toList()
                        .equals(
                                after.models().stream()
                                        .filter(m -> !m.key().equals(intent.pendingModel()))
                                        .toList())) throw conflict("中断期间出现其他结构修订，请重新核对");
    }

    private Plan materialize(
            Scope scope,
            String id,
            String key,
            long revision,
            HttpServletRequest servlet,
            Runnable guard) {
        var target =
                new SiteModelStructureService.Scope(
                        scope.siteId(), scope.code(), scope.packageVersion(), scope.rootMenuId());
        return structure
                .apply(
                        target,
                        id,
                        new SiteModelStructureService.Apply(revision, key, null),
                        servlet,
                        guard)
                .plan();
    }

    private static List<Node> targetNodes(Scope scope, Plan plan, Intent intent) {
        var models = models(plan);
        if (intent.owners().isEmpty()) return intent.nodes();
        return intent.nodes().stream()
                .map(
                        node -> {
                            if (Objects.equals(node.reuseMenuId(), scope.rootMenuId())
                                    || node.modelKey() == null) return node;
                            var model = models.get(node.modelKey());
                            if (model == null || model.menuId() == null)
                                throw conflict("确认的模型尚无有效栏目");
                            return SiteModelNavigationService.withApplicationReuse(
                                    node,
                                    node.key().equals(intent.owners().get(node.modelKey()))
                                            ? model.menuId().intValue()
                                            : null);
                        })
                .toList();
    }

    private static boolean sameBlueprint(List<Node> planned, List<Node> actual) {
        if (planned.size() != actual.size()) return false;
        for (int i = 0; i < planned.size(); i++) {
            var a = planned.get(i);
            var b = actual.get(i);
            if (!Objects.equals(a.key(), b.key())
                    || !Objects.equals(a.parentKey(), b.parentKey())
                    || !Objects.equals(a.name(), b.name())
                    || a.order() != b.order()
                    || !Objects.equals(a.sourcePath(), b.sourcePath())
                    || a.kind() != b.kind()
                    || !Objects.equals(a.modelKey(), b.modelKey())
                    || !Objects.equals(a.reuseMenuId(), b.reuseMenuId())) return false;
        }
        return true;
    }

    private static boolean sameSourceNodes(List<Node> left, List<Node> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            var a = left.get(i);
            var b = right.get(i);
            if (!Objects.equals(a.key(), b.key())
                    || !Objects.equals(a.parentKey(), b.parentKey())
                    || !Objects.equals(a.name(), b.name())
                    || a.order() != b.order()
                    || !Objects.equals(a.sourcePath(), b.sourcePath())
                    || !Objects.equals(a.evidencePath(), b.evidencePath())
                    || !Objects.equals(a.evidenceSelector(), b.evidenceSelector())
                    || !Objects.equals(a.sourceHref(), b.sourceHref())) return false;
        }
        return true;
    }

    private static Result result(
            String status, Request request, Navigation nav, List<Issue> issues) {
        return new Result(
                status,
                request.planRevision(),
                nav.revision(),
                request.packageRevision(),
                nav.nodes(),
                issues,
                request.requestId());
    }

    private static Result partial(
            Intent intent, Long navigationRevision, String phase, String detail) {
        return new Result(
                "PARTIAL",
                intent.planRevision(),
                navigationRevision == null ? intent.baseNavigationRevision() : navigationRevision,
                intent.packageRevision(),
                List.of(),
                List.of(new Issue(phase, detail, null, intent.pendingModel())),
                intent.requestId());
    }

    private Intent checkpoint(
            Scope scope,
            String id,
            Intent previous,
            long rev,
            String pending,
            Long navRev,
            Result result) {
        var next =
                new Intent(
                        previous.requestId(),
                        previous.token(),
                        previous.packageRevision(),
                        previous.baseNavigationRevision(),
                        previous.nodes(),
                        previous.owners(),
                        rev,
                        pending,
                        navRev,
                        result);
        save(scope, id, next);
        return next;
    }

    private void save(Scope scope, String id, Intent intent) {
        store.saveWorkbenchApplication(
                scope.siteId(), scope.code(), scope.packageVersion(), id, intent);
    }

    private Intent stored(Scope scope, String id) {
        var active = store.workbenchApplication(scope.siteId(), scope.code(), scope.packageVersion(), id);
        return active == null || closed(scope, id, active.requestId()) != null ? null : active;
    }

    private Intent closed(Scope scope, String id, String requestId) {
        return store.closedWorkbenchApplication(scope.siteId(), scope.code(), scope.packageVersion(), id, requestId);
    }

    private static Result checkedResult(Intent intent, Request request) {
        if (!Objects.equals(request.token(), intent.token())) throw conflict("确认回执与原请求不符");
        return intent.result();
    }

    private static boolean alreadySynchronized(
            String id, Plan plan, Navigation receipt, PackageDraftAnalysisService.State pkg) {
        return receipt != null && receipt.status() == Status.APPLIED && Objects.equals(pkg.structurePlanId(), id)
                && Objects.equals(pkg.structurePlanRevision(), plan.revision());
    }

    private static PackageDraftAnalysisService.Scope packageScope(Scope scope) {
        return new PackageDraftAnalysisService.Scope(
                scope.siteId(), scope.code(), scope.packageVersion());
    }

    private static Map<String, Model> models(Plan plan) {
        return plan.proposal().models().stream()
                .collect(Collectors.toMap(Model::key, Function.identity()));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

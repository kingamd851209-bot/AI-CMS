package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelNavigationService.Kind;
import com.xinhongru.cms.template.SiteModelNavigationService.Node;
import com.xinhongru.cms.template.SiteModelNavigationService.Scope;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ParentEntryDecisionService {
    private final SiteModelPlanService plans;
    private final SiteModelNavigationService navigation;
    private final SiteWorkbenchContextService documents;
    private final ObjectMapper json = new ObjectMapper();

    public ParentEntryDecisionService(
            SiteModelPlanService plans,
            SiteModelNavigationService navigation,
            SiteWorkbenchContextService documents) {
        this.plans = plans;
        this.navigation = navigation;
        this.documents = documents;
    }

    public enum Role {
        DIRECTORY,
        REDIRECT,
        INDEPENDENT_OVERVIEW
    }

    public record Save(
            String packageVersion,
            long planRevision,
            long navigationRevision,
            Long expectedRevision,
            String nodeKey,
            Role role,
            String targetNodeKey) {}

    public record Item(
            String nodeKey, Role role, String targetNodeKey, boolean valid, String reason) {}

    public record Result(long revision, List<Item> items) {}

    private record Decision(String nodeKey, Role role, String targetNodeKey) {}

    private record Stored(long revision, List<Decision> items) {}

    private record Context(
            String fileScope,
            Map<String, Node> nodes,
            SiteWorkbenchContextService.Document document,
            Stored stored) {}

    public Result read(
            Scope scope,
            String planId,
            String version,
            long planRevision,
            long navigationRevision,
            Runnable guard) {
        synchronized (plans) {
            var context =
                    load(scope, planId, version, planRevision, navigationRevision, false, guard);
            return result(context, scope, context.stored());
        }
    }

    // 公开编译可能已锁站点行，仅读取原子决定文件及调用方已核对的导航快照。
    Result readForPublication(
            Scope scope, String planId, long planRevision, SiteModelNavigationService.Navigation view) {
        if (!navigation.hasOwnAppliedRevision(scope, planId, view, view.planRevision()))
            return new Result(0, List.of());
        var context = storedContext(scope, planId, planRevision, view.revision(), view.nodes());
        return result(context, scope, context.stored());
    }

    public Result save(Scope scope, String planId, Save request, Runnable guard) {
        if (request == null
                || !validKey(request.nodeKey())
                || request.role() == null
                || request.expectedRevision() == null
                || request.expectedRevision() < 0
                || request.role() == Role.REDIRECT && !validKey(request.targetNodeKey())
                || request.role() != Role.REDIRECT && request.targetNodeKey() != null)
            throw new IllegalArgumentException("请提供有效父入口、职责、目标与原决定修订");
        synchronized (plans) {
            var context =
                    load(
                            scope,
                            planId,
                            request.packageVersion(),
                            request.planRevision(),
                            request.navigationRevision(),
                            true,
                            guard);
            var old = context.stored();
            if (old.revision() != request.expectedRevision()) throw conflict("入口职责已变化，请重新读取后保存");
            var decisions = decisions(old);
            var next = new Decision(request.nodeKey(), request.role(), request.targetNodeKey());
            var previous = decisions.put(next.nodeKey(), next);
            String reason = check(context, scope, next, decisions);
            if (reason != null) throw new IllegalArgumentException(reason);
            if (next.equals(previous)) return result(context, scope, old);

            var updated =
                    new Stored(Math.addExact(old.revision(), 1), List.copyOf(decisions.values()));
            guard.run();
            documents.saveParentEntryDecisions(
                    scope.siteId(),
                    scope.code(),
                    context.fileScope(),
                    json.writeValueAsString(updated),
                    context.document().digest());
            return result(context, scope, updated);
        }
    }

    private Context load(
            Scope scope,
            String planId,
            String version,
            long planRevision,
            long navigationRevision,
            boolean saving,
            Runnable guard) {
        if (!Objects.equals(scope.packageVersion(), version)) throw conflict("页面包版本已变化");
        guard.run();
        var plan = plans.get(scope.siteId(), scope.code(), version, planId);
        var original = storedContext(scope, planId, planRevision, navigationRevision, List.of());
        var current =
                saving || !original.stored().items().isEmpty()
                        ? navigation.get(scope, planId, guard)
                        : navigation.appliedSnapshot(scope, planId);
        if (!saving && original.stored().items().isEmpty() && current.originPlanId() != null
                && current.planRevision() != planRevision) {
            // 接续签收保留原导航文件，读取已核验的当前结构视图。
            current = navigation.get(scope, planId, guard);
        }
        if (!"CONFIRMED".equals(plan.status())
                || plan.revision() != planRevision
                || current.revision() != navigationRevision
                || !navigation.hasOwnAppliedRevision(scope, planId, current, planRevision))
            throw conflict("确认计划或已应用导航已变化，请重新读取");
        return new Context(
                original.fileScope(),
                current.nodes().stream().collect(Collectors.toMap(Node::key, Function.identity())),
                original.document(),
                original.stored());
    }

    private Context storedContext(
            Scope scope, String planId, long planRevision, long navigationRevision, List<Node> nodes) {
        String fileScope =
                json.writeValueAsString(List.of(scope.packageVersion(), planId, planRevision, navigationRevision));
        var document = documents.parentEntryDecisions(scope.siteId(), scope.code(), fileScope);
        var stored = document.present()
                ? json.readValue(document.content(), Stored.class) : new Stored(0, List.of());
        return new Context(fileScope,
                nodes.stream().collect(Collectors.toMap(Node::key, Function.identity())), document, stored);
    }

    private static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 128;
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static Map<String, Decision> decisions(Stored stored) {
        var index = new LinkedHashMap<String, Decision>();
        for (var decision : stored.items()) index.put(decision.nodeKey(), decision);
        return index;
    }

    private static Result result(Context context, Scope scope, Stored stored) {
        var decisions = decisions(stored);
        return new Result(
                stored.revision(),
                stored.items().stream()
                        .map(
                                decision -> {
                                    String problem = check(context, scope, decision, decisions);
                                    return new Item(
                                            decision.nodeKey(),
                                            decision.role(),
                                            decision.targetNodeKey(),
                                            problem == null,
                                            problem);
                                })
                        .toList());
    }

    private static String check(
            Context context, Scope scope, Decision decision, Map<String, Decision> decisions) {
        var node = context.nodes().get(decision.nodeKey());
        if (node == null
                || node.menuId() == null
                || node.drift()
                || node.menuId().equals(scope.rootMenuId())
                || context.nodes().values().stream()
                        .noneMatch(
                                child ->
                                        node.key().equals(child.parentKey())
                                                && child.menuId() != null
                                                && !child.drift()))
            return "此入口当前没有已应用的子栏目，或与栏目配置不一致";
        if (decision.role() == Role.INDEPENDENT_OVERVIEW
                && (node.sourcePath() == null || node.sourcePath().isBlank()))
            return "独立概览需要可核实的原始页面";
        if (decision.role() != Role.REDIRECT) return null;

        var visited = new HashSet<String>();
        visited.add(node.key());
        for (String key = decision.targetNodeKey(); ; ) {
            if (!visited.add(key)) return "入口跳转构成循环，请改选目标";
            var target = context.nodes().get(key);
            var destination = decisions.get(key);
            if (target == null
                    || target.drift()
                    || target.menuId() == null
                    || target.kind() == Kind.DIRECTORY
                    || target.sourcePath() == null
                    || target.sourcePath().isBlank()
                    || destination != null && destination.role() == Role.DIRECTORY)
                return "跳转目标不是当前已应用的有效页面入口";
            if (destination == null || destination.role() != Role.REDIRECT) return null;
            key = destination.targetNodeKey();
        }
    }
}

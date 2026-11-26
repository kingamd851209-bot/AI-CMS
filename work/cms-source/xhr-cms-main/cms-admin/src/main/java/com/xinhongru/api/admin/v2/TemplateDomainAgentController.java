package com.xinhongru.api.admin.v2;

import com.xinhongru.ai.generation.AiGenerationTasks;
import com.xinhongru.ai.generation.AiTaskEvent;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.TemplateDomainAgentService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.UUID;

/** Internal, read-only domain planning. It never invokes a content/model write operation. */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true")
@RequestMapping("/api/admin/v2/sites/{siteId}/template-seed/domain-agent-tasks")
public class TemplateDomainAgentController {
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final AiGenerationTasks tasks;
    private final TemplateDomainAgentService agent;
    private final ObjectMapper json = new ObjectMapper();

    public TemplateDomainAgentController(
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions,
            AiGenerationTasks tasks,
            TemplateDomainAgentService agent) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.tasks = tasks;
        this.agent = agent;
    }

    public record Request(
            String sourcePath, Long menuId, String requestId, TemplateDomainAgentService.Chunk chunk) {}

    @GetMapping("/recognition-evidence")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateDomainPageEvidence.Evidence> recognition(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam String sourcePath,
            @RequestParam(required = false) Long menuId, @RequestParam(required = false) String digest,
            @RequestParam(required = false) String packageVersion) {
        var project = authorizedSite(request, siteId);
        if (sourcePath.isBlank() || sourcePath.length() > 1024
                || menuId != null && (menuId <= 0 || menuId > Integer.MAX_VALUE))
            throw new IllegalArgumentException("请选择有效页面和目标栏目");
        var code = project.getProjectCode();
        var version = project.getUiPackageVersion();
        if (packageVersion != null && !Objects.equals(version, packageVersion))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "页面包已变化，请重新读取");
        var actorId = actor(request);
        checkCurrentActorAndProject(actorId, siteId, code, version);
        com.xinhongru.cms.template.TemplateDomainPageEvidence.Evidence evidence;
        try {
            evidence = agent.recognition(siteId, code, version, sourcePath, menuId, digest);
        } catch (SecurityException unavailableMenu) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法在该站点核对所选栏目");
        }
        checkCurrentActorAndProject(actorId, siteId, code, version);
        return AdminApiResponse.success(evidence);
    }

    @PostMapping
    public AdminApiResponse<AiGenerationTasks.Snapshot> start(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody Request body) {
        var project = authorizedSite(request, siteId);
        int actorId = actor(request);
        if (body == null
                || body.sourcePath() == null
                || body.sourcePath().isBlank()
                || body.sourcePath().length() > 1024
                || body.menuId() != null && body.menuId() <= 0)
            throw new IllegalArgumentException("请选择有效页面和目标栏目");
        String code = project.getProjectCode();
        String version = project.getUiPackageVersion();
        Runnable guard = () -> checkCurrentActorAndProject(actorId, siteId, code, version);
        guard.run();
        if (body.chunk() != null && !Objects.equals(body.chunk().packageVersion(), version))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "分段所属页面包、摘要或人工修订已变化，请重新开始");
        var scope = new java.util.ArrayList<Object>(java.util.Arrays.asList(
                code, version, body.sourcePath(), body.menuId()));
        if (body.chunk() != null) scope.add(body.chunk());
        var task =
                tasks.create(
                        actorId,
                        siteId,
                        AiUsage.WORKBENCH_DOMAIN_AGENT,
                        body.requestId() == null ? UUID.randomUUID().toString() : body.requestId(),
                        json.writeValueAsString(scope));
        synchronized (task) {
            if (task.state().equals("pending")) {
                tasks.start(
                        task,
                        Mono.fromCallable(
                                        () -> {
                                            try {
                                                var result =
                                                        agent.analyze(
                                                                siteId,
                                                                code,
                                                                version,
                                                                body.sourcePath(),
                                                                body.menuId(),
                                                                body.chunk(),
                                                                task,
                                                                guard);
                                                return task.completeWith(
                                                        () -> {
                                                            guard.run();
                                                            return json.writeValueAsString(result);
                                                        });
                                            } catch (RuntimeException failure) {
                                                reportFailure(task, failure);
                                                throw failure;
                                            }
                                        })
                                .map(AiTaskEvent::delta)
                                .flux());
            }
        }
        return AdminApiResponse.success(task.snapshot());
    }

    @GetMapping("/{taskId}")
    public AdminApiResponse<AiGenerationTasks.Snapshot> get(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        return AdminApiResponse.success(owned(request, siteId, taskId, true).snapshot());
    }

    @DeleteMapping("/{taskId}")
    public AdminApiResponse<AiGenerationTasks.Snapshot> cancel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        var task = owned(request, siteId, taskId, false);
        tasks.cancel(task);
        return AdminApiResponse.success(task.snapshot());
    }

    @GetMapping(value = "/{taskId}/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var task = owned(request, siteId, taskId, true);
        return com.xinhongru.ai.generation.AgentTaskProgressSse.open(
                task, lastEventId, () -> owned(request, siteId, taskId, true));
    }

    private AiGenerationTasks.Task owned(
            HttpServletRequest request, Long siteId, String taskId, boolean requireCurrentPackage) {
        var project = authorizedSite(request, siteId);
        int actorId = actor(request);
        if (!permissions.snapshotForActor(actorId, siteId).isAll()) denied();
        var task = tasks.require(taskId, actorId);
        if (!Objects.equals(task.siteId, siteId)
                || task.usage != AiUsage.WORKBENCH_DOMAIN_AGENT
                || task.inputKey().startsWith("site-model:"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "领域Agent任务不存在");
        if (requireCurrentPackage) {
            var scope = json.readTree(task.inputKey());
            if (!scope.isArray() || !Objects.equals(scope.path(0).asText(), project.getProjectCode())
                    || !Objects.equals(scope.path(1).asText(), project.getUiPackageVersion())
                    || project.getRootMenuId() == null
                    || Integer.valueOf(1).equals(project.getDeleted()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "任务属于旧页面包，请重新开始分析；旧任务仍可取消");
        }
        return task;
    }

    private SiteProjectEntity authorizedSite(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var project = projects.selectById(siteId);
        if (project == null) throw new IllegalArgumentException("站点不存在");
        return project;
    }

    private int actor(HttpServletRequest request) {
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) denied();
        return user.getId();
    }

    private void checkCurrentActorAndProject(
            Integer actorId, Long siteId, String code, String version) {
        if (!permissions.snapshotForActor(actorId, siteId).isAll()) denied();
        var current = projects.selectById(siteId);
        if (current == null
                || Integer.valueOf(1).equals(current.getDeleted())
                || current.getRootMenuId() == null
                || !Objects.equals(code, current.getProjectCode())
                || !Objects.equals(version, current.getUiPackageVersion()))
            throw new IllegalStateException("站点或页面包已变化，请重新开始分析");
    }

    private void denied() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权执行内部Agent任务");
    }

    private void reportFailure(AiGenerationTasks.Task task, RuntimeException failure) {
        if (task.terminal()) return;
        String message = failure.getMessage();
        var safe =
                java.util.Set.of(
                        "Agent历史输入超限",
                        "Agent动作输出超限",
                        "工具结果超限",
                        "Agent轮次达到上限，方案未完成",
                        "连续无效动作达到上限",
                        "未知或禁止的Agent工具",
                        "页面来源、人工方案或栏目配置已变化，请重新启动",
                        "分段所属页面包、摘要或人工修订已变化，请重新开始",
                        "分段证据或栏目模型已变化，请重新开始",
                        "分段起点超出本页可识别来源范围",
                        "站点或页面包已变化，请重新开始分析",
                        "栏目字段配置超出只读上下文限制",
                        "Agent已取消或超时");
        if (message == null || !safe.contains(message))
            message =
                    failure instanceof SecurityException
                                    || failure instanceof ResponseStatusException
                            ? "权限或任务范围检查未通过，已停止"
                            : "模型调用或工具执行未完成，未保存建议";
        var steps = new java.util.ArrayList<java.util.Map<String, Object>>();
        try {
            var progress = json.readTree(task.snapshot().progress());
            for (var step : progress.path("steps")) {
                steps.add(
                        java.util.Map.of(
                                "round",
                                step.path("round").asInt(),
                                "tool",
                                step.path("tool").asText(),
                                "status",
                                step.path("status").asText(),
                                "message",
                                step.path("message").asText()));
            }
        } catch (RuntimeException ignored) {
            // No prior step exists if configuration or scope validation failed before the first
            // tool.
        }
        int round = steps.size() + 1;
        steps.add(
                java.util.Map.of(
                        "round", round, "tool", "stopped", "status", "error", "message", message));
        task.progress(
                json.writeValueAsString(
                        java.util.Map.of(
                                "round", round, "tool", "stopped", "status", "error", "message",
                                message, "steps", steps)));
    }
}

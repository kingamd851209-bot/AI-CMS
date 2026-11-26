package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.operations.OperationTask;
import com.xinhongru.operations.OperationTaskDispatcher;
import com.xinhongru.operations.OperationTaskService;
import com.xinhongru.operations.SiteHealthOverviewService;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Submission and operator-facing status only; domain services keep their existing execution logic.
 */
@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}/operations")
public class OperationsV2ApiController {
    private final OperationTaskService taskService;
    private final SiteHealthOverviewService healthService;
    private final SiteContextService siteContextService;
    private AdminAuthenticationService adminAuthenticationService;
    private OperationTaskDispatcher taskDispatcher;

    public OperationsV2ApiController(
            OperationTaskService taskService,
            SiteHealthOverviewService healthService,
            SiteContextService siteContextService) {
        this.taskService = Objects.requireNonNull(taskService, "运营任务服务不能为空");
        this.healthService = Objects.requireNonNull(healthService, "站点健康服务不能为空");
        this.siteContextService = Objects.requireNonNull(siteContextService, "站点上下文服务不能为空");
    }

    @Autowired
    public void setAdminAuthenticationService(
            AdminAuthenticationService adminAuthenticationService) {
        this.adminAuthenticationService = adminAuthenticationService;
    }

    @Autowired
    public void setTaskDispatcher(OperationTaskDispatcher taskDispatcher) {
        this.taskDispatcher = taskDispatcher;
    }

    @GetMapping("/health")
    public AdminApiResponse<List<SiteHealthOverviewService.HealthItem>> health(
            @PathVariable long siteId, HttpServletRequest request) {
        assertSite(request, siteId);
        return AdminApiResponse.success(healthService.overview(siteId));
    }

    @PostMapping
    public AdminApiResponse<OperationTask.TaskSnapshot> submit(
            @PathVariable long siteId,
            @RequestBody SubmitRequest body,
            HttpServletRequest request) {
        assertSite(request, siteId);
        if (body == null) {
            throw new IllegalArgumentException("运营任务请求不能为空");
        }
        OperationTask.Type type = parseType(body.type());
        if (taskDispatcher == null) {
            throw new IllegalStateException("运营任务 dispatcher 未初始化");
        }
        if (!taskDispatcher.supports(type)) {
            throw new IllegalArgumentException("不支持通用提交的运营任务类型: " + type);
        }
        UserEntity operator = currentOperator(request);
        if (operator.getId() == null) {
            throw new IllegalStateException("当前管理员缺少可审计身份");
        }
        Long operatorId = operator.getId().longValue();
        String commandJson =
                taskDispatcher.normalizeAndValidate(
                        type,
                        body.command() == null ? null : body.command().toString(),
                        siteId,
                        operatorId,
                        operator.getUsername());
        OperationTask.TaskSnapshot submitted =
                taskService.submit(
                        new OperationTask.TaskSubmission(
                                siteId,
                                type,
                                body.idempotencyKey(),
                                body.businessRoute(),
                                body.inputSummary(),
                                operatorId,
                                commandJson));
        return AdminApiResponse.success(
                submitted.status() == OperationTask.Status.QUEUED
                        ? taskDispatcher.execute(submitted.id())
                        : submitted);
    }

    @GetMapping("/{taskId}")
    public AdminApiResponse<OperationTask.TaskSnapshot> detail(
            @PathVariable long siteId, @PathVariable long taskId, HttpServletRequest request) {
        assertSite(request, siteId);
        OperationTask.TaskSnapshot task = taskInSite(siteId, taskId);
        return AdminApiResponse.success(task);
    }

    @PostMapping("/{taskId}/retry")
    public AdminApiResponse<OperationTask.TaskSnapshot> retry(
            @PathVariable long siteId, @PathVariable long taskId, HttpServletRequest request) {
        assertSite(request, siteId);
        OperationTask.TaskSnapshot task = taskInSite(siteId, taskId);
        if (taskDispatcher == null) {
            throw new IllegalStateException("运营任务 dispatcher 未初始化");
        }
        return AdminApiResponse.success(taskDispatcher.retryAndExecute(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public AdminApiResponse<OperationTask.TaskSnapshot> cancel(
            @PathVariable long siteId,
            @PathVariable long taskId,
            @RequestBody(required = false) CancelRequest body,
            HttpServletRequest request) {
        assertSite(request, siteId);
        OperationTask.TaskSnapshot task = taskInSite(siteId, taskId);
        return AdminApiResponse.success(
                taskService.cancel(taskId, body == null ? "运营人员取消任务" : body.reason()));
    }

    private OperationTask.TaskSnapshot taskInSite(long siteId, long taskId) {
        OperationTask.TaskSnapshot task = taskService.get(taskId);
        if (task.siteId() != siteId) {
            throw new IllegalArgumentException("任务不属于当前站点");
        }
        return task;
    }

    private void assertSite(HttpServletRequest request, long siteId) {
        if (siteId <= 0) {
            throw new IllegalArgumentException("站点无效");
        }
        siteContextService.assertCanAccessSite(request, siteId);
    }

    private UserEntity currentOperator(HttpServletRequest request) {
        if (adminAuthenticationService == null) {
            throw new IllegalStateException("管理员认证服务未初始化");
        }
        return adminAuthenticationService.requireAuthenticatedUser(request);
    }

    private static OperationTask.Type parseType(String value) {
        try {
            return OperationTask.Type.valueOf(
                    value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的运营任务类型");
        }
    }

    public record SubmitRequest(
            String type,
            String idempotencyKey,
            String businessRoute,
            String inputSummary,
            JsonNode command) {}

    public record CancelRequest(String reason) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }
}

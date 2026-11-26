package com.xinhongru.operations;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Claims and synchronously executes a persisted operation command without re-submitting it. */
@Service
public class OperationTaskDispatcher {
    private final OperationTaskService taskService;
    private final CmsOperationProperties properties;
    private final Map<OperationTask.Type, Handler> handlers;

    public OperationTaskDispatcher(
            OperationTaskService taskService,
            CmsOperationProperties properties,
            List<Handler> handlers) {
        this.taskService = Objects.requireNonNull(taskService, "运营任务服务不能为空");
        this.properties = Objects.requireNonNull(properties, "运营任务配置不能为空");
        EnumMap<OperationTask.Type, Handler> indexed = new EnumMap<>(OperationTask.Type.class);
        for (Handler handler : handlers == null ? List.<Handler>of() : handlers) {
            if (indexed.put(handler.type(), handler) != null) {
                throw new IllegalStateException("运营任务类型存在重复 dispatcher: " + handler.type());
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public OperationTask.TaskSnapshot retryAndExecute(long taskId) {
        OperationTask.TaskSnapshot queued = taskService.retry(taskId);
        if (queued.status() != OperationTask.Status.QUEUED) {
            return queued;
        }
        return execute(taskId);
    }

    public boolean supports(OperationTask.Type type) {
        return type != null && handlers.containsKey(type);
    }

    public void validate(OperationTask.Type type, String commandJson) {
        Handler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("不支持通用提交的运营任务类型: " + type);
        }
        if (commandJson == null || commandJson.isBlank()) {
            throw new IllegalArgumentException("运营任务缺少 type-specific command");
        }
        try {
            handler.validate(commandJson);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("运营任务 command 无法重建: " + type, ex);
        }
    }

    public String normalizeAndValidate(
            OperationTask.Type type,
            String commandJson,
            long siteId,
            Long operatorId,
            String operatorName) {
        Handler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("不支持通用提交的运营任务类型: " + type);
        }
        if (commandJson == null || commandJson.isBlank()) {
            throw new IllegalArgumentException("运营任务缺少 type-specific command");
        }
        try {
            String normalized = handler.normalize(commandJson, siteId, operatorId, operatorName);
            validate(type, normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("运营任务 command 无法规范化: " + type, ex);
        }
    }

    public OperationTask.TaskSnapshot execute(long taskId) {
        Duration lease = Duration.ofSeconds(properties.getLeaseSeconds());
        OperationTask.TaskClaim claim =
                taskService
                        .claim(taskId, properties.ownerOr("operation-dispatcher"), lease)
                        .orElseThrow(() -> new IllegalStateException("运营任务未能获得执行权"));
        OperationExecutionContext context =
                new DefaultOperationExecutionContext(taskService, claim, lease);
        try {
            OperationTask.TaskSnapshot task = claimedSnapshot(claim);
            Handler handler = handlers.get(task.type());
            if (handler == null) {
                throw new IllegalStateException("运营任务类型未配置可执行 dispatcher: " + task.type());
            }
            if (task.commandJson() == null || task.commandJson().isBlank()) {
                throw new IllegalStateException("运营任务缺少可重建 command");
            }
            DispatchResult result = handler.execute(task, context);
            return taskService.complete(claim, result.summary(), result.resultJson());
        } catch (OperationTaskService.FenceRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            taskService.fail(claim, safeMessage(ex));
            throw ex;
        }
    }

    private OperationTask.TaskSnapshot claimedSnapshot(OperationTask.TaskClaim claim) {
        OperationTask.TaskSnapshot task = taskService.get(claim.taskId());
        if (task.status() != OperationTask.Status.RUNNING
                || task.cancelRequested()
                || !claim.ownerToken().equals(task.ownerToken())
                || claim.fencingToken() != task.fencingToken()
                || task.leaseUntil() == null
                || task.leaseUntil().isBefore(LocalDateTime.now())) {
            throw new OperationTaskService.FenceRejectedException("运营任务领取后的租约已失效");
        }
        taskService.assertActive(claim);
        return task;
    }

    private static String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "运营任务执行失败" : ex.getMessage();
    }

    public interface Handler {
        OperationTask.Type type();

        String normalize(String commandJson, long siteId, Long operatorId, String operatorName);

        void validate(String commandJson);

        DispatchResult execute(OperationTask.TaskSnapshot task, OperationExecutionContext context);
    }

    public record DispatchResult(String summary, String resultJson) {}
}

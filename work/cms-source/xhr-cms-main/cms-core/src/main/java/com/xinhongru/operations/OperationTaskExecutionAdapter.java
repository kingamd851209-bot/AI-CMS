package com.xinhongru.operations;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Thin bridge for existing synchronous long tasks; it owns runtime state but not domain logic. */
@Service
public class OperationTaskExecutionAdapter {
    private static final ObjectMapper RESULT_MAPPER = new ObjectMapper();
    private final OperationTaskService taskService;
    private final CmsOperationProperties properties;

    public OperationTaskExecutionAdapter(OperationTaskService taskService) {
        this(taskService, new CmsOperationProperties());
    }

    @Autowired
    public OperationTaskExecutionAdapter(
            OperationTaskService taskService, CmsOperationProperties properties) {
        this.taskService = Objects.requireNonNull(taskService, "运营任务服务不能为空");
        this.properties = Objects.requireNonNull(properties, "运营任务配置不能为空");
    }

    public <T> ExecutionResult<T> execute(
            OperationTask.TaskSubmission submission,
            String ownerToken,
            Duration lease,
            Function<OperationExecutionContext, T> action,
            Function<T, String> resultSummary,
            ResultCodec<T> resultCodec) {
        Objects.requireNonNull(action, "运营任务动作不能为空");
        Objects.requireNonNull(resultSummary, "运营任务结果摘要不能为空");
        Objects.requireNonNull(resultCodec, "运营任务结果编解码不能为空");
        Duration configuredLease = configuredLease(lease);
        OperationTask.TaskSnapshot task = taskService.submit(submission);
        Optional<OperationTask.TaskClaim> claim =
                taskService.claim(task.id(), properties.ownerOr(ownerToken), configuredLease);
        if (claim.isEmpty()) {
            return replay(taskService.get(task.id()), resultCodec);
        }
        OperationTask.TaskClaim acquired = claim.get();
        OperationExecutionContext context =
                new DefaultOperationExecutionContext(taskService, acquired, configuredLease);
        try {
            context.assertActive();
            T result = action.apply(context);
            context.assertActive();
            return new ExecutionResult<>(
                    taskService.complete(
                            acquired, resultSummary.apply(result), resultCodec.serialize(result)),
                    result,
                    true);
        } catch (OperationTaskService.FenceRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            try {
                taskService.fail(acquired, safeMessage(ex));
            } catch (OperationTaskService.FenceRejectedException fenceRejected) {
                throw fenceRejected;
            }
            throw ex;
        }
    }

    public static <T> ResultCodec<T> json(Class<T> type) {
        Objects.requireNonNull(type, "运营任务结果类型不能为空");
        return new ResultCodec<>() {
            @Override
            public String serialize(T value) {
                try {
                    return RESULT_MAPPER.writeValueAsString(value);
                } catch (JacksonException exception) {
                    throw new IllegalStateException("运营任务结果序列化失败", exception);
                }
            }

            @Override
            public T deserialize(String json) {
                try {
                    return RESULT_MAPPER.readValue(json, type);
                } catch (JacksonException exception) {
                    throw new IllegalStateException("运营任务结果反序列化失败", exception);
                }
            }
        };
    }

    public static String command(Object value) {
        Objects.requireNonNull(value, "运营任务 command 不能为空");
        try {
            return RESULT_MAPPER.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营任务 command 序列化失败", exception);
        }
    }

    public static <T> T readCommand(String json, Class<T> type) {
        try {
            return RESULT_MAPPER.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营任务 command 无法重建", exception);
        }
    }

    private static <T> ExecutionResult<T> replay(
            OperationTask.TaskSnapshot task, ResultCodec<T> resultCodec) {
        if (task.status() == OperationTask.Status.SUCCEEDED
                && task.resultJson() != null
                && !task.resultJson().isBlank()) {
            return new ExecutionResult<>(task, resultCodec.deserialize(task.resultJson()), false);
        }
        return new ExecutionResult<>(task, null, false);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "领域任务执行失败" : message;
    }

    private Duration configuredLease(Duration requestedLease) {
        if (properties.getLeaseSeconds() <= 0) {
            throw new IllegalStateException("cms.operation.lease-seconds 必须大于 0");
        }
        return Duration.ofSeconds(properties.getLeaseSeconds());
    }

    public record ExecutionResult<T>(OperationTask.TaskSnapshot task, T result, boolean executed) {}

    public interface ResultCodec<T> {
        String serialize(T value);

        T deserialize(String json);
    }
}

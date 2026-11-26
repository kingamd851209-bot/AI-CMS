package com.xinhongru.operations;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Fencing capability handed to a long-running operation while it owns a task lease. */
final class DefaultOperationExecutionContext implements OperationExecutionContext {
    private final OperationTaskService taskService;
    private final OperationTask.TaskClaim claim;
    private final Duration lease;

    DefaultOperationExecutionContext(
            OperationTaskService taskService, OperationTask.TaskClaim claim, Duration lease) {
        this.taskService = Objects.requireNonNull(taskService, "运营任务服务不能为空");
        this.claim = Objects.requireNonNull(claim, "运营任务租约不能为空");
        this.lease = Objects.requireNonNull(lease, "运营任务租约时长不能为空");
    }

    public long taskId() {
        return claim.taskId();
    }

    public String owner() {
        return claim.ownerToken();
    }

    public long fencingToken() {
        return claim.fencingToken();
    }

    public void assertActive() {
        taskService.assertActive(claim);
    }

    public void renew() {
        taskService.renew(claim, lease);
    }

    /** Renews a task atomically with state that is fenced by the same operation attempt. */
    public void renewWithFencedAction(Runnable action) {
        Objects.requireNonNull(action, "续租栅栏动作不能为空");
        taskService.withFencedTransaction(
                claim,
                lease,
                () -> {
                    action.run();
                    return null;
                });
    }

    public Duration leaseDuration() {
        return lease;
    }

    public void pageProgress(OperationTask.PageProgress progress) {
        taskService.pageProgress(claim, progress);
    }

    public void replaceCommandJson(String commandJson) {
        taskService.replaceCommand(claim, commandJson);
    }

    public void withFencedWrite(Runnable action) {
        Objects.requireNonNull(action, "栅栏写入动作不能为空");
        taskService.withFencedResult(
                claim,
                () -> {
                    action.run();
                    return null;
                });
    }

    public <T> T withFencedResult(Supplier<T> action) {
        return taskService.withFencedResult(claim, action);
    }

    public <T> T withFencedTransaction(Supplier<T> action) {
        return taskService.withFencedTransaction(claim, lease, action);
    }
}

package com.xinhongru.operations;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Fenced execution capability supplied by the task runner, without exposing its persistence
 * service.
 */
public interface OperationExecutionContext {
    long taskId();

    String owner();

    long fencingToken();

    Duration leaseDuration();

    void assertActive();

    void renew();

    void renewWithFencedAction(Runnable action);

    void pageProgress(OperationTask.PageProgress progress);

    void replaceCommandJson(String commandJson);

    void withFencedWrite(Runnable action);

    <T> T withFencedResult(Supplier<T> action);

    <T> T withFencedTransaction(Supplier<T> action);
}

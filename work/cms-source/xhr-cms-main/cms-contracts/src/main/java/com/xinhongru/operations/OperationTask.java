package com.xinhongru.operations;

import java.time.LocalDateTime;

/** Shared operation commands and results, independent of JDBC and task scheduling. */
public final class OperationTask {
    private OperationTask() {}

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public enum Type {
        AI_RECOGNITION,
        DATA_CONVERSION,
        RESOURCE_PROCESSING,
        STATIC_PUBLISH,
        IMPORT_EXPORT
    }

    public record TaskSubmission(
            Long siteId,
            Type type,
            String idempotencyKey,
            String businessRoute,
            String inputSummary,
            Long operatorId,
            String commandJson) {
        public TaskSubmission(
                Long siteId,
                Type type,
                String idempotencyKey,
                String businessRoute,
                String inputSummary,
                Long operatorId) {
            this(siteId, type, idempotencyKey, businessRoute, inputSummary, operatorId, null);
        }
    }

    public record TaskClaim(long taskId, String ownerToken, long fencingToken) {}

    public record TaskSnapshot(
            long id,
            long siteId,
            Type type,
            String idempotencyKey,
            Status status,
            int progress,
            String progressStage,
            int progressTotal,
            int progressCompleted,
            int progressSucceeded,
            int progressSkipped,
            int progressFailed,
            String progressCurrentItem,
            String ownerToken,
            LocalDateTime leaseUntil,
            long fencingToken,
            boolean cancelRequested,
            int retryCount,
            String businessRoute,
            String inputSummary,
            String commandJson,
            String resultSummary,
            String resultJson,
            String failureSummary,
            Long operatorId,
            String operatorRef,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime updatedAt) {
        public TaskSnapshot(
                long id,
                long siteId,
                Type type,
                String idempotencyKey,
                Status status,
                int progress,
                String ownerToken,
                LocalDateTime leaseUntil,
                long fencingToken,
                boolean cancelRequested,
                int retryCount,
                String businessRoute,
                String inputSummary,
                String commandJson,
                String resultSummary,
                String resultJson,
                String failureSummary,
                Long operatorId,
                String operatorRef,
                LocalDateTime createdAt,
                LocalDateTime startedAt,
                LocalDateTime finishedAt,
                LocalDateTime updatedAt) {
            this(
                    id,
                    siteId,
                    type,
                    idempotencyKey,
                    status,
                    progress,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    ownerToken,
                    leaseUntil,
                    fencingToken,
                    cancelRequested,
                    retryCount,
                    businessRoute,
                    inputSummary,
                    commandJson,
                    resultSummary,
                    resultJson,
                    failureSummary,
                    operatorId,
                    operatorRef,
                    createdAt,
                    startedAt,
                    finishedAt,
                    updatedAt);
        }

        public boolean terminal() {
            return status == Status.SUCCEEDED
                    || status == Status.FAILED
                    || status == Status.CANCELLED;
        }
    }

    public record PageProgress(
            String stage,
            int total,
            int completed,
            int succeeded,
            int skipped,
            int failed,
            String currentItem) {}
}

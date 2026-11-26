package com.xinhongru.operations;

import com.xinhongru.operations.OperationTask.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Database-backed long-task ownership. Redis is deliberately not a fallback for task state:
 * ownership, leases and fencing must survive a single application process.
 */
@Service
public class OperationTaskService {
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final ObjectMapper COMMAND_JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;

    public OperationTaskService(JdbcTemplate jdbc) {
        this(
                jdbc,
                new DataSourceTransactionManager(
                        Objects.requireNonNull(jdbc.getDataSource(), "任务数据源不能为空")));
    }

    @Autowired
    public OperationTaskService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "任务数据库不能为空");
        Objects.requireNonNull(transactionManager, "任务事务管理器不能为空");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public TaskSnapshot submit(TaskSubmission submission) {
        return requiresNew.execute(status -> submitInTransaction(submission));
    }

    public TaskSnapshot submitRequired(TaskSubmission submission) {
        return required.execute(status -> submitInTransaction(submission));
    }

    public List<Long> queuedTaskIds(Type type, int limit) {
        return jdbc.queryForList(
                """
                SELECT id FROM cms_operation_task
                WHERE task_type=? AND status IN ('QUEUED', 'RUNNING')
                  AND (status='QUEUED' OR lease_until < NOW(6))
                ORDER BY id LIMIT ?
                """,
                Long.class,
                type.name(),
                Math.max(1, Math.min(limit, 50)));
    }

    private TaskSnapshot submitInTransaction(TaskSubmission submission) {
        requireSubmission(submission);
        TaskSnapshot existing = findByIdempotency(submission);
        if (existing != null) {
            return existing;
        }
        try {
            return insertTask(submission);
        } catch (DuplicateKeyException duplicate) {
            if (!isIdempotencyUniqueKeyConflict(duplicate)) {
                throw duplicate;
            }
            TaskSnapshot raced = requiresNew.execute(status -> findByIdempotency(submission));
            if (raced != null) {
                return requireSameOperationRequest(raced, submission);
            }
            throw duplicate;
        }
    }

    private TaskSnapshot requireSameOperationRequest(TaskSnapshot task, TaskSubmission submission) {
        if (task.siteId() != normalizedSiteId(submission.siteId())
                || task.type() != submission.type()
                || !task.idempotencyKey().equals(submission.idempotencyKey().trim())
                || !Objects.equals(
                        canonicalOperationCommand(task.commandJson()),
                        canonicalOperationCommand(submission.commandJson()))) {
            throw new IdempotencyConflictException("运营任务幂等冲突：同一幂等键的任务请求不一致");
        }
        return task;
    }

    private static boolean isIdempotencyUniqueKeyConflict(DuplicateKeyException duplicate) {
        for (Throwable cause = duplicate; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null
                    && cause.getMessage().contains("uk_cms_operation_task_idempotency")) {
                return true;
            }
        }
        return false;
    }

    private TaskSnapshot insertTask(TaskSubmission submission) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO cms_operation_task(site_id, task_type, idempotency_key, status, business_route,
                                                                   input_summary, command_json, operator_id, operator_ref, created_at, updated_at)
                                    VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, NOW(6), NOW(6))
                                    """,
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, normalizedSiteId(submission.siteId()));
                    statement.setString(2, submission.type().name());
                    statement.setString(3, submission.idempotencyKey().trim());
                    statement.setString(4, blankToNull(submission.businessRoute()));
                    statement.setString(5, truncate(submission.inputSummary()));
                    statement.setString(6, blankToNull(submission.commandJson()));
                    if (submission.operatorId() == null) {
                        statement.setObject(7, null);
                    } else {
                        statement.setLong(7, submission.operatorId());
                    }
                    statement.setString(
                            8,
                            submission.operatorId() == null
                                    ? "SYSTEM"
                                    : "USER:" + submission.operatorId());
                    return statement;
                },
                holder);
        Number id = generatedTaskId(holder);
        if (id == null) {
            throw new IllegalStateException("任务创建后未返回主键");
        }
        return get(id.longValue());
    }

    private static Number generatedTaskId(KeyHolder holder) {
        Map<String, Object> keys = holder.getKeys();
        if (keys == null) {
            return null;
        }
        Number namedId =
                keys.entrySet().stream()
                        .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .filter(Number.class::isInstance)
                        .map(Number.class::cast)
                        .findFirst()
                        .orElse(null);
        if (namedId != null) {
            return namedId;
        }
        if (keys.size() == 1 && keys.values().iterator().next() instanceof Number generatedKey) {
            return generatedKey;
        }
        return null;
    }

    public Optional<TaskClaim> claim(long taskId, String ownerToken, Duration lease) {
        return requiresNew.execute(status -> claimInTransaction(taskId, ownerToken, lease));
    }

    private Optional<TaskClaim> claimInTransaction(long taskId, String ownerToken, Duration lease) {
        String owner = requireOwner(ownerToken);
        long micros = leaseMicros(lease);
        int changed =
                jdbc.update(
                        """
                        UPDATE cms_operation_task
                           SET status='RUNNING', owner_token=?, lease_until=DATE_ADD(NOW(6), INTERVAL ? MICROSECOND),
                               fencing_token=fencing_token+1, progress=CASE WHEN progress < 1 THEN 1 ELSE progress END,
                               started_at=COALESCE(started_at, NOW(6)), finished_at=NULL, updated_at=NOW(6)
                         WHERE id=? AND cancel_requested=0
                           AND (status='QUEUED' OR (status='RUNNING' AND lease_until < NOW(6)))
                        """,
                        owner,
                        micros,
                        taskId);
        if (changed != 1) {
            return Optional.empty();
        }
        TaskSnapshot snapshot = get(taskId);
        int attemptNo = Math.toIntExact(snapshot.fencingToken());
        jdbc.update(
                """
                UPDATE cms_operation_task_attempt
                   SET status='LEASE_LOST', finished_at=NOW(6), failure_code='LEASE_LOST',
                       failure_summary='任务租约过期并被新 owner 接管'
                 WHERE task_id=? AND status='RUNNING' AND fencing_token<?
                """,
                taskId,
                snapshot.fencingToken());
        jdbc.update(
                """
                INSERT INTO cms_operation_task_attempt(task_id, attempt_no, owner_token, fencing_token, status, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', NOW(6))
                """,
                taskId,
                attemptNo,
                owner,
                snapshot.fencingToken());
        return Optional.of(new TaskClaim(taskId, owner, snapshot.fencingToken()));
    }

    public TaskSnapshot renew(TaskClaim claim, Duration lease) {
        return withFencedResult(
                claim,
                () -> {
                    int changed =
                            jdbc.update(
                                    """
                                    UPDATE cms_operation_task
                                       SET lease_until=DATE_ADD(NOW(6), INTERVAL ? MICROSECOND), updated_at=NOW(6)
                                     WHERE id=?
                                    """,
                                    leaseMicros(lease),
                                    claim.taskId());
                    if (changed != 1) {
                        throw inactive(claim);
                    }
                    return get(claim.taskId());
                });
    }

    public TaskSnapshot complete(TaskClaim claim, String resultSummary) {
        return complete(claim, resultSummary, null);
    }

    public TaskSnapshot complete(TaskClaim claim, String resultSummary, String resultJson) {
        return withFencedResult(
                claim,
                () -> {
                    int changed =
                            jdbc.update(
                                    """
                                    UPDATE cms_operation_task
                                       SET status='SUCCEEDED', progress=100, result_summary=?, result_json=?, failure_summary=NULL, lease_until=NULL,
                                           finished_at=NOW(6), updated_at=NOW(6)
                                     WHERE id=?
                                    """,
                                    truncate(resultSummary),
                                    truncateResultJson(resultJson),
                                    claim.taskId());
                    if (changed != 1) {
                        throw inactive(claim);
                    }
                    finishAttempt(claim, "SUCCEEDED", null);
                    return get(claim.taskId());
                });
    }

    public TaskSnapshot fail(TaskClaim claim, String failureSummary) {
        return withFencedResult(
                claim,
                () -> {
                    int changed =
                            jdbc.update(
                                    """
                                    UPDATE cms_operation_task
                                       SET status='FAILED', failure_summary=?, lease_until=NULL, finished_at=NOW(6), updated_at=NOW(6)
                                     WHERE id=?
                                    """,
                                    truncate(failureSummary),
                                    claim.taskId());
                    if (changed != 1) {
                        throw inactive(claim);
                    }
                    finishAttempt(claim, "FAILED", truncate(failureSummary));
                    return get(claim.taskId());
                });
    }

    public TaskSnapshot cancel(long taskId, String reason) {
        return requiresNew.execute(
                status -> {
                    lockTask(taskId);
                    jdbc.update(
                            """
                            UPDATE cms_operation_task
                               SET status='CANCELLED', cancel_requested=1, failure_summary=?, lease_until=NULL,
                                   finished_at=NOW(6), updated_at=NOW(6)
                             WHERE id=? AND status IN ('QUEUED', 'RUNNING')
                            """,
                            truncate(reason),
                            taskId);
                    jdbc.update(
                            """
                            UPDATE cms_operation_task_attempt attempt
                            JOIN cms_operation_task task ON task.id=attempt.task_id
                               SET attempt.status='CANCELLED', attempt.finished_at=NOW(6), attempt.failure_summary=task.failure_summary
                             WHERE task.id=? AND attempt.status='RUNNING'
                            """,
                            taskId);
                    return get(taskId);
                });
    }

    public TaskSnapshot retry(long taskId) {
        return requiresNew.execute(
                status -> {
                    lockTask(taskId);
                    jdbc.update(
                            """
                            UPDATE cms_operation_task
                               SET status='QUEUED', progress=0, cancel_requested=0, owner_token=NULL, lease_until=NULL,
                                   progress_stage=NULL, progress_total=0, progress_completed=0, progress_succeeded=0,
                                   progress_skipped=0, progress_failed=0, progress_current_item=NULL,
                                   failure_summary=NULL, result_summary=NULL, result_json=NULL, retry_count=retry_count+1, started_at=NULL,
                                   finished_at=NULL, updated_at=NOW(6)
                             WHERE id=? AND status='FAILED'
                            """,
                            taskId);
                    return get(taskId);
                });
    }

    public TaskSnapshot progress(TaskClaim claim, int progress) {
        return withFencedResult(
                claim,
                () -> {
                    jdbc.update(
                            """
                            UPDATE cms_operation_task
                               SET progress=?, updated_at=NOW(6)
                             WHERE id=?
                            """,
                            Math.max(1, Math.min(99, progress)),
                            claim.taskId());
                    return get(claim.taskId());
                });
    }

    public TaskSnapshot pageProgress(TaskClaim claim, PageProgress progress) {
        Objects.requireNonNull(progress, "页面进度不能为空");
        requirePageProgress(progress);
        return withFencedResult(
                claim,
                () -> {
                    jdbc.update(
                            """
                            UPDATE cms_operation_task
                               SET progress_stage=?, progress_total=?, progress_completed=?, progress_succeeded=?,
                                   progress_skipped=?, progress_failed=?, progress_current_item=?, updated_at=NOW(6)
                             WHERE id=?
                            """,
                            progress.stage(),
                            progress.total(),
                            progress.completed(),
                            progress.succeeded(),
                            progress.skipped(),
                            progress.failed(),
                            progress.currentItem(),
                            claim.taskId());
                    return get(claim.taskId());
                });
    }

    /** Replaces a task command only while its current lease owns the task. */
    public TaskSnapshot replaceCommand(TaskClaim claim, String commandJson) {
        if (commandJson == null || commandJson.isBlank() || commandJson.length() > 1_000_000) {
            throw new IllegalArgumentException("运营任务 command 无效");
        }
        return withFencedResult(
                claim,
                () -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE cms_operation_task SET command_json=?,"
                                            + " updated_at=NOW(6) WHERE id=?",
                                    commandJson,
                                    claim.taskId());
                    if (changed != 1) throw inactive(claim);
                    return get(claim.taskId());
                });
    }

    public <T> T withFencedResult(TaskClaim claim, Supplier<T> action) {
        Objects.requireNonNull(claim, "任务租约不能为空");
        Objects.requireNonNull(action, "栅栏写入动作不能为空");
        return requiresNew.execute(
                status -> {
                    lockTask(claim.taskId());
                    requireActive(claim);
                    return action.get();
                });
    }

    public <T> T withFencedTransaction(TaskClaim claim, Duration lease, Supplier<T> action) {
        Objects.requireNonNull(claim, "任务租约不能为空");
        Objects.requireNonNull(action, "栅栏事务动作不能为空");
        long micros = leaseMicros(lease);
        return required.execute(
                status -> {
                    lockTask(claim.taskId());
                    requireActive(claim);
                    T result = action.get();
                    int renewed =
                            jdbc.update(
                                    """
                                    UPDATE cms_operation_task
                                       SET lease_until=DATE_ADD(NOW(6), INTERVAL ? MICROSECOND), updated_at=NOW(6)
                                     WHERE id=? AND status='RUNNING' AND cancel_requested=0
                                       AND owner_token=? AND fencing_token=?
                                    """,
                                    micros,
                                    claim.taskId(),
                                    claim.ownerToken(),
                                    claim.fencingToken());
                    if (renewed != 1) {
                        throw inactive(claim);
                    }
                    return result;
                });
    }

    public void assertActive(TaskClaim claim) {
        Objects.requireNonNull(claim, "任务租约不能为空");
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM cms_operation_task
                         WHERE id=? AND status='RUNNING' AND cancel_requested=0 AND owner_token=? AND fencing_token=?
                           AND lease_until >= NOW(6)
                        """,
                        Integer.class,
                        claim.taskId(),
                        claim.ownerToken(),
                        claim.fencingToken());
        if (count == null || count != 1) {
            throw inactive(claim);
        }
    }

    private TaskSnapshot lockTask(long taskId) {
        jdbc.queryForObject(
                "SELECT id FROM cms_operation_task WHERE id=? FOR UPDATE", Long.class, taskId);
        return get(taskId);
    }

    private void requireActive(TaskClaim claim) {
        Integer active =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM cms_operation_task
                         WHERE id=? AND status='RUNNING' AND cancel_requested=0
                           AND owner_token=? AND fencing_token=? AND lease_until >= NOW(6)
                        """,
                        Integer.class,
                        claim.taskId(),
                        claim.ownerToken(),
                        claim.fencingToken());
        if (active == null || active != 1) {
            throw inactive(claim);
        }
    }

    public boolean isCancelled(TaskClaim claim) {
        Objects.requireNonNull(claim, "任务租约不能为空");
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM cms_operation_task
                         WHERE id=? AND (cancel_requested=1 OR status='CANCELLED' OR owner_token<>? OR fencing_token<>?)
                        """,
                        Integer.class,
                        claim.taskId(),
                        claim.ownerToken(),
                        claim.fencingToken());
        return count != null && count > 0;
    }

    public TaskSnapshot get(long taskId) {
        List<TaskSnapshot> tasks = queryTasks("WHERE id=?", taskId);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("运营任务不存在");
        }
        return tasks.getFirst();
    }

    private List<TaskSnapshot> queryTasks(String where, Object... args) {
        return jdbc.query(
                """
                SELECT id, site_id, task_type, idempotency_key, status, progress,
                       progress_stage, progress_total, progress_completed, progress_succeeded,
                       progress_skipped, progress_failed, progress_current_item, owner_token, lease_until,
                       fencing_token, cancel_requested, retry_count, business_route, input_summary, command_json,
                       result_summary, result_json, failure_summary, operator_id, operator_ref,
                       created_at, started_at, finished_at, updated_at
                  FROM cms_operation_task
                """
                        + where,
                (rs, rowNum) ->
                        new TaskSnapshot(
                                rs.getLong("id"),
                                rs.getLong("site_id"),
                                Type.valueOf(rs.getString("task_type")),
                                rs.getString("idempotency_key"),
                                Status.valueOf(rs.getString("status")),
                                rs.getInt("progress"),
                                rs.getString("progress_stage"),
                                rs.getInt("progress_total"),
                                rs.getInt("progress_completed"),
                                rs.getInt("progress_succeeded"),
                                rs.getInt("progress_skipped"),
                                rs.getInt("progress_failed"),
                                rs.getString("progress_current_item"),
                                rs.getString("owner_token"),
                                localDateTime(rs, "lease_until"),
                                rs.getLong("fencing_token"),
                                rs.getBoolean("cancel_requested"),
                                rs.getInt("retry_count"),
                                rs.getString("business_route"),
                                rs.getString("input_summary"),
                                rs.getString("command_json"),
                                rs.getString("result_summary"),
                                rs.getString("result_json"),
                                rs.getString("failure_summary"),
                                nullableLong(rs.getObject("operator_id")),
                                rs.getString("operator_ref"),
                                localDateTime(rs, "created_at"),
                                localDateTime(rs, "started_at"),
                                localDateTime(rs, "finished_at"),
                                localDateTime(rs, "updated_at")),
                args);
    }

    private TaskSnapshot findByIdempotency(TaskSubmission submission) {
        List<Long> ids =
                jdbc.queryForList(
                        """
                        SELECT id FROM cms_operation_task WHERE site_id=? AND task_type=? AND idempotency_key=?
                        """,
                        Long.class,
                        normalizedSiteId(submission.siteId()),
                        submission.type().name(),
                        submission.idempotencyKey().trim());
        return ids.isEmpty() ? null : get(ids.getFirst());
    }

    private void finishAttempt(TaskClaim claim, String status, String failureSummary) {
        jdbc.update(
                """
                UPDATE cms_operation_task_attempt
                   SET status=?, finished_at=NOW(6), failure_summary=?
                 WHERE task_id=? AND owner_token=? AND fencing_token=? AND status='RUNNING'
                """,
                status,
                failureSummary,
                claim.taskId(),
                claim.ownerToken(),
                claim.fencingToken());
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long normalizedSiteId(Long siteId) {
        return siteId == null ? 0L : siteId;
    }

    private static String requireOwner(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank() || ownerToken.length() > 128) {
            throw new IllegalArgumentException("任务 owner 无效");
        }
        return ownerToken.trim();
    }

    private static long leaseMicros(Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("任务租约必须大于 0");
        }
        try {
            return Math.max(1L, lease.toNanos() / 1_000L);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("任务租约过大", ex);
        }
    }

    private static void requireSubmission(TaskSubmission submission) {
        if (submission == null
                || submission.type() == null
                || submission.idempotencyKey() == null
                || submission.siteId() == null
                || submission.siteId() <= 0
                || submission.idempotencyKey().isBlank()
                || submission.idempotencyKey().length() > 160) {
            throw new IllegalArgumentException("运营任务提交参数无效");
        }
    }

    private static void requirePageProgress(PageProgress progress) {
        if (progress.total() < 0
                || progress.completed() < 0
                || progress.succeeded() < 0
                || progress.skipped() < 0
                || progress.failed() < 0) {
            throw new IllegalArgumentException("页面进度统计不能为负数");
        }
        if (progress.completed() > progress.total()) {
            throw new IllegalArgumentException("页面已完成数量不能超过总数");
        }
        long settled = (long) progress.succeeded() + progress.skipped() + progress.failed();
        if (progress.completed() != settled) {
            throw new IllegalArgumentException("页面已完成数量必须等于成功、跳过和失败数量之和");
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_SUMMARY_LENGTH
                ? value.trim()
                : value.substring(0, MAX_SUMMARY_LENGTH);
    }

    private static String truncateResultJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 1_000_000) {
            throw new IllegalArgumentException("运营任务结果过大");
        }
        return value;
    }

    private FenceRejectedException inactive(TaskClaim claim) {
        TaskSnapshot current = get(claim.taskId());
        if (current.cancelRequested() || current.status() == Status.CANCELLED) {
            return new FenceRejectedException("运营任务已取消，禁止继续写入");
        }
        return new FenceRejectedException("运营任务租约已失效，禁止继续写入");
    }

    public static class FenceRejectedException extends IllegalStateException {
        public FenceRejectedException(String message) {
            super(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String canonicalOperationCommand(String commandJson) {
        String normalized = blankToNull(commandJson);
        if (normalized == null) {
            return null;
        }
        try {
            JsonNode parsed = COMMAND_JSON.readTree(normalized);
            return "json:" + COMMAND_JSON.writeValueAsString(canonicalOperationJson(parsed));
        } catch (Exception ignored) {
            return "text:" + normalized;
        }
    }

    private static JsonNode canonicalOperationJson(JsonNode source) {
        if (source.isObject()) {
            ObjectNode target = COMMAND_JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(source.propertyNames());
            names.sort(Comparator.naturalOrder());
            for (String name : names) target.set(name, canonicalOperationJson(source.get(name)));
            return target;
        }
        if (source.isArray()) {
            ArrayNode target = COMMAND_JSON.createArrayNode();
            source.forEach(value -> target.add(canonicalOperationJson(value)));
            return target;
        }
        return source;
    }
}

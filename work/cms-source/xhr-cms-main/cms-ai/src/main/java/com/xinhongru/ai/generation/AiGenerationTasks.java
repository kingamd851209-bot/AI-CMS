package com.xinhongru.ai.generation;

import jakarta.annotation.PreDestroy;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded single-instance task registry, shared by model and agent execution. */
@Service
public class AiGenerationTasks {
    private final AiTaskRouter router;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public AiGenerationTasks(AiTaskRouter router) {
        this.router = router;
    }

    public synchronized Task create(Integer owner, Long siteId, AiUsage usage) {
        return create(owner, siteId, usage, UUID.randomUUID().toString(), "");
    }

    public synchronized Task create(
            Integer owner, Long siteId, AiUsage usage, String requestId, String inputKey) {
        if (requestId == null || !requestId.matches("[a-fA-F0-9-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析请求标识无效");
        cleanup();
        var existing = tasks.get(requestId);
        if (existing != null) {
            if (!existing.owner.equals(owner)
                    || !java.util.Objects.equals(existing.siteId, siteId)
                    || existing.usage != usage
                    || !existing.inputKey.equals(inputKey))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "分析请求标识冲突");
            return existing;
        }
        if (tasks.size() >= 200
                || tasks.values().stream()
                                .filter(task -> task.owner.equals(owner) && !task.terminal())
                                .count()
                        >= 8) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 任务过多，请稍后重试");
        }
        Task task =
                new Task(requestId, inputKey, owner, siteId, usage, router.timeoutMillis(usage));
        tasks.put(task.id, task);
        return task;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(600);
        tasks.values()
                .removeIf(
                        task -> {
                            // Long-running agents keep their result for ten minutes after
                            // completion.
                            if (!task.state().equals("running") && task.updated.isBefore(cutoff)) {
                                cancel(task);
                                return true;
                            }
                            return false;
                        });
    }

    public Task require(String id, Integer owner) {
        Task task = findOwned(id, owner);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 任务不存在或已过期");
        return task;
    }

    /** 仅在任务已离开内存登记表时返回空，其他账号的任务继续按不存在处理。 */
    public Task findOwned(String id, Integer owner) {
        Task task = tasks.get(id);
        if (task != null && !task.owner.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 任务不存在或已过期");
        }
        return task;
    }

    public void start(Task task, String prompt) {
        start(task, Flux.defer(() -> router.execute(task.id, task.siteId, task.usage, prompt)));
    }

    public void start(Task task, Flux<AiTaskEvent> execution) {
        if (!task.state.compareAndSet("pending", "running")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已提交或已取消");
        }
        task.deadline = Instant.now().plusMillis(task.timeoutMs);
        StringBuilder output = new StringBuilder();
        Disposable subscription =
                execution
                        .<AiTaskEvent>handle(
                                (event, sink) -> {
                                    if (event.type().equals("delta")) {
                                        if (output.length() + event.text().length() > 2_000_000) {
                                            sink.error(new IllegalStateException("AI 输出超过大小限制"));
                                            return;
                                        }
                                        output.append(event.text());
                                    }
                                    sink.next(event);
                                })
                        .concatWith(
                                Flux.defer(
                                        () -> {
                                            AiOutputValidator.validate(
                                                    task.usage, output.toString());
                                            return Flux.empty();
                                        }))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(java.time.Duration.ofMillis(task.timeoutMs))
                        .subscribe(
                                task::publish,
                                error -> {
                                    String message =
                                            task.usage == AiUsage.TEMPLATE_REPAIR
                                                    ? "模板修复失败，请检查复杂任务服务、并发限制、模型额度及超时后重试"
                                                    : "AI 生成失败，请检查模型配置、额度和超时后重试";
                                    if (task.inputKey.startsWith("site-model:")
                                            && (error
                                                            instanceof
                                                            java.util.concurrent.TimeoutException
                                                    || task.deadline != null
                                                            && !Instant.now()
                                                                    .isBefore(task.deadline))) {
                                        message = "整站分析达到本次时限；可尝试恢复已校验草案，再继续补充未完成部分";
                                    }
                                    task.finish("failed", "", message);
                                },
                                () -> task.finish("completed", output.toString(), ""));
        task.subscription.set(subscription);
        if (task.terminal()) subscription.dispose();
    }

    public void cancel(Task task) {
        synchronized (task) {
            if (task.terminal()) return;
            task.updated = Instant.now();
            task.error = "AI 任务已取消";
            task.state.set("cancelled");
            task.publish(new AiTaskEvent("cancelled", task.error));
        }
        Disposable subscription = task.subscription.get();
        if (subscription != null) subscription.dispose();
    }

    @PreDestroy
    public void close() {
        tasks.values().forEach(this::cancel);
        tasks.clear();
    }

    public record Snapshot(
            String taskId,
            String state,
            String text,
            String error,
            long timeoutMs,
            String progress) {}

    /** 面向工作台实时窗口的状态，省略完整模型产物。 */
    public record ProgressSnapshot(String taskId, String state, String error, long timeoutMs,
                                   String progress, long sequence) {}

    public static final class Task {
        public final String id;
        private final String inputKey;
        public final Integer owner;
        public final Long siteId;
        public final AiUsage usage;
        public final long timeoutMs;
        private volatile Instant updated = Instant.now();
        private final AtomicReference<String> state = new AtomicReference<>("pending");
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final Sinks.Many<AiTaskEvent> events = Sinks.many().replay().limit(256);
        private final Sinks.Many<ProgressSnapshot> progressEvents = Sinks.many().replay().latest();
        private long progressSequence;
        private volatile Instant deadline;
        private String progress = "";
        private String text = "";
        private String error = "";

        Task(
                String id,
                String inputKey,
                Integer owner,
                Long siteId,
                AiUsage usage,
                long timeoutMs) {
            this.id = id;
            this.inputKey = inputKey;
            this.owner = owner;
            this.siteId = siteId;
            this.usage = usage;
            this.timeoutMs = timeoutMs;
        }

        private synchronized void finish(String next, String output, String message) {
            if (!state.get().equals("running")) return;
            text = output;
            error = message;
            updated = Instant.now();
            state.set(next);
            publish(new AiTaskEvent(next.equals("completed") ? "done" : "failed", message));
        }

        private synchronized void publish(AiTaskEvent event) {
            if (!event.terminal() && !state.get().equals("running")) return;
            events.tryEmitNext(event);
            if (event.terminal()) events.tryEmitComplete();
            if ("progress".equals(event.type()) || event.terminal()) {
                progressSequence++;
                progressEvents.tryEmitNext(progressSnapshot());
                if (terminal()) progressEvents.tryEmitComplete();
            }
        }

        public synchronized Snapshot snapshot() {
            return new Snapshot(id, state(), text, error, timeoutMs, progress);
        }

        public synchronized void progress(String message) {
            if (!state().equals("running")) return;
            progress = message;
            publish(AiTaskEvent.progress(message));
        }

        public synchronized String completeWith(java.util.function.Supplier<String> action) {
            return commitIfRunning(
                    () -> {
                        String output = action.get();
                        finish("completed", output, "");
                        return output;
                    });
        }

        public synchronized <T> T commitIfRunning(java.util.function.Supplier<T> action) {
            if (!state().equals("running") || deadline == null || !Instant.now().isBefore(deadline))
                throw new IllegalStateException("任务已结束或超时，拒绝迟到提交");
            return action.get();
        }

        public Flux<AiTaskEvent> events() {
            return events.asFlux();
        }

        /** 在同一任务锁内发送当前快照并订阅，避免检查状态与订阅之间遗漏完成或取消。 */
        public Flux<ProgressSnapshot> progressUpdates() {
            return Flux.create(sink -> {
                synchronized (Task.this) {
                    long boundary = progressSequence;
                    sink.next(progressSnapshot());
                    if (terminal()) {
                        sink.complete();
                        return;
                    }
                    var subscription = progressEvents.asFlux()
                            .filter(update -> update.sequence() > boundary)
                            .subscribe(sink::next, sink::error, sink::complete);
                    sink.onDispose(subscription);
                }
            });
        }

        private synchronized ProgressSnapshot progressSnapshot() {
            return new ProgressSnapshot(id, state(), error, timeoutMs, progress, progressSequence);
        }

        public String state() {
            return state.get();
        }

        /** 服务端任务作用域，不进入对外的 Snapshot。 */
        public String inputKey() {
            return inputKey;
        }

        public boolean terminal() {
            return !state().equals("pending") && !state().equals("running");
        }
    }
}

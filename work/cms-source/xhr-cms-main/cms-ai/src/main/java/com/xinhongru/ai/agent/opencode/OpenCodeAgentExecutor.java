package com.xinhongru.ai.agent.opencode;

import com.xinhongru.ai.generation.AgentExecutor;
import com.xinhongru.ai.generation.AiTaskEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded remote execution. A lost abort acknowledgement quarantines this adapter. */
@Service
public class OpenCodeAgentExecutor implements AgentExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(OpenCodeAgentExecutor.class);
    private final OpenCodeProperties config;
    private final OpenCodeClient client;
    private final Semaphore capacity;
    private final AtomicBoolean quarantined = new AtomicBoolean();

    public OpenCodeAgentExecutor(OpenCodeProperties config, OpenCodeClient client) {
        this.config = config;
        this.client = client;
        capacity = new Semaphore(Math.max(1, config.getMaxConcurrent()));
    }

    private void checkConfiguration() {
        config.checkConfigured();
        if (quarantined.get()) throw new IllegalStateException("复杂任务清理未确认，请管理员检查并重启执行服务和 CMS");
    }

    @Override
    public void checkReady() {
        checkConfiguration();
        try {
            client.checkReady();
        } catch (RuntimeException error) {
            throw new IllegalStateException("复杂任务服务未就绪，请检查连接、认证及模板执行器配置");
        }
    }

    @Override
    public long timeoutMillis() {
        return Math.max(10_000, Math.min(config.getTimeoutMs(), 1_800_000));
    }

    @Override
    public Flux<AiTaskEvent> execute(Request request) {
        return Flux.defer(
                () -> {
                    checkConfiguration();
                    // Acquire the lease synchronously; cache asynchronous resource creation so
                    // cancellation
                    // cannot discard a workspace after its files have been created but before it is
                    // emitted.
                    Mono<Run> acquire =
                            Mono.fromSupplier(
                                    () -> {
                                        if (!capacity.tryAcquire())
                                            throw new IllegalStateException("复杂任务执行器繁忙，请稍后重试");
                                        Mono<OpenCodeWorkspace> workspace =
                                                Mono.fromCallable(
                                                                () ->
                                                                        OpenCodeWorkspace.create(
                                                                                config,
                                                                                request.taskId(),
                                                                                request.files()))
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .cache();
                                        Mono<String> session =
                                                workspace
                                                        .flatMap(
                                                                value ->
                                                                        client.createSession(
                                                                                request,
                                                                                value.remote()))
                                                        .cache();
                                        return new Run(
                                                request.taskId(),
                                                workspace,
                                                session,
                                                new AtomicBoolean());
                                    });
                    return Flux.usingWhen(
                                    acquire,
                                    run ->
                                            run.workspace
                                                    .flatMapMany(
                                                            workspace ->
                                                                    run.session.flatMapMany(
                                                                            id -> {
                                                                                Flux<AiTaskEvent>
                                                                                        result =
                                                                                                client.run(
                                                                                                                id,
                                                                                                                workspace
                                                                                                                        .remote(),
                                                                                                                request,
                                                                                                                timeoutMillis())
                                                                                                        .map(
                                                                                                                AiTaskEvent
                                                                                                                        ::delta)
                                                                                                        .flux();
                                                                                Flux<AiTaskEvent>
                                                                                        progress =
                                                                                                Flux
                                                                                                        .interval(
                                                                                                                Duration
                                                                                                                        .ofSeconds(
                                                                                                                                10))
                                                                                                        .map(
                                                                                                                tick ->
                                                                                                                        AiTaskEvent
                                                                                                                                .progress(
                                                                                                                                        "任务仍在执行，已等待"
                                                                                                                                            + " "
                                                                                                                                                + ((tick
                                                                                                                                                                + 1)
                                                                                                                                                        * 10)
                                                                                                                                                + " 秒"));
                                                                                return Flux.merge(
                                                                                                result,
                                                                                                progress)
                                                                                        .takeUntil(
                                                                                                event ->
                                                                                                        event.type()
                                                                                                                .equals(
                                                                                                                        "delta"));
                                                                            }))
                                                    .startWith(
                                                            AiTaskEvent.progress(
                                                                    "已创建独立任务工作目录，正在执行复杂任务"))
                                                    .takeUntilOther(
                                                            Mono.delay(
                                                                            Duration.ofMillis(
                                                                                    timeoutMillis()))
                                                                    .flatMap(
                                                                            ignored ->
                                                                                    Mono.error(
                                                                                            new IllegalStateException(
                                                                                                    "复杂任务执行超时")))),
                                    run -> cleanup(run, false),
                                    (run, error) -> cleanup(run, true),
                                    run -> cleanup(run, true).onErrorResume(error -> Mono.empty()))
                            .doOnDiscard(
                                    Run.class,
                                    run ->
                                            cleanup(run, true)
                                                    .subscribe(ignored -> {}, error -> {}));
                });
    }

    private Mono<Void> cleanup(Run run, boolean abort) {
        // The cached creation request completes even if the caller cancels during session creation.
        // Cleanup can then abort/delete the exact remote session instead of leaking it.
        if (!run.cleanupStarted.compareAndSet(false, true)) return Mono.empty();
        return run.workspace
                .flatMap(
                        workspace ->
                                run.session
                                        .flatMap(
                                                id ->
                                                        (abort
                                                                        ? client.abort(
                                                                                id,
                                                                                workspace.remote())
                                                                        : Mono.<Void>empty())
                                                                .then(
                                                                        client.delete(
                                                                                id,
                                                                                workspace.remote()))
                                                                .then(
                                                                        client.disposeWorkspace(
                                                                                workspace
                                                                                        .remote())))
                                        .then(
                                                Mono.<Void>fromRunnable(
                                                                () -> {
                                                                    try {
                                                                        workspace.delete();
                                                                    } catch (Exception error) {
                                                                        throw new IllegalStateException(
                                                                                "任务工作目录清理失败");
                                                                    }
                                                                })
                                                        .subscribeOn(Schedulers.boundedElastic())))
                .doOnError(
                        error -> {
                            quarantined.set(true);
                            LOG.warn(
                                    "Agent cleanup not confirmed; execution disabled. Workspace:"
                                            + " {}",
                                    run.taskId);
                        })
                .doFinally(signal -> capacity.release());
    }

    private record Run(
            String taskId,
            Mono<OpenCodeWorkspace> workspace,
            Mono<String> session,
            AtomicBoolean cleanupStarted) {}
}

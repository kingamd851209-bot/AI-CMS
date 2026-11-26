package com.xinhongru.ai.generation;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Text-only model access. No filesystem, shell or agent tools are registered. */
@Service
public class SpringAiTextGenerator implements AiTextGenerator {
    private static final EncodingRegistry TOKEN_ENCODINGS = Encodings.newLazyEncodingRegistry();
    private static final int AGENT_MESSAGE_OVERHEAD_TOKENS = 64;

    private final AiGenerationProperties config;
    private final Semaphore capacity;
    private AiGenerationSettings settings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettings(AiGenerationSettings settings) {
        this.settings = settings;
    }

    public AiGenerationSettings.Snapshot configurationSnapshot() {
        var stored = settings == null ? null : settings.generationSnapshot();
        return stored != null
                ? stored
                : new AiGenerationSettings.Snapshot(
                        config.isEnabled(),
                        config.getBaseUrl(),
                        config.getModelName(),
                        config.getApiKey(),
                        config.getTimeoutMs(),
                        config.getMaxRetries());
    }

    public static String normalizedEndpoint(String baseUrl) {
        try {
            var value = endpoint(baseUrl);
            return value.baseUrl() + value.path();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("模型地址必须是无凭据、query或fragment的有效HTTP(S)地址");
        }
    }

    public SpringAiTextGenerator(AiGenerationProperties config) {
        this.config = config;
        this.capacity = new Semaphore(Math.max(1, config.getMaxConcurrent()));
    }

    @Override
    public void checkConfigured(AiUsage usage) {
        var snapshot = configurationSnapshot();
        snapshot.checkConfigured();
        normalizedEndpoint(snapshot.baseUrl());
    }

    @Override
    public AiTextGenerator forTask() {
        var snapshot = configurationSnapshot();
        return new AiTextGenerator() {
            public void checkConfigured(AiUsage usage) {
                snapshot.checkConfigured();
                normalizedEndpoint(snapshot.baseUrl());
            }

            public Flux<String> stream(AiUsage usage, String prompt) {
                return streamWithSnapshot(usage, prompt, snapshot);
            }
        };
    }

    @Override
    public Flux<String> stream(AiUsage usage, String prompt) {
        return Flux.defer(() -> streamWithSnapshot(usage, prompt, configurationSnapshot()));
    }

    private Flux<String> streamWithSnapshot(
            AiUsage usage, String prompt, AiGenerationSettings.Snapshot snapshot) {
        return Flux.defer(
                () -> {
                    boolean domainAgent = usage == AiUsage.WORKBENCH_DOMAIN_AGENT
                            || usage == AiUsage.WORKBENCH_DOMAIN_CHAT;
                    if (prompt == null || prompt.isBlank()) {
                        return Flux.error(new IllegalArgumentException(
                                domainAgent ? "Agent提示词不能为空" : "提示词不能为空且不能超过 200000 字符"));
                    }
                    int maxCompletionTokens = domainAgent ? config.getDomainAgentCompletionTokens() : 0;
                    if (domainAgent) {
                        int contextTokens = config.getDomainAgentContextTokens();
                        if (maxCompletionTokens <= 0
                                || contextTokens <= (long) maxCompletionTokens + AGENT_MESSAGE_OVERHEAD_TOKENS) {
                            return Flux.error(new IllegalStateException("Agent 上下文或输出 token 预算配置无效"));
                        }
                        // Estimate the text messages sent below and reserve space for message framing.
                        var encoding = TOKEN_ENCODINGS.getEncoding(EncodingType.O200K_BASE);
                        long inputTokens = (long) encoding.countTokensOrdinary(usage.systemPrompt())
                                + encoding.countTokensOrdinary(prompt)
                                + AGENT_MESSAGE_OVERHEAD_TOKENS;
                        int inputBudget = contextTokens - maxCompletionTokens;
                        if (inputTokens > inputBudget) {
                            return Flux.error(new InputBudgetExceededException(inputTokens, inputBudget));
                        }
                    } else if (prompt.length() > 200_000) {
                        return Flux.error(new IllegalArgumentException("提示词不能为空且不能超过 200000 字符"));
                    }
                    snapshot.checkConfigured();
                    if (!capacity.tryAcquire()) {
                        return Flux.error(new IllegalStateException("AI 并发任务已达上限，请稍后重试"));
                    }
                    int timeoutMs = Math.max(1_000, Math.min(snapshot.timeoutMs(), 300_000));
                    return Flux.using(
                                    () -> createClient(snapshot, timeoutMs),
                                    client ->
                                            streamResponse(
                                                    client, usage, prompt, timeoutMs, maxCompletionTokens, snapshot),
                                    OpenAIClient::close)
                            .onErrorMap(
                                    error ->
                                            new IllegalStateException("AI调用失败，请检查已保存的模型配置、服务状态或超时"))
                            .doFinally(signal -> capacity.release());
                });
    }

    private Flux<String> streamResponse(
            OpenAIClient client,
            AiUsage usage,
            String prompt,
            int timeoutMs,
            int maxCompletionTokens,
            AiGenerationSettings.Snapshot snapshot) {
        var options = OpenAiChatOptions.builder().model(snapshot.modelName());
        if (maxCompletionTokens > 0) {
            options.maxCompletionTokens(maxCompletionTokens);
        } else if (usage == AiUsage.WORKBENCH_ANALYSIS) {
            options.maxCompletionTokens(4096);
        }
        var model =
                OpenAiChatModel.builder()
                        .openAiClient(client)
                        .openAiClientAsync(client.async())
                        .options(options.build())
                        .build();
        var request =
                new Prompt(
                        List.of(new SystemMessage(usage.systemPrompt()), new UserMessage(prompt)));
        AtomicBoolean received = new AtomicBoolean();
        AtomicInteger length = new AtomicInteger();
        Flux<String> result =
                model.stream(request)
                        .handle(
                                (response, sink) -> {
                                    if (response.getResult() != null
                                            && response.getResult().getOutput() != null) {
                                        String reason =
                                                response.getResult()
                                                        .getMetadata()
                                                        .getFinishReason();
                                        if ("length".equalsIgnoreCase(reason)
                                                || "content_filter".equalsIgnoreCase(reason)
                                                || "tool_calls".equalsIgnoreCase(reason)) {
                                            sink.error(
                                                    new IllegalStateException(
                                                            "AI 输出未完整完成：" + reason));
                                            return;
                                        }
                                        String text = response.getResult().getOutput().getText();
                                        if (text != null && !text.isEmpty()) {
                                            received.set(true);
                                            if (length.addAndGet(text.length()) > 2_000_000) {
                                                sink.error(
                                                        new IllegalStateException("AI 输出超过大小限制"));
                                            } else {
                                                sink.next(text);
                                            }
                                        }
                                    }
                                });
        int retries =
                usage == AiUsage.WORKBENCH_ANALYSIS
                        ? 0
                        : Math.max(0, Math.min(2, snapshot.maxRetries()));
        return result.retryWhen(
                        Retry.backoff(retries, Duration.ofMillis(500))
                                .filter(error -> !received.get() && isRetryable(error))
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // One deadline covers the request, retries, and continuous output.
                .takeUntilOther(
                        Mono.delay(Duration.ofMillis(timeoutMs))
                                .flatMap(ignored -> Mono.error(new TimeoutException("AI 生成超时"))))
                .switchIfEmpty(Flux.error(new IllegalStateException("AI 返回空响应")));
    }

    private OpenAIClient createClient(AiGenerationSettings.Snapshot snapshot, int timeoutMs) {
        Endpoint endpoint = endpoint(snapshot.baseUrl());
        String apiRoot =
                endpoint.baseUrl()
                        + endpoint.path()
                                .substring(
                                        0, endpoint.path().length() - "/chat/completions".length());
        Duration timeout = Duration.ofMillis(timeoutMs);
        return new OpenAIClientImpl(
                ClientOptions.builder()
                        .baseUrl(apiRoot)
                        .apiKey(snapshot.apiKey())
                        // CMS owns retries so partially emitted responses are never replayed.
                        .maxRetries(0)
                        .timeout(timeout)
                        .httpClient(new NoRedirectAiHttpClient(timeout))
                        .build());
    }

    private static boolean isRetryable(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof OpenAIIoException) {
            return true;
        }
        if (error instanceof OpenAIServiceException serviceError) {
            int status = serviceError.statusCode();
            return status == 408 || status == 409 || status == 429 || status >= 500;
        }
        return false;
    }

    static Endpoint endpoint(String value) {
        URI uri = URI.create(value.trim());
        if (!List.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("模型地址必须是有效的 HTTP(S) API 地址");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) {
            path += path.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        }
        return new Endpoint(uri.getScheme() + "://" + uri.getRawAuthority(), path);
    }

    record Endpoint(String baseUrl, String path) {}
}

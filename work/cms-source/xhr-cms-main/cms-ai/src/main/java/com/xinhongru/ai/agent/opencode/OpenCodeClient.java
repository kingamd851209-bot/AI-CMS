package com.xinhongru.ai.agent.opencode;

import com.xinhongru.ai.generation.AgentExecutor;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** The only class that knows OpenCode's HTTP contract. No process or global event proxy. */
@Component
public class OpenCodeClient {
    private final OpenCodeProperties properties;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    // OpenCode's Bun server serves HTTP/1.1. Avoid the JDK's default h2c upgrade:
    // it can accept POST /session without returning a response to the JDK client.
    private final HttpClient client =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

    public OpenCodeClient(OpenCodeProperties properties) {
        this.properties = properties;
    }

    public void checkReady() {
        JsonNode health = exchange(HttpMethod.GET, "/global/health", null, null, 5_000).block();
        if (health == null || !health.path("healthy").asBoolean(false)) {
            throw new IllegalStateException("复杂任务服务健康检查失败");
        }
        JsonNode agents = exchange(HttpMethod.GET, "/agent", null, null, 5_000).block();
        if (agents == null || !agents.isArray()) throw new IllegalStateException("复杂任务服务协议不兼容");
        for (JsonNode agent : agents) {
            if (properties.getAgent().equals(agent.path("name").asText())) return;
        }
        throw new IllegalStateException("复杂任务服务未配置指定的模板执行器");
    }

    public Mono<String> createSession(AgentExecutor.Request request, String directory) {
        // Noninteractive file tools only. Each request gets a fresh workspace and session.
        var permissions = new ArrayList<Map<String, String>>();
        permissions.add(rule("*", "*", "deny"));
        request.files().keySet().stream()
                .sorted()
                .forEach(name -> allowFile(permissions, "read", directory, name));
        request.editableFiles().stream()
                .sorted()
                .forEach(name -> allowFile(permissions, "edit", directory, name));
        permissions.add(rule("external_directory", "*", "deny"));
        return exchange(
                        HttpMethod.POST,
                        "/session",
                        directory,
                        Map.of("title", "CMS " + request.taskId(), "permission", permissions),
                        10_000)
                .map(
                        body -> {
                            String id = body.path("id").asText();
                            if (!id.matches("ses_[a-zA-Z0-9]+"))
                                throw new IllegalStateException("复杂任务服务会话无效");
                            return id;
                        });
    }

    public Mono<String> run(
            String sessionId, String directory, AgentExecutor.Request request, long timeoutMs) {
        var body =
                Map.of(
                        "agent", properties.getAgent(),
                        "model",
                                Map.of(
                                        "providerID",
                                        properties.getProviderId(),
                                        "modelID",
                                        properties.getModelId()),
                        "system", request.instruction(),
                        "parts",
                                List.of(
                                        Map.of(
                                                "type",
                                                "text",
                                                "text",
                                                "以下提供完整任务上下文；当前工作目录中另有任务文件副本。\n"
                                                        + request.prompt())));
        return exchange(
                        HttpMethod.POST,
                        "/session/" + sessionId + "/message",
                        directory,
                        body,
                        timeoutMs)
                .map(
                        response -> {
                            JsonNode info = response.path("info");
                            if (!sessionId.equals(info.path("sessionID").asText())
                                    || !"assistant".equals(info.path("role").asText())
                                    || info.hasNonNull("error")
                                    || !"stop".equals(info.path("finish").asText())) {
                                throw new IllegalStateException("复杂任务未完整完成");
                            }
                            StringBuilder text = new StringBuilder();
                            for (JsonNode part : response.path("parts")) {
                                if ("text".equals(part.path("type").asText())
                                        && !part.path("ignored").asBoolean()) {
                                    if (!text.isEmpty()) text.append('\n');
                                    text.append(part.path("text").asText());
                                }
                            }
                            if (text.isEmpty() || text.length() > 2_000_000) {
                                throw new IllegalStateException("复杂任务结果为空或超过限制");
                            }
                            return text.toString();
                        });
    }

    public Mono<Void> abort(String id, String directory) {
        return exchange(HttpMethod.POST, "/session/" + id + "/abort", directory, Map.of(), 5_000)
                .flatMap(
                        body ->
                                body.asBoolean(false)
                                        ? Mono.empty()
                                        : Mono.error(new IllegalStateException("复杂任务停止未确认")));
    }

    public Mono<Void> delete(String id, String directory) {
        return exchange(HttpMethod.DELETE, "/session/" + id, directory, null, 5_000)
                .flatMap(
                        body ->
                                body.asBoolean(false)
                                        ? Mono.empty()
                                        : Mono.error(new IllegalStateException("复杂任务清理未确认")));
    }

    public Mono<Void> disposeWorkspace(String directory) {
        return exchange(HttpMethod.POST, "/instance/dispose", directory, Map.of(), 5_000)
                .flatMap(
                        body ->
                                body.asBoolean(false)
                                        ? Mono.empty()
                                        : Mono.error(new IllegalStateException("复杂任务工作空间释放未确认")));
    }

    private Mono<JsonNode> exchange(
            HttpMethod method, String path, String directory, Object body, long timeout) {
        return Mono.<JsonNode>create(
                        sink -> {
                            try {
                                String base = properties.getBaseUrl().replaceAll("/+$", "");
                                String query =
                                        directory == null
                                                ? ""
                                                : "?directory="
                                                        + URLEncoder.encode(
                                                                directory, StandardCharsets.UTF_8);
                                String credentials =
                                        properties.getUsername() + ":" + properties.getPassword();
                                var builder =
                                        HttpRequest.newBuilder(URI.create(base + path + query))
                                                .timeout(Duration.ofMillis(timeout))
                                                .header("Accept", "application/json")
                                                .header(
                                                        "Authorization",
                                                        "Basic "
                                                                + Base64.getEncoder()
                                                                        .encodeToString(
                                                                                credentials
                                                                                        .getBytes(
                                                                                                StandardCharsets
                                                                                                        .UTF_8)));
                                var publisher =
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofByteArray(
                                                        json.writeValueAsBytes(body));
                                if (body != null)
                                    builder.header("Content-Type", "application/json");
                                var future =
                                        client.sendAsync(
                                                builder.method(method.name(), publisher).build(),
                                                ignored -> new LimitedBodySubscriber());
                                AtomicBoolean cancelled = new AtomicBoolean();
                                sink.onCancel(
                                        () -> {
                                            cancelled.set(true);
                                            future.cancel(true);
                                        });
                                future.whenComplete(
                                        (response, error) -> {
                                            if (cancelled.get()) return;
                                            if (error != null
                                                    || response.statusCode() < 200
                                                    || response.statusCode() >= 300) {
                                                sink.error(new IllegalStateException("复杂任务服务请求失败"));
                                                return;
                                            }
                                            try {
                                                JsonNode value = json.readTree(response.body());
                                                if (value == null)
                                                    throw new IllegalArgumentException();
                                                sink.success(value);
                                            } catch (Exception invalid) {
                                                sink.error(new IllegalStateException("复杂任务服务响应无效"));
                                            }
                                        });
                            } catch (Exception error) {
                                sink.error(new IllegalStateException("复杂任务服务请求失败"));
                            }
                        })
                .timeout(Duration.ofMillis(timeout));
    }

    /** Enforce the response bound while receiving bytes, not after allocating an unbounded body. */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                done = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("复杂任务服务响应超过大小限制"));
            } else delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    private static Map<String, String> rule(String permission, String pattern, String action) {
        return Map.of("permission", permission, "pattern", pattern, "action", action);
    }

    private static void allowFile(
            List<Map<String, String>> rules, String tool, String directory, String name) {
        // OpenCode versions normalize paths against either the instance or project root.
        // Grant exact paths in each representation; external_directory remains denied.
        rules.add(rule(tool, directory + "/" + name, "allow"));
        rules.add(rule(tool, directory.replaceFirst("^/", "") + "/" + name, "allow"));
        rules.add(rule(tool, name, "allow"));
    }
}

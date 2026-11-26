package com.xinhongru.ai.generation;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import com.xinhongru.ai.config.AiHttpClientFactory;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK传输窄适配：不跟随任何重定向，并在流取消/关闭时释放实际HTTP请求。 */
final class NoRedirectAiHttpClient implements com.openai.core.http.HttpClient {
    private final java.net.http.HttpClient client = AiHttpClientFactory.newHttpClient();
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<Response> responses = ConcurrentHashMap.newKeySet();

    NoRedirectAiHttpClient(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        try {
            return executeAsync(request, options).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenAIIoException("AI请求被中断");
        } catch (ExecutionException ex) {
            throw new OpenAIIoException("AI请求失败");
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(
            HttpRequest request, RequestOptions options) {
        if (closed.get()) return CompletableFuture.failedFuture(new OpenAIIoException("AI请求已关闭"));
        var data = new ByteArrayOutputStream();
        if (request.body() != null) request.body().writeTo(data);
        var builder =
                java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(timeout);
        for (String name : request.headers().names())
            for (String value : request.headers().values(name))
                if (!Set.of("content-length", "host", "connection")
                        .contains(name.toLowerCase(java.util.Locale.ROOT)))
                    builder.header(name, value);
        if (request.body() != null
                && request.body().contentType() != null
                && request.headers().values("content-type").isEmpty())
            builder.header("Content-Type", request.body().contentType());
        builder.method(
                request.method().toString(),
                java.net.http.HttpRequest.BodyPublishers.ofByteArray(data.toByteArray()));
        var network =
                client.sendAsync(
                        builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        pending.add(network);
        return network.thenApply(
                        reply -> {
                            var response = new Response(reply);
                            responses.add(response);
                            if (closed.get()) {
                                response.close();
                                throw new OpenAIIoException("AI请求已关闭");
                            }
                            return (HttpResponse) response;
                        })
                .whenComplete((value, error) -> pending.remove(network));
    }

    private final class Response implements HttpResponse {
        private final java.net.http.HttpResponse<InputStream> response;
        private final InputStream body;

        Response(java.net.http.HttpResponse<InputStream> response) {
            this.response = response;
            if (response.statusCode() >= 300) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                }
                body =
                        new ByteArrayInputStream(
                                "{\"error\":{\"message\":\"AI gateway request rejected\",\"type\":\"upstream_error\"}}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else body = response.body();
        }

        public int statusCode() {
            return response.statusCode();
        }

        public Headers headers() {
            var result = Headers.builder();
            response.headers()
                    .map()
                    .forEach((name, values) -> values.forEach(value -> result.put(name, value)));
            return result.build();
        }

        public InputStream body() {
            return body;
        }

        public void close() {
            responses.remove(this);
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pending.forEach(value -> value.cancel(true));
            responses.forEach(Response::close);
            client.shutdownNow();
        }
    }
}

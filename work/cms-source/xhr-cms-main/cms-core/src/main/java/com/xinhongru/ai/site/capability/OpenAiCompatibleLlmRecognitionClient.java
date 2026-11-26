package com.xinhongru.ai.site.capability;

import com.xinhongru.ai.config.AiHttpClientFactory;
import com.xinhongru.ai.config.AiProviderConfigService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(
        name = "cms.ai.recognition.llm-client",
        havingValue = OpenAiCompatibleLlmRecognitionClient.PROVIDER)
public class OpenAiCompatibleLlmRecognitionClient implements LlmRecognitionClient {
    public static final String PROVIDER = "openai-compatible";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROMPT_VERSION = "cms-ai-default-v1";
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private final HttpTransport httpTransport;

    public OpenAiCompatibleLlmRecognitionClient() {
        this(new JdkHttpTransport());
    }

    OpenAiCompatibleLlmRecognitionClient(HttpTransport httpTransport) {
        this.httpTransport = httpTransport == null ? new JdkHttpTransport() : httpTransport;
    }

    @Override
    public AssistResult assist(ClientRequest request) {
        try {
            if (request == null || request.runtimeConfig() == null) {
                return AssistResult.skipped("RUNTIME_CONFIG_MISSING");
            }
            AiProviderConfigService.RuntimeConfig config = request.runtimeConfig();
            if (isBlank(config.baseUrl())
                    || isBlank(config.modelName())
                    || isBlank(config.apiKey())) {
                return AssistResult.skipped("RUNTIME_CONFIG_INCOMPLETE");
            }
            String body =
                    requestBody(config.modelName(), config.promptVersion(), request.requestJson());
            HttpRequest httpRequest =
                    HttpRequest.newBuilder(chatCompletionsUri(config.baseUrl()))
                            .timeout(Duration.ofMillis(timeoutMs(config.timeoutMs())))
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResult response = sendWithRetry(httpRequest, body, config.maxRetries());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return AssistResult.failed(
                        "HTTP_ERROR",
                        "HTTP " + response.statusCode() + ": " + limit(response.body(), 1000));
            }
            String content = extractContent(response.body());
            return AssistResult.success(parseSuggestions(content), content);
        } catch (RuntimeException ex) {
            return AssistResult.failed("CLIENT_FAILED", safeMessage(ex));
        }
    }

    private String requestBody(String modelName, String promptVersion, String requestJson) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        messages.add(message("system", systemPrompt(promptVersion)));
        messages.add(message("user", requestJson == null ? "{}" : requestJson));
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JacksonException ex) {
            throw new IllegalStateException("LLM recognition request serialization failed", ex);
        }
    }

    private String systemPrompt(String promptVersion) {
        return "You are a CMS site capability recognition assistant. "
                + "Prompt version: "
                + normalizedPromptVersion(promptVersion)
                + ". Review the sanitized rule evidence and return only JSON. The JSON object must"
                + " be {\"suggestions\":[{\"capabilityCode\",\"action\",\"confidence\",\"reason\",\"evidence\"}]}."
                + " Use confidence from 1 to 100. Do not include markdown, prose, or raw source"
                + " content.";
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = OBJECT_MAPPER.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String normalizedPromptVersion(String promptVersion) {
        return isBlank(promptVersion) ? DEFAULT_PROMPT_VERSION : promptVersion.trim();
    }

    private HttpResult sendWithRetry(HttpRequest request, String body, Integer maxRetries) {
        RuntimeException lastFailure = null;
        int retries = maxRetries == null ? 0 : Math.max(0, maxRetries);
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpResult response = httpTransport.send(request, body);
                if (!retryable(response.statusCode()) || attempt == retries) {
                    return response;
                }
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt == retries) {
                    throw ex;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("LLM recognition provider failed")
                : lastFailure;
    }

    private boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return validateChatCompletionsUri(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return validateChatCompletionsUri(normalized + "/chat/completions");
        }
        return validateChatCompletionsUri(normalized + "/v1/chat/completions");
    }

    private URI validateChatCompletionsUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme =
                    uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalStateException(
                        "LLM recognition provider baseUrl must use http/https protocol");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException(
                        "LLM recognition provider baseUrl host is required");
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw new IllegalStateException("LLM recognition provider baseUrl is invalid", ex);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("LLM recognition provider response has no choices");
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            JsonNode content = message == null ? firstChoice.get("text") : message.get("content");
            if (content == null || content.asText().isBlank()) {
                throw new IllegalStateException("LLM recognition provider response has no content");
            }
            return content.asText().trim();
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "LLM recognition provider response is invalid JSON", ex);
        }
    }

    private List<AssistSuggestion> parseSuggestions(String content) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(stripMarkdownFence(content));
            JsonNode suggestionsNode = root.isArray() ? root : root.get("suggestions");
            if (suggestionsNode == null || !suggestionsNode.isArray()) {
                return List.of();
            }
            List<AssistSuggestion> suggestions = new ArrayList<>();
            for (JsonNode node : suggestionsNode) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                suggestions.add(
                        new AssistSuggestion(
                                text(node, "capabilityCode"),
                                text(node, "action"),
                                intValue(node, "confidence"),
                                text(node, "reason"),
                                evidence(node.get("evidence"))));
            }
            return List.copyOf(suggestions);
        } catch (JacksonException ex) {
            throw new IllegalStateException("LLM recognition suggestion JSON is invalid", ex);
        }
    }

    private String stripMarkdownFence(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd >= 0) {
            value = value.substring(firstLineEnd + 1).trim();
        } else {
            value = value.substring(3).trim();
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3).trim();
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        Integer parsed;
        if (value.isNumber()) {
            parsed = value.asInt();
        } else {
            try {
                parsed = Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return Math.max(1, Math.min(parsed, 100));
    }

    private List<String> evidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> evidence = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    evidence.add(item.asText().trim());
                }
            }
            return List.copyOf(evidence);
        }
        String text = node.asText();
        return text == null || text.isBlank() ? List.of() : List.of(text.trim());
    }

    private int timeoutMs(Integer value) {
        return value == null || value <= 0 ? DEFAULT_TIMEOUT_MS : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : limit(message, 1000);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @FunctionalInterface
    public interface HttpTransport {
        HttpResult send(HttpRequest request, String body);
    }

    public record HttpResult(int statusCode, String body) {}

    private static final class JdkHttpTransport implements HttpTransport {
        @Override
        public HttpResult send(HttpRequest request, String body) {
            try {
                HttpResponse<String> response =
                        AiHttpClientFactory.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString());
                return new HttpResult(response.statusCode(), response.body());
            } catch (IOException ex) {
                throw new IllegalStateException("LLM recognition provider HTTP request failed", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "LLM recognition provider HTTP request interrupted", ex);
            }
        }
    }
}

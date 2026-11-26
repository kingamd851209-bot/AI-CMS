package com.xinhongru.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AiProviderConfigService implements com.xinhongru.ai.generation.AiGenerationSettings {
    public static final String GENERATION_USAGE = "GENERATION";
    private static final String GENERATION_PROVIDER = "SYSTEM_GENERATION";
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final String DEFAULT_PROMPT_VERSION = "cms-ai-default-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SECRET_CONTEXT_PATTERN =
            Pattern.compile(
                    "(?i)\\b(?:api[_-]?key|token|password|secret|authorization)\\b\\s*[:=]\\s*[^\\s,;]+|\\bsk-[a-z0-9][a-z0-9_-]*\\b");

    private final AiProviderConfigMapper mapper;
    private final AiSecretCryptoService cryptoService;
    private final ProviderConnectionTester providerConnectionTester;

    @Autowired
    public AiProviderConfigService(
            AiProviderConfigMapper mapper, AiSecretCryptoService cryptoService) {
        this(mapper, cryptoService, new OpenAiCompatibleProviderConnectionTester());
    }

    AiProviderConfigService(
            AiProviderConfigMapper mapper,
            AiSecretCryptoService cryptoService,
            ProviderConnectionTester providerConnectionTester) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.providerConnectionTester =
                providerConnectionTester == null
                        ? new OpenAiCompatibleProviderConnectionTester()
                        : providerConnectionTester;
    }

    public record GenerationRequest(
            boolean enabled,
            String baseUrl,
            String modelName,
            String apiKey,
            boolean clearApiKey,
            int timeoutMs,
            int maxRetries) {
        @Override
        public String toString() {
            return "GenerationRequest[enabled=" + enabled + ", apiKey=***]";
        }
    }

    public record GenerationView(
            boolean enabled,
            String baseUrl,
            String modelName,
            boolean configured,
            int timeoutMs,
            int maxRetries,
            String source) {}

    @Override
    public Snapshot generationSnapshot() {
        var entity = findExisting(GENERATION_PROVIDER, GENERATION_USAGE);
        if (entity == null) return null;
        return new Snapshot(
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getBaseUrl(),
                entity.getModelName(),
                hasApiKey(entity)
                        ? cryptoService.decrypt(entity.getApiKeyCipher(), entity.getApiKeyNonce())
                        : "",
                entity.getTimeoutMs() == null ? 30000 : entity.getTimeoutMs(),
                entity.getMaxRetries() == null ? 0 : entity.getMaxRetries());
    }

    public GenerationView generationView() {
        var value = generationSnapshot();
        return value == null
                ? null
                : new GenerationView(
                        value.enabled(),
                        value.baseUrl(),
                        value.modelName(),
                        value.configured(),
                        value.timeoutMs(),
                        value.maxRetries(),
                        "SYSTEM");
    }

    @Transactional
    public GenerationView saveGeneration(GenerationRequest request) {
        if (request == null
                || request.modelName() == null
                || request.modelName().isBlank()
                || request.modelName().length() > 128
                || request.timeoutMs() < 1000
                || request.timeoutMs() > 300000
                || request.maxRetries() < 0
                || request.maxRetries() > 2
                || request.apiKey() != null
                        && (request.apiKey().length() > 4096
                                || request.apiKey().chars().anyMatch(Character::isISOControl))
                || request.clearApiKey() && request.apiKey() != null && !request.apiKey().isBlank())
            throw new IllegalArgumentException("AI配置或密钥清除选项无效");
        String url =
                com.xinhongru.ai.generation.SpringAiTextGenerator.normalizedEndpoint(
                        request.baseUrl());
        if (url.length() > 512) throw new IllegalArgumentException("AI地址规范化后不能超过512字符");
        save(
                new SaveRequest(
                        GENERATION_PROVIDER,
                        "系统普通模型",
                        GENERATION_USAGE,
                        request.modelName(),
                        url,
                        request.apiKey(),
                        request.enabled(),
                        request.timeoutMs(),
                        null,
                        request.maxRetries(),
                        null,
                        null,
                        null));
        if (request.clearApiKey()) {
            var entity = findExisting(GENERATION_PROVIDER, GENERATION_USAGE);
            entity.setApiKeyCipher("").setApiKeyNonce("");
            mapper.updateById(entity);
        }
        return generationView();
    }

    public TestResult testGeneration() {
        var entity = findExisting(GENERATION_PROVIDER, GENERATION_USAGE);
        if (entity == null) return new TestResult(false, "请先保存系统AI配置", 0L, null);
        return test(entity.getId());
    }

    public List<ProviderConfigView> list() {
        LambdaQueryWrapper<AiProviderConfigEntity> wrapper = baseQuery();
        wrapper.orderByAsc(AiProviderConfigEntity::getUsageType)
                .orderByAsc(AiProviderConfigEntity::getProviderCode)
                .orderByAsc(AiProviderConfigEntity::getId);
        List<AiProviderConfigEntity> entities = mapper.selectList(wrapper);
        return safeList(entities).stream().map(this::toView).toList();
    }

    @Transactional
    public ProviderConfigView save(SaveRequest request) {
        validate(request);
        AiProviderConfigEntity entity = findExisting(request.providerCode(), request.usageType());
        boolean create = entity == null;
        if (create) {
            entity = new AiProviderConfigEntity();
        }
        entity.setProviderCode(trimToNull(request.providerCode()))
                .setProviderName(trimToNull(request.providerName()))
                .setUsageType(normalizeUsage(request.usageType()))
                .setModelName(trimToNull(request.modelName()))
                .setBaseUrl(normalizeUrl(request.baseUrl()))
                .setEnabled(Boolean.TRUE.equals(request.enabled()) ? 1 : 0)
                .setTimeoutMs(
                        request.timeoutMs() != null && request.timeoutMs() > 0
                                ? request.timeoutMs()
                                : DEFAULT_TIMEOUT_MS)
                .setDailyBudgetCents(request.dailyBudgetCents())
                .setMaxRetries(normalizeMaxRetries(request.maxRetries()))
                .setDailyTokenBudget(positiveOrNull(request.dailyTokenBudget()))
                .setMonthlyCostBudgetCents(positiveOrNull(request.monthlyCostBudgetCents()))
                .setPromptVersion(defaultIfBlank(request.promptVersion(), DEFAULT_PROMPT_VERSION))
                .setDeleted(0);
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            AiSecretCryptoService.CipherText encrypted = cryptoService.encrypt(request.apiKey());
            entity.setApiKeyCipher(encrypted.cipher()).setApiKeyNonce(encrypted.nonce());
        }
        if (create) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return toView(entity, maskApiKey(request.apiKey(), hasApiKey(entity)));
    }

    @Transactional
    public void disable(Long id) {
        AiProviderConfigEntity entity = require(id);
        entity.setEnabled(0);
        mapper.updateById(entity);
    }

    public ProviderConfigView chooseDefault(String usageType) {
        String normalizedUsage = normalizeUsage(usageType);
        return safeList(mapper.selectList(baseQuery())).stream()
                .filter(entity -> normalizedUsage.equals(entity.getUsageType()))
                .filter(entity -> Integer.valueOf(1).equals(entity.getEnabled()))
                .sorted(
                        Comparator.comparing(
                                AiProviderConfigEntity::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .findFirst()
                .map(this::toView)
                .orElse(null);
    }

    public RuntimeConfig findRuntimeConfig(String usageType) {
        String normalizedUsage = normalizeUsage(usageType);
        if (isBlank(normalizedUsage)) {
            return null;
        }
        return safeList(mapper.selectList(baseQuery())).stream()
                .filter(entity -> normalizedUsage.equals(entity.getUsageType()))
                .filter(entity -> Integer.valueOf(1).equals(entity.getEnabled()))
                .filter(entity -> !isBlank(entity.getBaseUrl()))
                .filter(entity -> !isBlank(entity.getModelName()))
                .filter(this::hasApiKey)
                .sorted(
                        Comparator.comparing(
                                AiProviderConfigEntity::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .findFirst()
                .map(this::toRuntimeConfig)
                .orElse(null);
    }

    public TestResult test(Long id) {
        AiProviderConfigEntity entity = require(id);
        if (!Integer.valueOf(1).equals(entity.getEnabled())) {
            return new TestResult(false, "Provider 未启用", 0L, null, null, null);
        }
        if (isBlank(entity.getBaseUrl())) {
            return new TestResult(false, "Base URL 未配置", 0L, null, null, null);
        }
        if (!hasApiKey(entity)) {
            return new TestResult(false, "API Key 未配置", 0L, null, null, null);
        }
        if (isBlank(entity.getModelName())) {
            return new TestResult(false, "模型名称未配置", 0L, null, null, null);
        }
        return providerConnectionTester.test(toRuntimeConfig(entity));
    }

    private AiProviderConfigEntity findExisting(String providerCode, String usageType) {
        LambdaQueryWrapper<AiProviderConfigEntity> wrapper = baseQuery();
        wrapper.eq(AiProviderConfigEntity::getProviderCode, trimToNull(providerCode))
                .eq(AiProviderConfigEntity::getUsageType, normalizeUsage(usageType))
                .orderByAsc(AiProviderConfigEntity::getId)
                .last("LIMIT 1");
        return safeList(mapper.selectList(wrapper)).stream().findFirst().orElse(null);
    }

    private AiProviderConfigEntity require(Long id) {
        AiProviderConfigEntity entity = mapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new IllegalArgumentException("AI Provider 配置不存在");
        }
        return entity;
    }

    private LambdaQueryWrapper<AiProviderConfigEntity> baseQuery() {
        LambdaQueryWrapper<AiProviderConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiProviderConfigEntity::getDeleted, 0);
        return wrapper;
    }

    private ProviderConfigView toView(AiProviderConfigEntity entity) {
        return toView(entity, maskApiKey(null, hasApiKey(entity)));
    }

    private RuntimeConfig toRuntimeConfig(AiProviderConfigEntity entity) {
        return new RuntimeConfig(
                entity.getProviderCode(),
                entity.getBaseUrl(),
                entity.getModelName(),
                entity.getTimeoutMs() != null && entity.getTimeoutMs() > 0
                        ? entity.getTimeoutMs()
                        : DEFAULT_TIMEOUT_MS,
                normalizeMaxRetries(entity.getMaxRetries()),
                positiveOrNull(entity.getDailyTokenBudget()),
                positiveOrNull(entity.getMonthlyCostBudgetCents()),
                defaultIfBlank(entity.getPromptVersion(), DEFAULT_PROMPT_VERSION),
                cryptoService.decrypt(entity.getApiKeyCipher(), entity.getApiKeyNonce()));
    }

    private ProviderConfigView toView(AiProviderConfigEntity entity, String apiKeyMasked) {
        return new ProviderConfigView(
                entity.getId(),
                entity.getProviderCode(),
                entity.getProviderName(),
                entity.getUsageType(),
                entity.getModelName(),
                entity.getBaseUrl(),
                Integer.valueOf(1).equals(entity.getEnabled()),
                apiKeyMasked,
                hasApiKey(entity),
                entity.getTimeoutMs(),
                entity.getDailyBudgetCents(),
                normalizeMaxRetries(entity.getMaxRetries()),
                positiveOrNull(entity.getDailyTokenBudget()),
                positiveOrNull(entity.getMonthlyCostBudgetCents()),
                defaultIfBlank(entity.getPromptVersion(), DEFAULT_PROMPT_VERSION));
    }

    private String maskApiKey(String plainApiKey, boolean hasApiKey) {
        if (plainApiKey != null && !plainApiKey.isBlank()) {
            String trimmed = plainApiKey.trim();
            if (trimmed.length() <= 8) {
                return "***";
            }
            return "已配置";
        }
        return hasApiKey ? "已配置" : "未配置";
    }

    private boolean hasApiKey(AiProviderConfigEntity entity) {
        return entity.getApiKeyCipher() != null
                && !entity.getApiKeyCipher().isBlank()
                && entity.getApiKeyNonce() != null
                && !entity.getApiKeyNonce().isBlank();
    }

    private void validate(SaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AI Provider 配置不能为空");
        }
        if (isBlank(request.providerCode())) {
            throw new IllegalArgumentException("Provider 不能为空");
        }
        if (isBlank(request.usageType())) {
            throw new IllegalArgumentException("模型用途不能为空");
        }
        if (isBlank(request.baseUrl())) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
    }

    private String normalizeUsage(String value) {
        return value == null ? null : value.trim().toUpperCase().replace('-', '_');
    }

    private String normalizeUrl(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        try {
            URI uri = new URI(normalized);
            String scheme =
                    uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("Base URL must use http/https protocol");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Base URL host is required");
            }
            return normalized;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Base URL is invalid", ex);
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private int normalizeMaxRetries(Integer value) {
        if (value == null) {
            return DEFAULT_MAX_RETRIES;
        }
        return Math.max(0, Math.min(value, 5));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<AiProviderConfigEntity> safeList(List<AiProviderConfigEntity> entities) {
        return entities == null ? List.of() : entities;
    }

    public record SaveRequest(
            String providerCode,
            String providerName,
            String usageType,
            String modelName,
            String baseUrl,
            String apiKey,
            Boolean enabled,
            Integer timeoutMs,
            Integer dailyBudgetCents,
            Integer maxRetries,
            Integer dailyTokenBudget,
            Integer monthlyCostBudgetCents,
            String promptVersion) {
        public SaveRequest(
                String providerCode,
                String providerName,
                String usageType,
                String modelName,
                String baseUrl,
                String apiKey,
                Boolean enabled,
                Integer timeoutMs,
                Integer dailyBudgetCents) {
            this(
                    providerCode,
                    providerName,
                    usageType,
                    modelName,
                    baseUrl,
                    apiKey,
                    enabled,
                    timeoutMs,
                    dailyBudgetCents,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        public String toString() {
            return "SaveRequest["
                    + "providerCode="
                    + providerCode
                    + ", providerName="
                    + providerName
                    + ", usageType="
                    + usageType
                    + ", modelName="
                    + modelName
                    + ", baseUrl="
                    + baseUrl
                    + ", apiKey="
                    + (apiKey == null || apiKey.isBlank() ? "null" : "***")
                    + ", enabled="
                    + enabled
                    + ", timeoutMs="
                    + timeoutMs
                    + ", dailyBudgetCents="
                    + dailyBudgetCents
                    + ", maxRetries="
                    + maxRetries
                    + ", dailyTokenBudget="
                    + dailyTokenBudget
                    + ", monthlyCostBudgetCents="
                    + monthlyCostBudgetCents
                    + ", promptVersion="
                    + promptVersion
                    + ']';
        }
    }

    public record ProviderConfigView(
            Long id,
            String providerCode,
            String providerName,
            String usageType,
            String modelName,
            String baseUrl,
            boolean enabled,
            String apiKeyMasked,
            boolean hasApiKey,
            Integer timeoutMs,
            Integer dailyBudgetCents,
            Integer maxRetries,
            Integer dailyTokenBudget,
            Integer monthlyCostBudgetCents,
            String promptVersion) {
        public ProviderConfigView(
                Long id,
                String providerCode,
                String providerName,
                String usageType,
                String modelName,
                String baseUrl,
                boolean enabled,
                String apiKeyMasked,
                boolean hasApiKey,
                Integer timeoutMs,
                Integer dailyBudgetCents) {
            this(
                    id,
                    providerCode,
                    providerName,
                    usageType,
                    modelName,
                    baseUrl,
                    enabled,
                    apiKeyMasked,
                    hasApiKey,
                    timeoutMs,
                    dailyBudgetCents,
                    DEFAULT_MAX_RETRIES,
                    null,
                    null,
                    DEFAULT_PROMPT_VERSION);
        }
    }

    public record TestResult(
            boolean success,
            String message,
            Long durationMs,
            Integer statusCode,
            Long outputTokens,
            Double outputTokensPerSecond) {
        public TestResult(boolean success, String message, Long durationMs, Integer statusCode) {
            this(success, message, durationMs, statusCode, null, null);
        }
    }

    @FunctionalInterface
    public interface ProviderConnectionTester {
        TestResult test(RuntimeConfig config);
    }

    /**
     * Returns a default ProviderConnectionTester implementation for OpenAI-compatible providers.
     */
    public static ProviderConnectionTester defaultConnectionTester() {
        return new OpenAiCompatibleProviderConnectionTester();
    }

    public record RuntimeConfig(
            String providerCode,
            String baseUrl,
            String modelName,
            Integer timeoutMs,
            Integer maxRetries,
            Integer dailyTokenBudget,
            Integer monthlyCostBudgetCents,
            String promptVersion,
            String apiKey) {
        public RuntimeConfig(
                String providerCode,
                String baseUrl,
                String modelName,
                Integer timeoutMs,
                String apiKey) {
            this(
                    providerCode,
                    baseUrl,
                    modelName,
                    timeoutMs,
                    DEFAULT_MAX_RETRIES,
                    null,
                    null,
                    DEFAULT_PROMPT_VERSION,
                    apiKey);
        }

        @Override
        public String toString() {
            return "RuntimeConfig["
                    + "providerCode="
                    + providerCode
                    + ", baseUrl="
                    + baseUrl
                    + ", modelName="
                    + modelName
                    + ", timeoutMs="
                    + timeoutMs
                    + ", maxRetries="
                    + maxRetries
                    + ", dailyTokenBudget="
                    + dailyTokenBudget
                    + ", monthlyCostBudgetCents="
                    + monthlyCostBudgetCents
                    + ", promptVersion="
                    + promptVersion
                    + ", apiKey="
                    + (apiKey == null || apiKey.isBlank() ? "null" : "***")
                    + ']';
        }
    }

    private static final class OpenAiCompatibleProviderConnectionTester
            implements ProviderConnectionTester {
        @Override
        public TestResult test(RuntimeConfig config) {
            long startedAt = System.nanoTime();
            try {
                HttpRequest request =
                        HttpRequest.newBuilder(providerTestUri(config.baseUrl()))
                                .timeout(Duration.ofMillis(timeoutMs(config.timeoutMs())))
                                .header("Authorization", "Bearer " + config.apiKey())
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                requestBody(
                                                        config.modelName(),
                                                        config.promptVersion())))
                                .build();
                HttpResponse<String> response =
                        AiHttpClientFactory.newHttpClient()
                                .send(request, AiHttpClientFactory.limitedUtf8Body(65536));
                long durationMs = durationMsSince(startedAt);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var parsed = OBJECT_MAPPER.readTree(response.body());
                    if (!parsed.path("choices").isArray() || parsed.path("choices").isEmpty())
                        return new TestResult(
                                false, "连接已响应但未返回有效模型结果", durationMs, response.statusCode());
                    Long outputTokens = outputTokens(response.body());
                    return new TestResult(
                            true,
                            "外部连接测试成功（HTTP " + response.statusCode() + "）",
                            durationMs,
                            response.statusCode(),
                            outputTokens,
                            outputTokensPerSecond(outputTokens, durationMs));
                }
                return new TestResult(
                        false,
                        "外部连接测试失败（HTTP " + response.statusCode() + "），请检查地址、模型、权限或额度",
                        durationMs,
                        response.statusCode());
            } catch (IOException ex) {
                return new TestResult(
                        false, "外部连接测试请求失败，请检查地址、网络或超时", durationMsSince(startedAt), null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new TestResult(false, "外部连接测试请求被中断", durationMsSince(startedAt), null);
            } catch (RuntimeException ex) {
                return new TestResult(false, "外部连接测试失败，请检查配置", durationMsSince(startedAt), null);
            }
        }

        private String requestBody(String modelName, String promptVersion) {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("model", modelName);
            root.put("temperature", 0);
            root.put("max_tokens", 180);
            ArrayNode messages = root.putArray("messages");
            messages.add(message("system", "你是 CMS AI Provider 连接和输出速度测试助手。请直接回答，不要使用 Markdown。"));
            messages.add(
                    message(
                            "user",
                            "请回答一个中等难度问题：简要解释 CMS 中内容模型、字段校验和发布流程之间的关系。请输出 120 到 180 个中文字。Prompt"
                                    + " version: "
                                    + defaultIfBlankStatic(promptVersion, DEFAULT_PROMPT_VERSION)));
            try {
                return OBJECT_MAPPER.writeValueAsString(root);
            } catch (JacksonException ex) {
                throw new IllegalStateException(
                        "AI provider test request serialization failed", ex);
            }
        }

        private ObjectNode message(String role, String content) {
            ObjectNode message = OBJECT_MAPPER.createObjectNode();
            message.put("role", role);
            message.put("content", content);
            return message;
        }

        private URI providerTestUri(String baseUrl) {
            return validateProviderTestUri(baseUrl == null ? "" : baseUrl.trim());
        }

        private URI validateProviderTestUri(String value) {
            try {
                URI uri = new URI(value);
                String scheme =
                        uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    throw new IllegalStateException("Base URL must use http/https protocol");
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalStateException("Base URL host is required");
                }
                return uri;
            } catch (URISyntaxException | IllegalArgumentException ex) {
                throw new IllegalStateException("Base URL is invalid", ex);
            }
        }

        private int timeoutMs(Integer value) {
            return value == null || value <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(value, 30000);
        }

        private long durationMsSince(long startedAt) {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }

        private Long outputTokens(String responseBody) {
            try {
                JsonNode usage = OBJECT_MAPPER.readTree(responseBody).path("usage");
                long completionTokens = usage.path("completion_tokens").asLong(-1L);
                if (completionTokens >= 0) {
                    return completionTokens;
                }
                long outputTokens = usage.path("output_tokens").asLong(-1L);
                return outputTokens >= 0 ? outputTokens : null;
            } catch (JacksonException ex) {
                return null;
            }
        }

        private Double outputTokensPerSecond(Long outputTokens, Long durationMs) {
            if (outputTokens == null || outputTokens < 0 || durationMs == null || durationMs <= 0) {
                return null;
            }
            return outputTokens * 1000.0d / durationMs;
        }

        private String safeMessage(Exception ex) {
            String message = ex.getMessage();
            return message == null || message.isBlank()
                    ? ex.getClass().getSimpleName()
                    : limit(message, 1000);
        }

        private String limit(String value, int maxLength) {
            if (value == null) {
                return "";
            }
            return value.length() <= maxLength ? value : value.substring(0, maxLength);
        }

        private String redact(String value, String apiKey) {
            String redacted = value == null ? "" : value;
            if (apiKey != null && !apiKey.isBlank()) {
                redacted = redacted.replace(apiKey, "[REDACTED]");
            }
            return SECRET_CONTEXT_PATTERN.matcher(redacted).replaceAll("[REDACTED]");
        }

        private String defaultIfBlankStatic(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}

package com.xinhongru.cms.template;

import com.xinhongru.ai.config.AiProviderConfigEntity;
import com.xinhongru.ai.config.AiProviderConfigMapper;
import com.xinhongru.ai.config.AiProviderConfigService;
import com.xinhongru.ai.config.AiProviderConfigService.ProviderConnectionTester;
import com.xinhongru.ai.config.AiProviderConfigService.RuntimeConfig;
import com.xinhongru.ai.config.AiProviderConfigService.TestResult;
import com.xinhongru.ai.config.AiSecretCryptoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 模板识别AI服务健康检查服务 */
@Service
public class TemplateAiHealthService {
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final String DEFAULT_PROMPT_VERSION = "cms-ai-default-v1";

    private final AiProviderConfigMapper providerMapper;
    private final AiSecretCryptoService cryptoService;
    private final ProviderConnectionTester connectionTester;

    @Autowired
    public TemplateAiHealthService(
            AiProviderConfigMapper providerMapper, AiSecretCryptoService cryptoService) {
        this(providerMapper, cryptoService, null);
    }

    TemplateAiHealthService(
            AiProviderConfigMapper providerMapper,
            AiSecretCryptoService cryptoService,
            ProviderConnectionTester connectionTester) {
        this.providerMapper = providerMapper;
        this.cryptoService = cryptoService;
        this.connectionTester =
                connectionTester != null
                        ? connectionTester
                        : AiProviderConfigService.defaultConnectionTester();
    }

    /**
     * 检查AI服务健康状态
     *
     * @param usageType 用途类型，如 "RECOGNITION"
     * @return 健康检查结果
     */
    public AiHealthResult checkHealth(String usageType) {
        String normalizedUsage = normalizeUsage(usageType);

        // 1. 查找启用的Provider
        AiProviderConfigEntity provider = findEnabledProvider(normalizedUsage);
        if (provider == null) {
            return AiHealthResult.noProvider("未找到启用的AI Provider，请先在系统设置中配置");
        }

        // 2. 检查配置完整性
        String configError = checkConfigIncomplete(provider);
        if (configError != null) {
            return AiHealthResult.configIncomplete(
                    configError, provider.getProviderName(), provider.getModelName());
        }

        // 3. 执行连接测试
        RuntimeConfig config = toRuntimeConfig(provider);
        TestResult testResult = connectionTester.test(config);

        if (!testResult.success()) {
            return AiHealthResult.connectionFailed(
                    "AI服务连接失败: " + testResult.message(),
                    provider.getProviderName(),
                    provider.getModelName());
        }

        // 4. 返回健康状态
        return AiHealthResult.healthy(
                provider.getProviderName(), provider.getModelName(), testResult.durationMs());
    }

    private AiProviderConfigEntity findEnabledProvider(String usageType) {
        List<AiProviderConfigEntity> allProviders = providerMapper.selectList(null);
        if (allProviders == null || allProviders.isEmpty()) {
            return null;
        }

        return allProviders.stream()
                .filter(p -> p != null && !Integer.valueOf(1).equals(p.getDeleted()))
                .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                .filter(p -> usageType.equals(normalizeUsage(p.getUsageType())))
                .sorted(
                        Comparator.comparing(
                                AiProviderConfigEntity::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .findFirst()
                .orElse(null);
    }

    private String checkConfigIncomplete(AiProviderConfigEntity provider) {
        if (isBlank(provider.getBaseUrl())) {
            return "Base URL 未配置";
        }
        if (isBlank(provider.getModelName())) {
            return "模型名称未配置";
        }
        if (isBlank(provider.getApiKeyCipher()) || isBlank(provider.getApiKeyNonce())) {
            return "API Key 未配置";
        }
        return null;
    }

    private RuntimeConfig toRuntimeConfig(AiProviderConfigEntity entity) {
        String apiKey = cryptoService.decrypt(entity.getApiKeyCipher(), entity.getApiKeyNonce());
        return new RuntimeConfig(
                entity.getProviderCode(),
                entity.getBaseUrl(),
                entity.getModelName(),
                entity.getTimeoutMs() != null && entity.getTimeoutMs() > 0
                        ? entity.getTimeoutMs()
                        : DEFAULT_TIMEOUT_MS,
                entity.getMaxRetries() != null ? entity.getMaxRetries() : DEFAULT_MAX_RETRIES,
                entity.getDailyTokenBudget(),
                entity.getMonthlyCostBudgetCents(),
                DEFAULT_PROMPT_VERSION,
                apiKey);
    }

    private static String normalizeUsage(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

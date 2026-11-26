package com.xinhongru.ai.generation;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Ordinary model calls use one explicit configuration in the active environment YAML. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cms.ai.generation")
public class AiGenerationProperties {
    private boolean enabled;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String modelName = "";
    private int timeoutMs = 120_000;
    private int maxRetries;
    private int maxConcurrent = 4;
    private int domainAgentContextTokens = 220_000;
    private int domainAgentCompletionTokens = 16_384;

    public void checkConfigured() {
        if (!enabled || baseUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) {
            throw new IllegalStateException(
                    "请在当前环境配置文件的 cms.ai.generation 中启用服务并填写模型地址、API Key 和模型名称");
        }
        SpringAiTextGenerator.endpoint(baseUrl);
    }
}

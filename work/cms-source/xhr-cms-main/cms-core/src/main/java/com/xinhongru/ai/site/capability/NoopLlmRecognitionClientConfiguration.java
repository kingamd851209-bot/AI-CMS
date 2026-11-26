package com.xinhongru.ai.site.capability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NoopLlmRecognitionClientConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "cms.ai.recognition.llm-client",
            havingValue = "noop",
            matchIfMissing = true)
    @ConditionalOnMissingBean(LlmRecognitionClient.class)
    NoopLlmRecognitionClient noopLlmRecognitionClient() {
        return new NoopLlmRecognitionClient();
    }
}

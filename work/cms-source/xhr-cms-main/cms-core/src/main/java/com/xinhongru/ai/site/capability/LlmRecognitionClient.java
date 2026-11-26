package com.xinhongru.ai.site.capability;

import com.xinhongru.ai.config.AiProviderConfigService;

public interface LlmRecognitionClient {
    AssistResult assist(ClientRequest request);

    record ClientRequest(AiProviderConfigService.RuntimeConfig runtimeConfig, String requestJson) {}
}

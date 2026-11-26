package com.xinhongru.ai.site.capability;

public class NoopLlmRecognitionClient implements LlmRecognitionClient {
    @Override
    public AssistResult assist(ClientRequest request) {
        return AssistResult.skipped("CLIENT_NOT_IMPLEMENTED");
    }
}

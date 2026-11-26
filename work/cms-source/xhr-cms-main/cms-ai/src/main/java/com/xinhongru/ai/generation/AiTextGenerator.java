package com.xinhongru.ai.generation;

import reactor.core.publisher.Flux;

public interface AiTextGenerator {
    default AiTextGenerator forTask() {
        return this;
    }

    void checkConfigured(AiUsage usage);

    Flux<String> stream(AiUsage usage, String prompt);

    /** 本地输入预算预检失败，尚未发起模型请求。 */
    final class InputBudgetExceededException extends IllegalArgumentException {
        public InputBudgetExceededException(long inputTokens, int inputBudget) {
            super("Agent 输入估算为 " + inputTokens + " tokens，超过可用输入预算 " + inputBudget + " tokens");
        }
    }

    default String generate(AiUsage usage, String prompt) {
        return stream(usage, prompt)
                .collectList()
                .map(parts -> AiOutputValidator.validate(usage, String.join("", parts)))
                .block();
    }
}

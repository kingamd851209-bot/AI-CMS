package com.xinhongru.cms.i18n;

import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;

import org.springframework.stereotype.Component;

/** LLM：直连大模型（AiTextGenerator#generate）。 */
@Component
public class SpringAiTextTranslationEngine implements TextTranslationEngine {
    private final AiTextGenerator generator;

    public SpringAiTextTranslationEngine(AiTextGenerator generator) {
        this.generator = generator;
    }

    @Override
    public TranslationEngine engine() {
        return TranslationEngine.SPRING_AI;
    }

    @Override
    public String complete(AiUsage usage, String prompt) {
        String raw = generator.generate(usage, prompt);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("翻译返回空响应");
        }
        return raw;
    }
}

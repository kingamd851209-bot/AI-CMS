package com.xinhongru.cms.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xinhongru.ai.generation.AiUsage;

import org.junit.jupiter.api.Test;

import java.util.List;

class TextTranslationEngineRegistryTest {
    private static TextTranslationEngine stub(TranslationEngine engine, String marker) {
        return new TextTranslationEngine() {
            @Override
            public TranslationEngine engine() {
                return engine;
            }

            @Override
            public String complete(AiUsage usage, String prompt) {
                return marker;
            }
        };
    }

    @Test
    void routesByNameIgnoringCase() {
        TextTranslationEngine openCode = stub(TranslationEngine.OPENCODE, "agent");
        TextTranslationEngine springAi = stub(TranslationEngine.SPRING_AI, "llm");
        TextTranslationEngineRegistry registry =
                new TextTranslationEngineRegistry(List.of(openCode, springAi));

        assertThrows(IllegalArgumentException.class, () -> registry.of("OPENCODE"));
        assertSame(springAi, registry.of("spring_ai"));
    }

    @Test
    void usesSpringForBlankAndRejectsUnknown() {
        TextTranslationEngine openCode = stub(TranslationEngine.OPENCODE, "agent");
        TextTranslationEngine springAi = stub(TranslationEngine.SPRING_AI, "llm");
        TextTranslationEngineRegistry registry =
                new TextTranslationEngineRegistry(List.of(openCode, springAi));

        assertSame(springAi, registry.of((String) null));
        assertSame(springAi, registry.of(""));
        assertThrows(IllegalArgumentException.class, () -> registry.of("nope"));
    }

    @Test
    void completesViaResolvedEngine() {
        TextTranslationEngineRegistry registry =
                new TextTranslationEngineRegistry(
                        List.of(stub(TranslationEngine.SPRING_AI, "llm")));
        assertEquals("llm", registry.of("SPRING_AI").complete(AiUsage.CONTENT_TRANSLATION, "x"));
    }
}

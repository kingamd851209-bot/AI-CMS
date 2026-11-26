package com.xinhongru.cms.i18n;

import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.entity.OneshotEntity;

import org.springframework.stereotype.Component;

import java.util.List;

/** LLM：经通用引擎（Spring AI）翻译栏目。 */
@Component
public class SpringAiMenuTranslationEngine implements MenuTranslationEngine {
    private final TextTranslationEngineRegistry engines;

    public SpringAiMenuTranslationEngine(TextTranslationEngineRegistry engines) {
        this.engines = engines;
    }

    @Override
    public TranslationEngine engine() {
        return TranslationEngine.SPRING_AI;
    }

    @Override
    public TranslateOutcome translate(List<OneshotEntity> menus, String targetLanguage) {
        String prompt = MenuTranslationPrompt.build(menus, targetLanguage);
        String raw =
                engines.of(TranslationEngine.SPRING_AI).complete(AiUsage.MENU_TRANSLATION, prompt);
        return new TranslateOutcome(MenuTranslationPrompt.parse(raw, menus), prompt, raw);
    }
}

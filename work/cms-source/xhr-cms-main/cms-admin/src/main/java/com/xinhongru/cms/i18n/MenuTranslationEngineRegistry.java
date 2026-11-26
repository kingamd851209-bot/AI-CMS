package com.xinhongru.cms.i18n;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按引擎路由到具体实现；新增引擎只需新增一个 {@link MenuTranslationEngine} Bean。 */
@Component
public class MenuTranslationEngineRegistry {
    private final Map<TranslationEngine, MenuTranslationEngine> engines =
            new EnumMap<>(TranslationEngine.class);

    public MenuTranslationEngineRegistry(List<MenuTranslationEngine> engineBeans) {
        for (MenuTranslationEngine engine : engineBeans) {
            engines.put(engine.engine(), engine);
        }
    }

    /** 取指定引擎的实现；名称为空时使用SPRING_AI，旧指定与未知名称明确拒绝。 */
    public MenuTranslationEngine of(String engineName) {
        TranslationEngine engine = TranslationEngine.parse(engineName);
        if (engine == null) {
            if (engineName != null && !engineName.isBlank()) {
                throw new IllegalArgumentException("翻译引擎无效，请选择SPRING_AI");
            }
            engine = TranslationEngine.DEFAULT;
        }
        if (engine == TranslationEngine.OPENCODE)
            throw new IllegalArgumentException("旧OPENCODE引擎已停用，请选择SPRING_AI");
        MenuTranslationEngine impl = engines.get(engine);
        if (impl == null) {
            throw new IllegalStateException("翻译引擎未注册: " + engine);
        }
        return impl;
    }
}

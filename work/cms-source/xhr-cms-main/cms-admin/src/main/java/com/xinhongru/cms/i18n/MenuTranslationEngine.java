package com.xinhongru.cms.i18n;

import com.xinhongru.entity.OneshotEntity;

import java.util.List;

/** 栏目翻译引擎：一种引擎一个实现，由 {@link MenuTranslationEngineRegistry} 按引擎路由。 */
public interface MenuTranslationEngine {
    TranslationEngine engine();

    TranslateOutcome translate(List<OneshotEntity> menus, String targetLanguage);
}

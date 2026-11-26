package com.xinhongru.cms.i18n;

import java.util.List;

/** 一次内容翻译的结果：字段译文、prompt 与 AI 原始响应。 */
public record ContentTranslateOutcome(
        List<ContentTranslationItem> items, String prompt, String rawResponse) {}

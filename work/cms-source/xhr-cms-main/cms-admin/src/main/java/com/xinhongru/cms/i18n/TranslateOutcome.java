package com.xinhongru.cms.i18n;

import java.util.Map;

/** 一次栏目翻译的结果：译文、prompt 与 AI 原始响应。 */
public record TranslateOutcome(
        Map<Integer, String> translations, String prompt, String rawResponse) {}

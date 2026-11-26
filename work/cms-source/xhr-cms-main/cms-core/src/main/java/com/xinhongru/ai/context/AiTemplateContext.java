package com.xinhongru.ai.context;

public record AiTemplateContext(
        Integer menuId,
        String menuName,
        Integer legacyFieldSettingType,
        String templatePath,
        String html,
        String existingFieldJson,
        boolean templateFound,
        String message) {}

package com.xinhongru.api.admin;

public record LanguageCreateRequest(
        String languageCode,
        String languageName,
        String nativeName,
        String locale,
        Integer sort,
        Boolean enabled,
        Boolean defaultLanguage) {}

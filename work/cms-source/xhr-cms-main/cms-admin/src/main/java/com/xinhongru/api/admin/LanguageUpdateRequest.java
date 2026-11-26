package com.xinhongru.api.admin;

public record LanguageUpdateRequest(
        String languageName, String nativeName, String locale, Integer sort, Boolean enabled) {}

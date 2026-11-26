package com.xinhongru.i18n.service;

import com.xinhongru.i18n.entity.LanguageEntity;

public interface LanguageService {
    LanguageEntity createLanguage(
            String languageCode, String languageName, boolean defaultLanguage);

    LanguageEntity createLanguage(
            String languageCode,
            String languageName,
            String nativeName,
            String locale,
            Integer sort,
            boolean enabled,
            boolean defaultLanguage);

    void updateLanguage(
            Long languageId,
            String languageName,
            String nativeName,
            String locale,
            Integer sort,
            Boolean enabled);

    void setDefaultLanguage(Long languageId);

    void enableLanguage(Long languageId);

    void disableLanguage(Long languageId);

    void deleteLanguage(Long languageId);
}

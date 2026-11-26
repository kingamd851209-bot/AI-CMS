package com.xinhongru.i18n.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xinhongru.i18n.entity.LanguageEntity;
import com.xinhongru.i18n.mapper.LanguageMapper;
import com.xinhongru.i18n.service.LanguageService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LanguageServiceImpl implements LanguageService {
    private final LanguageMapper languageMapper;

    public LanguageServiceImpl(LanguageMapper languageMapper) {
        this.languageMapper = languageMapper;
    }

    @Override
    public LanguageEntity createLanguage(
            String languageCode, String languageName, boolean defaultLanguage) {
        return createLanguage(
                languageCode, languageName, languageName, languageCode, 0, true, defaultLanguage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LanguageEntity createLanguage(
            String languageCode,
            String languageName,
            String nativeName,
            String locale,
            Integer sort,
            boolean enabled,
            boolean defaultLanguage) {
        String code = requireText(languageCode, "languageCode");
        String name = requireText(languageName, "languageName");
        LanguageEntity language =
                new LanguageEntity()
                        .setLanguageCode(code)
                        .setLanguageName(name)
                        .setNativeName(
                                nativeName == null || nativeName.isBlank()
                                        ? name
                                        : nativeName.trim())
                        .setLocale(locale == null || locale.isBlank() ? code : locale.trim())
                        .setIsDefault(defaultLanguage ? 1 : 0)
                        .setEnabled(defaultLanguage || enabled ? 1 : 0)
                        .setSort(sort == null ? 0 : sort)
                        .setCreateTime(LocalDateTime.now())
                        .setDeleted(0);
        if (defaultLanguage) {
            clearDefaultLanguage();
        }
        assertAffected(languageMapper.insert(language), "language create failed");
        return language;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultLanguage(Long languageId) {
        requireId(languageId);
        clearDefaultLanguage();
        LanguageEntity update =
                new LanguageEntity()
                        .setId(languageId)
                        .setIsDefault(1)
                        .setEnabled(1)
                        .setUpdateTime(LocalDateTime.now());
        assertAffected(languageMapper.updateById(update), "default language update failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableLanguage(Long languageId) {
        requireId(languageId);
        LanguageEntity current = languageMapper.selectById(languageId);
        if (current == null) {
            throw new IllegalStateException("language not found");
        }
        if (Integer.valueOf(1).equals(current.getIsDefault())) {
            throw new IllegalStateException("default language cannot be disabled");
        }
        LanguageEntity update =
                new LanguageEntity()
                        .setId(languageId)
                        .setEnabled(0)
                        .setUpdateTime(LocalDateTime.now());
        LambdaUpdateWrapper<LanguageEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LanguageEntity::getId, languageId)
                .eq(LanguageEntity::getDeleted, 0)
                .eq(LanguageEntity::getIsDefault, 0);
        assertAffected(languageMapper.update(update, wrapper), "language disable failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateLanguage(
            Long languageId,
            String languageName,
            String nativeName,
            String locale,
            Integer sort,
            Boolean enabled) {
        requireId(languageId);
        LanguageEntity current = languageMapper.selectById(languageId);
        if (current == null) {
            throw new IllegalStateException("language not found");
        }

        LanguageEntity update =
                new LanguageEntity()
                        .setId(languageId)
                        .setLanguageName(requireText(languageName, "languageName"))
                        .setUpdateTime(LocalDateTime.now());
        if (nativeName != null) {
            update.setNativeName(nativeName.trim());
        }
        if (locale != null) {
            update.setLocale(locale.trim());
        }
        if (sort != null) {
            update.setSort(sort);
        }
        if (enabled != null) {
            if (!enabled && Integer.valueOf(1).equals(current.getIsDefault())) {
                throw new IllegalStateException("default language cannot be disabled");
            }
            update.setEnabled(enabled ? 1 : 0);
        }
        assertAffected(languageMapper.updateById(update), "language update failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableLanguage(Long languageId) {
        requireId(languageId);
        LanguageEntity update =
                new LanguageEntity()
                        .setId(languageId)
                        .setEnabled(1)
                        .setUpdateTime(LocalDateTime.now());
        LambdaUpdateWrapper<LanguageEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LanguageEntity::getId, languageId).eq(LanguageEntity::getDeleted, 0);
        assertAffected(languageMapper.update(update, wrapper), "language enable failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLanguage(Long languageId) {
        requireId(languageId);
        LanguageEntity current = languageMapper.selectById(languageId);
        if (current == null) {
            throw new IllegalStateException("language not found");
        }
        if (Integer.valueOf(1).equals(current.getIsDefault())) {
            throw new IllegalStateException("default language cannot be deleted");
        }
        assertAffected(languageMapper.deleteById(languageId), "language delete failed");
    }

    private void clearDefaultLanguage() {
        LanguageEntity update =
                new LanguageEntity().setIsDefault(0).setUpdateTime(LocalDateTime.now());
        LambdaUpdateWrapper<LanguageEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LanguageEntity::getDeleted, 0);
        languageMapper.update(update, wrapper);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("languageId is required");
        }
    }

    private void assertAffected(int rows, String message) {
        if (rows <= 0) {
            throw new IllegalStateException(message);
        }
    }
}

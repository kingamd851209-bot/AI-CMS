package com.xinhongru.site.locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinhongru.i18n.entity.LanguageEntity;
import com.xinhongru.i18n.mapper.LanguageMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SiteLocaleService {
    private static final int MAX_FALLBACK_CHAIN_SIZE = 8;

    private final SiteLocaleMapper siteLocaleMapper;
    private final LanguageMapper languageMapper;

    public SiteLocaleService(SiteLocaleMapper siteLocaleMapper, LanguageMapper languageMapper) {
        this.siteLocaleMapper = siteLocaleMapper;
        this.languageMapper = languageMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<LocaleView> configure(Long siteId, ConfigureCommand command) {
        requireSiteId(siteId);
        List<SiteLocaleEntity> candidates = toCandidates(siteId, command);
        validate(candidates);
        Map<Long, SiteLocaleEntity> existingByLanguage =
                siteLocaleMapper.selectBySiteIdIncludingDeleted(siteId).stream()
                        .filter(item -> item.getLanguageId() != null)
                        .collect(
                                Collectors.toMap(
                                        SiteLocaleEntity::getLanguageId,
                                        item -> item,
                                        (left, right) -> left));
        Set<Long> retainedLanguageIds = new HashSet<>();
        candidates.forEach(
                locale -> {
                    retainedLanguageIds.add(locale.getLanguageId());
                    SiteLocaleEntity existing = existingByLanguage.get(locale.getLanguageId());
                    if (existing == null) {
                        if (siteLocaleMapper.insert(locale) != 1) {
                            throw new IllegalStateException("保存站点语言失败");
                        }
                        return;
                    }
                    locale.setId(existing.getId()).setCreateTime(existing.getCreateTime());
                    if (siteLocaleMapper.restoreAndUpdate(locale) != 1) {
                        throw new IllegalStateException("保存站点语言失败");
                    }
                });
        existingByLanguage.entrySet().stream()
                .filter(entry -> !retainedLanguageIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(item -> !Integer.valueOf(1).equals(item.getDeleted()))
                .forEach(
                        item -> {
                            if (siteLocaleMapper.deleteById(item.getId()) != 1) {
                                throw new IllegalStateException("移除站点语言失败");
                            }
                        });
        return toViews(candidates);
    }

    @Transactional(rollbackFor = Exception.class)
    public void initializeDefaultLocale(Long siteId, String languageCode) {
        String code = requireText(languageCode, "默认语言代码");
        LanguageEntity language =
                languageMapper.selectOne(
                        new LambdaQueryWrapper<LanguageEntity>()
                                .eq(LanguageEntity::getLanguageCode, code)
                                .eq(LanguageEntity::getDeleted, 0)
                                .last("LIMIT 1"));
        if (language == null || language.getId() == null) {
            throw new IllegalArgumentException("默认语言不存在");
        }
        configure(
                siteId,
                new ConfigureCommand(
                        List.of(
                                new LocaleCommand(
                                        language.getId(), true, true, false, "", null, 0))));
    }

    public SiteLocaleEntity requireEnabled(Long siteId, Long languageId) {
        requireSiteId(siteId);
        requireLanguageId(languageId);
        SiteLocaleEntity locale =
                siteLocaleMapper.selectOne(
                        new LambdaQueryWrapper<SiteLocaleEntity>()
                                .eq(SiteLocaleEntity::getSiteId, siteId)
                                .eq(SiteLocaleEntity::getLanguageId, languageId)
                                .eq(SiteLocaleEntity::getEnabled, 1)
                                .eq(SiteLocaleEntity::getDeleted, 0)
                                .last("LIMIT 1"));
        if (locale == null) {
            throw new IllegalArgumentException("该语言未在当前站点启用");
        }
        return locale;
    }

    public ResolvedLocale resolveByPath(Long siteId, String requestPath) {
        List<SiteLocaleEntity> locales = enabledLocales(siteId);
        String path = requestPath == null ? "" : requestPath.trim();
        String matchedPrefix =
                locales.stream()
                        .map(SiteLocaleEntity::getPathPrefix)
                        .filter(prefix -> prefix != null && !prefix.isBlank())
                        .filter(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))
                        .max(Comparator.comparingInt(String::length))
                        .orElse(null);
        SiteLocaleEntity locale =
                matchedPrefix == null
                        ? locales.stream()
                                .filter(item -> Integer.valueOf(1).equals(item.getIsDefault()))
                                .findFirst()
                                .orElse(null)
                        : locales.stream()
                                .filter(item -> matchedPrefix.equals(item.getPathPrefix()))
                                .findFirst()
                                .orElse(null);
        if (locale == null) {
            throw new IllegalStateException("当前站点没有可用默认语言");
        }
        return new ResolvedLocale(
                locale.getLanguageId(), locale.getPathPrefix(), matchedPrefix != null);
    }

    public List<SiteLocaleEntity> resolveFallbackChain(Long siteId, Long languageId) {
        SiteLocaleEntity current = requireEnabled(siteId, languageId);
        Map<Long, SiteLocaleEntity> byLanguage =
                enabledLocales(siteId).stream()
                        .collect(Collectors.toMap(SiteLocaleEntity::getLanguageId, item -> item));
        List<SiteLocaleEntity> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        while (current != null) {
            if (!visited.add(current.getLanguageId())) {
                throw new IllegalStateException("站点语言 fallback 存在循环");
            }
            chain.add(current);
            if (chain.size() > MAX_FALLBACK_CHAIN_SIZE) {
                throw new IllegalStateException("站点语言 fallback 超过最大深度");
            }
            Long fallbackLanguageId = current.getFallbackLanguageId();
            current = fallbackLanguageId == null ? null : byLanguage.get(fallbackLanguageId);
            if (fallbackLanguageId != null && current == null) {
                throw new IllegalStateException("fallback 语言未在当前站点启用");
            }
        }
        return chain;
    }

    public List<LocaleView> list(Long siteId) {
        requireSiteId(siteId);
        return toViews(allLocales(siteId));
    }

    public List<LanguageOption> languageOptions() {
        return languageMapper
                .selectList(
                        new LambdaQueryWrapper<LanguageEntity>()
                                .eq(LanguageEntity::getDeleted, 0)
                                .orderByAsc(LanguageEntity::getSort, LanguageEntity::getId))
                .stream()
                .map(
                        language ->
                                new LanguageOption(
                                        language.getId(),
                                        language.getLanguageCode(),
                                        language.getLanguageName()))
                .toList();
    }

    private List<SiteLocaleEntity> toCandidates(Long siteId, ConfigureCommand command) {
        if (command == null || command.locales() == null || command.locales().isEmpty()) {
            throw new IllegalArgumentException("至少启用一种站点语言");
        }
        LocalDateTime now = LocalDateTime.now();
        List<SiteLocaleEntity> candidates = new ArrayList<>();
        Set<Long> languageIds = new HashSet<>();
        for (LocaleCommand item : command.locales()) {
            if (item == null || item.languageId() == null || !languageIds.add(item.languageId())) {
                throw new IllegalArgumentException("站点语言不能重复");
            }
            LanguageEntity language = languageMapper.selectById(item.languageId());
            if (language == null || Integer.valueOf(1).equals(language.getDeleted())) {
                throw new IllegalArgumentException("语言字典不存在");
            }
            candidates.add(
                    new SiteLocaleEntity()
                            .setSiteId(siteId)
                            .setLanguageId(item.languageId())
                            .setIsDefault(item.defaultLanguage() ? 1 : 0)
                            .setEnabled(item.enabled() ? 1 : 0)
                            .setRequiredForPublish(item.requiredForPublish() ? 1 : 0)
                            .setPathPrefix(normalizePrefix(item.pathPrefix()))
                            .setFallbackLanguageId(item.fallbackLanguageId())
                            .setSort(item.sort())
                            .setCreateTime(now)
                            .setUpdateTime(now)
                            .setDeleted(0));
        }
        return candidates;
    }

    private void validate(List<SiteLocaleEntity> candidates) {
        long defaults =
                candidates.stream()
                        .filter(item -> Integer.valueOf(1).equals(item.getIsDefault()))
                        .count();
        if (defaults != 1) {
            throw new IllegalArgumentException("每个站点必须且只能有一个默认语言");
        }
        Set<String> prefixes = new HashSet<>();
        Map<Long, SiteLocaleEntity> byLanguage = new HashMap<>();
        for (SiteLocaleEntity locale : candidates) {
            byLanguage.put(locale.getLanguageId(), locale);
        }
        for (SiteLocaleEntity locale : candidates) {
            boolean defaultLanguage = Integer.valueOf(1).equals(locale.getIsDefault());
            boolean enabled = Integer.valueOf(1).equals(locale.getEnabled());
            if (defaultLanguage && !enabled) {
                throw new IllegalArgumentException("默认语言必须启用");
            }
            if (defaultLanguage && !locale.getPathPrefix().isEmpty()) {
                throw new IllegalArgumentException("默认语言不能配置 URL 前缀");
            }
            if (!defaultLanguage && locale.getPathPrefix().isEmpty()) {
                throw new IllegalArgumentException("非默认语言必须配置 URL 前缀");
            }
            if (!prefixes.add(locale.getPathPrefix())) {
                throw new IllegalArgumentException("同一站点的 URL 前缀不能重复");
            }
            if (locale.getFallbackLanguageId() != null) {
                SiteLocaleEntity fallback = byLanguage.get(locale.getFallbackLanguageId());
                if (fallback == null) {
                    throw new IllegalArgumentException("fallback 语言必须属于当前站点");
                }
                if (!Integer.valueOf(1).equals(fallback.getEnabled())) {
                    throw new IllegalArgumentException("fallback 语言必须在当前站点启用");
                }
            }
        }
        for (SiteLocaleEntity locale : candidates) {
            assertFallbackChain(locale, byLanguage);
        }
    }

    private void assertFallbackChain(
            SiteLocaleEntity start, Map<Long, SiteLocaleEntity> byLanguage) {
        Set<Long> visited = new HashSet<>();
        SiteLocaleEntity current = start;
        while (current != null) {
            if (!visited.add(current.getLanguageId())) {
                throw new IllegalArgumentException("站点语言 fallback 不能形成循环");
            }
            if (visited.size() > MAX_FALLBACK_CHAIN_SIZE) {
                throw new IllegalArgumentException("站点语言 fallback 超过最大深度");
            }
            current =
                    current.getFallbackLanguageId() == null
                            ? null
                            : byLanguage.get(current.getFallbackLanguageId());
        }
    }

    private List<SiteLocaleEntity> allLocales(Long siteId) {
        return siteLocaleMapper.selectList(
                new LambdaQueryWrapper<SiteLocaleEntity>()
                        .eq(SiteLocaleEntity::getSiteId, siteId)
                        .eq(SiteLocaleEntity::getDeleted, 0)
                        .orderByAsc(SiteLocaleEntity::getSort, SiteLocaleEntity::getId));
    }

    private List<SiteLocaleEntity> enabledLocales(Long siteId) {
        return allLocales(siteId).stream()
                .filter(item -> Integer.valueOf(1).equals(item.getEnabled()))
                .toList();
    }

    private List<LocaleView> toViews(List<SiteLocaleEntity> locales) {
        Map<Long, LanguageEntity> languages = new HashMap<>();
        for (SiteLocaleEntity locale : locales) {
            languages.put(
                    locale.getLanguageId(), languageMapper.selectById(locale.getLanguageId()));
        }
        return locales.stream()
                .map(
                        locale -> {
                            LanguageEntity language = languages.get(locale.getLanguageId());
                            LanguageEntity fallback =
                                    locale.getFallbackLanguageId() == null
                                            ? null
                                            : languages.get(locale.getFallbackLanguageId());
                            return new LocaleView(
                                    locale.getLanguageId(),
                                    language == null ? null : language.getLanguageCode(),
                                    language == null ? "未知语言" : language.getLanguageName(),
                                    Integer.valueOf(1).equals(locale.getIsDefault()),
                                    Integer.valueOf(1).equals(locale.getEnabled()),
                                    Integer.valueOf(1).equals(locale.getRequiredForPublish()),
                                    locale.getPathPrefix(),
                                    locale.getFallbackLanguageId(),
                                    fallback == null ? null : fallback.getLanguageName(),
                                    locale.getSort());
                        })
                .toList();
    }

    private String normalizePrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (prefix.isEmpty()) {
            return "";
        }
        if (!prefix.startsWith("/")
                || "/".equals(prefix)
                || prefix.endsWith("/")
                || prefix.contains("//")) {
            throw new IllegalArgumentException("URL 前缀格式不合法");
        }
        return prefix.toLowerCase(Locale.ROOT);
    }

    private void requireSiteId(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new IllegalArgumentException("站点 ID 无效");
        }
    }

    private void requireLanguageId(Long languageId) {
        if (languageId == null || languageId <= 0) {
            throw new IllegalArgumentException("语言 ID 无效");
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    public record ConfigureCommand(List<LocaleCommand> locales) {}

    public record LocaleCommand(
            Long languageId,
            boolean defaultLanguage,
            boolean enabled,
            boolean requiredForPublish,
            String pathPrefix,
            Long fallbackLanguageId,
            int sort) {}

    public record LocaleView(
            Long languageId,
            String languageCode,
            String languageName,
            boolean defaultLanguage,
            boolean enabled,
            boolean requiredForPublish,
            String pathPrefix,
            Long fallbackLanguageId,
            String fallbackLanguageName,
            int sort) {}

    public record ResolvedLocale(Long languageId, String pathPrefix, boolean pathPrefixMatched) {}

    public record LanguageOption(Long languageId, String languageCode, String languageName) {}
}

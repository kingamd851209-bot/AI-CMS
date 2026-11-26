package com.xinhongru.cms.i18n;

import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.cms.content.record.NativeContentAdminQueryService;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内容字段翻译：取源值 → 构造 prompt → 按引擎补全 → 解析为逐字段结果。 */
@Service
public class ContentTranslationService {
    private final NativeContentAdminQueryService contentQueries;
    private final TextTranslationEngineRegistry engines;

    public ContentTranslationService(
            NativeContentAdminQueryService contentQueries, TextTranslationEngineRegistry engines) {
        this.contentQueries = contentQueries;
        this.engines = engines;
    }

    /** 只构造 prompt，不调用 AI（供 agent 走浏览器代理）。 */
    public String prompt(
            long siteId,
            long menuId,
            long languageId,
            long recordId,
            List<String> fieldKeys,
            String targetLanguage) {
        return ContentTranslationPrompt.build(
                sourceValues(siteId, menuId, languageId, recordId, fieldKeys), targetLanguage);
    }

    /** 按引擎同步翻译。 */
    public ContentTranslateOutcome translate(
            long siteId,
            long menuId,
            long languageId,
            long recordId,
            List<String> fieldKeys,
            String targetLanguage,
            String engineName) {
        Map<String, String> source = sourceValues(siteId, menuId, languageId, recordId, fieldKeys);
        String prompt = ContentTranslationPrompt.build(source, targetLanguage);
        String raw = engines.of(engineName).complete(AiUsage.CONTENT_TRANSLATION, prompt);
        Map<String, String> translated = ContentTranslationPrompt.parse(raw);
        List<ContentTranslationItem> items = toItems(source, translated);
        return new ContentTranslateOutcome(items, prompt, raw);
    }

    static List<ContentTranslationItem> toItems(
            Map<String, String> source, Map<String, String> translated) {
        return source.entrySet().stream()
                .map(
                        entry ->
                                new ContentTranslationItem(
                                        entry.getKey(),
                                        entry.getValue(),
                                        translated.getOrDefault(entry.getKey(), "")))
                .toList();
    }

    private Map<String, String> sourceValues(
            long siteId, long menuId, long languageId, long recordId, List<String> fieldKeys) {
        var detail = contentQueries.editor(siteId, menuId, languageId, recordId, null, null);
        Map<String, String> values = new LinkedHashMap<>();
        if (fieldKeys == null) {
            return values;
        }
        for (String key : fieldKeys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Object value = detail.payload().get(trimmed);
            values.put(trimmed, value == null ? "" : String.valueOf(value));
        }
        return values;
    }
}

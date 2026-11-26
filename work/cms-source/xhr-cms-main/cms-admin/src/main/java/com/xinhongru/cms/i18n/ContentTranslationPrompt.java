package com.xinhongru.cms.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/** 内容字段翻译的 prompt 构造与响应解析。行协议：{@code fieldKey|译文}；多行用 {{BR}} 占位。 */
public final class ContentTranslationPrompt {
    private static final String LINE_BREAK_TOKEN = "{{BR}}";

    private ContentTranslationPrompt() {}

    /** 构造 prompt。{@code values} 的迭代顺序即为输出行顺序（调用方用 LinkedHashMap 保证稳定）。 */
    public static String build(Map<String, String> values, String targetLanguage) {
        StringBuilder builder = new StringBuilder();
        builder.append("Translate the following content fields into ")
                .append(languageName(targetLanguage))
                .append(".\n");
        builder.append(
                "Rules: keep numbers, URLs, file paths, HTML tags/attributes and template"
                        + " variables like {$1_title_id$} unchanged; keep the placeholder "
                        + LINE_BREAK_TOKEN
                        + " unchanged; if a value needs no translation, output it as is.\n");
        builder.append(
                "Output one line per input, in the same order, exactly as: fieldKey|translated"
                        + " value\n");
        builder.append("Do not output explanations, code fences or extra lines.\n\n");
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                builder.append(entry.getKey())
                        .append('|')
                        .append(value.replace("\r\n", "\n").replace("\n", LINE_BREAK_TOKEN))
                        .append('\n');
            }
        }
        return builder.toString();
    }

    /** 解析 {@code fieldKey|译文} 行；无法识别的行跳过，缺失字段不回填。 */
    public static Map<String, String> parse(String content) {
        Map<String, String> translations = new LinkedHashMap<>();
        if (content == null) {
            return translations;
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int index = line.indexOf('|');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            translations.put(key, value.replace(LINE_BREAK_TOKEN, "\n"));
        }
        return translations;
    }

    private static String languageName(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return "";
        }
        if (targetLanguage.startsWith("en")) {
            return "English";
        }
        if (targetLanguage.startsWith("zh")) {
            if (targetLanguage.toLowerCase().contains("tw") || targetLanguage.contains("Hant")) {
                return "Traditional Chinese";
            }
            return "Simplified Chinese";
        }
        return targetLanguage;
    }
}

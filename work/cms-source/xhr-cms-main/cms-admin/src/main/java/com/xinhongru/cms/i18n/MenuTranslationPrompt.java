package com.xinhongru.cms.i18n;

import com.xinhongru.entity.OneshotEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 栏目翻译的 prompt 构造与响应解析，各引擎共用，避免文案与解析规则漂移。 */
public final class MenuTranslationPrompt {
    private MenuTranslationPrompt() {}

    public static String build(List<OneshotEntity> menus, String targetLanguage) {
        StringBuilder builder = new StringBuilder();
        builder.append("Translate the following menu names to ")
                .append(languageName(targetLanguage))
                .append(". ");
        builder.append(
                "Return each translation on a new line in the format: id|translated_name\n\n");
        for (OneshotEntity menu : menus) {
            builder.append(menu.getId()).append("|").append(menu.getClassName()).append("\n");
        }
        return builder.toString();
    }

    /** 解析 “id|译文” 行；任一栏目缺译时抛错，保证不会应用不完整译文。 */
    public static Map<Integer, String> parse(String content, List<OneshotEntity> menus) {
        Map<Integer, String> translations = new HashMap<>();
        if (content != null) {
            for (String raw : content.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", 2);
                if (parts.length < 2) {
                    continue;
                }
                try {
                    Integer id = Integer.parseInt(parts[0].trim());
                    String translated =
                            parts[1].trim()
                                    .replaceAll("\\s*-\\s*already\\s+\\w+\\s*$", "")
                                    .replaceAll("\\s*\\(already\\s+\\w+\\)\\s*$", "");
                    translations.put(id, translated.trim());
                } catch (NumberFormatException ignored) {
                    // 非 “id|译文” 行，跳过
                }
            }
        }
        for (OneshotEntity menu : menus) {
            String value = translations.get(menu.getId());
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("翻译结果不完整，请重试，未应用任何翻译");
            }
        }
        translations.keySet().retainAll(menus.stream().map(OneshotEntity::getId).toList());
        return translations;
    }

    private static String languageName(String targetLanguage) {
        if (targetLanguage == null) {
            return "";
        }
        if (targetLanguage.startsWith("en")) {
            return "English";
        }
        if (targetLanguage.startsWith("zh")) {
            return "Chinese";
        }
        return targetLanguage;
    }
}

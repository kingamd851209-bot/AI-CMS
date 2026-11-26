package com.xinhongru.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** t_contents 列名白名单：将字段名（驼峰或下划线形式）解析为合法列名，供动态查询拼 SQL 时做列名校验，防止注入。 */
public final class ContentColumnWhitelist {
    private ContentColumnWhitelist() {}

    private static final Map<String, String> COLUMNS = build();

    /** 解析字段名为 t_contents 合法列名；无法解析（含 null/空/未知字段）返回 null。 */
    public static String resolve(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String trimmed = fieldName.trim();
        String column = COLUMNS.get(trimmed);
        if (column != null) {
            return column;
        }
        return COLUMNS.get(StringDeal.convertToUnderscore(trimmed));
    }

    private static Map<String, String> build() {
        Map<String, String> columns = new LinkedHashMap<>();
        add(columns, "title", "title");
        add(columns, "createTime", "create_time");
        add(columns, "beginTime", "begin_time");
        add(columns, "endTime", "end_time");
        add(columns, "entryIntoForceTime", "entry_into_force_time");
        add(columns, "expirationTime", "expiration_time");
        add(columns, "tags", "tags");
        numbered(columns, "content", "content", 6);
        numbered(columns, "singleLine", "single_line", 20);
        numbered(columns, "note", "note", 15);
        numbered(columns, "photo", "photo", 15);
        add(columns, "seoTitle", "seo_title");
        add(columns, "seoKeywords", "seo_keywords");
        add(columns, "seoDescription", "seo_description");
        numbered(columns, "media", "media", 4);
        numbered(columns, "file", "file", 4);
        return Collections.unmodifiableMap(columns);
    }

    private static void numbered(
            Map<String, String> columns, String propertyPrefix, String columnPrefix, int count) {
        for (int index = 1; index <= count; index++) {
            add(columns, propertyPrefix + index, columnPrefix + index);
        }
    }

    private static void add(Map<String, String> columns, String propertyName, String columnName) {
        columns.put(propertyName, columnName);
        columns.put(columnName, columnName);
    }
}

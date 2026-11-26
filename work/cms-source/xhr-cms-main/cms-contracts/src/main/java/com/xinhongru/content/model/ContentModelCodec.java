package com.xinhongru.content.model;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * One codec for persisted model/menu/list/search field definitions. Existing JSON arrays remain
 * readable.
 */
public final class ContentModelCodec {
    private ContentModelCodec() {}

    public static List<ContentFieldDefinition> read(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<ContentFieldDefinition> fields =
                    JSON.parseArray(json, ContentFieldDefinition.class);
            return fields == null ? new ArrayList<>() : fields;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("内容模型字段配置必须是有效的 JSON 数组", e);
        }
    }

    public static String write(List<ContentFieldDefinition> fields) {
        if (fields == null) return "[]";
        var names = new HashSet<String>();
        for (ContentFieldDefinition field : fields) {
            if (field == null
                    || field.getFieldName() == null
                    || (!field.getFieldName().matches("[A-Za-z][A-Za-z0-9]*")
                            && !ContentExtensionFields.isName(field.getFieldName()))
                    || !names.add(field.getFieldName())) {
                throw new IllegalArgumentException("内容模型字段名无效或重复");
            }
        }
        for (ContentFieldDefinition field : fields) {
            ContentDateTimes.validateDefinition(field);
            CounterNumbers.validateDefinition(field);
            if (ContentExtensionFields.isName(field.getFieldName()))
                ContentExtensionFields.validateDefinition(field);
        }
        return JSON.toJSONString(fields);
    }

    /** 原生模型增补。已有 JSON 项保留原属性与顺序。 */
    public static String append(String raw, List<ContentFieldDefinition> additions) {
        var current = read(raw);
        String added = write(additions);
        if (additions == null || additions.isEmpty()) return raw;
        var names = new HashSet<String>();
        current.forEach(field -> names.add(field.getFieldName()));
        if (additions.stream().anyMatch(field -> !names.add(field.getFieldName())))
            throw new IllegalArgumentException("增补字段名已存在");
        String existing = raw == null || raw.isBlank() ? "[]" : raw;
        int end = existing.lastIndexOf(']');
        if (end < 0) throw new IllegalArgumentException("原字段配置不是可增补的 JSON 数组");
        if (existing.substring(0, end).stripTrailing().endsWith(","))
            throw new IllegalArgumentException("原字段配置含尾逗号，请先核对原生字段配置");
        return existing.substring(0, end)
                + (current.isEmpty() ? "" : ",")
                + added.substring(1, added.length() - 1)
                + existing.substring(end);
    }
}

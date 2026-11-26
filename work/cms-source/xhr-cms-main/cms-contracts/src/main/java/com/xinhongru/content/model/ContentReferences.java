package com.xinhongru.content.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 新增关联字段沿用content输入与逗号ID存储；不重新解释旧槽位的标题值。 */
public final class ContentReferences {
    private ContentReferences() {}

    public static boolean applies(ContentFieldDefinition field) {
        return field != null
                && ContentExtensionFields.isName(field.getFieldName())
                && "content".equals(field.getInputType());
    }

    public static List<Integer> menuIds(ContentFieldDefinition field) {
        if (!applies(field) || !"SingleLine".equals(field.getFieldType()))
            throw new IllegalArgumentException("关联内容仅支持新增的SingleLine字段");
        String query = field.getDefaultValue();
        if (query == null || query.length() > 1200 || !query.matches("\\{\\$[0-9,]+_title_id\\$}"))
            throw new IllegalArgumentException("关联来源必须明确配置当前站点栏目，使用标题显示和内容ID存储");
        if (ContentFieldDefinition.enabled(field.getIsNumber())
                || (field.getCascadeParentField() != null
                        && !field.getCascadeParentField().isBlank()))
            throw new IllegalArgumentException("新增关联字段不使用数值校验或旧级联条件");
        return parse(query.substring(2, query.length() - "_title_id$}".length()));
    }

    /** 输入可为选项数组或既有逗号文本，输出保持原CMS存储约定和人工顺序。 */
    public static String normalize(ContentFieldDefinition field, Object value) {
        menuIds(field);
        List<Integer> ids;
        if (value == null) ids = List.of();
        else if (value instanceof List<?> list) {
            if (list.size() > 100) throw new IllegalArgumentException("每个关联字段最多100条内容");
            var tokens = new ArrayList<String>();
            for (Object item : list) {
                if (!(item instanceof String || item instanceof Integer || item instanceof Long))
                    throw new IllegalArgumentException("关联内容须为内容ID");
                tokens.add(String.valueOf(item));
            }
            ids = tokens.isEmpty() ? List.of() : parse(String.join(",", tokens));
        } else if (value instanceof String text) {
            if (text.isBlank()) ids = List.of();
            else {
                if (text.length() > 1200) throw new IllegalArgumentException("关联内容值超长");
                if (Boolean.TRUE.equals(field.getInputMultiple())) {
                    if (!text.startsWith(",") || !text.endsWith(",") || text.length() < 3)
                        throw new IllegalArgumentException("多条关联须使用逗号ID格式");
                    text = text.substring(1, text.length() - 1);
                }
                ids = parse(text);
            }
        } else throw new IllegalArgumentException("关联内容须为选项数组或ID文本");
        if (!Boolean.TRUE.equals(field.getInputMultiple()) && ids.size() > 1)
            throw new IllegalArgumentException("当前关联字段只允许一条内容");
        if (ids.isEmpty()) return "";
        String csv = String.join(",", ids.stream().map(String::valueOf).toList());
        return Boolean.TRUE.equals(field.getInputMultiple()) ? "," + csv + "," : csv;
    }

    public static List<Integer> ids(ContentFieldDefinition field, Object value) {
        String normalized = normalize(field, value);
        if (normalized.isEmpty()) return List.of();
        return parse(
                Boolean.TRUE.equals(field.getInputMultiple())
                        ? normalized.substring(1, normalized.length() - 1)
                        : normalized);
    }

    private static List<Integer> parse(String csv) {
        String[] tokens = csv.split(",", -1);
        if (tokens.length > 100) throw new IllegalArgumentException("关联来源或内容最多100项");
        var ids = new LinkedHashSet<Integer>();
        for (String token : tokens) {
            if (!token.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("关联ID无效");
            int id;
            try {
                id = Integer.parseInt(token);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("关联ID超出范围", error);
            }
            if (!ids.add(id)) throw new IllegalArgumentException("关联ID不能重复");
        }
        return List.copyOf(ids);
    }
}

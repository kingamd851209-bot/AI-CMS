package com.xinhongru.dto.cond;

import com.xinhongru.content.model.ContentExtensionFields;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/** 列来自现有白名单；扩展key来自当前模型，路径和值只作绑定参数。 */
public record ContentSearchCriterion(
        String column,
        String op,
        String value,
        List<String> values,
        String extensionKey,
        boolean numeric)
        implements Serializable {
    public ContentSearchCriterion {
        if (numeric
                && (extensionKey == null
                        || op == null
                        || !Set.of("eq", "ge", "le", "gt", "lt", "empty", "notEmpty").contains(op)))
            throw new IllegalArgumentException("数值筛选字段或操作无效");
        if (!numeric && Set.of("gt", "lt").contains(op == null ? "" : op))
            throw new IllegalArgumentException("大小比较仅用于已授权数值字段");
        if (extensionKey != null
                && (!ContentExtensionFields.isName(extensionKey)
                        || op == null
                        || !Set.of(
                                        "contains",
                                        "groupExact",
                                        "eq",
                                        "ge",
                                        "le",
                                        "gt",
                                        "lt",
                                        "any",
                                        "all",
                                        "empty",
                                        "notEmpty")
                                .contains(op))) throw new IllegalArgumentException("扩展筛选字段或操作无效");
        if ("groupExact".equals(op) && extensionKey == null
                && (column == null || !column.matches("title|single_line(?:[1-9]|1[0-9]|20)|note(?:[1-9]|1[0-5])")))
            throw new IllegalArgumentException("分组筛选字段无效");
    }

    public ContentSearchCriterion(
            String column, String op, String value, List<String> values, String extensionKey) {
        this(column, op, value, values, extensionKey, false);
    }

    public ContentSearchCriterion(String column, String op, String value, List<String> values) {
        this(column, op, value, values, null);
    }

    public ContentSearchCriterion(String column, String op, String value) {
        this(column, op, value, null, null);
    }
}

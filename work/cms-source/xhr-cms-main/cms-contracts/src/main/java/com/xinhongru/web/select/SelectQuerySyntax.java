package com.xinhongru.web.select;

import java.util.ArrayList;
import java.util.List;

/** 解析字段 defaultValue 中的 {$...$} 动态下拉查询语法。 */
public final class SelectQuerySyntax {
    private SelectQuerySyntax() {}

    /** 是否为动态下拉查询：以 {$ 开头且以 $} 结尾。 */
    public static boolean isDynamic(String defaultValue) {
        return defaultValue != null && defaultValue.startsWith("{$") && defaultValue.endsWith("$}");
    }

    /**
     * 解析 {$<父栏目id,父栏目id,...>_children$}（父栏目的全部直接子栏目并集作为选项），返回父栏目 id 列表； 非该形式、空内容或含非法 id 片段时返回空列表（单个
     * id 的 {$109_children$} 亦被解析为 [109]）。
     */
    public static List<Integer> parseChildrenParentIds(String defaultValue) {
        if (!isDynamic(defaultValue) || defaultValue.length() < 4) {
            return List.of();
        }
        String inner = defaultValue.substring(2, defaultValue.length() - 2);
        String[] parts = inner.split("_", -1);
        if (parts.length != 2 || !"children".equals(parts[1])) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String segment : parts[0].split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Integer id = Integer.valueOf(trimmed);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return ids;
    }
}

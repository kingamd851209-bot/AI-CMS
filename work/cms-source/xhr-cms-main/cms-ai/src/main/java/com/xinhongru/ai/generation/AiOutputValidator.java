package com.xinhongru.ai.generation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validate business output before a task can be marked successful. */
public final class AiOutputValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiOutputValidator() {}

    public static String validate(AiUsage usage, String output) {
        String text = stripFence(output);
        if (text.isBlank()) throw new IllegalArgumentException("AI 返回空响应");
        if (usage == AiUsage.COLUMN_RECOGNITION) {
            try {
                JsonNode root = JSON.readTree(text);
                JsonNode columns = root.has("columns") ? root.get("columns") : root.get("menus");
                validateColumns(columns, 0, new int[] {0});
            } catch (Exception error) {
                throw new IllegalArgumentException("AI 栏目结果不符合栏目数据结构", error);
            }
        } else if (usage == AiUsage.TEMPLATE_GENERATION
                || usage == AiUsage.TEMPLATE_REPAIR
                || usage == AiUsage.AI_PAGE_GENERATION) {
            if (text.startsWith("{") || !Pattern.compile("<[a-zA-Z][^>]*>").matcher(text).find()) {
                throw new IllegalArgumentException("AI 未返回有效 HTML");
            }
        }
        return text;
    }

    private static void validateColumns(JsonNode columns, int depth, int[] count) {
        if (columns == null || !columns.isArray() || depth > 20)
            throw new IllegalArgumentException();
        for (JsonNode column : columns) {
            if (++count[0] > 1000
                    || !column.isObject()
                    || !column.path("name").isTextual()
                    || column.path("name").asText().isBlank()) throw new IllegalArgumentException();
            JsonNode type = column.get("pageType");
            if (type != null
                    && (!type.isIntegralNumber() || type.asInt() < 0 || type.asInt() > 1)) {
                throw new IllegalArgumentException();
            }
            for (String field : new String[] {"path", "enName", "parentName"}) {
                if (column.hasNonNull(field) && !column.get(field).isTextual())
                    throw new IllegalArgumentException();
            }
            String path = column.path("path").asText("").toLowerCase(Locale.ROOT);
            if (path.startsWith("javascript:")
                    || path.startsWith("data:")
                    || path.contains("../")) {
                throw new IllegalArgumentException();
            }
            if (column.has("children")) validateColumns(column.get("children"), depth + 1, count);
        }
    }

    public static String stripFence(String output) {
        String text = output == null ? "" : output.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0 || !text.endsWith("```"))
                throw new IllegalArgumentException("AI 代码块不完整");
            text = text.substring(newline + 1, text.length() - 3).trim();
        }
        return text;
    }
}

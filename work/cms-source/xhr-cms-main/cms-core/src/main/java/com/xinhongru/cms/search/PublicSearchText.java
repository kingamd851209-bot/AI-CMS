package com.xinhongru.cms.search;

import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;

import org.jsoup.Jsoup;

import java.util.*;
import java.util.regex.Pattern;

/** 同表投影与只含文本的高亮片段；调用者必须通过文本节点呈现，不能当HTML片段执行。 */
public final class PublicSearchText {
    private static final Set<String> TEXT_TYPES =
            Set.of("SingleLine", "MultiLine", "Editor", "Text", "Textarea", "DateTime");

    private PublicSearchText() {}

    public record Part(String text, boolean hit) {}

    public static String project(List<ContentFieldDefinition> fields, Map<String, ?> values) {
        Objects.requireNonNull(fields, "搜索字段配置");
        Objects.requireNonNull(values, "搜索值");
        var parts = new ArrayList<String>();
        add(parts, values.get("title"), false);
        for (var field : fields) {
            if (field == null
                    || !ContentFieldDefinition.enabled(field.getIsOpen())
                    || field.getFieldType() == null
                    || !TEXT_TYPES.contains(field.getFieldType())) continue;
            String name = field.getFieldName();
            if (name == null || name.equals("title")) continue;
            boolean extension = ContentExtensionFields.isName(name);
            if (!extension && !name.matches("(singleLine|note|content)[1-9][0-9]*")) continue;
            if (!field.isPublicSearch()) continue;
            add(parts, values.get(name), "Editor".equals(field.getFieldType()));
        }
        return String.join("\n", parts);
    }

    public static String forContent(
            List<ContentFieldDefinition> fields,
            com.xinhongru.entity.ContentEntity current,
            com.xinhongru.entity.ContentEntity before) {
        return project(fields, actualValues(current, before));
    }

    public static String forSinglePage(
            List<ContentFieldDefinition> fields,
            com.xinhongru.entity.OneshotEntity current,
            com.xinhongru.entity.OneshotEntity before) {
        var values = actualValues(current, before);
        values.put(
                "title",
                current.getClassName() != null
                        ? current.getClassName()
                        : before == null ? null : before.getClassName());
        return project(fields, values);
    }

    private static Map<String, Object> actualValues(Object current, Object before) {
        var values = new LinkedHashMap<String, Object>();
        if (before != null) copyValues(before, values);
        copyValues(current, values);
        // JSON列按实际整体替换语义读取，不能把已移除的旧ext键重新合并回来。
        Object json = values.get("extensionFields");
        values.keySet().removeIf(ContentExtensionFields::isName);
        if (json instanceof String text) values.putAll(ContentExtensionFields.read(text));
        return values;
    }

    private static void copyValues(Object source, Map<String, Object> values) {
        for (var field : com.xinhongru.common.reflect.EntityFields.all(source.getClass())) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(source);
                if (value != null) values.put(field.getName(), value);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("搜索投影读取实际字段失败", error);
            }
        }
    }

    private static void add(List<String> parts, Object value, boolean rich) {
        if (!(value instanceof String text)) return;
        if (rich) {
            var document = Jsoup.parseBodyFragment(text);
            document.select("script,style,noscript,template").remove();
            text = document.body().text();
        }
        text = text.replaceAll("[\\p{Cc}\\s]+", " ").trim();
        if (!text.isEmpty()) parts.add(text);
    }

    public static List<Part> snippet(String text, String keyword, int maxLength) {
        if (maxLength < 1 || maxLength > 1000) throw new IllegalArgumentException("摘要长度须为1至1000");
        if (text == null || text.isEmpty()) return List.of();
        if (keyword == null || keyword.isBlank())
            return List.of(new Part(window(text, 0, maxLength), false));
        Pattern pattern =
                Pattern.compile(
                        Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        var first = pattern.matcher(text);
        int start = first.find() ? Math.max(0, first.start() - Math.min(60, maxLength / 3)) : 0;
        String window = window(text, start, maxLength);
        var matches = pattern.matcher(window);
        var result = new ArrayList<Part>();
        int last = 0;
        while (matches.find()) {
            if (matches.start() > last)
                result.add(new Part(window.substring(last, matches.start()), false));
            result.add(new Part(matches.group(), true));
            last = matches.end();
        }
        if (last < window.length()) result.add(new Part(window.substring(last), false));
        return List.copyOf(result);
    }

    private static String window(String text, int start, int length) {
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start++;
        int end = Math.min(text.length(), start + length);
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1)))
            end--;
        return text.substring(start, end);
    }
}

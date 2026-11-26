package com.xinhongru.content.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

/** 扩展DateTime的精度与值校验；仍保存原JSON字符串，不转换原生时间字段。 */
public final class ContentDateTimes {
    private ContentDateTimes() {}

    public static String precision(ContentFieldDefinition field) {
        String precision = field.getDatePrecision();
        if (precision == null) return "SECOND";
        if (!List.of("DAY", "SECOND").contains(precision))
            throw new IllegalArgumentException("日期精度仅支持DAY或SECOND");
        return precision;
    }

    private static boolean applies(ContentFieldDefinition field) {
        return field != null
                && ContentExtensionFields.isName(field.getFieldName())
                && "DateTime".equals(field.getFieldType());
    }

    public static void validateDefinition(ContentFieldDefinition field) {
        if (field.getDatePrecision() != null && !applies(field))
            throw new IllegalArgumentException("日期精度仅适用于扩展DateTime字段");
        if (!applies(field)) return;
        int length = "DAY".equals(precision(field)) ? 10 : 19;
        if (!Integer.valueOf(length).equals(field.getMaxLength())
                || field.getDefaultValue() != null && !field.getDefaultValue().isEmpty())
            throw new IllegalArgumentException("日期字段长度须匹配精度且不设置默认值，请在内容编辑器维护");
    }

    public static void validateValue(ContentFieldDefinition field, String value) {
        validateDefinition(field);
        if (!applies(field) || value == null || value.isEmpty()) return;
        boolean day = "DAY".equals(precision(field));
        if (day && value.matches("00[0-9]{2}-.*"))
            throw new IllegalArgumentException("日期字段支持0100至9999年，请保留来源待人工确认: " + field.getTitle());
        if (!value.matches(
                day
                        ? "[0-9]{4}-[0-9]{2}-[0-9]{2}"
                        : "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))
            throw new IllegalArgumentException("日期值格式须匹配字段精度: " + field.getTitle());
        try {
            if (day)
                LocalDate.parse(
                        value,
                        DateTimeFormatter.ofPattern("uuuu-MM-dd")
                                .withResolverStyle(ResolverStyle.STRICT));
            else
                LocalDateTime.parse(
                        value,
                        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                .withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("日期值无效: " + field.getTitle());
        }
    }

    /** 直接JSON同步也检查目标日期定义；不迁移或改写非日期字段。 */
    public static void validateStored(String json, List<ContentFieldDefinition> fields) {
        if (fields == null || fields.stream().noneMatch(ContentDateTimes::applies)) return;
        var values = ContentExtensionFields.read(json);
        for (var field : fields) {
            if (!applies(field) || !ContentFieldDefinition.enabled(field.getIsOpen())) continue;
            Object value = values.get(field.getFieldName());
            if (value != null && !(value instanceof String))
                throw new IllegalArgumentException("日期值须使用文本保存: " + field.getTitle());
            validateValue(field, (String) value);
            if (ContentFieldDefinition.enabled(field.getIsRequired())
                    && (value == null || ((String) value).isBlank()))
                throw new IllegalArgumentException("必填日期不能为空: " + field.getTitle());
        }
    }
}

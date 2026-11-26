package com.xinhongru.content.model;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** 显式统计数值输入方式的保守数值域；不改变普通SingleLine或isNumber。 */
public final class CounterNumbers {
    public static final String INPUT_TYPE = "counterNumber";
    public static final String DECIMAL_INPUT_TYPE = "counterDecimal";
    private static final BigDecimal MAX = new BigDecimal("9007199254740991");
    private static final Pattern NUMBER =
            Pattern.compile("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?");

    private CounterNumbers() {}

    public static boolean applies(ContentFieldDefinition field) {
        return field != null
                && (INPUT_TYPE.equals(field.getInputType())
                        || DECIMAL_INPUT_TYPE.equals(field.getInputType()));
    }

    public static void validateDefinition(ContentFieldDefinition field) {
        if (!applies(field)) return;
        if (!ContentExtensionFields.isName(field.getFieldName())
                || !"SingleLine".equals(field.getFieldType())
                || Boolean.TRUE.equals(field.getInputMultiple()))
            throw new IllegalArgumentException("统计数值输入仅适用显式新增的单值SingleLine字段，不自动迁移旧槽位");
        if (field.getDefaultValue() != null
                && !field.getDefaultValue().isBlank()
                && !supported(field.getInputType(), field.getDefaultValue())) throw invalid(field);
    }

    public static boolean supported(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 64
                || !NUMBER.matcher(value).matches()) return false;
        BigDecimal parsed = new BigDecimal(value.replace(",", ""));
        BigDecimal significant = parsed.stripTrailingZeros();
        return parsed.compareTo(MAX) <= 0
                && parsed.scale() <= 12
                && (significant.scale() <= 0 || significant.precision() <= 15);
    }

    /** 按脚本读取能力约束格式；旧counterNumber继续支持规范千分位。 */
    public static boolean supported(String inputType, String value) {
        if (!INPUT_TYPE.equals(inputType) && !DECIMAL_INPUT_TYPE.equals(inputType)) return false;
        if (DECIMAL_INPUT_TYPE.equals(inputType) && value != null && value.contains(","))
            return false;
        return supported(value);
    }

    public static void validate(ContentFieldDefinition field, Object value) {
        if (!applies(field)) return;
        validateDefinition(field);
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("统计数值须使用文本保存: " + field.getTitle());
        String text = value == null ? "" : (String) value;
        if (text.contains("\n") || text.contains("\r")) throw invalid(field);
        if (text.isBlank()) {
            if (ContentFieldDefinition.enabled(field.getIsRequired()))
                throw new IllegalArgumentException("必填统计数值不能为空: " + field.getTitle());
            return;
        }
        if (!supported(field.getInputType(), text)) throw invalid(field);
    }

    /** 直接JSON同步也只校验已明确标记的统计字段，不迁移其他历史字段。 */
    public static void validateStored(String json, java.util.List<ContentFieldDefinition> fields) {
        if (fields == null || fields.stream().noneMatch(CounterNumbers::applies)) return;
        var values = ContentExtensionFields.read(json);
        for (var field : fields) {
            if (!applies(field)) continue;
            validateDefinition(field);
            Object value = values.get(field.getFieldName());
            if (!ContentFieldDefinition.enabled(field.getIsOpen())
                    && (value == null || value instanceof String text && text.isBlank())) continue;
            validate(field, value);
        }
    }

    private static IllegalArgumentException invalid(ContentFieldDefinition field) {
        return new IllegalArgumentException(
                "统计数值支持0至9007199254740991、"
                        + (DECIMAL_INPUT_TYPE.equals(field.getInputType()) ? "不接受逗号、" : "规范逗号、")
                        + "最多12位小数且小数有效数字最多15位；单位/限定符须保留在目标外: "
                        + field.getTitle());
    }
}

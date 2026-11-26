package com.xinhongru.content.model;

import com.alibaba.fastjson.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON values for manually configured text extension fields. */
public final class ContentExtensionFields {
    private ContentExtensionFields() {}

    public static boolean isName(String name) {
        return name != null && name.matches("ext_[a-f0-9]{32}");
    }

    public static Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof Map<?, ?> object))
                throw new IllegalArgumentException("扩展字段值必须为JSON对象");
            Map<String, Object> values = new LinkedHashMap<>();
            object.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("扩展字段值必须为有效JSON对象", error);
        }
    }

    public static boolean isResource(ContentFieldDefinition field) {
        return field != null
                && java.util.Set.of("Image", "Video", "File")
                        .contains(field.getFieldType() == null ? "" : field.getFieldType());
    }

    public static void validateDefinition(ContentFieldDefinition field) {
        ContentDateTimes.validateDefinition(field);
        if (!isName(field.getFieldName())
                || field.getFieldType() == null
                || !java.util.Set.of(
                                "SingleLine",
                                "MultiLine",
                                "Editor",
                                "Image",
                                "Video",
                                "File",
                                "DateTime")
                        .contains(field.getFieldType()))
            throw new IllegalArgumentException("新增字段仅支持已开放的字段类型和有效的稳定标识");
        if (field.getTitle() == null
                || field.getTitle().isBlank()
                || field.getTitle().length() > 100)
            throw new IllegalArgumentException("字段业务名称不能为空且不能超过100字");
        int maximum =
                "DateTime".equals(field.getFieldType())
                        ? 19
                        : isResource(field)
                                ? 2048
                                : "SingleLine".equals(field.getFieldType())
                                        ? 500
                                        : "MultiLine".equals(field.getFieldType()) ? 10000 : 200000;
        if (field.getMaxLength() == null
                || field.getMaxLength() < 1
                || field.getMaxLength() > maximum)
            throw new IllegalArgumentException("字段长度必须为1至" + maximum);
        if (isResource(field)
                && field.getDefaultValue() != null
                && !field.getDefaultValue().isBlank())
            throw new IllegalArgumentException("资源字段请在内容编辑器选择资源，不设置字段默认值");
        if ((Boolean.TRUE.equals(field.getInputMultiple())
                        && !isFixedEnum(field)
                        && !ContentReferences.applies(field))
                || (field.getCascadeParentField() != null
                        && !field.getCascadeParentField().isBlank())
                || (field.getDefaultValue() != null
                        && field.getDefaultValue().startsWith("{$")
                        && !ContentReferences.applies(field)))
            throw new IllegalArgumentException("新增字段仅支持手动维护");
        if (field.getInputType() != null
                && !field.getInputType().isBlank()
                && !isFixedEnum(field)
                && !CounterNumbers.applies(field)
                && !ContentReferences.applies(field))
            throw new IllegalArgumentException("新增字段不支持选项输入方式");
        if (isFixedEnum(field)) enumValues(field);
        CounterNumbers.validateDefinition(field);
        if (ContentReferences.applies(field)) ContentReferences.menuIds(field);
        if ((ContentFieldDefinition.enabled(field.getIsList())
                        || ContentFieldDefinition.enabled(field.getIsSearch()))
                && !supportsQuery(field)) throw new IllegalArgumentException("新增字段的列表和搜索配置尚未开放");
    }

    public static boolean supportsQuery(ContentFieldDefinition field) {
        return field != null
                && java.util.Set.of("SingleLine", "DateTime")
                        .contains(field.getFieldType() == null ? "" : field.getFieldType());
    }

    /** 数值查询只派生查询能力，不改变内容写入与原值。 */
    public static boolean supportsNumericQuery(ContentFieldDefinition field) {
        return field != null
                && "SingleLine".equals(field.getFieldType())
                && !isFixedEnum(field)
                && ContentFieldDefinition.enabled(field.getIsNumber());
    }

    public static String numericQueryValue(String value) {
        if (value == null || !value.matches("[0-9]+"))
            throw new IllegalArgumentException("数值筛选须为非负十进制整数");
        String normalized = value.replaceFirst("^0+(?!$)", "");
        if (normalized.length() > 65) throw new IllegalArgumentException("数值筛选最多65个有效数字");
        return normalized;
    }

    private static boolean isFixedEnum(ContentFieldDefinition field) {
        return "SingleLine".equals(field.getFieldType()) && "enum".equals(field.getInputType());
    }

    private static java.util.Set<String> enumValues(ContentFieldDefinition field) {
        String source = field.getDefaultValue();
        if (source == null || source.isBlank() || source.length() > 10000)
            throw new IllegalArgumentException("请配置固定枚举选项");
        var options = source.split(",", -1);
        var values = new java.util.LinkedHashSet<String>();
        for (String option : options) {
            if (option.isBlank()
                    || !option.equals(option.trim())
                    || option.matches("(?s).*[\\r\\n|:=].*")
                    || option.startsWith("{$")
                    || option.length() > field.getMaxLength()
                    || !values.add(option)) throw new IllegalArgumentException("选项不能为空、重复或包含分隔符");
        }
        return values;
    }

    public static void validateRequired(
            String json, java.util.List<ContentFieldDefinition> fields) {
        ContentDateTimes.validateStored(json, fields);
        Map<String, Object> values = read(json);
        for (ContentFieldDefinition field : fields) {
            if (!isName(field.getFieldName()) || !ContentFieldDefinition.enabled(field.getIsOpen()))
                continue;
            validateDefinition(field);
            Object value = values.get(field.getFieldName());
            CounterNumbers.validate(field, value);
            if (ContentReferences.applies(field)) ContentReferences.normalize(field, value);
            if (ContentFieldDefinition.enabled(field.getIsRequired())
                    && (!(value instanceof String text) || text.isBlank()))
                throw new IllegalArgumentException("必填字段不能为空: " + field.getTitle());
        }
    }

    public static String put(String json, ContentFieldDefinition field, Object value) {
        validateDefinition(field);
        if (ContentReferences.applies(field)) value = ContentReferences.normalize(field, value);
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("字段值必须是文本: " + field.getFieldName());
        String text = value == null ? "" : (String) value;
        CounterNumbers.validate(field, text);
        if ((("SingleLine".equals(field.getFieldType()) || isResource(field))
                        && (text.contains("\n") || text.contains("\r")))
                || text.length() > field.getMaxLength())
            throw new IllegalArgumentException("字段长度或换行无效: " + field.getFieldName());
        if (ContentFieldDefinition.enabled(field.getIsRequired()) && text.isBlank())
            throw new IllegalArgumentException("必填字段不能为空: " + field.getTitle());
        if ("DateTime".equals(field.getFieldType())) ContentDateTimes.validateValue(field, text);
        if (isFixedEnum(field) && !text.isEmpty()) {
            var allowed = enumValues(field);
            if (Boolean.TRUE.equals(field.getInputMultiple())) {
                if (!text.startsWith(",") || !text.endsWith(",") || text.length() < 3)
                    throw new IllegalArgumentException("多选值格式无效: " + field.getTitle());
                var selected =
                        java.util.Arrays.asList(
                                text.substring(1, text.length() - 1).split(",", -1));
                if (new java.util.HashSet<>(selected).size() != selected.size()
                        || !allowed.containsAll(selected))
                    throw new IllegalArgumentException("多选包含重复或非法选项: " + field.getTitle());
            } else if (!allowed.contains(text))
                throw new IllegalArgumentException("非法选项: " + field.getTitle());
        }
        Map<String, Object> values = read(json);
        values.put(field.getFieldName(), text);
        return JSON.toJSONString(values);
    }
}

package com.xinhongru.content.model;

/** 工作台可以复用的原生单图字段约束。 */
public final class NativeImageFields {
    private NativeImageFields() {}

    public static boolean isName(String name) {
        return name != null && name.matches("photo(?:[1-9]|1[0-5])");
    }

    public static boolean supports(ContentFieldDefinition field) {
        String inputType = field.getInputType();
        return isName(field.getFieldName())
                && "Image".equals(field.getFieldType())
                && ContentFieldDefinition.enabled(field.getIsOpen())
                && !ContentFieldDefinition.enabled(field.getIsReadOnly())
                && !Boolean.TRUE.equals(field.getInputMultiple())
                && (inputType == null
                        || inputType.isBlank()
                        || "upload".equalsIgnoreCase(inputType));
    }

    public static int capacity(ContentFieldDefinition field) {
        Integer configured = field.getMaxLength();
        return configured != null && configured > 0 ? Math.min(configured, 255) : 255;
    }
}

package com.xinhongru.cms.formservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 表单字段定义解析测试（纯逻辑，无需 Spring/DB，可直接在 IDE 运行）。 */
class FormFieldDefinitionTest {
    @Test
    void parse_shouldSkipDisabledFields() {
        String json =
                "[{\"fieldName\":\"singleLine1\",\"fromName\":\"姓名\",\"fieldType\":\"SingleLine\",\"isOpen\":\"on\"},"
                    + "{\"fieldName\":\"singleLine2\",\"fromName\":\"隐藏字段\",\"fieldType\":\"SingleLine\"}"
                    + "]";

        List<FormFieldDefinition> fields = FormFieldDefinition.parse(json);

        assertEquals(1, fields.size());
        assertEquals("singleLine1", fields.get(0).fieldName());
    }

    @Test
    void parse_shouldPreferTitleAsLabelAndFallbackToFromName() {
        String json =
                "[{\"fieldName\":\"singleLine1\",\"fromName\":\"字段标题A\",\"title\":\"业务名称A\",\"isOpen\":\"on\"},"
                    + "{\"fieldName\":\"singleLine2\",\"fromName\":\"字段标题B\",\"isOpen\":\"on\"}]";

        List<FormFieldDefinition> fields = FormFieldDefinition.parse(json);

        assertEquals("业务名称A", fields.get(0).label());
        assertEquals("字段标题B", fields.get(1).label());
    }

    @Test
    void parse_shouldSplitEnumOptionsAndDedupe() {
        String json =
                "[{\"fieldName\":\"singleLine1\",\"fromName\":\"意向\",\"fieldType\":\"SingleLine\","
                        + "\"isOpen\":\"on\",\"inputType\":\"enum\",\"inputMultiple\":true,"
                        + "\"defaultValue\":\"产品咨询, 技术支持 ,产品咨询,,\"}]";

        FormFieldDefinition field = FormFieldDefinition.parse(json).get(0);

        assertEquals("enum", field.inputType());
        assertTrue(field.multiple());
        assertEquals(2, field.options().size());
        assertEquals("产品咨询", field.options().get(0).value());
        assertEquals("技术支持", field.options().get(1).value());
        assertEquals("产品咨询", field.options().get(0).label());
    }

    @Test
    void parse_shouldIgnoreOptionsForNonEnumInputType() {
        String json =
                "[{\"fieldName\":\"singleLine1\",\"fromName\":\"姓名\",\"isOpen\":\"on\","
                        + "\"inputType\":\"\",\"defaultValue\":\"a,b\"}]";

        FormFieldDefinition field = FormFieldDefinition.parse(json).get(0);

        assertTrue(field.options().isEmpty());
        assertEquals("a,b", field.defaultValue());
    }

    @Test
    void parse_shouldMapValidationsAndReadOnlyAndMaxLength() {
        String json =
                "[{\"fieldName\":\"singleLine1\",\"fromName\":\"手机\",\"isOpen\":\"on\","
                    + "\"isRequired\":\"on\",\"isTel\":\"on\",\"isReadOnly\":\"on\",\"maxLength\":11}]";

        FormFieldDefinition field = FormFieldDefinition.parse(json).get(0);

        assertTrue(field.required());
        assertTrue(field.readOnly());
        assertEquals(11, field.maxLength());
        assertTrue(field.validations().contains("required"));
        assertTrue(field.validations().contains("tel"));
        assertFalse(field.validations().contains("email"));
    }

    @Test
    void parse_shouldDedupeByFieldName() {
        String json =
                "["
                        + "{\"fieldName\":\"singleLine1\",\"fromName\":\"A\",\"isOpen\":\"on\"},"
                        + "{\"fieldName\":\"singleLine1\",\"fromName\":\"B\",\"isOpen\":\"on\"}"
                        + "]";

        List<FormFieldDefinition> fields = FormFieldDefinition.parse(json);

        assertEquals(1, fields.size());
        assertEquals("A", fields.get(0).label());
    }

    @Test
    void parse_shouldReturnEmptyOnBlankOrInvalidJson() {
        assertTrue(FormFieldDefinition.parse(null).isEmpty());
        assertTrue(FormFieldDefinition.parse("").isEmpty());
        assertTrue(FormFieldDefinition.parse("   ").isEmpty());
        assertTrue(FormFieldDefinition.parse("[]").isEmpty());
        assertTrue(FormFieldDefinition.parse("{}").isEmpty());
        assertTrue(FormFieldDefinition.parse("not-a-json").isEmpty());
    }

    @Test
    void generate_shouldProduceRequestedLength() {
        assertEquals(32, FormTokenGenerator.generate(32).length());
        assertEquals(1, FormTokenGenerator.generate(0).length());
    }
}

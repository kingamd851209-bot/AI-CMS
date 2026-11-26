package com.xinhongru.cms.formservice;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 表单字段定义（由分类的字段配置 JSON 解析而来）。
 *
 * <p>字段配置来源：单页分类取 {@code menuField}，列表分类取 {@code modelField}， 只保留 {@code isOpen == "on"} 的字段。{@code
 * inputType == "enum"} 表示固定枚举（下拉框）， 其可选值存于 {@code defaultValue}（逗号分隔）。
 *
 * @param fieldName 字段名（对应 t_from_contents 列名）
 * @param label 展示名（title 优先，回退 fromName）
 * @param fieldType 字段类型（SingleLine/MultiLine/Editor/...）
 * @param required 是否必填
 * @param readOnly 是否只读（提交时忽略）
 * @param maxLength 最大长度，<=0 表示不限
 * @param inputType 输入方式：''=手动输入，enum=固定枚举，content=栏目内容
 * @param multiple 多选（仅 enum/content 有效）
 * @param defaultValue 默认值
 * @param validations 验证项：required/tel/email/idCard/number/webUrl
 * @param options 枚举选项（仅 inputType=enum 时非空）
 */
public record FormFieldDefinition(
        String fieldName,
        String label,
        String fieldType,
        boolean required,
        boolean readOnly,
        int maxLength,
        String inputType,
        boolean multiple,
        String defaultValue,
        List<String> validations,
        List<Option> options) {
    private static final Logger LOG = LoggerFactory.getLogger(FormFieldDefinition.class);

    /** 枚举选项 */
    public record Option(String value, String label) {}

    /**
     * 解析字段配置 JSON 数组为字段定义列表。
     *
     * @param json 分类的 menuField/modelField 原始 JSON；为空或非法时返回空列表
     */
    public static List<FormFieldDefinition> parse(String json) {
        List<FormFieldDefinition> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        String trimmed = json.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) {
            return result;
        }
        JSONArray array;
        try {
            array = JSON.parseArray(trimmed);
        } catch (Exception e) {
            LOG.warn("表单字段配置 JSON 解析失败: {}", e.getMessage());
            return result;
        }
        if (array == null) {
            return result;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String fieldName = item.getString("fieldName");
            if (fieldName == null || fieldName.isBlank() || !seen.add(fieldName)) {
                continue;
            }
            if (!"on".equals(item.getString("isOpen"))) {
                continue;
            }
            result.add(fromItem(item, fieldName));
        }
        return result;
    }

    private static FormFieldDefinition fromItem(JSONObject item, String fieldName) {
        boolean required = "on".equals(item.getString("isRequired"));
        boolean readOnly = "on".equals(item.getString("isReadOnly"));
        String inputType = item.getString("inputType");
        if (inputType == null) {
            inputType = "";
        }
        boolean multiple = Boolean.TRUE.equals(item.getBoolean("inputMultiple"));
        String defaultValue = item.getString("defaultValue");
        if (defaultValue == null) {
            defaultValue = "";
        }
        Integer maxLength = item.getInteger("maxLength");
        String label =
                firstNonBlank(item.getString("title"), item.getString("fromName"), fieldName);
        List<String> validations = new ArrayList<>();
        if (required) {
            validations.add("required");
        }
        if ("on".equals(item.getString("isTel"))) {
            validations.add("tel");
        }
        if ("on".equals(item.getString("isEmail"))) {
            validations.add("email");
        }
        if ("on".equals(item.getString("isIdCard"))) {
            validations.add("idCard");
        }
        if ("on".equals(item.getString("isNumber"))) {
            validations.add("number");
        }
        if ("on".equals(item.getString("isWebUrl"))) {
            validations.add("webUrl");
        }
        List<Option> options = "enum".equals(inputType) ? parseOptions(defaultValue) : List.of();
        return new FormFieldDefinition(
                fieldName,
                label,
                item.getString("fieldType"),
                required,
                readOnly,
                maxLength == null ? 0 : maxLength,
                inputType,
                multiple,
                defaultValue,
                validations,
                options);
    }

    /** 固定枚举值：defaultValue 按逗号切分、去空白、去重；选项值即显示值 */
    private static List<Option> parseOptions(String defaultValue) {
        List<Option> options = new ArrayList<>();
        if (defaultValue == null || defaultValue.isBlank()) {
            return options;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : defaultValue.split(",")) {
            String value = raw.trim();
            if (!value.isEmpty() && seen.add(value)) {
                options.add(new Option(value, value));
            }
        }
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

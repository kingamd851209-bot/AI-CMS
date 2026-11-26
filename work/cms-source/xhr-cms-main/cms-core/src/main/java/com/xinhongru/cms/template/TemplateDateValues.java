package com.xinhongru.cms.template;

import com.xinhongru.content.model.ContentDateTimes;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

/** 日期绑定仅派生格式，不保存第二套日期值，不推测时刻。 */
public final class TemplateDateValues {
    private static final Map<String, DateTimeFormatter> FORMATS =
            Map.of(
                    "-",
                    formatter("uuuu-MM-dd"),
                    "/",
                    formatter("uuuu/MM/dd"),
                    ".",
                    formatter("uuuu.MM.dd"));

    private TemplateDateValues() {}

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private static DateTimeFormatter format(String original) {
        if (original == null || !original.matches("[0-9]{4}([-/.])[0-9]{2}\\1[0-9]{2}"))
            throw new IllegalArgumentException("日期须有完整日精度来源证据，格式暂不支持");
        var formatter = FORMATS.get(original.substring(4, 5));
        LocalDate.parse(original, formatter);
        return formatter;
    }

    public static boolean supported(String original) {
        try {
            initial(original);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static String initial(String original) {
        var date = LocalDate.parse(original, format(original));
        if (date.getYear() < 100)
            throw new IllegalArgumentException("日精度年份仅支持0100至9999，不能保真原文则待处理");
        return date.toString();
    }

    public static String original(
            TemplateFieldPlanService.Binding binding,
            List<TemplateContentUnitsService.Source> sources) {
        if (!"DateTime".equals(binding.fieldType()) || binding.sources().size() != 1)
            throw new IllegalArgumentException("日期仅支持一个完整TEXT来源");
        var ref = binding.sources().getFirst();
        var source =
                sources.stream()
                        .filter(s -> s.id().equals(ref.sourceId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("日期来源不存在"));
        if (!"TEXT".equals(source.kind())
                || ref.start() != 0
                || ref.end() != source.rawValue().length()
                || !source.rawValue().equals(binding.valuePreview())
                || binding.valueLength() != source.rawValue().length())
            throw new IllegalArgumentException("日期格式证据与完整来源不一致");
        initial(source.rawValue());
        return source.rawValue();
    }

    public static void validateField(
            TemplateFieldPlanService.Binding binding, ContentFieldDefinition field) {
        validateField(field, binding.valuePreview());
    }

    static void validateField(ContentFieldDefinition field, String original) {
        if (field == null
                || !ContentExtensionFields.isName(field.getFieldName())
                || !"DateTime".equals(field.getFieldType())
                || !"DAY".equals(ContentDateTimes.precision(field)))
            throw new IllegalArgumentException("日期绑定须明确日精度扩展DateTime，不自动迁移旧秒级或原生时间字段");
        ContentDateTimes.validateDefinition(field);
        initial(original);
    }

    public static String display(
            TemplateFieldPlanService.Binding binding, ContentFieldDefinition field, String value) {
        validateField(binding, field);
        ContentDateTimes.validateValue(field, value);
        return value == null || value.isEmpty()
                ? ""
                : LocalDate.parse(value, FORMATS.get("-")).format(format(binding.valuePreview()));
    }
}

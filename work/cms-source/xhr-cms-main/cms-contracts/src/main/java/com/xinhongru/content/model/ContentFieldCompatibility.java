package com.xinhongru.content.model;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

/** 已有新增字段的身份和值域兼容规则，人工配置与模型同步共用。 */
public final class ContentFieldCompatibility {
    private ContentFieldCompatibility() {}

    public static List<ContentFieldDefinition> retainExtensions(
            List<ContentFieldDefinition> saved, List<ContentFieldDefinition> requested) {
        List<ContentFieldDefinition> next =
                new ArrayList<>(requested == null ? List.of() : requested);
        Map<String, ContentFieldDefinition> submitted = new LinkedHashMap<>();
        for (ContentFieldDefinition field : next)
            if (field != null) submitted.put(field.getFieldName(), field);
        for (ContentFieldDefinition previous :
                saved == null ? List.<ContentFieldDefinition>of() : saved) {
            if (!ContentExtensionFields.isName(previous.getFieldName())) continue;
            ContentFieldDefinition field = submitted.get(previous.getFieldName());
            if (field == null) {
                previous.setIsOpen("");
                next.add(previous);
            } else validateExtensionChange(previous, field);
        }
        return next;
    }

    public static void validateExtensionChange(
            ContentFieldDefinition previous, ContentFieldDefinition next) {
        if (!Objects.equals(previous.getFieldType(), next.getFieldType()))
            throw new IllegalArgumentException("已有新增字段不能修改类型");
        if ("DateTime".equals(previous.getFieldType())
                && !ContentDateTimes.precision(previous).equals(ContentDateTimes.precision(next)))
            throw new IllegalArgumentException("已有日期字段不能修改精度，请新增独立日期字段");
        boolean incompatible =
                next.getMaxLength() == null
                        || (previous.getMaxLength() != null
                                && next.getMaxLength() < previous.getMaxLength())
                        || !Objects.equals(
                                StringUtils.defaultString(previous.getInputType()),
                                StringUtils.defaultString(next.getInputType()))
                        || Boolean.TRUE.equals(previous.getInputMultiple())
                                != Boolean.TRUE.equals(next.getInputMultiple());
        if ("enum".equals(previous.getInputType())) {
            Set<String> oldOptions =
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    StringUtils.defaultString(previous.getDefaultValue())
                                            .split(",", -1)));
            Set<String> newOptions =
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    StringUtils.defaultString(next.getDefaultValue())
                                            .split(",", -1)));
            incompatible |= !newOptions.containsAll(oldOptions);
        }
        List<String> oldFlags =
                Arrays.asList(
                        previous.getIsRequired(),
                        previous.getIsReadOnly(),
                        previous.getIsTel(),
                        previous.getIsEmail(),
                        previous.getIsIdCard(),
                        previous.getIsNumber(),
                        previous.getIsWebUrl());
        List<String> newFlags =
                Arrays.asList(
                        next.getIsRequired(),
                        next.getIsReadOnly(),
                        next.getIsTel(),
                        next.getIsEmail(),
                        next.getIsIdCard(),
                        next.getIsNumber(),
                        next.getIsWebUrl());
        for (int i = 0; i < oldFlags.size(); i++)
            incompatible |=
                    !ContentFieldDefinition.enabled(oldFlags.get(i))
                            && ContentFieldDefinition.enabled(newFlags.get(i));
        if (incompatible)
            throw new IllegalArgumentException(
                    "字段变更可能使已有内容或历史值失效：请保留长度、输入方式、已有选项和校验范围；可改名、扩展长度、增加选项或停用（保留历史值）");
    }
}

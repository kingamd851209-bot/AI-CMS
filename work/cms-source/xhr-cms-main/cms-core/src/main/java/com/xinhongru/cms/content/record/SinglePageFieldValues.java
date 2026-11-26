package com.xinhongru.cms.content.record;

import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.security.upload.ResourceHtml;
import com.xinhongru.security.upload.ResourceReferenceResolver;

import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.*;

/** 人工单页保存与工作台初始化共用原生赋值、扩展字段清洗及必填校验。 */
@Service
public class SinglePageFieldValues {
    private final ContentExtensionResources resources;

    @org.springframework.beans.factory.annotation.Autowired
    private ContentReferenceValues referenceValues;

    private final ResourceHtml resourceHtml;
    private final ResourceReferenceResolver resolver;

    public SinglePageFieldValues(
            ContentExtensionResources resources,
            ResourceHtml resourceHtml,
            ResourceReferenceResolver resolver) {
        this.resources = resources;
        this.resourceHtml = resourceHtml;
        this.resolver = resolver;
    }

    public void apply(
            OneshotEntity item,
            List<ContentFieldDefinition> fields,
            Map<String, Object> values,
            Long siteId) {
        apply(item, item, fields, values, siteId);
    }

    public void apply(
            OneshotEntity item,
            OneshotEntity current,
            List<ContentFieldDefinition> fields,
            Map<String, Object> values,
            Long siteId) {
        if (values == null) values = Map.of();
        Map<String, ContentFieldDefinition> enabled = new LinkedHashMap<>();
        for (ContentFieldDefinition field : fields)
            if (ContentFieldDefinition.enabled(field.getIsOpen()))
                enabled.put(field.getFieldName(), field);
        for (var entry : values.entrySet()) {
            ContentFieldDefinition field = enabled.get(entry.getKey());
            if (field == null) throw new IllegalArgumentException("字段未在单页模型中启用: " + entry.getKey());
            if (ContentFieldDefinition.enabled(field.getIsReadOnly())) continue;
            if (ContentExtensionFields.isName(entry.getKey())) {
                Object value = entry.getValue();
                if (com.xinhongru.content.model.ContentReferences.applies(field))
                    value = referenceValues.prepare(siteId, field, value);
                if (ContentExtensionFields.isResource(field))
                    value = resources.prepare(field, value, siteId);
                if ("Editor".equals(field.getFieldType())
                        && (value == null || value instanceof String)) {
                    value =
                            com.xinhongru.cms.content.record.ContentExtensionHtml.clean(
                                    (String) value);
                    if (resourceHtml != null)
                        value = resourceHtml.canonical((String) value, resolver.siteCode(siteId));
                }
                item.setExtensionFields(
                        ContentExtensionFields.put(item.getExtensionFields(), field, value));
            } else {
                Object value = entry.getValue();
                if (NativeImageFields.supports(field)) {
                    if (Objects.equals(value, readNativeImage(current, entry.getKey()))) continue;
                    value = resources.prepareTemplateMedia(field, value, siteId);
                }
                writeSinglePageValue(item, entry.getKey(), value);
            }
        }
        ContentExtensionFields.validateRequired(item.getExtensionFields(), fields);
    }

    private static Object readNativeImage(OneshotEntity item, String name) {
        try {
            Field field = EntityFields.find(OneshotEntity.class, name);
            field.setAccessible(true);
            return field.get(item);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("原生图片字段不可读取: " + name, error);
        }
    }

    private static void writeSinglePageValue(OneshotEntity item, String fieldName, Object value) {
        try {
            Field field = EntityFields.find(OneshotEntity.class, fieldName);
            if (!field.canAccess(item)) {
                field.setAccessible(true);
            }
            if (value == null) {
                field.set(item, null);
                return;
            }
            if (field.getType().equals(String.class)) {
                field.set(item, String.valueOf(value));
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // Ignore stale legacy field configuration entries.
        }
    }
}

package com.xinhongru.common.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;

/** Reflection used by template/Excel adapters, including inherited entity properties. */
public final class EntityFields {
    private EntityFields() {}

    public static Field[] all(Class<?> type) {
        var fields = new LinkedHashMap<String, Field>();
        for (Class<?> current = type;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                    fields.putIfAbsent(field.getName(), field);
            }
        }
        return fields.values().toArray(Field[]::new);
    }

    public static Field find(Class<?> type, String name) throws NoSuchFieldException {
        for (Field field : all(type)) if (field.getName().equals(name)) return field;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}

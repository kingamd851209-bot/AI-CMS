package com.xinhongru.utils;

import com.xinhongru.common.reflect.EntityFields;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.StringJoiner;

/**
 * @Description @Copyright (c) 1998-2022 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName QueryUtils @Author guoyangyang <519156724@qq.com> @Version
 * V1.0.0 @Since 1.0 @Date 2023/1/5 13:17
 */
@Slf4j
public class QueryUtils {
    private QueryUtils() {}

    public static <T> String getQueryString(T t) {
        // 获取类对象
        final Class<?> aClass = t.getClass();
        // 获取当前类下的属性
        Field[] declaredFields = EntityFields.all(aClass);

        StringJoiner queryString = new StringJoiner("&", "?", "").setEmptyValue("");
        for (Field declaredField : declaredFields) {
            // 判断是否可以访问，无法访问的话需要设置成可访问
            if (!declaredField.isAccessible()) {
                declaredField.setAccessible(true);
            }
            try {
                // 从对象中取字段值
                final Object o = declaredField.get(t);
                if (o != null && !declaredField.getName().equals("pageIndex")) {
                    queryString.add(
                            java.net.URLEncoder.encode(
                                            declaredField.getName(),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    + "="
                                    + java.net.URLEncoder.encode(
                                            o.toString(), java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IllegalAccessException e) {
                log.error("获取{}的属性出现异常", declaredField.getName());
            }
        }
        return queryString.toString();
    }
}

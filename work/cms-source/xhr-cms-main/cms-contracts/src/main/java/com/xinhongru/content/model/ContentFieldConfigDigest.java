package com.xinhongru.content.model;

import com.alibaba.fastjson.JSON;
import com.xinhongru.entity.OneshotEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 正式字段保存入口与工作台计划使用同一配置摘要，不包含运营内容值。 */
public final class ContentFieldConfigDigest {
    private ContentFieldConfigDigest() {}

    public static String of(OneshotEntity menu) {
        if (menu == null) throw new IllegalArgumentException("栏目不存在");
        String value =
                JSON.toJSONString(
                        Arrays.asList(
                                menu.getId(),
                                menu.getSiteProjectId(),
                                menu.getPageType(),
                                menu.getHasManageChannel(),
                                menu.getModelField(),
                                menu.getMenuField(),
                                menu.getModelList(),
                                menu.getModelSearch()));
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

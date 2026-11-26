package com.xinhongru.cms.formservice;

import java.security.SecureRandom;

/** 表单服务用随机串生成（Base62），用于站点认证 key 与提交回查凭证。 */
public final class FormTokenGenerator {
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private FormTokenGenerator() {}

    public static String generate(int length) {
        int size = Math.max(1, length);
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}

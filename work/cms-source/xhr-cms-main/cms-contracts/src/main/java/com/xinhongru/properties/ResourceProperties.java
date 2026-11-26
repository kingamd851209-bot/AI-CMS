package com.xinhongru.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

/** 全部逻辑资源引用共用的外部访问域名；不改变磁盘存储位置。 */
@Component
@ConfigurationProperties(prefix = "cms.resources")
public class ResourceProperties {
    private String domain = "";

    public String getDomain() {
        return domain;
    }

    /** 旧裸主机值只允许原样保留；新配置必须显式指定协议。 */
    public static String normalizeSiteDomain(String value, String previous) {
        value = value == null ? "" : value.trim();
        if (value.isEmpty()) return null;
        if (value.equals(previous) && !isExplicitDomain(value)) return value;
        if (value.length() > 255) throw new IllegalArgumentException("资源域名不能超过 255 个字符");
        return normalizeDomain(value);
    }

    public static boolean isExplicitDomain(String value) {
        return value != null && value.matches("(?i)^https?://.*");
    }

    public void setDomain(String value) {
        domain = normalizeDomain(value);
    }

    private static String normalizeDomain(String value) {
        value = value == null ? "" : value.trim();
        if (!value.isEmpty()) {
            try {
                URI uri = URI.create(value);
                if (!("http".equalsIgnoreCase(uri.getScheme())
                                || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || uri.getPort() > 65535
                        || uri.getPort() == 0
                        || value.contains("{")
                        || value.contains("\\")
                        || value.contains("%")
                        || uri.getPath().contains("..")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("资源域名必须为完整 HTTP(S) 地址，可带端口和路径前缀，不能包含查询参数或片段");
            }
        }
        return value.replaceAll("/+$", "");
    }
}

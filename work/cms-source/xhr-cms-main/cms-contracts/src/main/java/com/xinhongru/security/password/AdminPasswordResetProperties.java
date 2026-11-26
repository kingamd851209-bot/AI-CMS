package com.xinhongru.security.password;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cms.password-reset")
public class AdminPasswordResetProperties {
    private boolean enabled;

    private String resetValue = "Cms@2026!Reset";
}

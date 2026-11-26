package com.xinhongru.security.preview;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 环境级总开关；关闭时本站部署中的所有站点均不启用预发布认证。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cms.preview-access")
public class PreviewAccessProperties {
    private boolean enabled;
}

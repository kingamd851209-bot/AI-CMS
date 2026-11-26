package com.xinhongru.properties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "cms.wechat")
@ConditionalOnProperty(
        prefix = "cms.wechat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WechatProperties {
    /** 微信导入接口地址 不含最后的 / */
    private String apiUrl;

    /** 删除已导入的压缩包地址 含最后的 / */
    private String deleteUrl;

    /** 默认开启 */
    private Boolean enabled = true;
}

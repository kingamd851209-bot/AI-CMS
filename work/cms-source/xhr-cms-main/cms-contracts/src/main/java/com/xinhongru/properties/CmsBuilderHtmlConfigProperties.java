package com.xinhongru.properties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description @ClassName CmsBuilderHtmlConfigProperties @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/9/3 11:58
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "cms.html")
public class CmsBuilderHtmlConfigProperties {
    /** 动态页面地址 默认为本地地址 */
    private String dynamicUrl;

    /** HTML保存路径 默认为根目录 */
    private String saveRoot = System.getProperty("user.dir");

    /** 默认开启 */
    private Boolean enabled = true;
}

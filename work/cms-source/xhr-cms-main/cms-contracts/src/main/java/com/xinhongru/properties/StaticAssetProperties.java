package com.xinhongru.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 静态资源库配置 */
@Component
@ConfigurationProperties(prefix = "cms.static-asset")
public class StaticAssetProperties {
    private String root = Path.of(System.getProperty("user.dir"), "runtime", "assets").toString();

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}

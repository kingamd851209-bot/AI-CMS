package com.xinhongru.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 生产多节点共享磁盘约束，与资源域名和资源权限无关。 */
@Component
@ConfigurationProperties(prefix = "cms.storage")
public class BusinessStorageProperties {
    private boolean sharedRequired;
    private String sharedRoot;

    public boolean isSharedRequired() {
        return sharedRequired;
    }

    public void setSharedRequired(boolean value) {
        sharedRequired = value;
    }

    public String getSharedRoot() {
        return sharedRoot;
    }

    public void setSharedRoot(String value) {
        sharedRoot = value;
    }
}

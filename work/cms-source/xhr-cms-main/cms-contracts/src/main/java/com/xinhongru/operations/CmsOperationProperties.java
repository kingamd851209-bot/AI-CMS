package com.xinhongru.operations;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cms.operation")
public class CmsOperationProperties {
    private String instanceId;
    private long leaseSeconds = 60;
    private int workerBatchSize = 10;
    private boolean requireRedis;

    String ownerOr(String fallback) {
        return instanceId == null || instanceId.isBlank() ? fallback : instanceId.trim();
    }
}

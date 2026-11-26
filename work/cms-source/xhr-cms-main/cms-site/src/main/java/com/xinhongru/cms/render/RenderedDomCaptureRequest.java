package com.xinhongru.cms.render;

public record RenderedDomCaptureRequest(
        Long batchId,
        Long pageSnapshotId,
        String pageUrl,
        String pagePath,
        String htmlContent,
        Integer viewportWidth,
        Integer viewportHeight) {}

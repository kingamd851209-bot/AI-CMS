package com.xinhongru.cms.render;

public record RenderedDomCaptureResult(
        RenderedDomCaptureStatus status,
        String htmlSnapshot,
        String screenshotPath,
        String networkSummary,
        Integer viewportWidth,
        Integer viewportHeight,
        String errorCode,
        String errorMessage,
        String evidenceJson) {
    public static RenderedDomCaptureResult captured(
            String htmlSnapshot,
            String screenshotPath,
            String networkSummary,
            Integer viewportWidth,
            Integer viewportHeight,
            String evidenceJson) {
        return new RenderedDomCaptureResult(
                RenderedDomCaptureStatus.CAPTURED,
                htmlSnapshot,
                screenshotPath,
                networkSummary,
                viewportWidth,
                viewportHeight,
                null,
                null,
                evidenceJson);
    }

    public static RenderedDomCaptureResult failed(
            String errorCode,
            String errorMessage,
            Integer viewportWidth,
            Integer viewportHeight,
            String evidenceJson) {
        return new RenderedDomCaptureResult(
                RenderedDomCaptureStatus.FAILED,
                null,
                null,
                null,
                viewportWidth,
                viewportHeight,
                errorCode,
                errorMessage,
                evidenceJson);
    }
}

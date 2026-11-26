package com.xinhongru.ai.site.security;

import java.util.List;

public record UrlSampleValidationResult(
        boolean accepted, String normalizedUrl, String host, List<UrlSampleViolation> violations) {
    public static UrlSampleValidationResult accepted(String normalizedUrl, String host) {
        return new UrlSampleValidationResult(true, normalizedUrl, host, List.of());
    }

    public static UrlSampleValidationResult rejected(
            String normalizedUrl, String host, List<UrlSampleViolation> violations) {
        return new UrlSampleValidationResult(false, normalizedUrl, host, List.copyOf(violations));
    }
}

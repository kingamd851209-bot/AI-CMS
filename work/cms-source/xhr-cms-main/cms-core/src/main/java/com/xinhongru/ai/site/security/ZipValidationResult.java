package com.xinhongru.ai.site.security;

import java.util.List;

public record ZipValidationResult(
        boolean accepted, List<ZipViolation> violations, List<ZipEntrySummary> entrySummaries) {
    public static ZipValidationResult accepted(List<ZipEntrySummary> entrySummaries) {
        return new ZipValidationResult(true, List.of(), List.copyOf(entrySummaries));
    }

    public static ZipValidationResult rejected(
            List<ZipViolation> violations, List<ZipEntrySummary> entrySummaries) {
        return new ZipValidationResult(false, List.copyOf(violations), List.copyOf(entrySummaries));
    }
}

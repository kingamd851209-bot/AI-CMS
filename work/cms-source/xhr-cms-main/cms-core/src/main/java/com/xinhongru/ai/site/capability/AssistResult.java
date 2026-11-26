package com.xinhongru.ai.site.capability;

import java.util.List;

public record AssistResult(
        String status,
        String reasonCode,
        List<AssistSuggestion> suggestions,
        String rawResponseJson,
        String diagnosticMessage) {
    public AssistResult(
            String status,
            String reasonCode,
            List<AssistSuggestion> suggestions,
            String rawResponseJson) {
        this(status, reasonCode, suggestions, rawResponseJson, null);
    }

    public static AssistResult skipped(String reasonCode) {
        return new AssistResult("SKIPPED", reasonCode, List.of(), null);
    }

    public static AssistResult failed(String reasonCode, String message) {
        return new AssistResult("FAILED", reasonCode, List.of(), message);
    }

    public static AssistResult success(List<AssistSuggestion> suggestions, String rawResponseJson) {
        return new AssistResult(
                "SUCCESS",
                null,
                suggestions == null ? List.of() : List.copyOf(suggestions),
                rawResponseJson);
    }

    public AssistResult withDiagnostic(String diagnosticMessage) {
        if (diagnosticMessage == null || diagnosticMessage.isBlank()) {
            return this;
        }
        String merged =
                this.diagnosticMessage == null || this.diagnosticMessage.isBlank()
                        ? diagnosticMessage
                        : this.diagnosticMessage + "; " + diagnosticMessage;
        return new AssistResult(status, reasonCode, suggestions, rawResponseJson, merged);
    }
}

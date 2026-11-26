package com.xinhongru.ai.site.capability;

import java.util.List;

public record AssistSuggestion(
        String capabilityCode,
        String action,
        Integer confidence,
        String reason,
        List<String> evidence) {}

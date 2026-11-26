package com.xinhongru.ai.site.visual;

import java.util.List;
import java.util.Map;

public record VisualElementCandidate(
        String selector,
        String stableSelector,
        int selectorScore,
        String tagName,
        String text,
        String imageSrc,
        String linkHref,
        BBox bbox,
        int domDepth,
        int repeatIndex,
        boolean visible,
        String role,
        String type,
        Map<String, Object> evidence,
        List<String> diagnostics) {
    public VisualElementCandidate {
        selector = defaultText(selector);
        stableSelector = defaultText(stableSelector);
        selectorScore = Math.max(0, Math.min(100, selectorScore));
        tagName = defaultText(tagName);
        text = defaultText(text);
        imageSrc = blankToNull(imageSrc);
        linkHref = blankToNull(linkHref);
        bbox = bbox == null ? new BBox(0, 0, 0, 0) : bbox;
        domDepth = Math.max(0, domDepth);
        repeatIndex = Math.max(0, repeatIndex);
        role = blankToNull(role);
        type = blankToNull(type);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    private static String defaultText(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record BBox(double x, double y, double width, double height) {}
}

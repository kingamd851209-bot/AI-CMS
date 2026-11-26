package com.xinhongru.resource;

import java.util.List;

public record AssetReferenceCheck(String path, boolean referenced, List<Usage> references) {
    public record Usage(
            String sourceType, String sourceName, String fieldName, String valuePreview) {}
}

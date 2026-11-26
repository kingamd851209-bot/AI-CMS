package com.xinhongru.ai.site.security;

public record ZipEntrySummary(
        String entryName,
        String normalizedPath,
        long uncompressedBytes,
        long compressedBytes,
        boolean directory,
        boolean html) {}

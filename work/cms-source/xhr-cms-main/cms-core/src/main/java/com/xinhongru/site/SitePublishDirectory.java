package com.xinhongru.site;

import java.util.regex.Pattern;

public final class SitePublishDirectory {
    private static final Pattern SAFE_DIRECTORY =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,127}$");

    private SitePublishDirectory() {}

    public static String resolve(String configured, String siteName, String siteCode) {
        if (configured != null && !configured.isBlank()) return requireValid(configured);
        String normalizedName = normalizeDefault(siteName);
        return normalizedName.isBlank() ? requireValid(siteCode) : normalizedName;
    }

    public static String requireValid(String value) {
        String candidate = value == null ? "" : value.trim();
        if (!SAFE_DIRECTORY.matcher(candidate).matches()) {
            throw new IllegalArgumentException("发布目录只能包含中文、字母、数字、短横线和下划线，且不能超过 128 个字符");
        }
        return candidate;
    }

    private static String normalizeDefault(String value) {
        if (value == null) return "";
        String normalized =
                value.trim().replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }
}

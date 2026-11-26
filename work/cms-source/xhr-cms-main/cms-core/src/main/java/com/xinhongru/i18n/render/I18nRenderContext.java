package com.xinhongru.i18n.render;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public record I18nRenderContext(String languageCode, String dynamicUrl, String saveRoot) {
    private static final Pattern SAFE_LANGUAGE_CODE =
            Pattern.compile("[A-Za-z0-9_-]+(?:-[A-Za-z0-9_-]+)*");

    public static I18nRenderContext of(String languageCode, String dynamicUrl, String saveRoot) {
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        String normalizedSaveRoot =
                normalizedLanguageCode == null
                        ? saveRoot
                        : trimTrailingSlash(saveRoot) + "/" + normalizedLanguageCode;
        return new I18nRenderContext(normalizedLanguageCode, dynamicUrl, normalizedSaveRoot);
    }

    public String toBuildHref(String href) {
        if (languageCode == null) {
            return href + (href.contains("?") ? "&" : "?") + "buildStatus=true";
        }
        String separator = href.contains("?") ? "&" : "?";
        return href
                + separator
                + "buildStatus=true&languageCode="
                + URLEncoder.encode(languageCode, StandardCharsets.UTF_8);
    }

    public String filePath(String savePath) {
        return saveRoot + savePath;
    }

    private static String normalizeLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return null;
        }
        String normalized = languageCode.trim();
        if (!SAFE_LANGUAGE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("languageCode contains unsafe path segment");
        }
        return normalized;
    }

    private static String trimTrailingSlash(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String normalized = path;
        while (normalized.endsWith("/") || normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}

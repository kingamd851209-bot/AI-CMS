package com.xinhongru.ai.site.security;

import java.util.Set;
import java.util.stream.Collectors;

public record ZipSafetyPolicy(
        int maxEntries,
        long maxSingleEntryBytes,
        long maxTotalUncompressedBytes,
        double maxCompressionRatio,
        Set<String> allowedHtmlExtensions,
        Set<String> allowedResourceExtensions,
        Set<String> blockedExtensions) {
    public ZipSafetyPolicy {
        allowedHtmlExtensions = normalizeExtensions(allowedHtmlExtensions);
        allowedResourceExtensions = normalizeExtensions(allowedResourceExtensions);
        blockedExtensions = normalizeExtensions(blockedExtensions);
    }

    public static ZipSafetyPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isHtmlExtension(String path) {
        String extension = extensionOf(path);
        return allowedHtmlExtensions.contains(extension);
    }

    public boolean isBlockedExtension(String path) {
        String extension = extensionOf(path);
        return blockedExtensions.contains(extension);
    }

    public boolean isKnownFileExtension(String path) {
        String extension = extensionOf(path);
        return allowedHtmlExtensions.contains(extension)
                || allowedResourceExtensions.contains(extension);
    }

    public boolean isIgnoredSystemEntry(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = normalizeZipPath(path);
        if (normalized.equals("__MACOSX") || normalized.equals("__MACOSX/")) {
            return true;
        }
        if (!normalized.startsWith("__MACOSX/")) {
            return false;
        }
        String rest = normalized.substring("__MACOSX/".length());
        int slash = rest.lastIndexOf('/');
        String filename = slash < 0 ? rest : rest.substring(slash + 1);
        return filename.startsWith("._") && filename.length() > 2;
    }

    public boolean isMacOsSystemPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = normalizeZipPath(path);
        return normalized.equals("__MACOSX") || normalized.startsWith("__MACOSX/");
    }

    static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot).toLowerCase();
    }

    private static Set<String> normalizeExtensions(Set<String> extensions) {
        return extensions.stream()
                .map(String::trim)
                .filter(extension -> !extension.isEmpty())
                .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeZipPath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public static final class Builder {
        private int maxEntries = 500;
        private long maxSingleEntryBytes = 200L * 1024L * 1024L;
        private long maxTotalUncompressedBytes = 1024L * 1024L * 1024L;
        private double maxCompressionRatio = 100.0d;
        private Set<String> allowedHtmlExtensions =
                Set.of(".html", ".htm", ".aspx", ".shtml", ".xhtml");
        private Set<String> allowedResourceExtensions =
                Set.of(
                        ".css", ".js", ".mjs", ".json", ".xml", ".txt", ".map", ".png", ".jpg",
                        ".jpeg", ".gif", ".svg", ".webp", ".ico", ".woff", ".woff2", ".ttf", ".otf",
                        ".eot", ".mp4", ".webm", ".mp3", ".wav", ".pdf");
        private Set<String> blockedExtensions =
                Set.of(
                        ".ade",
                        ".adp",
                        ".apk",
                        ".app",
                        ".appx",
                        ".bat",
                        ".bin",
                        ".cmd",
                        ".com",
                        ".cpl",
                        ".dll",
                        ".dmg",
                        ".exe",
                        ".gadget",
                        ".hta",
                        ".ins",
                        ".iso",
                        ".jar",
                        ".jscript",
                        ".lnk",
                        ".msi",
                        ".msp",
                        ".mst",
                        ".pif",
                        ".ps1",
                        ".reg",
                        ".scr",
                        ".sh",
                        ".vb",
                        ".vbe",
                        ".vbs",
                        ".ws",
                        ".wsc",
                        ".wsf",
                        ".wsh");

        private Builder() {}

        public Builder maxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        public Builder maxSingleEntryBytes(long maxSingleEntryBytes) {
            this.maxSingleEntryBytes = maxSingleEntryBytes;
            return this;
        }

        public Builder maxTotalUncompressedBytes(long maxTotalUncompressedBytes) {
            this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
            return this;
        }

        public Builder maxCompressionRatio(double maxCompressionRatio) {
            this.maxCompressionRatio = maxCompressionRatio;
            return this;
        }

        public Builder allowedHtmlExtensions(Set<String> allowedHtmlExtensions) {
            this.allowedHtmlExtensions = allowedHtmlExtensions;
            return this;
        }

        public Builder allowedResourceExtensions(Set<String> allowedResourceExtensions) {
            this.allowedResourceExtensions = allowedResourceExtensions;
            return this;
        }

        public Builder blockedExtensions(Set<String> blockedExtensions) {
            this.blockedExtensions = blockedExtensions;
            return this;
        }

        public ZipSafetyPolicy build() {
            return new ZipSafetyPolicy(
                    maxEntries,
                    maxSingleEntryBytes,
                    maxTotalUncompressedBytes,
                    maxCompressionRatio,
                    allowedHtmlExtensions,
                    allowedResourceExtensions,
                    blockedExtensions);
        }
    }
}

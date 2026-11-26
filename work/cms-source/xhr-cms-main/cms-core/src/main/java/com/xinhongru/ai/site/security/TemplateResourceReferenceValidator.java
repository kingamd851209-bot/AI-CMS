package com.xinhongru.ai.site.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that local resources referenced by a frontend package are archived in the same package.
 */
public final class TemplateResourceReferenceValidator {
    private static final Pattern CSS_URL =
            Pattern.compile("url\\s*\\(\\s*(['\\\"]?)(.*?)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> RESOURCE_LINK_RELATIONS =
            Set.of(
                    "stylesheet",
                    "icon",
                    "manifest",
                    "preload",
                    "modulepreload",
                    "apple-touch-icon");

    private TemplateResourceReferenceValidator() {}

    public static void validate(Map<String, byte[]> contents, Set<String> htmlPaths) {
        Set<String> packagePaths = Set.copyOf(contents.keySet());
        for (String htmlPath : htmlPaths) {
            byte[] bytes = contents.get(htmlPath);
            if (bytes != null) {
                validateHtml(htmlPath, new String(bytes, StandardCharsets.UTF_8), packagePaths);
            }
        }
        contents.forEach(
                (path, bytes) -> {
                    if (path.toLowerCase(Locale.ROOT).endsWith(".css")) {
                        validateCss(path, new String(bytes, StandardCharsets.UTF_8), packagePaths);
                    }
                });
    }

    private static void validateHtml(String ownerPath, String html, Set<String> packagePaths) {
        Document document = Jsoup.parse(html);
        for (Element element :
                document.select(
                        "script[src], img[src], source[src], video[src], video[poster], audio[src],"
                            + " track[src], embed[src], object[data], input[type=image][src]")) {
            String attribute =
                    element.hasAttr("data") ? "data" : element.hasAttr("poster") ? "poster" : "src";
            if (element.tagName().equals("script") && isExternal(element.attr(attribute))) {
                if ("integration".equalsIgnoreCase(element.attr("data-cms-external"))) {
                    continue;
                }
                throw new ReferenceViolation(
                        "EXTERNAL_RESOURCE_NOT_ARCHIVED",
                        ownerPath,
                        element.attr(attribute),
                        "阻塞型外部脚本必须归档到模板包内: " + element.attr(attribute));
            }
            requireArchived(ownerPath, element.attr(attribute), packagePaths);
        }
        for (Element link : document.select("link[href]")) {
            boolean resourceLink =
                    link.attr("rel")
                            .toLowerCase(Locale.ROOT)
                            .lines()
                            .flatMap(value -> Arrays.stream(value.split("\\s+")))
                            .anyMatch(RESOURCE_LINK_RELATIONS::contains);
            if (resourceLink) {
                requireArchived(ownerPath, link.attr("href"), packagePaths);
            }
        }
        for (Element element : document.select("img[srcset], source[srcset]")) {
            for (String candidate : element.attr("srcset").split(",")) {
                String value = candidate.trim().split("\\s+", 2)[0];
                requireArchived(ownerPath, value, packagePaths);
            }
        }
        for (Element element : document.select("[style]")) {
            validateCss(ownerPath, element.attr("style"), packagePaths);
        }
        for (Element element : document.select("style")) {
            validateCss(ownerPath, element.data(), packagePaths);
        }
        for (Element anchor : document.select("a[href]")) {
            requireArchivedPage(ownerPath, anchor.attr("href"), packagePaths);
        }
    }

    private static void validateCss(String ownerPath, String css, Set<String> packagePaths) {
        Matcher matcher = CSS_URL.matcher(css);
        while (matcher.find()) {
            requireArchived(ownerPath, matcher.group(2), packagePaths);
        }
    }

    private static void requireArchived(String ownerPath, String value, Set<String> packagePaths) {
        String resolved = resolve(ownerPath, value);
        if (resolved != null && !packagePaths.contains(resolved)) {
            throw new ReferenceViolation(
                    "RESOURCE_NOT_FOUND", ownerPath, resolved, "模板引用的文件不存在: " + resolved);
        }
    }

    private static void requireArchivedPage(
            String ownerPath, String value, Set<String> packagePaths) {
        if (isExternal(value)) return;
        String resolved = resolve(ownerPath, value);
        if (resolved == null || !requiresArchivedPage(value)) return;
        if (packagePaths.contains(resolved) || packagePaths.contains(directoryIndex(resolved)))
            return;
        throw new ReferenceViolation(
                "PAGE_NOT_FOUND", ownerPath, resolved, "模板引用的页面不存在: " + resolved);
    }

    private static boolean requiresArchivedPage(String value) {
        String candidate = stripSuffix(value == null ? "" : value.trim());
        if (candidate.isBlank()) return false;
        String name = candidate.substring(candidate.lastIndexOf('/') + 1);
        return candidate.endsWith("/") || name.matches("(?i).+\\.(html?|shtml)$");
    }

    private static String directoryIndex(String resolved) {
        return resolved.endsWith("/") ? resolved + "index.html" : resolved + "/index.html";
    }

    private static boolean isExternal(String value) {
        if (value == null) return false;
        String candidate = value.trim();
        return candidate.startsWith("//") || candidate.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private static String stripSuffix(String value) {
        int query = value.indexOf('?');
        int anchor = value.indexOf('#');
        int end = query < 0 ? anchor : anchor < 0 ? query : Math.min(query, anchor);
        return end < 0 ? value : value.substring(0, end);
    }

    private static String resolve(String ownerPath, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("#")
                || candidate.startsWith("data:")
                || candidate.startsWith("mailto:")
                || candidate.startsWith("tel:")
                || candidate.startsWith("javascript:")
                || candidate.startsWith("//")
                || candidate.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return null;
        }
        int query = candidate.indexOf('?');
        int anchor = candidate.indexOf('#');
        int end = query < 0 ? anchor : anchor < 0 ? query : Math.min(query, anchor);
        if (end >= 0) {
            candidate = candidate.substring(0, end);
        }
        if (candidate.isBlank()) {
            return null;
        }
        Path owner = Path.of(ownerPath);
        Path resolved =
                candidate.startsWith("/")
                        ? Path.of(candidate.substring(1))
                        : owner.getParent() == null
                                ? Path.of(candidate)
                                : owner.getParent().resolve(candidate);
        String normalized = resolved.normalize().toString().replace('\\', '/');
        if (resolved.isAbsolute()
                || normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.contains("/../")) {
            throw new ReferenceViolation(
                    "PATH_TRAVERSAL", ownerPath, value, "模板引用越出包根目录: " + value);
        }
        return normalized;
    }

    public static final class ReferenceViolation extends IllegalArgumentException {
        private final String code;
        private final String ownerPath;
        private final String referencedPath;

        private ReferenceViolation(
                String code, String ownerPath, String referencedPath, String message) {
            super(code + ": " + message + " (" + ownerPath + ")");
            this.code = code;
            this.ownerPath = ownerPath;
            this.referencedPath = referencedPath;
        }

        public String code() {
            return code;
        }

        public String ownerPath() {
            return ownerPath;
        }

        public String referencedPath() {
            return referencedPath;
        }
    }
}

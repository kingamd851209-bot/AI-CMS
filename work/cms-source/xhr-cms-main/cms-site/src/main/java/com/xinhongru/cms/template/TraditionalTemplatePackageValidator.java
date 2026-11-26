package com.xinhongru.cms.template;

import com.xinhongru.ai.site.security.TemplateResourceReferenceValidator;
import com.xinhongru.ai.site.security.ZipEntrySummary;
import com.xinhongru.ai.site.security.ZipSafetyValidator;
import com.xinhongru.ai.site.security.ZipValidationResult;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class TraditionalTemplatePackageValidator {
    private static final Pattern DOUBLE_BRACE_VARIABLE =
            Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL);
    private static final Pattern DOLLAR_VARIABLE =
            Pattern.compile("\\$\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern THYMELEAF_ATTRIBUTE =
            Pattern.compile("(?i)\\bth:[a-z][a-z0-9_-]*\\s*=");

    private final ZipSafetyValidator zipSafetyValidator;

    public TraditionalTemplatePackageValidator(ZipSafetyValidator zipSafetyValidator) {
        this.zipSafetyValidator = zipSafetyValidator;
    }

    public TraditionalTemplatePackageManifest validate(
            String originalFilename, byte[] archiveBytes, String requestedEntryPath) {
        if (originalFilename == null
                || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw rejected("INVALID_ARCHIVE", "模板包必须是 ZIP 文件");
        }
        if (archiveBytes == null || archiveBytes.length == 0) {
            throw rejected("INVALID_ARCHIVE", "模板包不能为空");
        }

        ZipValidationResult result = zipSafetyValidator.validate(archiveBytes);
        if (!result.accepted()) {
            var violation = result.violations().getFirst();
            throw rejected(violation.code(), violation.message());
        }

        String entryPath =
                normalizeRequestedPath(
                        requestedEntryPath == null || requestedEntryPath.isBlank()
                                ? "index.html"
                                : requestedEntryPath);
        Map<String, byte[]> contents = readContents(archiveBytes, result.entrySummaries());
        List<TraditionalTemplateFile> files =
                result.entrySummaries().stream()
                        .filter(summary -> !summary.directory())
                        .map(summary -> toFile(summary, contents.get(summary.normalizedPath())))
                        .sorted(Comparator.comparing(TraditionalTemplateFile::path))
                        .toList();
        if (files.stream().noneMatch(file -> file.path().equals(entryPath) && file.html())) {
            throw rejected("ENTRY_NOT_FOUND", "入口文件不存在或不是 HTML: " + entryPath);
        }

        LinkedHashSet<String> variables = new LinkedHashSet<>();
        try {
            TemplateResourceReferenceValidator.validate(
                    contents,
                    files.stream()
                            .filter(TraditionalTemplateFile::html)
                            .map(TraditionalTemplateFile::path)
                            .collect(Collectors.toUnmodifiableSet()));
        } catch (TemplateResourceReferenceValidator.ReferenceViolation ex) {
            throw rejected(ex.code(), ex.getMessage());
        }
        for (TraditionalTemplateFile file : files) {
            if (file.html()) {
                String source = new String(contents.get(file.path()), StandardCharsets.UTF_8);
                extractVariables(source, variables);
            }
        }
        long totalBytes = files.stream().mapToLong(TraditionalTemplateFile::size).sum();
        return new TraditionalTemplatePackageManifest(
                entryPath,
                files,
                variables.stream().sorted().toList(),
                archiveBytes.length,
                totalBytes,
                sha256(archiveBytes),
                renderMode(files, contents));
    }

    private TraditionalTemplateRenderMode renderMode(
            List<TraditionalTemplateFile> files, Map<String, byte[]> contents) {
        boolean serverRendered =
                files.stream()
                        .filter(TraditionalTemplateFile::html)
                        .map(file -> new String(contents.get(file.path()), StandardCharsets.UTF_8))
                        .anyMatch(
                                source ->
                                        THYMELEAF_ATTRIBUTE.matcher(source).find()
                                                || source.contains("[[${")
                                                || source.contains("[(${"));
        return serverRendered
                ? TraditionalTemplateRenderMode.SERVER_RENDERED
                : TraditionalTemplateRenderMode.STATIC_FILES;
    }

    private Map<String, byte[]> readContents(byte[] archiveBytes, List<ZipEntrySummary> summaries) {
        Map<String, String> normalizedByEntryName =
                summaries.stream()
                        .collect(
                                Collectors.toMap(
                                        ZipEntrySummary::entryName,
                                        ZipEntrySummary::normalizedPath,
                                        (left, right) -> left,
                                        LinkedHashMap::new));
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(
                        new ByteArrayInputStream(archiveBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = normalizedByEntryName.get(entry.getName());
                if (path != null && !entry.isDirectory()) {
                    contents.put(path, zip.readAllBytes());
                }
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw rejected("INVALID_ZIP", "ZIP 内容读取失败");
        }
        if (contents.size() != summaries.stream().filter(summary -> !summary.directory()).count()) {
            throw rejected("INVALID_ZIP", "ZIP 文件清单与内容不一致");
        }
        return contents;
    }

    private TraditionalTemplateFile toFile(ZipEntrySummary summary, byte[] bytes) {
        if (bytes == null) {
            throw rejected("INVALID_ZIP", "ZIP 文件内容缺失: " + summary.normalizedPath());
        }
        return new TraditionalTemplateFile(
                summary.normalizedPath(),
                contentType(summary.normalizedPath()),
                bytes.length,
                sha256(bytes),
                summary.html());
    }

    private void extractVariables(String html, Set<String> variables) {
        extractVariables(DOUBLE_BRACE_VARIABLE.matcher(html), variables);
        extractVariables(DOLLAR_VARIABLE.matcher(html), variables);
    }

    private void extractVariables(Matcher matcher, Set<String> variables) {
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            if (!VARIABLE_NAME.matcher(variable).matches()) {
                throw rejected("INVALID_TEMPLATE_VARIABLE", "模板变量不能为空或格式无效");
            }
            variables.add(variable);
        }
    }

    private String normalizeRequestedPath(String path) {
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:[\\\\/].*")) {
            throw rejected("ABSOLUTE_PATH", "入口路径必须相对包根目录");
        }
        String normalized =
                Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/');
        if (normalized.isBlank()
                || normalized.equals(".")
                || normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.contains("/../")) {
            throw rejected("PATH_TRAVERSAL", "入口路径越出包根目录");
        }
        return normalized;
    }

    private static String contentType(String path) {
        String lowercase = path.toLowerCase(Locale.ROOT);
        if (lowercase.endsWith(".css")) return "text/css";
        if (lowercase.endsWith(".js") || lowercase.endsWith(".mjs")) return "text/javascript";
        if (lowercase.endsWith(".html") || lowercase.endsWith(".htm")) return "text/html";
        if (lowercase.endsWith(".svg")) return "image/svg+xml";
        if (lowercase.endsWith(".png")) return "image/png";
        if (lowercase.endsWith(".jpg") || lowercase.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static TraditionalTemplatePackageRejectedException rejected(
            String code, String message) {
        return new TraditionalTemplatePackageRejectedException(code, message);
    }
}

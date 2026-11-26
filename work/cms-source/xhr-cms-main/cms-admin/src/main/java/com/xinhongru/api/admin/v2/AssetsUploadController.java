package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.config.UploadConfig;
import com.xinhongru.security.upload.PublicAssetStorage;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.site.context.SiteContextService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** 站点公开素材上传与列表，保存在各站点 assets/upload 或全局 runtime/assets 下。 */
@Api(tags = "资源上传")
@RestController
@RequestMapping("/api/admin/v2/cms/assets-upload")
@RequiredArgsConstructor
public class AssetsUploadController {
    private final PublicStorageRoots storage;
    private final SiteContextService siteContextService;
    private final PublicAssetStorage publicAssets;
    private final UploadConfig uploadConfig;
    private final ResourceReferenceResolver resolver;

    @ApiOperation("上传资源文件")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminApiResponse<AssetsUploadResponse> upload(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "file") String type,
            @RequestParam(defaultValue = "site") String scope,
            @RequestParam(required = false) String siteCode) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }

        boolean global = globalScope(scope);
        String code = authorizedCode(request, scope, siteCode);
        global = global || code == null;
        String effectiveType = type;
        if ("all".equals(type)) {
            String extension =
                    UploadDelivery.extension(
                            file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
            for (String category : new String[] {"image", "video", "audio", "file"}) {
                if (uploadConfig.extensions(category).contains(extension)) {
                    effectiveType = category;
                    break;
                }
            }
        }
        String subDir = subDir(effectiveType);
        Path targetDir =
                (global ? storage.configuredRoot() : storage.siteRoot(code)).resolve(subDir);
        try {
            String reference = publicAssets.store(file, type, targetDir);
            String url = resolver.resolve(reference, code);
            return AdminApiResponse.success(
                    new AssetsUploadResponse(
                            reference,
                            url,
                            file.getOriginalFilename(),
                            file.getSize(),
                            UploadDelivery.contentType(url)));
        } catch (IOException ex) {
            throw new IllegalStateException("文件保存失败", ex);
        }
    }

    private String authorizedCode(HttpServletRequest request, String scope, String siteCode) {
        boolean global = globalScope(scope);
        if (siteCode != null) {
            if (siteCode.isBlank()) {
                if (global) return null;
                throw new IllegalArgumentException("请指定当前站点");
            }
            return siteContextService.requireAccessibleSiteCode(request, siteCode).getProjectCode();
        }
        if (global) return null;
        var current = siteContextService.requireCurrentSite(request);
        siteContextService.assertCanAccessSite(request, current.getSiteId());
        return current.getSiteCode();
    }

    private static String subDir(String type) {
        return switch (type) {
            case "image" -> "images";
            case "video" -> "videos";
            case "audio" -> "audios";
            case "file", "all" -> "files";
            default -> throw new IllegalArgumentException("未知的上传类型");
        };
    }

    @ApiOperation("列出已上传资源")
    @GetMapping
    public AdminApiResponse<AssetsListResponse> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "file") String type,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize,
            @RequestParam(defaultValue = "") String extensions,
            @RequestParam(defaultValue = "site") String scope,
            @RequestParam(required = false) String siteCode)
            throws IOException {
        String code = authorizedCode(request, scope, siteCode);
        Path site =
                globalScope(scope) || code == null
                        ? storage.configuredRoot()
                        : storage.siteRoot(code);
        Path base = "all".equals(type) ? site : site.resolve(subDir(type));
        Path current = storage.resolveUnder(base, path);
        if (Path.of(path).isAbsolute() || !current.startsWith(base))
            throw new IllegalArgumentException("非法资源目录");
        int size = Math.max(1, Math.min(100, pageSize));
        var allowed =
                Arrays.stream(extensions.split(","))
                        .map(
                                value ->
                                        value.trim()
                                                .toLowerCase(Locale.ROOT)
                                                .replaceFirst("^\\.", ""))
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toSet());
        List<AssetDirectory> directories = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(current)) {
                entries.filter(
                                entry ->
                                        !Files.isSymbolicLink(entry)
                                                && !entry.getFileName().toString().startsWith("."))
                        .filter(Files::isDirectory)
                        .forEach(
                                entry ->
                                        directories.add(
                                                new AssetDirectory(
                                                        entry.getFileName().toString(),
                                                        base.relativize(entry)
                                                                .toString()
                                                                .replace('\\', '/'))));
            }
            try (var entries = Files.walk(current, "all".equals(type) ? 32 : 1)) {
                entries.filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                        .filter(entry -> !entry.getFileName().toString().startsWith("."))
                        .filter(
                                entry ->
                                        allowed.isEmpty()
                                                || allowed.contains(
                                                        UploadDelivery.extension(entry.toString())))
                        .forEach(files::add);
            }
        }
        files.sort(
                Comparator.comparingLong(this::modifiedAt)
                        .reversed()
                        .thenComparing(Path::toString));
        directories.sort(Comparator.comparing(AssetDirectory::name));
        int total = files.size();
        int safePage = Math.max(1, Math.min(page, Math.max(1, (total + size - 1) / size)));
        int from = Math.min(total, (safePage - 1) * size);
        List<AssetsListItem> items = new ArrayList<>();
        for (Path file : files.subList(from, Math.min(total, from + size))) {
            items.add(
                    new AssetsListItem(
                            file.getFileName().toString(),
                            storage.reference(file),
                            resolver.resolve(storage.reference(file), code),
                            Files.size(file),
                            UploadDelivery.contentType(file),
                            modifiedAt(file)));
        }
        return AdminApiResponse.success(
                new AssetsListResponse(
                        path, parentRelative(path), directories, items, total, safePage, size));
    }

    private boolean globalScope(String scope) {
        if (!"global".equals(scope) && !"site".equals(scope))
            throw new IllegalArgumentException("未知的资源作用域");
        return "global".equals(scope);
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String parentRelative(String relative) {
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        int idx = relative.lastIndexOf('/');
        if (idx < 0) {
            return "";
        }
        return relative.substring(0, idx);
    }

    public record AssetsUploadResponse(
            String reference, String url, String originalName, long fileSize, String contentType) {}

    public record AssetsListResponse(
            String currentPath,
            String parentPath,
            List<AssetDirectory> directories,
            List<AssetsListItem> items,
            int total,
            int page,
            int pageSize) {}

    public record AssetDirectory(String name, String path) {}

    public record AssetsListItem(
            String name,
            String reference,
            String url,
            long fileSize,
            String contentType,
            long modifiedAt) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalidUpload(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> accessDenied(
            com.xinhongru.site.context.SiteAccessDeniedException error) {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, error.getMessage(), null));
    }
}

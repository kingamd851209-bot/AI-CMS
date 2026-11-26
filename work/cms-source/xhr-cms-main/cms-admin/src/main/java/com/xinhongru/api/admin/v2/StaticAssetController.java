package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.service.cmsPlatform.StaticAssetService;
import com.xinhongru.service.cmsPlatform.StaticAssetService.AssetScope;
import com.xinhongru.service.cmsPlatform.StaticAssetService.StaticAssetFileEntry;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 静态资源库（跨站点）：全局资源 + 各站点 upload/模板/UIPackage 三类资源。 */
@RestController
@RequestMapping("/api/admin/v2/static-assets")
public class StaticAssetController {
    private final StaticAssetService service;
    private final SiteContextService siteContext;

    public StaticAssetController(StaticAssetService service, SiteContextService siteContext) {
        this.service = service;
        this.siteContext = siteContext;
    }

    /** 可浏览的站点列表（用于左侧导航） */
    @GetMapping("/nav")
    public AdminApiResponse<List<SiteNavItem>> nav(HttpServletRequest request) {
        List<SiteNavItem> sites =
                siteContext.listAvailableSites(request).stream()
                        .filter(
                                site ->
                                        site.getProjectCode() != null
                                                && !site.getProjectCode().isBlank())
                        .map(
                                site ->
                                        new SiteNavItem(
                                                site.getId(),
                                                site.getProjectName(),
                                                site.getProjectCode()))
                        .toList();
        return AdminApiResponse.success(sites);
    }

    @GetMapping("/list")
    public AdminApiResponse<List<StaticAssetFileEntry>> list(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false, defaultValue = "") String path) {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        return AdminApiResponse.success(service.list(assetScope, siteId, path));
    }

    @PostMapping("/mkdir")
    public AdminApiResponse<Void> mkdir(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false, defaultValue = "") String path,
            @RequestParam String name) {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        service.mkdir(assetScope, siteId, path, name);
        return AdminApiResponse.success();
    }

    @PostMapping("/upload")
    public AdminApiResponse<String> upload(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false, defaultValue = "") String path)
            throws IOException {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        return AdminApiResponse.success(service.upload(assetScope, siteId, file, path));
    }

    @DeleteMapping("/delete")
    public AdminApiResponse<Void> delete(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam String path) {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        service.delete(assetScope, siteId, path);
        return AdminApiResponse.success();
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam String path)
            throws IOException {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        Path file = service.resolve(assetScope, siteId, path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在");
        }
        Resource resource = new UrlResource(file.toUri());
        String fileName = file.getFileName().toString();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", UploadDelivery.CSP)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(resource);
    }

    /** 仅查询授权本地路径，不通过网络探测外部地址。 */
    @GetMapping("/metadata")
    public ResponseEntity<AdminApiResponse<FileMetadata>> metadata(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) Long siteId,
            @RequestParam String path)
            throws IOException {
        AssetScope assetScope = parseScope(scope);
        authorize(request, assetScope, siteId);
        Path file = service.resolve(assetScope, siteId, path);
        boolean exists = Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        AdminApiResponse.success(
                                new FileMetadata(
                                        exists,
                                        file.getFileName().toString(),
                                        exists ? Files.size(file) : 0)));
    }

    public record FileMetadata(boolean exists, String name, long size) {}

    private static AssetScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return AssetScope.GLOBAL;
        }
        try {
            return AssetScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的资源作用域: " + scope);
        }
    }

    private void authorize(HttpServletRequest request, AssetScope scope, Long siteId) {
        if (scope == AssetScope.GLOBAL) {
            return;
        }
        if (siteId == null) {
            throw new IllegalArgumentException("请指定站点");
        }
        siteContext.assertCanAccessSite(request, siteId);
    }

    public record SiteNavItem(Long siteId, String name, String code) {}

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

package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.resource.AssetReferenceCheck;
import com.xinhongru.security.upload.PublicAssetReferences;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 已登录管理端共享资源治理；目录作用域只用于定位，不限制用户角色或站点。 */
@RestController
@RequestMapping("/api/admin/v2/cms/public-resources")
public class PublicResourceV2ApiController {
    private final PublicStorageRoots roots;
    private final SiteContextService sites;
    private final PublicAssetReferences references;
    private final ResourceReferenceResolver resolver;

    public PublicResourceV2ApiController(
            PublicStorageRoots roots,
            SiteContextService sites,
            PublicAssetReferences references,
            ResourceReferenceResolver resolver) {
        this.roots = roots;
        this.sites = sites;
        this.references = references;
        this.resolver = resolver;
    }

    private String code(HttpServletRequest request, String code) {
        return code != null ? code : sites.current(request).getSiteCode();
    }

    private Path file(String reference, String code) {
        Path file = roots.resolveReference(reference, code);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("资源文件不存在");
        return file;
    }

    @GetMapping("/references")
    public AdminApiResponse<AssetReferenceCheck> references(
            HttpServletRequest request,
            @RequestParam String path,
            @RequestParam(required = false) String siteCode) {
        var usages = references.find(file(path, code(request, siteCode)));
        return AdminApiResponse.success(new AssetReferenceCheck(path, !usages.isEmpty(), usages));
    }

    @DeleteMapping
    public AdminApiResponse<Void> delete(
            HttpServletRequest request,
            @RequestParam String path,
            @RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "false") boolean force)
            throws IOException {
        Path file = file(path, code(request, siteCode));
        if (!force && !references.find(file).isEmpty()) throw new IllegalStateException("资源正在被引用");
        Files.delete(file);
        return AdminApiResponse.success();
    }

    @PatchMapping
    public AdminApiResponse<Renamed> rename(HttpServletRequest request, @RequestBody Rename input)
            throws IOException {
        String code = code(request, input.siteCode());
        Path source = file(input.path(), code);
        String name = input.newName();
        if (name == null
                || !name.matches("[^/\\\\:%\\p{Cntrl}]{1,255}")
                || name.startsWith(".")
                || name.endsWith(".")
                || !name.equals(name.trim())
                || name.chars().anyMatch(c -> "?#<>\"".indexOf(c) >= 0)
                || !UploadDelivery.extension(source.toString())
                        .equals(UploadDelivery.extension(name)))
            throw new IllegalArgumentException("新文件名不安全或扩展名发生变化");
        if (!references.find(source).isEmpty()) throw new IllegalStateException("资源正在被引用，不能重命名");
        Path target = source.resolveSibling(name);
        Files.move(source, target);
        String ref = roots.reference(target);
        return AdminApiResponse.success(new Renamed(ref, resolver.resolve(ref, code), name));
    }

    public record Rename(String path, String newName, String siteCode) {}

    public record Renamed(String reference, String url, String name) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, failure.getMessage(), null));
    }

    @ExceptionHandler({IllegalStateException.class, FileAlreadyExistsException.class})
    public ResponseEntity<AdminApiResponse<Void>> conflict(Exception failure) {
        return ResponseEntity.status(409)
                .body(
                        new AdminApiResponse<>(
                                409,
                                failure instanceof FileAlreadyExistsException
                                        ? "同名资源已存在"
                                        : failure.getMessage(),
                                null));
    }
}

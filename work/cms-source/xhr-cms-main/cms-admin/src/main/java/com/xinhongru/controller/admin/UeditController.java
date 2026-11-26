package com.xinhongru.controller.admin;

import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.config.UploadConfig;
import com.xinhongru.security.upload.PublicAssetStorage;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** UEditor 协议适配；素材存储、格式和站点权限与普通上传一致。 */
@RestController
public class UeditController {
    private final PublicAssetStorage storage;
    private final PublicStorageRoots roots;
    private final UploadConfig formats;
    private final SiteContextService sites;
    private final ResourceReferenceResolver resolver;

    public UeditController(
            PublicAssetStorage storage,
            PublicStorageRoots roots,
            UploadConfig formats,
            SiteContextService sites,
            ResourceReferenceResolver resolver) {
        this.resolver = resolver;
        this.storage = storage;
        this.roots = roots;
        this.formats = formats;
        this.sites = sites;
    }

    @GetMapping("/api/admin/config")
    public Map<String, Object> config(
            HttpServletRequest request, @RequestParam(defaultValue = "config") String action)
            throws IOException {
        if ("config".equals(action)) {
            Map<String, Object> config = new LinkedHashMap<>();
            for (String type : new String[] {"image", "video", "file"}) {
                config.put(type + "ActionName", "upload" + type);
                config.put(type + "FieldName", "upfile");
                config.put(type + "MaxSize", 20 * 1024 * 1024);
                config.put(
                        type + "AllowFiles",
                        formats.extensions(type).stream().map(ext -> "." + ext).toList());
                config.put(type + "UrlPrefix", "");
            }
            config.put("imageCompressEnable", true);
            config.put("imageCompressBorder", 7680);
            // 编辑器素材选择统一使用管理端资源选择器，停用旧磁盘枚举和远程抓图协议。
            config.put("imageManagerActionName", "");
            config.put("fileManagerActionName", "");
            config.put("catcherActionName", "");
            return config;
        }
        throw new IllegalArgumentException("上传资源必须使用POST请求");
    }

    @PostMapping("/api/admin/config")
    public Map<String, Object> upload(
            HttpServletRequest request,
            @RequestParam String action,
            @RequestParam(value = "upfile", required = false) MultipartFile file)
            throws IOException {
        String type =
                switch (action) {
                    case "uploadimage" -> "image";
                    case "uploadvideo" -> "video";
                    case "uploadfile" -> "file";
                    default -> throw new IllegalArgumentException("不支持的编辑器资源操作");
                };
        String code = sites.current(request).getSiteCode();
        String dir =
                switch (type) {
                    case "image" -> "images";
                    case "video" -> "videos";
                    default -> "files";
                };
        if (resolver.context(code).upload() == null)
            throw new IllegalStateException("请配置统一资源域名或启动本地站点/UI服务后上传");
        String reference = storage.store(file, type, roots.siteRoot(code).resolve(dir));
        String url = resolver.resolve(reference, code);
        if (url == null) throw new IllegalStateException("请配置统一资源域名或启动本地站点/UI服务后上传");
        return Map.of(
                "state",
                "SUCCESS",
                "url",
                url,
                "reference",
                reference,
                "original",
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                "title",
                file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename(),
                "type",
                "." + UploadDelivery.extension(url),
                "size",
                file.getSize());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        IllegalStateException.class,
        IOException.class
    })
    public Map<String, Object> uploadError(Exception failure) {
        return Map.of("state", failure instanceof IOException ? "上传文件读写失败" : failure.getMessage());
    }
}

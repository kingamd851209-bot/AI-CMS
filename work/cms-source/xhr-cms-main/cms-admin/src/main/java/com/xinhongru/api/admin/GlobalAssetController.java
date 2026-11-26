package com.xinhongru.api.admin;

import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.security.upload.SafeSvg;
import com.xinhongru.security.upload.UploadDelivery;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 全局素材本地访问入口；不提供 /media 或旧 upload 路由。 */
@RestController
public class GlobalAssetController {
    private final PublicStorageRoots roots;

    public GlobalAssetController(PublicStorageRoots roots) {
        this.roots = roots;
    }

    @GetMapping("/assets/global/**")
    public ResponseEntity<Resource> resource(HttpServletRequest request) throws IOException {
        String path =
                request.getRequestURI()
                        .substring(request.getContextPath().length() + "/assets/global/".length());
        Path file = roots.resolve(UriUtils.decode(path, StandardCharsets.UTF_8));
        if (!UploadDelivery.within(roots.configuredRoot(), file))
            return ResponseEntity.notFound().build();
        Resource body;
        if ("svg".equals(UploadDelivery.extension(file.toString()))) {
            try (var input = Files.newInputStream(file)) {
                body = new ByteArrayResource(SafeSvg.read(input));
            }
        } else body = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(UploadDelivery.contentType(file)))
                .contentLength(body.contentLength())
                .header(
                        "Content-Disposition",
                        UploadDelivery.attachment(file) ? "attachment" : "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", UploadDelivery.CSP)
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }
}

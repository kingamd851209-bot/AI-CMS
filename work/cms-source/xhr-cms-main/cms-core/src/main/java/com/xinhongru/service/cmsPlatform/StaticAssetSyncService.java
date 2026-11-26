package com.xinhongru.service.cmsPlatform;

import com.xinhongru.security.upload.PublicAssetStorage;
import com.xinhongru.service.cmsPlatform.StaticAssetService.AssetScope;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 静态资源同步：将源作用域（全局或某站点）下的目录/文件复制到当前站点指定作用域的目录。 */
@Service
public class StaticAssetSyncService {
    private final StaticAssetService staticAssetService;
    private final PublicAssetStorage publicAssets;

    public StaticAssetSyncService(
            StaticAssetService staticAssetService, PublicAssetStorage publicAssets) {
        this.staticAssetService = staticAssetService;
        this.publicAssets = publicAssets;
    }

    public record FailedItem(String path, String reason) {}

    public record SyncResult(int copiedCount, List<FailedItem> failed) {}

    public SyncResult copy(
            AssetScope sourceScope,
            Long sourceSiteId,
            List<String> sourcePaths,
            AssetScope targetScope,
            Long targetSiteId,
            String targetPath) {
        Path targetDir =
                staticAssetService.resolve(
                        targetScope, targetSiteId, targetPath == null ? "" : targetPath);
        try {
            Files.createDirectories(targetDir);
        } catch (IOException ex) {
            throw new IllegalArgumentException("目标目录创建失败", ex);
        }
        int copied = 0;
        List<FailedItem> failed = new ArrayList<>();
        for (String rel : sourcePaths) {
            if (rel == null || rel.isBlank()) {
                failed.add(new FailedItem(rel, "路径为空"));
                continue;
            }
            try {
                Path source = staticAssetService.resolve(sourceScope, sourceSiteId, rel);
                if (!Files.exists(source)) {
                    failed.add(new FailedItem(rel, "源不存在"));
                    continue;
                }
                copyItem(
                        source,
                        targetDir,
                        targetScope == AssetScope.GLOBAL || targetScope == AssetScope.SITE_ASSETS);
                copied++;
            } catch (Exception ex) {
                failed.add(new FailedItem(rel, ex.getMessage() == null ? "复制失败" : ex.getMessage()));
            }
        }
        return new SyncResult(copied, failed);
    }

    private void copyItem(Path source, Path targetDir, boolean publicTarget) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IllegalArgumentException("禁止同步符号链接");
        String name = source.getFileName() == null ? "unnamed" : source.getFileName().toString();
        Path target = uniqueTarget(targetDir, name);
        if (Files.isDirectory(source)) {
            if (isSelfOrDescendant(source, target)) {
                throw new IllegalArgumentException("不能将目录复制到自身或其子目录");
            }
            copyDirectory(source, target, publicTarget);
        } else {
            copyFile(source, targetDir, publicTarget);
        }
    }

    private boolean isSelfOrDescendant(Path dir, Path target) {
        Path d = dir.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        return t.startsWith(d);
    }

    private void copyDirectory(Path source, Path target, boolean publicTarget) throws IOException {
        Files.createDirectories(target);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path child : stream) {
                Path childTarget = target.resolve(child.getFileName().toString());
                if (Files.isSymbolicLink(child)) throw new IllegalArgumentException("禁止同步符号链接");
                if (Files.isDirectory(child)) {
                    copyDirectory(child, childTarget, publicTarget);
                } else {
                    copyFile(child, target, publicTarget);
                }
            }
        }
    }

    private void copyFile(Path source, Path targetDir, boolean publicTarget) throws IOException {
        if (publicTarget) {
            try (var input = Files.newInputStream(source)) {
                byte[] bytes = input.readNBytes(20 * 1024 * 1024 + 1);
                if (bytes.length > 20 * 1024 * 1024)
                    throw new IllegalArgumentException("同步素材不能超过20MB");
                publicAssets.store(bytes, source.getFileName().toString(), "all", targetDir);
            }
        } else Files.copy(source, uniqueTarget(targetDir, source.getFileName().toString()));
    }

    /** 目标目录内重名时追加 _1、_2 后缀，避免覆盖。 */
    private Path uniqueTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "_" + i + ext);
            i++;
        }
        return candidate;
    }

    public static AssetScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("未知的资源作用域");
        }
        try {
            return AssetScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的资源作用域: " + scope);
        }
    }
}

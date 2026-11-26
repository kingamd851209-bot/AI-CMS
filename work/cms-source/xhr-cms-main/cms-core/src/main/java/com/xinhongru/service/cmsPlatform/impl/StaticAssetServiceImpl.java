package com.xinhongru.service.cmsPlatform.impl;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.properties.StaticAssetProperties;
import com.xinhongru.security.upload.PublicAssetReferences;
import com.xinhongru.security.upload.PublicAssetStorage;
import com.xinhongru.service.cmsPlatform.StaticAssetService;
import com.xinhongru.site.entity.SiteProjectEntity;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

@Service
public class StaticAssetServiceImpl implements StaticAssetService {
    /** 分类目录 -> 支持的扩展名 */
    private static final Map<String, String[]> TYPE_DIRS =
            Map.of(
                    "images",
                            new String[] {"jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico"},
                    "videos", new String[] {"mp4", "webm", "mov", "avi"},
                    "audios", new String[] {"mp3", "wav", "ogg", "aac"},
                    "pdf", new String[] {"pdf"},
                    "ppt", new String[] {"ppt", "pptx"},
                    "excel", new String[] {"xls", "xlsx", "csv"},
                    "word", new String[] {"doc", "docx"});

    private final PublicAssetStorage publicAssets;
    private final PublicAssetReferences references;
    private final PublicStorageRoots publicRoots;
    private final StaticAssetProperties properties;
    private final UiPackageStorageService uiPackageStorageService;
    private final AiSiteProjectService projectService;
    private final TemplateStorageService templateStorageService;

    public StaticAssetServiceImpl(
            StaticAssetProperties properties,
            UiPackageStorageService uiPackageStorageService,
            AiSiteProjectService projectService,
            TemplateStorageService templateStorageService,
            PublicAssetStorage publicAssets,
            PublicStorageRoots publicRoots,
            PublicAssetReferences references) {
        this.references = references;
        this.publicAssets = publicAssets;
        this.publicRoots = publicRoots;
        this.properties = properties;
        this.uiPackageStorageService = uiPackageStorageService;
        this.projectService = projectService;
        this.templateStorageService = templateStorageService;
    }

    private Path rootPath() {
        return Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    private AssetScope scopeOf(AssetScope scope) {
        return scope == null ? AssetScope.GLOBAL : scope;
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("请指定站点");
        }
        return projectService.getSite(siteId);
    }

    private Path scopeRoot(AssetScope scope, Long siteId) {
        return switch (scopeOf(scope)) {
            case GLOBAL -> rootPath();
            case SITE_ASSETS -> publicRoots.siteRoot(requireSite(siteId).getProjectCode());
            case SITE_TEMPLATE ->
                    templateStorageService.resolveTemplateDir(requireSite(siteId).getProjectCode());
            case SITE_UIPACKAGE ->
                    uiPackageStorageService
                            .getActivePath(requireSite(siteId).getProjectCode())
                            .orElseThrow(() -> new IllegalArgumentException("该站点 UIPackage 未激活"));
        };
    }

    private Path resolveWithin(Path root, String rel) {
        String clean = rel == null ? "" : rel.replace('\\', '/');
        Path p = root.resolve(clean).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法路径: " + rel);
        }
        Path current = root;
        for (Path segment : root.relativize(p)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("资源路径不能包含符号链接");
        }
        return p;
    }

    @Override
    public Path resolve(AssetScope scope, Long siteId, String rel) {
        Path resolved = resolveWithin(scopeRoot(scope, siteId), rel);
        if (scopeOf(scope) == AssetScope.GLOBAL || scopeOf(scope) == AssetScope.SITE_ASSETS) {
            return publicRoots.resolveUnder(
                    scopeRoot(scope, siteId),
                    scopeRoot(scope, siteId).relativize(resolved).toString().replace('\\', '/'));
        }
        return resolved;
    }

    private String relOf(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        return rel.isEmpty() ? "/" : rel;
    }

    private String classifyDir(String fileName) {
        String ext = extOf(fileName);
        for (Entry<String, String[]> e : TYPE_DIRS.entrySet()) {
            for (String x : e.getValue()) {
                if (x.equals(ext)) {
                    return e.getKey();
                }
            }
        }
        return "files";
    }

    private String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    @Override
    public List<StaticAssetFileEntry> list(AssetScope scope, Long siteId, String path) {
        AssetScope effectiveScope = scopeOf(scope);
        Path root = scopeRoot(effectiveScope, siteId);
        Path dir = resolve(effectiveScope, siteId, path);
        if (effectiveScope == AssetScope.GLOBAL) {
            ensureRootDirs();
        } else if (effectiveScope != AssetScope.SITE_UIPACKAGE) {
            try {
                Files.createDirectories(root);
            } catch (IOException ignored) {
                // 目录已存在或权限不足时忽略
            }
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<StaticAssetFileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isSymbolicLink(p) || p.getFileName().toString().startsWith(".")) continue;
                boolean isDir = Files.isDirectory(p);
                String name = p.getFileName().toString();
                long size = isDir ? 0 : Files.size(p);
                String rel = relOf(root, p);
                entries.add(
                        new StaticAssetFileEntry(name, isDir, size, isDir ? "" : extOf(name), rel));
            }
        } catch (IOException ex) {
            return List.of();
        }
        entries.sort(
                Comparator.comparing(StaticAssetFileEntry::dir)
                        .reversed()
                        .thenComparing(e -> e.name().toLowerCase()));
        return entries;
    }

    private void ensureRootDirs() {
        Path root = rootPath();
        try {
            Files.createDirectories(root);
            for (String dir : TYPE_DIRS.keySet()) {
                Files.createDirectories(root.resolve(dir));
            }
        } catch (IOException ignored) {
            // 目录已存在或权限不足时忽略
        }
    }

    @Override
    public void mkdir(AssetScope scope, Long siteId, String path, String name) {
        if (name == null
                || name.isBlank()
                || name.contains("/")
                || name.contains("\\")
                || name.equals(".")
                || name.equals("..")) {
            throw new IllegalArgumentException("文件夹名不合法");
        }
        Path root = scopeRoot(scope, siteId);
        Path target =
                resolve(scope, siteId, (path == null || path.isBlank() ? "" : path + "/") + name);
        try {
            Files.createDirectories(target);
        } catch (IOException ex) {
            throw new IllegalArgumentException("创建文件夹失败", ex);
        }
    }

    @Override
    public String upload(AssetScope scope, Long siteId, MultipartFile file, String path)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        AssetScope effectiveScope = scopeOf(scope);
        Path root = scopeRoot(effectiveScope, siteId);
        if (effectiveScope != AssetScope.SITE_TEMPLATE
                && effectiveScope != AssetScope.SITE_UIPACKAGE) {
            Path dir =
                    effectiveScope == AssetScope.GLOBAL && (path == null || path.isBlank())
                            ? root.resolve(classifyDir(file.getOriginalFilename()))
                            : resolveWithin(root, path);
            String reference = publicAssets.store(file, "all", dir);
            return relOf(
                    root,
                    publicRoots.resolveReference(
                            reference,
                            effectiveScope == AssetScope.GLOBAL
                                    ? null
                                    : requireSite(siteId).getProjectCode()));
        }
        Path dir = resolveWithin(root, path);
        Files.createDirectories(dir);
        return uploadToDir(file, dir, root);
    }

    /** 将文件写入目标目录（平台无关防重：时间戳 + 计数器），返回相对作用域根目录的路径 */
    private String uploadToDir(MultipartFile file, Path dir, Path root) throws IOException {
        String original = safeFileName(file.getOriginalFilename());
        String baseName;
        String ext = extOf(original);
        if (ext.isEmpty()) {
            baseName = original;
        } else {
            baseName = original.substring(0, original.length() - ext.length() - 1);
        }
        Path target;
        String suffix = "";
        int i = 0;
        do {
            suffix = i == 0 ? "" : "_" + i;
            String fileName =
                    baseName
                            + (System.currentTimeMillis() % 100000)
                            + suffix
                            + (ext.isEmpty() ? "" : "." + ext);
            target = dir.resolve(fileName);
            i++;
        } while (Files.exists(target));
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return relOf(root, target);
    }

    private String safeFileName(String original) {
        if (original == null) {
            return "file";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isEmpty() ? "file" : name;
    }

    @Override
    public void delete(AssetScope scope, Long siteId, String path) {
        AssetScope effectiveScope = scopeOf(scope);
        Path root = scopeRoot(effectiveScope, siteId);
        Path target = resolve(effectiveScope, siteId, path);
        if (target.equals(root) || (path == null ? "" : path).isBlank()) {
            throw new IllegalArgumentException("不允许删除根目录");
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        if (Files.isDirectory(target)) {
            if (effectiveScope == AssetScope.GLOBAL
                    && TYPE_DIRS.containsKey(target.getFileName().toString())) {
                throw new IllegalArgumentException("不允许删除分类文件夹");
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                if (stream.iterator().hasNext()) {
                    throw new IllegalArgumentException("文件夹非空，无法删除");
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("删除文件夹失败", ex);
            }
        }
        if (Files.isRegularFile(target)
                && (effectiveScope == AssetScope.GLOBAL || effectiveScope == AssetScope.SITE_ASSETS)
                && !references.find(target).isEmpty())
            throw new IllegalStateException("资源正在被引用，不能删除");
        try {
            Files.delete(target);
        } catch (IOException ex) {
            throw new IllegalArgumentException("删除失败", ex);
        }
    }
}

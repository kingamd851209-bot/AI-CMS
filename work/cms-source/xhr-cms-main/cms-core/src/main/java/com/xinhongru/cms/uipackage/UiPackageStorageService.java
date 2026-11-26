package com.xinhongru.cms.uipackage;

import com.xinhongru.config.PublicStorageRoots;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class UiPackageStorageService {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Pattern DELIVERY_ASSET = Pattern.compile(
            ".+\\.(css|js|mjs|png|jpe?g|gif|webp|svg|ico|avif|bmp|woff2?|ttf|otf|eot|txt)",
            Pattern.CASE_INSENSITIVE);
    private static final long MAX_UNCOMPRESSED_SIZE = 1024 * 1024 * 1024; // 1GB
    private static final int MAX_ENTRY_COUNT = 10000;
    private PublicStorageRoots publicStorageRoots;

    @Autowired
    public void setPublicStorageRoots(PublicStorageRoots roots) {
        this.publicStorageRoots = roots;
    }

    public Path publicSiteAssetsRoot(String code) {
        return publicStorageRoots.siteRoot(code);
    }

    public Path publicAssetsRoot() {
        return publicStorageRoots.configuredRoot();
    }

    /** 正式交付可覆盖指定包版本的静态资源，原包预览及取证仍读取原版本。 */
    public Optional<Path> deliveryAsset(String code, Path active, String path) throws IOException {
        requireSafeName(code, "站点编码");
        if (!DELIVERY_ASSET.matcher(path).matches()
                || path.contains("\\") || path.contains(":") || path.indexOf(0) >= 0
                || java.util.Arrays.stream(path.split("/", -1))
                        .anyMatch(segment -> segment.isEmpty() || segment.startsWith("."))) {
            return Optional.empty();
        }
        Path site = root.toRealPath().resolve(code);
        Path version = active.toRealPath();
        if (!site.equals(version.getParent()) || !SAFE_NAME.matcher(version.getFileName().toString()).matches()) {
            return Optional.empty();
        }
        Path file = site.resolve(".delivery-assets").resolve(version.getFileName()).resolve(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath())) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    private final Path root;
    private final StaticPageSource.ContentValidationCache safeStaticContent =
            new StaticPageSource.ContentValidationCache();

    @Autowired
    public UiPackageStorageService(
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}") String root) {
        this(Path.of(root));
    }

    public UiPackageStorageService(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 根目录创建失败", ex);
        }
    }

    public Path deploy(String projectCode, String version, byte[] archiveBytes) {
        requireSafeName(projectCode, "站点编码");
        requireSafeName(version, "版本标识");
        if (archiveBytes == null || archiveBytes.length == 0) {
            throw new IllegalArgumentException("ZIP 文件不能为空");
        }

        Path siteRoot = root.resolve(projectCode);
        Path targetDir = siteRoot.resolve(version);
        try {
            Files.createDirectories(targetDir);
            extractZip(archiveBytes, targetDir);
            return targetDir;
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 部署失败", ex);
        }
    }

    public void activate(String projectCode, String version) {
        requireSafeName(projectCode, "站点编码");
        requireSafeName(version, "版本标识");
        Path siteRoot = root.resolve(projectCode);
        Path targetDir = siteRoot.resolve(version);
        if (!Files.isDirectory(targetDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("UIPackage 版本不存在");
        }
        Path activeLink = siteRoot.resolve("active");
        try {
            Files.createDirectories(siteRoot);
            if (Files.exists(activeLink, LinkOption.NOFOLLOW_LINKS)) {
                // Only delete the link/junction itself, not the target directory
                Files.delete(activeLink);
            }
            createSymbolicLink(activeLink, targetDir);
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 激活失败", ex);
        }
    }

    public Optional<Path> getActivePath(String projectCode) {
        requireSafeName(projectCode, "站点编码");
        Path siteRoot = root.resolve(projectCode);
        Path activeLink = siteRoot.resolve("active");

        // Check if active path exists (could be symlink or junction on Windows)
        if (!Files.exists(activeLink, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }

        // If it's a symbolic link, resolve it
        if (Files.isSymbolicLink(activeLink)) {
            try {
                Path target = Files.readSymbolicLink(activeLink);
                if (!target.isAbsolute()) {
                    target = siteRoot.resolve(target);
                }
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.of(target);
                }
            } catch (IOException ignored) {
            }
            return Optional.empty();
        }

        // On Windows, could be a junction point - just return the path if it's a directory
        if (Files.isDirectory(activeLink, LinkOption.NOFOLLOW_LINKS)) {
            // Junction points appear as directories, need to check if it's a reparse point
            try {
                Object key = Files.getAttribute(activeLink, "dos:reparsePoint");
                if (key != null) {
                    // It's a junction point, return the path
                    return Optional.of(activeLink);
                }
            } catch (Exception ignored) {
                // Not a reparse point or attribute not available
            }

            // Could be a regular directory or a junction we can't detect
            // Return the path as-is if it's a directory
            return Optional.of(activeLink);
        }

        return Optional.empty();
    }

    public void delete(String projectCode) {
        requireSafeName(projectCode, "站点编码");
        Path siteRoot = root.resolve(projectCode);
        try {
            deleteRecursively(siteRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 删除失败", ex);
        }
    }

    /** 删除指定版本目录（不影响其他版本与 active 链接）。 */
    public void deleteVersion(String projectCode, String version) {
        requireSafeName(projectCode, "站点编码");
        requireSafeName(version, "版本标识");
        Path versionDir = root.resolve(projectCode).resolve(version);
        try {
            deleteRecursively(versionDir);
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 版本删除失败", ex);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * 把源站点编码目录下的全部版本文件复制到目标站点编码目录（同名文件覆盖，其余保留）。 跳过站点根下的 active 链接——它是源站点私有的指向，复制过来会指错目录，
     * 由调用方在复制完成后按目标站点自己的版本重建。
     *
     * @param cancelled 取消标记：每复制一个条目前检查，为 true 则中断并抛异常
     */
    public Path copySiteDir(
            String sourceProjectCode, String targetProjectCode, BooleanSupplier cancelled) {
        requireSafeName(sourceProjectCode, "源站点编码");
        requireSafeName(targetProjectCode, "目标站点编码");
        if (sourceProjectCode.equals(targetProjectCode)) {
            throw new IllegalArgumentException("源站点与目标站点编码相同");
        }
        Path sourceRoot = root.resolve(sourceProjectCode);
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("源站点 UIPackage 目录不存在: " + sourceProjectCode);
        }
        Path targetRoot = root.resolve(targetProjectCode);
        try {
            Files.createDirectories(targetRoot);
            try (var children = Files.list(sourceRoot)) {
                for (Path child : children.toList()) {
                    if ("active".equals(child.getFileName().toString())) {
                        continue;
                    }
                    checkCancelled(cancelled);
                    copyEntry(child, targetRoot.resolve(child.getFileName().toString()), cancelled);
                }
            }
            return targetRoot;
        } catch (IOException ex) {
            throw new IllegalStateException("UIPackage 目录复制失败: " + ex.getMessage(), ex);
        }
    }

    public Path copySiteDir(String sourceProjectCode, String targetProjectCode) {
        return copySiteDir(sourceProjectCode, targetProjectCode, null);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new IOException("已停止");
        }
    }

    private static void copyEntry(Path source, Path target, BooleanSupplier cancelled)
            throws IOException {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(source)) {
            Path linkTarget = Files.readSymbolicLink(source);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(target);
            }
            Files.createSymbolicLink(target, linkTarget);
        } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target);
            try (var children = Files.list(source)) {
                for (Path child : children.toList()) {
                    copyEntry(child, target.resolve(child.getFileName().toString()), cancelled);
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<FileInfo> listFiles(String projectCode) {
        return listFiles(projectCode, null);
    }

    public List<FileInfo> listFiles(String projectCode, String dbVersion) {
        requireSafeName(projectCode, "站点编码");
        Path activePath = resolveReadablePath(projectCode, dbVersion);
        if (activePath == null) {
            return List.of();
        }
        List<FileInfo> files = new ArrayList<>();
        try (var stream = Files.walk(activePath)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isHtmlLike(p) || isAsset(p))
                    .forEach(
                            p -> {
                                String relativePath =
                                        activePath.relativize(p).toString().replace('\\', '/');
                                String displayPath = relativePath;
                                files.add(
                                        new FileInfo(
                                                relativePath,
                                                displayPath,
                                                p.getFileName().toString()));
                            });
        } catch (IOException e) {
            throw new IllegalStateException("获取文件列表失败: " + e.getMessage(), e);
        }
        // 排序：HTML 文件在前，按路径排序
        files.sort(
                (a, b) -> {
                    boolean aHtml =
                            a.relativePath().endsWith(".html")
                                    || a.relativePath().endsWith(".aspx");
                    boolean bHtml =
                            b.relativePath().endsWith(".html")
                                    || b.relativePath().endsWith(".aspx");
                    if (aHtml && !bHtml) return -1;
                    if (!aHtml && bHtml) return 1;
                    return a.relativePath().compareTo(b.relativePath());
                });
        return files;
    }

    public String getFileContent(String projectCode, String filePath) {
        return getFileContent(projectCode, null, filePath);
    }

    public String getFileContent(String projectCode, String dbVersion, String filePath) {
        requireSafeName(projectCode, "站点编码");
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        // 防止路径遍历攻击
        if (filePath.contains("..") || filePath.startsWith("/") || filePath.contains("\0")) {
            throw new IllegalArgumentException("文件路径格式无效");
        }
        Path activePath = resolveReadablePath(projectCode, dbVersion);
        if (activePath == null) {
            throw new IllegalArgumentException("UIPackage 未部署");
        }
        Path targetFile = activePath.resolve(filePath).normalize();
        // 确保目标文件在 active 目录内
        if (!targetFile.startsWith(activePath)) {
            throw new IllegalArgumentException("文件路径越出允许范围");
        }
        // 跟随链接判断：activePath 已解析为真实目录，其下的内部符号链接也应可读
        if (!Files.isRegularFile(targetFile)) {
            throw new IllegalArgumentException("文件不存在");
        }
        try {
            return Files.readString(targetFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /** 内容提取仅列出当前站点中的 HTML/HTM/ASPX/ASP，不跟随目录链接；清单最多 500 页。 */
    public List<FileInfo> listSeedPages(String projectCode, String dbVersion) {
        requireSafeName(projectCode, "站点编码");
        Path active = resolveReadablePath(projectCode, dbVersion);
        if (active == null) return List.of();
        try {
            Path siteRoot = root.resolve(projectCode).toRealPath();
            Path base = active.toRealPath();
            if (!base.startsWith(siteRoot)) throw new IllegalArgumentException("UI 包越出站点范围");
            try (var files = Files.walk(base, 16)) {
                return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(
                                p -> {
                                    String name =
                                            p.getFileName()
                                                    .toString()
                                                    .toLowerCase(java.util.Locale.ROOT);
                                    return StaticPageSource.supports(name);
                                })
                        .sorted()
                        .limit(500)
                        .map(
                                p -> {
                                    String relative =
                                            base.relativize(p).toString().replace('\\', '/');
                                    return new FileInfo(
                                            relative, relative, p.getFileName().toString());
                                })
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取页面清单", e);
        }
    }

    /** 工作台识别、摘要、隔离预览与绑定编译共用同一个受控静态来源。 */
    public StaticPageSource.Page readStaticPage(
            String projectCode, String dbVersion, String filePath) {
        return StaticPageSource.read(staticPageBase(projectCode, dbVersion), filePath);
    }

    /** 绑定当前安全页面包目录，供同一轮取证读取多个页面。 */
    public StaticPageSource.Reader openStaticPageReader(String projectCode, String dbVersion) {
        return StaticPageSource.open(staticPageBase(projectCode, dbVersion), safeStaticContent);
    }

    private Path staticPageBase(String projectCode, String dbVersion) {
        requireSafeName(projectCode, "站点编码");
        Path active = resolveReadablePath(projectCode, dbVersion);
        if (active == null) throw new IllegalArgumentException("UI 包未部署");
        try {
            Path siteRoot = root.resolve(projectCode).toRealPath();
            Path base = active.toRealPath();
            if (!base.startsWith(siteRoot)) throw new IllegalArgumentException("UI 包越出站点范围");
            return base;
        } catch (IOException e) {
            throw new IllegalArgumentException("页面不存在或无法读取", e);
        }
    }

    public String getBoundedHtmlContent(String projectCode, String dbVersion, String filePath) {
        return readStaticPage(projectCode, dbVersion, filePath).html();
    }

    /** 工作台仅限量读取包内普通文件，不访问网络或执行包内容。 */
    public byte[] readInspectionResource(String code, String version, String path) {
        return readInspectionResource(code, version, path, 2 * 1024 * 1024);
    }

    public byte[] readInspectionResource(String code, String version, String path, int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > 2 * 1024 * 1024)
            throw new IllegalArgumentException("资源读取限制无效");
        try (var input = Files.newInputStream(inspectionResourcePath(code, version, path))) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes)
                throw new IllegalArgumentException("资源超过 "
                        + (maximumBytes == 2 * 1024 * 1024 ? "2 MB" : maximumBytes + " 字节")
                        + " 预览限制");
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException("资源不存在或无法读取: " + path, e);
        }
    }

    /** 用原包文件内容识别同路径被替换的资源，无需加载到内存。 */
    public String inspectionResourceDigest(String code, String version, String path) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input =
                    new DigestInputStream(
                            Files.newInputStream(inspectionResourcePath(code, version, path)),
                            digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalArgumentException("资源不存在或无法读取: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path inspectionResourcePath(String code, String version, String path) {
        requireSafeName(code, "站点编码");
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.contains(":")
                || path.indexOf(0) >= 0) {
            throw new IllegalArgumentException("资源路径无效");
        }
        Path active = resolveReadablePath(code, version);
        if (active == null) throw new IllegalArgumentException("UI 包未部署");
        try {
            Path base = active.toRealPath();
            Path target = base.resolve(path).normalize().toRealPath();
            if (!base.startsWith(root.resolve(code).toRealPath())
                    || !target.startsWith(base)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("资源路径越出站点范围");
            }
            return target;
        } catch (IOException e) {
            throw new IllegalArgumentException("资源不存在或无法读取: " + path);
        }
    }

    Path exactInspectionRoot(String code, String version) throws IOException {
        requireSafeName(code, "站点编码");
        requireSafeName(version, "版本标识");
        Path expected = root.toRealPath().resolve(code).resolve(version);
        if (!Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)
                || !expected.equals(expected.toRealPath()))
            throw new IllegalArgumentException("页面包版本不存在或路径无效");
        return expected;
    }

    /** 隔离预览读取既有当前站点/全局素材，不通过HTTP访问。 */
    public byte[] readInspectionAsset(String code, String reference) {
        requireSafeName(code, "站点编码");
        if (reference == null
                || !(reference.startsWith("assets:/upload/")
                        || reference.startsWith("assets:/global/")))
            throw new IllegalArgumentException("素材引用无效");
        String path = reference.split("[?#]", 2)[0];
        if (!path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)$"))
            throw new IllegalArgumentException("隔离素材预览仅支持安全位图图片");
        Path base =
                path.startsWith("assets:/global/")
                        ? publicStorageRoots.configuredRoot()
                        : publicStorageRoots.siteRoot(code);
        Path target = publicStorageRoots.resolveReference(path, code);
        try {
            Path actualBase = base.toRealPath();
            Path cursor = base.toAbsolutePath().normalize();
            for (Path segment : cursor.relativize(target.toAbsolutePath().normalize())) {
                cursor = cursor.resolve(segment);
                Path expected =
                        actualBase.resolve(base.toAbsolutePath().normalize().relativize(cursor));
                if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(expected))
                    throw new IllegalArgumentException("素材路径不允许软链接或目录重定向");
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !target.toRealPath().startsWith(actualBase))
                throw new IllegalArgumentException("素材不存在或越界");
            try (var input = Files.newInputStream(target)) {
                byte[] data = input.readNBytes(2 * 1024 * 1024 + 1);
                if (data.length > 2 * 1024 * 1024)
                    throw new IllegalArgumentException("素材超过2MB隔离预览限制");
                return data;
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("素材不存在或无法读取");
        }
    }

    public record InspectionFile(String path, long size) {}

    public List<InspectionFile> inspectionFiles(String code, String version) {
        requireSafeName(code, "站点编码");
        Path active = resolveReadablePath(code, version);
        if (active == null) return List.of();
        try {
            Path base = active.toRealPath();
            if (!base.startsWith(root.resolve(code).toRealPath()))
                throw new IllegalArgumentException("UI 包越出站点范围");
            try (var files = Files.walk(base)) {
                return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .limit(MAX_ENTRY_COUNT)
                        .map(
                                p -> {
                                    try {
                                        return new InspectionFile(
                                                base.relativize(p).toString().replace('\\', '/'),
                                                Files.size(p));
                                    } catch (IOException e) {
                                        throw new IllegalArgumentException("无法读取文件大小");
                                    }
                                })
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取包清单");
        }
    }

    private boolean isHtmlLike(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".html")
                || name.endsWith(".htm")
                || name.endsWith(".aspx")
                || name.endsWith(".asp");
    }

    private boolean isAsset(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        // CSS/JS/JSON
        if (name.endsWith(".css") || name.endsWith(".js") || name.endsWith(".json")) {
            return true;
        }
        // 图片
        if (name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".svg")
                || name.endsWith(".ico")
                || name.endsWith(".webp")
                || name.endsWith(".bmp")) {
            return true;
        }
        // 视频
        if (name.endsWith(".mp4")
                || name.endsWith(".webm")
                || name.endsWith(".ogg")
                || name.endsWith(".mov")
                || name.endsWith(".avi")
                || name.endsWith(".mkv")
                || name.endsWith(".flv")
                || name.endsWith(".swf")) {
            return true;
        }
        // 字体
        if (name.endsWith(".woff")
                || name.endsWith(".woff2")
                || name.endsWith(".ttf")
                || name.endsWith(".eot")
                || name.endsWith(".otf")) {
            return true;
        }
        return false;
    }

    public record FileInfo(String relativePath, String displayPath, String fileName) {}

    /**
     * 解析可读取的站点目录：优先 active 链接，缺失时回退到数据库记录的版本目录。 覆盖两类站点：(1) 已上传但从未启动服务——active 只在启动时创建； (2) active
     * 链接存在但被 NOFOLLOW 检查误拒——Linux symlink 在 NOFOLLOW_LINKS 下 isDirectory 为 false，而 Windows junction
     * 为 true，此前导致 Linux 上文件列表恒为空。 active 由本服务创建并指向受控 version 目录，跟随链接无路径逃逸风险。
     */
    private Path resolveReadablePath(String projectCode, String dbVersion) {
        Path siteRoot = root.resolve(projectCode);
        Path activeLink = siteRoot.resolve("active");
        // 解析为真实目录：Files.walk 以 symlink 为起点时不会进入目标目录
        // （Windows junction 会，故本机测试无法暴露 Linux 上的此问题），统一 toRealPath 消除平台差异
        if (Files.isDirectory(activeLink)) {
            try {
                return activeLink.toRealPath();
            } catch (IOException ignored) {
                return activeLink;
            }
        }
        if (dbVersion != null && !dbVersion.isBlank()) {
            requireSafeName(dbVersion, "版本标识");
            Path versionDir = siteRoot.resolve(dbVersion);
            if (Files.isDirectory(versionDir, LinkOption.NOFOLLOW_LINKS)) {
                return versionDir;
            }
        }
        return null;
    }

    private void extractZip(byte[] archiveBytes, Path targetDir) throws IOException {
        long totalSize = 0;
        int entryCount = 0;
        // 使用 ISO-8859-1 编码，该编码可以处理任意字节序列，不会抛出 MalformedInputException
        try (ZipInputStream zip =
                new ZipInputStream(
                        new ByteArrayInputStream(archiveBytes), Charset.forName("ISO-8859-1"))) {
            // 首先收集所有条目，确定是否需要去掉根目录
            List<ZipEntryInfo> entries = new ArrayList<>();
            String commonPrefix = null;
            // 区分"尚未确定前缀"与"已确定不去根目录"——两者此前都用 null 表示，
            // 根级文件后跟子目录文件时（如 index.html + css/style.css），css/ 会被误当作根目录剥掉
            boolean prefixDecided = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("ZIP 文件包含过多条目");
                }
                String entryPath = entry.getName().replace('\\', '/');
                if (entryPath.startsWith("/")
                        || entryPath.contains(":")
                        || entryPath.contains("\0")
                        || java.util.Arrays.asList(entryPath.split("/")).contains("..")) {
                    throw new IllegalArgumentException("ZIP 文件路径格式无效");
                }
                long remaining = MAX_UNCOMPRESSED_SIZE - totalSize;
                byte[] data = zip.readNBytes((int) Math.min(remaining + 1, Integer.MAX_VALUE));
                totalSize += data.length;
                if (totalSize > MAX_UNCOMPRESSED_SIZE) {
                    throw new IllegalArgumentException("ZIP 文件解压后总大小超限");
                }
                // 目录和元数据也计入解压大小，避免通过跳过条目绕过限量读取。
                if (!entry.isDirectory()
                        && !entryPath.contains("__MACOSX")
                        && !entryPath.startsWith("._")) {
                    entries.add(new ZipEntryInfo(entryPath, data));

                    // 计算公共前缀（根目录）
                    int slashIndex = entryPath.indexOf('/');
                    if (slashIndex > 0 && !prefixDecided) {
                        commonPrefix = entryPath.substring(0, slashIndex + 1);
                        prefixDecided = true;
                    } else if (slashIndex > 0) {
                        String prefix = entryPath.substring(0, slashIndex + 1);
                        if (!prefix.equals(commonPrefix)) {
                            commonPrefix = null; // 不同前缀，不去掉根目录
                        }
                    } else {
                        commonPrefix = null; // 文件在根级别，不去掉根目录
                        prefixDecided = true;
                    }
                }
                zip.closeEntry();
            }

            // 写入文件
            for (ZipEntryInfo info : entries) {
                String entryPath = info.path;
                // 如果所有文件都在同一个根目录下，去掉根目录
                if (commonPrefix != null && entryPath.startsWith(commonPrefix)) {
                    entryPath = entryPath.substring(commonPrefix.length());
                }

                if (entryPath.isEmpty()) continue;

                Path destination =
                        targetDir.toAbsolutePath().normalize().resolve(entryPath).normalize();
                if (!destination.startsWith(targetDir.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("ZIP 文件路径越出目标目录");
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, info.data);
            }
        }
    }

    private record ZipEntryInfo(String path, byte[] data) {}

    private static void requireSafeName(String value, String label) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 格式无效");
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }

    private static void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException ex) {
            // Windows may require administrator privileges for symbolic links
            // Fallback: create a junction point using mklink /J
            if (isWindows()) {
                try {
                    ProcessBuilder pb =
                            new ProcessBuilder(
                                    "cmd",
                                    "/c",
                                    "mklink",
                                    "/J",
                                    "\"" + link + "\"",
                                    "\"" + target + "\"");
                    Process process = pb.start();
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        throw ex;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                } catch (Exception e) {
                    throw ex;
                }
            } else {
                throw ex;
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}

package com.xinhongru.cms.template;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class TraditionalTemplatePackageStorageService {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private final Path root;

    @Autowired
    public TraditionalTemplatePackageStorageService(
            @Value(
                            "${cms.template.traditional-root:${user.dir}/runtime/traditional-template-packages}")
                    String root) {
        this(Path.of(root));
    }

    public TraditionalTemplatePackageStorageService(Path root) {
        Path configuredRoot = root.toAbsolutePath().normalize();
        try {
            this.root = canonicalManagedRoot(configuredRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("传统模板包根目录不可用", ex);
        }
    }

    public TraditionalTemplatePackageDeployment deploy(
            String serverDerivedSiteCode,
            String version,
            TraditionalTemplatePackageManifest manifest,
            byte[] archiveBytes) {
        TraditionalTemplatePackageDeployment deployment =
                stage(serverDerivedSiteCode, version, manifest, archiveBytes);
        activate(serverDerivedSiteCode, version);
        return deployment;
    }

    public TraditionalTemplatePackageDeployment stage(
            String serverDerivedSiteCode,
            String version,
            TraditionalTemplatePackageManifest manifest,
            byte[] archiveBytes) {
        requireSafeName(serverDerivedSiteCode, "站点编码");
        requireSafeName(version, "模板包版本");
        if (manifest == null || archiveBytes == null) {
            throw new IllegalArgumentException("模板包 manifest 与 ZIP 不能为空");
        }
        Map<String, byte[]> entries = archiveContents(archiveBytes);
        Map<String, TraditionalTemplateFile> expected = new LinkedHashMap<>();
        for (TraditionalTemplateFile file : manifest.files()) {
            expected.put(file.path(), file);
        }
        if (!entries.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException("ZIP 文件与 manifest 不一致");
        }
        for (Map.Entry<String, TraditionalTemplateFile> item : expected.entrySet()) {
            if (!TraditionalTemplatePackageValidator.sha256(entries.get(item.getKey()))
                    .equals(item.getValue().checksum())) {
                throw new IllegalArgumentException("ZIP 文件 checksum 与 manifest 不一致");
            }
        }

        Path siteRoot = managedPath(root.resolve(serverDerivedSiteCode));
        Path versionsRoot = managedPath(siteRoot.resolve("versions"));
        Path staging =
                managedPath(siteRoot.resolve(".staging").resolve(UUID.randomUUID().toString()));
        Path target = managedPath(versionsRoot.resolve(version));
        try {
            createManagedDirectories(siteRoot);
            createManagedDirectories(staging);
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                Path destination = managedPath(staging.resolve(item.getKey()));
                if (!destination.startsWith(staging)) {
                    throw new IllegalArgumentException("模板文件路径越出 staging 目录");
                }
                createManagedDirectories(destination.getParent());
                assertManagedPath(destination);
                Files.write(
                        destination,
                        item.getValue(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            }
            createManagedDirectories(versionsRoot);
            assertManagedPath(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("模板包版本已存在");
            }
            atomicMove(staging, target, false);
            assertManagedPath(target);
            return new TraditionalTemplatePackageDeployment(serverDerivedSiteCode, version, target);
        } catch (IOException ex) {
            throw new IllegalStateException("传统模板包部署失败", ex);
        } finally {
            deleteIfEmpty(staging);
        }
    }

    public void activate(String serverDerivedSiteCode, String version) {
        requireSafeName(serverDerivedSiteCode, "站点编码");
        requireSafeName(version, "模板包版本");
        Path siteRoot = managedPath(root.resolve(serverDerivedSiteCode));
        Path target = managedPath(siteRoot.resolve("versions").resolve(version));
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("模板包版本不存在");
        try {
            writeActiveVersion(siteRoot, version);
        } catch (IOException ex) {
            throw new IllegalStateException("传统模板包活动版本切换失败", ex);
        }
    }

    public void restoreActive(String serverDerivedSiteCode, String previousVersion) {
        if (previousVersion == null || previousVersion.isBlank()) {
            try {
                Files.deleteIfExists(
                        managedPath(root.resolve(serverDerivedSiteCode).resolve("active-version")));
            } catch (IOException ex) {
                throw new IllegalStateException("传统模板包活动版本恢复失败", ex);
            }
            return;
        }
        activate(serverDerivedSiteCode, previousVersion);
    }

    public Optional<TraditionalTemplatePackageDeployment> activeDeployment(
            String serverDerivedSiteCode) {
        requireSafeName(serverDerivedSiteCode, "站点编码");
        Path siteRoot = managedPath(root.resolve(serverDerivedSiteCode));
        Path active = managedPath(siteRoot.resolve("active-version"));
        try {
            if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            assertManagedPath(active);
            String version = Files.readString(active, StandardCharsets.UTF_8).trim();
            requireSafeName(version, "模板包版本");
            Path deploymentRoot = managedPath(siteRoot.resolve("versions").resolve(version));
            if (!deploymentRoot.startsWith(siteRoot)
                    || !Files.isDirectory(deploymentRoot, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            assertManagedPath(deploymentRoot);
            return Optional.of(
                    new TraditionalTemplatePackageDeployment(
                            serverDerivedSiteCode, version, deploymentRoot));
        } catch (IOException ex) {
            throw new IllegalStateException("读取传统模板包版本失败", ex);
        }
    }

    public Path root() {
        return root;
    }

    private Map<String, byte[]> archiveContents(byte[] archiveBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(
                        new ByteArrayInputStream(archiveBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName().replace('\\', '/'), zip.readAllBytes());
                }
                zip.closeEntry();
            }
            return entries;
        } catch (IOException ex) {
            throw new IllegalArgumentException("ZIP 文件无法读取", ex);
        }
    }

    private void writeActiveVersion(Path siteRoot, String version) throws IOException {
        assertManagedPath(siteRoot);
        Path activeVersion = managedPath(siteRoot.resolve("active-version"));
        assertManagedPath(activeVersion);
        Path temporary = Files.createTempFile(siteRoot, "active-version-", ".tmp");
        assertManagedPath(temporary);
        Files.writeString(
                temporary, version, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        atomicMove(temporary, activeVersion, true);
    }

    private static void atomicMove(Path source, Path target, boolean replaceExisting)
            throws IOException {
        try {
            if (replaceExisting) {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IllegalStateException("传统模板包存储必须支持原子重命名", ex);
        }
    }

    private static void requireSafeName(String value, String label) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "只能由服务端生成安全标识");
        }
    }

    private static Path canonicalManagedRoot(Path configuredRoot) throws IOException {
        if (Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(configuredRoot)) {
            throw new IllegalArgumentException("传统模板包根目录不能使用符号链接: " + configuredRoot);
        }
        Path existingAncestor = configuredRoot;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
            if (existingAncestor == null) {
                throw new IOException("传统模板包根目录没有可解析的现有祖先");
            }
        }
        Path canonicalAncestor = existingAncestor.toRealPath();
        Path canonicalRoot =
                canonicalAncestor.resolve(existingAncestor.relativize(configuredRoot)).normalize();
        Files.createDirectories(canonicalRoot);
        if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(canonicalRoot)) {
            throw new IllegalArgumentException("传统模板包根目录不能使用符号链接: " + configuredRoot);
        }
        return canonicalRoot.toRealPath();
    }

    private Path managedPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("传统模板包目录越出受管根目录");
        }
        return normalized;
    }

    private void createManagedDirectories(Path path) throws IOException {
        assertManagedPath(path);
        Files.createDirectories(path);
        assertManagedPath(path);
    }

    private void assertManagedPath(Path path) throws IOException {
        Path managed = managedPath(path);
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("传统模板包目录不能使用符号链接: " + current);
        }
        for (Path segment : root.relativize(managed)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("传统模板包目录不能使用符号链接: " + current);
            }
        }
    }

    private static void deleteIfEmpty(Path path) {
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        } catch (IOException ignored) {
            // A failed deployment can leave a non-empty staging directory for an operator to
            // inspect.
        }
    }
}

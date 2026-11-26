package com.xinhongru.config;

import com.xinhongru.properties.StaticAssetProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 全局素材与站点素材的磁盘边界；资源作用域不承担用户权限判断。 */
@Component
public class PublicStorageRoots {
    private final StaticAssetProperties properties;
    private final Path packages;

    public PublicStorageRoots(
            StaticAssetProperties properties,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}") String packages) {
        this.properties = properties;
        this.packages = Path.of(packages).toAbsolutePath().normalize();
    }

    public Path configuredRoot() {
        return Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    public Path siteRoot(String code) {
        requireSiteCode(code);
        return packages.resolve(code).resolve("assets/upload");
    }

    public static void requireSiteCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("站点编码不合法");
    }

    public Path resolve(String relative) {
        return resolveUnder(configuredRoot(), relative);
    }

    public Path resolveReference(String reference, String code) {
        if (reference != null && reference.startsWith("assets:/global/"))
            return resolve(reference.substring(15));
        if (reference != null && reference.startsWith("assets:/upload/"))
            return resolveUnder(siteRoot(code), reference.substring(15));
        throw new IllegalArgumentException("不支持的素材引用");
    }

    public Path resolveUnder(Path root, String relative) {
        if (relative == null
                || relative.contains("\\")
                || relative.contains("%")
                || relative.contains(":")
                || relative.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("资源路径不安全");
        Path target = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !target.startsWith(root))
            throw new IllegalArgumentException("资源路径越界");
        Path current = target.getRoot();
        for (Path segment : target) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("资源路径不能包含符号链接");
        }
        return target;
    }

    public Location location(Path path) {
        Path target = path.toAbsolutePath().normalize();
        if (target.startsWith(configuredRoot()))
            return new Location("assets:/global/" + relative(configuredRoot(), target), null);
        if (target.startsWith(packages)) {
            Path rel = packages.relativize(target);
            if (rel.getNameCount() >= 4
                    && rel.getName(1).toString().equals("assets")
                    && rel.getName(2).toString().equals("upload")) {
                String code = rel.getName(0).toString();
                resolveUnder(siteRoot(code), relative(siteRoot(code), target));
                return new Location("assets:/upload/" + relative(siteRoot(code), target), code);
            }
        }
        throw new IllegalArgumentException("文件不属于公开素材目录");
    }

    public String reference(Path path) {
        return location(path).reference();
    }

    public Path directory(Path directory) throws IOException {
        Location location = location(directory.resolve("placeholder"));
        Path root = location.siteCode() == null ? configuredRoot() : siteRoot(location.siteCode());
        Path safe = resolveUnder(root, relative(root, directory));
        Files.createDirectories(safe);
        return safe;
    }

    public Path directory(String relative) throws IOException {
        return directory(resolve(relative));
    }

    private String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public record Location(String reference, String siteCode) {}
}

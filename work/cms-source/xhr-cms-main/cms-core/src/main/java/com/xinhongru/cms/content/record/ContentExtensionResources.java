package com.xinhongru.cms.content.record;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.config.UploadConfig;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** 字段只引用当前站点或全局真实文件，不通过网络猜测资源归属和存在性。 */
@Service
public class ContentExtensionResources {
    private final PublicStorageRoots roots;
    private final ResourceReferenceResolver resolver;
    private final UploadConfig formats;
    private final UiPackageStorageService packages;
    private final SiteProjectMapper projects;

    public ContentExtensionResources(
            PublicStorageRoots roots,
            ResourceReferenceResolver resolver,
            UploadConfig formats,
            UiPackageStorageService packages,
            SiteProjectMapper projects) {
        this.roots = roots;
        this.resolver = resolver;
        this.formats = formats;
        this.packages = packages;
        this.projects = projects;
    }

    public String prepare(ContentFieldDefinition field, Object value, Long siteId) {
        ContentExtensionFields.validateDefinition(field);
        if (!ContentExtensionFields.isResource(field))
            throw new IllegalArgumentException("字段不是资源类型");
        return prepareReference(field, value, siteId);
    }

    /** 模板只允许复用可安全读写的原生单图槽位。 */
    public String prepareTemplateMedia(ContentFieldDefinition field, Object value, Long siteId) {
        if (!NativeImageFields.supports(field)) return prepare(field, value, siteId);
        if (value instanceof String text) validateNativeImageReference(text);
        String reference = prepareReference(field, value, siteId);
        validateNativeImageReference(reference);
        if (reference.length() > NativeImageFields.capacity(field))
            throw new IllegalArgumentException("原生图片地址超出字段容量: " + field.getFieldName());
        return reference;
    }

    private static void validateNativeImageReference(String reference) {
        if (reference.indexOf('"') >= 0
                || reference.indexOf('\\') >= 0
                || reference.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("原生图片地址包含不支持的字符");
    }

    private String prepareReference(ContentFieldDefinition field, Object value, Long siteId) {
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("资源地址必须为文本");
        String text = value == null ? "" : ((String) value).trim();
        if (text.isEmpty()) return "";
        if (text.matches("(?s).*[\r\n,;].*") || text.startsWith("["))
            throw new IllegalArgumentException("当前字段只接受一个资源地址");
        var site = siteId == null ? null : projects.selectById(siteId);
        if (site == null || site.getProjectCode() == null)
            throw new IllegalArgumentException("资源所属站点不存在");
        String code = site.getProjectCode();
        PublicStorageRoots.requireSiteCode(code);
        var context = resolver.context(code);
        String reference = context.canonical(text);
        String resolved = context.resolve(reference);
        if (resolved != null) {
            String normalized = context.canonical(resolved);
            if (normalized.startsWith("assets:") || normalized.startsWith("ui:"))
                reference = normalized;
        }
        String path = reference.split("[?#]", 2)[0];
        Path base;
        Path file;
        if (path.startsWith("assets:/global/") || path.startsWith("assets:/upload/")) {
            base =
                    path.startsWith("assets:/global/")
                            ? roots.configuredRoot()
                            : roots.siteRoot(code);
            file = roots.resolveReference(path, code);
        } else if (path.startsWith("ui:/")) {
            String version = site.getUiPackageVersion();
            base =
                    packages.getActivePath(code)
                            .orElseGet(
                                    () -> {
                                        if (version == null
                                                || !version.matches(
                                                        "[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                                            throw new IllegalArgumentException("当前站点UI包未部署");
                                        return packages.root().resolve(code).resolve(version);
                                    });
            file = roots.resolveUnder(base, path.substring(4));
        } else throw new IllegalArgumentException("资源必须来自当前站点或全局素材库");
        if (!formats.extensions(field.getFieldType().toLowerCase(Locale.ROOT))
                .contains(UploadDelivery.extension(file.getFileName().toString())))
            throw new IllegalArgumentException("资源格式不符合字段类型: " + field.getTitle());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("资源不存在或已被删除: " + field.getTitle());
            if (!file.toRealPath().startsWith(base.toRealPath()))
                throw new IllegalArgumentException("资源路径越界");
            if (path.startsWith("ui:/")
                    && !base.toRealPath().startsWith(packages.root().resolve(code).toRealPath()))
                throw new IllegalArgumentException("UI资源不属于当前站点");
        } catch (IOException error) {
            throw new IllegalArgumentException("资源不存在或无法读取: " + field.getTitle());
        }
        return reference;
    }
}

package com.xinhongru.security.upload;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinhongru.cms.uipackage.UiPackageHttpServer;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.properties.ResourceProperties;
import com.xinhongru.resource.ResourceAccessProvider;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 资源引用与域名分离；空域名沿用站点/UI服务地址，不隐式启动服务。 */
@Service
public class ResourceReferenceResolver {
    private final ResourceProperties properties;
    private final UiPackageHttpServer ui;
    private final ObjectProvider<ResourceAccessProvider> sites;
    private final SiteProjectMapper projects;

    public ResourceReferenceResolver(
            ResourceProperties properties,
            UiPackageHttpServer ui,
            ObjectProvider<ResourceAccessProvider> sites,
            SiteProjectMapper projects) {
        this.properties = properties;
        this.ui = ui;
        this.sites = sites;
        this.projects = projects;
    }

    public String siteCode(Long siteId) {
        if (siteId == null) return null;
        var site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site.getProjectCode();
    }

    public Context context(String code) {
        String domain = properties.getDomain();
        String global = domain.isBlank() ? "/assets/global/" : domain + "/assets/global/";
        if (code == null || code.isBlank()) return new Context(null, null, null, global);
        PublicStorageRoots.requireSiteCode(code);
        var site =
                projects.selectOne(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getProjectCode, code));
        String siteDomain = site == null ? null : site.getSiteServiceIp();
        String base;
        if (ResourceProperties.isExplicitDomain(siteDomain)) {
            base = ResourceProperties.normalizeSiteDomain(siteDomain, null) + "/";
            global = base + "assets/global/";
        } else if (!domain.isBlank()) {
            base = domain + "/" + code + "/";
        } else {
            ResourceAccessProvider provider = sites.getIfAvailable();
            base = provider == null ? null : provider.accessUrl(code);
            if (base != null && !base.isBlank()) {
                if (!base.endsWith("/")) base += "/";
                global = base + "assets/global/";
            } else {
                base = ui.getResourceBaseUrl(code, siteDomain);
                // 共享 UI 服务的全局素材入口不带站点编码。
                if (base != null)
                    global =
                            base.substring(0, base.length() - code.length() - 1) + "assets/global/";
            }
        }
        if (base != null && !base.endsWith("/")) base += "/";
        return new Context(code, base, base == null ? null : base + "assets/upload/", global);
    }

    public String resolve(String reference, String code) {
        return context(code).resolve(reference);
    }

    public String resolveForSite(String reference, Long siteId) {
        return resolve(reference, siteCode(siteId));
    }

    public record Context(String siteCode, String ui, String upload, String global) {
        public String resolve(String reference) {
            if (reference == null || reference.isBlank()) return reference;
            if (reference.startsWith("assets:/global/"))
                return join(global, reference.substring(15));
            if (reference.startsWith("assets:/upload/"))
                return join(upload, reference.substring(15));
            if (reference.startsWith("ui:/")) return join(ui, reference.substring(4));
            if (reference.startsWith("assets:") || reference.startsWith("ui:"))
                throw new IllegalArgumentException("资源引用格式无效");
            if (reference.matches("(?is)^(javascript|file|classpath):.*"))
                throw new IllegalArgumentException("不允许的资源协议");
            return reference;
        }

        public String canonical(String url) {
            if (url == null) return null;
            if (global != null && url.startsWith(global))
                return "assets:/global/" + decode(url.substring(global.length()));
            if (upload != null && url.startsWith(upload))
                return "assets:/upload/" + decode(url.substring(upload.length()));
            if (ui != null
                    && url.startsWith(ui)
                    && url.matches(
                            "(?i).*\\.(png|jpe?g|gif|webp|svg|bmp|mp4|webm|mp3|pdf|docx?|xlsx?|pptx?|zip|txt|css|js)([?#].*)?"))
                return "ui:/" + decode(url.substring(ui.length()));
            return url;
        }

        private static String decode(String path) {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        }

        private static String join(String base, String path) {
            int suffixAt = path.length();
            for (char marker : new char[] {'?', '#'}) {
                int index = path.indexOf(marker);
                if (index >= 0) suffixAt = Math.min(suffixAt, index);
            }
            String suffix = path.substring(suffixAt);
            path = path.substring(0, suffixAt);
            if (path.contains("\\")
                    || path.contains("%")
                    || path.startsWith("/")
                    || path.contains(":")
                    || Arrays.asList(path.split("/")).contains(".."))
                throw new IllegalArgumentException("资源引用路径不安全");
            return base == null
                    ? null
                    : base + UriUtils.encodePath(path, StandardCharsets.UTF_8) + suffix;
        }
    }
}

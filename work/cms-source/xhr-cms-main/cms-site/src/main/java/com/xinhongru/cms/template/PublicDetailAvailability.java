package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.SiteServerRegistry;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.content.ContentLinkService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 校验共享列表详情模板和当前已加载的公开路由。 */
@Component
class PublicDetailAvailability {
    private final TemplateStorageService templates;
    private final ContentLinkService links;
    private final SiteServerRegistry servers;
    private final Path root;

    PublicDetailAvailability(
            TemplateStorageService templates,
            ContentLinkService links,
            SiteServerRegistry servers,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units") String root) {
        this.templates = templates;
        this.links = links;
        this.servers = servers;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    boolean detailTemplateAvailable(Long site, String code, String version, OneshotEntity menu) {
        return detailTemplateProblem(site, code, version, menu) == null;
    }

    String detailTemplateProblem(Long site, String code, String version, OneshotEntity menu) {
        String template = menu.getDetailPageTemplate();
        if (menu.getDetailPageUrl() == null || !menu.getDetailPageUrl().contains("{id}"))
            return "当前栏目未配置包含记录 ID 的详情路由，卡片不提供详情链接";
        if (template == null || template.isBlank())
            return "当前栏目未配置详情模板，卡片不提供详情链接";
        try {
            if (PublicBindingApplication.managed(template)) {
                var application = PublicBindingApplication.atPath(root, code, template);
                if (!Objects.equals(application.siteId(), site)
                        || !Objects.equals(application.menuId(), menu.getId().longValue())
                        || !Objects.equals(application.packageVersion(), version) || application.pageType() != 1
                        || application.queryId() != null || application.routeMenuId() != null
                        || !Objects.equals(application.routeUrl(), menu.getDetailPageUrl())
                        || !Objects.equals(application.fieldConfigDigest(), ContentFieldConfigDigest.of(menu)))
                    return "详情模板应用与当前栏目、原包或字段配置不一致，卡片不提供详情链接";
                verifiedTemplate(templates, code, application);
                return null;
            }
            var directory = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
            var file = directory.resolve(template).normalize();
            return file.startsWith(directory) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.toRealPath().startsWith(directory.toRealPath())
                    ? null : "详情模板文件缺失或不可用，卡片不提供详情链接";
        } catch (IOException | IllegalArgumentException unavailable) {
            return "详情模板文件或公开应用凭据缺失或失效，卡片不提供详情链接";
        }
    }

    String sharedDetailUrl(Long site, String code, OneshotEntity menu, ContentEntity row) {
        String url;
        URI uri;
        try {
            url = PublicListModelProjection.localUrl(links.getDetailUrl(row, menu));
            uri = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        String path = uri.getPath();
        return uri.getRawQuery() == null && path.length() > 1
                && servers.hasPublicDetailRoute(site, code, menu, row.getId(), path.substring(1))
                ? url : null;
    }

    static String verifiedTemplate(TemplateStorageService templates, String code, PublicBindingApplication application) {
        var directory = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
        var path = directory.resolve(application.templatePath()).normalize();
        try {
            for (var current = path;
                    current != null && current.startsWith(directory);
                    current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("公开模板不能是链接");
            if (!path.startsWith(directory)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 2 * 1024 * 1024
                    || !path.toRealPath().startsWith(directory.toRealPath()))
                throw new IllegalArgumentException("公开模板已变化，拒绝覆盖或撤回");
            var html = Files.readString(path);
            if (!Objects.equals(PublicBindingApplication.digest(html), application.templateDigest()))
                throw new IllegalArgumentException("公开模板已变化，拒绝覆盖或撤回");
            return html;
        } catch (IOException error) {
            throw new IllegalArgumentException("公开模板不可读取", error);
        }
    }
}

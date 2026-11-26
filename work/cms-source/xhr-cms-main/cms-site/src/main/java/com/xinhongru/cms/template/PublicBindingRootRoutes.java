package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.site.storage.SiteStoragePathResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

/** 仅为当前根栏目已确认的首页别名保留动态路由；正文与发布状态由可信贡献者每次核验。 */
@Component
public class PublicBindingRootRoutes implements NativeHomePublication {
    private final SiteProjectMapper projects;
    private final Path root;
    private final OneShotMapper menus;
    private final TemplateStorageService templates;
    private final SiteWorkbenchContextService workspace;
    private final SiteStoragePathResolver storage;
    private final CmsBuilderHtmlConfigProperties publishing;
    private final boolean workbenchEnabled;

    private record NativeHome(String previousTemplate, String template, String templateDigest) {}

    public PublicBindingRootRoutes(
            SiteProjectMapper projects,
            OneShotMapper menus, TemplateStorageService templates, SiteWorkbenchContextService workspace,
            SiteStoragePathResolver storage, CmsBuilderHtmlConfigProperties publishing,
            @Value("${cms.workbench.enabled:false}") boolean workbenchEnabled,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.projects = projects;
        this.menus = menus;
        this.templates = templates;
        this.workspace = workspace;
        this.storage = storage;
        this.publishing = publishing;
        this.workbenchEnabled = workbenchEnabled;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public List<String> aliases(Long siteId, String code, OneshotEntity route) {
        if (route == null || !PublicBindingApplication.managed(route.getListPageTemplate()))
            return nativeAliases(siteId, code, route).orElse(List.of());
        var application =
                PublicBindingApplication.read(
                        root, code, PublicBindingApplication.keyFrom(route.getListPageTemplate()));
        if (application == null) throw new IllegalArgumentException("当前根模板应用凭据缺失，请明确恢复或撤回");
        if (application.routeMenuId() == null) return List.of(); // 旧普通模板凭据没有首页别名。
        var project = projects.selectById(siteId);
        if (project == null
                || !Objects.equals(project.getId(), siteId)
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getRootMenuId(), route.getId())
                || !Objects.equals(route.getSiteRootId(), route.getId())
                || !Objects.equals(application.siteId(), siteId)
                || !Objects.equals(route.getSiteProjectId(), siteId)
                || !Objects.equals(application.routeMenuId(), route.getId().longValue())
                || Objects.equals(application.menuId(), application.routeMenuId())
                || application.pageType() != (application.homeBanner() == null ? 0 : 1)
                || !Objects.equals(application.templatePath(), route.getListPageTemplate())
                || !Objects.equals(application.routeUrl(), route.getListPageUrl()))
            throw new IllegalArgumentException("当前首页别名与真实站点根路由不一致");
        return checkedAliases(application.routeAliases());
    }

    @Override
    public Optional<List<String>> nativeAliases(Long siteId, String code, OneshotEntity route) {
        return nativeProof(siteId, code, route).map(Proof::aliases);
    }

    @Override
    public Optional<Proof> nativeProof(Long siteId, String code, OneshotEntity route) {
        if (!workbenchEnabled || route == null || route.getId() == null
                || !Objects.equals(route.getId(), route.getSiteRootId())
                || !Objects.equals(route.getSiteProjectId(), siteId) || !Integer.valueOf(0).equals(route.getPageType())
                || !Boolean.TRUE.equals(route.getHasFrontChannel()) || Boolean.TRUE.equals(route.getIsDeleted())
                || code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) return Optional.empty();
        try {
            Path review = root.resolve(code).resolve("native-home.json");
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(review.getParent())
                    || !Files.isRegularFile(review, LinkOption.NOFOLLOW_LINKS) || Files.size(review) > 4096)
                return Optional.empty();
            var approval = new tools.jackson.databind.ObjectMapper().readValue(Files.readString(review), NativeHome.class);
            if (approval.template() == null || !approval.template().matches("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.html")
                    || PublicBindingApplication.managed(approval.template())
                    || !Objects.equals(route.getListPageTemplate(), approval.template())) return Optional.empty();
            var original = PublicBindingApplication.atPath(root, code, approval.previousTemplate());
            var project = projects.selectById(siteId);
            if (original == null || project == null || Integer.valueOf(1).equals(project.getDeleted())
                    || !Objects.equals(project.getProjectCode(), code) || !Objects.equals(project.getRootMenuId(), route.getId())
                    || !Objects.equals(project.getUiPackageVersion(), original.packageVersion())
                    || !Objects.equals(original.siteId(), siteId) || !Objects.equals(original.routeMenuId(), route.getId().longValue())
                    || !Objects.equals(original.routeUrl(), route.getListPageUrl()) || original.pageType() != 0
                    || Objects.equals(original.menuId(), original.routeMenuId())) return Optional.empty();

            var owner = menus.selectPublicBindingMenu(siteId, original.menuId());
            if (owner == null || !Objects.equals(owner.getSiteProjectId(), siteId)
                    || !Objects.equals(owner.getParentId(), route.getId()) || !Objects.equals(owner.getSiteRootId(), route.getId())
                    || !Integer.valueOf(0).equals(owner.getPageType()) || !Integer.valueOf(3).equals(owner.getStatus())
                    || Boolean.TRUE.equals(owner.getIsDeleted()) || !Boolean.TRUE.equals(owner.getHasFrontChannel())
                    || !Objects.equals(owner.getListPageTemplate(), approval.template())
                    || !Objects.equals(ContentFieldConfigDigest.of(owner), original.fieldConfigDigest())) return Optional.empty();

            Path directory = templates.resolveTemplateDir(code);
            Path template = directory.resolve(approval.template());
            Path previous = directory.resolve(original.templatePath());
            if (!matchesTemplate(directory, template, approval.templateDigest())
                    || !matchesTemplate(directory, previous, original.templateDigest())) return Optional.empty();
            String url = owner.getListPageUrl();
            if (url == null || !url.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:html|htm|aspx)")) return Optional.empty();
            Path outputDir = Path.of(storage.siteRootPath(publishing.getSaveRoot(), code)).toAbsolutePath().normalize();
            Path output = outputDir.resolve(url.substring(1));
            if (!checkedFile(outputDir, output) || Files.size(output) > 16 * 1024 * 1024
                    || Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(template)) < 0
                    || !StaticRenderProof.of(siteId, owner.getId(), false, approval.template())
                            .equals(workspace.staticArtifactProof(siteId, code, output, Files.readString(output))))
                return Optional.empty();
            return Optional.of(new Proof(owner.getId(), checkedAliases(original.routeAliases())));
        } catch (java.io.IOException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static boolean matchesTemplate(Path root, Path path, String digest) throws java.io.IOException {
        return checkedFile(root, path) && Files.size(path) <= 16 * 1024 * 1024
                && Objects.equals(PublicBindingApplication.digest(Files.readString(path)), digest);
    }

    private static boolean checkedFile(Path root, Path path) throws java.io.IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(root.toRealPath());
    }

    private static List<String> checkedAliases(List<String> values) {
        if (values == null
                || values.size() > 8
                || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> !indexAlias(value)))
            throw new IllegalArgumentException("首页别名不是已限定的根目录入口");
        // 不能因未发布、UI版本变化或模板tamper移除保留路由而落回同名静态文件。
        return List.copyOf(values);
    }

    static boolean indexAlias(String value) {
        return value != null && value.matches("(?i)/index(?:\\.(?:html|htm|aspx|asp))?");
    }
}

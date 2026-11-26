package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.RouteHandlerEntry;
import com.xinhongru.cms.siteservice.SiteRouteRegistry;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 已审栏目入口集合的公开应用，复用原生LIST路由和模板指针。 */
@Service
public class NavigationLandingApplicationService {
    public record Receipt(String planId, PublicNavigationLinkCompiler.LandingPreview preview) {}

    public record Request(String packageVersion, String sourcePath, String nodeKey, String containerSelector,
                          String previewDigest, String expectedTemplate, String routeUrl, boolean confirmed,
                          Set<String> displayOnlyNodeKeys) {}

    public record Inspection(boolean applied, String templatePath, String publicUrl, boolean requiresRouteReload,
                             String message, Set<String> displayOnlyNodeKeys) {}

    private final PublicNavigationLinkCompiler navigation;
    private final UiPackageStorageService packages;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final TemplateStorageService templates;
    private final SiteRouteRegistry routes;
    private final PublicSharedContentCompiler shared;
    private final Path root;

    public NavigationLandingApplicationService(PublicNavigationLinkCompiler navigation,
            UiPackageStorageService packages, OneShotMapper menus, SiteProjectMapper projects,
            TemplateStorageService templates, SiteRouteRegistry routes, PublicSharedContentCompiler shared,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units") String root) {
        this.navigation = navigation;
        this.packages = packages;
        this.menus = menus;
        this.projects = projects;
        this.templates = templates;
        this.routes = routes;
        this.shared = shared;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Inspection inspect(SiteModelNavigationService.Scope scope, String planId,
            String sourcePath, String nodeKey, String containerSelector) {
        var preview = navigation.previewLanding(scope, planId, sourcePath, nodeKey, containerSelector);
        var menu = menus.selectPublicBindingMenu(scope.siteId(), preview.menuId().longValue());
        requireScope(scope, menu);
        var application = read(scope, planId, sourcePath, nodeKey);
        if (application == null) return new Inspection(false, null,
                menu.getListPageUrl(), false, "尚未公开应用此栏目概览", Set.of());
        verifyTemplate(scope.code(), application);
        var result = result(application, menu, false);
        var displayOnly = application.navigationLanding().preview().cards().stream()
                .filter(card -> card.state() == PublicNavigationLinkCompiler.LandingTargetState.DISPLAY_ONLY)
                .map(PublicNavigationLinkCompiler.LandingCard::nodeKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Inspection(result.applied(), result.templatePath(), result.publicUrl(), false,
                result.message(), displayOnly);
    }

    @Transactional
    public PublicListApplicationService.Result apply(SiteModelNavigationService.Scope scope, String planId,
            Request request) {
        transaction(request != null && request.confirmed());
        if (!Objects.equals(scope.packageVersion(), request.packageVersion()))
            throw new IllegalArgumentException("概览页面包版本已变化");
        var before = navigation.previewLanding(scope, planId, request.sourcePath(), request.nodeKey(),
                request.containerSelector());
        var menu = menus.lockFieldConfiguration(scope.siteId(), before.menuId().longValue());
        requireScope(scope, menu);
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("概览父栏目未开放前台");
        var preview = navigation.previewLanding(scope, planId, request.sourcePath(), request.nodeKey(),
                request.containerSelector());
        if (!before.equals(preview) || !Objects.equals(request.previewDigest(), preview.digest())
                || !Objects.equals(request.expectedTemplate(), menu.getListPageTemplate())
                || !Objects.equals(request.routeUrl(), menu.getListPageUrl()))
            throw new IllegalArgumentException("概览审核、路由或当前模板已变化，请重新预览");
        var displayOnly = request.displayOnlyNodeKeys() == null ? Set.<String>of() : request.displayOnlyNodeKeys();
        if (!preview.cards().stream().map(PublicNavigationLinkCompiler.LandingCard::nodeKey).toList()
                .containsAll(displayOnly)) throw new IllegalArgumentException("纯展示决定超出本次审核卡片范围");
        preview = preview.withDisplayOnly(displayOnly);
        requireRoute(scope, menu);
        String key = key(scope, planId, request.sourcePath(), request.nodeKey());
        var existing = read(scope, planId, request.sourcePath(), request.nodeKey());
        String current = menu.getListPageTemplate();
        if (existing != null && !Objects.equals(current, existing.previousTemplate())
                && !Objects.equals(current, existing.templatePath()))
            throw new IllegalArgumentException("概览模板已被其他应用或人工更改");
        if (PublicBindingApplication.managed(current)
                && (existing == null || !Objects.equals(current, existing.templatePath())))
            throw new IllegalArgumentException("当前栏目已有其他公开列表应用，请先明确撤回");
        String oldHtml = existing == null ? null : verifyTemplate(scope.code(), existing);
        var source = packages.readStaticPage(scope.code(), scope.packageVersion(), preview.sourcePath());
        if (!source.digest().equals(preview.sourceDigest())) throw new IllegalArgumentException("概览来源已变化");
        String html = compile(source.html(), preview);
        html = PublicBindingResources.rebase(html, preview.sourcePath());
        boolean withNavigation = navigation.hasConfirmation(scope.siteId(), scope.code(), scope.packageVersion(),
                preview.sourcePath());
        if (withNavigation) html = navigation.bind(scope.siteId(), scope.code(), scope.packageVersion(),
                preview.sourcePath(), html, menu.getId().longValue());
        var publicFields = shared.compile(scope.siteId(), scope.code(), scope.packageVersion(), preview.sourcePath(), html);
        html = publicFields.html();
        validateCards(PublicSharedContentCompiler.withoutInstructions(html, publicFields.references()), preview);
        if (existing != null && !Objects.equals(existing.templateDigest(), PublicBindingApplication.digest(html))) {
            if (!existing.navigationLanding().preview().sameSources(preview))
                throw new IllegalArgumentException("原概览卡片来源已变化，请独立核对后迁移");
            var cardSelectors = preview.cards().stream().map(PublicNavigationLinkCompiler.LandingCard::selector).toList();
            var compareNavigation = withNavigation
                    ? navigation.reapplicationComparison(scope.siteId(), scope.code(), scope.packageVersion(), preview.sourcePath())
                    : java.util.function.UnaryOperator.<String>identity();
            PublicSharedContentCompiler.verifyReapplication(oldHtml, existing.sharedContent(), html,
                    publicFields.references(), true, preview.sourcePath(), candidate -> {
                        var document = Jsoup.parse(compareNavigation.apply(candidate));
                        document.outputSettings().prettyPrint(false);
                        for (String selector : cardSelectors) document.select(selector)
                                .removeAttr("href").removeAttr("aria-disabled").removeAttr("tabindex");
                        return document.outerHtml();
                    });
        }
        String hash = PublicBindingApplication.digest(html);
        String path = "cms-applied/" + key + "/" + hash + ".html";
        var application = new PublicBindingApplication(key, scope.siteId(), menu.getId().longValue(), planId,
                scope.packageVersion(), 1, ContentFieldConfigDigest.of(menu), List.of(), path, hash,
                existing == null ? current : existing.previousTemplate(), menu.getListPageUrl(), null, null,
                List.of(), null, publicFields.references(), List.of(), List.of(), null, null,
                new Receipt(planId, preview));
        if (Objects.equals(current, path)) return result(application, menu, false);
        try {
            var target = templates.resolveTemplateDir(scope.code()).resolve(path);
            if (Files.exists(target)) verifyTemplate(scope.code(), application);
            else TemplateFileWriter.write(templates.resolveTemplateDir(scope.code()), path, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("概览模板无法保存", error);
        }
        if (menus.updatePublicListBindingTemplate(scope.siteId(), menu.getId().longValue(), current, path) != 1)
            throw new IllegalArgumentException("列表模板并发变化，概览未应用");
        restoreReceiptOnRollback(scope.code(), existing, application);
        application.write(root, scope.code());
        return new PublicListApplicationService.Result(true, path, application.routeUrl(), true,
                "概览模板已应用，请重载路由并完成静态发布");
    }

    private void restoreReceiptOnRollback(String code, PublicBindingApplication previous,
            PublicBindingApplication next) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                var current = PublicBindingApplication.read(root, code, next.key());
                if (!Objects.equals(current, next)) return;
                if (previous != null) previous.write(root, code);
                else {
                    try {
                        Files.deleteIfExists(PublicBindingApplication.file(root, code, next.key()));
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException("概览事务失败后回执未能恢复", error);
                    }
                }
            }
        });
    }

    @Transactional
    public PublicListApplicationService.Result withdraw(SiteModelNavigationService.Scope scope, String planId,
            String sourcePath, String nodeKey, boolean confirmed) {
        transaction(confirmed);
        var application = read(scope, planId, sourcePath, nodeKey);
        if (application == null) throw new IllegalArgumentException("找不到原概览公开回执");
        var menu = menus.lockFieldConfiguration(scope.siteId(), application.menuId());
        requireScope(scope, menu);
        if (Objects.equals(menu.getListPageTemplate(), application.previousTemplate()))
            return result(application, menu, false);
        if (!Objects.equals(menu.getListPageTemplate(), application.templatePath())
                || !Objects.equals(menu.getListPageUrl(), application.routeUrl()))
            throw new IllegalArgumentException("概览路由或模板已有人工修改，不能自动撤回");
        verifyTemplate(scope.code(), application);
        if (menus.updatePublicListBindingTemplate(scope.siteId(), application.menuId(), application.templatePath(),
                application.previousTemplate()) != 1)
            throw new IllegalArgumentException("概览模板并发变化，未撤回");
        return new PublicListApplicationService.Result(false, application.templatePath(), application.routeUrl(), true,
                "概览已撤回，请重载路由并清理本次新增静态路径");
    }

    void validateRuntime(SiteModelNavigationService.Scope scope, PublicBindingApplication application, String html) {
        var receipt = application.navigationLanding();
        var saved = receipt.preview();
        var current = navigation.previewLanding(scope, receipt.planId(), saved.sourcePath(), saved.nodeKey(),
                saved.containerSelector(), false);
        if (!saved.sameSources(current)) throw new IllegalArgumentException("概览来源或栏目身份已漂移，请重新审核");
        validateCards(html, saved);
        var menu = menus.selectPublicBindingMenu(scope.siteId(), application.menuId());
        requireScope(scope, menu);
        requireRoute(scope, menu);
    }

    static String compile(String html, PublicNavigationLinkCompiler.LandingPreview preview) {
        var compiled = new TextBindingCompiler().compile(html, List.of(), List.of(), 1);
        var document = Jsoup.parse(compiled.html());
        document.outputSettings().prettyPrint(false);
        document.select("script").remove();
        for (var element : document.getAllElements())
            for (var attribute : List.copyOf(element.attributes().asList()))
                if (attribute.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("on"))
                    element.removeAttr(attribute.getKey());
        for (var card : preview.cards()) {
            var anchors = document.select(card.selector());
            if (anchors.size() != 1 || !Objects.equals(card.rawHref(), anchors.first().attr("href")))
                throw new IllegalArgumentException("概览原入口定位或地址已变化");
            var anchor = anchors.first();
            anchor.removeAttr("target").removeAttr("download").removeAttr("ping");
            if (card.state() == PublicNavigationLinkCompiler.LandingTargetState.READY) {
                anchor.attr("href", card.publicUrl()).removeAttr("aria-disabled").removeAttr("tabindex");
            } else if (card.state() == PublicNavigationLinkCompiler.LandingTargetState.DISPLAY_ONLY) {
                anchor.removeAttr("href").removeAttr("aria-disabled").removeAttr("tabindex");
            } else {
                anchor.removeAttr("href").attr("aria-disabled", "true").attr("tabindex", "-1");
            }
        }
        String result = document.outerHtml();
        validateCards(result, preview);
        return result;
    }

    static void validateCards(String html, PublicNavigationLinkCompiler.LandingPreview preview) {
        // 概览没有记录字段或查询表达式，完整沿原源模板语法检查拒绝任何动态指令。
        new TextBindingCompiler().compile(html, List.of(), List.of(), 1);
        var document = Jsoup.parse(html);
        if (!document.select("script").isEmpty()
                || document.getAllElements().stream().anyMatch(element -> element.attributes().asList().stream()
                        .anyMatch(attribute -> attribute.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("on"))))
            throw new IllegalArgumentException("概览模板含未审核执行属性");
        var containers = document.select(preview.containerSelector());
        if (containers.size() != 1) throw new IllegalArgumentException("概览列表位置不唯一或已变化");
        var container = containers.first();
        if (container.childrenSize() != preview.cards().size())
            throw new IllegalArgumentException("概览卡片数量已变化");
        for (int i = 0; i < preview.cards().size(); i++) {
            var card = preview.cards().get(i);
            var anchors = document.select(card.selector());
            if (anchors.size() != 1 || !anchors.first().is("a") || anchors.first().hasAttr("target")
                    || anchors.first().hasAttr("download") || anchors.first().hasAttr("ping")
                    || !container.child(i).getAllElements().contains(anchors.first()))
                throw new IllegalArgumentException("概览入口超出已审核卡片");
            var anchor = anchors.first();
            if (card.state() == PublicNavigationLinkCompiler.LandingTargetState.READY) {
                if (!Objects.equals(card.publicUrl(), anchor.attr("href")))
                    throw new IllegalArgumentException("概览真实入口已变化");
            } else if (card.state() == PublicNavigationLinkCompiler.LandingTargetState.DISPLAY_ONLY) {
                if (anchor.hasAttr("href") || anchor.hasAttr("aria-disabled") || anchor.hasAttr("tabindex"))
                    throw new IllegalArgumentException("纯展示卡片仍含入口属性");
            } else if (anchor.hasAttr("href") || !"true".equals(anchor.attr("aria-disabled"))
                    || !"-1".equals(anchor.attr("tabindex")))
                throw new IllegalArgumentException("不可用栏目仍含可操作入口");
        }
    }

    private PublicBindingApplication read(SiteModelNavigationService.Scope scope, String planId, String sourcePath,
            String nodeKey) {
        var application = PublicBindingApplication.read(root, scope.code(), key(scope, planId, sourcePath, nodeKey));
        if (application != null && (!Objects.equals(scope.siteId(), application.siteId())
                || !Objects.equals(scope.packageVersion(), application.packageVersion())
                || !Objects.equals(planId, application.planId()) || application.navigationLanding() == null
                || !Objects.equals(sourcePath, application.navigationLanding().preview().sourcePath())
                || !Objects.equals(nodeKey, application.navigationLanding().preview().nodeKey())))
            throw new IllegalArgumentException("原概览回执与当前范围不一致");
        return application;
    }

    private static String key(SiteModelNavigationService.Scope scope, String planId, String sourcePath, String nodeKey) {
        if (planId == null || sourcePath == null || nodeKey == null) throw new IllegalArgumentException("概览身份不完整");
        return UUID.nameUUIDFromBytes(("landing\n" + scope.siteId() + "\n" + scope.packageVersion() + "\n"
                + planId + "\n" + sourcePath + "\n" + nodeKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void requireScope(SiteModelNavigationService.Scope scope, OneshotEntity menu) {
        var project = projects.selectById(scope.siteId());
        if (project == null || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(scope.code(), project.getProjectCode())
                || !Objects.equals(scope.packageVersion(), project.getUiPackageVersion())
                || !Objects.equals(scope.rootMenuId(), project.getRootMenuId()) || menu == null
                || !Objects.equals(scope.siteId(), menu.getSiteProjectId())
                || !Objects.equals(scope.rootMenuId(), menu.getSiteRootId())
                || Objects.equals(scope.rootMenuId(), menu.getId()) || !Objects.equals(menu.getPageType(), 1)
                || Boolean.TRUE.equals(menu.getIsDeleted()))
            throw new IllegalArgumentException("概览父栏目或站点身份无效");
    }

    private void requireRoute(SiteModelNavigationService.Scope scope, OneshotEntity menu) {
        String url = menu.getListPageUrl();
        if (url == null || !url.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.html")
                || PublicBindingRootRoutes.indexAlias(url))
            throw new IllegalArgumentException("概览必须使用独立固定本地HTML路由");
        for (var handler : routes.getHandlers(scope.code()))
            if (handler.matches(url.substring(1))) throw new IllegalArgumentException("概览路径与自定义路由冲突");
        for (var other : Optional.ofNullable(menus.selectListBySiteProjectId(scope.siteId())).orElse(List.of()))
            for (String pattern : java.util.Arrays.asList(other.getListPageUrl(), other.getDetailPageUrl())) {
                if (pattern == null || pattern.isBlank() || Objects.equals(other.getId(), menu.getId())
                        && Objects.equals(pattern, menu.getListPageUrl())) continue;
                String expanded = pattern.replace("{classId}", String.valueOf(other.getId()))
                        .replace("{parentId}", String.valueOf(other.getParentId()))
                        .replace("${className}", Objects.toString(other.getClassName(), ""));
                if (new RouteHandlerEntry(expanded, null).matches(url.substring(1)))
                    throw new IllegalArgumentException("概览路径与现有栏目或详情路由冲突");
            }
    }

    private String verifyTemplate(String code, PublicBindingApplication application) {
        var directory = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
        var path = directory.resolve(application.templatePath()).normalize();
        try {
            for (var parent = path; parent != null && parent.startsWith(directory); parent = parent.getParent())
                if (Files.isSymbolicLink(parent)) throw new IllegalArgumentException("概览模板路径不能含链接");
            if (!path.startsWith(directory) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 2 * 1024 * 1024 || !path.toRealPath().startsWith(directory.toRealPath()))
                throw new IllegalArgumentException("概览模板路径无效");
            String html = Files.readString(path);
            if (!Objects.equals(PublicBindingApplication.digest(html), application.templateDigest()))
                throw new IllegalArgumentException("概览模板已变化");
            return html;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("概览模板不可读取", error);
        }
    }

    private static PublicListApplicationService.Result result(PublicBindingApplication application,
            OneshotEntity menu, boolean reload) {
        return new PublicListApplicationService.Result(Objects.equals(menu.getListPageTemplate(), application.templatePath()),
                application.templatePath(), application.routeUrl(), reload, "请核对概览公开模板与实际页面");
    }

    private static void transaction(boolean confirmed) {
        if (!confirmed || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("请通过CMS事务入口明确确认概览应用或撤回");
    }
}

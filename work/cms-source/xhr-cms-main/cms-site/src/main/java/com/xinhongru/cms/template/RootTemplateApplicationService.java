package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.*;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 首页只接已应用的公开单页；根栏目是路由身份，不初始化根CMS值。 */
@Service
public class RootTemplateApplicationService {
    public record Options(
            List<String> availableAliases,
            List<String> activeAliases,
            boolean applied,
            String activePlanId,
            String message) {}

    public record Result(
            boolean applied,
            String templatePath,
            List<String> publicUrls,
            boolean requiresRouteReload,
            String message) {}

    public record SwitchOptions(
            String activePlanId,
            String activeTemplatePath,
            String sourceTemplatePath,
            List<String> publicUrls,
            boolean canSwitch,
            boolean canRollback,
            String message) {}

    public enum BannerState {
        READY, APPLIED, OTHER_ACTIVE, REVIEW_REQUIRED, SOURCE_CHANGED, NOT_INITIALIZED,
        ROOT_NOT_PUBLIC, TARGET_NOT_PUBLIC, BLOCKED
    }

    public record BannerOptions(List<String> availableAliases, List<String> activeAliases,
                                boolean applied, String activePlanId, boolean ready,
                                BannerState state, Long publishedRecords, int maxVisibleRecords, String message) {}

    private final TemplateFieldPlanService plans;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final UiPackageStorageService packages;
    private final TemplateStorageService templates;
    private final SiteRouteRegistry routes;
    private final ContentLinkService links;
    private final Path root;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicNavigationLinkCompiler navigationLinks;
    @org.springframework.beans.factory.annotation.Autowired
    private HomeBannerBinding banners;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSharedContentCompiler sharedContent;

    public RootTemplateApplicationService(
            TemplateFieldPlanService plans,
            OneShotMapper menus,
            SiteProjectMapper projects,
            UiPackageStorageService packages,
            TemplateStorageService templates,
            SiteRouteRegistry routes,
            ContentLinkService links,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.plans = plans;
        this.menus = menus;
        this.projects = projects;
        this.packages = packages;
        this.templates = templates;
        this.routes = routes;
        this.links = links;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Options options(Long siteId, String code, String version, String planId) {
        var project = project(siteId, code, version, true);
        var plan = rootSource(plans.read(siteId, code, version, planId), project);
        var route = menus.selectPublicBindingMenu(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        var active = current(code, route);
        return new Options(
                available(code, version),
                active == null ? List.of() : active.routeAliases(),
                active != null && Objects.equals(active.planId(), planId),
                active == null ? null : active.planId(),
                active == null ? "尚未接入首页；先完成非根单页公开应用" : "当前首页来源计划：" + active.planId());
    }

    @Transactional
    public Result apply(
            Long siteId,
            String code,
            String version,
            String planId,
            List<String> aliases,
            boolean confirmed) {
        transaction(confirmed);
        var project = project(siteId, code, version, true, true);
        var plan = rootSource(plans.read(siteId, code, version, planId), project);
        // 同站路由修改先锁站点，再按根栏目、数据栏目顺序锁定。
        var route = menus.lockFieldConfiguration(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        if (!Boolean.TRUE.equals(route.getHasFrontChannel()))
            throw new IllegalArgumentException("请先通过原配置明确开放根路由前台");
        var owner = menus.lockFieldConfiguration(siteId, plan.menuId());
        var source = owner == null ? null
                : PublicBindingApplication.atPath(root, code, owner.getListPageTemplate());
        if (source == null || source.routeMenuId() != null || source.pageType() != 0
                || !Objects.equals(source.planId(), planId)
                || !Objects.equals(source.siteId(), siteId)
                || !Objects.equals(source.menuId(), plan.menuId())
                || !Objects.equals(source.packageVersion(), version)
                || !Objects.equals(owner.getSiteProjectId(), siteId)
                || !Objects.equals(owner.getId().longValue(), plan.menuId())
                || !Objects.equals(owner.getSiteRootId(), route.getId())
                || !Objects.equals(source.routeUrl(), owner.getListPageUrl())
                || !Objects.equals(source.fieldConfigDigest(), ContentFieldConfigDigest.of(owner))
                || !Boolean.TRUE.equals(owner.getHasFrontChannel())
                || !Integer.valueOf(3).equals(owner.getStatus()))
            throw new IllegalArgumentException("请先完成并核对当前单页公开应用，再接入首页");
        String html = readHtml(code, source);
        var selected = selected(code, version, aliases);
        conflicts(siteId, code, route, selected);
        String baseKey = key(siteId, route.getId(), TemplateFieldPlanService.applicationId(plan));
        String key = baseKey;
        String previous = route.getListPageTemplate();
        var active = current(code, route);
        var existing = active == null ? PublicBindingApplication.read(root, code, baseKey) : active;
        if (existing != null
                && (!Objects.equals(existing.planId(), planId)
                        || !Objects.equals(existing.menuId(), plan.menuId())
                        || !Objects.equals(existing.routeMenuId(), route.getId().longValue())
                        || !Objects.equals(existing.siteId(), siteId)
                        || !Objects.equals(existing.packageVersion(), version)
                        || existing.homeBanner() != null))
            throw new IllegalArgumentException("已有其他首页应用，请读取原计划明确撤回");
        if (existing != null && !Objects.equals(existing.fieldConfigDigest(), source.fieldConfigDigest()))
            throw new IllegalArgumentException("首页数据字段配置已有变更，请核对原应用");
        if (existing != null && !Objects.equals(existing.routeUrl(), route.getListPageUrl()))
            throw new IllegalArgumentException("当前首页入口配置不同，请先撤回后重新确认");
        if (active != null && !Objects.equals(existing.routeAliases(), selected))
            throw new IllegalArgumentException("当前首页入口配置不同，请先撤回后重新确认");
        if (active != null && Objects.equals(existing.templateDigest(), source.templateDigest())) {
            readHtml(code, existing);
            return result(existing, route, true, "首页模板已保存；请核对运行路由与实际公开URL");
        }
        if (active == null && existing != null && !Objects.equals(previous, existing.previousTemplate()))
            throw new IllegalArgumentException("根模板存在人工变更，不能覆盖");
        if (existing != null && !Objects.equals(existing.templateDigest(), source.templateDigest())) {
            if (existing.previousActiveTemplate() != null)
                throw new IllegalArgumentException("请通过首页版本切换更新，保留可恢复指针");
            boolean withShared = !source.sharedContent().isEmpty() || !existing.sharedContent().isEmpty();
            boolean withNavigation = navigationLinks != null
                    && navigationLinks.hasConfirmation(siteId, code, version, plan.sourcePath());
            String oldHtml = readHtml(code, existing);
            if ((!withShared && !withNavigation
                            && !PublicBindingResources.onlyResourceLinkPathsChanged(
                                    oldHtml, html, plan.sourcePath()))
                    || !Objects.equals(existing.initializedRecordId(), source.initializedRecordId())
                    || !Objects.equals(existing.detailCollections(), source.detailCollections())
                    || !Objects.equals(existing.routeAliases(), selected))
                throw new IllegalArgumentException("原首页应用与当前导航版本不一致");
            PublicSharedContentCompiler.verifyReapplication(
                    DetailCollections.withoutInstructions(oldHtml, existing.detailCollections()),
                    existing.sharedContent(),
                    DetailCollections.withoutInstructions(html, source.detailCollections()),
                    source.sharedContent(),
                    false,
                    plan.sourcePath(),
                    withNavigation
                            ? navigationLinks.reapplicationComparison(
                                    siteId, code, version, plan.sourcePath())
                            : java.util.function.UnaryOperator.identity());
            key = key(siteId, route.getId(), source.key());
        }
        String path = "cms-applied/" + key + "/" + source.templateDigest() + ".html";
        var application =
                new PublicBindingApplication(
                        key,
                        siteId,
                        plan.menuId(),
                        planId,
                        version,
                        0,
                        source.fieldConfigDigest(),
                        source.bindings(),
                        path,
                        source.templateDigest(),
                        existing == null ? previous : existing.previousTemplate(),
                        route.getListPageUrl(),
                        null,
                        route.getId().longValue(),
                        selected, null, source.sharedContent(), List.of(), source.detailCollections(), null, null, null);
        Path file = templates.resolveTemplateDir(code).resolve(path);
        try {
            if (Files.exists(file)) readHtml(code, application);
            else TemplateFileWriter.write(templates.resolveTemplateDir(code), path, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("首页模板不可保存", error);
        }
        application.write(root, code);
        if (menus.updatePublicBindingTemplate(siteId, route.getId().longValue(), 0, previous, path)
                != 1) throw new IllegalArgumentException("根模板并发变化，首页应用未完成");
        return result(application, route, true, "首页模板已保存；请重载现有站点路由，再逐一读取公开URL确认");
    }

    public SwitchOptions switchOptions(Long siteId, String code, String version, String planId) {
        var project = project(siteId, code, version, true);
        var plan = rootSource(plans.readForWithdrawal(siteId, code, version, planId), project);
        var route = menus.selectPublicBindingMenu(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        var active = current(code, route);
        var owner = menus.selectPublicBindingMenu(siteId, plan.menuId());
        boolean canRollback = false;
        if (active != null && Objects.equals(active.planId(), planId)
                && active.previousActiveTemplate() != null) {
            try {
                rollbackTarget(project, plan, route, owner, active);
                canRollback = true;
            } catch (IllegalArgumentException error) {
                // An invalid rollback receipt must not authorize a switch or a restore.
                return switchOptions(route, active, null, false, error.getMessage());
            }
        }
        try {
            var currentPlan = plans.read(siteId, code, version, planId);
            var source = switchSource(project, currentPlan, route, owner, active);
            return switchOptions(route, active, source, canRollback,
                    canRollback ? "可恢复切换前的公开首页" : "可切换至已应用的单页模板");
        } catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException error) {
            return switchOptions(route, active, null, canRollback,
                    canRollback ? "可恢复切换前的公开首页"
                            : error instanceof org.springframework.web.server.ResponseStatusException status
                                    ? status.getReason() : error.getMessage());
        }
    }

    private SwitchOptions switchOptions(OneshotEntity route, PublicBindingApplication active,
            PublicBindingApplication source, boolean canRollback, String message) {
        return new SwitchOptions(
                active == null ? null : active.planId(),
                route.getListPageTemplate(),
                source == null ? null : source.templatePath(),
                active == null ? List.of() : result(active, route, true, message).publicUrls(),
                source != null,
                canRollback,
                message);
    }

    @Transactional
    public Result switchVersion(Long siteId, String code, String version, String planId,
            String expectedActive, String expectedSource, boolean confirmed) {
        transaction(confirmed);
        var project = project(siteId, code, version, true, true);
        var plan = rootSource(plans.read(siteId, code, version, planId), project);
        var route = menus.lockFieldConfiguration(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        requirePointer(expectedActive, route.getListPageTemplate(), "首页");
        var owner = menus.lockFieldConfiguration(siteId, plan.menuId());
        requirePointer(expectedSource, owner == null ? null : owner.getListPageTemplate(), "维护页");
        var active = current(code, route);
        var source = switchSource(project, plan, route, owner, active);
        String html = readHtml(code, source);
        String key = key(siteId, route.getId(), "SWITCH\n" + active.templatePath() + "\n"
                + source.templatePath());
        String path = "cms-applied/" + key + "/" + source.templateDigest() + ".html";
        var candidate = new PublicBindingApplication(
                key, siteId, plan.menuId(), planId, version, 0, source.fieldConfigDigest(),
                source.bindings(), path, source.templateDigest(), active.previousTemplate(),
                route.getListPageUrl(), null, route.getId().longValue(), active.routeAliases(),
                null, source.sharedContent(), List.of(), source.detailCollections(), null, null,
                null, active.templatePath());
        var recorded = PublicBindingApplication.read(root, code, key);
        if (recorded != null && !Objects.equals(recorded, candidate))
            throw new IllegalArgumentException("该首页版本的切换凭据不同，不能覆盖");
        try {
            if (Files.exists(templates.resolveTemplateDir(code).resolve(path))) readHtml(code, candidate);
            else TemplateFileWriter.write(templates.resolveTemplateDir(code), path, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("首页切换模板不可保存", error);
        }
        if (recorded == null) candidate.write(root, code);
        updatePointer(siteId, route, expectedActive, path);
        return result(candidate, route, true, "已切换首页模板；请重载路由并核对公开入口");
    }

    @Transactional
    public Result rollbackSwitch(Long siteId, String code, String version, String planId,
            String expectedActive, boolean confirmed) {
        transaction(confirmed);
        var project = project(siteId, code, version, true, true);
        var plan = rootSource(plans.readForWithdrawal(siteId, code, version, planId), project);
        var route = menus.lockFieldConfiguration(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        requirePointer(expectedActive, route.getListPageTemplate(), "首页");
        var owner = menus.lockFieldConfiguration(siteId, plan.menuId());
        var previous = rollbackTarget(project, plan, route, owner, current(code, route));
        updatePointer(siteId, route, expectedActive, previous.templatePath());
        return result(previous, route, true, "已恢复切换前的首页模板；请重载路由并核对公开入口");
    }

    private PublicBindingApplication switchSource(SiteProjectEntity project,
            TemplateFieldPlanService.Plan plan, OneshotEntity route, OneshotEntity owner,
            PublicBindingApplication active) {
        validateSwitchRoot(project, plan, route, owner, active);
        var originalPlan = historicalSwitchPlan(project, plan, active);
        var source = PublicBindingApplication.atPath(root, project.getProjectCode(),
                owner.getListPageTemplate());
        if (source == null || source.routeMenuId() != null || source.pageType() != 0
                || !Objects.equals(source.planId(), plan.id())
                || !Objects.equals(source.siteId(), project.getId())
                || !Objects.equals(source.menuId(), plan.menuId())
                || !Objects.equals(source.packageVersion(), plan.packageVersion())
                || !Objects.equals(source.routeUrl(), owner.getListPageUrl())
                || !Objects.equals(source.fieldConfigDigest(), active.fieldConfigDigest())
                || !Objects.equals(originalPlan.sourcePath(), plan.sourcePath()))
            throw new IllegalArgumentException("请先核对并公开应用同一维护页的新版本");
        if (Objects.equals(active.templateDigest(), source.templateDigest())
                && Objects.equals(active.planId(), source.planId()))
            throw new IllegalArgumentException("当前首页已使用该版本");
        readHtml(project.getProjectCode(), source);
        return source;
    }

    private PublicBindingApplication rollbackTarget(SiteProjectEntity project,
            TemplateFieldPlanService.Plan plan, OneshotEntity route, OneshotEntity owner,
            PublicBindingApplication active) {
        validateSwitchRoot(project, plan, route, owner, active);
        if (!Objects.equals(active.planId(), plan.id()) || active.previousActiveTemplate() == null)
            throw new IllegalArgumentException("当前首页没有该计划的可恢复切换");
        var previous = PublicBindingApplication.atPath(root, project.getProjectCode(),
                active.previousActiveTemplate());
        validateSwitchRoot(project, plan, route, owner, previous);
        if (Objects.equals(previous.templatePath(), active.templatePath())
                || !Objects.equals(previous.previousTemplate(), active.previousTemplate())
                || !Objects.equals(historicalSwitchPlan(project, plan, previous).sourcePath(),
                        plan.sourcePath()))
            throw new IllegalArgumentException("切换前的首页版本与当前站点范围不一致");
        return previous;
    }

    private TemplateFieldPlanService.Plan historicalSwitchPlan(SiteProjectEntity project,
            TemplateFieldPlanService.Plan plan, PublicBindingApplication application) {
        var saved = rootSource(plans.readForWithdrawal(project.getId(), project.getProjectCode(),
                plan.packageVersion(), application.planId()), project);
        if (!Objects.equals(saved.menuId(), plan.menuId()))
            throw new IllegalArgumentException("原首页计划与维护栏目不一致");
        return saved;
    }

    private void validateSwitchRoot(SiteProjectEntity project, TemplateFieldPlanService.Plan plan,
            OneshotEntity route, OneshotEntity owner, PublicBindingApplication application) {
        if (owner == null
                || !Objects.equals(owner.getId().longValue(), plan.menuId())
                || !Objects.equals(owner.getSiteProjectId(), project.getId())
                || !Objects.equals(owner.getSiteRootId(), route.getId())
                || !Integer.valueOf(0).equals(owner.getPageType())
                || Boolean.TRUE.equals(owner.getIsDeleted())
                || !Boolean.TRUE.equals(owner.getHasFrontChannel())
                || !Integer.valueOf(3).equals(owner.getStatus())
                || !Boolean.TRUE.equals(route.getHasFrontChannel())
                || application == null || application.pageType() != 0 || application.homeBanner() != null
                || !Objects.equals(application.siteId(), project.getId())
                || !Objects.equals(application.menuId(), plan.menuId())
                || !Objects.equals(application.routeMenuId(), route.getId().longValue())
                || !Objects.equals(application.packageVersion(), plan.packageVersion())
                || !Objects.equals(application.routeUrl(), route.getListPageUrl())
                || !Objects.equals(application.routeAliases(), selected(project.getProjectCode(),
                        plan.packageVersion(), application.routeAliases()))
                || !Objects.equals(application.fieldConfigDigest(), ContentFieldConfigDigest.of(owner)))
            throw new IllegalArgumentException("根首页、维护栏目、包版本或公开路由已变化");
        conflicts(project.getId(), project.getProjectCode(), route, application.routeAliases());
        readHtml(project.getProjectCode(), application);
    }

    private static void requirePointer(String expected, String actual, String label) {
        if (expected == null || !Objects.equals(expected, actual))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, label + "版本已变化，请重新读取");
    }

    private void updatePointer(Long siteId, OneshotEntity route, String expected, String target) {
        if (menus.updatePublicBindingTemplate(siteId, route.getId().longValue(), 0, expected, target) != 1)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "根模板并发变化，首页切换未完成");
    }
    @Transactional
    public Result withdraw(
            Long siteId, String code, String version, String planId, boolean confirmed) {
        transaction(confirmed);
        var project = project(siteId, code, version, false, true);
        var plan = rootSource(plans.readForWithdrawal(siteId, code, version, planId), project);
        var route = menus.lockFieldConfiguration(siteId, project.getRootMenuId().longValue());
        scope(project, route);
        var application = current(code, route);
        if (application == null) {
            application = PublicBindingApplication.read(root, code,
                    key(siteId, route.getId(), TemplateFieldPlanService.applicationId(plan)));
        }
        if (application == null
                || !Objects.equals(application.planId(), planId)
                || !Objects.equals(application.siteId(), siteId)
                || !Objects.equals(application.routeMenuId(), route.getId().longValue())
                || !Objects.equals(application.menuId(), plan.menuId()))
            throw new IllegalArgumentException("原首页应用不存在或身份不同");
        if (Objects.equals(route.getListPageTemplate(), application.previousTemplate()))
            return result(application, route, false, "已撤回首页应用；请核对运行路由");
        if (!Objects.equals(route.getListPageTemplate(), application.templatePath())
                || !Objects.equals(route.getListPageUrl(), application.routeUrl()))
            throw new IllegalArgumentException("根模板或原路由已有人工变更，不能自动撤回");
        readHtml(code, application);
        if (menus.updatePublicBindingTemplate(
                        siteId,
                        route.getId().longValue(),
                        0,
                        application.templatePath(),
                        application.previousTemplate())
                != 1) throw new IllegalArgumentException("根模板并发变化，撤回未完成");
        return result(application, route, false, "已恢复原根模板指针；请重载现有路由，数据栏目值与发布状态保留");
    }

    public BannerOptions bannerOptions(Long site, String code, String version, String planId,
                                       long revision, String path, String regionId) {
        var project = project(site, code, version, true);
        var route = menus.selectPublicBindingMenu(site, project.getRootMenuId().longValue());
        scope(project, route);
        var active = current(code, route);
        boolean applied = active != null && active.homeBanner() != null
                && Objects.equals(active.planId(), planId)
                && Objects.equals(active.homeBanner().regionId(), regionId);
        String message = "轮播可生成首页模板；公开显示取决于栏目记录的发布状态和有效期";
        BannerState state;
        Long published = null;
        try {
            var scope = bannerScope(project, version);
            var prepared = banners.prepare(scope, planId, revision, path, regionId);
            var menu = menus.selectPublicBindingMenu(site, prepared.menuId());
            banners.compile(scope, prepared, menu);
            banners.requireSeeded(scope, planId, revision, prepared);
            published = banners.countPublished(site, menu);
            if (active != null && !applied) {
                state = BannerState.OTHER_ACTIVE;
                message = "请先明确撤回当前使用的首页应用";
            } else if (!Boolean.TRUE.equals(route.getHasFrontChannel())) {
                state = BannerState.ROOT_NOT_PUBLIC;
                message = "请先通过原配置明确开放根路由前台";
            } else if (!Boolean.TRUE.equals(menu.getHasFrontChannel())) {
                state = BannerState.TARGET_NOT_PUBLIC;
                message = "请先通过原配置明确开放轮播栏目前台内容";
            } else state = applied ? BannerState.APPLIED : BannerState.READY;
        } catch (HomeBannerBinding.Pending error) {
            state = error.state;
            message = error.getMessage();
        } catch (org.springframework.web.server.ResponseStatusException error) {
            if (error.getStatusCode().value() != 409) throw error;
            state = BannerState.REVIEW_REQUIRED;
            message = error.getReason();
        } catch (IllegalArgumentException error) {
            state = BannerState.BLOCKED;
            message = error.getMessage();
        }
        boolean ready = state == BannerState.READY || state == BannerState.APPLIED;
        return new BannerOptions(available(code, version), active == null ? List.of() : active.routeAliases(),
                applied, active == null ? null : active.planId(), ready, state, published,
                HomeBannerBinding.MAX_ROWS, message);
    }

    @Transactional
    public Result applyBanner(Long site, String code, String version, String planId, long revision,
                              String path, String regionId, List<String> aliases, boolean confirmed) {
        transaction(confirmed);
        var project = project(site, code, version, true);
        var bannerScope = bannerScope(project, version);
        var prepared = banners.prepare(bannerScope, planId, revision, path, regionId);
        var route = menus.lockFieldConfiguration(site, project.getRootMenuId().longValue());
        scope(project, route);
        if (!Boolean.TRUE.equals(route.getHasFrontChannel()))
            throw new IllegalArgumentException("请先通过原配置明确开放根路由前台");
        var menu = menus.lockFieldConfiguration(site, prepared.menuId());
        var compiled = banners.compile(bannerScope, prepared, menu);
        banners.requireSeeded(bannerScope, planId, revision, prepared);
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("请先通过原配置明确开放轮播栏目前台内容");
        var aliasesChecked = selected(code, version, aliases);
        conflicts(site, code, route, aliasesChecked);
        var html = PublicBindingResources.rebase(compiled.html(), path);
        if (navigationLinks.hasConfirmation(site, code, version, path))
            html = navigationLinks.bind(site, code, version, path, html, route.getId().longValue());
        var shared = sharedContent.compile(site, code, version, path, html);
        html = shared.html();
        validateBanner(html, compiled.receipt(), shared.references());
        var previous = route.getListPageTemplate();
        var key = key(site, route.getId(), planId + "-banner-" + regionId);
        var digest = PublicBindingApplication.digest(html);
        var templatePath = "cms-applied/" + key + "/" + digest + ".html";
        var existing = PublicBindingApplication.read(root, code, key);
        if (existing != null && (!Objects.equals(existing.planId(), planId)
                || !Objects.equals(existing.siteId(), site) || !Objects.equals(existing.packageVersion(), version)
                || !Objects.equals(existing.menuId(), prepared.menuId())
                || !Objects.equals(existing.routeMenuId(), route.getId().longValue())))
            throw new IllegalArgumentException("首页轮播应用凭据与当前栏目不一致");
        var fieldDigest = com.xinhongru.content.model.ContentFieldConfigDigest.of(menu);
        if (PublicBindingApplication.managed(previous)) {
            if (existing == null || !Objects.equals(previous, templatePath)
                    || !Objects.equals(existing.templatePath(), templatePath)
                    || !Objects.equals(existing.routeUrl(), route.getListPageUrl())
                    || !Objects.equals(existing.fieldConfigDigest(), fieldDigest)
                    || !Objects.equals(existing.homeBanner(), compiled.receipt())
                    || !Objects.equals(existing.routeAliases(), aliasesChecked))
                throw new IllegalArgumentException("已有首页应用，请明确撤回后再确认新版本");
            readHtml(code, existing);
            return result(existing, route, true, "首页轮播已保存；请核对公开URL");
        }
        if (existing != null && !Objects.equals(previous, existing.previousTemplate()))
            throw new IllegalArgumentException("根模板存在人工变更，不能覆盖");
        var application = new PublicBindingApplication(key, site, prepared.menuId(), planId, version, 1,
                fieldDigest, List.of(), templatePath, digest, previous, route.getListPageUrl(), null,
                route.getId().longValue(), aliasesChecked, null, shared.references(), List.of(), List.of(),
                compiled.receipt());
        Path file = templates.resolveTemplateDir(code).resolve(templatePath);
        try {
            if (Files.exists(file)) readHtml(code, application);
            else TemplateFileWriter.write(templates.resolveTemplateDir(code), templatePath, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("首页轮播模板不可保存", error);
        }
        application.write(root, code);
        if (menus.updatePublicBindingTemplate(site, route.getId().longValue(), 0, previous, templatePath) != 1)
            throw new IllegalArgumentException("根模板并发变化，首页轮播应用未完成");
        return result(application, route, true, "首页轮播模板已保存；请重载路由并核对公开URL");
    }

    @Transactional
    public Result withdrawBanner(Long site, String code, String version, String planId,
                                 String regionId, boolean confirmed) {
        transaction(confirmed);
        if (regionId == null || !regionId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("请选择原首页轮播区域");
        var project = project(site, code, version, false);
        var route = menus.lockFieldConfiguration(site, project.getRootMenuId().longValue());
        scope(project, route);
        var application = PublicBindingApplication.read(root, code,
                key(site, route.getId(), planId + "-banner-" + regionId));
        if (application == null || application.homeBanner() == null
                || !Objects.equals(application.planId(), planId)
                || !Objects.equals(application.packageVersion(), version)
                || !Objects.equals(application.siteId(), site)
                || !Objects.equals(application.routeMenuId(), route.getId().longValue())
                || !Objects.equals(application.homeBanner().regionId(), regionId))
            throw new IllegalArgumentException("原首页轮播应用不存在或身份不同");
        if (Objects.equals(route.getListPageTemplate(), application.previousTemplate()))
            return result(application, route, false, "已撤回首页轮播应用；请核对运行路由");
        if (!Objects.equals(route.getListPageTemplate(), application.templatePath())
                || !Objects.equals(route.getListPageUrl(), application.routeUrl()))
            throw new IllegalArgumentException("根模板或原路由已有人工变更，不能自动撤回");
        readHtml(code, application);
        if (menus.updatePublicBindingTemplate(site, route.getId().longValue(), 0,
                application.templatePath(), application.previousTemplate()) != 1)
            throw new IllegalArgumentException("根模板并发变化，撤回未完成");
        return result(application, route, false, "已恢复原根模板指针；请重载既有路由");
    }

    private static SiteModelRecordSeedService.Scope bannerScope(SiteProjectEntity project, String version) {
        return new SiteModelRecordSeedService.Scope(project.getId(), project.getRootMenuId(),
                project.getProjectCode(), version);
    }

    static void validateBanner(String html, HomeBannerBinding.Receipt banner,
                               List<PublicSharedContentCompiler.Reference> shared) {
        var stripped = HomeBannerBinding.withoutInstructions(
                PublicSharedContentCompiler.withoutInstructions(html, shared), banner);
        new TextBindingCompiler().compile(stripped, List.of(), List.of(), 0);
    }

    private SiteProjectEntity project(
            Long siteId, String code, String version, boolean currentVersion) {
        return project(siteId, code, version, currentVersion, false);
    }

    private SiteProjectEntity project(
            Long siteId, String code, String version, boolean currentVersion, boolean lock) {
        var project = lock ? projects.selectByIdForUpdate(siteId) : projects.selectById(siteId);
        if (project == null
                || !Objects.equals(project.getId(), siteId)
                || !Objects.equals(project.getProjectCode(), code)
                || project.getRootMenuId() == null
                || currentVersion && !Objects.equals(project.getUiPackageVersion(), version))
            throw new IllegalArgumentException("当前站点根身份或包版本无效");
        return project;
    }

    private static TemplateFieldPlanService.Plan rootSource(
            TemplateFieldPlanService.Plan plan, SiteProjectEntity project) {
        if (plan.pageType() != 0
                || Objects.equals(plan.menuId(), project.getRootMenuId().longValue()))
            throw new IllegalArgumentException("首页须选已应用的非根单页；不能初始化根栏目或引用详情");
        return plan;
    }

    private static void scope(SiteProjectEntity project, OneshotEntity route) {
        if (route == null
                || !Objects.equals(route.getId(), project.getRootMenuId())
                || !Objects.equals(route.getSiteRootId(), route.getId())
                || !Objects.equals(route.getSiteProjectId(), project.getId())
                || !Integer.valueOf(0).equals(route.getPageType())
                || Boolean.TRUE.equals(route.getIsDeleted()))
            throw new IllegalArgumentException("真实根栏目不存在或身份已变化");
    }

    private PublicBindingApplication current(String code, OneshotEntity route) {
        if (!PublicBindingApplication.managed(route.getListPageTemplate())) return null;
        var application =
                PublicBindingApplication.read(
                        root, code, PublicBindingApplication.keyFrom(route.getListPageTemplate()));
        if (application == null
                || !Objects.equals(application.routeMenuId(), route.getId().longValue())
                || !Objects.equals(application.siteId(), route.getSiteProjectId())
                || !Objects.equals(application.templatePath(), route.getListPageTemplate()))
            throw new IllegalArgumentException("当前根应用凭据不同，请明确恢复或撤回");
        return application;
    }

    private List<String> available(String code, String version) {
        return packages.listSeedPages(code, version).stream()
                .map(file -> "/" + file.relativePath())
                .filter(PublicBindingRootRoutes::indexAlias)
                .sorted()
                .limit(9)
                .toList();
    }

    private List<String> selected(String code, String version, List<String> requested) {
        var available = available(code, version);
        if (requested == null
                || requested.size() > 8
                || available.size() > 8
                || new HashSet<>(requested).size() != requested.size()
                || !new HashSet<>(requested).equals(new HashSet<>(available)))
            throw new IllegalArgumentException("请重新读取并明确确认当前真实首页入口清单；不能指定任意别名或遗漏同名入口");
        for (String path : available) packages.readStaticPage(code, version, path.substring(1));
        return List.copyOf(available);
    }

    private void conflicts(Long siteId, String code, OneshotEntity route, List<String> aliases) {
        Set<String> candidates = new LinkedHashSet<>(aliases);
        candidates.add("/");
        if (route.getListPageUrl() != null && !route.getListPageUrl().isBlank())
            candidates.add(java.net.URI.create(links.getListUrl(route, 1)).getPath());
        for (String value : candidates) {
            String candidate = normalize(value);
            for (var custom : routes.getHandlers(code))
                if (custom.matches(candidate)) throw new IllegalArgumentException("首页入口与自定义路由冲突");
            for (var other :
                    Optional.ofNullable(menus.selectListBySiteProjectId(siteId))
                            .orElse(List.of())) {
                if (Objects.equals(other.getId(), route.getId())) continue;
                if (candidate.isEmpty() && Objects.equals(other.getId(), other.getSiteRootId()))
                    throw new IllegalArgumentException("其他栏目已占用根路由，请先规范栏目根身份");
                for (String path :
                        Arrays.asList(other.getListPageUrl(), other.getDetailPageUrl())) {
                    if (path == null || path.isBlank()) continue;
                    String expanded =
                            path.replace("{classId}", String.valueOf(other.getId()))
                                    .replace("{parentId}", String.valueOf(other.getParentId()))
                                    .replace(
                                            "${className}",
                                            other.getClassName() == null
                                                    ? ""
                                                    : other.getClassName());
                    if (new RouteHandlerEntry(expanded, null).matches(candidate))
                        throw new IllegalArgumentException("首页入口与其他栏目路由冲突");
                }
            }
        }
    }

    private String readHtml(String code, PublicBindingApplication application) {
        PublicBindingApplication.keyFrom(application.templatePath());
        Path dir = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
        Path file = dir.resolve(application.templatePath()).normalize();
        try {
            if (Files.isSymbolicLink(dir)) throw new IllegalArgumentException("模板目录不能是链接");
            Path current = dir;
            for (Path part : dir.relativize(file)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("模板路径不能是链接");
            }
            if (!file.startsWith(dir)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 2 * 1024 * 1024
                    || !file.toRealPath().startsWith(dir.toRealPath()))
                throw new IllegalArgumentException("受控模板不存在或越界");
            String html = Files.readString(file);
            if (!Objects.equals(
                    PublicBindingApplication.digest(html), application.templateDigest()))
                throw new IllegalArgumentException("受控模板已变化，不能复制或撤回");
            if (application.homeBanner() != null) {
                validateBanner(html, application.homeBanner(), application.sharedContent());
                return html;
            }
            new TextBindingCompiler()
                    .validatePublicControlled(
                            new TextBindingCompiler.Compilation(
                                    PublicSharedContentCompiler.withoutInstructions(
                                            DetailCollections.withoutInstructions(html, application.detailCollections()),
                                            application.sharedContent()),
                                    application.bindings(), List.of()),
                            0);
            return html;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("受控模板不可读取", error);
        }
    }

    private Result result(
            PublicBindingApplication a, OneshotEntity route, boolean applied, String message) {
        var urls = new LinkedHashSet<String>();
        urls.add("/");
        if (route.getListPageUrl() != null && !route.getListPageUrl().isBlank())
            urls.add(links.getListUrl(route, 1));
        urls.addAll(a.routeAliases());
        return new Result(applied, a.templatePath(), List.copyOf(urls), true, message);
    }

    private static String key(Long siteId, Integer rootId, String ownerKey) {
        return UUID.nameUUIDFromBytes(
                        ("ROOT\n" + siteId + "\n" + rootId + "\n" + ownerKey)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String normalize(String value) {
        return value.replaceAll("^/|/$", "");
    }

    private static void transaction(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("请明确确认首页应用或撤回");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("首页应用须通过CMS实际事务");
    }
}

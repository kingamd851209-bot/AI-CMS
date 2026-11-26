package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionHtml;
import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.*;
import com.xinhongru.entity.*;
import com.xinhongru.mapper.*;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.service.InfoService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/** 从可信路由与当前CMS值生成公开受控输出；不读取私有预览map或缓存业务值。 */
@Component
public class BoundTemplateModelContributor implements TemplateModelContributor {
    private final OneShotMapper menus;
    private final ContentMapper contents;
    private final ContentExtensionResources resources;
    private final Path root;
    private final com.xinhongru.site.mapper.SiteProjectMapper projects;

    public BoundTemplateModelContributor(
            OneShotMapper menus,
            ContentMapper contents,
            ContentExtensionResources resources,
            com.xinhongru.site.mapper.SiteProjectMapper projects,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.menus = menus;
        this.contents = contents;
        this.resources = resources;
        this.projects = projects;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private PublicListModelProjection lists;
    private QueryTemplateCompiler queryCompiler;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSharedContentCompiler sharedContent;
    @org.springframework.beans.factory.annotation.Autowired
    private DetailCollections detailCollections;
    @org.springframework.beans.factory.annotation.Autowired
    private HomeBannerBinding homeBanners;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NavigationLandingApplicationService navigationLandings;
    @org.springframework.beans.factory.annotation.Autowired
    private InfoService neighborContents;
    @org.springframework.beans.factory.annotation.Autowired
    private ContentLinkService neighborLinks;
    @org.springframework.beans.factory.annotation.Autowired
    private TemplateStorageService neighborTemplates;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPublicLists(
            PublicListModelProjection lists, QueryTemplateCompiler queryCompiler) {
        this.lists = lists;
        this.queryCompiler = queryCompiler;
    }

    @Override
    public boolean supports(String code, String path, String html) {
        return PublicBindingApplication.managed(path);
    }

    @Override
    public void contribute(Long siteId, Context context) {
        throw new IllegalArgumentException("受控公开模板需要本次可信渲染信息");
    }

    @Override
    public void contribute(
            Long siteId,
            String code,
            String path,
            String html,
            Context context,
            boolean publicRequest) {
        var application =
                PublicBindingApplication.read(root, code, PublicBindingApplication.keyFrom(path));
        if (application == null
                || !Objects.equals(application.siteId(), siteId)
                || !path.equals(application.templatePath())
                || !PublicBindingApplication.digest(html).equals(application.templateDigest()))
            throw new IllegalArgumentException("公开应用不存在或模板已变化");
        var project = projects.selectById(siteId);
        if (project == null
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getUiPackageVersion(), application.packageVersion()))
            throw new IllegalArgumentException("当前站点包版本已变化，请显式检查原应用");
        var id = context.getVariable("idEntity") instanceof IdEntity value ? value : null;
        if (publicRequest
                && (id == null
                        || !Objects.equals(id.getServerSiteProjectId(), siteId)
                        || !Objects.equals(id.getSiteProjectId(), siteId)))
            throw new IllegalArgumentException("公开绑定须使用当前服务器身份");
        Integer menuId = id == null ? null : id.getClassId();
        if (!publicRequest
                && menuId == null
                && context.getVariable("currentMenu") instanceof OneshotEntity menu)
            menuId = menu.getId();
        boolean rootRoute = application.routeMenuId() != null;
        Long expectedRouteId = rootRoute ? application.routeMenuId() : application.menuId();
        if (menuId == null || !Objects.equals(menuId.longValue(), expectedRouteId))
            throw new IllegalArgumentException("公开应用与当前路由栏目不一致");
        var routeMenu = menus.selectPublicBindingMenu(siteId, expectedRouteId);
        if (routeMenu == null
                || !Objects.equals(routeMenu.getSiteProjectId(), siteId)
                || !Objects.equals(routeMenu.getId(), menuId)
                || Boolean.TRUE.equals(routeMenu.getIsDeleted()))
            throw unavailable(publicRequest, "当前路由栏目不存在或未公开");
        boolean banner = application.homeBanner() != null;
        boolean landing = application.navigationLanding() != null;
        if (rootRoute
                && (application.pageType() != (banner ? 1 : 0)
                        || !Objects.equals(project.getRootMenuId(), menuId)
                        || !Objects.equals(routeMenu.getSiteRootId(), menuId)
                        || !Integer.valueOf(0).equals(routeMenu.getPageType())
                        || Objects.equals(application.menuId(), expectedRouteId)))
            throw new IllegalArgumentException("首页路由身份或数据来源不符合当前站点根栏目");
        if (publicRequest && !Boolean.TRUE.equals(routeMenu.getHasFrontChannel()))
            throw unavailable(true, "当前路由栏目未开放前台");
        var menu =
                rootRoute ? menus.selectPublicBindingMenu(siteId, application.menuId()) : routeMenu;
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getId().longValue(), application.menuId())
                || Boolean.TRUE.equals(menu.getIsDeleted()))
            throw unavailable(publicRequest, "当前数据栏目不存在或未公开");
        boolean listSlot = application.pageType() == 0 || application.queryId() != null || banner || landing;
        String activeTemplate = listSlot ? routeMenu.getListPageTemplate() : routeMenu.getDetailPageTemplate();
        String activeRoute = listSlot ? routeMenu.getListPageUrl() : routeMenu.getDetailPageUrl();
        boolean sameRoute = path.equals(activeTemplate) && Objects.equals(application.routeUrl(), activeRoute);
        if (!sameRoute && publicRequest && !rootRoute && application.queryId() == null && !landing
                && !path.equals(activeTemplate)) {
            // 正式路由重载前，处理已匹配到前后两个相邻版本之一的请求。
            var active = PublicBindingApplication.atPath(root, code, activeTemplate);
            sameRoute = active != null
                    && Objects.equals(active.siteId(), siteId)
                    && Objects.equals(active.menuId(), application.menuId())
                    && Objects.equals(active.packageVersion(), application.packageVersion())
                    && active.pageType() == application.pageType()
                    && Objects.equals(active.initializedRecordId(), application.initializedRecordId())
                    && Objects.equals(active.routeUrl(), activeRoute)
                    && (Objects.equals(active.previousTemplate(), path)
                            || Objects.equals(application.previousTemplate(), activeTemplate));
        }
        if (!Objects.equals(menu.getPageType(), application.pageType())
                || !ContentFieldConfigDigest.of(menu).equals(application.fieldConfigDigest())
                || !sameRoute)
            throw new IllegalArgumentException("公开栏目或绑定配置已变化");
        context.setVariable("currentRouteMenu", routeMenu);
        if (publicRequest
                && (!Boolean.TRUE.equals(menu.getHasFrontChannel())
                        || application.pageType() == 0 && !Objects.equals(menu.getStatus(), 3)))
            throw unavailable(publicRequest, "当前单页尚未公开");
        String bodyHtml = PublicSharedContentCompiler.withoutInstructions(
                DetailCollections.withoutInstructions(
                        DetailNeighbors.withoutInstructions(html, application.detailNeighbors()),
                        application.detailCollections()),
                application.sharedContent());
        if (ReviewedDisclosureRows.supports(html)) {
            if (application.pageType() != 0)
                throw new IllegalArgumentException("披露列表只能挂在已审核的本站单页");
            context.setVariable("cmsBoundDisclosures",
                    ReviewedDisclosureRows.publishedRows(html, siteId, menu, menus, neighborContents));
            bodyHtml = ReviewedDisclosureRows.withoutInstructions(bodyHtml);
        }
        if (ReviewedCultureRecords.supports(html)) {
            if (application.pageType() != 0)
                throw new IllegalArgumentException("文化产品记录只可投影到已审核的本站单页");
            context.setVariable("cmsBoundCulture",
                    ReviewedCultureRecords.publishedRows(html, siteId, menu, menus, neighborContents, neighborLinks).model());
            bodyHtml = ReviewedCultureRecords.withoutInstructions(bodyHtml);
        }
        if (!application.sharedContent().isEmpty()) {
            if (sharedContent == null) throw new IllegalArgumentException("公共字段投影不可用");
            sharedContent.contribute(siteId, project.getRootMenuId(), application.sharedContent(), context);
        }
        if (banner) {
            RootTemplateApplicationService.validateBanner(html, application.homeBanner(), application.sharedContent());
            if (id == null || homeBanners == null)
                throw new IllegalArgumentException("轮播公开模板须使用服务器路由身份");
            homeBanners.contribute(siteId, code, application.packageVersion(), project.getRootMenuId(),
                    menu, application.planId(), application.homeBanner(), context, id);
            context.setVariable("currentMenu", routeMenu);
            context.setVariable("itemMenu", routeMenu);
            return;
        }
        if (landing) {
            if (navigationLandings == null || rootRoute || application.pageType() != 1
                    || Objects.equals(menu.getDetailPageTemplate(), path))
                throw new IllegalArgumentException("概览须使用独立栏目列表模板");
            navigationLandings.validateRuntime(new SiteModelNavigationService.Scope(siteId, code,
                    application.packageVersion(), project.getRootMenuId()), application, bodyHtml);
            context.setVariable("currentMenu", menu);
            context.setVariable("itemMenu", menu);
            return;
        }
        if (application.queryId() != null) {
            if (lists == null
                    || queryCompiler == null
                    || id == null
                    || Objects.equals(menu.getDetailPageTemplate(), path))
                throw new IllegalArgumentException("公开LIST不能作为详情模板或使用无身份预览");
            var compiled =
                    queryCompiler.compile(
                            siteId, code, application.packageVersion(), application.queryId(),
                            ListHeaderMenu.hasMarker(bodyHtml));
            if (!compiled.completeList()) throw new IllegalArgumentException("查询仅含接线，尚非完整LIST");
            var presentation = compiled.snapshot().presentation().request();
            boolean supportedHeaderMenu = presentation.timeline() == null
                    && ListHeaderMenu.generated(presentation.featuredCarousel() == null
                            ? compiled.html() : ListFeaturedCarousel.withoutScript(compiled.html()));
            queryCompiler.validateControlled(
                    new QueryTemplateCompiler.Compiled(bodyHtml, compiled.snapshot(), List.of(), true),
                    supportedHeaderMenu);
            if (compiled.snapshot().presentation().request().timeline() != null) {
                context.setVariable("cmsBoundTimeline", lists.timeline(compiled.snapshot(), id, menu));
                context.setVariable("currentMenu", menu);
                context.setVariable("itemMenu", menu);
                return;
            }
            var model =
                    lists.query(
                            siteId,
                            code,
                            application.packageVersion(),
                            application.queryId(),
                            id,
                            menu);
            for (var row : model.rows()) {
                @SuppressWarnings("unchecked")
                var media = (Map<String, String>) row.get("media");
                validateMediaSources(html, "cmsBoundRow.media", media);
            }
            context.setVariable("cmsBoundRows", model.rows());
            context.setVariable("cmsBoundPage", model.page());
            context.setVariable("cmsBoundForm", model.form());
            context.setVariable("cmsBoundPageNumbers", model.numbers());
            context.setVariable("currentMenu", menu);
            context.setVariable("itemMenu", menu);
            return;
        }
        if (application.pageType()==0 && !application.detailCollections().isEmpty())
            detailCollections.contribute(siteId, code, menu, null, application.detailCollections(), context);
        Object owner = menu;
        if (application.pageType() == 1) {
            if (id == null || id.getInfoId() == null || id.getInfoId() <= 0)
                throw unavailable(publicRequest, "详情身份无效");
            var content = contents.selectActiveArticleInSiteMenu(siteId, menuId, id.getInfoId());
            if (content == null
                    || !Objects.equals(content.getClassid(), menuId)
                    || !Objects.equals(content.getId(), id.getInfoId())
                    || content.getSiteProjectId() != null
                            && !Objects.equals(content.getSiteProjectId(), siteId))
                throw unavailable(publicRequest, "详情不属于当前站点栏目");
            if (publicRequest
                    && (!Objects.equals(content.getStatus(), 1)
                            || content.getEntryIntoForceTime() == null
                            || content.getExpirationTime() == null
                            || content.getEntryIntoForceTime().isAfter(LocalDateTime.now())
                            || content.getExpirationTime().isBefore(LocalDateTime.now())))
                throw unavailable(publicRequest, "详情尚未公开或已失效");
            owner = content;
            context.setVariable("content", content);
            context.setVariable("info", content);
            if (!application.detailCollections().isEmpty())
                detailCollections.contribute(siteId, code, menu, content, application.detailCollections(), context);
            if (application.detailNeighbors() != null) {
                context.setVariable(DetailNeighbors.VARIABLE,
                        publicRequest && application.detailNeighbors().candidate().kind() == DetailNeighbors.Kind.NEIGHBORS
                        ? DetailNeighbors.project(siteId, menu, content, neighborContents, neighborLinks)
                        : new DetailsVo());
                context.setVariable(DetailNeighbors.RETURN_URL,
                        publicRequest
                                ? DetailNeighbors.returnUrl(root, code, application.packageVersion(),
                                        application.detailNeighbors().returnQueryId(), menu, neighborLinks, neighborTemplates)
                                : null);
            }
        }
        var compilation =
                new TextBindingCompiler.Compilation(
                        DetailFormActions.withoutInstructions(
                                bodyHtml, application.detailActions()),
                        application.bindings(),
                        List.of());
        new TextBindingCompiler().validatePublicControlled(compilation, application.pageType());
        context.setVariable("cmsBoundMenuNames",
                MenuNameBinding.names(menus, menu, application.bindings(), !publicRequest));
        if (!application.detailActions().isEmpty()) {
            if (!(owner instanceof ContentEntity content) || !DetailFormActions.titleEnabled(menu))
                throw new IllegalArgumentException("详情预填来源无效");
            var actionUrls = new LinkedHashMap<String, String>();
            for (var binding : application.detailActions()) {
                var action = binding.action();
                var selection = binding.selection();
                var target = menus.selectPublicBindingMenu(siteId, selection.targetMenuId());
                if (DetailFormActions.currentTarget(target, siteId, binding))
                    actionUrls.put(
                            action.sourceId(),
                            PublicListModelProjection.formActionUrl(action, content.getTitle()));
            }
            context.setVariable("cmsBoundDetailActionUrls", Map.copyOf(actionUrls));
        }
        var fields =
                ContentModelCodec.read(
                        application.pageType() == 0 ? menu.getMenuField() : menu.getModelField());
        Map<String, String> htmlValues = new LinkedHashMap<>(), mediaValues = new LinkedHashMap<>();
        Map<String, String> dateValues = new LinkedHashMap<>();
        for (var binding : application.bindings()) {
            if (MenuNameBinding.applies(binding)) continue;
            var field =
                    fields.stream()
                            .filter(f -> Objects.equals(f.getFieldName(), binding.fieldName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("绑定字段不存在"));
            if (!ContentFieldDefinition.enabled(field.getIsOpen())
                    || !Objects.equals(field.getFieldType(), binding.fieldType()))
                throw new IllegalArgumentException("绑定字段已停用或类型变化");
            if ("DateTime".equals(binding.fieldType()))
                dateValues.put(
                        binding.fieldName(),
                        TemplateDateValues.display(
                                binding, field, value(owner, binding.fieldName())));
            if ("Editor".equals(binding.fieldType()))
                htmlValues.put(
                        binding.fieldName(),
                        ContentExtensionHtml.clean(value(owner, binding.fieldName())));
            if (Set.of("Image", "Video").contains(binding.fieldType()))
                mediaValues.put(
                        binding.fieldName(),
                        LinkedImageBackground.validateReference(
                                html,
                                "cmsBoundMedia",
                                binding.fieldName(),
                                resources.prepareTemplateMedia(
                                        field, value(owner, binding.fieldName()), siteId)));
        }
        validateMediaSources(html, "cmsBoundMedia", mediaValues);
        context.setVariable("currentMenu", menu);
        context.setVariable("itemMenu", menu);
        context.setVariable("cmsBoundHtml", Map.copyOf(htmlValues));
        context.setVariable("cmsBoundMedia", Map.copyOf(mediaValues));
        context.setVariable("cmsBoundDates", Map.copyOf(dateValues));
    }

    private static void validateMediaSources(
            String html, String namespace, Map<String, String> mediaValues) {
        for (var source : org.jsoup.Jsoup.parse(html).select("source[type]"))
            for (var entry : mediaValues.entrySet())
                if (source.attr("th:src")
                        .equals("${" + namespace + "['" + entry.getKey() + "']}")) {
                    String v = entry.getValue().split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
                    String mime =
                            source.attr("type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!v.isEmpty()
                            && !(mime.equals("video/mp4") && v.endsWith(".mp4")
                                    || mime.equals("video/webm") && v.endsWith(".webm")
                                    || mime.equals("video/ogg") && v.endsWith(".ogv")))
                        throw new IllegalArgumentException("媒体与固定MIME不一致");
                }
    }

    private static RuntimeException unavailable(boolean publicRequest, String adminMessage) {
        return publicRequest
                ? new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "页面不存在")
                : new IllegalArgumentException(adminMessage);
    }

    static String value(Object owner, String name) {
        Object value;
        if (ContentExtensionFields.isName(name))
            value =
                    owner instanceof OneshotEntity m
                            ? m.getExtensionValues().get(name)
                            : ((ContentEntity) owner).getExtensionValues().get(name);
        else
            try {
                var f = EntityFields.find(owner.getClass(), name);
                f.setAccessible(true);
                value = f.get(owner);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("绑定字段不可读取", e);
            }
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("绑定字段须为文本");
        return (String) value;
    }
}

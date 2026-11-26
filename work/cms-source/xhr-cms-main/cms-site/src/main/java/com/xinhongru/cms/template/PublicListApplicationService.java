package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.*;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 完整受控查询应用仅切换原栏目列表模板；不创建内容，不改变发布和详情模板。 */
@Service
public class PublicListApplicationService {
    public record Result(
            boolean applied,
            String templatePath,
            String publicUrl,
            boolean requiresRouteReload,
            String message) {}

    private final TemplateQueryBindingService queries;
    private final QueryTemplateCompiler compiler;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final TemplateStorageService templates;
    private final ContentLinkService links;
    private final SiteRouteRegistry routes;
    private final Path root;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicNavigationLinkCompiler navigationLinks;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSharedContentCompiler sharedContent;

    @org.springframework.beans.factory.annotation.Autowired private TextBindingPublicApplicationService details;
    @org.springframework.beans.factory.annotation.Autowired private TemplateFieldPlanService fieldPlans;
    @org.springframework.beans.factory.annotation.Autowired private PublicDetailAvailability detailAvailability;
    @org.springframework.beans.factory.annotation.Autowired private com.xinhongru.mapper.ContentMapper contents;

    public record SharedDetail(String token, Long sourceMenuId, String sourceTemplate, String targetTemplate,
            String targetRoute, boolean applied, List<OmittedRegion> omittedRegions, String returnUrl,
            boolean replacementRequiresWithdrawal) {}

    public record OmittedRegion(String selector, String preview) {}

    record SharedDetailComposition(String html, List<OmittedRegion> omittedRegions, String returnUrl) {}

    public record LayoutChoice(Long menuId, String name, String queryId, boolean current) {}

    public List<LayoutChoice> sharedLayouts(Long site, String code, String version, Long targetMenu,
            String sourcePath, java.util.function.IntPredicate canAccess) {
        var choices = new ArrayList<LayoutChoice>();
        for (var menu : Optional.ofNullable(menus.selectListBySiteProjectId(site)).orElse(List.of())) {
            if (!canAccess.test(menu.getId())) continue;
            try {
                var applied = PublicBindingApplication.atPath(root, code, menu.getListPageTemplate());
                if (applied == null || applied.queryId() == null || !Objects.equals(applied.siteId(), site)
                        || !Objects.equals(applied.menuId(), menu.getId().longValue())
                        || !Objects.equals(applied.packageVersion(), version)) continue;
                boolean current = Objects.equals(targetMenu, applied.menuId());
                var query = queries.read(site, code, version, applied.queryId());
                if (query.completeList() && Objects.equals(query.sourcePath(), sourcePath)
                        && current == (query.sharedLayout() != null))
                    choices.add(new LayoutChoice(query.menuId(), menu.getClassName(), query.id(), current));
            } catch (IllegalArgumentException stale) {
                // 已失效的绑定不能再选为来源。
            }
        }
        return List.copyOf(choices);
    }

    public record Readiness(int publishedRecords, boolean moreRecords, boolean detailConfigured, String message) {}

    public Readiness readiness(Long site, String code, String version, String queryId) {
        var query = queries.read(site, code, version, queryId);
        var target = menus.selectPublicBindingMenu(site, query.menuId());
        scope(site, code, version, query, target);
        var published = contents.selectPublicReleaseRecords(site, target.getId());
        int records = published.size();
        String detailProblem = detailAvailability.detailTemplateProblem(site, code, version, target);
        boolean configured = detailProblem == null;
        return new Readiness(Math.min(records, 200), records > 200, configured,
                records == 0 ? "当前栏目暂无已发布且在有效期内的记录"
                        : !configured ? detailProblem
                        : detailAvailability.sharedDetailUrl(site, code, target, published.getFirst()) == null
                                ? "首条记录的详情路由尚未生效，请核对栏目归属与站点路由重载"
                                : "首条有效记录已匹配本栏目的公开详情路由");
    }

    public Long sharedDetailMenuId(Long site, String code, String version, String queryId) {
        return sharedDetailReceipt(site, code, version, queryId).menuId();
    }

    private PublicBindingApplication sharedDetailReceipt(Long site, String code, String version, String queryId) {
        var receipt = PublicBindingApplication.read(root, code, key(site, "SHARED-DETAIL:" + queryId));
        if (receipt == null || !Objects.equals(receipt.siteId(), site)
                || !Objects.equals(receipt.packageVersion(), version)
                || receipt.pageType() != 1 || receipt.queryId() != null || receipt.routeMenuId() != null)
            throw new IllegalArgumentException("原共享详情应用不存在");
        return receipt;
    }

    @Transactional
    public boolean withdrawSharedDetail(Long site, String code, String version, String queryId, boolean confirmed) {
        transaction(confirmed);
        var receipt = sharedDetailReceipt(site, code, version, queryId);
        var menu = menus.lockFieldConfiguration(site, receipt.menuId());
        var project = projects.selectById(site);
        if (project == null || !Objects.equals(project.getProjectCode(), code) || menu == null
                || !Objects.equals(menu.getSiteProjectId(), site) || !Objects.equals(menu.getPageType(), 1)
                || Objects.equals(project.getRootMenuId(), menu.getId()))
            throw new IllegalArgumentException("原详情应用栏目不属于当前站点");
        if (!Objects.equals(menu.getDetailPageUrl(), receipt.routeUrl()))
            throw new IllegalArgumentException("详情路由已有人工变更，不能自动撤回");
        String current = menu.getDetailPageTemplate();
        if (Objects.equals(current, receipt.previousTemplate())) return false;
        if (!Objects.equals(current, receipt.templatePath()))
            throw new IllegalArgumentException("详情模板已有人工变更，不能自动撤回");
        verify(code, receipt);
        if (menus.updatePublicBindingTemplate(site, receipt.menuId(), 1, current, receipt.previousTemplate()) != 1)
            throw new IllegalArgumentException("详情模板并发变化，未完成撤回");
        return true;
    }

    public SharedDetail previewSharedDetail(Long site, String code, String version, String queryId) {
        return sharedDetail(site, code, version, queryId, null, false);
    }

    @Transactional
    public SharedDetail applySharedDetail(Long site, String code, String version, String queryId,
            String expectedToken, boolean confirmed) {
        transaction(confirmed);
        return sharedDetail(site, code, version, queryId, expectedToken, true);
    }

    private SharedDetail sharedDetail(Long site, String code, String version, String queryId,
            String expectedToken, boolean apply) {
        var query = queries.read(site, code, version, queryId);
        if (query.sharedLayout() == null)
            throw new IllegalArgumentException("须先确认目标栏目的共享列表查询");
        var sourceQuery = queries.read(site, code, version, query.sharedLayout().sourceQueryId());
        var target = apply ? menus.lockFieldConfiguration(site, query.menuId())
                : menus.selectPublicBindingMenu(site, query.menuId());
        scope(site, code, version, query, target);
        var source = menus.selectPublicBindingMenu(site, sourceQuery.menuId());
        scope(site, code, version, sourceQuery, source);
        var original = PublicBindingApplication.atPath(root, code, source.getDetailPageTemplate());
        if (original == null || !Objects.equals(original.siteId(), site)
                || !Objects.equals(original.menuId(), sourceQuery.menuId())
                || !Objects.equals(original.packageVersion(), version)
                || !Objects.equals(original.routeUrl(), source.getDetailPageUrl())
                || !Objects.equals(original.fieldConfigDigest(), ContentFieldConfigDigest.of(source))
                || original.pageType() != 1 || original.queryId() != null || original.routeMenuId() != null
                || original.detailNeighbors() != null || !original.detailCollections().isEmpty())
            throw new IllegalArgumentException("原栏目详情尚未应用或含须单独确认的邻接栏目与内容集合");
        var evidence = fieldPlans.readSavedDisplaySource(site, code, version, sourceQuery.sourcePlanId());
        var detail = fieldPlans.readSavedDisplaySource(site, code, version, original.planId());
        if (!"CONFIRMED".equals(detail.status()) || !Objects.equals(detail.menuId(), sourceQuery.menuId())
                || !Objects.equals(detail.sourcePath(), evidence.sourcePath())
                || !Objects.equals(detail.digest(), evidence.digest()))
            throw new IllegalArgumentException("原栏目详情应用与原列表的已确认详情证据不一致");

        var sourceFields = ContentModelCodec.read(source.getModelField());
        var targetFields = ContentModelCodec.read(target.getModelField());
        for (var binding : original.bindings()) {
            if (MenuNameBinding.applies(binding)) continue;
            var field = sourceFields.stream().filter(value -> Objects.equals(value.getFieldName(),
                    binding.fieldName())).findFirst().orElseThrow();
            if (targetFields.stream().noneMatch(value -> ContentFieldDefinition.enabled(value.getIsOpen())
                    && Objects.equals(value.getFieldName(), field.getFieldName())
                    && Objects.equals(value.getFieldType(), field.getFieldType())
                    && Objects.equals(value.getTitle(), field.getTitle())))
                throw new IllegalArgumentException("目标栏目的详情字段未就绪：" + field.getTitle());
        }
        if (!original.detailActions().isEmpty() && !DetailFormActions.titleEnabled(target))
            throw new IllegalArgumentException("目标栏目须启用标题字段，供已审核的样品表单预填");
        String route = target.getDetailPageUrl();
        details.route(site, code, target, route, 1L);
        var composition = composeSharedDetail(verify(code, original), detail.sourcePath(),
                query.sourcePath(), PublicListModelProjection.localUrl(query.firstUrl()));
        String html = composition.html();
        String templateDigest = PublicBindingApplication.digest(html);
        String key = key(site, "SHARED-DETAIL:" + queryId);
        String path = "cms-applied/" + key + "/" + templateDigest + ".html";
        String current = target.getDetailPageTemplate();
        var existing = PublicBindingApplication.read(root, code, key);
        boolean sameReceiptScope = existing != null && Objects.equals(existing.siteId(), site)
                && Objects.equals(existing.packageVersion(), version)
                && Objects.equals(existing.menuId(), query.menuId())
                && Objects.equals(existing.routeUrl(), route);
        boolean oldPointer = sameReceiptScope && Objects.equals(current, existing.templatePath());
        boolean active = oldPointer && Objects.equals(current, path);
        String previous = oldPointer ? existing.previousTemplate() : current;
        String token = PublicBindingApplication.digest(queryId + "\n" + original.templatePath() + "\n"
                + path + "\n" + route + "\n" + previous);
        var result = new SharedDetail(token, sourceQuery.menuId(), original.templatePath(), path, route, active,
                composition.omittedRegions(), composition.returnUrl(), oldPointer && !active);
        if (!apply) return result;
        if (!Objects.equals(token, expectedToken))
            throw new IllegalArgumentException("详情版式或目标路由已变化，请重新预览");
        if (active) {
            verify(code, existing);
            return result;
        }
        var receipt = new PublicBindingApplication(key, site, query.menuId(), original.planId(), version,
                1, ContentFieldConfigDigest.of(target), original.bindings(), path, templateDigest,
                current, route, null, null, List.of(), null, original.sharedContent(), original.detailActions());
        if (PublicBindingApplication.managed(current) || existing != null
                && (!sameReceiptScope || !Objects.equals(current, existing.previousTemplate())))
            throw new IllegalArgumentException("目标详情已有公开应用，请先明确撤回现有模板");
        if (existing != null) verify(code, existing);
        var directory = templates.resolveTemplateDir(code);
        try {
            if (Files.exists(directory.resolve(path))) verify(code, receipt);
            else TemplateFileWriter.write(directory, path, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("共享详情模板无法保存", error);
        }
        if (menus.updatePublicBindingTemplate(site, query.menuId(), 1, current, path) != 1)
            throw new IllegalArgumentException("详情模板并发变化，未应用");
        receipt.write(root, code);
        return new SharedDetail(token, sourceQuery.menuId(), original.templatePath(), path, route, true,
                composition.omittedRegions(), composition.returnUrl(), false);
    }

    static SharedDetailComposition composeSharedDetail(
            String html, String detailPath, String listPath, String targetListUrl) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var bound = document.getAllElements().stream().filter(el -> el.attributes().asList().stream()
                .anyMatch(attribute -> attribute.getKey().startsWith("th:")
                        && (attribute.getValue().contains("${content.")
                                || attribute.getValue().contains("${cmsBoundMedia")
                                || attribute.getValue().contains("${cmsBoundHtml")
                                || attribute.getValue().contains("${cmsBoundDates")))).toList();
        var main = bound.isEmpty() ? null : bound.getFirst().closest("main,[role=main],.main,article");
        if (main == null || bound.stream().anyMatch(el -> el != main && !el.parents().contains(main)))
            throw new IllegalArgumentException("详情缺少可核对的共同记录区域，需单独审核详情版式");
        var omitted = new ArrayList<OmittedRegion>();
        omitStaticRecordSections(main, omitted);
        boolean returnBound = false;
        for (var anchor : main.select("a[href]")) {
            if (hasBinding(anchor)) continue;
            try {
                if (listPath.equals(TemplateListPairingService.staticLinkPath(detailPath, anchor.attr("href")))) {
                    anchor.attr("href", targetListUrl);
                    returnBound = true;
                }
            } catch (IllegalArgumentException unsupported) {
                // 未指向本原列表页的其他动作保留原审核布局。
            }
        }
        return new SharedDetailComposition(document.outerHtml(), List.copyOf(omitted),
                returnBound ? targetListUrl : null);
    }

    private static void omitStaticRecordSections(org.jsoup.nodes.Element parent, List<OmittedRegion> omitted) {
        for (var child : parent.children()) {
            if (child.is("header,footer,nav,aside,script,style")) continue;
            boolean recordSection = child.select("ul,ol,table,dl,p,video,picture").stream()
                    .anyMatch(el -> el.is("video,picture") || !el.text().isBlank());
            boolean containsBinding = child.getAllElements().stream().anyMatch(PublicListApplicationService::hasBinding);
            if (!containsBinding && child.is("div,section,article,ul,ol,table,dl,p,video,picture")
                    && (recordSection || child.ownText().strip().length() >= 35)
                    && child.select("form,input,button").isEmpty()) {
                String text = child.text();
                omitted.add(new OmittedRegion(child.cssSelector(),
                        text.length() <= 140 ? text : text.substring(0, 140) + "…"));
                child.empty().attr("hidden", "hidden");
            } else if (containsBinding && !hasBinding(child)) {
                omitStaticRecordSections(child, omitted);
            }
        }
    }

    private static boolean hasBinding(org.jsoup.nodes.Element element) {
        return element.attributes().asList().stream().anyMatch(attribute -> {
            String key = attribute.getKey();
            return key.startsWith("th:") || key.startsWith("data-th-");
        });
    }

    public PublicListApplicationService(
            TemplateQueryBindingService queries,
            QueryTemplateCompiler compiler,
            OneShotMapper menus,
            SiteProjectMapper projects,
            TemplateStorageService templates,
            ContentLinkService links,
            SiteRouteRegistry routes,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.queries = queries;
        this.compiler = compiler;
        this.menus = menus;
        this.projects = projects;
        this.templates = templates;
        this.links = links;
        this.routes = routes;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Result inspect(Long site, String code, String version, String queryId) {
        var snapshot = queries.read(site, code, version, queryId);
        var menu = menus.selectPublicBindingMenu(site, snapshot.menuId());
        scope(site, code, version, snapshot, menu);
        var application = PublicBindingApplication.read(root, code, key(site, queryId));
        boolean timeline = snapshot.presentation() != null && snapshot.presentation().request().timeline() != null;
        return application == null
                ? new Result(
                        false, null, snapshot.firstUrl(), false, "尚未公开应用；请先确认完整列表展示")
                : result(
                        application,
                        menu,
                        Objects.equals(menu.getListPageTemplate(), application.templatePath()),
                        false, timeline);
    }

    @Transactional
    public Result apply(Long site, String code, String version, String queryId, boolean confirmed) {
        transaction(confirmed);
        var snapshot = queries.read(site, code, version, queryId);
        var menu = menus.lockFieldConfiguration(site, snapshot.menuId());
        scope(site, code, version, snapshot, menu);
        var compiled = compiler.compile(site, code, version, queryId);
        if (!compiled.completeList()
                || compiled.snapshot().presentation() == null
                || !Objects.equals(compiled.snapshot(), snapshot))
            throw new IllegalArgumentException("必须先确认完整列表展示，不能公开仅字段接线结果");
        boolean timeline = snapshot.presentation().request().timeline() != null;
        String key = key(site, queryId);
        String current = menu.getListPageTemplate();
        var existing = PublicBindingApplication.read(root, code, key);
        if (existing != null && Objects.equals(existing.siteId(), site)
                && Objects.equals(existing.queryId(), queryId) && Objects.equals(existing.menuId(), snapshot.menuId())
                && Objects.equals(existing.packageVersion(), version)
                && Objects.equals(existing.previousTemplate(), current)) {
            verify(code, existing);
        }
        boolean featured = snapshot.presentation().request().featuredCarousel() != null;
        String body = compiled.html();
        if (featured) body = ListFeaturedCarousel.withoutScript(body);
        boolean headerMenu = !timeline && ListHeaderMenu.generated(body);
        if (headerMenu) body = ListHeaderMenu.withoutGenerated(body);
        String html = PublicBindingResources.rebase(body, snapshot.sourcePath());
        boolean withNavigation = navigationLinks != null
                && navigationLinks.hasConfirmation(site, code, version, snapshot.sourcePath());
        if (withNavigation)
            // A replacement plan may follow explicit withdrawal. Its previously approved self-link
            // still points to this same validated route while the current template is being installed.
            html = navigationLinks.bind(site, code, version, snapshot.sourcePath(), html, snapshot.menuId());
        var shared = sharedContent == null ? new PublicSharedContentCompiler.Compiled(html, List.of())
                : sharedContent.compile(site, code, version, snapshot.sourcePath(), html);
        html = headerMenu ? ListHeaderMenu.withGenerated(shared.html()) : shared.html();
        if (featured) html = ListFeaturedCarousel.withScript(html);
        compiler.validateControlled(
                new QueryTemplateCompiler.Compiled(
                        PublicSharedContentCompiler.withoutInstructions(html, shared.references()),
                        snapshot, compiled.warnings(), true),
                headerMenu);
        route(site, code, menu);
        String hash = PublicBindingApplication.digest(html);
        String path = "cms-applied/" + key + "/" + hash + ".html";
        if (existing != null
                && (!Objects.equals(existing.queryId(), queryId)
                        || !Objects.equals(existing.siteId(), site)
                        || !Objects.equals(existing.menuId(), snapshot.menuId())
                        || !Objects.equals(existing.routeUrl(), menu.getListPageUrl())
                        || !Objects.equals(existing.packageVersion(), version)))
            throw new IllegalArgumentException("原公开应用版本已变化，请先读取原应用撤回");
        String oldHtml = null;
        if (existing != null && !Objects.equals(existing.templatePath(), path)
                && (Objects.equals(current, existing.previousTemplate())
                        || Objects.equals(current, existing.templatePath()))) {
            oldHtml = verify(code, existing);
            html = retainLegacyEmptyText(oldHtml, html, snapshot.presentation().containerSelector());
            hash = PublicBindingApplication.digest(html);
            path = "cms-applied/" + key + "/" + hash + ".html";
        }
        java.util.function.UnaryOperator<String> compare = withNavigation
                ? navigationLinks.reapplicationComparison(site, code, version, snapshot.sourcePath())
                : java.util.function.UnaryOperator.identity();
        if (headerMenu) {
            var approvedLinks = compare;
            compare = value -> approvedLinks.apply(ListHeaderMenu.withoutGenerated(
                    featured ? ListFeaturedCarousel.withoutScript(value) : value));
        }
        // 原应用的审定链接与固定页头菜单以外的变化仍须撤回审核。
        boolean refresh = false;
        if ((withNavigation || headerMenu) && existing != null && oldHtml != null
                && Objects.equals(current, existing.templatePath())
                && Objects.equals(existing.fieldConfigDigest(), snapshot.fieldConfigDigest())
                && Objects.equals(existing.sharedContent(), shared.references())) {
            try {
                PublicSharedContentCompiler.verifyReapplication(
                        oldHtml, existing.sharedContent(), html, shared.references(),
                        true, snapshot.sourcePath(), compare);
                refresh = true;
            } catch (IllegalArgumentException changed) {
                // 无法核实为同一模板的受控变更时，继续走已有的替换拒绝分支。
            }
        }
        String previousTemplate = refresh ? existing.previousTemplate() : current;
        boolean featuredUpgrade = featured && PublicBindingApplication.managed(current)
                && (existing == null || Objects.equals(current, existing.previousTemplate()))
                && canReplaceWithFeaturedCarousel(site, code, version, current, snapshot);
        if (PublicBindingApplication.managed(current)
                && !refresh && !featuredUpgrade && (existing == null || !Objects.equals(current, path)))
            throw new IllegalArgumentException("当前列表已有其他公开应用，请读取原应用并明确撤回");
        if (existing != null && Objects.equals(current, path)) {
            verify(code, existing);
            return result(existing, menu, true, false, timeline);
        }
        if (existing != null && !refresh && !Objects.equals(current, existing.previousTemplate()))
            throw new IllegalArgumentException("列表模板已被人工修改，拒绝覆盖");
        if (existing != null && !Objects.equals(existing.templatePath(), path)) {
            boolean withShared = !shared.references().isEmpty() || !existing.sharedContent().isEmpty();
            if ((!withNavigation && !withShared && !headerMenu
                            && !PublicBindingResources.onlyResourceLinkPathsChanged(
                                    oldHtml, html, snapshot.sourcePath()))
                    || !Objects.equals(existing.fieldConfigDigest(), snapshot.fieldConfigDigest()))
                throw new IllegalArgumentException("已有其他公开列表版本，不能自动重绑");
            PublicSharedContentCompiler.verifyReapplication(
                    oldHtml,
                    existing.sharedContent(),
                    html,
                    shared.references(),
                    true,
                    snapshot.sourcePath(),
                    compare);
            existing = null;
        }
        var application =
                existing != null
                        ? existing
                        : new PublicBindingApplication(
                                key,
                                site,
                                snapshot.menuId(),
                                snapshot.sourcePlanId(),
                                version,
                                1,
                                snapshot.fieldConfigDigest(),
                                List.of(),
                                path,
                                hash,
                                previousTemplate,
                                menu.getListPageUrl(),
                                null,
                                null,
                                List.of(),
                                queryId, shared.references());
        var target = templates.resolveTemplateDir(code).resolve(path);
        try {
            if (Files.exists(target)) verify(code, application);
            else TemplateFileWriter.write(templates.resolveTemplateDir(code), path, html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("公开列表模板无法保存", error);
        }
        application.write(root, code);
        // 固定LIST专用CAS，不复用单页/详情的pageType分支。
        if (menus.updatePublicListBindingTemplate(site, snapshot.menuId(), current, path) != 1)
            throw new IllegalArgumentException("列表模板并发变化，未完成应用");
        return result(application, menu, true, true, timeline);
    }

    private boolean canReplaceWithFeaturedCarousel(Long site, String code, String version, String current,
            TemplateQueryBindingService.Snapshot snapshot) {
        var prior = PublicBindingApplication.atPath(root, code, current);
        if (prior == null || prior.queryId() == null || !Objects.equals(prior.siteId(), site)
                || !Objects.equals(prior.menuId(), snapshot.menuId()) || !Objects.equals(prior.packageVersion(), version)
                || !Objects.equals(prior.fieldConfigDigest(), snapshot.fieldConfigDigest())
                || !Objects.equals(prior.planId(), snapshot.sourcePlanId())) return false;
        var source = queries.read(site, code, version, prior.queryId());
        if (!source.completeList() || source.sharedLayout() != null
                || !Objects.equals(source.containerSelector(), snapshot.containerSelector())
                || !Objects.equals(source.sourcePath(), snapshot.sourcePath())
                || !Objects.equals(source.digest(), snapshot.digest())
                || !Objects.equals(source.firstUrl(), snapshot.firstUrl())
                || !Objects.equals(source.routeDigest(), snapshot.routeDigest())
                || !Objects.equals(source.pageSize(), snapshot.pageSize())
                || !Objects.equals(source.mappings(), snapshot.mappings())
                || !source.presentation().request().equals(snapshot.presentation().request().withoutFeaturedCarousel()))
            return false;
        verify(code, prior);
        return true;
    }

    static String retainLegacyEmptyText(String previous, String next, String listSelector) {
        String legacy = "当前页暂无符合条件的产品";
        String generic = "当前页暂无符合条件的内容";
        if (emptyText(Jsoup.parse(previous), listSelector, legacy) == null) return next;
        var document = Jsoup.parse(next);
        var empty = emptyText(document, listSelector, generic);
        if (empty == null) return next;
        document.outputSettings().prettyPrint(false);
        empty.text(legacy);
        return document.outerHtml();
    }

    private static TextNode emptyText(Document document, String listSelector, String value) {
        var lists = document.select(listSelector);
        var placeholders = document.select("[data-cms-query-empty]");
        if (lists.size() != 1 || placeholders.size() != 1) return null;
        var placeholder = placeholders.first();
        if (placeholder.parent() != lists.first()
                || !placeholder.is("p[data-cms-query-empty=true]")
                || placeholder.attributesSize() != 2
                || !"${cmsBoundRows.isEmpty()}".equals(placeholder.attr("th:if"))
                || placeholder.childNodeSize() != 1
                || !(placeholder.childNode(0) instanceof TextNode text)
                || !value.equals(text.getWholeText())) return null;
        return text;
    }

    @Transactional
    public Result withdraw(
            Long site, String code, String version, String queryId, boolean confirmed) {
        transaction(confirmed);
        var application = application(site, code, version, queryId);
        var menu = menus.lockFieldConfiguration(site, application.menuId());
        var project = projects.selectById(site);
        if (project == null
                || !Objects.equals(project.getProjectCode(), code)
                || menu == null
                || !Objects.equals(menu.getSiteProjectId(), site)
                || !Objects.equals(menu.getPageType(), 1)
                || Objects.equals(project.getRootMenuId(), menu.getId()))
            throw new IllegalArgumentException("原应用栏目不属于当前站点");
        if (Objects.equals(menu.getListPageTemplate(), application.previousTemplate()))
            return result(application, menu, false, false, false);
        if (!Objects.equals(menu.getListPageTemplate(), application.templatePath())
                || !Objects.equals(menu.getListPageUrl(), application.routeUrl()))
            throw new IllegalArgumentException("列表模板或路由已有人工变更，不能自动撤回");
        verify(code, application);
        if (menus.updatePublicListBindingTemplate(
                        site,
                        application.menuId(),
                        application.templatePath(),
                        application.previousTemplate())
                != 1) throw new IllegalArgumentException("列表模板并发变化，未完成撤回");
        return result(application, menu, false, true, false);
    }

    /** 撤回先从服务端凭据定位权限栏目，不依赖已失效的来源或模型快照。 */
    public Long applicationMenuId(Long site, String code, String version, String queryId) {
        return application(site, code, version, queryId).menuId();
    }

    private PublicBindingApplication application(
            Long site, String code, String version, String queryId) {
        var application = PublicBindingApplication.read(root, code, key(site, queryId));
        if (application == null
                || !Objects.equals(application.siteId(), site)
                || !Objects.equals(application.queryId(), queryId)
                || !Objects.equals(application.packageVersion(), version)
                || application.pageType() != 1
                || application.routeMenuId() != null)
            throw new IllegalArgumentException("原公开列表应用不存在");
        return application;
    }

    private Result result(
            PublicBindingApplication a, OneshotEntity menu, boolean applied, boolean reload, boolean timeline) {
        String first = links.getListUrl(menu, 1);
        return new Result(
                applied,
                a.templatePath(),
                timeline ? first.split("\\?", 2)[0] : first,
                reload,
                applied ? (timeline ? "已接入时间轴模板；请重载路由并核对公开页的分组联动"
                        : "已接入列表模板；须重载站点路由并读取公开URL核对筛选与分页")
                        : "已恢复原列表模板；内容与详情模板不变，须核对运行路由");
    }

    private static String key(Long site, String query) {
        return UUID.nameUUIDFromBytes(
                        ("LIST\n" + site + "\n" + query).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private void scope(
            Long site,
            String code,
            String version,
            TemplateQueryBindingService.Snapshot snapshot,
            OneshotEntity menu) {
        var project = projects.selectById(site);
        if (project == null
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getUiPackageVersion(), version)
                || menu == null
                || !Objects.equals(menu.getSiteProjectId(), site)
                || !Objects.equals(menu.getId().longValue(), snapshot.menuId())
                || Objects.equals(project.getRootMenuId(), menu.getId())
                || !Objects.equals(menu.getPageType(), 1)
                || Boolean.TRUE.equals(menu.getIsDeleted())
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !Objects.equals(ContentFieldConfigDigest.of(menu), snapshot.fieldConfigDigest()))
            throw new IllegalArgumentException("当前站点、栏目或查询模型已变化");
    }

    private void route(Long site, String code, OneshotEntity menu) {
        String first =
                PublicListModelProjection.localUrl(links.getListUrl(menu, 1)).split("\\?", 2)[0];
        String last =
                PublicListModelProjection.localUrl(links.getListUrl(menu, 2)).split("\\?", 2)[0];
        if (first.equals("/")
                || PublicBindingRootRoutes.indexAlias(first)
                || menu.getListPageUrl().contains("{pageSize}"))
            throw new IllegalArgumentException("列表不能接管根路径或未支持的容量路由");
        var listPattern =
                new RouteHandlerEntry(
                        menu.getListPageUrl()
                                .replace("{classId}", String.valueOf(menu.getId()))
                                .replace("{parentId}", String.valueOf(menu.getParentId()))
                                .replace("{id}", String.valueOf(menu.getId()))
                                .replace(
                                        "${className}",
                                        menu.getClassName() == null ? "" : menu.getClassName()),
                        null);
        for (var handler : routes.getHandlers(code))
            if (handler.matches(first.substring(1))
                    || handler.matches(last.substring(1))
                    || listPattern.matches(handler.getUrlPattern().replaceAll("\\{[^}]+\\}", "1")))
                throw new IllegalArgumentException("列表路径与自定义路由冲突");
        for (var other :
                Optional.ofNullable(menus.selectListBySiteProjectId(site)).orElse(List.of()))
            for (var url : Arrays.asList(other.getListPageUrl(), other.getDetailPageUrl())) {
                if (url == null
                        || url.isBlank()
                        || Objects.equals(other.getId(), menu.getId())
                                && Objects.equals(url, menu.getListPageUrl())) continue;
                String expanded =
                        url.replace("{classId}", String.valueOf(other.getId()))
                                .replace("{parentId}", String.valueOf(other.getParentId()))
                                .replace(
                                        "${className}",
                                        other.getClassName() == null ? "" : other.getClassName());
                var candidate = new RouteHandlerEntry(expanded, null);
                if (candidate.matches(first.substring(1))
                        || candidate.matches(last.substring(1))
                        || listPattern.matches(expanded.replaceAll("\\{[^}]+\\}", "1")))
                    throw new IllegalArgumentException("列表路径与其他栏目或详情路由冲突");
            }
    }

    private String verify(String code, PublicBindingApplication application) {
        return PublicDetailAvailability.verifiedTemplate(templates, code, application);
    }

    private static void transaction(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("请明确确认公开应用或撤回");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("公开列表必须通过CMS实际事务入口");
    }
}

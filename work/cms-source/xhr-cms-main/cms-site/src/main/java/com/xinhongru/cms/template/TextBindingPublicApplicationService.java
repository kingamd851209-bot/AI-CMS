package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.*;
import com.xinhongru.cms.uipackage.UiPackageInspectionService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.*;
import com.xinhongru.mapper.*;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.UnaryOperator;

/** 把既有已初始化原生内容接入公开模板；不写业务值、不代替发布或私有恢复。 */
@Service
public class TextBindingPublicApplicationService {
    public record Result(
            boolean applied,
            String templatePath,
            String publicUrl,
            boolean requiresRouteReload,
            String message,
            String activePlanId,
            String currentRoute,
            String previousRoute,
            String changeDigest,
            UiPackageInspectionService.Preview preview,
            boolean requiresCurrentPlan) {
        public Result(boolean applied, String templatePath, String publicUrl,
                boolean requiresRouteReload, String message) {
            this(applied, templatePath, publicUrl, requiresRouteReload, message,
                    null, null, null, null, null, false);
        }
    }

    private final TemplateFieldPlanService plans;
    private final TextBindingDraftService drafts;
    private final OneShotMapper menus;
    private final ContentMapper contents;
    private final UiPackageStorageService packages;
    private final TemplateStorageService templates;
    private final SiteRouteRegistry routes;
    private final com.xinhongru.site.content.ContentLinkService links;
    private final Path root;
    private final TextBindingCompiler compiler = new TextBindingCompiler();

    @org.springframework.beans.factory.annotation.Autowired
    private PublicNavigationLinkCompiler navigationLinks;

    @org.springframework.beans.factory.annotation.Autowired
    private PublicSharedContentCompiler sharedContent;

    @org.springframework.beans.factory.annotation.Autowired private DetailFormActions detailActions;
    @org.springframework.beans.factory.annotation.Autowired private DetailCollections detailCollections;
    @org.springframework.beans.factory.annotation.Autowired private TemplateQueryBindingService queries;

    @org.springframework.beans.factory.annotation.Autowired private SiteProjectMapper projects;
    @org.springframework.beans.factory.annotation.Autowired private TemplatePreviewService renderer;
    @org.springframework.beans.factory.annotation.Autowired private UiPackageInspectionService isolation;
    @org.springframework.beans.factory.annotation.Autowired private com.xinhongru.web.service.InfoService disclosures;

    public record CollectionEvidence(
            List<DetailCollections.Candidate> collections, List<String> selectedCollectionRegionIds) {}

    public record NeighborEvidence(List<DetailNeighbors.Candidate> candidates, String selectedCandidateId,
                                   String returnListUrl) {}

    public NeighborEvidence neighborEvidence(Long siteId, String code, String version, String planId) {
        var plan = plans.read(siteId, code, version, planId);
        var menu = menus.selectPublicBindingMenu(siteId, plan.menuId());
        scope(plan, menu);
        if (plan.pageType() != 1) return new NeighborEvidence(List.of(), null, null);
        var existing = activeApplication(plan, menu);
        var source = packages.readStaticPage(code, version, plan.sourcePath());
        if (!Objects.equals(source.digest(), plan.digest()))
            throw new IllegalArgumentException("详情原页已变化，请重新取证");
        var selected = existing == null ? null : existing.detailNeighbors();
        var candidates = neighbors(plan, menu, source.html());
        var returnQuery = candidates.stream().map(DetailNeighbors.Binding::returnQueryId)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new NeighborEvidence(
                candidates.stream().map(DetailNeighbors.Binding::candidate).toList(),
                selected == null ? null : selected.candidate().id(),
                DetailNeighbors.returnUrl(root, code, version, returnQuery, menu, links, templates));
    }

    private List<DetailNeighbors.Binding> neighbors(
            TemplateFieldPlanService.Plan plan, OneshotEntity menu, String html) {
        var list = DetailNeighbors.activeList(root, plan.code(), plan.packageVersion(), menu);
        DetailNeighbors.ListTarget target = null;
        if (list != null) {
            try {
                var query = queries.read(plan.siteId(), plan.code(), plan.packageVersion(), list.queryId());
                if (Objects.equals(query.menuId(), plan.menuId()) && query.presentation() != null)
                    target = new DetailNeighbors.ListTarget(query.sourcePath(), query.id());
            } catch (IllegalArgumentException staleList) {
                // 正式列表配置失效时，仍可独立处理上下篇。
            }
        }
        return DetailNeighbors.inspect(html, plan.sourcePath(), target);
    }

    public CollectionEvidence collectionEvidence(Long siteId, String code, String version, String planId) {
        var plan = plans.read(siteId, code, version, planId);
        var menu = menus.selectPublicBindingMenu(siteId, plan.menuId());
        scope(plan, menu);
        var existing = activeApplication(plan, menu);
        return new CollectionEvidence(
                detailCollections.inspect(plan, menu),
                existing == null ? List.of() : existing.detailCollections().stream()
                        .map(DetailCollections.Binding::regionId).toList());
    }

    public record ActionEvidence(
            List<FormActionBinding.Candidate> actions,
            List<FormActionBinding.TargetMenu> targetMenus,
            List<FormActionBinding.Selection> selectedActions) {}

    public ActionEvidence actionEvidence(Long siteId, String code, String version, String planId) {
        var plan = plans.read(siteId, code, version, planId);
        var menu = menus.selectPublicBindingMenu(siteId, plan.menuId());
        scope(plan, menu);
        var candidates =
                plan.pageType() != 1 || !DetailFormActions.titleEnabled(menu)
                        ? List.<FormActionBinding.Candidate>of()
                        : detailActions.inspect(plan).stream()
                                .map(DetailFormActions.CandidateSource::candidate)
                                .toList();
        var targets =
                candidates.isEmpty()
                        ? List.<FormActionBinding.TargetMenu>of()
                        : Optional.ofNullable(menus.selectListBySiteProjectId(siteId))
                                .orElse(List.of())
                                .stream()
                                .filter(
                                        target ->
                                                FormActionBinding.single(target, siteId)
                                                        && Boolean.TRUE.equals(
                                                                target.getHasFrontChannel())
                                                        && Objects.equals(target.getStatus(), 3)
                                                        && !Boolean.TRUE.equals(
                                                                target.getIsDeleted()))
                                .map(
                                        target ->
                                                new FormActionBinding.TargetMenu(
                                                        target.getId(), target.getClassName()))
                                .toList();
        var application = activeApplication(plan, menu);
        var selected =
                application == null
                        ? List.<FormActionBinding.Selection>of()
                        : application.detailActions().stream()
                                .map(DetailFormActions.Binding::selection)
                                .toList();
        return new ActionEvidence(candidates, targets, selected);
    }

    public FormActionBinding.Candidate actionCandidate(
            Long siteId,
            String code,
            String version,
            String planId,
            FormActionBinding.Selection selection) {
        var plan = plans.read(siteId, code, version, planId);
        var menu = menus.selectPublicBindingMenu(siteId, plan.menuId());
        scope(plan, menu);
        if (plan.pageType() != 1 || !DetailFormActions.titleEnabled(menu))
            throw new IllegalArgumentException("详情预填需要已启用的当前内容标题");
        return DetailFormActions.requireCandidate(detailActions.inspect(plan), selection);
    }

    public TextBindingPublicApplicationService(
            TemplateFieldPlanService plans,
            TextBindingDraftService drafts,
            OneShotMapper menus,
            ContentMapper contents,
            UiPackageStorageService packages,
            TemplateStorageService templates,
            SiteRouteRegistry routes,
            com.xinhongru.site.content.ContentLinkService links,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.plans = plans;
        this.drafts = drafts;
        this.menus = menus;
        this.contents = contents;
        this.packages = packages;
        this.templates = templates;
        this.routes = routes;
        this.links = links;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Result inspect(Long siteId, String code, String version, String planId) {
        var plan = plans.readForWithdrawal(siteId, code, version, planId);
        var menu = menus.selectPublicBindingMenu(siteId, plan.menuId());
        scope(plan, menu);
        var active = activeApplication(plan, menu);
        if (active == null) return new Result(false, null, null, false, "尚未公开应用");
        verifyFile(code, active);
        var predecessor = active.previous(root, code);
        String route = configuredRoute(menu, plan.pageType());
        var priorPlan = plans.readForWithdrawal(siteId, code, version, active.planId());
        var differences = new ArrayList<String>();
        if (!TextBindingDraftService.sameReceiptSource(plan, priorPlan))
            differences.add("页面来源或双来源范围");
        if (!Objects.equals(plan.afterFieldConfigDigest(), active.fieldConfigDigest()))
            differences.add("本页栏目字段配置");
        if (!sameBindingSelections(plan, priorPlan)) {
            var changed = new LinkedHashSet<String>();
            var priorSelections = bindingSelections(priorPlan);
            var currentSelections = bindingSelections(plan);
            for (var binding : plan.bindings())
                if (!priorSelections.contains(bindingSelection(binding)))
                    changed.add(Objects.toString(binding.fieldName(), binding.name()));
            for (var binding : priorPlan.bindings())
                if (!currentSelections.contains(bindingSelection(binding)))
                    changed.add(Objects.toString(binding.fieldName(), binding.name()));
            differences.add("本页字段映射 " + String.join("、", changed));
        }
        if (!Objects.equals(route, active.routeUrl())) differences.add("栏目现配置地址 " + route);
        boolean requiresCurrentPlan = false;
        try {
            plans.read(siteId, code, version, planId);
        } catch (org.springframework.web.server.ResponseStatusException error) {
            if (error.getStatusCode() != org.springframework.http.HttpStatus.CONFLICT) throw error;
            requiresCurrentPlan = true;
        }
        String message = differences.isEmpty()
                ? "当前栏目已应用受控模板，本页面来源与映射一致"
                : "需复核：" + String.join("、", differences);
        return new Result(true, active.templatePath(), candidateUrl(priorPlan, menu, active.routeUrl(), active.initializedRecordId()), false, message,
                active.planId(), active.routeUrl(), predecessor == null ? null : predecessor.routeUrl(),
                null, null, requiresCurrentPlan);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, String planId, boolean confirmed) {
        return apply(siteId, code, version, planId, confirmed, null);
    }

    @Transactional
    public Result apply(
            Long siteId,
            String code,
            String version,
            String planId,
            boolean confirmed,
            List<FormActionBinding.Selection> actions) {
        return apply(siteId, code, version, planId, confirmed, actions, null);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, String planId, boolean confirmed,
            List<FormActionBinding.Selection> actions, List<String> collectionRegionIds) {
        return apply(siteId, code, version, planId, confirmed, actions, collectionRegionIds, null);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, String planId, boolean confirmed,
            List<FormActionBinding.Selection> actions, List<String> collectionRegionIds,
            String neighborCandidateId) {
        return apply(siteId, code, version, planId, confirmed, actions, collectionRegionIds,
                neighborCandidateId, null, null, false);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, String planId, boolean confirmed,
            List<FormActionBinding.Selection> actions, List<String> collectionRegionIds,
            String neighborCandidateId, String routeUrl, String changeDigest, boolean preview) {
        if (!preview) {
            transaction(confirmed);
            lockSite(siteId, code);
        }
        var plan = preview
                ? plans.readForWithdrawal(siteId, code, version, planId)
                : plans.preflight(siteId, code, version, planId).plan();
        if (TemplateFieldPlanService.usesSharedDetail(plan))
            throw new IllegalArgumentException("共享详情初值方案需另外审核并绑定公开详情模板标题与正文");
        if (!Set.of("CONFIRMED", "CONFIRMED_WITH_PENDING").contains(plan.status()))
            throw new IllegalArgumentException("请先确认当前字段计划");
        var menu = menus.lockFieldConfiguration(siteId, plan.menuId());
        scope(plan, menu);
        if (!Objects.equals(ContentFieldConfigDigest.of(menu), plan.afterFieldConfigDigest()))
            throw new IllegalArgumentException("当前字段配置已变化");
        var source = packages.readStaticPage(code, version, plan.sourcePath());
        if (!Objects.equals(source.digest(), plan.digest()))
            throw new IllegalArgumentException("公开来源已变化");
        var selected = TextBindingCompiler.publicBindings(plan);
        var original =
                compiler.compile(
                        source.html(),
                        selected,
                        plan.sources(),
                        plan.pageType(),
                        true,
                        plan.schemaVersion() >= 3);
        var compiled =
                compiler.compilePublic(
                        source.html(),
                        selected,
                        plan.sources(),
                        plan.pageType(),
                        plan.schemaVersion() >= 3);
        if (compiled.bound().isEmpty() || !compiled.pending().isEmpty())
            throw new IllegalArgumentException("公开模板仍有待处理绑定，请先人工确认");
        MenuNameBinding.names(menus, menu, compiled.bound(), true);
        String key = TemplateFieldPlanService.applicationId(plan);
        String current = pointer(menu, plan.pageType());
        var active = activeApplication(plan, menu);
        var stored = PublicBindingApplication.read(root, code, key);
        var existing = active != null ? active : stored;
        if (actions != null && !actions.isEmpty() && plan.pageType() != 1)
            throw new IllegalArgumentException("只有详情模板可以接入详情表单动作");
        var oldActions =
                existing == null ? List.<DetailFormActions.Binding>of() : existing.detailActions();
        var selections =
                actions == null
                        ? oldActions.stream().map(DetailFormActions.Binding::selection).toList()
                        : actions;
        if (!selections.isEmpty()
                && compiled.bound().stream().noneMatch(DetailFormActions::titleBinding))
            throw new IllegalArgumentException("详情预填需要已绑定的当前内容标题");
        var confirmedActions =
                selections.isEmpty()
                        ? List.<DetailFormActions.Binding>of()
                        : detailActions.confirm(plan, menu, selections);
        boolean actionsChanged = !Objects.equals(oldActions, confirmedActions);
        var oldCollections = existing == null ? List.<DetailCollections.Binding>of()
                : existing.detailCollections();
        var confirmedCollections = collectionRegionIds == null ? oldCollections
                : detailCollections.confirm(plan, menu, collectionRegionIds);
        boolean collectionsChanged = !Objects.equals(oldCollections, confirmedCollections);
        var oldNeighbors = existing == null ? null : existing.detailNeighbors();
        DetailNeighbors.Binding confirmedNeighbors = oldNeighbors;
        if (neighborCandidateId != null) {
            if (neighborCandidateId.isEmpty()) confirmedNeighbors = null;
            else {
                if (plan.pageType() != 1 || !neighborCandidateId.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("请选择当前详情的上下篇候选区域");
                confirmedNeighbors = neighbors(plan, menu, source.html()).stream()
                        .filter(candidate -> neighborCandidateId.equals(candidate.candidate().id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("上下篇缺少当前详情原页证据"));
            }
        }
        boolean neighborsChanged = !Objects.equals(oldNeighbors, confirmedNeighbors);
        Long restoringMenuId = null;
        if (existing != null
                && Objects.equals(existing.siteId(), siteId)
                && Objects.equals(existing.planId(), planId)
                && Objects.equals(existing.menuId(), plan.menuId())
                && Objects.equals(existing.packageVersion(), version)
                && Objects.equals(existing.previousTemplate(), current)) {
            verifyFile(code, existing);
            restoringMenuId = plan.menuId();
        }
        String publicHtml = PublicBindingResources.rebase(compiled.html(), plan.sourcePath());
        boolean withNavigation = navigationLinks != null
                && navigationLinks.hasConfirmation(siteId, code, version, plan.sourcePath());
        if (withNavigation) {
            var occupiedSelectors = new ArrayList<String>();
            confirmedActions.forEach(action -> occupiedSelectors.add(action.selector()));
            for (var collection : confirmedCollections) {
                if (collection.regionSelector() != null) occupiedSelectors.add(collection.regionSelector());
                if (collection.linkSelector() != null) occupiedSelectors.add(collection.linkSelector());
            }
            if (confirmedNeighbors != null) {
                occupiedSelectors.add(confirmedNeighbors.candidate().selector());
                if (confirmedNeighbors.candidate().returnSelector() != null)
                    occupiedSelectors.add(confirmedNeighbors.candidate().returnSelector());
            }
            publicHtml = navigationLinks.bind(siteId, code, version, plan.sourcePath(), publicHtml,
                    restoringMenuId, occupiedSelectors);
        }
        var reportCards = ReviewedReportCards.read(root, plan);
        var disclosureRows = ReviewedDisclosureRows.read(root, plan);
        var officialEntrances = ReviewedOfficialEntrances.read(root, plan);
        var documentList = ReviewedDocumentList.read(root, plan);
        var mediaLibrary = ReviewedMediaLibrary.read(root, plan, packages);
        var culture = ReviewedCultureRecords.read(root, plan);
        var currentCulture = culture == null ? null : culture.publishedRows(siteId, menu, menus, disclosures, links);
        if (currentCulture != null && currentCulture.books().isEmpty() && currentCulture.newspapers().isEmpty())
            throw new IllegalArgumentException("请先审核发布文化产品记录");
        var currentDisclosures = disclosureRows == null ? List.<Map<String, String>>of()
                : ReviewedDisclosureRows.publishedRows(disclosureRows.targetMenuId(), siteId, menu, menus, disclosures);
        if (disclosureRows != null && currentDisclosures.isEmpty())
            throw new IllegalArgumentException("请先发布本站经审核的披露记录");
        UnaryOperator<String> reviewedCorrection = reportCards != null ? reportCards::apply
                : disclosureRows != null ? disclosureRows::apply
                : officialEntrances != null ? officialEntrances::apply
                : documentList != null ? documentList::apply
                : culture != null ? culture::apply : mediaLibrary;
        if (reviewedCorrection != null) publicHtml = reviewedCorrection.apply(publicHtml);
        if (Integer.valueOf(0).equals(menu.getPageType()))
            publicHtml = FragmentRequestGuard.apply(publicHtml, code, version, plan.sourcePath(), packages);
        var shared =
                sharedContent == null
                        ? new PublicSharedContentCompiler.Compiled(publicHtml, List.of())
                        : sharedContent.compile(
                                siteId, code, version, plan.sourcePath(), publicHtml);
        compiled =
                new TextBindingCompiler.Compilation(
                        DetailNeighbors.compile(
                                DetailCollections.compile(
                                        DetailFormActions.compile(shared.html(), confirmedActions),
                                        confirmedCollections),
                                confirmedNeighbors),
                        compiled.bound(),
                        compiled.pending());
        compiler.validatePublicControlled(
                new TextBindingCompiler.Compilation(
                        ReviewedCultureRecords.withoutInstructions(ReviewedDisclosureRows.withoutInstructions(PublicSharedContentCompiler.withoutInstructions(
                                DetailFormActions.withoutInstructions(
                                        DetailCollections.withoutInstructions(
                                                DetailNeighbors.withoutInstructions(compiled.html(), confirmedNeighbors),
                                                confirmedCollections),
                                        confirmedActions),
                                shared.references()))),
                        compiled.bound(),
                        compiled.pending()),
                plan.pageType());
        boolean continued = active != null && !Objects.equals(active.planId(), plan.id());
        Long recordId = continued || active != null && !Objects.equals(active.key(), key)
                ? continuedRecord(plan, original, active)
                : drafts.requireInitializedRecord(plan, original);
        published(plan, menu, recordId);
        String oldRoute = configuredRoute(menu, plan.pageType());
        String route = routeUrl == null ? oldRoute : routeUrl.trim();
        route(siteId, code, menu, route, recordId);
        String templateDigest = PublicBindingApplication.digest(compiled.html());
        boolean switchApplication = active != null
                && (continued || !Objects.equals(active.routeUrl(), route)
                        || !Objects.equals(oldRoute, route)
                        || !Objects.equals(active.templateDigest(), templateDigest));
        boolean pageListsChanged = collectionsChanged
                && java.util.stream.Stream.concat(oldCollections.stream(), confirmedCollections.stream())
                        .anyMatch(binding -> "PAGE_LIST".equals(binding.mode()));
        boolean needsPreview = reviewedCorrection != null || pageListsChanged || active != null
                && (continued || routeUrl != null || neighborsChanged
                        || !Objects.equals(active.routeUrl(), oldRoute));
        var fingerprint = new ArrayList<>(Arrays.asList(
                plan.id(), plan.afterFieldConfigDigest(), plan.digest(), recordId, current,
                oldRoute, route, templateDigest, shared.references(), confirmedActions,
                confirmedCollections, confirmedNeighbors));
        if (disclosureRows != null) fingerprint.add(currentDisclosures);
        if (currentCulture != null) fingerprint.add(currentCulture);
        String expectedDigest = PublicBindingApplication.digest(
                new tools.jackson.databind.ObjectMapper().writeValueAsString(fingerprint));
        if (preview) {
            var pageLists = confirmedCollections.stream()
                    .filter(binding -> "PAGE_LIST".equals(binding.mode())).toList();
            UnaryOperator<String> correctionPreview = disclosureRows == null ? reviewedCorrection
                    : html -> disclosureRows.preview(html, currentDisclosures);
            if (currentCulture != null) correctionPreview = html -> culture.preview(html, currentCulture);
            var rendered = pageLists.isEmpty() ? draftPreview(plan, original, menu, recordId, correctionPreview)
                    : isolation.isolateRendered(plan.code(), plan.packageVersion(), plan.sourcePath(),
                            renderer.renderControlledPageListDraft(
                                    plan.code(), original, menu, detailCollections, pageLists));
            return new Result(active != null, current, candidateUrl(plan, menu, route, recordId),
                    false, "已使用当前CMS内容试编译，请审核变更及目标地址",
                    active == null ? null : active.planId(), active == null ? oldRoute : active.routeUrl(),
                    null, expectedDigest, rendered, false);
        }
        // 预览覆盖模板、内容身份和路由的快照。发布重试允许已到达相同的目标状态。
        if (active != null && !switchApplication && Objects.equals(oldRoute, route)) {
            verifyFile(code, active);
            return result(active, menu, true, "已应用；请核对当前运行路由");
        }
        if (needsPreview && !Objects.equals(expectedDigest, changeDigest))
            throw new IllegalArgumentException("当前公开应用或目标已变化，请重新试编译并确认");
        if (active == null && routeUrl != null && !Objects.equals(oldRoute, route))
            throw new IllegalArgumentException("首次公开应用请先通过栏目配置设置正式地址");
        if (switchApplication) {
            verifyFile(code, active);
            String replacementKey = UUID.nameUUIDFromBytes(
                    expectedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            String replacementPath = "cms-applied/" + replacementKey + "/" + templateDigest + ".html";
            var replacement = new PublicBindingApplication(
                    replacementKey, siteId, plan.menuId(), plan.id(), version, plan.pageType(),
                    ContentFieldConfigDigest.of(menu), compiled.bound(), replacementPath,
                    templateDigest, active.templatePath(), route, recordId, null, List.of(), null,
                    shared.references(), confirmedActions, confirmedCollections, null, confirmedNeighbors);
            // 模板内容变化仍须通过原有组合校验。
            if (!Objects.equals(active.templateDigest(), templateDigest))
                verifyComposition(code, plan, active, compiled, oldActions, oldCollections,
                        oldNeighbors, confirmedActions, confirmedCollections, confirmedNeighbors,
                        shared, withNavigation, reviewedCorrection);
            writeApplication(code, replacement, compiled.html());
            if (menus.switchPublicBindingRoute(siteId, plan.menuId(), plan.pageType(), current,
                    replacementPath, oldRoute, route) != 1)
                throw new IllegalArgumentException("栏目模板或地址并发变化，未完成切换");
            return new Result(true, replacementPath, candidateUrl(plan, menu, route, recordId), true,
                    Objects.equals(route, active.routeUrl())
                            ? "已应用新版本。请重新生成并核对静态产物"
                            : "已应用新版本。请核对当前地址和旧地址对应的静态产物",
                    plan.id(), route, active.routeUrl(), null, null, false);
        }
        String path = "cms-applied/" + key + "/" + templateDigest + ".html";
        if (PublicBindingApplication.managed(current)
                && (existing == null || !Objects.equals(current, path)))
            throw new IllegalArgumentException("当前公开应用仍占用栏目模板，请读取当前应用并明确撤回或恢复其凭据；不能叠加另一来源应用");
        if (existing != null
                && (!Objects.equals(existing.planId(), plan.id())
                        || !Objects.equals(existing.siteId(), siteId)
                        || !Objects.equals(existing.menuId(), plan.menuId())
                        || !Objects.equals(existing.packageVersion(), version)
                        || !Objects.equals(existing.routeUrl(), route)))
            throw new IllegalArgumentException("已有其他公开版本，请先读取原计划并撤回，不能自动重绑");
        if (existing != null && Objects.equals(current, path)) {
            verifyFile(code, existing);
            return result(existing, menu, true, "已应用；请核对当前运行路由");
        }
        if (existing != null && !Objects.equals(current, existing.previousTemplate()))
            throw new IllegalArgumentException("栏目模板已被人工修改，拒绝覆盖");
        if (existing != null && !Objects.equals(existing.templatePath(), path)) {
            boolean withShared =
                    !shared.references().isEmpty() || !existing.sharedContent().isEmpty();
            if ((!withNavigation && !withShared && !actionsChanged && !collectionsChanged && !neighborsChanged)
                    || !Objects.equals(
                            existing.fieldConfigDigest(), ContentFieldConfigDigest.of(menu))
                    || !Objects.equals(existing.initializedRecordId(), recordId))
                throw new IllegalArgumentException("已有其他公开版本，不能自动重绑");
            verifyFile(code, existing);
            verifyComposition(code, plan, existing, compiled, oldActions, oldCollections,
                    oldNeighbors, confirmedActions, confirmedCollections, confirmedNeighbors,
                    shared, withNavigation, reviewedCorrection);
            existing = null;
        }
        var application =
                existing != null
                        ? existing
                        : new PublicBindingApplication(
                                key,
                                siteId,
                                plan.menuId(),
                                plan.id(),
                                version,
                                plan.pageType(),
                                ContentFieldConfigDigest.of(menu),
                                compiled.bound(),
                                path,
                                PublicBindingApplication.digest(compiled.html()),
                                current,
                                route,
                                recordId,
                                null,
                                List.of(),
                                null,
                                shared.references(),
                                confirmedActions,
                                confirmedCollections,
                                null,
                                confirmedNeighbors);
        writeApplication(code, application, compiled.html());
        if (menus.updatePublicBindingTemplate(siteId, plan.menuId(), plan.pageType(), current, path)
                != 1) throw new IllegalArgumentException("栏目模板并发变化，未完成应用");
        return result(application, menu, true, "模板已保存；须重载现有站点路由并读取公开URL确认生效");
    }

    @Transactional
    public Result withdraw(
            Long siteId, String code, String version, String planId, boolean confirmed) {
        transaction(confirmed);
        lockSite(siteId, code);
        var plan = plans.readForWithdrawal(siteId, code, version, planId);
        var menu = menus.lockFieldConfiguration(siteId, plan.menuId());
        scope(plan, menu);
        var application = activeApplication(plan, menu);
        if (application == null || !Objects.equals(application.planId(), planId)) {
            var original = PublicBindingApplication.read(root, code, TemplateFieldPlanService.applicationId(plan));
            if (original != null && Objects.equals(pointer(menu, plan.pageType()), original.previousTemplate()))
                return result(original, menu, false, "已撤回；请核对当前运行路由");
            throw new IllegalArgumentException("当前应用已变化，请重新读取当前版本后再撤回");
        }
        String route = configuredRoute(menu, plan.pageType());
        if (!Objects.equals(route, application.routeUrl()))
            throw new IllegalArgumentException("栏目路由已有人工变更，请先试编译并应用目标地址");
        verifyFile(code, application);
        var previous = application.previous(root, code);
        String desiredRoute = previous == null ? route : previous.routeUrl();
        if (previous != null) {
            if (!Objects.equals(previous.initializedRecordId(), application.initializedRecordId())
                    || !Objects.equals(previous.fieldConfigDigest(), ContentFieldConfigDigest.of(menu)))
                throw new IllegalArgumentException("前一应用的内容身份或配置不再有效");
            verifyFile(code, previous);
            route(siteId, code, menu, desiredRoute, previous.initializedRecordId());
        }
        if (menus.switchPublicBindingRoute(siteId, plan.menuId(), plan.pageType(),
                application.templatePath(), application.previousTemplate(), route, desiredRoute) != 1)
            throw new IllegalArgumentException("栏目模板或地址并发变化，未完成撤回");
        if (previous == null) return result(application, menu, false, "已恢复原模板指针；须重载现有站点路由并确认");
        return new Result(true, previous.templatePath(), candidateUrl(plan, menu, desiredRoute,
                previous.initializedRecordId()), true, "已恢复前一应用；重载后核对地址及待处理产物",
                previous.planId(), desiredRoute, route, null, null, false);
    }

    private PublicBindingApplication activeApplication(TemplateFieldPlanService.Plan plan, OneshotEntity menu) {
        var application = PublicBindingApplication.atPath(root, plan.code(), pointer(menu, plan.pageType()));
        if (application != null && (!Objects.equals(application.siteId(), plan.siteId())
                || !Objects.equals(application.menuId(), plan.menuId())
                || application.pageType() != plan.pageType()
                || !Objects.equals(application.packageVersion(), plan.packageVersion())
                || application.queryId() != null || application.routeMenuId() != null
                || application.navigationLanding() != null))
            throw new IllegalArgumentException("当前栏目受控模板属于其他应用");
        return application;
    }

    private Long continuedRecord(TemplateFieldPlanService.Plan plan, TextBindingCompiler.Compilation original,
            PublicBindingApplication active) {
        var receipt = active.initial(root, plan.code());
        var receiptPlan = plans.readForWithdrawal(plan.siteId(), plan.code(), plan.packageVersion(), receipt.planId());
        if (!sameBindingSelections(plan, receiptPlan))
            throw new IllegalArgumentException("本页字段映射或来源区间已变化，请复核原应用与当前计划");
        Long recordId = drafts.requireInitializedRecord(plan, original, receiptPlan);
        if (!Objects.equals(active.initializedRecordId(), recordId))
            throw new IllegalArgumentException("公开应用与原初始化记录不一致");
        return recordId;
    }

    private static boolean sameBindingSelections(TemplateFieldPlanService.Plan left, TemplateFieldPlanService.Plan right) {
        return bindingSelections(left).equals(bindingSelections(right));
    }

    private static Set<List<?>> bindingSelections(TemplateFieldPlanService.Plan plan) {
        var result = new HashSet<List<?>>();
        for (var binding : plan.bindings())
            result.add(bindingSelection(binding));
        return result;
    }

    private static List<?> bindingSelection(TemplateFieldPlanService.Binding binding) {
        return Arrays.asList(binding.fieldName(), binding.fieldType(), binding.sources(), binding.menuId());
    }

    private UiPackageInspectionService.Preview draftPreview(TemplateFieldPlanService.Plan plan,
            TextBindingCompiler.Compilation compilation, OneshotEntity menu, Long recordId,
            UnaryOperator<String> reviewedCorrection) {
        String rendered = plan.pageType() == 0
                ? renderer.renderControlledDraft(plan.code(), compilation, menu)
                : renderer.renderControlledDetail(plan.code(), compilation, menu,
                        contents.selectActiveArticleInSiteMenu(plan.siteId(), menu.getId(), Math.toIntExact(recordId)));
        if (reviewedCorrection != null) rendered = reviewedCorrection.apply(rendered);
        return isolation.isolateRendered(plan.code(), plan.packageVersion(), plan.sourcePath(), rendered);
    }

    private void writeApplication(String code, PublicBindingApplication application, String html) {
        try {
            Path file = templates.resolveTemplateDir(code).resolve(application.templatePath());
            if (Files.exists(file)) verifyFile(code, application);
            else TemplateFileWriter.write(templates.resolveTemplateDir(code), application.templatePath(), html);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("公开模板无法保存", error);
        }
        application.write(root, code);
    }

    private void verifyComposition(String code, TemplateFieldPlanService.Plan plan,
            PublicBindingApplication previous, TextBindingCompiler.Compilation compiled,
            List<DetailFormActions.Binding> oldActions, List<DetailCollections.Binding> oldCollections,
            DetailNeighbors.Binding oldNeighbors, List<DetailFormActions.Binding> confirmedActions,
            List<DetailCollections.Binding> confirmedCollections, DetailNeighbors.Binding confirmedNeighbors,
            PublicSharedContentCompiler.Compiled shared, boolean withNavigation,
            UnaryOperator<String> reviewedCorrection) {
        try {
            String oldHtml = Files.readString(templates.resolveTemplateDir(code).resolve(previous.templatePath()));
            if (plan.pageType() == 0 && compiled.html().contains("data-cms-fragment-tabs"))
                oldHtml = FragmentRequestGuard.apply(oldHtml, code, plan.packageVersion(), plan.sourcePath(), packages);
            if (reviewedCorrection != null) oldHtml = reviewedCorrection.apply(oldHtml);
            PublicSharedContentCompiler.verifyReapplication(
                    DetailFormActions.withoutInstructions(DetailCollections.withoutInstructions(
                            DetailNeighbors.withoutInstructions(oldHtml, oldNeighbors), oldCollections), oldActions),
                    previous.sharedContent(),
                    DetailFormActions.withoutInstructions(DetailCollections.withoutInstructions(
                            DetailNeighbors.withoutInstructions(compiled.html(), confirmedNeighbors),
                            confirmedCollections), confirmedActions),
                    shared.references(), false, plan.sourcePath(), withNavigation
                            ? navigationLinks.reapplicationComparison(plan.siteId(), code, plan.packageVersion(), plan.sourcePath())
                            : java.util.function.UnaryOperator.identity());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("原公开模板不可读取", error);
        }
    }

    private void verifyFile(String code, PublicBindingApplication a) {
        Path directory = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
        Path path = directory.resolve(a.templatePath()).normalize();
        try {
            if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("站点模板目录不能是链接");
            Path current = directory;
            for (Path part : directory.relativize(path)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("公开模板不能是链接");
            }
            if (!path.startsWith(directory)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 2 * 1024 * 1024
                    || !path.toRealPath().startsWith(directory.toRealPath())
                    || !Objects.equals(
                            PublicBindingApplication.digest(Files.readString(path)),
                            a.templateDigest()))
                throw new IllegalArgumentException("公开模板文件已变化，不能覆盖或自动撤回");
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("公开模板不可读取", error);
        }
    }

    private void lockSite(Long siteId, String code) {
        // 同站的不同栏目可能争用一个地址。先锁站点，再读字段配置和冲突路由。
        var current = projects.selectByIdForUpdate(siteId);
        if (current == null || !Objects.equals(current.getProjectCode(), code))
            throw new IllegalArgumentException("当前站点不存在或身份已变化");
    }

    private static void transaction(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("请明确确认公开应用或撤回");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("公开应用必须通过CMS实际事务入口");
    }

    private static void scope(TemplateFieldPlanService.Plan plan, OneshotEntity menu) {
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), plan.siteId())
                || !Objects.equals(menu.getId().longValue(), plan.menuId())
                || !Objects.equals(menu.getPageType(), plan.pageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || Boolean.TRUE.equals(menu.getIsDeleted())
                || Objects.equals(menu.getId(), menu.getSiteRootId())
                || !Set.of(0, 1).contains(plan.pageType()))
            throw new IllegalArgumentException("公开目标栏目无效、跨站或为受保护根栏目");
    }

    private void published(TemplateFieldPlanService.Plan plan, OneshotEntity menu, Long recordId) {
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("请先通过原栏目配置明确开放前台");
        if (plan.pageType() == 0) {
            if (!Objects.equals(menu.getStatus(), 3))
                throw new IllegalArgumentException("请先通过原CMS发布单页，应用不代替发布");
        } else {
            var content =
                    contents.selectActiveArticleInSiteMenu(
                            plan.siteId(), menu.getId(), Math.toIntExact(recordId));
            if (content == null
                    || !Objects.equals(content.getId().longValue(), recordId)
                    || !Objects.equals(content.getClassid(), menu.getId())
                    || content.getSiteProjectId() != null
                            && !Objects.equals(content.getSiteProjectId(), plan.siteId())
                    || !Objects.equals(content.getStatus(), 1)
                    || content.getEntryIntoForceTime() == null
                    || content.getExpirationTime() == null
                    || content.getEntryIntoForceTime().isAfter(LocalDateTime.now())
                    || content.getExpirationTime().isBefore(LocalDateTime.now()))
                throw new IllegalArgumentException("原详情记录须为当前站点已发布有效内容；应用不创建或发布记录");
        }
    }

    void route(Long siteId, String code, OneshotEntity menu, String route, Long recordId) {
        if (route == null
                || route.isBlank()
                || route.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || route.length() > 512
                || route.contains(":")
                || route.contains("\\")
                || route.contains("..")
                || route.contains("?")
                || route.contains("#")
                || route.contains("%")
                || route.startsWith("//")) throw new IllegalArgumentException("请先通过原栏目配置设置有效的本站路由");
        String candidate = expand(route, menu);
        String normalized = normalize(candidate);
        if (normalized.isEmpty() || normalized.startsWith("__cms/") || normalized.startsWith("assets/")
                || normalized.equals("__cms") || normalized.equals("assets"))
            throw new IllegalArgumentException("请使用现有非根栏目路由或其他非保留路径");
        if (menu.getPageType() == 0 && candidate.contains("{")
                || menu.getPageType() == 1 && !candidate.contains("{id}"))
            throw new IllegalArgumentException("首片单页须固定本站路径、详情须已有{id}路由");
        String concrete = normalize(candidate.replace("{id}", String.valueOf(recordId)));
        if (candidate.replace("{id}", "").contains("{"))
            throw new IllegalArgumentException("当前公开路由参数仍待处理");
        var pattern = new RouteHandlerEntry(candidate, null);
        for (var handler : routes.getHandlers(code)) {
            if (handler.matches(concrete)
                    || pattern.matches(
                            normalize(handler.getUrlPattern().replaceAll("\\{[^}]+\\}", "1"))))
                throw new IllegalArgumentException("当前路径与自定义路由冲突，请先人工处理");
        }
        for (var other : Optional.ofNullable(menus.selectListBySiteProjectId(siteId)).orElse(List.of()))
            for (int slot = 0; slot < 2; slot++) {
                if (Objects.equals(other.getId(), menu.getId()) && slot == menu.getPageType()) continue;
                String value = configuredRoute(other, slot);
                if (value == null || value.isBlank()) continue;
                String expanded = expand(value, other);
                var p = new RouteHandlerEntry(expanded, null);
                if (p.matches(concrete)
                        || pattern.matches(normalize(expanded.replaceAll("\\{[^}]+\\}", "1"))))
                    throw new IllegalArgumentException("当前路径与其他栏目路由冲突，请先人工处理");
            }
    }

    private static String expand(String value, OneshotEntity menu) {
        return value.replace("{classId}", String.valueOf(menu.getId()))
                .replace("{parentId}", String.valueOf(menu.getParentId()))
                .replace("${className}", menu.getClassName() == null ? "" : menu.getClassName());
    }

    private static String normalize(String value) {
        return value.replaceAll("^/|/$", "");
    }

    private static String configuredRoute(OneshotEntity menu, int pageType) {
        return pageType == 0 ? menu.getListPageUrl() : menu.getDetailPageUrl();
    }

    private String candidateUrl(TemplateFieldPlanService.Plan plan, OneshotEntity menu, String route, Long recordId) {
        String value = expand(route, menu);
        if (plan.pageType() == 1) value = value.replace("{id}", String.valueOf(recordId));
        return org.springframework.web.util.UriComponentsBuilder.fromPath("/" + normalize(value))
                .build().encode().toUriString();
    }

    private static String pointer(OneshotEntity menu, int type) {
        return type == 0 ? menu.getListPageTemplate() : menu.getDetailPageTemplate();
    }

    private String publicUrl(PublicBindingApplication a, OneshotEntity menu) {
        if (a.pageType() == 0) return links.getListUrl(menu, 1);
        var checkedRoute =
                new OneshotEntity()
                        .setId(menu.getId())
                        .setParentId(menu.getParentId())
                        .setDetailPageUrl(expand(menu.getDetailPageUrl(), menu));
        String path =
                links.getDetailUrl(
                        new ContentEntity().setId(Math.toIntExact(a.initializedRecordId())),
                        checkedRoute);
        return org.springframework.web.util.UriComponentsBuilder.fromPath(
                        path.startsWith("/") ? path : "/" + path)
                .build()
                .encode()
                .toUriString();
    }

    private Result result(
            PublicBindingApplication a, OneshotEntity menu, boolean active, String message) {
        return new Result(active, a.templatePath(), publicUrl(a, menu), true, message,
                active ? a.planId() : null, active ? a.routeUrl() : null, null, null, null, false);
    }
}

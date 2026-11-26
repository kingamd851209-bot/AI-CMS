package com.xinhongru.cms.template;

import com.xinhongru.cms.siteservice.RetainedNavigationText;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.FormContentMapper;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.web.service.InfoService;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.utils.CommonUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Service
public class WorkbenchReleaseReviewService {
    private static final int MAX_PAGES = 200;
    private static final int MAX_REVIEW_BATCHES = 5;
    private static final int MAX_REQUIRED_INTERACTIONS = MAX_PAGES * 6;
    private static final int MAX_ADDITIONAL_EVIDENCE = 100;
    private static final int MAX_DEPLOYMENT_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DEPLOYMENT_FILES = 10_000;
    private static final long MAX_DEPLOYMENT_BYTES = 4L * 1024 * 1024 * 1024;
    private static final Set<String> DEPLOYMENTS = Set.of("CMS", "STATIC");
    private static final Set<String> KINDS =
            Set.of("FORM", "SEARCH", "FILTER", "PAGINATION", "ASYNC", "CUSTOM");
    private static final Set<String> BLOCKING_LINKS =
            Set.of("MISSING_ARTIFACT", "PACKAGE_FALLBACK", "MISSING_ANCHOR", "UNVERIFIED");

    private final WorkbenchStaticLinkCheckService links;
    private final PublicNavigationLinkCompiler publicNavigation;
    private final SiteWorkbenchHandoffService handoff;
    private final SiteWorkbenchContextService workspace;
    private final TemplateStorageService templates;
    private final SiteModelPlanService plans;
    private final SiteModelEvidenceService sourceEvidence;
    private final SiteModelNavigationService navigation;
    private final OneShotMapper menus;
    private final ContentMapper content;
    private final ContentLinkService routes;
    private final FormOneshotMapper categories;
    private final FormContentMapper formRows;
    private final ObjectMapper json = new ObjectMapper();
    private PackageDraftAnalysisService packageAnalysis;
    private InfoService nativeContent;
    private ResourceReferenceResolver nativeResources;
    private NativeHomePublication nativeHomePublication;

    @Autowired(required = false)
    void setNativeHomePublication(NativeHomePublication nativeHomePublication) {
        this.nativeHomePublication = nativeHomePublication;
    }

    @Autowired
    void setNativeProjectionEvidence(PackageDraftAnalysisService packageAnalysis, InfoService nativeContent,
            ResourceReferenceResolver nativeResources) {
        this.packageAnalysis = packageAnalysis;
        this.nativeContent = nativeContent;
        this.nativeResources = nativeResources;
    }

    public WorkbenchReleaseReviewService(
            WorkbenchStaticLinkCheckService links,
            SiteWorkbenchHandoffService handoff,
            SiteWorkbenchContextService workspace,
            TemplateStorageService templates,
            SiteModelPlanService plans,
            SiteModelNavigationService navigation,
            OneShotMapper menus,
            ContentMapper content,
            ContentLinkService routes,
            FormOneshotMapper categories,
            FormContentMapper formRows,
            PublicNavigationLinkCompiler publicNavigation,
            SiteModelEvidenceService sourceEvidence) {
        this.publicNavigation = publicNavigation;
        this.links = links;
        this.handoff = handoff;
        this.workspace = workspace;
        this.templates = templates;
        this.plans = plans;
        this.sourceEvidence = sourceEvidence;
        this.navigation = navigation;
        this.menus = menus;
        this.content = content;
        this.routes = routes;
        this.categories = categories;
        this.formRows = formRows;
    }

    public record Scope(
            String packageVersion, long planRevision, long navigationRevision, String deployment) {}

    public record Page(
            Integer menuId,
            String name,
            String sourcePath,
            String slot,
            Integer recordId,
            String route,
            String artifact,
            String state,
            String reason) {}

    public record Interaction(String route, String kind, Integer categoryId, boolean cmsRequired) {}

    public record Evidence(
            String route,
            String kind,
            Integer categoryId,
            String owner,
            String steps,
            String result,
            String outcome,
            Integer recordId) {}

    public record Save(
            Scope scope, String fingerprint, String expectedDigest, List<Evidence> evidence, Integer batch) {}

    public record DeploymentEvidence(String manifestDigest, String verifiedAt) {}

    public record Review(
            String fingerprint,
            String digest,
            boolean evidenceCurrent,
            boolean ready,
            List<Page> pages,
            List<Interaction> interactions,
            List<Evidence> evidence,
            WorkbenchStaticLinkCheckService.Result links,
            Integer nextBatch,
            String continuationToken,
            int batch,
            DeploymentEvidence deploymentEvidence) {}

    private record Saved(String fingerprint, List<Evidence> evidence, Integer batch) {}

    private record ReviewScope(
            SiteModelPlanService.Plan plan, SiteModelNavigationService.Navigation navigation) {}

    private enum DetailRequirement {
        REQUIRED,
        LIST_ONLY,
        UNCONFIRMED
    }

    private static String reviewKey(String id, Scope scope) {
        return id + ":" + scope.planRevision() + ":" + scope.navigationRevision() + ":" + scope.deployment();
    }

    public Review read(SiteProjectEntity site, String id, Scope scope, Runnable guard) {
        requireNavigation(site, id, scope, guard);
        var saved = workspace.staticReleaseReview(site.getId(), site.getProjectCode(), reviewKey(id, scope));
        var batch = saved.present() ? json.readValue(saved.content(), Saved.class).batch() : null;
        int firstBatch = batch != null && batch > 1 && batch <= MAX_REVIEW_BATCHES ? batch : 1;
        return review(site, id, scope, null, firstBatch, guard);
    }

    public Review continueRead(SiteProjectEntity site, String id, Scope scope, int batch,
            String previousFingerprint, Runnable guard) {
        if (batch < 2 || batch > MAX_REVIEW_BATCHES || previousFingerprint == null)
            throw new IllegalArgumentException("无效的整站续核批次");
        var previous = review(site, id, scope, null, batch - 1, guard);
        if (!Objects.equals(previous.nextBatch(), batch) || !Objects.equals(previous.continuationToken(), previousFingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上一批核验范围已变化，请重新核验");
        return review(site, id, scope, null, batch, guard);
    }

    public Review save(SiteProjectEntity site, String id, Save value, Runnable guard) {
        if (value == null || value.scope() == null) throw new IllegalArgumentException("缺少整站签收范围");
        int batch = value.batch() == null ? 1 : value.batch();
        if (batch < 1 || batch > MAX_REVIEW_BATCHES) throw new IllegalArgumentException("无效的整站核验批次");
        return review(site, id, value.scope(), value, batch, guard);
    }

    private static class PageBudget {
        final int limit;
        boolean remaining;

        PageBudget(int limit) { this.limit = limit; }

        boolean allows(int count) {
            if (count <= limit) return true;
            remaining = true;
            return false;
        }
    }

    private Review review(
            SiteProjectEntity site, String id, Scope scope, Save update, int batch, Runnable guard) {
        var reviewScope = requireNavigation(site, id, scope, guard);
        var nav = reviewScope.navigation();
        var plan = reviewScope.plan();
        var active = handoff.activeApplications(site);
        var pageBudget = new PageBudget(MAX_PAGES * batch);
        var scan = batch == 1 ? links.inspect(site, active) : links.inspect(site, active, pageBudget.limit);
        var checked = new ArrayList<Page>();
        var interactions = new ArrayList<Interaction>();
        var hashes = new TreeMap<String, String>();
        var claimed = new HashSet<String>();
        var reachable = reachable(scan.graph());
        var reviewedNavigation = scan.original() == null ? null : new RetainedNavigationText(
                scan.original().getParent().getParent(), site.getProjectCode(), scan.output());
        var actual =
                menus.selectListBySiteProjectId(site.getId()).stream()
                        .filter(
                                menu ->
                                        Objects.equals(menu.getSiteProjectId(), site.getId())
                                                && !Boolean.TRUE.equals(menu.getIsDeleted()))
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        OneshotEntity::getId, menu -> menu));
        try {
            var home = actual.get(site.getRootMenuId());
            var nativeHome = nativeHomePublication == null ? java.util.Optional.<NativeHomePublication.Proof>empty()
                    : nativeHomePublication.nativeProof(site.getId(), site.getProjectCode(), home);
            check(
                    site,
                    scan,
                    active,
                    reachable,
                    checked,
                    hashes,
                    claimed,
                    interactions,
                    home,
                    "首页",
                    null,
                    "LIST",
                    null,
                    "/",
                    nativeHome.isPresent());
            if (nativeHome.isPresent() && "READY".equals(checked.getFirst().state())) {
                for (String alias : nativeHome.get().aliases()) {
                    var path = uri(alias);
                    if (path == null || scan.output() == null) continue;
                    var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), path.getPath());
                    var aliasReachable = new HashSet<>(reachable);
                    if (file != null) aliasReachable.add(WorkbenchStaticLinkCheckService.relative(scan.output(), file));
                    check(site, scan, active, aliasReachable, checked, hashes, claimed, interactions,
                            home, "首页原地址", null, "LIST", null, alias, true);
                }
                var source = actual.get(nativeHome.get().sourceMenuId());
                if (source != null) {
                    String route = routes.getListUrl(source);
                    var path = uri(route);
                    if (path != null && scan.output() != null) {
                        var sourceReachable = new HashSet<>(reachable);
                        var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), path.getPath());
                        if (file != null) sourceReachable.add(WorkbenchStaticLinkCheckService.relative(scan.output(), file));
                        check(site, scan, active, sourceReachable, checked, hashes, claimed, interactions,
                                source, source.getClassName(), null, "LIST", null, route, true);
                    }
                }
            }
            for (var node : nav.nodes()) {
                if (Objects.equals(node.menuId(), site.getRootMenuId())) continue;
                if (!pageBudget.allows(checked.size() + 1)) break;
                if (node.kind() == SiteModelNavigationService.Kind.DIRECTORY
                        && !node.independentLanding()) {
                    checked.add(
                            new Page(
                                    node.menuId(),
                                    node.name(),
                                    node.sourcePath(),
                                    "LIST",
                                    null,
                                    null,
                                    null,
                                    "EXCEPTION",
                                    "已审结构中的目录，仅用于组织栏目"));
                    continue;
                }
                var menu = node.drift() ? null : actual.get(node.menuId());
                var backOfficeOnly = backOfficeOnlyPage(site, scan, node, menu, nav);
                if (backOfficeOnly != null) {
                    checked.add(backOfficeOnly);
                    continue;
                }
                var retained = retainedTextPage(site, scan, hashes, node, menu, reviewedNavigation);
                if (retained != null) {
                    checked.add(retained);
                    continue;
                }
                String route =
                        menu == null
                                ? null
                                : routes.getListUrl(menu)
                                        .replace(
                                                "${className}",
                                                Objects.toString(menu.getClassName(), ""));
                String name = menu == null ? node.name() : Objects.toString(menu.getClassName(), node.name());
                if (reviewedNavigation != null && reviewedNavigation.internalDestinationMismatch(
                        site.getUiPackageVersion(), node.menuId(), name, route)) {
                    checked.add(new Page(node.menuId(), name, node.sourcePath(), "LIST", null,
                            route, null, "PENDING", "栏目正式路由与已审核站内导航地址不一致"));
                    continue;
                }
                check(
                        site,
                        scan,
                        active,
                        reachable,
                        checked,
                        hashes,
                        claimed,
                        interactions,
                        menu,
                        name,
                        node.sourcePath(),
                        "LIST",
                        null,
                        route,
                        false);
                if (node.kind() == SiteModelNavigationService.Kind.LIST && menu != null) {
                    var detail = detailRequirement(plan, node, menu);
                    if (detail == DetailRequirement.LIST_ONLY) continue;
                    if (detail == DetailRequirement.UNCONFIRMED) {
                        checked.add(
                                new Page(
                                        menu.getId(),
                                        node.name(),
                                        node.sourcePath(),
                                        "DETAIL",
                                        null,
                                        null,
                                        null,
                                        "PENDING",
                                        "请先核对栏目是否需要独立详情"));
                        continue;
                    }
                    var records = content.selectPublicReleaseRecords(site.getId(), menu.getId());
                    if (records.isEmpty()) {
                        Long owner =
                                WorkbenchChecklistEvidenceService.listSampleOwner(
                                        plan, nav, node.sourcePath());
                        boolean approvedEmpty =
                                owner != null
                                        && !owner.equals(menu.getId().longValue())
                                        && content.countNavigationRecords(
                                                        site.getId(), menu.getId())
                                                == 0
                                        && checked.stream()
                                                .anyMatch(
                                                        page ->
                                                                Objects.equals(
                                                                                page.menuId(),
                                                                                menu.getId())
                                                                        && "LIST"
                                                                                .equals(page.slot())
                                                                        && "READY"
                                                                                .equals(
                                                                                        page
                                                                                                .state()));
                        checked.add(
                                new Page(
                                        menu.getId(),
                                        node.name(),
                                        node.sourcePath(),
                                        "DETAIL",
                                        null,
                                        null,
                                        null,
                                        approvedEmpty ? "EXCEPTION" : "PENDING",
                                        approvedEmpty
                                                ? "已审共用页面的样本归属栏目 #" + owner + "。本栏目为空，正式列表已核对"
                                                : "栏目没有可用于验收详情页的有效已发布记录"));
                    }
                    for (var record : records) {
                        if (!pageBudget.allows(checked.size() + 1)) break;
                        check(
                                site,
                                scan,
                                active,
                                reachable,
                                checked,
                                hashes,
                                claimed,
                                interactions,
                                menu,
                                node.name(),
                                null,
                                "DETAIL",
                                record.getId(),
                                routes.getDetailUrl(record, menu),
                                false);
                    }
                }
            }
            reconcileNativeListsAndProjections(site, nav, actual, scan, active, reachable, checked,
                    hashes, claimed, interactions, pageBudget);
            reconcileReviewedCultureCatalogs(site, nav, actual, scan, active, reachable, checked,
                    hashes, claimed, interactions, pageBudget);
            reconcilePublishedEntries(site, plan, nav, actual, scan, active, reachable, checked, hashes,
                    claimed, interactions);
            reconcileReviewedSharedParents(site, nav, scan, active, checked, hashes, reviewedNavigation);
            inspectAdditional(
                    site, scan, active, reachable, checked, hashes, claimed, interactions, pageBudget);
        } catch (IOException error) {
            throw new IllegalStateException("整站静态产物读取期间发生变化，请重新核验", error);
        }
        boolean remainingPages = pageBudget.remaining
                || scan.result().truncated() && scan.result().scannedPages() == pageBudget.limit;
        if (remainingPages)
            checked.add(
                    new Page(
                            null,
                            "站点",
                            null,
                            "LIST",
                            null,
                            null,
                            null,
                            "PENDING",
                            batch < MAX_REVIEW_BATCHES ? "本批核验已完成，仍有页面待续核" : "已达到整站页面核验上限"));
        for (var item : scan.result().items()) {
            if (!"DYNAMIC".equals(item.state()) || !reachable.contains(item.outputPath())) continue;
            String route =
                    checked.stream()
                            .filter(page -> item.outputPath().equals(page.artifact()))
                            .map(Page::route)
                            .findFirst()
                            .orElse("/" + item.outputPath());
            if (interactions.stream()
                    .noneMatch(test -> route.equals(test.route()) && "CUSTOM".equals(test.kind())))
                interactions.add(new Interaction(route, "CUSTOM", null, false));
        }
        var deploymentEvidence = "STATIC".equals(scope.deployment())
                ? checkIndependentHost(site, scope, hashes, checked, interactions)
                : null;
        var allRequired = interactions.stream().distinct().toList();
        boolean interactionsLimited = allRequired.size() > MAX_REQUIRED_INTERACTIONS;
        var required = allRequired.stream().limit(MAX_REQUIRED_INTERACTIONS).toList();
        if (interactionsLimited) {
            checked.add(
                    new Page(
                            null,
                            "站点",
                            null,
                            "LIST",
                            null,
                            null,
                            null,
                            "PENDING",
                            "已达到交互核验上限，仍有功能待单独核对"));
        }
        for (var entry : nav.supplementalEvidence()) {
            boolean keepsLink = entry.keepsOriginalLink(nav.supplementalEvidence());
            checked.add(
                    new Page(
                            null,
                            entry.name(),
                            entry.sourcePath(),
                            "NAVIGATION",
                            null,
                            null,
                            null,
                            keepsLink ? "READY" : "PENDING",
                            keepsLink ? "保留原外链或锚点，静态链接核验仍检查产物中的链接" : "已发布导航发现新入口，需核对栏目归属并更新导航回执"));
        }
        String fingerprint =
                digest(json.writeValueAsString(List.of(scope, checked, scan.result(), hashes)));
        String key = reviewKey(id, scope);
        var document = workspace.staticReleaseReview(site.getId(), site.getProjectCode(), key);
        var saved =
                document.present()
                        ? json.readValue(document.content(), Saved.class)
                        : new Saved("", List.of(), null);
        if (update != null) {
            if (!fingerprint.equals(update.fingerprint()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "页面或链接核验结果已变化，请重新核验");
            validateEvidence(site, required, scan.output(), update.evidence());
            saved = new Saved(fingerprint, List.copyOf(update.evidence()), batch);
        }
        boolean current = fingerprint.equals(saved.fingerprint());
        var evidence = saved.evidence();
        boolean browserReady =
                !interactionsLimited
                        && (required.isEmpty() || current)
                        && required.stream()
                                .allMatch(
                                        item ->
                                                (!item.cmsRequired()
                                                                || "CMS".equals(scope.deployment()))
                                                        && evidence.stream()
                                                                .anyMatch(
                                                                        row ->
                                                                                matches(item, row)
                                                                                        && "PASSED"
                                                                                                .equals(
                                                                                                        row
                                                                                                                .outcome())
                                                                                        && (update
                                                                                                        != null
                                                                                                || validForm(
                                                                                                        site,
                                                                                                        row,
                                                                                                        scan
                                                                                                                .output()))));
        boolean ready =
                browserReady
                        && (current
                                ? evidence.stream()
                                        .allMatch(
                                                row ->
                                                        Set.of("PASSED", "EXCEPTION")
                                                                .contains(row.outcome()))
                                : evidence.isEmpty())
                        && checked.stream()
                                .allMatch(
                                        page -> Set.of("READY", "EXCEPTION").contains(page.state()))
                        && "SCANNED".equals(scan.result().state())
                        && !scan.result().truncated()
                        && scan.result().items().stream()
                                .noneMatch(item -> BLOCKING_LINKS.contains(item.state()));
        requireNavigation(site, id, scope, guard);
        if (update != null) {
            document =
                    workspace.saveStaticReleaseReview(
                            site.getId(),
                            site.getProjectCode(),
                            key,
                            json.writeValueAsString(saved),
                            update.expectedDigest());
        }
        return new Review(
                fingerprint,
                document.digest(),
                current,
                ready,
                checked,
                required,
                saved.evidence(),
                scan.result(),
                remainingPages && batch < MAX_REVIEW_BATCHES ? batch + 1 : null,
                remainingPages && batch < MAX_REVIEW_BATCHES
                        ? digest(json.writeValueAsString(List.of(site.getId(), id, scope, batch, fingerprint))) : null,
                batch,
                deploymentEvidence);
    }

    private DetailRequirement detailRequirement(
            SiteModelPlanService.Plan plan,
            SiteModelNavigationService.Node node,
            OneshotEntity menu) {
        if ((menu.getDetailPageUrl() != null && !menu.getDetailPageUrl().isBlank())
                || (menu.getDetailPageTemplate() != null
                        && !menu.getDetailPageTemplate().isBlank()))
            return DetailRequirement.REQUIRED;
        var proposal = plan.proposal();
        if (proposal == null
                || node.modelKey() == null
                || node.sourcePath() == null
                || proposal.models().stream()
                        .noneMatch(
                                model ->
                                        node.modelKey().equals(model.key())
                                                && "LIST".equals(model.kind())
                                                && Objects.equals(
                                                        model.menuId(), menu.getId().longValue())))
            return DetailRequirement.UNCONFIRMED;
        var regions =
                proposal.regions().stream()
                        .filter(region -> node.modelKey().equals(region.modelKey()))
                        .toList();
        if (regions.stream().anyMatch(region -> "DETAIL".equals(region.mode())))
            return DetailRequirement.REQUIRED;
        var lists = regions.stream().filter(region -> "LIST".equals(region.mode())).toList();
        if (!lists.isEmpty()
                && lists.stream()
                        .allMatch(
                                region ->
                                        node.sourcePath().equals(region.sourcePath())
                                                && region.maintenance() == null
                                                && !region.records().isEmpty()
                                                && sourceEvidence.confirmsDisplayOnlyList(
                                                        plan.code(),
                                                        plan.packageVersion(),
                                                        region)))
            return DetailRequirement.LIST_ONLY;
        return DetailRequirement.UNCONFIRMED;
    }

    private ReviewScope requireNavigation(
            SiteProjectEntity site, String id, Scope scope, Runnable guard) {
        if (scope == null
                || scope.deployment() == null
                || !DEPLOYMENTS.contains(scope.deployment())
                || !Objects.equals(site.getUiPackageVersion(), scope.packageVersion()))
            throw new IllegalArgumentException("签收必须使用当前页面包和有效部署方式");
        var siteScope =
                new SiteModelNavigationService.Scope(
                        site.getId(),
                        site.getProjectCode(),
                        scope.packageVersion(),
                        site.getRootMenuId());
        var plan = plans.get(site.getId(), site.getProjectCode(), scope.packageVersion(), id);
        var nav = navigation.get(siteScope, id, guard);
        if (!"CONFIRMED".equals(plan.status())
                || plan.revision() != scope.planRevision()
                || nav.planRevision() != plan.revision()
                || nav.revision() != scope.navigationRevision()
                || nav.status() != SiteModelNavigationService.Status.APPLIED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先应用当前版本的已审核整站结构");
        guard.run();
        return new ReviewScope(plan, nav);
    }

    private void check(
            SiteProjectEntity site,
            WorkbenchStaticLinkCheckService.Inspection scan,
            List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable,
            List<Page> checked,
            Map<String, String> hashes,
            Set<String> claimed,
            List<Interaction> interactions,
            OneshotEntity menu,
            String name,
            String source,
            String slot,
            Integer recordId,
            String route,
            boolean nativeRoute)
            throws IOException {
        var app =
                menu == null
                        ? null
                        : active.stream()
                                .filter(
                                        item ->
                                                Objects.equals(item.menuId(), menu.getId())
                                                        && slot.equals(item.slot()))
                                .findFirst()
                                .orElse(null);
        if (source == null && app != null) source = app.sourcePath();
        boolean nativeTemplate = nativeRoute && app != null && "UNMANAGED_TEMPLATE".equals(app.state());
        var path = uri(route);
        Path file =
                path == null || scan.output() == null
                        ? null
                        : WorkbenchStaticLinkCheckService.localFile(scan.output(), path.getPath());
        String artifact =
                file == null ? null : WorkbenchStaticLinkCheckService.relative(scan.output(), file);
        if (artifact != null && !claimed.add(artifact)) {
            var previous =
                    checked.stream().filter(page -> artifact.equals(page.artifact())).findFirst();
            if (previous.isPresent()
                    && Objects.equals(previous.get().menuId(), menu == null ? null : menu.getId())
                    && Objects.equals(previous.get().recordId(), recordId)
                    && previous.get().slot().equals(slot)) return;
        }
        String state = "PENDING";
        String reason;
        if (menu == null || !Boolean.TRUE.equals(menu.getHasFrontChannel()))
            reason = "栏目尚未绑定已审结构或未开放前台";
        else if (!nativeTemplate && (app == null
                || !"TEMPLATE_VERIFIED".equals(app.state())
                || !Objects.equals(source, app.sourcePath()))) reason = "正式模板与已审原页或应用回执不符";
        else if (!"DETAIL".equals(slot)
                && Integer.valueOf(0).equals(menu.getPageType())
                && !(nativeTemplate && Objects.equals(menu.getId(), site.getRootMenuId()))
                && !handoff.hasPublishedSinglePage(site,menu,app)) reason = "请先发布 CMS 单页的实际维护内容";
        else if (path == null) reason = "缺少可发布的正式站内路由";
        else if (file == null)
            reason =
                    scan.original() != null
                                    && WorkbenchStaticLinkCheckService.localFile(
                                                    scan.original(), path.getPath())
                                            != null
                            ? "缺少正式产物，CMS 可能回退到原页面包"
                            : "缺少正式静态产物";
        else if (Files.size(file) > 2 * 1024 * 1024) reason = "页面超过核验上限";
        else {
            String html = Files.readString(file);
            hashes.put(artifact, digest(html));
            String proof =
                    workspace.staticArtifactProof(site.getId(), site.getProjectCode(), file, html);
            String expected =
                    StaticRenderProof.of(
                            site.getId(), menu.getId(), "DETAIL".equals(slot), app.templatePath());
            var templateRoot = templates.resolveTemplateDir(site.getProjectCode()).toAbsolutePath().normalize();
            var template = templateRoot.resolve(app.templatePath()).normalize();
            if (proof == null) reason = "文件缺少可核对的发布回执，请重新发布";
            else if (!expected.equals(proof)) reason = "产物由其他栏目或模板生成，可能是旧文件";
            else if (!template.startsWith(templateRoot) || !Files.isRegularFile(template)
                    || !template.toRealPath().startsWith(templateRoot.toRealPath())
                    || Files.getLastModifiedTime(template)
                                    .compareTo(Files.getLastModifiedTime(file))
                            > 0) reason = "模板更新后尚未重新生成产物";
            else if (!reachable.contains(artifact)) reason = "从静态首页沿链接无法到达此页";
            else {
                var targets = publicNavigation.approvedLinks(site.getId(), site.getProjectCode(),
                        site.getUiPackageVersion(), app.sourcePath());
                String mismatch = nativeTemplate ? null : navigationMismatch(site, scan, html, path, targets);
                if (mismatch == null) mismatch = retainedTextMismatch(site, scan, path.getPath(), html);
                if (mismatch == null) {
                    state = "READY";
                    reason = nativeTemplate ? "已审原生栏目入口与当前模板、记录和生成回执一致"
                            : "产物、栏目模板和首页链接已核对";
                } else {
                    reason = mismatch;
                }
            }
            detectInteractions(html, route, interactions);
        }
        checked.add(
                new Page(
                        menu == null ? null : menu.getId(),
                        name,
                        source,
                        slot,
                        recordId,
                        route,
                        artifact,
                        state,
                        reason));
    }

    private void reconcilePublishedEntries(SiteProjectEntity site, SiteModelPlanService.Plan plan,
            SiteModelNavigationService.Navigation nav, Map<Integer, OneshotEntity> actual,
            WorkbenchStaticLinkCheckService.Inspection scan, List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable, List<Page> checked, Map<String, String> hashes, Set<String> claimed,
            List<Interaction> interactions) throws IOException {
        var candidates = nav.nodes().stream()
                .filter(node -> node.menuId() != null && actual.containsKey(node.menuId()) && !node.drift()
                        && node.sourcePath() != null && !node.independentLanding()
                        && checked.stream().anyMatch(page -> Objects.equals(page.menuId(), node.menuId())
                                && "LIST".equals(page.slot()) && "PENDING".equals(page.state())))
                .toList();
        if (candidates.isEmpty()) return;
        var scope = new SiteModelNavigationService.Scope(site.getId(), site.getProjectCode(),
                site.getUiPackageVersion(), site.getRootMenuId());
        var byNode = new HashMap<String, PublicNavigationLinkCompiler.Target>();
        var conflicts = new HashSet<String>();
        var seen = new HashSet<String>();
        for (var page : checked) {
            if (!"READY".equals(page.state()) || !"LIST".equals(page.slot())
                    || page.sourcePath() == null || !seen.add(page.sourcePath())) continue;
            try {
                var entries = publicNavigation.releaseEntryTargets(scope, plan.id(), page.sourcePath(),
                        page.menuId(), candidates);
                entries.forEach((key, target) -> {
                    var previous = byNode.putIfAbsent(key, target);
                    if (previous != null && (!Objects.equals(previous.menuId(), target.menuId())
                            || !previous.url().equals(target.url()) || previous.kind() != target.kind()))
                        conflicts.add(key);
                });
            } catch (IllegalArgumentException | ResponseStatusException changed) {
                // 无法用发生变化的来源位置证明入口，保留相应待办。
            }
        }
        for (var node : candidates) {
            var target = byNode.get(node.key());
            if (target == null || conflicts.contains(node.key())) continue;
            var menu = actual.get(node.menuId());
            var pending = checked.stream().filter(page -> Objects.equals(page.menuId(), node.menuId())
                    && "PENDING".equals(page.state())).toList();
            if (!Objects.equals(node.menuId(), target.menuId())) {
                var destination = verifiedEntryDestination(target, checked, scan);
                if (destination == null || pending.stream().anyMatch(page -> page.recordId() != null || page.artifact() != null)
                        || active.stream().anyMatch(app -> Objects.equals(app.menuId(), node.menuId()))
                        || node.kind() == SiteModelNavigationService.Kind.LIST
                                && content.countNavigationRecords(site.getId(), node.menuId()) != 0) continue;
                int index = checked.indexOf(pending.getFirst());
                checked.removeAll(pending);
                checked.add(index, new Page(node.menuId(), node.name(), node.sourcePath(), "NAVIGATION", null,
                        target.url(), destination.artifact(), "READY",
                        switch (target.kind()) {
                            case FRAGMENT -> "已审入口到达正式页面的正文锚点";
                            case CONTENT_LIST -> "已审入口共用已发布栏目列表及其详情";
                            default -> "已审入口到达具备发布回执的目标栏目";
                        }));
            } else if (target.kind() == PublicNavigationLinkCompiler.TargetKind.CONTENT_LIST
                    && node.kind() == SiteModelNavigationService.Kind.LIST
                    && pending.stream().anyMatch(page -> "LIST".equals(page.slot()) && target.url().equals(page.route()))
                    && active.stream().anyMatch(app -> Objects.equals(app.menuId(), node.menuId())
                            && "LIST".equals(app.slot()) && "UNMANAGED_TEMPLATE".equals(app.state()))) {
                for (var page : pending) {
                    if (page.route() == null) continue;
                    checked.remove(page);
                    check(site, scan, active, reachable, checked, hashes, claimed, interactions, menu,
                            page.name(), page.sourcePath(), page.slot(), page.recordId(), page.route(), true);
                }
            }
        }
    }

    private void reconcileNativeListsAndProjections(SiteProjectEntity site,
            SiteModelNavigationService.Navigation nav, Map<Integer, OneshotEntity> actual,
            WorkbenchStaticLinkCheckService.Inspection scan, List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable, List<Page> checked, Map<String, String> hashes, Set<String> claimed,
            List<Interaction> interactions, PageBudget pageBudget) throws IOException {
        if (packageAnalysis == null || scan.output() == null || scan.original() == null) return;
        var packageScope = new PackageDraftAnalysisService.Scope(
                site.getId(), site.getProjectCode(), site.getUiPackageVersion());
        var reviewed = packageAnalysis.recoveredApplicationState(packageScope);
        if (reviewed == null) return;
        for (var page : List.copyOf(checked)) {
            if (!"PENDING".equals(page.state()) || !"LIST".equals(page.slot()) || page.sourcePath() == null
                    || page.artifact() == null) continue;
            var parent = actual.get(page.menuId());
            if (parent == null
                    || active.stream().noneMatch(app -> Objects.equals(app.menuId(), parent.getId())
                            && "LIST".equals(app.slot()) && "UNMANAGED_TEMPLATE".equals(app.state()))) continue;
            var originals = reviewed.pages().stream().filter(source -> page.sourcePath().equals(source.sourcePath())
                    && "READY".equals(source.state()) && "CONFIRMED".equals(source.review())
                    && Objects.equals(source.reviewMenuId(), parent.getId().longValue())).toList();
            if (originals.size() != 1
                    || WorkbenchStaticLinkCheckService.localFile(scan.original(), "/" + page.sourcePath()) == null) continue;
            var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), uri(page.route()).getPath());
            if (file == null || Files.size(file) > 2 * 1024 * 1024) continue;
            var html = Jsoup.parse(Files.readString(file));
            if (Integer.valueOf(1).equals(parent.getPageType())) {
                reconcileNativeList(site, parent, page, html, scan, active, reachable, checked,
                        hashes, claimed, interactions);
                continue;
            }
            if (!Integer.valueOf(0).equals(parent.getPageType()) || !Integer.valueOf(3).equals(parent.getStatus())) continue;
            if (html.selectFirst("[data-cms-native-list]") != null) {
                reconcileDirectNativeLists(site, actual, scan, active, reachable, checked, hashes,
                        claimed, interactions, page, parent, html);
                continue;
            }
            if (html.selectFirst("[data-cms-section-menu]") != null) {
                reconcileNativeSections(site, nav, actual, scan, active, reachable, checked, hashes, claimed,
                        interactions, page, parent, html);
                continue;
            }
            var markers = html.select("[data-cms-projection-menu][data-cms-projection-limit]");
            if (markers.size() != 1) continue;
            var marker = markers.first();
            int childId;
            int limit;
            try {
                childId = Integer.parseInt(marker.attr("data-cms-projection-menu"));
                limit = Integer.parseInt(marker.attr("data-cms-projection-limit"));
            } catch (NumberFormatException invalid) {
                continue;
            }
            var child = actual.get(childId);
            if (limit < 1 || limit > MAX_PAGES || child == null || child.getParentId() != parent.getId()
                    || !Objects.equals(child.getSiteRootId(), site.getRootMenuId())
                    || !Integer.valueOf(1).equals(child.getPageType()) || !Boolean.TRUE.equals(child.getHasManageChannel())
                    || nav.nodes().stream().anyMatch(node -> Objects.equals(node.menuId(), childId))) continue;
            var childRoute = routes.getListUrl(child);
            if (uri(childRoute) == null || html.select("a[href]").stream()
                    .noneMatch(link -> childRoute.equals(link.attr("href")))) continue;
            var records = content.selectPublicReleaseRecords(site.getId(), childId);
            if (records.isEmpty() || !pageBudget.allows(checked.size() + records.size() + 1)) continue;
            var expected = nativeContent.getArticleByClassId(childId, limit, 0, 1);
            if (!matchesNativeRows(child, limit, records, expected, marker, "data-cms-projection-record")) continue;
            var verified = new ArrayList<Page>();
            var verifiedHashes = new TreeMap<String, String>();
            var verifiedRoutes = new HashSet<String>();
            var verifiedInteractions = new ArrayList<Interaction>();
            check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                    parent, page.name(), page.sourcePath(), "LIST", null, page.route(), true);
            check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                    child, child.getClassName(), null, "LIST", null, childRoute, true);
            for (var record : records)
                check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                        child, child.getClassName(), null, "DETAIL", record.getId(),
                        routes.getDetailUrl(record, child), true);
            if (verified.size() != records.size() + 2 || verifiedRoutes.size() != verified.size()
                    || verified.stream().anyMatch(item -> !"READY".equals(item.state()))
                    || checked.stream().anyMatch(item -> item != page && item.artifact() != null
                            && verifiedRoutes.contains(item.artifact()))) continue;
            int index = checked.indexOf(page);
            checked.remove(index);
            checked.addAll(index, verified);
            hashes.putAll(verifiedHashes);
            claimed.addAll(verifiedRoutes);
            verifiedInteractions.stream().filter(item -> !interactions.contains(item)).toList().forEach(interactions::add);
        }
    }

    private OneshotEntity nativeFieldSnapshot(SiteProjectEntity site, OneshotEntity column) {
        // The site-wide menu projection intentionally omits model definitions and field values.
        var current = menus.selectPublicBindingMenu(site.getId(), column.getId().longValue());
        if (current == null || !Objects.equals(current.getId(), column.getId())
                || !Objects.equals(current.getSiteProjectId(), site.getId())
                || !Objects.equals(current.getSiteRootId(), site.getRootMenuId())
                || current.getParentId() != column.getParentId()
                || !Objects.equals(current.getPageType(), column.getPageType())
                || !Objects.equals(current.getStatus(), column.getStatus())
                || !Objects.equals(current.getHasManageChannel(), column.getHasManageChannel())
                || !Objects.equals(current.getHasFrontChannel(), column.getHasFrontChannel())
                || !Objects.equals(current.getListPageTemplate(), column.getListPageTemplate())
                || !Objects.equals(current.getListPageUrl(), column.getListPageUrl())
                || !Objects.equals(current.getDetailPageTemplate(), column.getDetailPageTemplate())
                || !Objects.equals(current.getDetailPageUrl(), column.getDetailPageUrl())) return null;
        return current;
    }

    private void reconcileDirectNativeLists(SiteProjectEntity site, Map<Integer, OneshotEntity> actual,
            WorkbenchStaticLinkCheckService.Inspection scan, List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable, List<Page> checked, Map<String, String> hashes, Set<String> claimed,
            List<Interaction> interactions, Page page, OneshotEntity parent, Element html) throws IOException {
        var verified = new ArrayList<Page>();
        var verifiedHashes = new TreeMap<String, String>();
        var verifiedRoutes = new HashSet<String>();
        var verifiedInteractions = new ArrayList<Interaction>();
        var ids = new HashSet<Integer>();
        for (var marker : html.select("[data-cms-native-list]")) {
            Integer id = markerId(marker, "data-cms-native-list");
            var list = id == null ? null : actual.get(id);
            if (list == null || !ids.add(id) || list.getParentId() != parent.getId()
                    || !Objects.equals(site.getRootMenuId(), list.getSiteRootId())
                    || !Objects.equals(site.getId(), list.getSiteProjectId())
                    || !Integer.valueOf(1).equals(list.getPageType()) || !Boolean.TRUE.equals(list.getHasManageChannel()))
                return;
            var snapshot = nativeFieldSnapshot(site, list);
            if (snapshot == null) return;
            var model = ContentModelCodec.read(snapshot.getModelField()).stream()
                    .filter(field -> ContentFieldDefinition.enabled(field.getIsOpen()))
                    .collect(java.util.stream.Collectors.toMap(ContentFieldDefinition::getFieldName, field -> field));
            var records = CommonUtils.readPublishedSectionItems(nativeContent, site.getId(), id);
            if (records.stream().anyMatch(record -> !id.equals(record.getClassid())
                    || !site.getId().equals(record.getSiteProjectId()))) return;
            boolean publicList = Boolean.TRUE.equals(list.getHasFrontChannel());
            if (!matchesDirectRows(site, list, model, records, marker, !publicList, false)) return;
            if (!publicList) {
                if (marker.hasAttr("data-cms-native-list-url")
                        || !Objects.toString(list.getListPageUrl(), "").isBlank()
                        || !Objects.toString(list.getDetailPageUrl(), "").isBlank()) return;
                continue;
            }
            String listUrl = routes.getListUrl(list);
            if (uri(listUrl) == null || !listUrl.equals(marker.attr("data-cms-native-list-url"))
                    || html.select("a[href]").stream().noneMatch(link -> listUrl.equals(link.attr("href")))) return;
            Element publishedIndex = nativeProjectionArtifact(scan, listUrl);
            if (publishedIndex == null) return;
            var lists = publishedIndex.select("[data-cms-native-list]");
            if (lists.size() != 1 || !id.equals(markerId(lists.first(), "data-cms-native-list"))
                    || !matchesDirectRows(site, list, model, records, lists.first(), false, true)) return;
            check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                    list, list.getClassName(), null, "LIST", null, listUrl, true);
            var anchors = marker.select("[data-cms-native-row][data-cms-native-anchor]").stream()
                    .filter(row -> row.closest("[data-cms-native-list]") == marker)
                    .map(row -> markerId(row, "data-cms-native-row")).collect(java.util.stream.Collectors.toSet());
            for (var record : records) {
                String detailUrl = routes.getDetailUrl(record, list);
                var detailDocument = nativeProjectionArtifact(scan, detailUrl);
                if (detailDocument == null) return;
                var details = detailDocument.select("[data-cms-native-detail]");
                if (details.size() != 1 || !record.getId().equals(markerId(details.first(), "data-cms-native-detail"))
                        || !matchesDirectFields(site, model, record, details.first(), true,
                                anchors.contains(record.getId()) ? Set.of("singleLine1") : Set.of()))
                    return;
                check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                        list, list.getClassName(), null, "DETAIL", record.getId(), detailUrl, true);
            }
        }
        check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                parent, page.name(), page.sourcePath(), "LIST", null, page.route(), true);
        if (verifiedRoutes.size() != verified.size() || verified.stream().anyMatch(row -> !"READY".equals(row.state()))
                || checked.stream().anyMatch(row -> row != page && row.artifact() != null && verifiedRoutes.contains(row.artifact())))
            return;
        int index = checked.indexOf(page);
        checked.remove(index);
        checked.addAll(index, verified);
        hashes.putAll(verifiedHashes);
        claimed.addAll(verifiedRoutes);
        verifiedInteractions.stream().filter(item -> !interactions.contains(item)).forEach(interactions::add);
    }

    private static Element nativeProjectionArtifact(WorkbenchStaticLinkCheckService.Inspection scan, String route)
            throws IOException {
        var path = uri(route);
        if (path == null) return null;
        var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), path.getPath());
        return file == null || Files.size(file) > 2 * 1024 * 1024 ? null : Jsoup.parse(Files.readString(file));
    }

    private boolean matchesDirectRows(SiteProjectEntity site, OneshotEntity list, Map<String, ContentFieldDefinition> model,
            List<ContentEntity> expected, Element marker, boolean complete, boolean detailLinks) {
        var rendered = marker.select("[data-cms-native-row]").stream()
                .filter(row -> row.closest("[data-cms-native-list]") == marker).toList();
        if (rendered.size() != expected.size()) return false;
        var records = new HashMap<Integer, ContentEntity>();
        for (var record : expected)
            if (record.getId() == null || records.put(record.getId(), record) != null) return false;
        boolean grouped = rendered.stream().anyMatch(row -> row.hasAttr("data-cms-native-group"));
        var positions = new HashMap<String, Integer>();
        for (var row : rendered) {
            var record = records.remove(markerId(row, "data-cms-native-row"));
            if (record == null) return false;
            String group = grouped ? Objects.toString(record.getSingleLine1(), "") : "";
            if (grouped && (!model.containsKey("singleLine1") || !row.hasAttr("data-cms-native-group")
                    || !group.equals(row.attr("data-cms-native-group")))) return false;
            if (row.hasAttr("data-cms-native-anchor") && (!model.containsKey("singleLine1")
                    || !Objects.equals(record.getSingleLine1(), row.attr("data-cms-native-anchor")))) return false;
            int position = expected.indexOf(record);
            Integer previous = positions.put(group, position);
            if (previous != null && previous >= position) return false;
            if (!matchesDirectFields(site, model, record, row, complete, grouped ? Set.of("singleLine1") : Set.of()))
                return false;
            if (Boolean.TRUE.equals(list.getHasFrontChannel())) {
                String route = routes.getDetailUrl(record, list);
                if (uri(route) == null || !route.equals(row.attr("data-cms-native-detail-url"))) return false;
                if (detailLinks && row.select("a[href]").stream().noneMatch(link -> route.equals(link.attr("href")))) return false;
            }
        }
        return records.isEmpty();
    }

    boolean matchesDirectFields(SiteProjectEntity site, Map<String, ContentFieldDefinition> model,
            ContentEntity record, Element marker, boolean complete, Set<String> separatelyVerified) {
        var fields = new HashMap<String, Element>();
        for (var target : marker.select("[data-cms-native-field]")) {
            if (target.closest("[data-cms-native-row],[data-cms-native-detail]") != marker) continue;
            if (fields.put(target.attr("data-cms-native-field"), target) != null) return false;
        }
        if (!fields.containsKey("title") || Objects.toString(record.getTitle(), "").isBlank()
                || !model.keySet().containsAll(fields.keySet())) return false;
        for (var field : model.values()) {
            String name = field.getFieldName();
            String value = projectionValue(record, name);
            var target = fields.get(name);
            if (target == null) {
                if (complete && !value.isBlank() && !separatelyVerified.contains(name)) return false;
                continue;
            }
            switch (field.getFieldType()) {
                case "SingleLine", "MultiLine" -> {
                    if (!normalizedSectionText(value).equals(target.text())) return false;
                }
                case "Editor" -> {
                    target.ownerDocument().outputSettings().prettyPrint(false);
                    if (!richProjection(site, value).equals(richProjection(site, target.html()))) return false;
                }
                case "Image" -> {
                    if (target.is("img[src]")) {
                        if (!Objects.equals(nativeResources.resolve(value, site.getProjectCode()), target.attr("src")))
                            return false;
                    } else if (!target.is("video[poster]")
                            || !isProjectedMedia(model, fields, videoSource(target), "Video")
                            || !matchesNativeMedia(site, value, target.attr("poster"))) return false;
                }
                case "Video" -> {
                    Element player = target.parent();
                    if (videoSource(player) != target || !matchesNativeMedia(site, value, target.attr("src"))
                            || player.hasAttr("poster") && !isProjectedMedia(model, fields, player, "Image")) return false;
                }
                default -> { return false; }
            }
        }
        return true;
    }

    private static Element videoSource(Element player) {
        if (player == null || !player.is("video:not([src])")) return null;
        var sources = player.select("source");
        return sources.size() == 1 && sources.first().parent() == player && sources.first().hasAttr("src")
                ? sources.first() : null;
    }

    private static boolean isProjectedMedia(Map<String, ContentFieldDefinition> model, Map<String, Element> fields,
            Element element, String type) {
        if (element == null) return false;
        String name = element.attr("data-cms-native-field");
        var field = model.get(name);
        return fields.get(name) == element && field != null && type.equals(field.getFieldType());
    }

    private boolean matchesNativeMedia(SiteProjectEntity site, String value, String rendered) {
        if (value.isBlank() || rendered.isBlank()) return false;
        try {
            var context = nativeResources.context(site.getProjectCode());
            if (context == null || !rendered.equals(context.resolve(value))) return false;
            var source = URI.create(rendered);
            if (!WorkbenchStaticLinkCheckService.safeUrlPath(source)) return false;
            if (!source.isAbsolute()) return source.getRawAuthority() == null;
            if (context.ui() == null || !Set.of("http", "https").contains(source.getScheme())) return false;
            var origin = URI.create(context.ui());
            return origin.getHost() != null && WorkbenchStaticLinkCheckService.sameOrigin(origin, source);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String projectionValue(ContentEntity record, String field) {
        if (ContentExtensionFields.isName(field))
            return Objects.toString(record.getExtensionValues().get(field), "");
        try {
            var binding = EntityFields.find(ContentEntity.class, field);
            binding.setAccessible(true);
            return Objects.toString(binding.get(record), "");
        } catch (ReflectiveOperationException | IllegalArgumentException invalid) {
            return "";
        }
    }

    private String richProjection(SiteProjectEntity site, String input) {
        var document = Jsoup.parseBodyFragment(input);
        document.outputSettings().prettyPrint(false);
        var context = nativeResources.context(site.getProjectCode());
        if (context != null) {
            for (var image : document.select("img[src]")) image.attr("src", context.canonical(image.attr("src")));
            for (var link : document.select("a[href]")) link.attr("href", context.canonical(link.attr("href")));
        }
        document.body().forEachNode(node -> {
            if (node instanceof org.jsoup.nodes.TextNode text && text.parent() instanceof Element parent
                    && parent.closest("pre,code,textarea") == null)
                text.text(text.getWholeText().replaceAll("[\\t\\r\\n\\f ]+", " "));
        });
        return document.body().html().strip();
    }

    private static Integer markerId(Element marker, String attribute) {
        try {
            int id = Integer.parseInt(marker.attr(attribute));
            return id > 0 ? id : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void reconcileNativeSections(SiteProjectEntity site, SiteModelNavigationService.Navigation nav,
            Map<Integer, OneshotEntity> actual, WorkbenchStaticLinkCheckService.Inspection scan,
            List<SiteWorkbenchHandoffService.Application> active, Set<String> reachable, List<Page> checked,
            Map<String, String> hashes, Set<String> claimed, List<Interaction> interactions,
            Page page, OneshotEntity parent, Element html) throws IOException {
        if (nativeResources == null) return;
        var owners = nav.nodes().stream().filter(node -> Objects.equals(node.menuId(), parent.getId())
                && page.sourcePath().equals(node.sourcePath()) && !node.drift()).toList();
        if (owners.size() != 1) return;
        var nodes = nav.nodes().stream().filter(node -> owners.getFirst().key().equals(node.parentKey())
                && page.sourcePath().equals(node.sourcePath()) && node.sourceHref() != null
                && node.sourceHref().contains("#")).toList();
        var markers = html.select("[data-cms-section-menu]");
        if (nodes.isEmpty() || nodes.size() != markers.size()) return;
        var originalFile = WorkbenchStaticLinkCheckService.localFile(scan.original(), "/" + page.sourcePath());
        if (originalFile == null || Files.size(originalFile) > 2 * 1024 * 1024) return;
        var original = Jsoup.parse(Files.readString(originalFile));
        var changes = new HashMap<Integer, Page>();
        var usedIds = new HashSet<Integer>();
        for (var node : nodes) {
            var child = actual.get(node.menuId());
            String fragment = node.sourceHref().substring(node.sourceHref().lastIndexOf('#') + 1);
            if (child == null || !usedIds.add(child.getId()) || node.drift() || node.independentLanding()
                    || node.kind() != SiteModelNavigationService.Kind.SINGLE
                    || child.getParentId() != parent.getId() || !Objects.equals(child.getSiteRootId(), site.getRootMenuId())
                    || !Integer.valueOf(0).equals(child.getPageType()) || !Integer.valueOf(3).equals(child.getStatus())
                    || !Boolean.TRUE.equals(child.getHasManageChannel()) || !Boolean.FALSE.equals(child.getHasFrontChannel())
                    || !Objects.toString(child.getListPageUrl(), "").isBlank()
                    || active.stream().anyMatch(app -> Objects.equals(app.menuId(), child.getId()))
                    || !fragment.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                    || !PublicNavigationLinkCompiler.bodyAnchor(original, fragment)
                    || !PublicNavigationLinkCompiler.bodyAnchor(html, fragment)) return;
            var snapshot = nativeFieldSnapshot(site, child);
            if (snapshot == null) return;
            var candidates = markers.stream().filter(element -> child.getId().toString().equals(element.attr("data-cms-section-menu"))
                    && fragment.equals(element.id())).toList();
            String target = page.route() + "#" + fragment;
            if (candidates.size() != 1 || html.select("header a[href],footer a[href],.footer a[href]").stream()
                    .noneMatch(link -> target.equals(link.attr("href")))
                    || !nativeSectionFields(site, snapshot, candidates.getFirst())
                    || !nativeSectionItems(site, child, actual, candidates.getFirst())) return;
            var pending = checked.stream().filter(item -> Objects.equals(child.getId(), item.menuId())
                    && "LIST".equals(item.slot()) && "PENDING".equals(item.state()) && item.artifact() == null).toList();
            if (pending.size() != 1) return;
            changes.put(checked.indexOf(pending.getFirst()), new Page(child.getId(), child.getClassName(), page.sourcePath(),
                    "NAVIGATION", null, target, page.artifact(), "READY", "已核对独立维护栏目的正式锚点与实际发布字段"));
        }
        var verified = new ArrayList<Page>();
        var verifiedHashes = new TreeMap<String, String>();
        var verifiedRoutes = new HashSet<String>();
        var verifiedInteractions = new ArrayList<Interaction>();
        check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                parent, page.name(), page.sourcePath(), "LIST", null, page.route(), true);
        if (verified.size() != 1 || !"READY".equals(verified.getFirst().state())) return;
        changes.forEach(checked::set);
        checked.set(checked.indexOf(page), verified.getFirst());
        hashes.putAll(verifiedHashes);
        claimed.addAll(verifiedRoutes);
        verifiedInteractions.stream().filter(item -> !interactions.contains(item)).toList().forEach(interactions::add);
    }

    private boolean nativeSectionItems(SiteProjectEntity site, OneshotEntity section,
            Map<Integer, OneshotEntity> actual, Element html) {
        final int id;
        try {
            id = Integer.parseInt(html.attr("data-cms-section-list"));
        } catch (NumberFormatException invalid) {
            return false;
        }
        var list = actual.get(id);
        if (list == null || list.getParentId() != section.getId()
                || !Objects.equals(list.getSiteRootId(), site.getRootMenuId())
                || !Integer.valueOf(1).equals(list.getPageType()) || !Boolean.FALSE.equals(list.getHasFrontChannel())
                || !Boolean.TRUE.equals(list.getHasManageChannel()) || !Objects.toString(list.getListPageUrl(), "").isBlank()
                || !Objects.toString(list.getDetailPageUrl(), "").isBlank()) return false;
        var expected = CommonUtils.readPublishedSectionItems(nativeContent, site.getId(), id);
        var cards = html.select("[data-cms-section-card]");
        var menu = html.select("[data-cms-section-menu-row]");
        String menuKind = html.attr("data-cms-section-menu-kind");
        boolean recordMenu = "records".equals(menuKind);
        if ((!recordMenu && !"categories".equals(menuKind)) || cards.size() != expected.size()
                || menu.size() != (recordMenu ? expected.size() : 0)) return false;
        var ids = new HashSet<Integer>();
        for (int index = 0; index < expected.size(); index++) {
            var row = expected.get(index);
            var card = cards.get(index);
            if (!Objects.equals(row.getClassid(), id) || !Objects.equals(row.getSiteProjectId(), site.getId())
                    || !ids.add(row.getId()) || !row.getId().toString().equals(card.attr("data-cms-section-card"))) return false;
            String title = normalizedSectionText(row.getTitle());
            if (title.isEmpty() || !sectionItemText(card, "title", title)
                    || !sectionItemText(card, "expandedTitle", row.getSingleLine1() == null || row.getSingleLine1().isBlank()
                            ? title : normalizedSectionText(row.getSingleLine1()))
                    || !sectionItemText(card, "note1", normalizedSectionText(row.getNote1()))) return false;
            String photo = nativeResources.resolve(row.getPhoto1(), site.getProjectCode());
            var covers = card.select("[data-cms-section-item-field=photo1]");
            if (photo == null || photo.isBlank()) {
                if (!covers.isEmpty()) return false;
            } else if (covers.size() != 1 || !covers.first().is("img[src]") || !photo.equals(covers.first().attr("src"))) return false;
            if (recordMenu && (!row.getId().toString().equals(menu.get(index).attr("data-cms-section-menu-row"))
                    || !title.equals(menu.get(index).text()))) return false;
        }
        return true;
    }

    private static boolean sectionItemText(Element card, String field, String text) {
        var values = card.select("[data-cms-section-item-field=" + field + "]");
        return values.size() == 1 && text.equals(values.first().text());
    }

    private static String normalizedSectionText(String value) {
        return Objects.toString(value, "").replaceAll("[\\s\\u00a0]+", " ").strip();
    }

    private boolean nativeSectionFields(SiteProjectEntity site, OneshotEntity menu, Element section) {
        var fields = ContentModelCodec.read(menu.getMenuField()).stream()
                .filter(field -> ContentFieldDefinition.enabled(field.getIsOpen())).toList();
        var projected = section.select("[data-cms-section-field]").stream().collect(java.util.stream.Collectors.groupingBy(
                element -> element.attr("data-cms-section-field")));
        var keys = fields.stream().map(ContentFieldDefinition::getFieldName).collect(java.util.stream.Collectors.toSet());
        if (keys.isEmpty() || keys.size() != fields.size() || !keys.equals(projected.keySet())) return false;
        String background = section.attr("data-cms-section-background");
        for (var field : fields) {
            String key = field.getFieldName();
            Object raw;
            try {
                if (ContentExtensionFields.isName(key)) raw = menu.getExtensionValues().get(key);
                else {
                    var binding = EntityFields.find(OneshotEntity.class, key);
                    binding.setAccessible(true);
                    raw = binding.get(menu);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException invalid) {
                return false;
            }
            if (raw != null && !(raw instanceof String)) return false;
            String value = Objects.toString(raw, "");
            if ("Image".equals(field.getFieldType())) {
                String url = nativeResources.resolve(value, site.getProjectCode());
                if (url == null || url.isBlank() || projected.get(key).stream()
                        .anyMatch(node -> !node.is("img[src]") || !url.equals(node.attr("src")))) return false;
                if (key.equals(background) && !section.attr("style").contains("url(\"" + url + "\")")) return false;
            } else if (Set.of("SingleLine", "MultiLine").contains(field.getFieldType())) {
                if (key.equals(background) || projected.get(key).stream().anyMatch(node ->
                        !normalizedSectionText(value).equals(node.text()))) return false;
            } else return false;
        }
        return background.isEmpty() || keys.contains(background);
    }

    private void reconcileNativeList(SiteProjectEntity site, OneshotEntity menu, Page page, Element html,
            WorkbenchStaticLinkCheckService.Inspection scan, List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable, List<Page> checked, Map<String, String> hashes, Set<String> claimed,
            List<Interaction> interactions) throws IOException {
        var markers = html.select("[data-cms-native-list-menu][data-cms-native-list-size]");
        if (markers.size() != 1) return;
        var marker = markers.first();
        if (!menu.getId().toString().equals(marker.attr("data-cms-native-list-menu"))) return;
        int size;
        try {
            size = Integer.parseInt(marker.attr("data-cms-native-list-size"));
        } catch (NumberFormatException invalid) {
            return;
        }
        if (size < 1 || size > MAX_PAGES || !Boolean.TRUE.equals(menu.getHasManageChannel())) return;
        var records = content.selectPublicReleaseRecords(site.getId(), menu.getId());
        if (records.isEmpty()) return;
        var pager = nativeContent.getNewsListByClassIdXgidAndPage(new IdEntity().setClassId(menu.getId())
                .setParentId(menu.getParentId()).setSiteProjectId(site.getId()).setPageSize(size));
        if (!matchesNativeRows(menu, size, records, pager.getRows(), marker, "data-cms-native-list-record")) return;
        var pending = checked.stream().filter(item -> Objects.equals(menu.getId(), item.menuId())
                && "PENDING".equals(item.state())).toList();
        if (pending.size() != records.size() + 1
                || pending.stream().filter(item -> "DETAIL".equals(item.slot())).map(Page::recordId).distinct().count()
                        != records.size()) return;
        var verified = new ArrayList<Page>();
        var verifiedHashes = new TreeMap<String, String>();
        var verifiedRoutes = new HashSet<String>();
        var verifiedInteractions = new ArrayList<Interaction>();
        for (var item : pending)
            check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                    menu, item.name(), item.sourcePath(), item.slot(), item.recordId(), item.route(), true);
        if (verified.size() != pending.size() || verifiedRoutes.size() != verified.size()
                || verified.stream().anyMatch(item -> !"READY".equals(item.state()))
                || checked.stream().anyMatch(item -> !pending.contains(item) && item.artifact() != null
                        && verifiedRoutes.contains(item.artifact()))) return;
        for (int index = 0; index < pending.size(); index++)
            checked.set(checked.indexOf(pending.get(index)), verified.get(index));
        hashes.putAll(verifiedHashes);
        claimed.addAll(verifiedRoutes);
        verifiedInteractions.stream().filter(item -> !interactions.contains(item)).toList().forEach(interactions::add);
    }

    boolean matchesNativeRows(OneshotEntity menu, int size, List<ContentEntity> records,
            List<ContentEntity> expected, Element marker, String recordAttribute) {
        var ids = records.stream().map(ContentEntity::getId).collect(java.util.stream.Collectors.toSet());
        var rendered = marker.select("[" + recordAttribute + "]");
        if (expected.size() != Math.min(records.size(), size) || rendered.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            var record = expected.get(index);
            var row = rendered.get(index);
            var destination = routes.getDetailUrl(record, menu);
            if (!ids.contains(record.getId()) || record.getTitle() == null
                    || !record.getId().toString().equals(row.attr(recordAttribute))
                    || row.select("a[href]").stream().noneMatch(link -> destination.equals(link.attr("href"))
                            && link.text().contains(record.getTitle()))) return false;
        }
        return true;
    }

    private void reconcileReviewedCultureCatalogs(SiteProjectEntity site,
            SiteModelNavigationService.Navigation nav, Map<Integer, OneshotEntity> actual,
            WorkbenchStaticLinkCheckService.Inspection scan, List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable, List<Page> checked, Map<String, String> hashes, Set<String> claimed,
            List<Interaction> interactions, PageBudget pageBudget) throws IOException {
        if (nativeContent == null || scan.output() == null) return;
        for (var parent : List.copyOf(checked)) {
            if (!"READY".equals(parent.state()) || !"LIST".equals(parent.slot()) || parent.sourcePath() == null
                    || parent.route() == null || !actual.containsKey(parent.menuId())
                    || active.stream().noneMatch(app -> Objects.equals(app.menuId(), parent.menuId())
                            && "LIST".equals(app.slot()) && "TEMPLATE_VERIFIED".equals(app.state())
                            && parent.sourcePath().equals(app.sourcePath()))) continue;
            var source = WorkbenchStaticLinkCheckService.localFile(scan.output(), uri(parent.route()).getPath());
            if (source == null || Files.size(source) > 2 * 1024 * 1024) continue;
            var catalog = Jsoup.parse(Files.readString(source)).select(".main [data-culture-records]");
            if (catalog.size() != 1) continue;
            var section = catalog.first();
            int childId;
            try {
                childId = Integer.parseInt(section.attr("data-culture-records"));
            } catch (NumberFormatException invalid) {
                continue;
            }
            var child = actual.get(childId);
            if (child == null || child.getParentId() != parent.menuId()
                    || !Objects.equals(child.getSiteRootId(), site.getRootMenuId()) || !Integer.valueOf(1).equals(child.getPageType())
                    || !Boolean.TRUE.equals(child.getHasManageChannel()) || !Boolean.TRUE.equals(child.getHasFrontChannel())
                    || nav.nodes().stream().anyMatch(node -> Objects.equals(node.menuId(), childId))) continue;
            String first = routes.getListUrl(child);
            if (uri(first) == null || section.select("a[href]").stream().noneMatch(link -> first.equals(link.attr("href")))) continue;
            var records = content.selectPublicReleaseRecords(site.getId(), childId);
            if (records.isEmpty() || child.getDetailPageUrl() == null || child.getListPageUrl() == null
                    || !child.getListPageUrl().contains("{pageIndex}")) continue;
            var destinations = records.stream().map(record -> routes.getDetailUrl(record, child))
                    .collect(java.util.stream.Collectors.toSet());
            var detailPattern = WorkbenchStaticLinkCheckService.routePattern(child.getDetailPageUrl());
            var projected = section.select("a[href]").stream().map(link -> link.attr("href"))
                    .filter(href -> detailPattern.matcher(href).matches()).distinct().toList();
            if (projected.isEmpty() || !destinations.containsAll(projected)) continue;
            var pagination = new IdEntity().setClassId(childId).setParentId(child.getParentId()).setSiteProjectId(site.getId());
            int size = pagination.getPageSize();
            int pages = (records.size() + size - 1) / size;
            if (!pageBudget.allows(checked.size() + pages + records.size())) continue;
            var verified = new ArrayList<Page>();
            var verifiedHashes = new TreeMap<String, String>();
            var verifiedRoutes = new HashSet<String>();
            var verifiedInteractions = new ArrayList<Interaction>();
            var visible = new HashSet<String>();
            for (int number = 1; number <= pages; number++) {
                String route = routes.getListUrl(child, number);
                if (uri(route) == null) break;
                var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), route);
                if (file == null || Files.size(file) > 2 * 1024 * 1024) break;
                var html = Jsoup.parse(Files.readString(file));
                var expected = nativeContent.getNewsListByClassIdXgidAndPage(pagination.setPageIndex(number)).getRows();
                var actualLinks = html.select(".main a[href]").stream()
                        .filter(link -> detailPattern.matcher(link.attr("href")).matches()).toList();
                var expectedRoutes = expected.stream().map(row -> routes.getDetailUrl(row, child)).toList();
                if (expected.size() != Math.min(size, records.size() - (number - 1) * size)
                        || !actualLinks.stream().map(link -> link.attr("href")).distinct().toList().equals(expectedRoutes)
                        || !destinations.containsAll(expectedRoutes)) break;
                for (int index = 0; index < expected.size(); index++) {
                    String href = expectedRoutes.get(index);
                    String title = expected.get(index).getTitle();
                    if (title != null && actualLinks.stream().anyMatch(link -> href.equals(link.attr("href"))
                            && (link.text().contains(title) || link.select("img[alt]").stream()
                                    .anyMatch(image -> title.equals(image.attr("alt")))))) visible.add(href);
                }
                check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                        child, child.getClassName(), null, "LIST", null, route, true);
            }
            if (verified.size() != pages || !visible.equals(destinations)) continue;
            for (var record : records)
                check(site, scan, active, reachable, verified, verifiedHashes, verifiedRoutes, verifiedInteractions,
                        child, child.getClassName(), null, "DETAIL", record.getId(), routes.getDetailUrl(record, child), true);
            if (verified.size() != pages + records.size() || verifiedRoutes.size() != verified.size()
                    || verified.stream().anyMatch(item -> !"READY".equals(item.state()))
                    || checked.stream().anyMatch(item -> item.artifact() != null && verifiedRoutes.contains(item.artifact()))) continue;
            checked.addAll(verified);
            hashes.putAll(verifiedHashes);
            claimed.addAll(verifiedRoutes);
            verifiedInteractions.stream().filter(item -> !interactions.contains(item)).toList().forEach(interactions::add);
        }
    }

    private void reconcileReviewedSharedParents(SiteProjectEntity site,
            SiteModelNavigationService.Navigation nav, WorkbenchStaticLinkCheckService.Inspection scan,
            List<SiteWorkbenchHandoffService.Application> active, List<Page> checked, Map<String, String> hashes,
            RetainedNavigationText rules) throws IOException {
        if (rules == null || scan.output() == null) return;
        String version = site.getUiPackageVersion();
        for (var parent : nav.nodes()) {
            if (parent.menuId() == null || parent.sourcePath() == null || parent.drift()
                    || parent.independentLanding() || parent.kind() != SiteModelNavigationService.Kind.SINGLE
                    || !rules.internalDestinationMismatch(version, parent.menuId(), parent.name(), null)
                    || active.stream().anyMatch(app -> Objects.equals(app.menuId(), parent.menuId()))) continue;
            var pending = checked.stream().filter(page -> Objects.equals(parent.menuId(), page.menuId())
                    && "PENDING".equals(page.state()) && "LIST".equals(page.slot()) && page.artifact() == null).toList();
            if (pending.size() != 1) continue;
            var approved = nav.nodes().stream().filter(child -> parent.key().equals(child.parentKey())
                    && parent.sourcePath().equals(child.sourcePath()) && child.menuId() != null && !child.drift()).toList();
            if (approved.size() != 1) continue;
            var child = approved.getFirst();
            var destination = checked.stream().filter(page -> Objects.equals(child.menuId(), page.menuId())
                    && parent.sourcePath().equals(page.sourcePath()) && "READY".equals(page.state())
                    && "LIST".equals(page.slot()) && page.artifact() != null && uri(page.route()) != null).toList();
            if (destination.size() != 1
                    || rules.internalDestinationMismatch(version, parent.menuId(), parent.name(), destination.getFirst().route())) continue;
            var reviewRoutes = rules.reviewRoutes(version, parent.menuId(), parent.name());
            if (!reviewRoutes.contains("/")) continue;
            var root = WorkbenchStaticLinkCheckService.localFile(scan.output(), "/");
            if (root == null || Files.size(root) > 2 * 1024 * 1024) continue;
            String html = Files.readString(root);
            if (workspace.staticArtifactProof(site.getId(), site.getProjectCode(), root, html) == null
                    || rules.mismatch(version, "/", html) != null) continue;
            var target = destination.getFirst();
            var page = pending.getFirst();
            hashes.put(WorkbenchStaticLinkCheckService.relative(scan.output(), root), digest(html));
            checked.set(checked.indexOf(page), new Page(parent.menuId(), parent.name(), parent.sourcePath(),
                    "NAVIGATION", null, target.route(), target.artifact(), "READY", "已审首页入口共用直属栏目的正式产物"));
        }
    }

    static Page verifiedEntryDestination(PublicNavigationLinkCompiler.Target target, List<Page> checked,
            WorkbenchStaticLinkCheckService.Inspection scan) throws IOException {
        var destination = URI.create(target.url());
        var page = checked.stream().filter(item -> "LIST".equals(item.slot()) && "READY".equals(item.state())
                && item.artifact() != null && Objects.equals(item.menuId(), target.menuId())
                && destination.getPath().equals(item.route())).findFirst().orElse(null);
        if (page == null || destination.getFragment() == null) return page;
        var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), destination.getPath());
        if (file == null || Files.size(file) > 2 * 1024 * 1024) return null;
        return PublicNavigationLinkCompiler.bodyAnchor(Jsoup.parse(Files.readString(file)), destination.getFragment())
                ? page : null;
    }

    private void inspectAdditional(
            SiteProjectEntity site,
            WorkbenchStaticLinkCheckService.Inspection scan,
            List<SiteWorkbenchHandoffService.Application> active,
            Set<String> reachable,
            List<Page> checked,
            Map<String, String> hashes,
            Set<String> claimed,
            List<Interaction> interactions, PageBudget pageBudget)
            throws IOException {
        var home=active.stream().filter(app -> Objects.equals(app.menuId(),site.getRootMenuId()) && "LIST".equals(app.slot())).findFirst().orElse(null);
        for (var path : scan.graph().keySet().stream().sorted().toList()) {
            if (claimed.contains(path)) continue;
            var file = WorkbenchStaticLinkCheckService.localFile(scan.output(), "/" + path);
            if (file == null || Files.size(file) > 2 * 1024 * 1024) continue;
            var html = Files.readString(file);
            var proof =
                    workspace.staticArtifactProof(site.getId(), site.getProjectCode(), file, html);
            var alias=active.stream().filter(app -> ("/"+path).equals(app.route())
                    && handoff.verifiedHomeSourceAlias(site,home,app)).findFirst().orElse(null);
            if (alias!=null && StaticRenderProof.of(site.getId(),alias.menuId(),false,alias.templatePath()).equals(proof)) {
                var template=templates.resolveTemplateDir(site.getProjectCode()).resolve(alias.templatePath()).normalize();
                if (Files.isRegularFile(template) && Files.getLastModifiedTime(template).compareTo(Files.getLastModifiedTime(file))<=0) {
                    if (!pageBudget.allows(checked.size() + 1)) break;
                    String mismatch=navigationMismatch(site,scan,html,uri(alias.route()),publicNavigation.approvedLinks(site.getId(),
                            site.getProjectCode(),site.getUiPackageVersion(),alias.sourcePath()));
                    if (mismatch == null) mismatch = retainedTextMismatch(site, scan, alias.route(), html);
                    hashes.put(path,digest(html));
                    checked.add(
                            new Page(
                                    alias.menuId(),
                                    alias.menuName(),
                                    alias.sourcePath(),
                                    "LIST",
                                    null,
                                    alias.route(),
                                    path,
                                    mismatch == null ? "READY" : "PENDING",
                                    mismatch == null ? "首页已关联的正式来源地址及静态产物已核对" : mismatch));
                    detectInteractions(html,alias.route(),interactions);
                    continue;
                }
            }
            boolean pagination =
                    active.stream()
                            .anyMatch(
                                    app ->
                                            app.route() != null
                                                    && "LIST".equals(app.slot())
                                                    && app.route().contains("{pageIndex}")
                                                    && WorkbenchStaticLinkCheckService.routePattern(
                                                                    app.route())
                                                            .matcher("/" + path)
                                                            .matches()
                                                    && Objects.equals(
                                                            proof,
                                                            StaticRenderProof.of(
                                                                    site.getId(),
                                                                    app.menuId(),
                                                                    false,
                                                                    app.templatePath())));
            if (pagination && reachable.contains(path)) {
                hashes.put(path, digest(html));
                detectInteractions(html, "/" + path, interactions);
                continue;
            }
            if (!pageBudget.allows(checked.size() + 1)) break;
            checked.add(
                    new Page(
                            null,
                            "待核对产物",
                            null,
                            "LIST",
                            null,
                            "/" + path,
                            path,
                            "PENDING",
                            "此发布文件不在本次应发清单或首页可达的分页中，请核对并处理旧文件"));
        }
    }

    private static String navigationMismatch(SiteProjectEntity site, WorkbenchStaticLinkCheckService.Inspection scan,
            String html, URI route, List<PublicNavigationLinkCompiler.Change> approved) {
        if (approved.isEmpty()) return null;
        var page = Jsoup.parse(html);
        var overrides = scan.original() == null ? null : new RetainedNavigationText(
                scan.original().getParent().getParent(), site.getProjectCode(), scan.output());
        var base = page.selectFirst("base[href]");
        URI documentBase;
        try {
            documentBase = base == null ? route : route.resolve(base.attr("href"));
        } catch (IllegalArgumentException invalidBase) {
            return "页面基准地址无效，请核对公共导航";
        }
        for (var change : approved) {
            var matches = page.select(change.selector());
            if (matches.size() != 1 || !matches.first().is("a[href],area[href]"))
                return "请核对生成页中「" + change.label() + "」的公共导航位置";
            try {
                var href = matches.first().attr("href");
                if (!href.isBlank() && documentBase.resolve(href).normalize().equals(route.resolve(change.url()).normalize())) continue;
                if (overrides != null && overrides.matchesInternalOverride(
                        site.getUiPackageVersion(), route.getPath(), page, matches.first(), change.url())) continue;
            } catch (IllegalArgumentException invalidUrl) {
                return "生成页中「" + change.label() + "」地址无效，请重新绑定并发布";
            }
            return "生成页中「" + change.label() + "」未指向已审核地址 " + change.url() + "，请重新应用公共链接并发布";
        }
        return null;
    }

    private static String retainedTextMismatch(
            SiteProjectEntity site, WorkbenchStaticLinkCheckService.Inspection scan, String route, String html) {
        if (scan.original() == null) return null;
        var rules = new RetainedNavigationText(scan.original().getParent().getParent(), site.getProjectCode(),
                scan.output());
        return rules.mismatch(site.getUiPackageVersion(), route, html);
    }

    private Page backOfficeOnlyPage(
            SiteProjectEntity site,
            WorkbenchStaticLinkCheckService.Inspection scan,
            SiteModelNavigationService.Node node,
            OneshotEntity menu,
            SiteModelNavigationService.Navigation nav) {
        if (scan.original() == null || menu == null || node.sourcePath() != null
                || node.independentLanding() || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !Boolean.FALSE.equals(menu.getHasFrontChannel())
                || !Objects.toString(menu.getListPageUrl(), "").isBlank()
                || !Objects.toString(menu.getDetailPageUrl(), "").isBlank()
                || !Objects.toString(menu.getListPageTemplate(), "").isBlank()
                || !Objects.toString(menu.getDetailPageTemplate(), "").isBlank()) return null;
        String version = site.getUiPackageVersion();
        if (!Objects.equals(version, scan.original().getFileName().toString())) return null;
        var file = scan.original().getParent().getParent().resolve(".back-office-only-navigation.json");
        if (!Files.isRegularFile(file)) return null;
        var decision = json.readTree(file.toFile()).path(site.getProjectCode()).path(version).path(menu.getId().toString());
        String label = decision.path("label").asString();
        String reason = decision.path("reason").asString();
        int parent = decision.path("parentMenuId").asInt();
        if (parent <= 0 || !Objects.equals(menu.getParentId(), parent)
                || label.isBlank() || !label.equals(node.name()) || !label.equals(menu.getClassName()) || reason.isBlank()
                || nav.nodes().stream().noneMatch(candidate -> Objects.equals(candidate.key(), node.parentKey())
                        && Objects.equals(candidate.menuId(), parent) && !candidate.drift())) return null;
        return new Page(menu.getId(), node.name(), node.sourcePath(), "NAVIGATION", null, null, null,
                "EXCEPTION", "已审核仅保留后台入口。已核对栏目身份、管理渠道与公开路由配置。" + reason);
    }

    private Page retainedTextPage(
            SiteProjectEntity site,
            WorkbenchStaticLinkCheckService.Inspection scan,
            Map<String, String> hashes,
            SiteModelNavigationService.Node node,
            OneshotEntity menu,
            RetainedNavigationText rules) throws IOException {
        if (rules == null || menu == null || Boolean.TRUE.equals(menu.getHasFrontChannel())
                || rules.internalDestinationMismatch(site.getUiPackageVersion(), menu.getId(), node.name(), null))
            return null;
        var routes = rules.reviewRoutes(site.getUiPackageVersion(), menu.getId(), node.name());
        if (routes.isEmpty()) return null;
        String external = rules.reviewedExternalDestination(site.getUiPackageVersion(), menu.getId(), node.name());
        for (String route : routes) {
            Path file = scan.output() == null ? null : WorkbenchStaticLinkCheckService.localFile(scan.output(), route);
            if (file != null && Files.size(file) <= 2 * 1024 * 1024) {
                String html = Files.readString(file);
                if (workspace.staticArtifactProof(site.getId(), site.getProjectCode(), file, html) != null
                        && rules.mismatch(site.getUiPackageVersion(), route, html) == null) {
                    hashes.put(WorkbenchStaticLinkCheckService.relative(scan.output(), file), digest(html));
                    continue;
                }
            }
            return new Page(menu.getId(), node.name(), node.sourcePath(), "NAVIGATION", null, null, null,
                    "PENDING", external == null ? "已审核保留文字，待重新生成并核对 " + route
                            : "已审核外部导航，待重新生成并核对 " + route);
        }
        if (external != null) return new Page(menu.getId(), node.name(), node.sourcePath(), "NAVIGATION", null, null, null,
                "READY", "已核对 " + routes.size() + " 个正式地址的官网外链 " + external);
        return new Page(menu.getId(), node.name(), node.sourcePath(), "NAVIGATION", null, null, null,
                "EXCEPTION", "已审核保留为不可点击文字，已核对 " + routes.size() + " 个正式地址的发布产物");
    }

    private static URI uri(String route) {
        try {
            if (route == null) return null;
            var uri = URI.create(route.startsWith("/") ? route : "/" + route);
            return WorkbenchStaticLinkCheckService.safeUrlPath(uri)
                            && uri.getRawAuthority() == null
                            && uri.getRawQuery() == null
                            && uri.getRawFragment() == null
                    ? uri
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Set<String> reachable(Map<String, Set<String>> graph) {
        var pending = new ArrayDeque<String>();
        pending.add("index.html");
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (seen.add(next)) pending.addAll(graph.getOrDefault(next, Set.of()));
        }
        return seen;
    }

    private static void detectInteractions(String html, String route, List<Interaction> out) {
        var doc = Jsoup.parse(html);
        for (var form : doc.select("form[data-cms-form-category]")) {
            try {
                int id = Integer.parseInt(form.attr("data-cms-form-category"));
                if (id > 0) out.add(new Interaction(route, "FORM", id, true));
            } catch (NumberFormatException invalid) {
                out.add(new Interaction(route, "CUSTOM", null, true));
            }
        }
        if (doc.selectFirst("form[action*=search]") != null)
            out.add(new Interaction(route, "SEARCH", null, true));
        if (doc.selectFirst("[name=__cms_filter], [data-cms-list-filter]") != null)
            out.add(new Interaction(route, "FILTER", null, true));
        if (doc.selectFirst("[data-cms-pagination] a[href], div.page a[href]") != null)
            out.add(new Interaction(route, "PAGINATION", null, false));
        if (doc.selectFirst("iframe[src], [data-async-src]") != null)
            out.add(new Interaction(route, "ASYNC", null, false));
        if (doc.selectFirst("[data-cms-featured-carousel=region]") != null)
            out.add(new Interaction(route, "CUSTOM", null, false));
        if (doc.selectFirst("form[method=post]:not([data-cms-form-category])") != null)
            out.add(new Interaction(route, "CUSTOM", null, true));
    }

    private record StaticHostCheck(Long siteId, String siteCode, String packageVersion, String origin,
            String manifestDigest, Map<String,String> sourcePages, List<Integer> verifiedForms,
            int filesChecked, int issueCount, String firstIssue, String directory, String verifiedAt) {}

    private boolean matchesLocalDeployment(StaticHostCheck proof) {
        if (proof.directory() == null || proof.verifiedAt() == null) return false;
        try {
            Instant.parse(proof.verifiedAt());
            var directory = Path.of(proof.directory()).normalize();
            if (!directory.isAbsolute() || !directory.equals(directory.toRealPath())) return false;
            var manifest = localDeploymentFile(directory, "_cms-static-export.json");
            byte[] manifestBytes;
            try (var input = Files.newInputStream(manifest)) {
                manifestBytes = input.readNBytes(MAX_DEPLOYMENT_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_DEPLOYMENT_MANIFEST_BYTES) return false;
            if (!proof.manifestDigest().equals(HexFormat.of().formatHex(sha256().digest(manifestBytes)))) return false;
            var entries = json.readTree(manifestBytes).path("files");
            if (!entries.isObject() || entries.size() != proof.filesChecked() || entries.size() > MAX_DEPLOYMENT_FILES) return false;
            long bytes = 0;
            byte[] buffer = new byte[64 * 1024];
            for (var entry : entries.properties()) {
                var file = localDeploymentFile(directory, entry.getKey());
                var digest = sha256();
                try (var input = Files.newInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > MAX_DEPLOYMENT_BYTES) return false;
                        digest.update(buffer, 0, count);
                    }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(entry.getValue().asText())) return false;
            }
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static Path localDeploymentFile(Path directory, String relative) throws IOException {
        var file = directory.resolve(relative).normalize();
        if (!file.startsWith(directory) || !file.equals(file.toRealPath()) || !Files.isRegularFile(file))
            throw new IOException("独立发布文件不可读取");
        return file;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record StaticSearchCheck(String hostDigest, String manifestDigest, String resultDigest,
            Map<String, String> sources) {}

    private Set<String> verifiedIndependentSearch(SiteProjectEntity site, StaticHostCheck host, String hostDigest) {
        if (host.issueCount() != 0) return Set.of();
        var document = workspace.staticSearchCheck(site.getId(), site.getProjectCode());
        if (!document.present()) return Set.of();
        try {
            var proof = json.readValue(document.content(), StaticSearchCheck.class);
            if (proof == null || !hostDigest.equals(proof.hostDigest())
                    || !host.manifestDigest().equals(proof.manifestDigest())
                    || proof.resultDigest() == null || !proof.resultDigest().matches("[a-f0-9]{64}")
                    || proof.sources() == null || !proof.sources().containsKey("index.html")
                    || !host.sourcePages().entrySet().containsAll(proof.sources().entrySet())) return Set.of();
            return proof.sources().keySet();
        } catch (RuntimeException invalid) {
            return Set.of();
        }
    }

    private static String staticSource(String route) {
        var path = uri(route);
        if (path == null) return "";
        return "/".equals(path.getPath()) ? "index.html" : path.getPath().substring(1);
    }

    private DeploymentEvidence checkIndependentHost(SiteProjectEntity site, Scope scope, Map<String,String> hashes,
            List<Page> checked, List<Interaction> interactions) {
        String state = "PENDING";
        String reason = "请导出当前版本，在独立托管地址运行 verify-static.py --record";
        String origin = null;
        DeploymentEvidence deploymentEvidence = null;
        var document = workspace.staticHostCheck(site.getId(), site.getProjectCode());
        if (document.present()) {
            try {
                var proof = json.readValue(document.content(), StaticHostCheck.class);
                if (proof != null && Objects.equals(site.getId(), proof.siteId())
                        && site.getProjectCode().equals(proof.siteCode())
                        && scope.packageVersion().equals(proof.packageVersion())
                        && proof.manifestDigest() != null && proof.manifestDigest().matches("[a-f0-9]{64}")
                        && proof.filesChecked() > 0 && proof.issueCount() >= 0
                        && proof.sourcePages() != null && proof.sourcePages().containsKey("index.html")
                        && proof.sourcePages().entrySet().containsAll(hashes.entrySet())
                        && proof.verifiedForms() != null && proof.origin() != null) {
                    origin = proof.origin();
                    if (proof.issueCount() == 0)
                        deploymentEvidence = new DeploymentEvidence(proof.manifestDigest(), proof.verifiedAt());
                    if (proof.issueCount() == 0 && matchesLocalDeployment(proof)) {
                        var offlineSearch = verifiedIndependentSearch(site, proof, document.digest());
                        hashes.put("@static-host", digest(json.writeValueAsString(List.of(
                                proof.origin(), proof.manifestDigest(), proof.verifiedForms(), offlineSearch.stream().sorted().toList()))));
                        interactions.replaceAll(item -> "FORM".equals(item.kind())
                                && proof.verifiedForms().contains(item.categoryId())
                                ? new Interaction(item.route(), item.kind(), item.categoryId(), false) : item);
                        interactions.replaceAll(item -> "SEARCH".equals(item.kind())
                                && offlineSearch.contains(staticSource(item.route()))
                                ? new Interaction(item.route(), item.kind(), item.categoryId(), false) : item);
                        state = "READY";
                        reason = "当前本地部署文件对应已通过独立 HTTP 核验的清单。交互须另行签收。";
                    } else if (proof.issueCount() == 0) {
                        reason = "历史独立 HTTP 核验通过，当前部署待核，请重新运行 verify-static.py --record。";
                    } else {
                        reason = "独立托管有 " + proof.issueCount() + " 项待处理：" + proof.firstIssue();
                    }
                } else reason = "独立托管回执与当前页面或版本不一致，请重新导出并核验";
            } catch (RuntimeException invalid) {
                reason = "独立托管回执不可读，请重新运行部署核验";
            }
        }
        checked.add(new Page(null, "独立托管", null, "DEPLOYMENT", null, origin, null, state, reason));
        return deploymentEvidence;
    }

    private static boolean matches(Interaction target, Evidence row) {
        return Objects.equals(target.route(), row.route())
                && Objects.equals(target.kind(), row.kind())
                && Objects.equals(target.categoryId(), row.categoryId());
    }

    private void validateEvidence(
            SiteProjectEntity site,
            List<Interaction> required,
            Path output,
            List<Evidence> evidence) {
        if (evidence == null || evidence.size() > required.size() + MAX_ADDITIONAL_EVIDENCE)
            throw new IllegalArgumentException("除必需交互外，最多登记 100 条补充回执");
        var keys = new HashSet<List<String>>();
        for (var row : evidence) {
            if (row == null
                    || uri(row.route()) == null
                    || !KINDS.contains(Objects.toString(row.kind(), ""))
                    || !Set.of("PENDING", "FAILED", "PASSED", "EXCEPTION")
                            .contains(Objects.toString(row.outcome(), ""))
                    || !text(row.owner(), 120)
                    || !text(row.steps(), 1000)
                    || !text(row.result(), 1000)
                    || !keys.add(
                            List.of(
                                    row.route(),
                                    row.kind(),
                                    Objects.toString(row.categoryId(), "")))
                    || ("EXCEPTION".equals(row.outcome())
                            && required.stream().anyMatch(item -> matches(item, row)))
                    || ("PASSED".equals(row.outcome()) && !validForm(site, row, output)))
                throw new IllegalArgumentException("交互回执无效。请填写责任人、复现步骤和结果；表单通过须填写本站对应提交记录 ID。");
        }
    }

    private boolean validForm(SiteProjectEntity site, Evidence row, Path output) {
        if (!"FORM".equals(row.kind())) return true;
        if (row.categoryId() == null || row.recordId() == null) return false;
        var category = categories.selectById(row.categoryId());
        if (category == null
                || !Objects.equals(site.getId(), category.getSiteProjectId())
                || Boolean.TRUE.equals(category.getIsDeleted())) return false;
        var record = formRows.selectById(row.recordId());
        if (record == null
                || !Objects.equals(category.getId(), record.getClassid())
                || !Integer.valueOf(1).equals(record.getStatus())
                || record.getQueryToken() == null
                || record.getCreateTime() == null
                || output == null) return false;
        try {
            var path = uri(row.route());
            if (path == null) return false;
            var file = WorkbenchStaticLinkCheckService.localFile(output, path.getPath());
            if (file == null) return false;
            if (Files.size(file) > 2 * 1024 * 1024
                    || Jsoup.parse(Files.readString(file))
                                    .selectFirst(
                                            "form[data-cms-form-category=" + category.getId() + "]")
                            == null) return false;
            var published =
                    LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(file).toInstant(),
                                    ZoneId.systemDefault())
                            .withNano(0);
            return !record.getCreateTime().isBefore(published);
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static String digest(String text) {
        return PublicBindingApplication.digest(text);
    }
}

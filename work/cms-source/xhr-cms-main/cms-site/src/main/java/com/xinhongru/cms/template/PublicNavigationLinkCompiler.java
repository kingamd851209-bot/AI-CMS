package com.xinhongru.cms.template;

import com.xinhongru.cms.formservice.FormApiException;
import com.xinhongru.cms.formservice.FormSiteApiService;
import com.xinhongru.cms.siteservice.RouteHandlerEntry;
import com.xinhongru.cms.siteservice.SiteRouteRegistry;
import com.xinhongru.cms.template.SiteModelNavigationService.Node;
import com.xinhongru.cms.template.SiteModelNavigationService.Status;
import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.site.storage.SiteStoragePathResolver;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 将已确认的公共导航链接加入当前页面的受控模板编译。 */
@Service
public class PublicNavigationLinkCompiler {
    private static final String PUBLIC_REGION = "header, footer, .header, .footer";
    private static final String LANDING_SHARED_REGION =
            "header,footer,nav,.header,.footer,.nav,#header,#footer,.site-header,.site-footer,"
                + ".site_header,.site_footer,.site-nav,.site_nav,[role=banner],[role=contentinfo],[role=navigation]";
    private static final int MAX_ANCHORS = 250;
    private static final String BODY_CONTENT_REGION = "main,[role=main],.main";
    private static final String BODY_REFERENCE_EXCLUSIONS =
            LANDING_SHARED_REGION
                    + ",aside,form,button,[role=form],[role=button],[hidden],[aria-hidden=true],[inert]";
    private static final String BODY_FRAGMENT_KEY_PREFIX = "body-fragment-";
    private static final String STATIC_TAB_HREF = "javascript:void(0);";
    private static final String MOBILE_FRAGMENT_LINK = "data-cms-reviewed-category";
    private static final java.util.regex.Pattern FULL_PAGE_TAG = java.util.regex.Pattern.compile(
            "<(?:!doctype|html|head|body)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    public record Selection(String selector, String rawHref, String nodeKey) {}

    public record Request(
            String packageVersion,
            String sourcePath,
            String sourceDigest,
            long navigationRevision,
            List<Selection> selections,
            String previousDigest,
            String digest,
            Boolean confirmed) {}

    public record Anchor(
            String selector,
            String rawHref,
            String label,
            String region,
            String suggestedNodeKey,
            List<String> eligibleTargetKeys,
            LinkRepair repair,
            String problem) {
        Anchor(String selector, String rawHref, String label, String region, String suggestedNodeKey) {
            this(selector, rawHref, label, region, suggestedNodeKey, List.of(), null, null);
        }

        Anchor(String selector, String rawHref, String label, String region, String suggestedNodeKey,
                List<String> eligibleTargetKeys, LinkRepair repair) {
            this(selector, rawHref, label, region, suggestedNodeKey, eligibleTargetKeys, repair, null);
        }

        Anchor reviewed(String target, String reason) {
            return new Anchor(selector, rawHref, label, region, target,
                    target == null ? List.of() : List.of(target), null, reason);
        }
    }

    public record LinkRepair(String sourcePath, String reason) {}

    public enum TargetKind { CONTENT_LIST, NATIVE_FORM, PARENT_ENTRY, FRAGMENT }

    public record Target(String nodeKey, Integer menuId, String label, String url, TargetKind kind) {}

    public record Change(
            String selector,
            String rawHref,
            String nodeKey,
            Integer menuId,
            String label,
            String url) {}

    public record Preview(String digest, List<Change> changes) {}

    public record Workspace(
            String sourcePath,
            String sourceDigest,
            long navigationRevision,
            List<Anchor> anchors,
            List<Target> targets,
            Preview confirmed,
            Status navigationStatus) {}

    public enum LandingTargetState {
        READY,
        FRONT_DISABLED,
        ROUTE_MISSING,
        TEMPLATE_MISSING,
        INDEPENDENT_SOURCE_REQUIRED,
        QUERY_REQUIRED,
        ROUTE_CONFLICT,
        ANCHOR_MISSING,
        DISPLAY_ONLY
    }

    public record LandingCard(
            String selector,
            String nodeKey,
            Integer menuId,
            String label,
            String rawHref,
            String imageSource,
            String summary,
            LandingTargetState state,
            String publicUrl) {
        LandingCard withDestination(LandingTargetState next, String url) {
            return new LandingCard(
                    selector, nodeKey, menuId, label, rawHref, imageSource, summary, next, url);
        }
    }

    public record LandingCandidate(String selector, List<String> labels) {}

    public List<LandingCandidate> landingCandidates(
            SiteModelNavigationService.Scope scope,
            String planId,
            String sourcePath,
            String nodeKey) {
        pageKey(sourcePath);
        var view = navigation.appliedSnapshot(scope, planId);
        var parents =
                view.nodes().stream()
                        .filter(
                                node ->
                                        Objects.equals(node.key(), nodeKey)
                                                && sourcePath.equals(node.sourcePath())
                                                && node.kind()
                                                        == SiteModelNavigationService.Kind.LIST)
                        .toList();
        if (parents.size() != 1) throw new IllegalArgumentException("请选择已应用导航中的明确概览父栏目");
        requireLandingIdentity(scope, parents.getFirst(), view.nodes());
        var children =
                view.nodes().stream()
                        .filter(node -> nodeKey.equals(node.parentKey()))
                        .sorted(Comparator.comparingInt(Node::order))
                        .toList();
        if (children.size() < 2 || children.size() > 30) return List.of();
        for (var child : children) requireLandingIdentity(scope, child, view.nodes());
        var source = packages.readStaticPage(scope.code(), scope.packageVersion(), sourcePath);
        var document = Jsoup.parse(source.html());
        var results = new ArrayList<LandingCandidate>();
        var lists = document.select("ul,ol");
        if (lists.size() > 100) throw new IllegalArgumentException("原页列表过多，请限定已审栏目来源");
        for (var list : lists) {
            if (list.childrenSize() != children.size()) continue;
            boolean labelled = true;
            for (int i = 0; i < children.size(); i++) {
                var headings = list.child(i).select("h2,h3,h4");
                String name = children.get(i).name();
                if (headings.isEmpty()
                        || headings.stream().anyMatch(heading -> !heading.text().equals(name))) {
                    labelled = false;
                    break;
                }
            }
            if (!labelled) continue;
            try {
                var preview =
                        previewLanding(scope, planId, sourcePath, nodeKey, list.cssSelector(), false);
                results.add(
                        new LandingCandidate(
                                list.cssSelector(),
                                preview.cards().stream().map(LandingCard::label).toList()));
            } catch (IllegalArgumentException invalidRegion) {
                // A label match alone does not establish an independent body navigation region.
            }
        }
        return List.copyOf(results);
    }

    public record LandingPreview(
            String sourcePath,
            String sourceDigest,
            long navigationRevision,
            long planRevision,
            String nodeKey,
            Integer menuId,
            String label,
            String containerSelector,
            List<LandingCard> cards,
            String digest) {
        boolean sameSources(LandingPreview other) {
            return Objects.equals(sourcePath, other.sourcePath)
                    && Objects.equals(sourceDigest, other.sourceDigest)
                    && navigationRevision == other.navigationRevision
                    && planRevision == other.planRevision
                    && Objects.equals(nodeKey, other.nodeKey)
                    && Objects.equals(menuId, other.menuId)
                    && Objects.equals(containerSelector, other.containerSelector)
                    && cards.stream()
                            .map(card -> card.withDestination(null, null))
                            .toList()
                            .equals(
                                    other.cards.stream()
                                            .map(card -> card.withDestination(null, null))
                                            .toList());
        }

        LandingPreview withDisplayOnly(Set<String> keys) {
            return new LandingPreview(
                    sourcePath,
                    sourceDigest,
                    navigationRevision,
                    planRevision,
                    nodeKey,
                    menuId,
                    label,
                    containerSelector,
                    cards.stream()
                            .map(
                                    card ->
                                            keys.contains(card.nodeKey())
                                                    ? card.withDestination(
                                                            LandingTargetState.DISPLAY_ONLY, null)
                                                    : card)
                            .toList(),
                    digest);
        }
    }

    private record Stored(
            String planId,
            String sourcePath,
            String sourceDigest,
            long navigationRevision,
            List<Selection> selections,
            Preview result,
            List<String> reviewedSelectors) {
        private Stored {
            reviewedSelectors =
                    reviewedSelectors == null
                            ? result.changes().stream().map(Change::selector).toList()
                            : List.copyOf(reviewedSelectors);
        }
    }

    private record Evidence(
            StaticPageSource.Page source,
            long revision,
            Status status,
            long planRevision,
            String originPlanId,
            Long originPlanRevision,
            Map<String, Anchor> anchors,
            Map<String, Target> targets,
            Map<String, String> routePatterns,
            Map<String, PublishedTarget> publishedTargets,
            Map<String, String> repairDigests,
            Map<String, FragmentLink> fragmentLinks,
            Map<String, String> bodyHeaderSelectors) {}

    private record FragmentLink(String triggerSelector, String rawTarget, String mobileSelector,
            String headerSelector, String targetPath, String targetDigest) {}

    private record PublishedTarget(String sourcePath, String digest, String alternateSourceLabel) {}
    private record Publication(Target target, String routePattern, String sourcePath, String digest) {}

    private record EntryReviews(
            Map<String, List<Node>> positions,
            Map<String, Node> nodes,
            Map<String, ParentEntryDecisionService.Item> decisions,
            Map<String, String> sourceOwners,
            Set<String> originalPaths) {}

    private record EntryDestination(Node node, String url, TargetKind kind, List<Object> proof) {}

    private final UiPackageStorageService packages;
    private final TemplateStorageService templates;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final ContentLinkService links;
    private final SiteRouteRegistry routes;
    private final SiteModelNavigationService navigation;
    private final SiteModelPlanService plans;
    private final SiteModelNavigationEvidence sourceNavigation;
    private final ParentEntryDecisionService parentEntries;
    private final ObjectMapper json = new ObjectMapper();
    private final SiteModelPlanStore store;
    private final TemplateQueryBindingService queryBindings;
    private final NativeFormImportService nativeForms;
    private final FormSiteApiService publicForms;

    @Autowired private CmsBuilderHtmlConfigProperties htmlConfig;
    @Autowired private SiteStoragePathResolver storage;
    @Autowired private SiteWorkbenchContextService workspace;
    @Autowired private TemplateFieldPlanService fieldPlans;

    @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
    private String publicApplicationRoot;

    public PublicNavigationLinkCompiler(
            UiPackageStorageService packages,
            TemplateStorageService templates,
            OneShotMapper menus,
            SiteProjectMapper projects,
            ContentLinkService links,
            SiteRouteRegistry routes,
            SiteModelNavigationService navigation,
            SiteModelPlanService plans,
            SiteModelNavigationEvidence sourceNavigation,
            ParentEntryDecisionService parentEntries,
            TemplateQueryBindingService queryBindings,
            NativeFormImportService nativeForms,
            FormSiteApiService publicForms,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model")
                    String root) {
        this.packages = packages;
        this.templates = templates;
        this.menus = menus;
        this.projects = projects;
        this.links = links;
        this.routes = routes;
        this.navigation = navigation;
        this.plans = plans;
        this.sourceNavigation = sourceNavigation;
        this.parentEntries = parentEntries;
        this.queryBindings = queryBindings;
        this.nativeForms = nativeForms;
        this.publicForms = publicForms;
        this.store = new SiteModelPlanStore(root, json);
    }

    public Workspace read(
            SiteModelNavigationService.Scope scope, String planId, String sourcePath) {
        var evidence = evidence(scope, planId, sourcePath);
        var existing = stored(scope, sourcePath);
        return new Workspace(
                sourcePath,
                evidence.source().digest(),
                evidence.revision(),
                evidence.anchors().values().stream()
                        .sorted(Comparator.comparing(Anchor::selector))
                        .toList(),
                evidence.targets().values().stream()
                        .sorted(Comparator.comparing(Target::label).thenComparing(Target::nodeKey))
                        .toList(),
                existing == null ? null : existing.result(),
                evidence.status());
    }

    /** Read the selected original overview without creating content or a publication receipt. */
    public LandingPreview previewLanding(
            SiteModelNavigationService.Scope scope,
            String planId,
            String sourcePath,
            String nodeKey,
            String containerSelector) {
        return previewLanding(scope, planId, sourcePath, nodeKey, containerSelector, true);
    }

    LandingPreview previewLanding(
            SiteModelNavigationService.Scope scope, String planId, String sourcePath, String nodeKey,
            String containerSelector, boolean verifyQuery) {
        if (containerSelector == null || containerSelector.isBlank()) {
            var candidates = landingCandidates(scope, planId, sourcePath, nodeKey);
            if (candidates.size() != 1)
                throw new IllegalArgumentException(
                        candidates.isEmpty() ? "原页没有与已审直接子栏目完整对应的概览区域" : "原页存在多个完整概览区域，请明确选择候选");
            containerSelector = candidates.getFirst().selector();
        }
        pageKey(sourcePath);
        var view = navigation.appliedSnapshot(scope, planId);
        var parents =
                view.nodes().stream()
                        .filter(
                                node ->
                                        Objects.equals(node.key(), nodeKey)
                                                && sourcePath.equals(node.sourcePath())
                                                && node.kind()
                                                        == SiteModelNavigationService.Kind.LIST)
                        .toList();
        if (parents.size() != 1) throw new IllegalArgumentException("请选择已应用导航中的明确概览父栏目");
        var parent = parents.getFirst();
        requireLandingIdentity(scope, parent, view.nodes());
        var children =
                view.nodes().stream()
                        .filter(node -> nodeKey.equals(node.parentKey()))
                        .sorted(Comparator.comparingInt(Node::order))
                        .toList();
        if (children.size() < 2
                || children.size() > 30
                || children.stream()
                        .anyMatch(
                                node ->
                                        node.kind() != SiteModelNavigationService.Kind.LIST
                                                || !sourcePath.equals(node.sourcePath())))
            throw new IllegalArgumentException("概览须对应同来源的完整直接子栏目集合");
        var source = packages.readStaticPage(scope.code(), scope.packageVersion(), sourcePath);
        var document = Jsoup.parse(source.html());
        if (containerSelector.length() > 1024
                || !containerSelector.matches("[a-zA-Z0-9_.# >():-]+"))
            throw new IllegalArgumentException("无效的概览原页位置");
        var selected = document.select(containerSelector);
        if (selected.size() != 1) throw new IllegalArgumentException("概览原页位置不唯一或已变化");
        var container = selected.first();
        if (!containerSelector.matches("[a-zA-Z0-9_.# -]{1,200}")
                && !containerSelector.equals(container.cssSelector()))
            throw new IllegalArgumentException("请使用原页取证提供的概览候选位置");
        if (!document.select("base[href]").isEmpty()
                || !container.is("ul,ol")
                || container.closest("body") == null
                || container.closest(LANDING_SHARED_REGION) != null
                || !container
                        .select(LANDING_SHARED_REGION + ",form,input,select,textarea,button,script")
                        .isEmpty()
                || container.childrenSize() != children.size())
            throw new IllegalArgumentException("请选择主体内完整的独立栏目卡片列表");
        var cards = new ArrayList<LandingCard>();
        var allMenus = menus.selectListBySiteProjectId(scope.siteId());
        for (int i = 0; i < children.size(); i++) {
            var node = children.get(i);
            var actual = requireLandingIdentity(scope, node, view.nodes());
            var card = container.child(i);
            var anchors = card.select("a[href]");
            var images = card.select("img[src]");
            var headings = card.select("h2,h3,h4");
            if (!card.is("li")
                    || anchors.size() != 1
                    || images.size() != 1
                    || headings.isEmpty()
                    || headings.stream().anyMatch(heading -> !heading.text().equals(node.name()))
                    || !localSource(anchors.first().attr("href"), true)
                    || URI.create(anchors.first().attr("href")).getRawQuery() != null)
                throw new IllegalArgumentException("原卡片标题、顺序或入口无法唯一对应已审子栏目");
            var anchor = anchors.first();
            if (!anchor.getAllElements().contains(images.first())
                    || headings.stream()
                            .anyMatch(heading -> !anchor.getAllElements().contains(heading)))
                throw new IllegalArgumentException("原卡片图片和标题须属于同一入口");
            LandingTargetState state;
            String url = null;
            if (!Boolean.TRUE.equals(actual.getHasFrontChannel()))
                state = LandingTargetState.FRONT_DISABLED;
            else if (actual.getListPageUrl() == null || actual.getListPageUrl().isBlank())
                state = LandingTargetState.ROUTE_MISSING;
            else if (!hasTemplate(scope.code(), actual.getListPageTemplate()))
                state = LandingTargetState.TEMPLATE_MISSING;
            else {
                state = Boolean.TRUE.equals(node.independentLanding())
                        ? LandingTargetState.READY : leafListState(scope, actual, verifyQuery);
                if (state == LandingTargetState.READY) {
                    url = route(scope, actual, allMenus == null ? List.of() : allMenus);
                    if (url == null) state = LandingTargetState.ROUTE_CONFLICT;
                }
                var uri = URI.create(anchor.attr("href"));
                if (url != null
                        && uri.getRawFragment() != null
                        && !uri.getRawFragment().isEmpty()) {
                    if (hasTemplateAnchor(
                            scope.code(), actual.getListPageTemplate(), uri.getFragment())) {
                        url += "#" + uri.getRawFragment();
                    } else {
                        url = null;
                        state = LandingTargetState.ANCHOR_MISSING;
                    }
                }
            }
            cards.add(
                    new LandingCard(
                            anchor.cssSelector(),
                            node.key(),
                            node.menuId(),
                            node.name(),
                            anchor.attr("href"),
                            images.first().attr("src"),
                            card.select("p").text(),
                            state,
                            url));
        }
        var values = List.copyOf(cards);
        String digest =
                PublicBindingApplication.digest(
                        json.writeValueAsString(
                                List.of(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        planId,
                                        view.revision(),
                                        view.planRevision(),
                                        sourcePath,
                                        source.digest(),
                                        nodeKey,
                                        parent.menuId(),
                                        containerSelector,
                                        values)));
        return new LandingPreview(
                sourcePath,
                source.digest(),
                view.revision(),
                view.planRevision(),
                nodeKey,
                parent.menuId(),
                parent.name(),
                containerSelector,
                values,
                digest);
    }

    private OneshotEntity requireLandingIdentity(
            SiteModelNavigationService.Scope scope, Node node, List<Node> nodes) {
        var actual =
                node.menuId() == null
                        ? null
                        : menus.selectPublicBindingMenu(scope.siteId(), node.menuId().longValue());
        if (!matches(scope, actual, node, nodes)
                || !Objects.equals(node.name(), actual.getClassName()))
            throw new IllegalArgumentException("已审核栏目身份、名称或父子关系已变化，请重新审核");
        return actual;
    }

    public Preview preview(SiteModelNavigationService.Scope scope, String planId, Request request) {
        var evidence = evidence(scope, planId, request.sourcePath());
        var previous = stored(scope, request.sourcePath());
        requireSelections(request, previous);
        var preview = preview(scope, planId, request, evidence);
        requireCurrentConfirmation(previous, request.previousDigest(), preview.digest());
        return preview;
    }

    @Transactional
    public Preview confirm(SiteModelNavigationService.Scope scope, String planId, Request request) {
        if (!Boolean.TRUE.equals(request.confirmed()) || request.digest() == null)
            throw new IllegalArgumentException("请先核对公共导航映射并明确确认");
        var project = projects.selectByIdForUpdate(scope.siteId());
        if (project == null
                || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(scope.code(), project.getProjectCode())
                || !Objects.equals(scope.packageVersion(), project.getUiPackageVersion())
                || !Objects.equals(scope.rootMenuId(), project.getRootMenuId()))
            throw new IllegalArgumentException("当前站点或原包版本已变化，请重新取证");
        var evidence = evidence(scope, planId, request.sourcePath());
        var current = stored(scope, request.sourcePath());
        requireSelections(request, current);
        var preview = preview(scope, planId, request, evidence);
        if (!preview.digest().equals(request.digest()))
            throw new IllegalArgumentException("原包、导航节点或真实路由已变化，请重新审核");
        requireCurrentConfirmation(current, request.previousDigest(), preview.digest());
        if (current != null && current.result().digest().equals(preview.digest()))
            return current.result();
        var reviewed = new java.util.TreeSet<String>();
        if (current != null && Objects.equals(current.sourceDigest(), request.sourceDigest()))
            reviewed.addAll(current.reviewedSelectors());
        preview.changes().forEach(change -> reviewed.add(change.selector()));
        if (reviewed.size() > MAX_ANCHORS) throw new IllegalArgumentException("已审核公共链接数量超限");
        store.saveNavigationLinks(
                scope.siteId(),
                scope.code(),
                scope.packageVersion(),
                pageKey(request.sourcePath()),
                new Stored(
                        planId,
                        request.sourcePath(),
                        request.sourceDigest(),
                        request.navigationRevision(),
                        List.copyOf(request.selections()),
                        preview,
                        List.copyOf(reviewed)));
        return preview;
    }

    List<Change> approvedLinks(Long siteId, String code, String version, String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) return List.of();
        var stored =
                store.navigationLinks(siteId, code, version, pageKey(sourcePath), Stored.class);
        if (stored == null) return List.of();
        if (!sourcePath.equals(stored.sourcePath()) || stored.result() == null)
            throw new IllegalArgumentException("公共导航回执与原页不一致");
        return stored.result().changes();
    }

    Map<String, Target> releaseEntryTargets(SiteModelNavigationService.Scope scope, String planId,
            String sourcePath, Integer applyingMenuId, List<Node> nodes) {
        var stored = stored(scope, sourcePath);
        if (stored == null || !planId.equals(stored.planId()) || stored.result() == null) return Map.of();
        var approved = new HashMap<String, Change>();
        for (var change : stored.result().changes()) {
            if (!change.nodeKey().startsWith("entry-")) continue;
            for (var node : nodes) {
                if (change.nodeKey().equals(entryKey(scope, sourcePath, change.selector(), node.key(), change.rawHref()))) {
                    approved.put(node.key(), change);
                    break;
                }
            }
        }
        if (approved.isEmpty()) return Map.of();
        var previous = stored.result().changes().stream().map(Change::nodeKey)
                .collect(java.util.stream.Collectors.toSet());
        var current = evidence(scope, planId, sourcePath,
                applyingMenuId == null ? null : applyingMenuId.longValue(), previous);
        if (current.status() != Status.APPLIED || current.revision() != stored.navigationRevision()
                || !current.source().digest().equals(stored.sourceDigest())) return Map.of();
        var verified = new HashMap<String, Target>();
        approved.forEach((key, change) -> {
            var anchor = current.anchors().get(change.selector());
            var target = current.targets().get(change.nodeKey());
            if (anchor != null && target != null && Objects.equals(anchor.rawHref(), change.rawHref())
                    && (Objects.equals(anchor.suggestedNodeKey(), target.nodeKey())
                            || anchor.eligibleTargetKeys().contains(target.nodeKey()))
                    && Objects.equals(target.menuId(), change.menuId())
                    && Objects.equals(target.url(), change.url())) verified.put(key, target);
        });
        return Map.copyOf(verified);
    }

    public String bind(Long siteId, String code, String version, String sourcePath, String html) {
        return bind(siteId, code, version, sourcePath, html, null);
    }

    String bind(
            Long siteId,
            String code,
            String version,
            String sourcePath,
            String html,
            Long applyingMenuId) {
        return bind(siteId, code, version, sourcePath, html, applyingMenuId, List.of());
    }

    String bind(
            Long siteId,
            String code,
            String version,
            String sourcePath,
            String html,
            Long applyingMenuId,
            List<String> occupiedSelectors) {
        var stored =
                store.navigationLinks(siteId, code, version, pageKey(sourcePath), Stored.class);
        if (stored == null) return html;
        var project = projects.selectById(siteId);
        if (project == null
                || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getUiPackageVersion(), version)
                || project.getRootMenuId() == null
                || !Objects.equals(stored.sourcePath(), sourcePath))
            throw new IllegalArgumentException("已确认公共导航与当前站点或来源不一致");
        var scope =
                new SiteModelNavigationService.Scope(
                        siteId, code, version, project.getRootMenuId());
        var request =
                new Request(
                        version,
                        sourcePath,
                        stored.sourceDigest(),
                        stored.navigationRevision(),
                        stored.selections(),
                        null,
                        stored.result().digest(),
                        true);
        var previouslyApproved =
                stored.result().changes().stream()
                        .map(Change::nodeKey)
                        .collect(java.util.stream.Collectors.toSet());
        var evidence =
                evidence(scope, stored.planId(), sourcePath, applyingMenuId, previouslyApproved);
        var checked = preview(scope, stored.planId(), request, evidence);
        if (!checked.equals(stored.result()))
            throw new IllegalArgumentException("公共导航的来源、栏目或真实路由已变化，请重新审核");
        if (checked.changes().isEmpty()) return html;
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var confirmedFragments = checked.changes().stream().map(Change::selector)
                .filter(evidence.fragmentLinks()::containsKey).toList();
        if (!confirmedFragments.isEmpty() && document.select("script").isEmpty()) {
            var original = Jsoup.parse(evidence.source().html());
            clearInactiveFragmentSiblings(document, original, confirmedFragments,
                    checked.changes().stream().map(Change::selector).toList());
        }
        for (var change : checked.changes()) {
            var elements = document.select(change.selector());
            var sourceAnchor = evidence.anchors().get(change.selector());
            var fragmentLink = evidence.fragmentLinks().get(change.selector());
            if (elements.size() != 1
                    || sourceAnchor == null
                    || fragmentLink == null && !(evidence.bodyHeaderSelectors().containsKey(change.selector())
                            || change.nodeKey().startsWith(BODY_FRAGMENT_KEY_PREFIX)
                            ? bodyReferenceLabel(elements.first()) != null
                            : Objects.equals(region(elements.first()), sourceAnchor.region())))
                throw new IllegalArgumentException("编译改变了已审核的链接位置：" + change.label());
            var anchor = elements.first();
            if ("BODY".equals(sourceAnchor.region())
                    && occupiedSelectors.stream()
                            .anyMatch(selector -> anchor.closest(selector) != null))
                throw new IllegalArgumentException("正文链接与已审核的表单动作或推荐导航重叠：" + anchor.text());
            if (!anchor.is("a[href]")
                    || anchor.hasAttr("th:href")
                    || anchor.hasAttr("data-th-href")
                    || (sourceAnchor.repair() != null || evidence.publishedTargets().containsKey(change.nodeKey()))
                            && !anchor.text().equals(sourceAnchor.label())
                    || !(change.rawHref().equals(anchor.attr("href"))
                            || change.url().equals(anchor.attr("href"))))
                throw new IllegalArgumentException("编译改变了已审核的公共链接：" + change.label());
            if (fragmentLink != null) {
                if (!anchor.text().equals(sourceAnchor.label())
                        || !document.select("script").isEmpty())
                    throw new IllegalArgumentException("正文分类仍有旧脚本或原文已变化：" + change.label());
                renderFragmentLink(document, anchor, fragmentLink, change);
            }
            anchor.attr("href", change.url());
        }
        return document.outerHtml();
    }

    boolean hasConfirmation(Long siteId, String code, String version, String sourcePath) {
        return store.navigationLinks(siteId, code, version, pageKey(sourcePath), Stored.class)
                != null;
    }

    java.util.function.UnaryOperator<String> reapplicationComparison(
            Long siteId, String code, String version, String sourcePath) {
        var stored =
                store.navigationLinks(siteId, code, version, pageKey(sourcePath), Stored.class);
        if (stored == null || !Objects.equals(stored.sourcePath(), sourcePath))
            throw new IllegalArgumentException("找不到可核对的原公开导航模板");
        var source = packages.readStaticPage(code, version, sourcePath);
        if (!source.digest().equals(stored.sourceDigest()))
            throw new IllegalArgumentException("原公开模板的导航来源已变化");
        var original = Jsoup.parse(source.html());
        return html -> withoutApprovedHrefs(html, stored.reviewedSelectors(), original);
    }

    static String withoutApprovedHrefs(String html, List<String> selectors) {
        return withoutApprovedHrefs(html, selectors, null);
    }

    private static String withoutApprovedHrefs(String html, List<String> selectors, Document original) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        clearInactiveFragmentSiblings(document, original, selectors, selectors);
        for (String selector : selectors) {
            var elements = document.select(selector);
            var originalAnchor = original == null ? null : original.selectFirst(selector);
            var tab = fragmentTrigger(originalAnchor);
            boolean bodyReference = bodyReferenceLabel(originalAnchor) != null;
            if (elements.size() != 1
                    || !elements.first().is(tab == null ? "a[href]" : "a")
                    || elements.first().hasAttr("th:href")
                    || elements.first().hasAttr("data-th-href")
                    || tab == null && region(elements.first()) == null
                            && !(bodyReference && bodyReferenceLabel(elements.first()) != null))
                throw new IllegalArgumentException("原公开模板的导航链接位置已变化");
            if (tab != null) {
                var oldAnchor = elements.first();
                var trigger = requireFragmentTrigger(document, oldAnchor, tab.cssSelector());
                var raw = tab.attr("data-src");
                boolean published = oldAnchor.hasAttr("href") && !STATIC_TAB_HREF.equals(oldAnchor.attr("href"));
                if (!oldAnchor.text().equals(originalAnchor.text()))
                    throw new IllegalArgumentException("正文分类的原文已变化");
                if (STATIC_TAB_HREF.equals(oldAnchor.attr("href"))) {
                    if (!raw.equals(trigger.attr("data-src")))
                        throw new IllegalArgumentException("原正文分类的片段路径已变化");
                } else if (trigger.hasAttr("data-src")) {
                    throw new IllegalArgumentException("已发布的正文分类仍含片段请求");
                }
                trigger.removeAttr("data-src");
                var mobile = fragmentMobileHeading(tab, oldAnchor.text());
                if (mobile != null) {
                    var heading = requireFragmentMobileHeading(document, trigger, mobile.cssSelector());
                    var links = heading.children().stream()
                            .filter(link -> selector.equals(link.attr(MOBILE_FRAGMENT_LINK))).toList();
                    if (published) {
                        var expected =
                                mobileFragmentLink(
                                        selector, oldAnchor.text(), oldAnchor.attr("href"));
                        if (links.size() != 1 || !expected.outerHtml().equals(links.getFirst().outerHtml()))
                            throw new IllegalArgumentException("已发布的移动正文分类链接已变化");
                        links.getFirst().remove();
                    } else if (!links.isEmpty()) {
                        throw new IllegalArgumentException("原模板混有已发布的移动正文分类链接");
                    }
                }
            }
            elements.first().removeAttr("href");
        }
        return document.outerHtml();
    }

    private static void clearInactiveFragmentSiblings(
            Document document, Document original, List<String> groupMembers, List<String> retained) {
        if (original == null || groupMembers.isEmpty() || !document.select("script").isEmpty()) return;
        var selected = Set.copyOf(retained);
        var groups = new LinkedHashSet<Element>();
        for (String selector : groupMembers) {
            var trigger = fragmentTrigger(original.selectFirst(selector));
            if (trigger != null) groups.add(trigger.parent());
        }
        for (var group : groups) {
            for (var item : group.children()) {
                var originalAnchor = item.childrenSize() == 1 ? item.child(0) : null;
                if (fragmentTrigger(originalAnchor) != item) continue;
                String selector = originalAnchor.cssSelector();
                if (selected.contains(selector)) continue;
                var matches = document.select(selector);
                if (matches.size() != 1 || !matches.first().is("a")
                        || !matches.first().text().equals(originalAnchor.text()))
                    throw new IllegalArgumentException("未接线的正文分类位置或原文已变化");
                var anchor = matches.first();
                var trigger = requireFragmentTrigger(document, anchor, item.cssSelector());
                if (!anchor.hasAttr("href") && !trigger.hasAttr("data-src")) continue;
                if (fragmentTrigger(anchor) != trigger || !item.attr("data-src").equals(trigger.attr("data-src")))
                    throw new IllegalArgumentException("未接线的正文分类原动作已变化");
                anchor.removeAttr("href");
                trigger.removeAttr("data-src");
            }
        }
    }

    private Preview preview(
            SiteModelNavigationService.Scope scope,
            String planId,
            Request request,
            Evidence evidence) {
        if (!Objects.equals(request.sourceDigest(), evidence.source().digest())
                || request.navigationRevision() != evidence.revision())
            throw new IllegalArgumentException("导航回执或原包内容已变化，请重新读取");
        if (request.selections() == null || request.selections().size() > MAX_ANCHORS)
            throw new IllegalArgumentException("请选择待绑定的公共导航链接");
        var selectors = new HashSet<String>();
        var changes = new ArrayList<Change>();
        for (var selected : request.selections()) {
            if (selected == null
                    || selected.selector() == null
                    || !selectors.add(selected.selector()))
                throw new IllegalArgumentException("公共链接选择器缺失或重复");
            var anchor = evidence.anchors().get(selected.selector());
            var target = evidence.targets().get(selected.nodeKey());
            if (anchor == null || !anchor.rawHref().equals(selected.rawHref()) || target == null)
                throw new IllegalArgumentException("原始链接或目标栏目已变化，请重新读取");
            if ("BODY".equals(anchor.region()) && !Objects.equals(anchor.suggestedNodeKey(), target.nodeKey()))
                throw new IllegalArgumentException("正文链接所选栏目与其唯一包内目标不一致");
            if (!anchor.eligibleTargetKeys().contains(target.nodeKey()))
                throw new IllegalArgumentException("发布目标与该原页链接位置、原文或来源不一致");
            changes.add(
                    new Change(
                            anchor.selector(),
                            anchor.rawHref(),
                            target.nodeKey(),
                            target.menuId(),
                            target.label(),
                            target.url()));
        }
        var selections = new HashMap<String, String>();
        changes.forEach(change -> selections.put(change.selector(), change.nodeKey()));
        for (var change : changes) {
            var fragmentLink = evidence.fragmentLinks().get(change.selector());
            String headerSelector = fragmentLink == null
                    ? evidence.bodyHeaderSelectors().get(change.selector())
                    : fragmentLink.headerSelector();
            if (headerSelector != null && !change.nodeKey().equals(selections.get(headerSelector)))
                throw new IllegalArgumentException("请同时保留已确认的同页页头栏目：" + change.label());
        }
        changes.sort(Comparator.comparing(Change::selector));
        var digestData =
                new ArrayList<Object>(
                        List.of(
                                scope.siteId(),
                                scope.code(),
                                scope.packageVersion(),
                                planId,
                                request.sourcePath(),
                                evidence.source().digest(),
                                evidence.revision(),
                                changes,
                                changes.stream()
                                        .map(
                                                change ->
                                                        evidence.routePatterns()
                                                                .get(change.nodeKey()))
                                        .toList()));
        if (evidence.originPlanId() != null) {
            digestData.add(evidence.originPlanId());
            digestData.add(evidence.originPlanRevision());
        }
        var publishedDigests = changes.stream()
                .filter(change -> evidence.publishedTargets().containsKey(change.nodeKey()))
                .map(change -> evidence.publishedTargets().get(change.nodeKey()).digest())
                .toList();
        if (!publishedDigests.isEmpty()) {
            digestData.add(evidence.status());
            digestData.add(evidence.planRevision());
            digestData.add(publishedDigests);
        }
        var repairs = changes.stream()
                .map(change -> evidence.anchors().get(change.selector()).repair())
                .filter(Objects::nonNull)
                .map(repair -> List.of(repair.sourcePath(), evidence.repairDigests().get(repair.sourcePath())))
                .toList();
        if (!repairs.isEmpty()) digestData.add(repairs);
        var fragments = changes.stream().map(change -> evidence.fragmentLinks().get(change.selector()))
                .filter(Objects::nonNull)
                .map(link -> List.of(link.triggerSelector(), link.rawTarget(), link.mobileSelector(),
                        link.headerSelector(), link.targetPath(), link.targetDigest()))
                .toList();
        if (!fragments.isEmpty()) digestData.add(fragments);
        String digest = PublicBindingApplication.digest(json.writeValueAsString(digestData));
        return new Preview(digest, List.copyOf(changes));
    }

    private Evidence evidence(
            SiteModelNavigationService.Scope scope, String planId, String sourcePath) {
        return evidence(scope, planId, sourcePath, null, java.util.Set.of());
    }

    private Evidence evidence(
            SiteModelNavigationService.Scope scope,
            String planId,
            String sourcePath,
            Long applyingMenuId,
            java.util.Set<String> previouslyApproved) {
        pageKey(sourcePath);
        var receipt =
                store.navigation(scope.siteId(), scope.code(), scope.packageVersion(), planId);
        var view = receipt == null ? navigation.inherited(scope, planId) : receipt.view();
        var status = view == null ? Status.DRAFT : view.status();
        if (status == Status.APPLYING)
            throw new IllegalArgumentException("整站导航正在应用，请稍后重新读取");
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
        if (status != Status.APPLIED && !"CONFIRMED".equals(plan.status()))
            throw new IllegalArgumentException("请先确认当前站点结构方案");
        long planRevision = status == Status.APPLIED ? view.planRevision() : plan.revision();
        if (receipt != null && status == Status.APPLIED)
            view = SiteModelNavigationService.withModelLinks(view,
                    store.navigationModelLinks(plan, view, scope.rootMenuId()));
        var nodes = status == Status.APPLIED ? view.nodes() : List.<Node>of();
        var source = packages.readStaticPage(scope.code(), scope.packageVersion(), sourcePath);
        var document = Jsoup.parse(source.html());
        var entryReviews = entryReviews(scope, plan, view, sourcePath, document, nodes);
        if (document.selectFirst("base[href]") != null)
            throw new IllegalArgumentException("公共导航含 base 路径，请先人工处理");
        var targetsInPackage = nodes.stream()
                .filter(node -> !node.drift() && node.menuId() != null && node.sourcePath() != null
                        && node.kind() != SiteModelNavigationService.Kind.DIRECTORY)
                .collect(java.util.stream.Collectors.groupingBy(Node::sourcePath));
        var anchors = new HashMap<String, Anchor>();
        var repairDigests = new HashMap<String, String>();
        for (Element item : document.select("a[href]")) {
            String region = region(item);
            String href = item.attr("href");
            if (region == null
                    || !localSource(href)
                            && !fragmentHref(href)
                            && !(href.matches("#+")
                                    && entryReviews.positions().containsKey(item.cssSelector())))
                continue;
            if ("BODY".equals(region) && fragmentHref(href)
                    && !entryReviews.positions().containsKey(item.cssSelector())) continue;
            String bodyTargetKey = null;
            LinkRepair repair = null;
            if (localSource(href) && hasRepeatedSeparator(href)) {
                String repairPath = repeatedSeparatorTarget(sourcePath, href);
                if (repairPath != null && repairDigests.computeIfAbsent(repairPath, path -> {
                    try {
                        return packages.readStaticPage(scope.code(), scope.packageVersion(), path).digest();
                    } catch (IllegalArgumentException | ResponseStatusException unavailable) {
                        return null;
                    }
                }) != null) {
                    repair = new LinkRepair(repairPath, "合并浏览器路径中的连续斜杠后找到原包页面");
                }
            } else if ("BODY".equals(region) && !fragmentHref(href)) {
                var targetPath = SiteModelEvidenceService.resolveLocal(sourcePath, href, targetsInPackage.keySet());
                if (targetPath == null || targetsInPackage.get(targetPath).size() != 1) continue;
                bodyTargetKey = targetsInPackage.get(targetPath).getFirst().key();
            }
            if (anchors.size() >= MAX_ANCHORS) throw new IllegalArgumentException("公共导航链接数量超限");
            String selector = item.cssSelector();
            if (selector.length() > 1024
                    || anchors.putIfAbsent(
                                    selector, new Anchor(
                                            selector, href, item.text(), region, bodyTargetKey, List.of(), repair))
                            != null) throw new IllegalArgumentException("公共导航链接位置无法唯一定位");
        }
        var menuList = menus.selectListBySiteProjectId(scope.siteId());
        var items = menuList == null ? List.<OneshotEntity>of() : menuList;
        var targets = new HashMap<String, Target>();
        var patterns = new HashMap<String, String>();
        var appliedPublished = new HashSet<String>();
        for (Node node : nodes) {
            if (node.menuId() == null || node.kind() == SiteModelNavigationService.Kind.DIRECTORY)
                continue;
            var item = menus.selectPublicBindingMenu(scope.siteId(), node.menuId().longValue());
            if (!matches(scope, item, node, nodes)
                    || !Boolean.TRUE.equals(item.getHasFrontChannel())) continue;
            boolean templatePresent = hasTemplate(scope.code(), item.getListPageTemplate());
            if (!templatePresent
                    && !restoringSelf(scope, node, sourcePath, applyingMenuId, previouslyApproved))
                continue;
            String url = route(scope, item, items);
            if (url != null) {
                targets.put(node.key(), new Target(node.key(), item.getId(), node.name(), url, null));
                patterns.put(node.key(), Objects.toString(item.getListPageUrl(), ""));
                if (templatePresent && !Boolean.TRUE.equals(item.getIsDeleted())
                        && item.getListPageUrl() != null && !item.getListPageUrl().isBlank()
                        && (Integer.valueOf(1).equals(item.getPageType())
                                || Integer.valueOf(3).equals(item.getStatus()))) {
                    appliedPublished.add(node.key());
                }
            }
        }
        var legacyKeys = List.copyOf(targets.keySet());
        var published = new HashMap<String, PublishedTarget>();
        var publications = addPublishedTargets(scope, sourcePath, anchors, items, targets, patterns, published);
        var checkedEntries = new HashMap<String, Boolean>();
        anchors.replaceAll((selector, original) -> {
            var reviewed = reviewEntry(scope, sourcePath, original, entryReviews, checkedEntries,
                    appliedPublished, targets, patterns, published, publications);
            if (reviewed != null) return reviewed;
            if (hasRepeatedSeparator(original.rawHref())) {
                var eligible = repairTargetKeys(
                        original, targetsInPackage, appliedPublished, publications, published, targets);
                return new Anchor(original.selector(), original.rawHref(), original.label(), original.region(),
                        eligible.isEmpty() ? null : eligible.getFirst(), eligible, original.repair());
            }
            var anchor = suggest(sourcePath, original, nodes, targets);
            var eligible = "BODY".equals(anchor.region())
                    ? legacyKeys.stream().filter(key -> key.equals(anchor.suggestedNodeKey())).toList()
                    : java.util.stream.Stream.concat(
                                    legacyKeys.stream(),
                                    published.entrySet().stream()
                                            .filter(entry -> matchesPublishedSource(
                                                    sourcePath, anchor, entry.getValue().sourcePath())
                                                    && (anchor.label().equals(targets.get(entry.getKey()).label())
                                                            || anchor.label().equals(entry.getValue().alternateSourceLabel())))
                                            .map(Map.Entry::getKey))
                            .sorted()
                            .toList();
            return new Anchor(anchor.selector(), anchor.rawHref(), anchor.label(), anchor.region(),
                    anchor.suggestedNodeKey(), eligible, null);
        });
        var sourceLists = publications.getOrDefault(sourcePath, List.of()).stream()
                .filter(publication -> publication.target().kind() == TargetKind.CONTENT_LIST)
                .toList();
        var fragmentLinks = sourceLists.size() == 1
                && (applyingMenuId == null || applyingMenuId.equals(sourceLists.getFirst().target().menuId().longValue()))
                ? addFragmentLinks(scope, sourcePath, source.digest(), document, anchors, targets)
                : Map.<String, FragmentLink>of();
        var confirmed = stored(scope, sourcePath);
        var bodyHeaderSelectors = targetsInPackage.containsKey(sourcePath)
                ? addBodyReferences(sourcePath, document, anchors, targets, published,
                        confirmed != null && planId.equals(confirmed.planId()) && view != null
                                && view.revision() == confirmed.navigationRevision()
                                && source.digest().equals(confirmed.sourceDigest()) && confirmed.result() != null
                                ? confirmed.result().changes() : List.of())
                : Map.<String, String>of();
        addBodyFragments(scope, sourcePath, document, anchors, entryReviews, targetsInPackage,
                targets, patterns, published, appliedPublished, applyingMenuId);
        return new Evidence(
                source,
                view == null ? 0 : view.revision(),
                status,
                planRevision,
                view == null ? null : view.originPlanId(),
                view == null ? null : view.originPlanRevision(),
                anchors,
                targets,
                patterns,
                published,
                repairDigests,
                fragmentLinks,
                bodyHeaderSelectors);
    }

    private static Map<String, String> addBodyReferences(
            String sourcePath, Document document, Map<String, Anchor> anchors,
            Map<String, Target> targets, Map<String, PublishedTarget> published, List<Change> confirmed) {
        var headers = new HashMap<Anchor, String>();
        for (var anchor : anchors.values()) {
            if (!"HEADER".equals(anchor.region())) continue;
            var target = targets.get(anchor.suggestedNodeKey());
            if (target != null && published.containsKey(target.nodeKey())
                    && (target.kind() == TargetKind.FRAGMENT || target.kind() == TargetKind.PARENT_ENTRY)) {
                headers.put(anchor, target.nodeKey());
            }
        }
        for (var change : confirmed) {
            var header = anchors.get(change.selector());
            var target = targets.get(change.nodeKey());
            if (header != null && "HEADER".equals(header.region()) && header.rawHref().equals(change.rawHref())
                    && target != null && target.kind() == TargetKind.CONTENT_LIST
                    && target.nodeKey().startsWith("entry-") && published.containsKey(target.nodeKey())
                    && header.eligibleTargetKeys().contains(target.nodeKey())
                    && Objects.equals(target.menuId(), change.menuId()) && target.url().equals(change.url())) {
                headers.put(header, target.nodeKey());
            }
        }
        if (headers.isEmpty()) return Map.of();
        var bodyHeaderSelectors = new HashMap<String, String>();
        for (var item : document.select(".main a[href]")) {
            String label = bodyReferenceLabel(item);
            String selector = item.cssSelector();
            if (label == null || anchors.containsKey(selector)) continue;
            String href = item.attr("href");
            var fragment = URI.create(href).getRawFragment();
            var matches = headers.keySet().stream()
                    .filter(header -> label.equals(header.label()))
                    .filter(header -> Objects.equals(fragment, URI.create(header.rawHref()).getRawFragment()))
                    .filter(header -> {
                        String path = published.get(headers.get(header)).sourcePath();
                        return path.equals(SiteModelEvidenceService.resolveLocal(sourcePath, href, Set.of(path)));
                    })
                    .limit(2).toList();
            if (matches.size() != 1) continue;
            if (anchors.size() >= MAX_ANCHORS || selector.length() > 1024
                    || document.select(selector).size() != 1)
                throw new IllegalArgumentException("正文链接位置无法唯一定位或数量超限");
            var header = matches.getFirst();
            anchors.put(selector, new Anchor(selector, href, item.text(), "BODY", null)
                    .reviewed(headers.get(header), null));
            bodyHeaderSelectors.put(selector, header.selector());
        }
        return bodyHeaderSelectors;
    }

    private static String bodyReferenceLabel(Element anchor) {
        if (anchor == null
                || !anchor.is("a[href]")
                || anchor.closest(BODY_CONTENT_REGION) == null
                || anchor.closest(BODY_REFERENCE_EXCLUSIONS) != null
                || !localSource(anchor.attr("href"), true)
                || hasRepeatedSeparator(anchor.attr("href"))
                || URI.create(anchor.attr("href")).getRawQuery() != null
                || anchor.attributes().asList().stream()
                        .anyMatch(
                                attr ->
                                        !Set.of("href", "class", "id", "title", "aria-label")
                                                .contains(attr.getKey()))
                || anchor.selectFirst("button,input,select,textarea,script,[role=button]") != null
                || anchor.text().isBlank()) return null;
        var headings =
                anchor.children().stream().filter(child -> child.is("h1,h2,h3,h4,h5,h6")).toList();
        return headings.size() == 1 ? headings.getFirst().text() : anchor.text();
    }

    private void addBodyFragments(
            SiteModelNavigationService.Scope scope,
            String sourcePath,
            Document document,
            Map<String, Anchor> anchors,
            EntryReviews reviews,
            Map<String, List<Node>> ownersByPath,
            Map<String, Target> targets,
            Map<String, String> patterns,
            Map<String, PublishedTarget> published,
            Set<String> appliedPublished,
            Long applyingMenuId) {
        var source = bodyOwner(sourcePath, reviews, ownersByPath);
        if (source == null
                || applyingMenuId != null && applyingMenuId.longValue() != source.menuId()
                || !validEntryHierarchy(scope, source, reviews, new HashMap<>())) return;
        for (var item : document.select("a[href]")) {
            String label = bodyReferenceLabel(item);
            if (label == null) continue;
            String href = item.attr("href");
            var uri = URI.create(href);
            if (uri.getRawFragment() == null || uri.getRawFragment().isBlank()) continue;
            String selector = item.cssSelector();
            var previous = anchors.get(selector);
            if (previous != null
                            && (!"BODY".equals(previous.region())
                                    || !previous.eligibleTargetKeys().isEmpty())
                    || reviews.positions().containsKey(selector)) continue;
            String targetPath =
                    SiteModelEvidenceService.resolveLocal(sourcePath, href, ownersByPath.keySet());
            if (sourcePath.equals(targetPath)) continue;
            if (previous == null && anchors.size() >= MAX_ANCHORS
                    || selector.length() > 1024
                    || document.select(selector).size() != 1)
                throw new IllegalArgumentException("正文链接位置无法唯一定位或数量超限");
            var candidate = new Anchor(selector, href, item.text(), "BODY", null);
            var owner = targetPath == null ? null : bodyOwner(targetPath, reviews, ownersByPath);
            var route = owner == null ? null : targets.get(owner.key());
            var proof =
                    route == null
                                    || !appliedPublished.contains(owner.key())
                                    || !validEntryHierarchy(scope, owner, reviews, new HashMap<>())
                            ? null
                            : bodyFragmentPublication(
                                    scope, owner, route, targetPath, uri.getFragment(), label);
            if (proof == null) {
                anchors.put(selector, candidate.reviewed(null, "正文分区的原包归属、正式发布产物或标题待核对"));
                continue;
            }
            String url = route.url() + "#" + uri.getRawFragment();
            String key =
                    BODY_FRAGMENT_KEY_PREFIX
                            + PublicBindingApplication.digest(
                                    json.writeValueAsString(
                                            List.of(
                                                    scope,
                                                    sourcePath,
                                                    selector,
                                                    href,
                                                    owner.key())));
            targets.put(
                    key, new Target(key, owner.menuId(), owner.name(), url, TargetKind.FRAGMENT));
            patterns.put(key, patterns.get(owner.key()));
            published.put(
                    key,
                    new PublishedTarget(
                            targetPath,
                            PublicBindingApplication.digest(
                                    json.writeValueAsString(
                                            List.of(
                                                    source.key(),
                                                    source.menuId(),
                                                    owner.key(),
                                                    owner.menuId(),
                                                    url,
                                                    label,
                                                    proof))),
                            null));
            anchors.put(selector, candidate.reviewed(key, null));
        }
    }

    private static Node bodyOwner(
            String path, EntryReviews reviews, Map<String, List<Node>> ownersByPath) {
        var candidates = ownersByPath.getOrDefault(path, List.of());
        if (candidates.size() == 1) return candidates.getFirst();
        String owner = reviews.sourceOwners().get(path);
        return candidates.stream()
                .filter(node -> node.key().equals(owner))
                .findFirst()
                .orElse(null);
    }

    private List<String> bodyFragmentPublication(
            SiteModelNavigationService.Scope scope,
            Node owner,
            Target route,
            String targetPath,
            String fragment,
            String label) {
        if (workspace == null) return null;
        var menu = menus.selectPublicBindingMenu(scope.siteId(), owner.menuId().longValue());
        if (menu == null || !hasTemplate(scope.code(), menu.getListPageTemplate())) return null;
        var url = URI.create(route.url());
        if (!route.url().startsWith("/")
                || !WorkbenchStaticLinkCheckService.safeUrlPath(url)
                || url.getRawFragment() != null
                || url.getRawQuery() != null) return null;
        String template = menu.getListPageTemplate();
        try {
            var source = packages.readStaticPage(scope.code(), scope.packageVersion(), targetPath);
            var root =
                    Path.of(storage.siteRootPath(htmlConfig.getSaveRoot(), scope.code()))
                            .toAbsolutePath()
                            .normalize();
            var output = WorkbenchStaticLinkCheckService.localFile(root, url.getPath());
            var templateFile =
                    templates.resolveTemplateDir(scope.code()).resolve(template).normalize();
            if (output == null
                    || Files.size(output) > 2 * 1024 * 1024
                    || Files.size(templateFile) > 2 * 1024 * 1024
                    || Files.getLastModifiedTime(templateFile)
                                    .compareTo(Files.getLastModifiedTime(output))
                            > 0) return null;
            String templateHtml = Files.readString(templateFile);
            String outputHtml = Files.readString(output);
            String proof = StaticRenderProof.of(scope.siteId(), owner.menuId(), false, template);
            if (!proof.equals(
                            workspace.staticArtifactProof(
                                    scope.siteId(), scope.code(), output, outputHtml))
                    || !bodyAnchor(Jsoup.parse(templateHtml), fragment)
                    || !label.equals(bodyFragmentTitle(Jsoup.parse(source.html()), fragment))
                    || !label.equals(bodyFragmentTitle(Jsoup.parse(outputHtml), fragment)))
                return null;
            return List.of(
                    source.digest(),
                    template,
                    PublicBindingApplication.digest(templateHtml),
                    PublicBindingApplication.digest(outputHtml),
                    proof);
        } catch (IOException
                | IllegalArgumentException
                | IllegalStateException
                | ResponseStatusException unavailable) {
            return null;
        }
    }

    static String bodyFragmentTitle(Document document, String fragment) {
        if (document.selectFirst("base[href]") != null || !bodyAnchor(document, fragment))
            return null;
        var anchor = anchorElements(document, fragment).getFirst();
        var region = anchor.closest(BODY_CONTENT_REGION);
        if (region == null || anchor.closest(BODY_REFERENCE_EXCLUSIONS) != null) return null;
        for (var section = anchor;
                section != null && section != region;
                section = section.parent()) {
            var headings = section.select("h1,h2,h3,h4,h5,h6");
            if (headings.isEmpty()) continue;
            var heading = headings.getFirst();
            int depth = heading.parents().size();
            if (heading.closest(BODY_REFERENCE_EXCLUSIONS) != null
                    || headings.stream()
                            .skip(1)
                            .anyMatch(
                                    other -> {
                                        int otherDepth = other.parents().size();
                                        return otherDepth < depth
                                                || (otherDepth == depth
                                                        && other.normalName()
                                                                        .compareTo(
                                                                                heading
                                                                                        .normalName())
                                                                <= 0);
                                    })) return null;
            return heading.text();
        }
        return null;
    }

    private EntryReviews entryReviews(
            SiteModelNavigationService.Scope scope, SiteModelPlanService.Plan plan,
            SiteModelNavigationService.Navigation view, String sourcePath, Document document, List<Node> nodes) {
        if (nodes.isEmpty()) return new EntryReviews(Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        var byKey = nodes.stream().collect(java.util.stream.Collectors.toMap(Node::key, node -> node));
        var positions = new HashMap<String, List<Node>>();
        for (var node : nodes) {
            for (var observation : node.observations()) {
                if (sourcePath.equals(observation.path()))
                    recordPosition(document, positions, node, observation.selector(), observation.href());
            }
            if (sourcePath.equals(node.evidencePath()))
                recordPosition(document, positions, node, node.evidenceSelector(), node.sourceHref());
        }
        var sourcePaths = nodes.stream().map(Node::sourcePath).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        sourcePaths.add(sourcePath);
        var localKeys = new HashMap<String, String>();
        for (var local : SiteModelNavigationEvidence.sharedOnPage(sourcePaths, sourcePath, document)) {
            // 同一个头尾片段可以使用不同的相对路径，始终核对页面、片段及完整父入口。
            var node = byKey.get(local.key());
            if (node == null && (local.parentKey() == null || localKeys.containsKey(local.parentKey()))) {
                var parentKey = local.parentKey() == null ? null : localKeys.get(local.parentKey());
                var candidates = nodes.stream().filter(candidate -> Objects.equals(parentKey, candidate.parentKey())
                        && local.name().equals(candidate.name()) && local.sourcePath() != null
                        && local.sourcePath().equals(candidate.sourcePath())
                        && local.observations().stream().anyMatch(observed -> candidate.observations().stream()
                                .anyMatch(original -> observed.linkKind().equals(original.linkKind())
                                        && observed.linkIdentity().equals(original.linkIdentity())))).toList();
                if (candidates.size() == 1) node = candidates.getFirst();
            }
            if (node == null) continue;
            localKeys.put(local.key(), node.key());
            for (var observation : local.observations())
                recordPosition(document, positions, node, observation.selector(), observation.href());
        }
        var decisions = parentEntries.readForPublication(scope, plan.id(), plan.revision(), view).items().stream()
                .collect(java.util.stream.Collectors.toMap(ParentEntryDecisionService.Item::nodeKey, item -> item));
        var owners = new HashMap<String, String>();
        if ("CONFIRMED".equals(plan.status())) {
            var bodyBySource = plan.proposal().regions().stream()
                    .filter(region -> region.maintenance() == null && "SINGLE".equals(region.mode())
                            && region.records().size() == 1
                            && !SiteModelEvidenceService.publicRegion(region.sourcePath(), region.regionId()))
                    .collect(java.util.stream.Collectors.groupingBy(SiteModelPlanService.RegionUse::sourcePath));
            bodyBySource.forEach((path, regions) -> {
                if (regions.size() != 1) return;
                String key = regions.getFirst().modelKey();
                var candidates = nodes.stream().filter(node -> !node.drift() && node.menuId() != null
                        && node.kind() == SiteModelNavigationService.Kind.SINGLE && path.equals(node.sourcePath())
                        && key.equals(node.modelKey())
                        && plan.proposal().models().stream().anyMatch(model -> key.equals(model.key())
                                && "SINGLE".equals(model.kind())
                                && Long.valueOf(node.menuId()).equals(model.menuId()))).toList();
                if (candidates.size() == 1) owners.put(path, candidates.getFirst().key());
            });
        }
        return new EntryReviews(positions, byKey, decisions, owners, sourcePaths);
    }

    private static void recordPosition(Element document, Map<String, List<Node>> positions,
            Node node, String selector, String rawHref) {
        if (selector == null || rawHref == null) return;
        var matches = document.select(selector);
        if (matches.size() != 1
                || !matches.first().is("a[href]")
                || !rawHref.equals(matches.first().attr("href"))
                || !node.name().equals(matches.first().text())) return;
        var at = positions.computeIfAbsent(selector, key -> new ArrayList<>());
        if (!at.contains(node)) at.add(node);
    }

    private Anchor reviewEntry(
            SiteModelNavigationService.Scope scope, String sourcePath, Anchor anchor, EntryReviews reviews,
            Map<String, Boolean> checkedEntries, Set<String> appliedPublished, Map<String, Target> targets,
            Map<String, String> patterns, Map<String, PublishedTarget> published,
            Map<String, List<Publication>> publications) {
        String href = anchor.rawHref();
        if (!localSource(href) && !fragmentHref(href))
            return anchor.reviewed(null, "此入口为原包占位链接");
        if (hasRepeatedSeparator(href)) return null;
        var uri = URI.create(href);
        String rawFragment = uri.getRawFragment();
        String path = (uri.getPath().isEmpty() ? sourcePath
                : SiteModelEvidenceService.resolveLocal(sourcePath, href, reviews.originalPaths()));
        var matches = reviews.positions().getOrDefault(anchor.selector(), List.of());
        var entry = matches.size() == 1 ? matches.getFirst() : null;
        var decision = entry == null ? null : reviews.decisions().get(entry.key());
        boolean shared = path != null && reviews.nodes().values().stream()
                .filter(node -> path.equals(node.sourcePath()) && node.menuId() != null && !node.drift())
                .limit(2).count() == 2;
        boolean missing = entry != null && (entry.sourcePath() == null || path == null);
        if (decision == null && !shared && rawFragment == null && !missing) {
            // 对未归类但实际存在的原页保留原审核入口，缺页无法靠标题指定替代路线。
            if (path == null && !"/".equals(uri.getPath()) && !reviews.nodes().isEmpty()
                    && published.keySet().stream().noneMatch(key -> matchesPublishedSource(
                            sourcePath, anchor, published.get(key).sourcePath()))) {
                String original = originalPagePath(sourcePath, href);
                if (original == null || !isOriginalPage(scope, original))
                    return anchor.reviewed(null, "原包未找到该链接页面");
            }
            return null;
        }
        if (entry == null || entry.drift() || path == null || !path.equals(entry.sourcePath())
                || uri.getRawQuery() != null || !validEntryHierarchy(scope, entry, reviews, checkedEntries))
            return anchor.reviewed(null, "无法按原页位置、链接和栏目层级核对此入口");
        var sameSourcePublications = "BODY".equals(anchor.region())
                ? List.<Publication>of() : publications.getOrDefault(path, List.of());
        var destination = entryDestination(scope, entry, path, uri, reviews, checkedEntries,
                targets, appliedPublished, shared, sameSourcePublications);
        boolean manual = destination == null && shared && "HEADER".equals(anchor.region()) && rawFragment == null
                && decision == null
                && entry.kind() == SiteModelNavigationService.Kind.LIST
                && reviews.nodes().values().stream().noneMatch(child -> entry.key().equals(child.parentKey()));
        if (manual) {
            var menu = menus.selectPublicBindingMenu(scope.siteId(), entry.menuId().longValue());
            if (appliedPublished.contains(entry.key())) {
                var route = targets.get(entry.key());
                destination = publishedList(entry, route, sameSourcePublications);
                if (destination == null) destination = nativePublishedList(scope, entry, route, menu);
            } else if (menu != null && (menu.getListPageUrl() == null || menu.getListPageUrl().isBlank())
                    && !targets.containsKey(entry.key())) {
                var parent = reviews.nodes().get(entry.parentKey());
                if (parent != null && path.equals(parent.sourcePath()) && appliedPublished.contains(parent.key())
                        && !reviews.decisions().containsKey(parent.key())) {
                    destination = publishedList(parent, targets.get(parent.key()), sameSourcePublications);
                }
            }
        }
        if (destination == null)
            return anchor.reviewed(null, rawFragment == null ? "入口职责、正文归属或正式发布地址待核对"
                    : "原包或已发布正文没有可核对的唯一锚点");
        var target = destination.node();
        String key = entryKey(scope, sourcePath, anchor.selector(), entry.key(), href);
        targets.put(key, new Target(key, target.menuId(), target.name(), destination.url(), destination.kind()));
        patterns.put(key, patterns.get(target.key()));
        published.put(key, new PublishedTarget(path, PublicBindingApplication.digest(json.writeValueAsString(
                List.of(entry.key(), destination.proof(), target.key(), target.menuId(), destination.url()))), null));
        return manual ? new Anchor(anchor.selector(), href, anchor.label(), anchor.region(), null, List.of(key), null)
                : anchor.reviewed(key, null);
    }

    private String entryKey(SiteModelNavigationService.Scope scope, String sourcePath, String selector,
            String entry, String href) {
        return "entry-" + PublicBindingApplication.digest(
                json.writeValueAsString(List.of(scope, sourcePath, selector, entry, href)));
    }

    private Map<String, FragmentLink> addFragmentLinks(
            SiteModelNavigationService.Scope scope, String sourcePath, String sourceDigest,
            Document document, Map<String, Anchor> anchors, Map<String, Target> targets) {
        var previous = stored(scope, sourcePath);
        if (previous == null
                || !sourceDigest.equals(previous.sourceDigest())
                || document.select("li[data-src] > a[href]").stream()
                        .noneMatch(a -> fragmentTrigger(a) != null)) return Map.of();

        var headerByLabel = new HashMap<String, Change>();
        var ambiguousLabels = new HashSet<String>();
        for (var change : previous.result().changes()) {
            var anchor = anchors.get(change.selector());
            var target = targets.get(change.nodeKey());
            if (anchor == null || !"HEADER".equals(anchor.region()) || target == null
                    || target.kind() != TargetKind.CONTENT_LIST
                    || !anchor.eligibleTargetKeys().contains(change.nodeKey())
                    || !change.url().equals(target.url()) || !change.menuId().equals(target.menuId())) continue;
            if (headerByLabel.putIfAbsent(anchor.label(), change) != null) ambiguousLabels.add(anchor.label());
        }
        if (headerByLabel.isEmpty()) return Map.of();

        var inspection = new TemplateFragmentInspectionService(packages)
                .inspect(scope.code(), scope.packageVersion(), sourcePath);
        var fragments = new HashMap<String, TemplateFragmentInspectionService.Candidate>();
        for (var candidate : inspection.candidates()) {
            if ("REVIEW".equals(candidate.status()) && "data-src".equals(candidate.attribute()))
                fragments.put(candidate.triggerSelector(), candidate);
        }

        var links = new HashMap<String, FragmentLink>();
        var staticFragments = new HashMap<String, Boolean>();
        for (var anchor : document.select("li[data-src] > a[href]")) {
            var trigger = fragmentTrigger(anchor);
            var header = headerByLabel.get(anchor.text());
            if (trigger == null || header == null || ambiguousLabels.contains(anchor.text())) continue;
            var group = trigger.parent();
            if (!group.children().stream()
                            .allMatch(
                                    item -> {
                                        var child = item.childrenSize() == 1 ? item.child(0) : null;
                                        var proof = fragments.get(item.cssSelector());
                                        return fragmentTrigger(child) == item
                                                && proof != null
                                                && proof.rawTarget().equals(item.attr("data-src"))
                                                && staticFragments.computeIfAbsent(
                                                        proof.targetPath(),
                                                        path -> {
                                                            var fragment =
                                                                    packages.readStaticPage(
                                                                            scope.code(),
                                                                            scope.packageVersion(),
                                                                            path);
                                                            return proof.targetDigest()
                                                                            .equals(
                                                                                    fragment
                                                                                            .digest())
                                                                    && !FULL_PAGE_TAG
                                                                            .matcher(
                                                                                    fragment.html())
                                                                            .find()
                                                                    && Jsoup.parseBodyFragment(
                                                                                            fragment
                                                                                                    .html())
                                                                                    .selectFirst(
                                                                                            "script,form,iframe")
                                                                            == null;
                                                        });
                                    })
                    || group.children().stream()
                                    .filter(item -> item.text().equals(anchor.text()))
                                    .count()
                            != 1) continue;

            var selector = anchor.cssSelector();
            var candidate = fragments.get(trigger.cssSelector());
            var mobile = fragmentMobileHeading(trigger, anchor.text());
            if (anchors.size() >= MAX_ANCHORS || selector.length() > 1024)
                throw new IllegalArgumentException("公共导航链接数量或位置长度超限");
            anchors.put(selector, new Anchor(selector, STATIC_TAB_HREF, anchor.text(), "BODY",
                    header.nodeKey(), List.of(header.nodeKey()), null));
            links.put(selector, new FragmentLink(trigger.cssSelector(), candidate.rawTarget(),
                    mobile == null ? "" : mobile.cssSelector(), header.selector(),
                    candidate.targetPath(), candidate.targetDigest()));
        }
        return links;
    }

    private static Element fragmentTrigger(Element anchor) {
        if (anchor == null
                || !anchor.is("a[href]")
                || !STATIC_TAB_HREF.equals(anchor.attr("href"))
                || anchor.text().isBlank()
                || anchor.childrenSize() != 0
                || anchor.closest("main,[role=main],.main") == null
                || anchor.closest(
                                LANDING_SHARED_REGION
                                        + ",article,aside,form,[hidden],[aria-hidden=true]")
                        != null) return null;
        var trigger = anchor.parent();
        var group = trigger == null ? null : trigger.parent();
        if (group == null || !trigger.is("li[data-src]") || trigger.childrenSize() != 1
                || !group.is("ul,ol") || group.childrenSize() < 2 || group.childrenSize() > 32
                || java.util.stream.Stream.of(anchor, trigger, group).anyMatch(element ->
                        element.attributes().asList().stream().anyMatch(attr ->
                                attr.getKey().startsWith("on") || attr.getKey().startsWith("th:")
                                        || attr.getKey().startsWith("data-th-"))))
            return null;
        return trigger;
    }

    private static Element fragmentMobileHeading(Element trigger, String label) {
        var parent = trigger.parent().parent();
        if (parent == null || !parent.is("dd") || parent.childrenSize() != 1
                || parent.parent() == null || !parent.parent().is("dl")) return null;
        var heading = parent.previousElementSibling();
        return heading != null
                        && heading.is("dt")
                        && !heading.text().equals(label)
                        && heading.selectFirst("a,button,[" + MOBILE_FRAGMENT_LINK + "]") == null
                ? heading
                : null;
    }

    private static Element requireFragmentTrigger(Document document, Element anchor, String selector) {
        var triggers = document.select(selector);
        if (triggers.size() != 1
                || triggers.first() != anchor.parent()
                || !triggers.first().is("li")
                || triggers.first().childrenSize() != 1)
            throw new IllegalArgumentException("编译改变了正文分类的位置：" + anchor.text());
        return triggers.first();
    }

    private static Element requireFragmentMobileHeading(Document document, Element trigger, String selector) {
        var headings = document.select(selector);
        var wrapper = trigger.parent().parent();
        if (headings.size() != 1
                || wrapper == null
                || !wrapper.is("dd")
                || headings.first() != wrapper.previousElementSibling()
                || !headings.first().is("dt"))
            throw new IllegalArgumentException("编译改变了正文分类的移动展示位置");
        return headings.first();
    }

    private static Element mobileFragmentLink(String selector, String label, String url) {
        return new Element("a").attr(MOBILE_FRAGMENT_LINK, selector).attr("href", url)
                .attr("style", "display:inline-block;margin-inline-start:.75em").text(label);
    }

    private static void renderFragmentLink(Document document, Element anchor, FragmentLink link, Change change) {
        var trigger = requireFragmentTrigger(document, anchor, link.triggerSelector());
        if (!link.rawTarget().equals(trigger.attr("data-src")))
            throw new IllegalArgumentException("编译改变了正文分类的片段路径：" + anchor.text());
        trigger.removeAttr("data-src");
        if (!link.mobileSelector().isEmpty()) {
            var heading = requireFragmentMobileHeading(document, trigger, link.mobileSelector());
            if (heading.children().stream().anyMatch(child -> change.selector().equals(child.attr(MOBILE_FRAGMENT_LINK))))
                throw new IllegalArgumentException("正文分类的移动展示位置已有链接");
            heading.appendChild(mobileFragmentLink(change.selector(), anchor.text(), change.url()));
        }
    }

    private static String originalPagePath(String sourcePath, String href) {
        try {
            var resolved = new URI(null, null, "/" + sourcePath, null).resolve(href).normalize();
            return WorkbenchStaticLinkCheckService.safeUrlPath(resolved)
                            && resolved.getPath().startsWith("/")
                    ? resolved.getPath().substring(1)
                    : null;
        } catch (IllegalArgumentException | java.net.URISyntaxException ignored) {
            return null;
        }
    }

    private boolean isOriginalPage(SiteModelNavigationService.Scope scope, String path) {
        if (!StaticPageSource.supports(path)) return false;
        try {
            packages.readStaticPage(scope.code(), scope.packageVersion(), path);
            return true;
        } catch (IllegalArgumentException | ResponseStatusException unavailable) {
            return false;
        }
    }

    private boolean validEntryHierarchy(
            SiteModelNavigationService.Scope scope, Node entry, EntryReviews reviews, Map<String, Boolean> checked) {
        var visited = new HashSet<String>();
        for (Node node = entry; node != null && visited.add(node.key()); node = reviews.nodes().get(node.parentKey())) {
            var current = node;
            if (!checked.computeIfAbsent(node.key(), key -> current.menuId() != null
                    && matches(scope, menus.selectPublicBindingMenu(scope.siteId(), current.menuId().longValue()),
                            current, List.copyOf(reviews.nodes().values())))) return false;
            if (node.parentKey() == null) return true;
        }
        return false;
    }

    private EntryDestination entryDestination(
            SiteModelNavigationService.Scope scope, Node entry, String path, URI uri, EntryReviews reviews,
            Map<String, Boolean> checkedEntries, Map<String, Target> targets,
            Set<String> appliedPublished, boolean shared, List<Publication> publications) {
        var checked = new HashSet<String>();
        var proof = new ArrayList<Object>();
        var target = entry;
        while (checked.add(target.key())) {
            var decision = reviews.decisions().get(target.key());
            if (decision == null) break;
            if (!decision.valid() || decision.role() == ParentEntryDecisionService.Role.DIRECTORY) return null;
            proof.add(decision);
            if (decision.role() == ParentEntryDecisionService.Role.INDEPENDENT_OVERVIEW) break;
            target = reviews.nodes().get(decision.targetNodeKey());
            if (target == null || checked.contains(target.key())
                    || !validEntryHierarchy(scope, target, reviews, checkedEntries)) return null;
        }
        var route = targets.get(target.key());
        if (uri.getRawFragment() == null) {
            if (route == null || !appliedPublished.contains(target.key())) return null;
            if (shared && path.equals(target.sourcePath())
                    && !target.key().equals(reviews.sourceOwners().get(path))
                    && !Boolean.TRUE.equals(target.independentLanding())) {
                if (target.kind() == SiteModelNavigationService.Kind.LIST) {
                    var list = publishedList(target, route, publications);
                    if (list != null) {
                        proof.addAll(list.proof());
                        return new EntryDestination(list.node(), list.url(), list.kind(), proof);
                    }
                }
                if (!entry.key().equals(target.key())
                        && target.kind() == SiteModelNavigationService.Kind.SINGLE) {
                    var applicationProof = publishedSingleProof(scope, target, path);
                    if (applicationProof != null) {
                        proof.add(applicationProof);
                        return new EntryDestination(target, route.url(), TargetKind.PARENT_ENTRY, proof);
                    }
                }
                if (!proof.isEmpty() || entry.kind() != SiteModelNavigationService.Kind.LIST
                        || reviews.nodes().values().stream().noneMatch(child -> entry.key().equals(child.parentKey())
                                && path.equals(child.sourcePath()) && child.menuId() != null && !child.drift()))
                    return null;
                return publishedList(entry, route, publications);
            }
            return new EntryDestination(target, route.url(), TargetKind.PARENT_ENTRY, proof);
        }
        if (uri.getRawFragment().isBlank() || !entry.key().equals(target.key())) return null;
        String ownerKey = shared ? reviews.sourceOwners().get(path) : target.key();
        if (ownerKey == null || !ownerKey.equals(target.key()) && !ownerKey.equals(target.parentKey())) return null;
        var owner = reviews.nodes().get(ownerKey);
        route = targets.get(ownerKey);
        if (owner == null || route == null || !appliedPublished.contains(ownerKey)) return null;
        var menu = menus.selectPublicBindingMenu(scope.siteId(), owner.menuId().longValue());
        if (!matches(scope, menu, owner, List.copyOf(reviews.nodes().values()))) return null;
        var source = packages.readStaticPage(scope.code(), scope.packageVersion(), path);
        String fragment = uri.getFragment();
        if (!bodyAnchor(Jsoup.parse(source.html()), fragment)
                || !hasTemplateAnchor(scope.code(), menu.getListPageTemplate(), fragment, true)) return null;
        proof.addAll(List.of(source.digest(), fragment));
        return new EntryDestination(
                owner, route.url() + "#" + uri.getRawFragment(), TargetKind.FRAGMENT, proof);
    }

    private static EntryDestination publishedList(Node node, Target route, List<Publication> publications) {
        var lists = publications.stream()
                .filter(publication -> publication.target().kind() == TargetKind.CONTENT_LIST
                        && node.name().equals(publication.target().label())).toList();
        if (lists.size() != 1) return null;
        var list = lists.getFirst();
        if (!node.menuId().equals(list.target().menuId()) || !route.url().equals(list.target().url())) return null;
        return new EntryDestination(node, route.url(), TargetKind.CONTENT_LIST, List.of(list.digest()));
    }

    /** 共享原页的父栏目可沿用已审核跳转，目标必须持有同一来源的当前单页应用凭据。 */
    private List<String> publishedSingleProof(SiteModelNavigationService.Scope scope, Node node, String path) {
        if (fieldPlans == null || publicApplicationRoot == null) return null;
        var menu = menus.selectPublicBindingMenu(scope.siteId(), node.menuId().longValue());
        if (menu == null || !Integer.valueOf(0).equals(menu.getPageType())
                || !Integer.valueOf(3).equals(menu.getStatus())
                || !PublicBindingApplication.managed(menu.getListPageTemplate())) return null;
        String template = menu.getListPageTemplate();
        try {
            var application = PublicBindingApplication.atPath(Path.of(publicApplicationRoot), scope.code(), template);
            if (application == null || application.pageType() != 0 || application.queryId() != null
                    || application.routeMenuId() != null || application.navigationLanding() != null
                    || !Objects.equals(application.menuId(), node.menuId().longValue())
                    || !Objects.equals(application.siteId(), scope.siteId())
                    || !Objects.equals(application.packageVersion(), scope.packageVersion())
                    || !Objects.equals(application.routeUrl(), menu.getListPageUrl())
                    || !Objects.equals(application.fieldConfigDigest(), ContentFieldConfigDigest.of(menu))
                    || !templateDigestMatches(scope.code(), template, application.templateDigest())) return null;
            var plan = fieldPlans.readForWithdrawal(scope.siteId(), scope.code(), scope.packageVersion(), application.planId());
            if (plan.pageType() != 0 || !Objects.equals(plan.menuId(), application.menuId())
                    || !path.equals(plan.sourcePath())
                    || !plan.digest().equals(packages.readStaticPage(scope.code(), scope.packageVersion(), path).digest())) return null;
            return List.of(plan.digest(), application.fieldConfigDigest());
        } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException invalidProof) {
            return null;
        }
    }

    private EntryDestination nativePublishedList(
            SiteModelNavigationService.Scope scope, Node node, Target route, OneshotEntity menu) {
        if (workspace == null || menu == null || !node.name().equals(menu.getClassName())) return null;
        String template = menu.getListPageTemplate();
        if (PublicBindingApplication.managed(template) || !hasTemplate(scope.code(), template)) return null;
        var url = URI.create(route.url());
        if (!route.url().startsWith("/") || !WorkbenchStaticLinkCheckService.safeUrlPath(url)
                || url.getRawFragment() != null || url.getRawQuery() != null) return null;
        try {
            var root = Path.of(storage.siteRootPath(htmlConfig.getSaveRoot(), scope.code())).toAbsolutePath().normalize();
            var output = WorkbenchStaticLinkCheckService.localFile(root, url.getPath());
            var templateFile = templates.resolveTemplateDir(scope.code()).resolve(template).normalize();
            if (output == null || Files.size(output) > 2 * 1024 * 1024 || Files.size(templateFile) > 2 * 1024 * 1024
                    || Files.getLastModifiedTime(templateFile).compareTo(Files.getLastModifiedTime(output)) > 0)
                return null;
            String proof = StaticRenderProof.of(scope.siteId(), node.menuId(), false, template);
            if (!proof.equals(workspace.staticArtifactProof(scope.siteId(), scope.code(), output, Files.readString(output))))
                return null;
            return new EntryDestination(node, route.url(), TargetKind.CONTENT_LIST,
                    List.of(proof, PublicBindingApplication.digest(Files.readString(templateFile))));
        } catch (IOException | IllegalArgumentException | IllegalStateException | ResponseStatusException unavailable) {
            return null;
        }
    }

    private Map<String, List<Publication>> addPublishedTargets(
            SiteModelNavigationService.Scope scope,
            String sourcePath,
            Map<String, Anchor> anchors,
            List<OneshotEntity> items,
            Map<String, Target> targets,
            Map<String, String> patterns,
            Map<String, PublishedTarget> published) {
        var itemsById = new HashMap<Integer, OneshotEntity>();
        var publications = new ArrayList<Publication>();
        items.forEach(item -> itemsById.put(item.getId(), item));
        for (var item : items) {
            if (item.getId() == null || Objects.equals(item.getId(), scope.rootMenuId())
                    || !Boolean.TRUE.equals(item.getHasFrontChannel())
                    || Boolean.TRUE.equals(item.getIsDeleted())
                    || !Objects.equals(item.getSiteProjectId(), scope.siteId())
                    || !Objects.equals(item.getSiteRootId(), scope.rootMenuId())
                    || item.getClassName() == null
                    || item.getListPageTemplate() == null) continue;
            var ancestry = new ArrayList<Integer>();
            for (var parent = itemsById.get(item.getParentId());
                    parent != null && !ancestry.contains(parent.getId())
                            && !Boolean.TRUE.equals(parent.getIsDeleted())
                            && Objects.equals(parent.getSiteProjectId(), scope.siteId())
                            && Objects.equals(parent.getSiteRootId(), scope.rootMenuId());
                    parent = itemsById.get(parent.getParentId())) {
                ancestry.add(parent.getId());
                if (Objects.equals(parent.getId(), scope.rootMenuId())) break;
            }
            if (ancestry.isEmpty() || !Objects.equals(ancestry.getLast(), scope.rootMenuId())) continue;
            String url = route(scope, item, items);
            if (url == null) continue;
            String template = item.getListPageTemplate();
            TargetKind kind;
            String targetPath;
            Object proof;
            try {
                if (Integer.valueOf(1).equals(item.getPageType())
                        && PublicBindingApplication.managed(template)
                        && publicApplicationRoot != null) {
                    var menu = menus.selectPublicBindingMenu(scope.siteId(), item.getId().longValue());
                    if (menu == null || !Objects.equals(menu.getListPageTemplate(), template)) continue;
                    var application = listApplication(scope, menu);
                    if (application == null || application.routeMenuId() != null) continue;
                    var query = queryBindings.read(
                            scope.siteId(), scope.code(), scope.packageVersion(), application.queryId());
                    if (!query.completeList() || !Objects.equals(query.menuId(), application.menuId())
                            || !Objects.equals(query.sourcePlanId(), application.planId())
                            || query.firstUrl() == null
                            || !url.equals(query.firstUrl().split("\\?", 2)[0])) continue;
                    kind = TargetKind.CONTENT_LIST;
                    targetPath = query.sourcePath();
                    proof = List.of(application.key(), application.templateDigest(), query.id(), query.digest());
                } else {
                    var form = SiteWorkbenchHandoffService.FORM_TEMPLATE.matcher(template);
                    if (!form.matches() || !FormActionBinding.single(item, scope.siteId())
                            || !Integer.valueOf(3).equals(item.getStatus())
                            || !form.group(1).equals(item.getId().toString())
                            || !url.equals(FormActionBinding.publicRoute(item, scope.siteId()))
                            || !templateDigestMatches(scope.code(), template, form.group(4))) continue;
                    var receipt = nativeForms.read(
                            scope.siteId(), scope.code(), scope.packageVersion(), Integer.valueOf(form.group(3)));
                    if (!Objects.equals(receipt.menuId(), item.getId().longValue())
                            || !form.group(2).equals(receipt.sourceDigest())) continue;
                    var definition = publicForms.publicDefinition(scope.siteId(), receipt.categoryId());
                    if (!Boolean.TRUE.equals(definition.get("captchaRequired"))) continue;
                    kind = TargetKind.NATIVE_FORM;
                    targetPath = receipt.sourcePath();
                    proof = List.of(receipt, form.group(4));
                }
            } catch (IllegalArgumentException | IllegalStateException | FormApiException | ResponseStatusException
                    | tools.jackson.core.JacksonException invalidReceipt) {
                continue;
            }
            if (targetPath == null || anchors.values().stream().noneMatch(anchor ->
                    matchesPublishedSource(sourcePath, anchor, targetPath))) continue;
            String key = "published-menu-" + item.getId();
            String digest = PublicBindingApplication.digest(json.writeValueAsString(
                    List.of(kind, item.getId(), ancestry, item.getClassName(), item.getListPageUrl(),
                            template, targetPath, proof)));
            publications.add(new Publication(new Target(key, item.getId(), item.getClassName(), url, kind),
                    item.getListPageUrl(), targetPath, digest));
        }
        var publishedBySource = publications.stream()
                .collect(java.util.stream.Collectors.groupingBy(Publication::sourcePath));
        var aliases = new HashMap<String, String>();
        if (publications.stream().anyMatch(publication ->
                publishedBySource.get(publication.sourcePath()).size() == 1
                && anchors.values().stream().anyMatch(anchor -> !anchor.label().equals(publication.target().label())
                        && matchesPublishedSource(sourcePath, anchor, publication.sourcePath())))) {
            try {
                var sourceEntries = sourceNavigation.read(scope.code(), scope.packageVersion()).stream()
                        .filter(entry -> entry.sourcePath() != null)
                        .collect(java.util.stream.Collectors.groupingBy(SiteModelNavigationEvidence.Entry::sourcePath));
                sourceEntries.forEach((path, entries) -> {
                    if (entries.size() == 1) aliases.put(path, entries.getFirst().name());
                });
            } catch (IllegalArgumentException | ResponseStatusException unavailableEvidence) {
                // Keep the exact-name choices if complete package navigation evidence is unavailable.
            }
        }
        for (var publication : publications) {
            var target = publication.target();
            var path = publication.sourcePath();
            String alias = publishedBySource.get(path).size() == 1 ? aliases.get(path) : null;
            if (anchors.values().stream().noneMatch(anchor ->
                    matchesPublishedSource(sourcePath, anchor, path)
                            && (anchor.label().equals(target.label()) || anchor.label().equals(alias)))) continue;
            targets.put(target.nodeKey(), target);
            patterns.put(target.nodeKey(), publication.routePattern());
            published.put(target.nodeKey(), new PublishedTarget(path, publication.digest(), alias));
        }
        return publishedBySource;
    }

    private static boolean matchesPublishedSource(String sourcePath, Anchor anchor, String targetPath) {
        if (!localSource(anchor.rawHref())) return false;
        if (hasRepeatedSeparator(anchor.rawHref())) {
            return anchor.repair() != null && targetPath.equals(anchor.repair().sourcePath());
        }
        return !"BODY".equals(anchor.region())
                && !anchor.label().isBlank()
                && URI.create(anchor.rawHref()).getRawQuery() == null
                && targetPath.equals(SiteModelEvidenceService.resolveLocal(
                        sourcePath, anchor.rawHref(), Set.of(targetPath)));
    }

    private static List<String> repairTargetKeys(
            Anchor anchor,
            Map<String, List<Node>> appliedSources,
            Set<String> appliedPublished,
            Map<String, List<Publication>> publications,
            Map<String, PublishedTarget> published,
            Map<String, Target> targets) {
        if (anchor.repair() == null) return List.of();
        String path = anchor.repair().sourcePath();
        var menuIds = new HashSet<Integer>();
        var choices = new ArrayList<String>();
        for (var node : appliedSources.getOrDefault(path, List.of())) {
            if (!appliedPublished.contains(node.key())) continue;
            menuIds.add(node.menuId());
            if ("BODY".equals(anchor.region()) || anchor.label().equals(node.name())) {
                choices.add(node.key());
            }
        }
        for (var publication : publications.getOrDefault(path, List.of())) {
            var target = publication.target();
            menuIds.add(target.menuId());
            var confirmed = published.get(target.nodeKey());
            if (confirmed != null && (anchor.label().equals(targets.get(target.nodeKey()).label())
                    || anchor.label().equals(confirmed.alternateSourceLabel()))) {
                choices.addFirst(target.nodeKey());
            }
        }
        return menuIds.size() == 1 && !choices.isEmpty() ? List.of(choices.getFirst()) : List.of();
    }

    private static boolean hasRepeatedSeparator(String href) {
        return URI.create(href).getRawPath().contains("//");
    }

    static String repeatedSeparatorTarget(String sourcePath, String href) {
        if (!localSource(href) || !hasRepeatedSeparator(href)
                || URI.create(href).getRawQuery() != null) return null;
        URI base;
        try {
            base = new URI("https", "original-package.invalid", "/" + sourcePath, null);
        } catch (java.net.URISyntaxException invalid) {
            return null;
        }
        var target = WorkbenchStaticLinkCheckService.resolve(base, href);
        if (target == null || !target.getRawPath().contains("//")) return null;
        var normalized = base.resolve(target.getRawPath().replaceAll("/{2,}", "/"));
        if (!WorkbenchStaticLinkCheckService.safeUrlPath(normalized)) return null;
        String path = normalized.getPath().substring(1);
        return StaticPageSource.supports(path) ? path : null;
    }

    private static boolean restoringSelf(
            SiteModelNavigationService.Scope scope,
            Node node,
            String sourcePath,
            Long applyingMenuId,
            java.util.Set<String> previouslyApproved) {
        return applyingMenuId != null
                && previouslyApproved.contains(node.key())
                && (applyingMenuId.equals(node.menuId().longValue())
                        || Objects.equals(node.menuId(), scope.rootMenuId())
                                && Objects.equals(node.sourcePath(), sourcePath));
    }

    private static Anchor suggest(
            String sourcePath, Anchor anchor, List<Node> nodes, Map<String, Target> targets) {
        if (anchor.label().isBlank() || "BODY".equals(anchor.region())) return anchor;
        var candidates =
                nodes.stream()
                        .filter(
                                node ->
                                        targets.containsKey(node.key())
                                                && anchor.label().equals(node.name())
                                                && node.sourcePath() != null)
                        .toList();
        var matches =
                candidates.stream()
                        .filter(
                                node ->
                                        Objects.equals(
                                                        node.sourcePath(),
                                                        SiteModelEvidenceService.resolveLocal(
                                                                sourcePath,
                                                                anchor.rawHref(),
                                                                java.util.Set.of(
                                                                        node.sourcePath())))
                                                || anchor.rawHref().equals(node.sourceHref())
                                                || node.observations().stream()
                                                        .anyMatch(
                                                                item ->
                                                                        anchor.region()
                                                                                        .equals(
                                                                                                item
                                                                                                        .origin())
                                                                                && anchor.rawHref()
                                                                                        .equals(
                                                                                                item
                                                                                                        .href())
                                                                                && Objects.equals(
                                                                                        node
                                                                                                .sourcePath(),
                                                                                        item
                                                                                                .targetPath())))
                        .toList();
        return matches.size() != 1
                ? anchor
                : new Anchor(
                        anchor.selector(),
                        anchor.rawHref(),
                        anchor.label(),
                        anchor.region(),
                        matches.getFirst().key());
    }

    private static boolean matches(
            SiteModelNavigationService.Scope scope,
            OneshotEntity item,
            Node node,
            List<Node> nodes) {
        if (item == null
                || node.drift()
                || !Objects.equals(item.getId(), node.menuId())
                || !Objects.equals(item.getSiteProjectId(), scope.siteId())
                || !Objects.equals(item.getSiteRootId(), scope.rootMenuId())) return false;
        if (Objects.equals(item.getId(), scope.rootMenuId()))
            return node.parentKey() == null
                    && node.kind() == SiteModelNavigationService.Kind.SINGLE;
        Integer parent = scope.rootMenuId();
        if (node.parentKey() != null) {
            parent =
                    nodes.stream()
                            .filter(candidate -> candidate.key().equals(node.parentKey()))
                            .map(Node::menuId)
                            .findFirst()
                            .orElse(null);
        }
        String marker =
                node.reuseMenuId() == null
                        ? "site_nav_" + node.key()
                        : SiteModelStructureService.modelMarker(node.modelKey());
        return parent != null
                && Objects.equals(item.getParentId(), parent)
                && Objects.equals(item.getSort(), node.order())
                && Objects.equals(
                        item.getPageType(),
                        node.kind() == SiteModelNavigationService.Kind.LIST ? 1 : 0)
                && Objects.equals(item.getIdentifies(), marker);
    }

    private boolean hasTemplate(String code, String template) {
        if (template == null || template.isBlank()) return false;
        try {
            Path root = templates.resolveTemplateDir(code);
            Path path = root.resolve(template).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(root)) return false;
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    private boolean templateDigestMatches(String code, String template, String digest) {
        if (!hasTemplate(code, template)) return false;
        try {
            var file = templates.resolveTemplateDir(code).resolve(template).normalize();
            return Files.size(file) <= 2 * 1024 * 1024
                    && Objects.equals(digest, PublicBindingApplication.digest(Files.readString(file)));
        } catch (IOException error) {
            return false;
        }
    }

    private boolean hasTemplateAnchor(String code, String template, String anchor) {
        return hasTemplateAnchor(code, template, anchor, false);
    }

    private boolean hasTemplateAnchor(String code, String template, String anchor, boolean bodyOnly) {
        if (!hasTemplate(code, template)) return false;
        try {
            var path = templates.resolveTemplateDir(code).resolve(template).normalize();
            if (Files.size(path) > 2 * 1024 * 1024) return false;
            var doc = Jsoup.parse(Files.readString(path));
            return bodyOnly ? bodyAnchor(doc, anchor) : stableAnchor(doc, anchor);
        } catch (IOException error) {
            return false;
        }
    }

    static boolean bodyAnchor(Element root, String name) {
        return stableAnchor(root, name) && anchorElements(root, name).getFirst()
                .closest(LANDING_SHARED_REGION + ",[hidden],[aria-hidden=true]") == null;
    }

    private static List<Element> anchorElements(Element root, String name) {
        return root.select("[id],a[name]").stream()
                .filter(
                        el ->
                                name.equals(el.id())
                                        || el.is("a[name]") && name.equals(el.attr("name")))
                .toList();
    }

    static boolean stableAnchor(Element root, String name) {
        var matches = anchorElements(root, name);
        if (matches.size() != 1) return false;
        var target = matches.getFirst();
        for (var el = target; el != null; el = el.parent()) {
            for (var attr : el.attributes()) {
                var key = attr.getKey().replaceFirst("^(?:data-th-|th:)", "");
                if (key.equals(attr.getKey())) continue;
                if (Set.of("each", "if", "unless", "case", "remove", "replace", "include")
                        .contains(key)) return false;
                if (el != target && Set.of("text", "utext", "insert").contains(key)) return false;
                if (el == target
                        && (Set.of("id", "name").contains(key)
                                || key.startsWith("attr")
                                        && attr.getValue().matches("(?is).*\\b(?:id|name)\\s*=.*")))
                    return false;
            }
        }
        return true;
    }

    private LandingTargetState leafListState(SiteModelNavigationService.Scope scope, OneshotEntity menu, boolean verifyQuery) {
        try {
            var application = listApplication(scope, menu);
            if (application == null) return LandingTargetState.INDEPENDENT_SOURCE_REQUIRED;
            if (verifyQuery) {
                try {
                    var query = queryBindings.read(scope.siteId(), scope.code(), scope.packageVersion(), application.queryId());
                    if (!query.completeList() || !Objects.equals(query.menuId(), application.menuId())
                            || !Objects.equals(query.sourcePlanId(), application.planId()))
                        return LandingTargetState.QUERY_REQUIRED;
                } catch (IllegalArgumentException | ResponseStatusException | tools.jackson.core.JacksonException error) {
                    return LandingTargetState.QUERY_REQUIRED;
                }
            }
            return LandingTargetState.READY;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException error) {
            return LandingTargetState.INDEPENDENT_SOURCE_REQUIRED;
        }
    }

    private PublicBindingApplication listApplication(SiteModelNavigationService.Scope scope, OneshotEntity menu) {
        String template = menu.getListPageTemplate();
        if (publicApplicationRoot == null || !PublicBindingApplication.managed(template)) return null;
        var application = PublicBindingApplication.atPath(Path.of(publicApplicationRoot), scope.code(), template);
        return application != null
                        && application.queryId() != null
                        && application.pageType() == 1
                        && Objects.equals(application.siteId(), scope.siteId())
                        && Objects.equals(application.packageVersion(), scope.packageVersion())
                        && Objects.equals(application.menuId(), menu.getId().longValue())
                        && Objects.equals(application.routeUrl(), menu.getListPageUrl())
                        && Objects.equals(application.fieldConfigDigest(), ContentFieldConfigDigest.of(menu))
                        && templateDigestMatches(scope.code(), template, application.templateDigest())
                ? application : null;
    }

    String routeForSourceLink(
            SiteModelNavigationService.Scope scope, String planId, String sourcePath, String href) {
        try {
            var uri = URI.create(href.strip());
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) return "";
        } catch (IllegalArgumentException error) {
            return "";
        }
        SiteModelNavigationService.Navigation view;
        try {
            view = navigation.get(scope, planId, () -> {});
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 404 || error.getStatusCode().value() == 409)
                return "";
            throw error;
        }
        if (view.status() != SiteModelNavigationService.Status.APPLIED) return "";
        var targets =
                view.nodes().stream()
                        .filter(
                                node ->
                                        node.sourcePath() != null
                                                && node.menuId() != null
                                                && node.kind()
                                                        != SiteModelNavigationService.Kind
                                                                .DIRECTORY)
                        .toList();
        var paths =
                targets.stream().map(Node::sourcePath).collect(java.util.stream.Collectors.toSet());
        var target = SiteModelEvidenceService.resolveLocal(sourcePath, href, paths);
        if (target == null) return "";
        var matches = targets.stream().filter(node -> target.equals(node.sourcePath())).toList();
        if (matches.size() != 1) return landingRoute(scope, planId, target, matches, view.nodes());
        var node = matches.getFirst();
        var menu = menus.selectPublicBindingMenu(scope.siteId(), node.menuId().longValue());
        if (!matches(scope, menu, node, view.nodes())
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || Objects.equals(menu.getPageType(), 0) && !Objects.equals(menu.getStatus(), 3)
                || menu.getListPageUrl() == null
                || menu.getListPageUrl().isBlank()
                || !hasTemplate(scope.code(), menu.getListPageTemplate())) return "";
        return Objects.toString(
                route(
                        scope,
                        menu,
                        Optional.ofNullable(menus.selectListBySiteProjectId(scope.siteId()))
                                .orElse(List.of())),
                "");
    }

    private String landingRoute(
            SiteModelNavigationService.Scope scope,
            String planId,
            String sourcePath,
            List<Node> candidates,
            List<Node> nodes) {
        if (publicApplicationRoot == null) return "";
        var destinations = new ArrayList<String>();
        var all =
                Optional.ofNullable(menus.selectListBySiteProjectId(scope.siteId()))
                        .orElse(List.of());
        for (var node : candidates) {
            var menu = menus.selectPublicBindingMenu(scope.siteId(), node.menuId().longValue());
            if (!matches(scope, menu, node, nodes)
                    || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                    || !PublicBindingApplication.managed(menu.getListPageTemplate())) continue;
            PublicBindingApplication application;
            try {
                application =
                        PublicBindingApplication.read(
                                Path.of(publicApplicationRoot),
                                scope.code(),
                                PublicBindingApplication.keyFrom(menu.getListPageTemplate()));
            } catch (IllegalArgumentException
                    | tools.jackson.core.JacksonException invalidReceipt) {
                continue;
            }
            if (application == null || application.navigationLanding() == null) continue;
            var receipt = application.navigationLanding();
            var preview = receipt.preview();
            if (!Objects.equals(application.siteId(), scope.siteId())
                    || !Objects.equals(application.menuId(), node.menuId().longValue())
                    || !Objects.equals(application.packageVersion(), scope.packageVersion())
                    || !Objects.equals(
                            application.fieldConfigDigest(), ContentFieldConfigDigest.of(menu))
                    || !Objects.equals(application.templatePath(), menu.getListPageTemplate())
                    || !Objects.equals(application.routeUrl(), menu.getListPageUrl())
                    || !Objects.equals(receipt.planId(), planId)
                    || !Objects.equals(preview.nodeKey(), node.key())
                    || !Objects.equals(preview.sourcePath(), sourcePath)
                    || !hasTemplate(scope.code(), application.templatePath())) continue;
            try {
                var html =
                        Files.readString(
                                templates
                                        .resolveTemplateDir(scope.code())
                                        .resolve(application.templatePath()));
                if (!Objects.equals(
                        application.templateDigest(), PublicBindingApplication.digest(html)))
                    continue;
            } catch (IOException error) {
                throw new IllegalStateException("概览公开模板不可读", error);
            }
            try {
                if (!preview.sameSources(
                        previewLanding(
                                scope,
                                planId,
                                sourcePath,
                                node.key(),
                                preview.containerSelector()))) continue;
            } catch (IllegalArgumentException error) {
                continue;
            }
            String url = route(scope, menu, all);
            if (url != null) destinations.add(url);
        }
        return destinations.size() == 1 ? destinations.getFirst() : "";
    }

    private String route(
            SiteModelNavigationService.Scope scope, OneshotEntity item, List<OneshotEntity> all) {
        String url;
        try {
            if (Objects.equals(item.getId(), scope.rootMenuId())) url = "/";
            else {
                var first = links.getListUrl(item, 1);
                url = PublicListModelProjection.localUrl(first).split("\\?", 2)[0];
                if (url.equals("/") || PublicBindingRootRoutes.indexAlias(url)) return null;
            }
        } catch (IllegalArgumentException error) {
            return null;
        }
        String path = URI.create(url).getPath().replaceAll("^/|/$", "");
        if (routes.getHandlers(scope.code()).stream().anyMatch(handler -> handler.matches(path)))
            return null;
        for (var other : all) {
            if (!Boolean.TRUE.equals(other.getHasFrontChannel())) continue;
            if ((!Objects.equals(other.getId(), item.getId())
                            && matchesRoute(other.getListPageUrl(), other, path))
                    || matchesRoute(other.getDetailPageUrl(), other, path)) return null;
        }
        return url;
    }

    private static boolean matchesRoute(String route, OneshotEntity menu, String path) {
        if (route == null || route.isBlank()) return false;
        return new RouteHandlerEntry(
                        route.replace("${className}", Objects.toString(menu.getClassName(), ""))
                                .replace("{classId}", Objects.toString(menu.getId(), ""))
                                .replace("{parentId}", Objects.toString(menu.getParentId(), "")),
                        null)
                .matches(path);
    }

    static String region(Element anchor) {
        var owner = anchor.closest(PUBLIC_REGION);
        if (owner != null) return owner.is("header, .header") ? "HEADER" : "FOOTER";
        if (anchor.closest("main,article,[role=main],.main") == null
                || anchor.closest(
                                "nav,aside,form,[role=form],[role=button],[hidden],[aria-hidden=true]")
                        != null
                || anchor.text().isBlank()
                || !localSource(anchor.attr("href")) && !fragmentHref(anchor.attr("href"))
                || anchor.attr("href").contains("?")
                || anchor.attributes().asList().stream()
                        .anyMatch(
                                attr ->
                                        !Set.of("href", "class", "id", "title", "aria-label")
                                                .contains(attr.getKey()))
                || anchor.selectFirst("button,input,select,textarea,[role=button]") != null)
            return null;
        return "BODY";
    }

    private static boolean fragmentHref(String href) {
        if (href.isBlank() || href.length() > 512 || href.startsWith("//")) return false;
        try {
            var uri = URI.create(href);
            return !uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() != null && !uri.getRawFragment().isBlank()
                    && uri.getPath() != null && !uri.getPath().contains("\\");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean localSource(String href) {
        return localSource(href, false);
    }

    private static boolean localSource(String href, boolean allowFragment) {
        if (href.isBlank() || href.length() > 512 || href.startsWith("//")) return false;
        try {
            var uri = URI.create(href);
            return !uri.isAbsolute()
                    && uri.getRawAuthority() == null
                    && (allowFragment || uri.getRawFragment() == null)
                    && uri.getPath() != null
                    && !uri.getPath().isBlank()
                    && uri.getPath().indexOf('\\') < 0;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private Stored stored(SiteModelNavigationService.Scope scope, String path) {
        return store.navigationLinks(
                scope.siteId(), scope.code(), scope.packageVersion(), pageKey(path), Stored.class);
    }

    private static String pageKey(String path) {
        if (!StaticPageSource.supports(path)
                || path.length() > 512
                || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || path.contains(":")
                || path.contains("?")
                || path.contains("#")
                || java.util.Arrays.stream(path.split("/", -1))
                        .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("无效的公共导航来源页面");
        return PublicBindingApplication.digest(path);
    }

    private static void requireCurrentConfirmation(
            Stored existing, String expected, String requested) {
        String actual = existing == null ? null : existing.result().digest();
        if (!Objects.equals(actual, expected) && !Objects.equals(actual, requested))
            throw new IllegalArgumentException("公共导航审核已更新，请重新读取");
    }

    private static void requireSelections(Request request, Stored existing) {
        if (request.selections() == null || request.selections().isEmpty() && existing == null)
            throw new IllegalArgumentException("请选择待绑定的公共导航链接");
    }
}

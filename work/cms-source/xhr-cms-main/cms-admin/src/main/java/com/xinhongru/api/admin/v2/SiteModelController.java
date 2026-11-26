package com.xinhongru.api.admin.v2;

import com.xinhongru.ai.generation.AiGenerationTasks;
import com.xinhongru.ai.generation.AiTaskEvent;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.PublicSharedContentCompiler;
import com.xinhongru.cms.template.SiteModelEvidenceService;
import com.xinhongru.cms.template.SiteModelFieldBindingService;
import com.xinhongru.cms.template.SiteModelNavigationService;
import com.xinhongru.cms.template.SiteModelPlanService;
import com.xinhongru.cms.template.RootTemplateApplicationService;
import com.xinhongru.cms.template.SiteModelRecordSeedService;
import com.xinhongru.cms.template.SiteModelStructureService;
import com.xinhongru.cms.template.TemplateFieldPlanService;
import com.xinhongru.cms.template.WorkbenchReleaseReviewService;
import com.xinhongru.cms.template.WorkbenchStaticLinkCheckService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 内部整站结构建议、审核及正式栏目配置。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true")
@RequestMapping("/api/admin/v2/sites/{siteId}/template-seed/site-model")
public class SiteModelController {
    static final String TASK_SCOPE = "site-model:";
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final AiGenerationTasks tasks;
    private final SiteModelEvidenceService evidence;
    private final SiteModelPlanService plans;
    private final SiteModelFieldBindingService bindings;
    @Autowired private SiteModelStructureService structures;
    @Autowired private SiteModelRecordSeedService recordSeeds;
    @Autowired private com.xinhongru.cms.template.NavigationModelDraftService navigationModels;
    @Autowired private com.xinhongru.cms.template.SiteModelWorkbenchApplyService workbenchApply;
    @Autowired private RootTemplateApplicationService homeApplications;
    @Autowired private SiteModelNavigationService navigation;
    @Autowired private PublicSharedContentCompiler sharedContent;
    @Autowired private com.xinhongru.cms.template.PublicNavigationLinkCompiler navigationLinks;
    @Autowired private com.xinhongru.cms.template.NavigationLandingApplicationService navigationLandings;
    @Autowired private com.xinhongru.cms.template.WorkbenchChecklistEvidenceService checklistEvidence;
    @Autowired private WorkbenchStaticLinkCheckService staticLinks;
    @Autowired private WorkbenchReleaseReviewService releaseReview;
    @Autowired private com.xinhongru.cms.template.ParentEntryDecisionService parentEntryDecisions;
    private final ObjectMapper json = new ObjectMapper();

    public SiteModelController(
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions,
            AiGenerationTasks tasks,
            SiteModelEvidenceService evidence,
            SiteModelPlanService plans,
            SiteModelFieldBindingService bindings) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.tasks = tasks;
        this.evidence = evidence;
        this.plans = plans;
        this.bindings = bindings;
    }

    public record Start(
            String requestId, String packageVersion, String basePlanId, Long basePlanRevision) {
        public Start(String requestId, String packageVersion) {
            this(requestId, packageVersion, null, null);
        }
    }

    public record Recover(String packageVersion) {}

    public record Review(
            long revision,
            SiteModelPlanService.Proposal proposal,
            boolean confirm,
            String packageVersion) {}

    public record Fields(
            String packageVersion,
            long revision,
            String modelKey,
            TemplateFieldPlanService.Request request) {}

    public record Structure(
            String packageVersion, long revision, String modelKey, Integer parentMenuId) {}

    public record ReferenceScope(
            String packageVersion,
            long revision,
            String modelKey,
            String fieldKey,
            Integer menuId,
            String previewDigest,
            boolean confirmed) {}

    public record Supplement(
            String packageVersion,
            long revision,
            String modelKey,
            String beforeDigest,
            String afterDigest,
            boolean confirmed) {
        SiteModelStructureService.Supplement input() {
            return new SiteModelStructureService.Supplement(
                    revision, modelKey, beforeDigest, afterDigest, confirmed);
        }
    }

    public record SharedFields(String packageVersion, SiteModelStructureService.SharedFields selection) {}

    public record RecordSeed(
            String packageVersion,
            long revision,
            String sourcePath,
            String regionId,
            String previewDigest,
            boolean confirmed,
            Long ownerRecordId,
            Long menuId) {
        public RecordSeed(
                String packageVersion,
                long revision,
                String sourcePath,
                String regionId,
                String previewDigest,
                boolean confirmed) {
            this(
                    packageVersion,
                    revision,
                    sourcePath,
                    regionId,
                    previewDigest,
                    confirmed,
                    null,
                    null);
        }

        public RecordSeed(
                String packageVersion,
                long revision,
                String sourcePath,
                String regionId,
                String previewDigest,
                boolean confirmed,
                Long ownerRecordId) {
            this(
                    packageVersion,
                    revision,
                    sourcePath,
                    regionId,
                    previewDigest,
                    confirmed,
                    ownerRecordId,
                    null);
        }
    }

    public record NavigationReview(
            String packageVersion,
            long revision,
            List<SiteModelNavigationService.Node> nodes,
            boolean confirmed) {}

    public record NavigationApply(String packageVersion, long revision, boolean confirmed) {}

    public record NavigationModelDraft(String packageVersion, long planRevision, long navigationRevision,
            String nodeKey, Long fieldRevision, SiteModelPlanService.Model model) {}

    public record PlaceholderModel(String packageVersion, long planRevision, long navigationRevision,
            String nodeKey, String digest) {}

    @PostMapping("/plans/{id}/navigation/placeholder-model/preview")
    public AdminApiResponse<com.xinhongru.cms.template.NavigationModelDraftService.PlaceholderPreview> previewPlaceholderModel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody PlaceholderModel body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigationModels.previewPlaceholder(scope, id, body.planRevision(),
                body.navigationRevision(), body.nodeKey(), guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/placeholder-model/confirm")
    public AdminApiResponse<SiteModelPlanService.Plan> confirmPlaceholderModel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody PlaceholderModel body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigationModels.confirmPlaceholder(scope, id, body.planRevision(),
                body.navigationRevision(), body.nodeKey(), body.digest(),
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/model-draft/preview")
    public AdminApiResponse<com.xinhongru.cms.template.NavigationModelDraftService.Preview> previewNavigationModel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody NavigationModelDraft body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigationModels.preview(scope, id, body.planRevision(),
                body.navigationRevision(), body.nodeKey(),
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/model-draft/save")
    public AdminApiResponse<SiteModelPlanService.Plan> saveNavigationModel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody NavigationModelDraft body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigationModels.save(scope, id, body.planRevision(),
                body.navigationRevision(), body.nodeKey(), body.fieldRevision(), body.model(),
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/apply-workbench/preview")
    public AdminApiResponse<com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Preview> previewWorkbenchApply(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Request body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(workbenchApply.preview(scope, id, body,
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/apply-workbench/apply")
    public AdminApiResponse<com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Result> applyWorkbench(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Request body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(workbenchApply.apply(scope, id, body, request,
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/apply-workbench/close")
    public AdminApiResponse<com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Result> closeWorkbenchApply(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody com.xinhongru.cms.template.SiteModelWorkbenchApplyService.Request body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(workbenchApply.closeStale(scope, id, body,
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @GetMapping("/plans/{id}/navigation")
    public AdminApiResponse<SiteModelNavigationService.Navigation> navigation(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestParam String packageVersion) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        var scope = navigationScope(site);
        return AdminApiResponse.success(
                navigation.get(
                        scope, id, guard(actor(request), siteId, scope.code(), packageVersion)));
    }

    @PostMapping("/plans/{id}/navigation/review")
    public AdminApiResponse<SiteModelNavigationService.Navigation> reviewNavigation(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody NavigationReview body) {
        var site = site(request, siteId);
        if (body == null || !body.confirmed()) throw new IllegalArgumentException("请确认导航审核");
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(
                navigation.review(
                        scope,
                        id,
                        body.revision(),
                        body.nodes(),
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/apply")
    public AdminApiResponse<SiteModelNavigationService.Navigation> applyNavigation(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody NavigationApply body) {
        var site = site(request, siteId);
        if (body == null || !body.confirmed()) throw new IllegalArgumentException("请确认应用导航栏目");
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待应用导航的站点");
        var scope = navigationScope(site);
        return AdminApiResponse.success(
                navigation.apply(
                        scope,
                        id,
                        body.revision(),
                        request,
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/continuation/preview")
    public AdminApiResponse<SiteModelNavigationService.ContinuationPreview> previewNavigationContinuation(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.ContinuationRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(workbenchApply.previewNavigationContinuation(
                scope, id, body, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/continuation/apply")
    public AdminApiResponse<SiteModelNavigationService.Navigation> applyNavigationContinuation(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.ContinuationRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待接续导航的站点");
        var scope = navigationScope(site);
        return AdminApiResponse.success(workbenchApply.applyNavigationContinuation(
                scope, id, body, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/append/preview")
    public AdminApiResponse<SiteModelNavigationService.AppendPreview> previewNavigationAppend(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.AppendRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(
                navigation.previewAppend(
                        scope,
                        id,
                        body,
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/append/apply")
    public AdminApiResponse<SiteModelNavigationService.AppendResult> applyNavigationAppend(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.AppendRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待应用导航的站点");
        var scope = navigationScope(site);
        return AdminApiResponse.success(
                navigation.applyAppend(
                        scope,
                        id,
                        body,
                        request,
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/kind-correction/preview")
    public AdminApiResponse<SiteModelNavigationService.KindCorrectionPreview> previewNavigationKindCorrection(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.KindCorrectionRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigation.previewKindCorrection(
                scope, id, body, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/navigation/kind-correction/apply")
    public AdminApiResponse<SiteModelNavigationService.AppendResult> applyNavigationKindCorrection(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody SiteModelNavigationService.KindCorrectionRequest body) {
        var site = site(request, siteId);
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待修正导航的站点");
        var scope = navigationScope(site);
        return AdminApiResponse.success(navigation.applyKindCorrection(
                scope, id, body, request, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    private static SiteModelNavigationService.Scope navigationScope(SiteProjectEntity site) {
        return new SiteModelNavigationService.Scope(
                site.getId(),
                site.getProjectCode(),
                site.getUiPackageVersion(),
                site.getRootMenuId());
    }

    public record BannerHomeRequest(String packageVersion, long revision, String sourcePath,
                                    String regionId, List<String> aliases, boolean confirmed) {}

    @GetMapping("/plans/{id}/public-home")
    public AdminApiResponse<RootTemplateApplicationService.BannerOptions> bannerHome(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestParam String packageVersion, @RequestParam long revision,
            @RequestParam String sourcePath, @RequestParam String regionId) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(homeApplications.bannerOptions(siteId, site.getProjectCode(),
                packageVersion, id, revision, sourcePath, regionId));
    }

    @PostMapping("/plans/{id}/public-home")
    public AdminApiResponse<RootTemplateApplicationService.Result> applyBannerHome(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody BannerHomeRequest body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择已审核轮播区域");
        expected(site, body.packageVersion());
        synchronized (plans) {
            guard(actor(request), siteId, site.getProjectCode(), body.packageVersion()).run();
            return AdminApiResponse.success(homeApplications.applyBanner(siteId, site.getProjectCode(),
                    body.packageVersion(), id, body.revision(), body.sourcePath(), body.regionId(),
                    body.aliases(), body.confirmed()));
        }
    }

    @PostMapping("/plans/{id}/public-home/withdraw")
    public AdminApiResponse<RootTemplateApplicationService.Result> withdrawBannerHome(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody BannerHomeRequest body) {
        var site = site(request, siteId);
        if (body == null || body.packageVersion() == null)
            throw new IllegalArgumentException("请选择原轮播首页应用及页面包版本");
        return AdminApiResponse.success(homeApplications.withdrawBanner(siteId, site.getProjectCode(),
                body.packageVersion(), id, body.regionId(), body.confirmed()));
    }

    public record WithdrawLanding(String packageVersion, String sourcePath, String nodeKey, boolean confirmed) {}

    @GetMapping("/plans/{id}/public-navigation-landing")
    public AdminApiResponse<com.xinhongru.cms.template.NavigationLandingApplicationService.Inspection> navigationLandingApplication(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestParam String packageVersion, @RequestParam String sourcePath, @RequestParam String nodeKey,
            @RequestParam String containerSelector) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(navigationLandings.inspect(navigationScope(site), id,
                sourcePath, nodeKey, containerSelector));
    }

    @PostMapping("/plans/{id}/public-navigation-landing")
    public AdminApiResponse<com.xinhongru.cms.template.PublicListApplicationService.Result> applyNavigationLanding(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody com.xinhongru.cms.template.NavigationLandingApplicationService.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请先预览并审核概览入口");
        expected(site, body.packageVersion());
        requireLandingSite(request, site);
        return AdminApiResponse.success(navigationLandings.apply(navigationScope(site), id, body));
    }

    @PostMapping("/plans/{id}/public-navigation-landing/withdraw")
    public AdminApiResponse<com.xinhongru.cms.template.PublicListApplicationService.Result> withdrawNavigationLanding(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody WithdrawLanding body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择原概览应用");
        expected(site, body.packageVersion());
        requireLandingSite(request, site);
        return AdminApiResponse.success(navigationLandings.withdraw(navigationScope(site), id,
                body.sourcePath(), body.nodeKey(), body.confirmed()));
    }

    private void requireLandingSite(HttpServletRequest request, SiteProjectEntity site) {
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), site.getId())
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至概览所属站点");
    }

    @GetMapping("/plans/{id}/shared-content")
    public AdminApiResponse<PublicSharedContentCompiler.Workspace> sharedContent(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestParam String packageVersion,
            @RequestParam String sourcePath) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(sharedContent.read(navigationScope(site), id, sourcePath));
    }

    @PostMapping("/plans/{id}/shared-content/preview")
    public AdminApiResponse<PublicSharedContentCompiler.Preview> previewSharedContent(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody PublicSharedContentCompiler.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择公共信息");
        expected(site, body.packageVersion());
        return AdminApiResponse.success(sharedContent.preview(navigationScope(site), id, body));
    }

    @PostMapping("/plans/{id}/shared-content/confirm")
    public AdminApiResponse<PublicSharedContentCompiler.Preview> confirmSharedContent(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody PublicSharedContentCompiler.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择公共信息");
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待审核公共信息的站点");
        return AdminApiResponse.success(sharedContent.confirm(navigationScope(site), id, body));
    }

    @GetMapping("/plans/{id}/navigation-landing")
    public AdminApiResponse<com.xinhongru.cms.template.PublicNavigationLinkCompiler.LandingPreview>
            navigationLanding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestParam String packageVersion,
                    @RequestParam String sourcePath,
                    @RequestParam String nodeKey,
                    @RequestParam(required = false) String containerSelector) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(navigationLinks.previewLanding(
                navigationScope(site), id, sourcePath, nodeKey, containerSelector));
    }

    @GetMapping("/plans/{id}/navigation-landing/candidates")
    public AdminApiResponse<List<com.xinhongru.cms.template.PublicNavigationLinkCompiler.LandingCandidate>>
            navigationLandingCandidates(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestParam String packageVersion,
                    @RequestParam String sourcePath,
                    @RequestParam String nodeKey) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(navigationLinks.landingCandidates(
                navigationScope(site), id, sourcePath, nodeKey));
    }

    @GetMapping("/static-link-check")
    public AdminApiResponse<WorkbenchStaticLinkCheckService.Result> staticLinkCheck(
            HttpServletRequest request, @PathVariable Long siteId) {
        var site = site(request, siteId);
        var checked = staticLinks.read(site);
        guard(actor(request), siteId, site.getProjectCode(), site.getUiPackageVersion()).run();
        return AdminApiResponse.success(checked);
    }

    @GetMapping("/plans/{id}/release-review")
    public AdminApiResponse<WorkbenchReleaseReviewService.Review> releaseReview(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestParam String packageVersion, @RequestParam long planRevision,
            @RequestParam long navigationRevision, @RequestParam String deployment,
            @RequestParam(required = false) Integer batch, @RequestParam(required = false) String previousFingerprint) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        var scope = new WorkbenchReleaseReviewService.Scope(packageVersion, planRevision, navigationRevision, deployment);
        var guard = guard(actor(request), siteId, site.getProjectCode(), packageVersion);
        return AdminApiResponse.success(batch == null
                ? releaseReview.read(site, id, scope, guard)
                : releaseReview.continueRead(site, id, scope, batch, previousFingerprint, guard));
    }

    @PostMapping("/plans/{id}/release-review")
    public AdminApiResponse<WorkbenchReleaseReviewService.Review> saveReleaseReview(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody WorkbenchReleaseReviewService.Save body) {
        var site = site(request, siteId);
        if (body == null || body.scope() == null) throw new IllegalArgumentException("缺少签收范围");
        expected(site, body.scope().packageVersion());
        return AdminApiResponse.success(releaseReview.save(site, id, body,
                guard(actor(request), siteId, site.getProjectCode(), body.scope().packageVersion())));
    }

    @PostMapping("/plans/{id}/checklist-evidence")
    public AdminApiResponse<com.xinhongru.cms.template.WorkbenchChecklistEvidenceService.Result>
            checklistEvidence(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestBody com.xinhongru.cms.template.WorkbenchChecklistEvidenceService.Request body) {
        var scope = navigationScope(site(request, siteId));
        return AdminApiResponse.success(checklistEvidence.read(
                scope, id, body, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @GetMapping("/plans/{id}/parent-entry-decisions")
    public AdminApiResponse<com.xinhongru.cms.template.ParentEntryDecisionService.Result>
            parentEntryDecisions(
                    HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
                    @RequestParam String packageVersion, @RequestParam long planRevision,
                    @RequestParam long navigationRevision) {
        var scope = navigationScope(site(request, siteId));
        return AdminApiResponse.success(parentEntryDecisions.read(scope, id, packageVersion,
                planRevision, navigationRevision, guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/parent-entry-decisions")
    public AdminApiResponse<com.xinhongru.cms.template.ParentEntryDecisionService.Result>
            saveParentEntryDecision(
                    HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
                    @RequestBody com.xinhongru.cms.template.ParentEntryDecisionService.Save body) {
        var scope = navigationScope(site(request, siteId));
        return AdminApiResponse.success(parentEntryDecisions.save(scope, id, body,
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @GetMapping("/plans/{id}/navigation-links")
    public AdminApiResponse<com.xinhongru.cms.template.PublicNavigationLinkCompiler.Workspace>
            navigationLinks(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestParam String packageVersion,
                    @RequestParam String sourcePath) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        return AdminApiResponse.success(
                navigationLinks.read(navigationScope(site), id, sourcePath));
    }

    @PostMapping("/plans/{id}/navigation-links/preview")
    public AdminApiResponse<com.xinhongru.cms.template.PublicNavigationLinkCompiler.Preview>
            previewNavigationLinks(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestBody
                            com.xinhongru.cms.template.PublicNavigationLinkCompiler.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择公共导航链接");
        expected(site, body.packageVersion());
        return AdminApiResponse.success(navigationLinks.preview(navigationScope(site), id, body));
    }

    @PostMapping("/plans/{id}/navigation-links/confirm")
    public AdminApiResponse<com.xinhongru.cms.template.PublicNavigationLinkCompiler.Preview>
            confirmNavigationLinks(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String id,
                    @RequestBody
                            com.xinhongru.cms.template.PublicNavigationLinkCompiler.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择公共导航链接");
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待审核公共导航的站点");
        return AdminApiResponse.success(navigationLinks.confirm(navigationScope(site), id, body));
    }

    @GetMapping("/plans/{id}/record-seed")
    public AdminApiResponse<SiteModelRecordSeedService.Result> recordSeedPreview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestParam String packageVersion,
            @RequestParam long revision,
            @RequestParam String sourcePath,
            @RequestParam String regionId,
            @RequestParam(required = false) Long ownerRecordId,
            @RequestParam(required = false) Long menuId) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        var scope = recordSeedScope(site);
        return AdminApiResponse.success(
                recordSeeds.preview(
                        scope,
                        id,
                        new SiteModelRecordSeedService.Selection(
                                revision, sourcePath, regionId, ownerRecordId, menuId),
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/record-seed")
    public AdminApiResponse<SiteModelRecordSeedService.Result> importRecordSeed(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody RecordSeed body) {
        var site = site(request, siteId);
        if (body == null || !body.confirmed())
            throw new IllegalArgumentException("请确认将页面原值批量导入为草稿");
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待导入内容的站点");
        var scope = recordSeedScope(site);
        int owner = actor(request);
        return AdminApiResponse.success(
                recordSeeds.importDrafts(
                        scope,
                        id,
                        new SiteModelRecordSeedService.Selection(
                                body.revision(),
                                body.sourcePath(),
                                body.regionId(),
                                body.ownerRecordId(),
                                body.menuId()),
                        body.previewDigest(),
                        owner,
                        guard(owner, siteId, scope.code(), scope.packageVersion())));
    }

    private SiteModelRecordSeedService.Scope recordSeedScope(SiteProjectEntity site) {
        return new SiteModelRecordSeedService.Scope(
                site.getId(),
                site.getRootMenuId(),
                site.getProjectCode(),
                site.getUiPackageVersion());
    }

    @GetMapping("/plans/{id}/structure/owned-instances")
    public AdminApiResponse<List<SiteModelStructureService.OwnedInstance>> ownedInstances(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestParam String packageVersion,
            @RequestParam long revision,
            @RequestParam String modelKey) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        String code = site.getProjectCode();
        String version = site.getUiPackageVersion();
        return AdminApiResponse.success(
                structures.ownedInstances(
                        new SiteModelStructureService.Scope(
                                siteId, code, version, site.getRootMenuId()),
                        id,
                        revision,
                        modelKey,
                        guard(actor(request), siteId, code, version)));
    }

    @PostMapping("/plans/{id}/apply-structure")
    public AdminApiResponse<SiteModelStructureService.Applied> applyStructure(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody Structure body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择已确认的内容类型");
        expected(site, body.packageVersion());
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待应用结构的站点");
        String code = site.getProjectCode();
        String version = site.getUiPackageVersion();
        return AdminApiResponse.success(
                structures.apply(
                        new SiteModelStructureService.Scope(
                                siteId, code, version, site.getRootMenuId()),
                        id,
                        new SiteModelStructureService.Apply(
                                body.revision(), body.modelKey(), body.parentMenuId()),
                        request,
                        guard(actor(request), siteId, code, version)));
    }

    @GetMapping("/plans/{id}/structure/reference-scope")
    public AdminApiResponse<SiteModelStructureService.ReferenceScope> referenceScope(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestParam String packageVersion,
            @RequestParam long revision,
            @RequestParam String modelKey,
            @RequestParam String fieldKey,
            @RequestParam Integer menuId) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        var scope =
                new SiteModelStructureService.Scope(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        site.getRootMenuId());
        return AdminApiResponse.success(
                structures.referenceScope(
                        scope,
                        id,
                        revision,
                        modelKey,
                        fieldKey,
                        menuId,
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/structure/reference-scope")
    public AdminApiResponse<SiteModelStructureService.ReferenceScope> applyReferenceScope(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody ReferenceScope body) {
        if (body == null) throw new IllegalArgumentException("请选择待更新的引用字段");
        var scope =
                supplementScope(
                        request,
                        siteId,
                        new Supplement(
                                body.packageVersion(),
                                body.revision(),
                                body.modelKey(),
                                null,
                                null,
                                false));
        return AdminApiResponse.success(
                structures.applyReferenceScope(
                        scope,
                        id,
                        body.revision(),
                        body.modelKey(),
                        body.fieldKey(),
                        body.menuId(),
                        body.previewDigest(),
                        body.confirmed(),
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/field-supplement/preview")
    public AdminApiResponse<SiteModelStructureService.SupplementPreview> previewFieldSupplement(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody Supplement body) {
        var scope = supplementScope(request, siteId, body);
        return AdminApiResponse.success(
                structures.previewSupplement(
                        scope,
                        id,
                        body.input(),
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    @PostMapping("/plans/{id}/field-supplement/apply")
    public AdminApiResponse<SiteModelStructureService.Applied> applyFieldSupplement(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody Supplement body) {
        var scope = supplementScope(request, siteId, body);
        return AdminApiResponse.success(
                structures.applySupplement(
                        scope,
                        id,
                        body.input(),
                        guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    private SiteModelStructureService.Scope supplementScope(
            HttpServletRequest request, Long siteId, Supplement body) {
        if (body == null) throw new IllegalArgumentException("请选择已审核的内容类型");
        return supplementScope(request, siteId, body.packageVersion());
    }

    @PostMapping("/plans/{id}/field-supplement/shared")
    public AdminApiResponse<SiteModelStructureService.SupplementPreview> sharedFields(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id,
            @RequestBody SharedFields body) {
        if (body == null || body.selection() == null)
            throw new IllegalArgumentException("请选择共享模型与实际栏目");
        var scope = supplementScope(request, siteId, body.packageVersion());
        return AdminApiResponse.success(structures.sharedFields(scope, id, body.selection(),
                guard(actor(request), siteId, scope.code(), scope.packageVersion())));
    }

    private SiteModelStructureService.Scope supplementScope(
            HttpServletRequest request, Long siteId, String packageVersion) {
        var site = site(request, siteId);
        expected(site, packageVersion);
        var current = sites.requireCurrentSite(request);
        if (!Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), site.getRootMenuId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请切换至待增补字段的站点");
        return new SiteModelStructureService.Scope(
                siteId, site.getProjectCode(), site.getUiPackageVersion(), site.getRootMenuId());
    }

    @PostMapping("/plans/{id}/field-plans")
    public AdminApiResponse<TemplateFieldPlanService.Plan> fieldPlan(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody Fields body) {
        var site = site(request, siteId);
        if (body == null || body.request() == null)
            throw new IllegalArgumentException("请选择已确认模型及页面");
        expected(site, body.packageVersion());
        if (body.request().menuId() == null
                || Objects.equals(body.request().menuId(), site.getRootMenuId().longValue()))
            throw new IllegalArgumentException("请选择本站实际内容栏目，根栏目不可作为绑定目标");
        String code = site.getProjectCode(), version = site.getUiPackageVersion();
        return AdminApiResponse.success(
                bindings.preview(
                        siteId,
                        code,
                        version,
                        id,
                        body.revision(),
                        body.modelKey(),
                        body.request(),
                        guard(actor(request), siteId, code, version)));
    }

    @GetMapping("/catalog")
    public AdminApiResponse<SiteModelEvidenceService.SiteCatalog> catalog(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam(required = false) String packageVersion) {
        var site = site(request, siteId);
        if (packageVersion != null) expected(site, packageVersion);
        var result = evidence.catalog(site.getProjectCode(), site.getUiPackageVersion());
        guard(actor(request), siteId, site.getProjectCode(), site.getUiPackageVersion()).run();
        return AdminApiResponse.success(result);
    }

    @GetMapping("/evidence")
    public AdminApiResponse<SiteModelEvidenceService.PageEvidence> evidence(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam String sourcePath,
            @RequestParam(required = false) String packageVersion) {
        var site = site(request, siteId);
        if (packageVersion != null) expected(site, packageVersion);
        var result = evidence.page(site.getProjectCode(), site.getUiPackageVersion(), sourcePath);
        guard(actor(request), siteId, site.getProjectCode(), site.getUiPackageVersion()).run();
        return AdminApiResponse.success(result);
    }

    @GetMapping("/plans/latest")
    public AdminApiResponse<SiteModelPlanService.Plan> latest(
            HttpServletRequest request, @PathVariable Long siteId) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                plans.latest(siteId, site.getProjectCode(), site.getUiPackageVersion()));
    }

    @GetMapping("/plans/{id}")
    public AdminApiResponse<SiteModelPlanService.Plan> get(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                plans.get(siteId, site.getProjectCode(), site.getUiPackageVersion(), id));
    }

    @PostMapping("/plans/{id}/review")
    public AdminApiResponse<SiteModelPlanService.Plan> review(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String id,
            @RequestBody Review body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提交整站结构审核内容");
        expected(site, body.packageVersion());
        return AdminApiResponse.success(
                plans.review(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        id,
                        new SiteModelPlanService.Review(
                                body.revision(), body.proposal(), body.confirm()),
                        guard(
                                actor(request),
                                siteId,
                                site.getProjectCode(),
                                site.getUiPackageVersion())));
    }

    @PostMapping("/tasks")
    public AdminApiResponse<AiGenerationTasks.Snapshot> start(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody Start body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提交整站分析请求");
        expected(site, body.packageVersion());
        int owner = actor(request);
        String code = site.getProjectCode(), version = site.getUiPackageVersion();
        Runnable check = guard(owner, siteId, code, version);
        check.run();
        if ((body.basePlanId() == null) != (body.basePlanRevision() == null))
            throw new IllegalArgumentException("继续分析需同时指定草案和修订号");
        String id = requestId(body.requestId());
        if (body.basePlanId() != null) {
            if (id.equals(body.basePlanId())) throw new IllegalArgumentException("继续分析需使用新的任务标识");
            plans.continuationBase(
                    siteId, code, version, body.basePlanId(), body.basePlanRevision());
        }
        var task =
                tasks.create(
                        owner,
                        siteId,
                        AiUsage.WORKBENCH_DOMAIN_AGENT,
                        id,
                        TASK_SCOPE
                                + json.writeValueAsString(
                                        java.util.Arrays.asList(
                                                code,
                                                version,
                                                body.basePlanId(),
                                                body.basePlanRevision())));
        synchronized (task) {
            if (task.state().equals("pending"))
                tasks.start(
                        task,
                        Mono.fromCallable(
                                        () ->
                                                plans.analyze(
                                                        siteId,
                                                        code,
                                                        version,
                                                        body.basePlanId(),
                                                        body.basePlanRevision(),
                                                        task,
                                                        check))
                                .map(plan -> AiTaskEvent.delta(json.writeValueAsString(plan)))
                                .flux());
        }
        return AdminApiResponse.success(task.snapshot());
    }

    @GetMapping("/tasks/{taskId}")
    public AdminApiResponse<AiGenerationTasks.Snapshot> task(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        var task = owned(request, siteId, taskId);
        var project = site(request, siteId);
        checkTaskPackage(task, project);
        return AdminApiResponse.success(task.snapshot());
    }

    private void checkTaskPackage(AiGenerationTasks.Task task, SiteProjectEntity project) {
        var scope = json.readTree(task.inputKey().substring(TASK_SCOPE.length()));
        if (!scope.isArray()
                || !Objects.equals(scope.path(0).asText(), project.getProjectCode())
                || !Objects.equals(scope.path(1).asText(), project.getUiPackageVersion()))
            throw new IllegalArgumentException("任务属于旧页面包，请读取当前包的整站结构；旧任务仍可取消");
    }

    @GetMapping(
            value = "/tasks/{taskId}/events",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter taskEvents(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var task = owned(request, siteId, taskId);
        return com.xinhongru.ai.generation.AgentTaskProgressSse.open(task, lastEventId,
                () -> checkTaskPackage(owned(request, siteId, taskId), site(request, siteId)));
    }

    @DeleteMapping("/tasks/{taskId}")
    public AdminApiResponse<AiGenerationTasks.Snapshot> cancel(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        var task = owned(request, siteId, taskId);
        tasks.cancel(task);
        return AdminApiResponse.success(task.snapshot());
    }

    @PostMapping("/tasks/{taskId}/recover-draft")
    public AdminApiResponse<SiteModelPlanService.Plan> recover(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String taskId,
            @RequestBody Recover body) {
        var project = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提供检查点的页面包版本");
        expected(project, body.packageVersion());
        String id = requestId(taskId);
        int owner = actor(request);
        var known = tasks.findOwned(id, owner);
        if (known != null) {
            checkTaskScope(known, siteId);
            checkTaskPackage(known, project);
            if (!known.terminal())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "任务仍在执行，请等待结束或先取消再恢复草案");
        }
        return AdminApiResponse.success(
                plans.recoverDraft(
                        siteId,
                        project.getProjectCode(),
                        project.getUiPackageVersion(),
                        id,
                        guard(
                                owner,
                                siteId,
                                project.getProjectCode(),
                                project.getUiPackageVersion())));
    }

    private AiGenerationTasks.Task owned(HttpServletRequest request, Long siteId, String taskId) {
        site(request, siteId);
        var task = tasks.require(taskId, actor(request));
        checkTaskScope(task, siteId);
        return task;
    }

    private void checkTaskScope(AiGenerationTasks.Task task, Long siteId) {
        if (!Objects.equals(task.siteId, siteId)
                || task.usage != AiUsage.WORKBENCH_DOMAIN_AGENT
                || !task.inputKey().startsWith(TASK_SCOPE))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "整站结构任务不存在");
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var site = projects.selectById(siteId);
        if (site == null || Integer.valueOf(1).equals(site.getDeleted()))
            throw new IllegalArgumentException("站点不存在");
        guard(actor(request), siteId, site.getProjectCode(), site.getUiPackageVersion()).run();
        return site;
    }

    private int actor(HttpServletRequest request) {
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请重新登录");
        return user.getId();
    }

    private Runnable guard(int owner, Long siteId, String code, String version) {
        return () -> {
            if (!permissions.snapshotForActor(owner, siteId).isAll())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权审核此站点结构");
            var current = projects.selectById(siteId);
            if (current == null
                    || Integer.valueOf(1).equals(current.getDeleted())
                    || current.getRootMenuId() == null
                    || code == null
                    || version == null
                    || version.isBlank()
                    || !Objects.equals(code, current.getProjectCode())
                    || !Objects.equals(version, current.getUiPackageVersion()))
                throw new IllegalStateException("站点或页面包已变化，请重新读取整站结构");
        };
    }

    private void expected(SiteProjectEntity site, String version) {
        if (version == null || !Objects.equals(version, site.getUiPackageVersion()))
            throw new IllegalArgumentException("页面包版本已变化，请重新读取整站结构");
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> accessDenied() {
        return org.springframework.http.ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AdminApiResponse<>(403, "无权访问当前站点工作台", null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> statusFailure(
            ResponseStatusException failure) {
        String message = failure.getReason() == null ? "整站结构请求未完成" : failure.getReason();
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
                .body(new AdminApiResponse<>(failure.getStatusCode().value(), message, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalidRequest(
            IllegalArgumentException failure) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, failure.getMessage(), null));
    }

    private String requestId(String value) {
        if (value == null) return UUID.randomUUID().toString();
        if (!value.matches("[a-fA-F0-9-]{36}")) throw new IllegalArgumentException("分析请求标识无效");
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("分析请求标识无效");
        }
    }
}

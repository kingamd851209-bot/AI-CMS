package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.TemplateFieldPlanService;
import com.xinhongru.cms.template.TemplateListPairingService;
import com.xinhongru.cms.template.TextBindingDraftService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 工作台有界配对；每条初始化调用既有独立事务入口，不自动恢复或发布。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/admin/v2/sites/{siteId}/template-seed/list-pairing")
public class TemplateListPairingController {
    private final TemplateListPairingService service;
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;

    public TemplateListPairingController(
            TemplateListPairingService service,
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions) {
        this.service = service;
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
    }

    @GetMapping("/containers")
    public AdminApiResponse<TemplateListPairingService.Candidates> containers(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam Long menuId,
            @RequestParam String sourcePath) {
        var site = site(request, siteId, menuId);
        return AdminApiResponse.success(
                service.candidates(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        menuId,
                        sourcePath));
    }

    @PostMapping("/inspect")
    public AdminApiResponse<TemplateListPairingService.Inspection> inspect(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateListPairingService.Request body) {
        if (body == null) throw new IllegalArgumentException("请选择列表和目标栏目");
        var site = site(request, siteId, body.menuId());
        return AdminApiResponse.success(
                service.inspectForReview(siteId, site.getProjectCode(), site.getUiPackageVersion(), body,
                        plans.reviewedPairingDetailPath(siteId, site.getProjectCode(), site.getUiPackageVersion(), body),
                        permissions.snapshot(request)::hasMenuAccess));
    }

    @PostMapping("/review")
    public AdminApiResponse<TemplateListPairingService.Review> review(
            HttpServletRequest request, @PathVariable Long siteId,
            @RequestBody TemplateListPairingService.ReviewRequest body) {
        if (body == null || body.pairing() == null) throw new IllegalArgumentException("请选择已检查的列表范围");
        var project = site(request, siteId, body.pairing().menuId());
        if (!Objects.equals(sites.requireCurrentSite(request).getSiteId(), siteId))
            throw new IllegalArgumentException("请切换到列表所属站点后再保存审核");
        return AdminApiResponse.success(service.saveReview(siteId, project.getProjectCode(),
                project.getUiPackageVersion(), body, plans.reviewedPairingDetailPath(
                        siteId, project.getProjectCode(), project.getUiPackageVersion(), body.pairing())));
    }

    @org.springframework.beans.factory.annotation.Autowired private TemplateFieldPlanService plans;
    @org.springframework.beans.factory.annotation.Autowired private TextBindingDraftService drafts;

    @PostMapping("/reviewed-details")
    public AdminApiResponse<List<TemplateFieldPlanService.Summary>> reviewedDetails(
            HttpServletRequest request, @PathVariable Long siteId,
            @RequestBody TemplateListPairingService.Request body) {
        if (body == null) throw new IllegalArgumentException("请选择列表和目标栏目");
        var site = site(request, siteId, body.menuId());
        return AdminApiResponse.success(plans.reviewedPairingDetails(
                siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    @PostMapping("/options")
    public AdminApiResponse<TemplateFieldPlanService.PairedOptions> options(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateFieldPlanService.PairedOptionsRequest body) {
        if (body == null || body.pairing() == null) throw new IllegalArgumentException("请选择已确认卡片");
        var site = site(request, siteId, body.pairing().menuId());
        return AdminApiResponse.success(
                plans.pairedOptions(
                        siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    @PostMapping("/plans")
    public AdminApiResponse<TemplateFieldPlanService.Plan> plan(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateFieldPlanService.PairedRequest body) {
        if (body == null || body.pairing() == null) throw new IllegalArgumentException("请选择已确认卡片");
        var site = site(request, siteId, body.pairing().menuId());
        return AdminApiResponse.success(
                plans.previewPaired(
                        siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    @PostMapping("/detail-field-plan")
    public AdminApiResponse<TemplateFieldPlanService.Plan> detailFieldPlan(HttpServletRequest request,
            @PathVariable Long siteId, @RequestBody TemplateFieldPlanService.SharedDetailTemplateRequest body) {
        if (body == null || body.pairing() == null) throw new IllegalArgumentException("请选择已审核的共享详情");
        var project = site(request, siteId, body.pairing().menuId());
        if (!Objects.equals(sites.requireCurrentSite(request).getSiteId(), siteId))
            throw new IllegalArgumentException("请切换至详情所属站点后再生成字段计划");
        return AdminApiResponse.success(drafts.previewSharedDetailTemplate(
                siteId, project.getProjectCode(), project.getUiPackageVersion(), body));
    }

    @GetMapping("/plans/{planId}/list-preview")
    public AdminApiResponse<TextBindingDraftService.Draft> listPreview(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var project = projects.selectById(siteId);
        if (project == null) throw new IllegalArgumentException("站点不存在");
        var plan =
                plans.read(siteId, project.getProjectCode(), project.getUiPackageVersion(), planId);
        site(request, siteId, plan.menuId());
        if (plan.origins() == null || plan.origins().size() != 2)
            throw new IllegalArgumentException("请选择双来源计划");
        return AdminApiResponse.success(
                drafts.previewList(
                        siteId, project.getProjectCode(), project.getUiPackageVersion(), planId));
    }

    public record BatchRequest(Long menuId, List<String> planIds, boolean confirmed) {}

    public record BatchEntry(
            String planId,
            String status,
            Long recordId,
            String message,
            TextBindingDraftService.Recovery recovery) {}

    public record BatchResult(List<BatchEntry> entries, int initialized, int pending) {}

    // 故意没有外围事务：Spring注入的drafts代理为每条建立原生独立事务，失败项不隐式重试。
    @PostMapping("/initialize-batch")
    public AdminApiResponse<BatchResult> initializeBatch(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody BatchRequest body) {
        if (body == null
                || !body.confirmed()
                || body.planIds() == null
                || body.planIds().isEmpty()
                || body.planIds().size() > 20
                || body.planIds().stream()
                        .anyMatch(id -> id == null || !id.matches("[0-9a-fA-F-]{36}"))
                || new HashSet<>(body.planIds()).size() != body.planIds().size())
            throw new IllegalArgumentException("请明确确认1至20个不重复的已确认计划");
        var project = site(request, siteId, body.menuId());
        if (!permissions.snapshot(request).canOperate(Math.toIntExact(body.menuId()), 1))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权新增目标栏目内容");
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        String listPath = null;
        var details = new HashSet<String>();
        var cards = new HashSet<String>();
        // 全部权限、目标和来源范围先检查；实际来源/字段配置仍由每条事务内再次验证。
        for (String id : body.planIds()) {
            var plan =
                    plans.read(siteId, project.getProjectCode(), project.getUiPackageVersion(), id);
            if (!Objects.equals(plan.menuId(), body.menuId())
                    || plan.pageType() != 1
                    || !"CONFIRMED".equals(plan.status())
                    || plan.origins() == null
                    || plan.origins().size() != 2)
                throw new IllegalArgumentException("批次只能包含同一目标列表栏目的已确认双来源计划");
            var list =
                    plan.origins().stream()
                            .filter(origin -> "LIST".equals(origin.role()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("计划缺少列表来源"));
            if (listPath == null) listPath = list.sourcePath();
            if (!listPath.equals(list.sourcePath())
                    || list.pairingReview() != null && !cards.add(list.pairingReview().cardId())
                    || !details.add(TemplateFieldPlanService.applicationId(plan)))
                throw new IllegalArgumentException("批次来源列表不一致或包含重复记录来源，仍待人工处理");
        }
        var result = new ArrayList<BatchEntry>();
        int initialized = 0;
        for (String id : body.planIds()) {
            try {
                var saved =
                        drafts.initialize(
                                siteId,
                                project.getProjectCode(),
                                project.getUiPackageVersion(),
                                id,
                                true,
                                user.getId().longValue(),
                                request);
                if (!saved.initialized()) throw new IllegalStateException("草稿未确认");
                result.add(
                        new BatchEntry(id, "INITIALIZED", saved.recordId(), "私有草稿已初始化；未发布", null));
                initialized++;
            } catch (RuntimeException error) {
                TextBindingDraftService.Recovery recovery = null;
                try {
                    recovery =
                            drafts.recovery(
                                    siteId,
                                    project.getProjectCode(),
                                    project.getUiPackageVersion(),
                                    id);
                } catch (RuntimeException unavailable) {
                    /* 明确失败，保留原计划供独立核对。 */
                }
                String message =
                        error instanceof IllegalArgumentException
                                ? error.getMessage()
                                : "初始化未完成，请检查恢复状态；不会自动重试或新增";
                result.add(
                        new BatchEntry(
                                id,
                                "RECOVERY_REQUIRED",
                                recovery == null ? null : recovery.recordId(),
                                message,
                                recovery));
            }
        }
        return AdminApiResponse.success(
                new BatchResult(List.copyOf(result), initialized, result.size() - initialized));
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId, Long menuId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        if (menuId == null
                || menuId <= 0
                || menuId > Integer.MAX_VALUE
                || java.util.Objects.equals(site.getRootMenuId(), menuId.intValue()))
            throw new IllegalArgumentException("请选择有效内容列表栏目");
        if (!permissions.snapshot(request).hasMenuAccess(menuId.intValue()))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权查看目标栏目");
        return site;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }
}

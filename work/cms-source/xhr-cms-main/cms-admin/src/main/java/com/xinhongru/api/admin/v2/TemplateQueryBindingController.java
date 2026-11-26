package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.content.record.NativeContentAdminQueryService;
import com.xinhongru.cms.template.FormActionBinding;
import com.xinhongru.cms.template.FormPrefillTemplateCompiler;
import com.xinhongru.cms.template.NativeFormImportService;
import com.xinhongru.cms.template.QueryTemplateCompiler;
import com.xinhongru.cms.template.TemplateQueryBindingService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 开发者明确查询接线；只保存元数据，不改业务记录或自动发布。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/admin/v2/sites/{siteId}/template-query")
public class TemplateQueryBindingController {
    private final TemplateQueryBindingService service;
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired private QueryTemplateCompiler compiler;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.QueryListPresentation presentation;

    @org.springframework.beans.factory.annotation.Autowired
    private FormPrefillTemplateCompiler prefill;

    @org.springframework.beans.factory.annotation.Autowired
    private NativeFormImportService nativeForms;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TemplateFieldPlanService fieldPlans;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TextBindingPublicApplicationService publicBindings;

    public TemplateQueryBindingController(
            TemplateQueryBindingService service,
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions) {
        this.service = service;
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
    }

    @GetMapping("/candidates")
    public AdminApiResponse<TemplateQueryBindingService.Candidates> candidates(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam Long menuId,
            @RequestParam String sourcePath,
            @RequestParam String selector) {
        var site = site(request, siteId, menuId);
        return AdminApiResponse.success(
                service.candidates(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        menuId,
                        sourcePath,
                        selector));
    }

    @GetMapping("/fields")
    public AdminApiResponse<NativeContentAdminQueryService.QueryFields> fields(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam Long menuId) {
        site(request, siteId, menuId);
        return AdminApiResponse.success(service.fields(siteId, menuId));
    }

    @GetMapping("/timeline/evidence")
    public AdminApiResponse<com.xinhongru.cms.template.QueryTimeline.Evidence> timelineEvidence(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam Long menuId,
            @RequestParam String sourcePath, @RequestParam String sourcePlanId) {
        var site = site(request, siteId, menuId);
        return AdminApiResponse.success(service.timelineEvidence(siteId, site.getProjectCode(),
                site.getUiPackageVersion(), menuId, sourcePath, sourcePlanId));
    }

    public record PresentationEvidenceRequest(
            String sourcePlanId,
            com.xinhongru.cms.template.TemplateListPairingService.Request pairing,
            String cardId) {}

    @PostMapping("/presentation/evidence")
    public AdminApiResponse<com.xinhongru.cms.template.QueryListPresentation.Evidence>
            presentationEvidence(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestBody PresentationEvidenceRequest body) {
        if (body == null || body.pairing() == null)
            throw new IllegalArgumentException("请选择已确认的内容计划与原卡片");
        var site = site(request, siteId, body.pairing().menuId());
        return AdminApiResponse.success(
                presentation.inspect(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        body.sourcePlanId(),
                        body.pairing(),
                        body.cardId()));
    }

    public record PrefillTargetRequest(
            String sourcePlanId,
            com.xinhongru.cms.template.TemplateListPairingService.Request pairing,
            String cardId,
            FormActionBinding.Selection action,
            Integer formCategoryId,
            String expectedTemplate,
            boolean confirmed,
            String detailPlanId) {}

    @PostMapping("/prefill-target/preview")
    public AdminApiResponse<FormPrefillTemplateCompiler.Result> prefillPreview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody PrefillTargetRequest body) {
        return AdminApiResponse.success(prefillTarget(request, siteId, body, false));
    }

    @PostMapping("/prefill-target/apply")
    public AdminApiResponse<FormPrefillTemplateCompiler.Result> prefillApply(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody PrefillTargetRequest body) {
        return AdminApiResponse.success(prefillTarget(request, siteId, body, true));
    }

    private FormPrefillTemplateCompiler.Result prefillTarget(
            HttpServletRequest request, Long siteId, PrefillTargetRequest body, boolean apply) {
        if (body == null
                || body.action() == null
                || body.detailPlanId() == null && body.pairing() == null
                || body.detailPlanId() != null && body.pairing() != null)
            throw new IllegalArgumentException("请选择详情或原卡片、目标表单与预填字段");
        var action = body.action();
        var project = site(request, siteId, action.targetMenuId());
        FormActionBinding.Candidate candidate;
        if (body.detailPlanId() != null) {
            var plan =
                    fieldPlans.read(
                            siteId,
                            project.getProjectCode(),
                            project.getUiPackageVersion(),
                            body.detailPlanId());
            site(request, siteId, plan.menuId());
            candidate =
                    publicBindings.actionCandidate(
                            siteId,
                            project.getProjectCode(),
                            project.getUiPackageVersion(),
                            plan.id(),
                            action);
        } else {
            site(request, siteId, body.pairing().menuId());
            var evidence =
                    presentation.inspect(
                            siteId,
                            project.getProjectCode(),
                            project.getUiPackageVersion(),
                            body.sourcePlanId(),
                            body.pairing(),
                            body.cardId());
            candidate =
                    evidence.actions().stream()
                            .filter(
                                    item ->
                                            item.sourceId().equals(action.sourceId())
                                                    && item.fields().stream()
                                                            .anyMatch(
                                                                    field ->
                                                                            field.inputId()
                                                                                    .equals(
                                                                                            action
                                                                                                    .inputId())))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("目标输入缺少当前取证"));
            if (evidence.fields().stream()
                    .noneMatch(
                            field ->
                                    "SingleLine".equals(field.fieldType())
                                            && field.fieldName().equals(action.fieldName())))
                throw new IllegalArgumentException("预填只支持已启用的普通单行文字字段");
        }
        if (body.formCategoryId() != null
                && action.formCategoryId() != null
                && !body.formCategoryId().equals(action.formCategoryId()))
            throw new IllegalArgumentException("目标表单分类与当前动作不一致");
        Integer categoryId =
                body.formCategoryId() == null ? action.formCategoryId() : body.formCategoryId();
        if (apply && categoryId == null) throw new IllegalArgumentException("请先审核正式表单分类，再应用预填提交模板");
        var receipt =
                categoryId == null
                        ? null
                        : nativeForms.read(
                                siteId,
                                project.getProjectCode(),
                                project.getUiPackageVersion(),
                                categoryId);
        return apply
                ? prefill.apply(
                        siteId,
                        project.getProjectCode(),
                        project.getUiPackageVersion(),
                        action.targetMenuId(),
                        candidate,
                        action.inputId(),
                        receipt,
                        body.expectedTemplate(),
                        body.confirmed())
                : prefill.preview(
                        siteId,
                        project.getProjectCode(),
                        project.getUiPackageVersion(),
                        action.targetMenuId(),
                        candidate,
                        action.inputId(),
                        receipt);
    }

    public record NativeFormTargetRequest(
            Long menuId, Integer formCategoryId, String expectedTemplate, boolean confirmed) {}

    @PostMapping("/native-form-target/preview")
    public AdminApiResponse<FormPrefillTemplateCompiler.Result> nativeFormTargetPreview(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody NativeFormTargetRequest body) {
        return AdminApiResponse.success(nativeFormTarget(request, siteId, body, false));
    }

    @PostMapping("/native-form-target/apply")
    public AdminApiResponse<FormPrefillTemplateCompiler.Result> nativeFormTargetApply(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody NativeFormTargetRequest body) {
        return AdminApiResponse.success(nativeFormTarget(request, siteId, body, true));
    }

    private FormPrefillTemplateCompiler.Result nativeFormTarget(
            HttpServletRequest request, Long siteId, NativeFormTargetRequest body, boolean apply) {
        if (body == null || body.formCategoryId() == null)
            throw new IllegalArgumentException("请先确认原生表单导入");
        var project = site(request, siteId, body.menuId());
        var receipt = nativeForms.read(siteId, project.getProjectCode(),
                project.getUiPackageVersion(), body.formCategoryId());
        return apply
                ? prefill.apply(siteId, project.getProjectCode(), project.getUiPackageVersion(),
                        body.menuId(), null, null, receipt, body.expectedTemplate(), body.confirmed())
                : prefill.preview(siteId, project.getProjectCode(), project.getUiPackageVersion(),
                        body.menuId(), null, null, receipt);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.PublicListApplicationService listApplications;

    @GetMapping("/shared-layout/sources")
    public AdminApiResponse<java.util.List<com.xinhongru.cms.template.PublicListApplicationService.LayoutChoice>>
            sharedLayoutSources(HttpServletRequest request, @PathVariable Long siteId,
                    @RequestParam Long menuId, @RequestParam String sourcePath) {
        var project = site(request, siteId, menuId);
        var access = permissions.snapshot(request);
        return AdminApiResponse.success(listApplications.sharedLayouts(siteId, project.getProjectCode(),
                project.getUiPackageVersion(), menuId, sourcePath, access::hasMenuAccess));
    }

    public record SharedLayoutRequest(Long menuId, String sourcePath,
            TemplateQueryBindingService.SharedLayout layout, String expectedId) {}

    @PostMapping("/shared-layout/preview")
    public AdminApiResponse<TemplateQueryBindingService.Snapshot> previewSharedLayout(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody SharedLayoutRequest body) {
        return sharedLayout(request, siteId, body, false);
    }

    @PostMapping("/shared-layout/confirm")
    public AdminApiResponse<TemplateQueryBindingService.Snapshot> confirmSharedLayout(
            HttpServletRequest request, @PathVariable Long siteId, @RequestBody SharedLayoutRequest body) {
        return sharedLayout(request, siteId, body, true);
    }

    private AdminApiResponse<TemplateQueryBindingService.Snapshot> sharedLayout(
            HttpServletRequest request, Long siteId, SharedLayoutRequest body, boolean confirm) {
        if (body == null || body.layout() == null)
            throw new IllegalArgumentException("请选择共享列表版式和目标栏目");
        var project = site(request, siteId, body.menuId());
        String code = project.getProjectCode(), version = project.getUiPackageVersion();
        var source = service.read(siteId, code, version, body.layout().sourceQueryId());
        site(request, siteId, source.menuId());
        if (confirm && !permissions.snapshot(request).canOperate(
                Math.toIntExact(body.menuId()), com.xinhongru.constant.WebConst.UPDATE_AUTHORITY_ID))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权更新当前栏目查询");
        return AdminApiResponse.success(confirm
                ? service.confirmSharedLayout(siteId, code, version, body.menuId(), body.sourcePath(),
                        body.layout(), body.expectedId())
                : service.previewSharedLayout(siteId, code, version, body.menuId(), body.sourcePath(),
                        body.layout()));
    }

    @PostMapping("/plans")
    public AdminApiResponse<TemplateQueryBindingService.Snapshot> confirm(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateQueryBindingService.MappingRequest body) {
        if (body == null || body.menuId() == null)
            throw new IllegalArgumentException("请选择目标栏目与明确控件映射");
        var site = site(request, siteId, body.menuId());
        if (body.presentation() != null && body.presentation().actions() != null)
            for (var action : body.presentation().actions()) {
                if (action == null) throw new IllegalArgumentException("链接动作缺少目标");
                site(request, siteId, action.targetMenuId());
            }
        return AdminApiResponse.success(
                service.confirm(siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    @GetMapping("/plans/{id}")
    public AdminApiResponse<TemplateQueryBindingService.Snapshot> read(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id) {
        var site = project(request, siteId);
        var snapshot = service.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), id);
        site(request, siteId, snapshot.menuId());
        return AdminApiResponse.success(snapshot);
    }

    @GetMapping("/plans/{id}/compiled")
    public AdminApiResponse<QueryTemplateCompiler.Compiled> compiled(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String id) {
        var site = project(request, siteId);
        var snapshot = service.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), id);
        site(request, siteId, snapshot.menuId());
        return AdminApiResponse.success(
                compiler.compile(siteId, site.getProjectCode(), site.getUiPackageVersion(), id));
    }

    private SiteProjectEntity project(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var project = projects.selectById(siteId);
        if (project == null) throw new IllegalArgumentException("站点不存在");
        return project;
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId, Long menuId) {
        var project = project(request, siteId);
        if (menuId == null
                || menuId <= 0
                || menuId > Integer.MAX_VALUE
                || java.util.Objects.equals(project.getRootMenuId(), menuId.intValue()))
            throw new IllegalArgumentException("请选择有效内容列表栏目");
        if (!permissions.snapshot(request).hasMenuAccess(menuId.intValue()))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权配置目标栏目查询");
        return project;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }
}

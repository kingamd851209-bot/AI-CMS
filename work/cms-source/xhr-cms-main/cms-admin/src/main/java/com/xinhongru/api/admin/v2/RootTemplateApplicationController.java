package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.RootTemplateApplicationService;
import com.xinhongru.cms.template.SiteModelPlanService;
import com.xinhongru.cms.template.TemplateFieldPlanService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping(
        "/api/admin/v2/sites/{siteId}/template-seed/field-plans/{planId}/text-binding/public-home")
public class RootTemplateApplicationController {
    public record Request(boolean confirmed, List<String> aliases, String packageVersion) {}

    public record SwitchRequest(
            boolean confirmed, String expectedActiveTemplatePath, String expectedSourceTemplatePath) {}

    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final TemplateFieldPlanService plans;
    private final RootTemplateApplicationService homes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SiteModelPlanService siteModelPlans;

    public RootTemplateApplicationController(
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions,
            TemplateFieldPlanService plans,
            RootTemplateApplicationService homes) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.plans = plans;
        this.homes = homes;
    }

    @GetMapping
    public AdminApiResponse<RootTemplateApplicationService.Options> options(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId) {
        var project = project(request, siteId);
        authorize(
                request,
                project,
                plans.read(siteId, project.getProjectCode(), project.getUiPackageVersion(), planId),
                false);
        return AdminApiResponse.success(
                homes.options(
                        siteId, project.getProjectCode(), project.getUiPackageVersion(), planId));
    }

    @PostMapping
    public AdminApiResponse<RootTemplateApplicationService.Result> apply(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String planId,
            @RequestBody Request body) {
        var project = project(request, siteId);
        authorize(
                request,
                project,
                plans.read(siteId, project.getProjectCode(), project.getUiPackageVersion(), planId),
                true);
        Supplier<RootTemplateApplicationService.Result> write =
                () -> {
                    var current = project(request, siteId);
                    if (!Objects.equals(project.getProjectCode(), current.getProjectCode())
                            || !Objects.equals(
                                    project.getUiPackageVersion(), current.getUiPackageVersion()))
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.CONFLICT,
                                "站点或页面包已变化，请重新读取字段计划");
                    return homes.apply(
                            siteId,
                            project.getProjectCode(),
                            project.getUiPackageVersion(),
                            planId,
                            body == null ? null : body.aliases(),
                            body != null && body.confirmed());
                };
        return AdminApiResponse.success(
                siteModelPlans == null
                        ? write.get()
                        : siteModelPlans.withDerivedPlan(
                                siteId,
                                project.getProjectCode(),
                                project.getUiPackageVersion(),
                                planId,
                                write));
    }

    @PostMapping("/withdraw")
    public AdminApiResponse<RootTemplateApplicationService.Result> withdraw(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String planId,
            @RequestBody Request body) {
        var project = project(request, siteId);
        String version =
                body != null && body.packageVersion() != null
                        ? body.packageVersion()
                        : project.getUiPackageVersion();
        authorize(
                request,
                project,
                plans.readForWithdrawal(siteId, project.getProjectCode(), version, planId),
                true);
        return AdminApiResponse.success(
                homes.withdraw(
                        siteId,
                        project.getProjectCode(),
                        version,
                        planId,
                        body != null && body.confirmed()));
    }

    @GetMapping("/switch")
    public AdminApiResponse<RootTemplateApplicationService.SwitchOptions> switchOptions(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId) {
        var project = project(request, siteId);
        var code = project.getProjectCode();
        var version = project.getUiPackageVersion();
        authorize(request, project, plans.readForWithdrawal(siteId, code, version, planId), false);
        return AdminApiResponse.success(homes.switchOptions(siteId, code, version, planId));
    }

    @PostMapping("/switch")
    public AdminApiResponse<RootTemplateApplicationService.Result> switchVersion(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId,
            @RequestBody SwitchRequest body) {
        var project = project(request, siteId);
        var code = project.getProjectCode();
        var version = project.getUiPackageVersion();
        authorize(request, project, plans.read(siteId, code, version, planId), true);
        Supplier<RootTemplateApplicationService.Result> write =
                () -> homes.switchVersion(siteId, code, version, planId,
                        body.expectedActiveTemplatePath(), body.expectedSourceTemplatePath(),
                        body.confirmed());
        return AdminApiResponse.success(siteModelPlans == null ? write.get()
                : siteModelPlans.withDerivedPlan(siteId, code, version, planId, write));
    }

    @PostMapping("/switch/rollback")
    public AdminApiResponse<RootTemplateApplicationService.Result> rollbackSwitch(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId,
            @RequestBody SwitchRequest body) {
        var project = project(request, siteId);
        var code = project.getProjectCode();
        var version = project.getUiPackageVersion();
        authorize(request, project, plans.readForWithdrawal(siteId, code, version, planId), true);
        return AdminApiResponse.success(homes.rollbackSwitch(siteId, code, version, planId,
                body.expectedActiveTemplatePath(), body.confirmed()));
    }
    private SiteProjectEntity project(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var project = projects.selectById(siteId);
        if (project == null || project.getRootMenuId() == null)
            throw new IllegalArgumentException("站点或真实根栏目不存在");
        return project;
    }

    private void authorize(
            HttpServletRequest request,
            SiteProjectEntity project,
            TemplateFieldPlanService.Plan plan,
            boolean writing) {
        if (plan.pageType() != 0
                || Objects.equals(plan.menuId(), project.getRootMenuId().longValue()))
            throw new IllegalArgumentException("须使用非根单页的原计划，不能替换服务器根身份");
        var permission = permissions.snapshot(request);
        if (!permission.hasMenuAccess(project.getRootMenuId())
                || !permission.hasMenuAccess(Math.toIntExact(plan.menuId()))
                || writing
                        && !permission.canOperate(
                                project.getRootMenuId(),
                                com.xinhongru.constant.WebConst.UPDATE_AUTHORITY_ID))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权更新根模板或读取数据栏目");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalid(
            IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> denied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "无权管理当前站点", null));
    }
}

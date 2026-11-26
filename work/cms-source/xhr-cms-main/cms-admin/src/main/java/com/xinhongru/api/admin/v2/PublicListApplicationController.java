package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.*;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping(
        "/api/admin/v2/sites/{siteId}/template-seed/query-bindings/plans/{queryId}/public-application")
public class PublicListApplicationController {
    public record Request(boolean confirmed, String packageVersion) {}

    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final TemplateQueryBindingService queries;
    private final PublicListApplicationService applications;

    public PublicListApplicationController(
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions,
            TemplateQueryBindingService queries,
            PublicListApplicationService applications) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.queries = queries;
        this.applications = applications;
    }

    @GetMapping("/readiness")
    public AdminApiResponse<PublicListApplicationService.Readiness> readiness(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String queryId) {
        var project = authorizeQuery(request, siteId, queryId, false);
        return AdminApiResponse.success(applications.readiness(
                siteId, project.getProjectCode(), project.getUiPackageVersion(), queryId));
    }

    public record SharedDetailRequest(String expectedToken, boolean confirmed) {}

    @GetMapping("/shared-detail")
    public AdminApiResponse<PublicListApplicationService.SharedDetail> previewSharedDetail(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String queryId) {
        var project = authorizeQuery(request, siteId, queryId, false);
        return AdminApiResponse.success(applications.previewSharedDetail(
                siteId, project.getProjectCode(), project.getUiPackageVersion(), queryId));
    }

    @PostMapping("/shared-detail")
    public AdminApiResponse<PublicListApplicationService.SharedDetail> applySharedDetail(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String queryId,
            @RequestBody SharedDetailRequest body) {
        if (body == null) throw new IllegalArgumentException("请预览并确认共享详情版式");
        var project = authorizeQuery(request, siteId, queryId, true);
        return AdminApiResponse.success(applications.applySharedDetail(
                siteId, project.getProjectCode(), project.getUiPackageVersion(), queryId,
                body.expectedToken(), body.confirmed()));
    }

    @PostMapping("/shared-detail/withdraw")
    public AdminApiResponse<Boolean> withdrawSharedDetail(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String queryId,
            @RequestBody Request body) {
        var project = site(request, siteId);
        String version = body != null && body.packageVersion() != null
                ? body.packageVersion() : project.getUiPackageVersion();
        authorize(request, applications.sharedDetailMenuId(siteId, project.getProjectCode(), version, queryId), true);
        return AdminApiResponse.success(applications.withdrawSharedDetail(
                siteId, project.getProjectCode(), version, queryId, body != null && body.confirmed()));
    }

    private SiteProjectEntity authorizeQuery(HttpServletRequest request, Long siteId, String queryId, boolean writing) {
        var project = site(request, siteId);
        var query = queries.read(siteId, project.getProjectCode(), project.getUiPackageVersion(), queryId);
        authorize(request, query.menuId(), writing);
        if (query.sharedLayout() != null) {
            var source = queries.read(siteId, project.getProjectCode(), project.getUiPackageVersion(),
                    query.sharedLayout().sourceQueryId());
            authorize(request, source.menuId(), false);
        }
        return project;
    }

    @GetMapping
    public AdminApiResponse<PublicListApplicationService.Result> inspect(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String queryId) {
        var site = site(request, siteId);
        authorize(
                request,
                queries.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), queryId)
                        .menuId(),
                false);
        return AdminApiResponse.success(
                applications.inspect(
                        siteId, site.getProjectCode(), site.getUiPackageVersion(), queryId));
    }

    @PostMapping
    public AdminApiResponse<PublicListApplicationService.Result> apply(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String queryId,
            @RequestBody Request body) {
        var site = site(request, siteId);
        authorize(
                request,
                queries.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), queryId)
                        .menuId(),
                true);
        return AdminApiResponse.success(
                applications.apply(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        queryId,
                        body != null && body.confirmed()));
    }

    @PostMapping("/withdraw")
    public AdminApiResponse<PublicListApplicationService.Result> withdraw(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String queryId,
            @RequestBody Request body) {
        var site = site(request, siteId);
        String version =
                body != null && body.packageVersion() != null
                        ? body.packageVersion()
                        : site.getUiPackageVersion();
        authorize(
                request,
                applications.applicationMenuId(siteId, site.getProjectCode(), version, queryId),
                true);
        return AdminApiResponse.success(
                applications.withdraw(
                        siteId,
                        site.getProjectCode(),
                        version,
                        queryId,
                        body != null && body.confirmed()));
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site;
    }

    private void authorize(HttpServletRequest request, Long menuId, boolean writing) {
        var permission = permissions.snapshot(request);
        int id = Math.toIntExact(menuId);
        if (!permission.hasMenuAccess(id)
                || writing
                        && !permission.canOperate(
                                id, com.xinhongru.constant.WebConst.UPDATE_AUTHORITY_ID))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权更新当前栏目公开模板");
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

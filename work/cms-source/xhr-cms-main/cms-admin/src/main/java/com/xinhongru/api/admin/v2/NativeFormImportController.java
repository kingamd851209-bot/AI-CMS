package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.NativeFormImportService;
import com.xinhongru.constant.WebConst;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 原包表单字段审核和原生表单创建。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/admin/v2/sites/{siteId}/native-form-import")
public class NativeFormImportController {
    private final NativeFormImportService forms;
    private final SiteProjectMapper projects;
    private final SiteContextService sites;
    private final AdminPermissionService permissions;

    public NativeFormImportController(
            NativeFormImportService forms,
            SiteProjectMapper projects,
            SiteContextService sites,
            AdminPermissionService permissions) {
        this.forms = forms;
        this.projects = projects;
        this.sites = sites;
        this.permissions = permissions;
    }

    @GetMapping
    public AdminApiResponse<NativeFormImportService.Inspection> inspect(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam Long menuId,
            @RequestParam String sourcePath) {
        var site = authorize(request, siteId, menuId, false);
        return AdminApiResponse.success(
                forms.inspect(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        menuId,
                        sourcePath));
    }

    @PostMapping
    public AdminApiResponse<NativeFormImportService.Receipt> apply(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody NativeFormImportService.Request body) {
        var site = authorize(request, siteId, body.menuId(), true);
        return AdminApiResponse.success(
                forms.apply(siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    private SiteProjectEntity authorize(
            HttpServletRequest request, Long siteId, Long menuId, boolean writing) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        if (menuId == null || menuId <= 0 || menuId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("请选择当前站点的表单目标单页");
        var permission = permissions.snapshot(request);
        int id = menuId.intValue();
        if (!permission.hasMenuAccess(id)
                || writing && !permission.canOperate(id, WebConst.UPDATE_AUTHORITY_ID))
            throw AdminSecurityException.forbidden("CMS_OPERATION_DENIED", "无权配置目标单页表单");
        var site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> denied() {
        return ResponseEntity.status(403).body(new AdminApiResponse<>(403, "无权管理当前站点", null));
    }
}

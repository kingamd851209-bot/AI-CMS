package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.PublicSharedContentCompiler;
import com.xinhongru.cms.template.PublicSharedContentSeedService;
import com.xinhongru.cms.template.SiteModelNavigationService;
import com.xinhongru.constant.WebConst;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.function.Consumer;

@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true")
@RequestMapping(
        "/api/admin/v2/sites/{siteId}/template-seed/site-model/plans/{planId}/shared-content/seed")
public class PublicSharedContentSeedController {
    private final PublicSharedContentSeedService seeds;
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;

    public PublicSharedContentSeedController(
            PublicSharedContentSeedService seeds,
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions) {
        this.seeds = seeds;
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
    }

    @PostMapping("/preview")
    public AdminApiResponse<PublicSharedContentSeedService.Preview> preview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String planId,
            @RequestBody PublicSharedContentCompiler.Request body) {
        return AdminApiResponse.success(
                seeds.preview(scope(request, siteId, body), planId, body, authorize(request)));
    }

    @PostMapping("/confirm")
    public AdminApiResponse<PublicSharedContentSeedService.Preview> confirm(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String planId,
            @RequestBody PublicSharedContentCompiler.Request body) {
        return AdminApiResponse.success(
                seeds.confirm(
                        scope(request, siteId, body), planId, body, request, authorize(request)));
    }

    private SiteModelNavigationService.Scope scope(
            HttpServletRequest request, Long siteId, PublicSharedContentCompiler.Request body) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        if (body == null) throw new IllegalArgumentException("请选择公共信息来源与字段");
        var current = sites.requireCurrentSite(request);
        var project = projects.selectById(siteId);
        if (project == null
                || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(current.getSiteId(), siteId)
                || !Objects.equals(current.getRootMenuId(), project.getRootMenuId())
                || !Objects.equals(body.packageVersion(), project.getUiPackageVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前站点或页面包不匹配");
        return new SiteModelNavigationService.Scope(
                siteId,
                project.getProjectCode(),
                project.getUiPackageVersion(),
                project.getRootMenuId());
    }

    private Consumer<Integer> authorize(HttpServletRequest request) {
        return id -> {
            var snapshot = permissions.snapshot(request);
            if (!snapshot.hasMenuAccess(id)
                    || !snapshot.canOperate(id, WebConst.UPDATE_AUTHORITY_ID))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权维护该公共信息单页");
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalidRequest(
            IllegalArgumentException failure) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, failure.getMessage(), null));
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteContextRequiredException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> missingSite(
            com.xinhongru.site.context.SiteContextRequiredException failure) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, "请先选择站点，再预览或确认公共信息初值", null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> statusFailure(
            ResponseStatusException failure) {
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
                .body(
                        new AdminApiResponse<>(
                                failure.getStatusCode().value(),
                                failure.getReason() == null ? "公共信息初值请求未完成" : failure.getReason(),
                                null));
    }
}

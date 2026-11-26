package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.site.SiteHeadlessAuthService;
import com.xinhongru.service.site.impl.SiteHeadlessAuthServiceImpl;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Headless 站点接入认证配置：每站点一对 appKey/appSecret。
 *
 * <p>仅超级管理员可维护。
 */
@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}/headless-auth")
public class SiteHeadlessAuthController {
    private final SiteHeadlessAuthService service;
    private final SiteContextService siteContextService;

    public SiteHeadlessAuthController(
            SiteHeadlessAuthServiceImpl impl, SiteContextService siteContextService) {
        this.service = impl;
        this.siteContextService = siteContextService;
    }

    @GetMapping
    public AdminApiResponse<SiteHeadlessAuthService.AuthView> get(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(service.get(siteId));
    }

    @PostMapping("/rotate")
    public AdminApiResponse<SiteHeadlessAuthService.AuthView> rotate(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(service.rotate(siteId));
    }

    @PutMapping
    public AdminApiResponse<SiteHeadlessAuthService.AuthView> update(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody UpdateRequest body) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(
                service.update(
                        siteId,
                        body == null ? null : body.enabled(),
                        body == null ? null : body.remark()));
    }

    public record UpdateRequest(Boolean enabled, String remark) {}
}

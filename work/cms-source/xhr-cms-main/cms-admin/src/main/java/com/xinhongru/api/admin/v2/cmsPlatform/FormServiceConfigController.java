package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.cmsPlatform.FormServiceConfigService;
import com.xinhongru.service.cmsPlatform.impl.FormServiceConfigServiceImpl;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表单服务接口（站点服务暴露）的管理侧配置：站点认证 key 与接口文档。
 *
 * <p>仅超级管理员可维护。
 */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/form-service")
public class FormServiceConfigController {
    private final FormServiceConfigService service;
    private final SiteContextService siteContextService;

    public FormServiceConfigController(
            FormServiceConfigServiceImpl impl, SiteContextService siteContextService) {
        this.service = impl;
        this.siteContextService = siteContextService;
    }

    @GetMapping("/key")
    public AdminApiResponse<FormServiceConfigService.KeyView> getKey(
            HttpServletRequest request, @RequestParam Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(service.getKey(siteId));
    }

    @PostMapping("/key")
    public AdminApiResponse<FormServiceConfigService.KeyView> rotateKey(
            HttpServletRequest request, @RequestParam Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(service.rotateKey(siteId));
    }

    @PutMapping("/key")
    public AdminApiResponse<FormServiceConfigService.KeyView> updateKey(
            HttpServletRequest request, @RequestBody UpdateKeyRequest body) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(
                service.updateKey(body.siteId(), body.enabled(), body.remark()));
    }

    @GetMapping("/doc")
    public AdminApiResponse<FormServiceConfigService.DocView> doc(
            HttpServletRequest request, @RequestParam Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(service.doc(siteId));
    }

    public record UpdateKeyRequest(Long siteId, Boolean enabled, String remark) {}
}

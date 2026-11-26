package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.integration.SiteExternalIntegrationService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}/external-integrations")
public class SiteExternalIntegrationsController {
    public record View(
            SiteExternalIntegrationService.Settings settings,
            String mode,
            List<String> diagnostics) {}

    private final SiteExternalIntegrationService service;
    private final SiteContextService sites;

    public SiteExternalIntegrationsController(
            SiteExternalIntegrationService service, SiteContextService sites) {
        this.service = service;
        this.sites = sites;
    }

    @GetMapping
    public AdminApiResponse<View> get(HttpServletRequest request, @PathVariable Long siteId) {
        sites.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(view(siteId));
    }

    @PutMapping
    public AdminApiResponse<View> save(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SiteExternalIntegrationService.Settings settings) {
        sites.assertCanAccessSite(request, siteId);
        service.save(siteId, settings);
        return AdminApiResponse.success(view(siteId));
    }

    private View view(Long siteId) {
        return new View(service.load(siteId), service.mode(), service.diagnostics(siteId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> denied() {
        return ResponseEntity.status(403).body(new AdminApiResponse<>(403, "无权访问该站点接入配置", null));
    }
}

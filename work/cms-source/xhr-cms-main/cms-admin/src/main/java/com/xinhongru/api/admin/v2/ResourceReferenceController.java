package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.ResourceReferenceResolver.Context;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v2/resource-references")
public class ResourceReferenceController {
    private final ResourceReferenceResolver resolver;
    private final SiteContextService sites;

    public ResourceReferenceController(
            ResourceReferenceResolver resolver, SiteContextService sites) {
        this.resolver = resolver;
        this.sites = sites;
    }

    @GetMapping("/context")
    public AdminApiResponse<Context> context(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String siteCode) {
        response.setHeader("Cache-Control", "no-store");
        String code;
        if (siteCode != null) {
            code =
                    siteCode.isBlank()
                            ? null
                            : sites.requireAccessibleSiteCode(request, siteCode).getProjectCode();
        } else if (siteId != null) {
            sites.assertCanAccessSite(request, siteId);
            code = resolver.siteCode(siteId);
        } else {
            var current = sites.current(request);
            if (current.getSiteId() != null)
                sites.assertCanAccessSite(request, current.getSiteId());
            code = current.getSiteCode();
        }
        return AdminApiResponse.success(resolver.context(code));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> accessDenied(
            com.xinhongru.site.context.SiteAccessDeniedException error) {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, error.getMessage(), null));
    }
}

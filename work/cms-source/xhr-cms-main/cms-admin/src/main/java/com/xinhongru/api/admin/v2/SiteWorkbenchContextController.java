package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.SiteWorkbenchContextService;
import com.xinhongru.cms.template.SiteWorkbenchHandoffService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 仅内部工作台的站点约定与可验证交接。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true")
@RequestMapping("/api/admin/v2/sites/{siteId}/workbench-context")
public class SiteWorkbenchContextController {
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final SiteWorkbenchContextService context;
    private final SiteWorkbenchHandoffService handoff;

    public SiteWorkbenchContextController(SiteContextService sites, SiteProjectMapper projects,
            AdminPermissionService permissions, SiteWorkbenchContextService context, SiteWorkbenchHandoffService handoff) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.context = context;
        this.handoff = handoff;
    }

    public record SaveAgents(String content, String expectedDigest) {}

    @GetMapping("/agents")
    public AdminApiResponse<SiteWorkbenchContextService.Document> agents(HttpServletRequest request,
            @PathVariable Long siteId) {
        var site = site(request, siteId);
        return AdminApiResponse.success(context.agents(siteId, site.getProjectCode()));
    }

    @PostMapping("/agents")
    public AdminApiResponse<SiteWorkbenchContextService.Document> save(HttpServletRequest request,
            @PathVariable Long siteId, @RequestBody SaveAgents body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提供站点约定和原文摘要");
        return AdminApiResponse.success(context.saveAgents(siteId, site.getProjectCode(), body.content(), body.expectedDigest()));
    }

    @GetMapping("/handoff")
    public AdminApiResponse<SiteWorkbenchHandoffService.Handoff> handoff(HttpServletRequest request,
            @PathVariable Long siteId) {
        return AdminApiResponse.success(handoff.read(site(request, siteId), false));
    }

    @PostMapping("/export")
    public AdminApiResponse<SiteWorkbenchHandoffService.Handoff> export(HttpServletRequest request,
            @PathVariable Long siteId) {
        return AdminApiResponse.success(handoff.read(site(request, siteId), true));
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        if (!permissions.snapshot(request).isAll())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权读取或维护本站交接与约定");
        var site = projects.selectById(siteId);
        if (site == null || Integer.valueOf(1).equals(site.getDeleted()))
            throw new IllegalArgumentException("站点不存在");
        return site;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new AdminApiResponse<>(400, error.getMessage(), null));
    }
}

package com.xinhongru.api.admin;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextRequiredException;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/site-management")
public class SiteManagementAdminApiController {
    private final AiSiteProjectService projectService;
    private final SiteContextService siteContextService;

    public SiteManagementAdminApiController(
            AiSiteProjectService projectService, SiteContextService siteContextService) {
        this.projectService = projectService;
        this.siteContextService = siteContextService;
    }

    @GetMapping("/sites")
    public AdminApiResponse<List<SiteManagementDto>> sites() {
        return AdminApiResponse.success(
                projectService.listSites().stream().map(SiteManagementDto::from).toList());
    }

    @PostMapping("/sites")
    public AdminApiResponse<SiteManagementDto> createSite(
            HttpServletRequest httpRequest,
            @RequestBody AiSiteProjectService.CreateSiteRequest request) {
        siteContextService.requireSuperAdmin(httpRequest);
        return AdminApiResponse.success(SiteManagementDto.from(projectService.createSite(request)));
    }

    @PutMapping("/sites/{siteId}")
    public ResponseEntity<AdminApiResponse<Void>> updateSite(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(
                        new AdminApiResponse<>(
                                501, "site profile update is not implemented yet", null));
    }

    @PostMapping("/sites/{siteId}/switch-current")
    public AdminApiResponse<SiteContext> switchCurrent(
            HttpServletRequest request, @PathVariable Long siteId) {
        return AdminApiResponse.success(siteContextService.switchTo(request, siteId));
    }

    @PostMapping("/sites/{siteId}/bind-root")
    public AdminApiResponse<SiteManagementDto> bindRoot(
            HttpServletRequest httpRequest,
            @PathVariable Long siteId,
            @RequestBody BindRootRequest request) {
        siteContextService.requireSuperAdmin(httpRequest);
        Integer rootMenuId = request == null ? null : request.rootMenuId();
        return AdminApiResponse.success(
                SiteManagementDto.from(projectService.bindRootMenu(siteId, rootMenuId)));
    }

    @DeleteMapping("/sites/{siteId}")
    public AdminApiResponse<SiteManagementDto> deleteSite(
            HttpServletRequest httpRequest, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(httpRequest);
        SiteProjectEntity deleted = projectService.deleteSite(siteId);
        siteContextService.clear(httpRequest);
        return AdminApiResponse.success(SiteManagementDto.from(deleted));
    }

    @PostMapping("/sites/{siteId}/template-package/clear")
    public AdminApiResponse<SiteManagementDto> clearTemplatePackage(
            HttpServletRequest httpRequest, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(httpRequest);
        return AdminApiResponse.success(
                SiteManagementDto.from(projectService.clearTemplatePackage(siteId)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdminApiResponse<>(409, ex.getMessage(), null));
    }

    @ExceptionHandler(SiteContextRequiredException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleSiteContextRequired(
            SiteContextRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleAccessDenied(SiteAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AdminApiResponse<>(403, ex.getMessage(), null));
    }

    public record BindRootRequest(Integer rootMenuId) {}

    public record SiteManagementDto(
            Long siteId,
            String projectName,
            String projectCode,
            String siteDomain,
            Integer rootMenuId,
            String templatePackageStatus,
            String templatePackageName,
            Long lastRecognitionBatchId,
            String applyStatus,
            String previewStatus,
            String status,
            Long currentBatchId,
            String siteMode) {
        static SiteManagementDto from(SiteProjectEntity site) {
            return new SiteManagementDto(
                    site.getId(),
                    site.getProjectName(),
                    site.getProjectCode(),
                    site.getSiteDomain(),
                    site.getRootMenuId(),
                    site.getTemplatePackageStatus(),
                    site.getTemplatePackageName(),
                    site.getLastRecognitionBatchId(),
                    site.getApplyStatus(),
                    site.getPreviewStatus(),
                    site.getStatus(),
                    site.getCurrentBatchId(),
                    site.getSiteMode() == null || site.getSiteMode().isBlank()
                            ? "CMS_MANAGED"
                            : site.getSiteMode());
        }
    }
}

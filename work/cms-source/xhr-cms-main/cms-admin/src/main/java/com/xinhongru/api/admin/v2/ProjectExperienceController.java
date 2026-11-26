package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.TemplateContentUnitsService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 项目内人工判断的可追溯查看与启停，不接运营内容更新。 */
@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/admin/v2/sites/{siteId}/project-experiences")
public class ProjectExperienceController {
    private final TemplateContentUnitsService units;
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final boolean experienceEnabled;

    public ProjectExperienceController(
            TemplateContentUnitsService units,
            SiteContextService sites,
            SiteProjectMapper projects,
            @org.springframework.beans.factory.annotation.Value(
                            "${cms.workbench.experience.enabled:false}")
                    boolean experienceEnabled) {
        this.units = units;
        this.sites = sites;
        this.projects = projects;
        this.experienceEnabled = experienceEnabled;
    }

    public record View(
            List<TemplateContentUnitsService.Correction> history,
            List<TemplateContentUnitsService.ExperienceReference> references,
            int total,
            int offset,
            int activeCount,
            List<TemplateContentUnitsService.Archive> archives,
            String referenceWarning,
            boolean experienceEnabled) {}

    public record StatusRequest(
            String packageVersion, String sourcePath, String id, long version, boolean enabled) {}

    @GetMapping
    public AdminApiResponse<View> get(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam String sourcePath,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit) {
        var site = site(request, siteId);
        var history =
                units.historyPage(
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        sourcePath,
                        offset,
                        limit);
        List<TemplateContentUnitsService.ExperienceReference> references;
        String referenceWarning = null;
        try {
            references =
                    experienceEnabled
                            ? units.references(
                                    site.getProjectCode(), site.getUiPackageVersion(), sourcePath)
                            : List.of();
            if (!experienceEnabled) referenceWarning = "经验暂停记录与参考；既有历史保留可查看管理";
        } catch (TemplateContentUnitsService.ExperienceCapacityException exceeded) {
            references = List.of();
            referenceWarning = "项目经验候选未加载，历史仍可管理：" + exceeded.getMessage();
        }
        return AdminApiResponse.success(
                new View(
                        history.items(),
                        references,
                        history.total(),
                        history.offset(),
                        history.activeCount(),
                        history.archives(),
                        referenceWarning,
                        experienceEnabled));
    }

    public record ArchiveRequest(String sourcePath) {}

    @PostMapping("/archive")
    public AdminApiResponse<TemplateContentUnitsService.Archive> archive(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody ArchiveRequest body) {
        var site = site(request, siteId);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        if (body == null || body.sourcePath() == null) throw new IllegalArgumentException("请选择页面");
        return AdminApiResponse.success(
                units.archiveHistory(
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        body.sourcePath(),
                        user.getId().longValue()));
    }

    @PutMapping
    public AdminApiResponse<Void> status(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody StatusRequest body) {
        var site = site(request, siteId);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        if (body == null) throw new IllegalArgumentException("请选择经验");
        units.setExperienceEnabled(
                site.getProjectCode(),
                body.packageVersion(),
                body.sourcePath(),
                body.id(),
                body.version(),
                body.enabled(),
                user.getId().longValue());
        return AdminApiResponse.success(null);
    }

    private SiteProjectEntity site(HttpServletRequest request, Long id) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, id);
        var site = projects.selectById(id);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site;
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> denied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "只有开发管理员可以管理项目内经验", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalid(
            IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> unavailable(
            IllegalStateException error) {
        return org.springframework.http.ResponseEntity.status(503)
                .body(new AdminApiResponse<>(503, error.getMessage(), null));
    }
}

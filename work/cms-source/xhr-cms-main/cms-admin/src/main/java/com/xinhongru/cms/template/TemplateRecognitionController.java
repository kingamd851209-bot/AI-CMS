package com.xinhongru.cms.template;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 普通模板文件维护保留；旧模型识别、自动保存及远程探测入口已停用。 */
@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}")
public class TemplateRecognitionController {
    @Autowired private SiteContextService siteContextService;
    private final TemplateRecognitionService templateService;
    private final SiteProjectMapper projectMapper;

    public TemplateRecognitionController(
            TemplateRecognitionService templateService, SiteProjectMapper projectMapper) {
        this.templateService = templateService;
        this.projectMapper = projectMapper;
    }

    @ModelAttribute
    public void authorizeSite(@PathVariable Long siteId, HttpServletRequest request) {
        siteContextService.assertCanAccessSite(request, siteId);
    }

    @GetMapping("/template-files/common")
    public AdminApiResponse<List<TemplateRecognitionService.TemplateFileInfo>>
            getCommonTemplateFiles(@PathVariable Long siteId) {
        return AdminApiResponse.success(templateService.getCommonTemplateFiles(siteId));
    }

    @GetMapping("/template-files")
    public AdminApiResponse<List<TemplateRecognitionService.TemplateFileInfo>> getTemplateFiles(
            @PathVariable Long siteId, @RequestParam(required = false) String templateType) {
        return AdminApiResponse.success(templateService.getTemplateFiles(siteId, templateType));
    }

    @GetMapping("/column-template-dir")
    public AdminApiResponse<String> getColumnTemplateDir(
            @PathVariable Long siteId, @RequestParam Long columnId) {
        return AdminApiResponse.success(templateService.buildContentTemplateDir(columnId));
    }

    @PostMapping({
        "/recognize-common-template",
        "/recognize-content-template",
        "/template-recognition-prompt",
        "/template-repair-prompt"
    })
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> retiredModelEntry() {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @GetMapping("/template-ai-health")
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> retiredHealthProbe() {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @PostMapping("/template-content")
    public AdminApiResponse<String> saveTemplateContent(
            @PathVariable Long siteId, @RequestBody Map<String, Object> request) {
        String templatePath = (String) request.get("templatePath");
        String content = (String) request.get("content");
        String savedPath = templateService.saveTemplateContent(siteId, templatePath, content);
        return AdminApiResponse.success(savedPath);
    }

    @GetMapping("/template-content")
    public AdminApiResponse<String> getTemplateContent(
            @PathVariable Long siteId, @RequestParam String templatePath) {
        return templateService
                .getTemplateContent(siteId, templatePath)
                .map(AdminApiResponse::success)
                .orElse(AdminApiResponse.success(null));
    }

    @GetMapping("/info")
    public AdminApiResponse<SiteInfo> getSiteInfo(@PathVariable Long siteId) {
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) return AdminApiResponse.success(null);
        return AdminApiResponse.success(
                new SiteInfo(site.getId(), site.getProjectName(), site.getProjectCode()));
    }

    public record SiteInfo(Long id, String projectName, String projectCode) {}
}

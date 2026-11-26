package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.cmsPlatform.SiteSettingsService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 站点设置：栏目上线配置（301/302/多语言回退/跨站映射）+ Excel 导入导出 */
@RestController
@RequestMapping("/api/admin/v2/cms/site-settings")
public class SiteSettingsController {
    private final SiteSettingsService service;

    public SiteSettingsController(SiteSettingsService service) {
        this.service = service;
    }

    @GetMapping("/children")
    public AdminApiResponse<List<SiteSettingsService.ColumnConfigDto>> children(
            @RequestParam Long siteId, @RequestParam Integer parentColumnId) {
        return AdminApiResponse.success(service.childrenConfigs(siteId, parentColumnId));
    }

    @GetMapping("/config")
    public AdminApiResponse<SiteSettingsService.SettingDto> config(
            @RequestParam Long siteId, @RequestParam Integer columnId, @RequestParam String kind) {
        return AdminApiResponse.success(service.get(siteId, columnId, kind));
    }

    @PostMapping("/save")
    public AdminApiResponse<Boolean> save(@RequestBody Map<String, Object> body) {
        return AdminApiResponse.success(
                service.save(
                        Long.valueOf(String.valueOf(body.get("siteId"))),
                        Integer.valueOf(String.valueOf(body.get("columnId"))),
                        str(body.get("columnName")),
                        body.get("parentId") == null
                                ? 0
                                : Integer.valueOf(String.valueOf(body.get("parentId"))),
                        str(body.get("kind")),
                        str(body.get("pageUrl")),
                        str(body.get("redirect301")),
                        str(body.get("redirect302")),
                        str(body.get("fallbackLang")),
                        str(body.get("crossMappingJson"))));
    }

    @GetMapping("/export-template")
    public void exportTemplate(
            HttpServletResponse response,
            @RequestParam Long siteId,
            @RequestParam Integer parentColumnId)
            throws Exception {
        service.writeTemplateXlsx(response, siteId, parentColumnId);
    }

    @GetMapping("/export")
    public void export(
            HttpServletResponse response,
            @RequestParam Long siteId,
            @RequestParam Integer parentColumnId)
            throws Exception {
        service.writeExportXlsx(response, siteId, parentColumnId);
    }

    @PostMapping("/import")
    public AdminApiResponse<Integer> importExcel(
            @RequestParam("file") MultipartFile file, @RequestParam Long siteId) {
        try {
            return AdminApiResponse.success(service.importXlsx(file, siteId));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    private String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}

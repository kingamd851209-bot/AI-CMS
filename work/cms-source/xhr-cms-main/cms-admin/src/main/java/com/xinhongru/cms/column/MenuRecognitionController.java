package com.xinhongru.cms.column;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 旧浏览器识别入口已停用；不再构造Prompt、读取页面或创建模型事件流。 */
@RestController
@RequestMapping("/api/admin/v2")
public class MenuRecognitionController {
    private final SiteContextService sites;

    public MenuRecognitionController(SiteContextService sites) {
        this.sites = sites;
    }

    @GetMapping("/generate-prompt")
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> generatePrompt(
            @RequestParam Long siteId, HttpServletRequest request) {
        sites.assertCanAccessSite(request, siteId);
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @PostMapping("/recognize-menu")
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> recognizeMenu(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long siteId = Long.valueOf(String.valueOf(body.get("siteId")));
        sites.assertCanAccessSite(request, siteId);
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }
}

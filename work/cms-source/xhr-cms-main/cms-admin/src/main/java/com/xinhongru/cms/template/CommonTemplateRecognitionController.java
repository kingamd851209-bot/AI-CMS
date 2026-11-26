package com.xinhongru.cms.template;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 无剩余消费者的旧公共模板模型入口，不再构造提示或提交模型任务。 */
@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}/common-template")
public class CommonTemplateRecognitionController {
    private final SiteContextService sites;

    public CommonTemplateRecognitionController(SiteContextService sites) {
        this.sites = sites;
    }

    @ModelAttribute
    public void authorizeSite(@PathVariable Long siteId, HttpServletRequest request) {
        sites.assertCanAccessSite(request, siteId);
    }

    @PostMapping({"/recognition-prompt", "/recognize"})
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> retired() {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }
}

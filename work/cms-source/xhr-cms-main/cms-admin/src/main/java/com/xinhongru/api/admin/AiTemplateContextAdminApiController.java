package com.xinhongru.api.admin;

import com.xinhongru.ai.context.AiTemplateContext;
import com.xinhongru.ai.context.AiTemplateContextService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai/template-context")
public class AiTemplateContextAdminApiController {
    private final AiTemplateContextService templateContextService;

    public AiTemplateContextAdminApiController(AiTemplateContextService templateContextService) {
        this.templateContextService = templateContextService;
    }

    @GetMapping("/{menuId}")
    public AdminApiResponse<AiTemplateContext> load(
            @PathVariable Integer menuId,
            @RequestParam(defaultValue = "0") Integer legacyFieldSettingType) {
        return AdminApiResponse.success(
                templateContextService.load(menuId, legacyFieldSettingType));
    }
}

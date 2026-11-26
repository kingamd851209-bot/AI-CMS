package com.xinhongru.api.admin;

import com.xinhongru.ai.config.AiProviderConfigService;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/admin/ai/providers", "/api/admin/v2/ai/providers"})
public class AiProviderConfigAdminApiController {
    private final AiProviderConfigService providerConfigService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.site.context.SiteContextService sites;

    private void authorize(jakarta.servlet.http.HttpServletRequest request) {
        sites.requireSuperAdmin(request);
    }

    public AiProviderConfigAdminApiController(AiProviderConfigService providerConfigService) {
        this.providerConfigService = providerConfigService;
    }

    private boolean isGeneration(AiProviderConfigService.ProviderConfigView item) {
        return AiProviderConfigService.GENERATION_USAGE.equalsIgnoreCase(
                        item.usageType() == null ? "" : item.usageType().trim())
                || "SYSTEM_GENERATION"
                        .equalsIgnoreCase(
                                item.providerCode() == null ? "" : item.providerCode().trim());
    }

    @GetMapping
    public AdminApiResponse<List<AiProviderConfigService.ProviderConfigView>> list(
            jakarta.servlet.http.HttpServletRequest http) {
        authorize(http);
        return AdminApiResponse.success(
                providerConfigService.list().stream().filter(item -> !isGeneration(item)).toList());
    }

    @PostMapping
    public AdminApiResponse<AiProviderConfigService.ProviderConfigView> save(
            @RequestBody AiProviderConfigService.SaveRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        authorize(http);
        if (request != null
                && request.usageType() != null
                && (AiProviderConfigService.GENERATION_USAGE.equalsIgnoreCase(
                                request.usageType().trim())
                        || "SYSTEM_GENERATION"
                                .equalsIgnoreCase(
                                        request.providerCode() == null
                                                ? ""
                                                : request.providerCode().trim())))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "普通模型请使用统一系统AI配置入口");
        return AdminApiResponse.success(providerConfigService.save(request));
    }

    @PostMapping("/{id}/test")
    public AdminApiResponse<AiProviderConfigService.TestResult> test(
            @PathVariable Long id, jakarta.servlet.http.HttpServletRequest http) {
        authorize(http);
        if (providerConfigService.list().stream()
                .anyMatch(item -> item.id().equals(id) && isGeneration(item)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "普通模型请使用统一系统AI配置入口");
        return AdminApiResponse.success(providerConfigService.test(id));
    }

    @PostMapping("/{id}/disable")
    public AdminApiResponse<Void> disable(
            @PathVariable Long id, jakarta.servlet.http.HttpServletRequest http) {
        authorize(http);
        if (providerConfigService.list().stream()
                .anyMatch(item -> item.id().equals(id) && isGeneration(item)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "普通模型请使用统一系统AI配置入口");
        providerConfigService.disable(id);
        return AdminApiResponse.success();
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> denied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "只有平台管理员可以维护AI配置", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AdminApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return new AdminApiResponse<>(400, safeMessage(ex), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public AdminApiResponse<Void> handleIllegalState(IllegalStateException ex) {
        return new AdminApiResponse<>(500, safeMessage(ex), null);
    }

    private String safeMessage(RuntimeException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "AI Provider 配置处理失败";
        }
        return "AI Provider配置处理失败，请检查配置和后端存储";
    }
}

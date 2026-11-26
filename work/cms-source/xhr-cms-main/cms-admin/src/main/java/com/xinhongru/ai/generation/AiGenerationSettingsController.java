package com.xinhongru.ai.generation;

import com.xinhongru.ai.config.AiProviderConfigService;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/admin/v2/ai-generation/settings")
public class AiGenerationSettingsController {
    private final AiProviderConfigService providers;
    private final SpringAiTextGenerator generator;
    private final SiteContextService sites;

    public AiGenerationSettingsController(
            AiProviderConfigService providers,
            SpringAiTextGenerator generator,
            SiteContextService sites) {
        this.providers = providers;
        this.generator = generator;
        this.sites = sites;
    }

    @GetMapping
    public AdminApiResponse<AiProviderConfigService.GenerationView> get(
            HttpServletRequest request) {
        sites.requireSuperAdmin(request);
        return AdminApiResponse.success(view());
    }

    @PostMapping
    public AdminApiResponse<AiProviderConfigService.GenerationView> save(
            @RequestBody AiProviderConfigService.GenerationRequest body,
            HttpServletRequest request) {
        sites.requireSuperAdmin(request);
        if (body == null) throw new IllegalArgumentException("AI配置不能为空");
        if (providers.generationSnapshot() == null
                && !body.clearApiKey()
                && (body.apiKey() == null || body.apiKey().isBlank())) {
            var initial = generator.configurationSnapshot();
            body =
                    new AiProviderConfigService.GenerationRequest(
                            body.enabled(),
                            body.baseUrl(),
                            body.modelName(),
                            initial.apiKey(),
                            false,
                            body.timeoutMs(),
                            body.maxRetries());
        }
        return AdminApiResponse.success(providers.saveGeneration(body));
    }

    @PostMapping("/test")
    public AdminApiResponse<AiProviderConfigService.TestResult> test(HttpServletRequest request) {
        sites.requireSuperAdmin(request);
        return AdminApiResponse.success(providers.testGeneration());
    }

    private AiProviderConfigService.GenerationView view() {
        var saved = providers.generationView();
        if (saved != null) return saved;
        var initial = generator.configurationSnapshot();
        return new AiProviderConfigService.GenerationView(
                initial.enabled(),
                initial.baseUrl(),
                initial.modelName(),
                initial.configured(),
                initial.timeoutMs(),
                initial.maxRetries(),
                "ENVIRONMENT");
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> denied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "只有平台管理员可以维护AI配置", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AdminApiResponse<Void> invalid() {
        return new AdminApiResponse<>(400, "AI配置无效，请检查地址、模型、超时及清除选项", null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public AdminApiResponse<Void> unavailable() {
        return new AdminApiResponse<>(500, "AI配置保存或读取失败，请检查后端密钥加密配置和存储", null);
    }
}

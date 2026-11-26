package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.cmsPlatform.AiPageGenerationService;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 内容中台 AI 页面生成 */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/ai-page")
public class AiPageGenerationController {

    public AiPageGenerationController(AiPageGenerationService service) {}

    @ResponseStatus(org.springframework.http.HttpStatus.GONE)
    @PostMapping("/generate")
    public AdminApiResponse<AiPageGenerationService.AiResult> generate(
            @RequestBody AiPageGenerationService.AiRequest request) {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @ResponseStatus(org.springframework.http.HttpStatus.GONE)
    @PostMapping("/save")
    public AdminApiResponse<String> save(@RequestBody Map<String, String> body) {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }
}

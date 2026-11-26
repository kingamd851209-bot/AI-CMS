package com.xinhongru.cms.template;

import com.xinhongru.api.admin.AdminApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 旧AI修复公开令牌入口退役；不签发令牌、不读取或渲染资源。 */
@RestController
@RequestMapping("/api/public/template-access")
public class TemplatePublicAccessController {
    @RequestMapping({"", "/**"})
    @ResponseStatus(HttpStatus.GONE)
    public AdminApiResponse<Void> retired() {
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }
}

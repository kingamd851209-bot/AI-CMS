package com.xinhongru.ai.agent.opencode;

import com.xinhongru.api.admin.AdminApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 旧开发入口退役响应；不再转发或管理进程。 */
@RestController
@RequestMapping("/api/admin/v2/opencode")
public class OpenCodeController {
    @RequestMapping({"", "/**"})
    public ResponseEntity<AdminApiResponse<Void>> retired() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new AdminApiResponse<>(410, "该开发入口已停用", null));
    }
}

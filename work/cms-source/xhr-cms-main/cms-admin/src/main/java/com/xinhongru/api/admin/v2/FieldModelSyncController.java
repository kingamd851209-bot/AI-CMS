package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.fieldsync.FieldModelSyncService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 字段模型同步：从目标站点拉取字段设置配置到当前站点对应栏目。 */
@RestController
@RequestMapping("/api/admin/v2/cms/field-model-sync")
public class FieldModelSyncController {
    private final FieldModelSyncService fieldModelSyncService;

    public FieldModelSyncController(FieldModelSyncService fieldModelSyncService) {
        this.fieldModelSyncService = fieldModelSyncService;
    }

    @GetMapping("/targets")
    public AdminApiResponse<List<FieldModelSyncService.TargetSite>> targets(
            HttpServletRequest request) {
        return AdminApiResponse.success(fieldModelSyncService.listTargets(request));
    }

    @GetMapping("/preview")
    public AdminApiResponse<FieldModelSyncService.Preview> preview(
            HttpServletRequest request, @RequestParam Long targetSiteId) {
        return AdminApiResponse.success(fieldModelSyncService.preview(request, targetSiteId));
    }

    @PostMapping("/apply")
    public AdminApiResponse<FieldModelSyncService.ApplyResult> apply(
            HttpServletRequest request, @RequestBody ApplyRequest body) {
        if (body == null || body.targetSiteId() == null) {
            throw new IllegalArgumentException("targetSiteId is required");
        }
        return AdminApiResponse.success(
                fieldModelSyncService.apply(request, body.targetSiteId(), body.mappings()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalid(
            IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    public record ApplyRequest(Long targetSiteId, List<FieldModelSyncService.Mapping> mappings) {}
}

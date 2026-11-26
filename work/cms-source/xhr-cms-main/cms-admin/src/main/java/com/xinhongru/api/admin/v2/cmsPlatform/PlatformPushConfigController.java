package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.PlatformPushConfigEntity;
import com.xinhongru.service.cmsPlatform.PlatformPushConfigService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 内容中台 推送配置（产品/新闻分类推送多个站点，各自字段映射与目标状态） */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/push-config")
public class PlatformPushConfigController {
    private final PlatformPushConfigService service;

    public PlatformPushConfigController(PlatformPushConfigService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public AdminApiResponse<List<PlatformPushConfigEntity>> list(
            @RequestParam String configType, @RequestParam Integer categoryId) {
        return AdminApiResponse.success(service.list(configType, categoryId));
    }

    @GetMapping("/{id}")
    public AdminApiResponse<PlatformPushConfigEntity> detail(@PathVariable Long id) {
        return AdminApiResponse.success(service.getById(id));
    }

    @PostMapping
    public AdminApiResponse<PlatformPushConfigEntity> create(
            @RequestBody PlatformPushConfigEntity entity) {
        try {
            return AdminApiResponse.success(service.create(entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping("/{id}")
    public AdminApiResponse<Boolean> update(
            @PathVariable Long id, @RequestBody PlatformPushConfigEntity entity) {
        try {
            entity.setId(id);
            return AdminApiResponse.success(service.update(entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping("/{id}")
    public AdminApiResponse<Boolean> delete(@PathVariable Long id) {
        return AdminApiResponse.success(service.delete(id));
    }
}

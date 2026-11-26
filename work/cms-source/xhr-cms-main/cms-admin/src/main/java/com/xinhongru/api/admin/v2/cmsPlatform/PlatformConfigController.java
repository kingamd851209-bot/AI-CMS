package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.PlatformOneshotEntity;
import com.xinhongru.service.cmsPlatform.PlatformDataType;
import com.xinhongru.service.cmsPlatform.PlatformOneshotService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台分类配置：产品配置 / 新闻配置 / 解决方案配置 / 案例配置 共用一个控制器， 分类与元数据统一存放 t_platform_oneshot，按 dataType（URL 模块段）隔离。
 * URL 与改造前保持一致（/cms-platform/{product|news|solution|case}-category/...）。
 */
@RestController
public class PlatformConfigController {
    private static final String BASE =
            "/api/admin/v2/cms-platform/{module:product|news|solution|case}-category";

    private final PlatformOneshotService service;

    public PlatformConfigController(PlatformOneshotService service) {
        this.service = service;
    }

    @GetMapping(BASE + "/tree")
    public AdminApiResponse<List<PlatformOneshotEntity>> tree(
            @PathVariable String module, @RequestParam(defaultValue = "0") long parentId) {
        return AdminApiResponse.success(service.tree(PlatformDataType.ofModule(module), parentId));
    }

    @GetMapping(BASE + "/all")
    public AdminApiResponse<List<PlatformOneshotEntity>> all(@PathVariable String module) {
        return AdminApiResponse.success(service.all(PlatformDataType.ofModule(module)));
    }

    @GetMapping(BASE + "/{id}")
    public AdminApiResponse<PlatformOneshotEntity> detail(
            @PathVariable String module, @PathVariable Integer id) {
        return AdminApiResponse.success(service.getById(PlatformDataType.ofModule(module), id));
    }

    @PostMapping(BASE)
    public AdminApiResponse<PlatformOneshotEntity> create(
            @PathVariable String module, @RequestBody PlatformOneshotEntity entity) {
        try {
            return AdminApiResponse.success(
                    service.create(PlatformDataType.ofModule(module), entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping(BASE + "/{id}")
    public AdminApiResponse<Boolean> update(
            @PathVariable String module,
            @PathVariable Integer id,
            @RequestBody PlatformOneshotEntity entity) {
        try {
            entity.setId(id);
            return AdminApiResponse.success(
                    service.update(PlatformDataType.ofModule(module), entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping(BASE + "/{id}")
    public AdminApiResponse<Integer> delete(@PathVariable String module, @PathVariable Integer id) {
        return AdminApiResponse.success(
                service.deleteCascade(PlatformDataType.ofModule(module), id));
    }

    @PutMapping(BASE + "/{id}/sort")
    public AdminApiResponse<Boolean> updateSort(
            @PathVariable String module, @PathVariable Integer id, @RequestParam int sort) {
        return AdminApiResponse.success(
                service.updateSort(PlatformDataType.ofModule(module), id, sort));
    }
}

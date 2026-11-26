package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.PlatformContentEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.PlatformPermission;
import com.xinhongru.service.cmsPlatform.PlatformContentService;
import com.xinhongru.service.cmsPlatform.PlatformDataType;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台内容中心：产品中心 / 新闻中心 / 解决方案 / 案例中心 共用一个控制器， 内容统一存放 t_platform_contents，按 dataType（URL
 * 模块段）隔离；按类别做数据中台权限校验。 URL 与改造前保持一致（/cms-platform/{product|news|solution|case}-center/...）。
 */
@RestController
public class PlatformCenterController {
    private static final String BASE =
            "/api/admin/v2/cms-platform/{module:product|news|solution|case}-center";

    private final PlatformContentService service;
    private final AdminPermissionService permissionService;

    public PlatformCenterController(
            PlatformContentService service, AdminPermissionService permissionService) {
        this.service = service;
        this.permissionService = permissionService;
    }

    @GetMapping(BASE + "/contents")
    public AdminApiResponse<Map<String, Object>> contents(
            @PathVariable String module,
            HttpServletRequest request,
            @RequestParam Map<String, String> allParams) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        Integer classId = toInt(allParams.get("classId"));
        permissionService.requirePlatformOperate(
                request, dataType.permissionType(), classId, PlatformPermission.OP_VIEW);
        int page = toInt(allParams.getOrDefault("page", "1"));
        int size = toInt(allParams.getOrDefault("size", "10"));
        String statusRaw = allParams.get("status");
        Integer status = (statusRaw == null || statusRaw.isBlank()) ? null : toInt(statusRaw);
        String keyword = allParams.get("keyword");
        Map<String, Object> filters = new HashMap<>();
        Set<String> reserved = Set.of("classId", "page", "size", "status", "keyword");
        allParams.forEach(
                (k, v) -> {
                    if (!reserved.contains(k)) {
                        filters.put(k, v);
                    }
                });
        return AdminApiResponse.success(
                Map.of(
                        "list",
                        service.listByClass(
                                dataType, classId, page, size, status, keyword, filters),
                        "total",
                        service.countByClass(dataType, classId, status, keyword, filters),
                        "page",
                        page,
                        "size",
                        size));
    }

    @GetMapping(BASE + "/{id}")
    public AdminApiResponse<PlatformContentEntity> detail(
            @PathVariable String module, HttpServletRequest request, @PathVariable Integer id) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        PlatformContentEntity entity = service.getById(dataType, id);
        permissionService.requirePlatformOperate(
                request,
                dataType.permissionType(),
                entity == null ? null : entity.getClassid(),
                PlatformPermission.OP_VIEW);
        return AdminApiResponse.success(entity);
    }

    @PostMapping(BASE)
    public AdminApiResponse<PlatformContentEntity> create(
            @PathVariable String module,
            HttpServletRequest request,
            @RequestBody PlatformContentEntity entity) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        permissionService.requirePlatformOperate(
                request, dataType.permissionType(), entity.getClassid(), PlatformPermission.OP_ADD);
        try {
            return AdminApiResponse.success(service.create(dataType, entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping(BASE + "/{id}")
    public AdminApiResponse<Boolean> update(
            @PathVariable String module,
            HttpServletRequest request,
            @PathVariable Integer id,
            @RequestBody PlatformContentEntity entity) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        PlatformContentEntity existing = service.getById(dataType, id);
        permissionService.requirePlatformOperate(
                request,
                dataType.permissionType(),
                existing == null ? null : existing.getClassid(),
                PlatformPermission.OP_EDIT);
        try {
            entity.setId(id);
            return AdminApiResponse.success(service.update(dataType, entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping(BASE + "/{id}")
    public AdminApiResponse<Boolean> delete(
            @PathVariable String module, HttpServletRequest request, @PathVariable Integer id) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        PlatformContentEntity existing = service.getById(dataType, id);
        permissionService.requirePlatformOperate(
                request,
                dataType.permissionType(),
                existing == null ? null : existing.getClassid(),
                PlatformPermission.OP_DELETE);
        return AdminApiResponse.success(service.delete(dataType, id));
    }

    @PostMapping(BASE + "/batch-option")
    public AdminApiResponse<Integer> batchOption(
            @PathVariable String module,
            HttpServletRequest request,
            @RequestParam(required = false) Integer classId,
            @RequestParam List<Integer> ids,
            @RequestParam String option,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String note) {
        PlatformDataType dataType = PlatformDataType.ofModule(module);
        Integer optionId = PlatformPermission.optionIdOfBatchOption(option);
        if (optionId == null) {
            return new AdminApiResponse<>(500, "不支持的操作：" + option, null);
        }
        permissionService.requirePlatformOperate(
                request, dataType.permissionType(), classId, optionId);
        return AdminApiResponse.success(
                service.batchOption(dataType, classId, ids, option, status, note));
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}

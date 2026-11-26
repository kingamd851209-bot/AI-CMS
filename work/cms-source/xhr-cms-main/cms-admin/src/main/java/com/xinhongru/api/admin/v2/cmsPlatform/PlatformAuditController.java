package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.PlatformPermission;
import com.xinhongru.service.cmsPlatform.PlatformAuditService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 内容中台 审批（写共享审核表 t_check_record） */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/audit")
public class PlatformAuditController {
    private final PlatformAuditService service;
    private final AdminPermissionService permissionService;

    public PlatformAuditController(
            PlatformAuditService service, AdminPermissionService permissionService) {
        this.service = service;
        this.permissionService = permissionService;
    }

    public record AuditBody(
            String configType,
            Integer categoryId,
            List<Integer> ids,
            Integer status,
            String note) {}

    @PostMapping
    public AdminApiResponse<Integer> audit(
            HttpServletRequest request, @RequestBody AuditBody body) {
        // 审核/审批通过权限按 configType 映射的类别校验（form 不在范围内，放行）
        String type = PlatformPermission.typeOfConfigType(body.configType());
        if (type != null) {
            permissionService.requirePlatformOperate(
                    request, type, body.categoryId(), PlatformPermission.OP_APPROVE);
        }
        try {
            UserEntity user = TaleUtils.getLoginUser(request);
            return AdminApiResponse.success(
                    service.audit(
                            body.configType(),
                            body.categoryId(),
                            body.ids(),
                            body.status(),
                            body.note(),
                            user));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }
}

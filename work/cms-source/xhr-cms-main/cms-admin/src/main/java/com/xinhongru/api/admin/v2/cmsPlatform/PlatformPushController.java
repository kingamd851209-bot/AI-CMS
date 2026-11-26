package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.PlatformPushConfigEntity;
import com.xinhongru.entity.PlatformPushRecordEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.PlatformPermission;
import com.xinhongru.service.cmsPlatform.PlatformPushConfigService;
import com.xinhongru.service.cmsPlatform.PlatformPushService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 内容中台 一键推送执行 + 推送记录；一键分发按推送配置所属类别做数据中台权限校验。 */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/push")
public class PlatformPushController {
    private final PlatformPushService service;
    private final PlatformPushConfigService pushConfigService;
    private final AdminPermissionService permissionService;

    public PlatformPushController(
            PlatformPushService service,
            PlatformPushConfigService pushConfigService,
            AdminPermissionService permissionService) {
        this.service = service;
        this.pushConfigService = pushConfigService;
        this.permissionService = permissionService;
    }

    public record PushRequest(String configType, Long pushConfigId, List<Integer> contentIds) {}

    @PostMapping("/execute")
    public AdminApiResponse<Integer> execute(
            HttpServletRequest request, @RequestBody PushRequest req) {
        // 先核配置类别权限；表单实际来源、站点与关联目标授权由service统一校验。
        PlatformPushConfigEntity config =
                req.pushConfigId() == null ? null : pushConfigService.getById(req.pushConfigId());
        String type =
                PlatformPermission.typeOfConfigType(
                        config != null ? config.getConfigType() : req.configType());
        if (type != null) {
            permissionService.requirePlatformOperate(
                    request,
                    type,
                    config == null ? null : config.getCategoryId(),
                    PlatformPermission.OP_PUSH);
        }
        try {
            UserEntity user = TaleUtils.getLoginUser(request);
            return AdminApiResponse.success(
                    service.push(
                            req.configType(), req.pushConfigId(), req.contentIds(), user, request));
        } catch (com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @GetMapping("/records")
    public AdminApiResponse<List<PlatformPushRecordEntity>> records(
            HttpServletRequest request,
            @RequestParam String configType,
            @RequestParam Integer sourceContentId) {
        return AdminApiResponse.success(service.listRecords(configType, sourceContentId, request));
    }
}

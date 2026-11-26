package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.cmsPlatform.StaticAssetService.AssetScope;
import com.xinhongru.service.cmsPlatform.StaticAssetSyncService;
import com.xinhongru.service.cmsPlatform.StaticAssetSyncService.SyncResult;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 静态资源同步：将源作用域（全局或某站点）资源复制到当前站点指定作用域目录。 */
@RestController
@RequestMapping("/api/admin/v2/static-assets/sync")
public class StaticAssetSyncController {
    private final StaticAssetSyncService syncService;

    public StaticAssetSyncController(StaticAssetSyncService syncService) {
        this.syncService = syncService;
    }

    public record SyncCopyRequest(
            String sourceScope,
            Long sourceSiteId,
            List<String> sourcePaths,
            String targetScope,
            Long targetSiteId,
            String targetPath) {}

    @PostMapping("/copy")
    public AdminApiResponse<SyncResult> copy(
            HttpServletRequest request, @RequestBody SyncCopyRequest body) {
        AssetScope sourceScope = StaticAssetSyncService.parseScope(body.sourceScope());
        AssetScope targetScope = StaticAssetSyncService.parseScope(body.targetScope());
        if (targetScope != AssetScope.GLOBAL) {
            if (body.targetSiteId() == null) {
                throw new IllegalArgumentException("请指定目标站点");
            }
        }
        if (sourceScope != AssetScope.GLOBAL) {
            if (body.sourceSiteId() == null) {
                throw new IllegalArgumentException("请指定源站点");
            }
        }
        return AdminApiResponse.success(
                syncService.copy(
                        sourceScope,
                        body.sourceSiteId(),
                        body.sourcePaths(),
                        targetScope,
                        body.targetSiteId(),
                        body.targetPath()));
    }
}

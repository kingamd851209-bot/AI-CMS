package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 内容中台 推送设置所需的站点/站点栏目目录 */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/site-catalog")
public class CmsPlatformSiteCatalogController {
    private final SiteContextService siteContextService;
    private final OneShotMapper oneShotMapper;

    public CmsPlatformSiteCatalogController(
            SiteContextService siteContextService, OneShotMapper oneShotMapper) {
        this.siteContextService = siteContextService;
        this.oneShotMapper = oneShotMapper;
    }

    @GetMapping("/sites")
    public AdminApiResponse<List<Map<String, Object>>> sites(HttpServletRequest request) {
        List<Map<String, Object>> result = new ArrayList<>();
        siteContextService
                .listAvailableSites(request)
                .forEach(
                        s ->
                                result.add(
                                        Map.of(
                                                "id", s.getId(),
                                                "name", s.getProjectName(),
                                                "code", s.getProjectCode(),
                                                "rootMenuId",
                                                        s.getRootMenuId() == null
                                                                ? 0
                                                                : s.getRootMenuId())));
        return AdminApiResponse.success(result);
    }

    @GetMapping("/columns")
    public AdminApiResponse<List<OneshotEntity>> columns(@RequestParam Long siteId) {
        return AdminApiResponse.success(oneShotMapper.selectBySiteProjectId(siteId));
    }
}

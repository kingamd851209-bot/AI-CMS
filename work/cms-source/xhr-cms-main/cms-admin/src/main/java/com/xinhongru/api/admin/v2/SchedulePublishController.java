package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.SchedulePublishEntity;
import com.xinhongru.entity.SchedulePublishHistoryEntity;
import com.xinhongru.service.cmsPlatform.SchedulePublishService;

import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 站点内容 定时发布管理（本轮为配置管理页面，调度执行后续接入） */
@RestController
@RequestMapping("/api/admin/v2/cms/schedule-publish")
public class SchedulePublishController {
    private final SchedulePublishService service;

    private final SiteContextService sites;

    public SchedulePublishController(SchedulePublishService service, SiteContextService sites) {
        this.sites = sites;
        this.service = service;
    }

    @GetMapping("/list")
    public AdminApiResponse<List<SchedulePublishEntity>> list(
            HttpServletRequest request, @RequestParam Long siteId, @RequestParam(required = false) String keyword) {
        return AdminApiResponse.success(service.list(requireRequestedSite(request, siteId), keyword));
    }

    @GetMapping("/{id}")
    public AdminApiResponse<SchedulePublishEntity> detail(HttpServletRequest request, @PathVariable Long id) {
        return AdminApiResponse.success(service.getById(sites.requireCurrentSiteId(request), id));
    }

    @PostMapping
    public AdminApiResponse<SchedulePublishEntity> create(
            HttpServletRequest request, @RequestParam Long siteId, @RequestBody SchedulePublishEntity entity) {
        long currentSite = requireRequestedSite(request, siteId);
        try {
            return AdminApiResponse.success(service.create(currentSite, entity));
        } catch (IllegalArgumentException ex) {
            return new AdminApiResponse<>(400, ex.getMessage(), null);
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping("/{id}")
    public AdminApiResponse<Boolean> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody SchedulePublishEntity entity) {
        long currentSite = requireRequestedSite(request, entity.getSiteId());
        try {
            entity.setId(id);
            return AdminApiResponse.success(service.update(currentSite, entity));
        } catch (IllegalArgumentException ex) {
            return new AdminApiResponse<>(400, ex.getMessage(), null);
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping("/{id}")
    public AdminApiResponse<Boolean> delete(HttpServletRequest request, @PathVariable Long id) {
        return AdminApiResponse.success(service.delete(sites.requireCurrentSiteId(request), id));
    }

    @PutMapping("/{id}/enabled")
    public AdminApiResponse<Boolean> toggleEnabled(
            HttpServletRequest request, @PathVariable Long id, @RequestParam Integer enabled) {
        return AdminApiResponse.success(service.toggleEnabled(sites.requireCurrentSiteId(request), id, enabled));
    }

    @GetMapping("/{id}/history")
    public AdminApiResponse<List<SchedulePublishHistoryEntity>> history(HttpServletRequest request, @PathVariable Long id) {
        return AdminApiResponse.success(service.history(sites.requireCurrentSiteId(request), id));
    }

    private long requireRequestedSite(HttpServletRequest request, Long requestedSite) {
        long current = sites.requireCurrentSiteId(request);
        if (requestedSite != null && requestedSite != current) {
            throw new SiteAccessDeniedException("请选择任务所属站点");
        }
        return current;
    }

    @GetMapping("/validate")
    public AdminApiResponse<Boolean> validate(@RequestParam String cron) {
        return AdminApiResponse.success(service.validateCron(cron));
    }
}

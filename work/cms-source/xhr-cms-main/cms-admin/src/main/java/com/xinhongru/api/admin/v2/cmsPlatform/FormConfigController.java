package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.NativeFormImportService;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.service.cmsPlatform.FormConfigService;
import com.xinhongru.service.cmsPlatform.impl.FormConfigServiceImpl;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/** 表单配置：表单分类树 + 元数据配置（按站点隔离，site_project_id=站点ID） */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/form-category")
public class FormConfigController {
    private final FormConfigService service;

    private final SiteContextService sites;

    public FormConfigController(FormConfigServiceImpl impl, SiteContextService sites) {
        this.service = impl;
        this.sites = sites;
    }

    private FormOneshotEntity category(HttpServletRequest request, Integer id) {
        var row = service.getById(id);
        if (row == null || Boolean.TRUE.equals(row.getIsDeleted()))
            throw new IllegalArgumentException("表单分类不存在");
        sites.assertCanAccessSite(request, row.getSiteProjectId());
        return row;
    }

    private void parent(HttpServletRequest request, Long siteId, Integer parentId) {
        if (parentId != null
                && parentId > 0
                && !Objects.equals(category(request, parentId).getSiteProjectId(), siteId))
            throw new IllegalArgumentException("父分类不属于该站点");
    }

    @GetMapping("/tree")
    public AdminApiResponse<List<FormOneshotEntity>> tree(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") long parentId,
            @RequestParam Long siteId) {
        sites.assertCanAccessSite(request, siteId);
        parent(request, siteId, Math.toIntExact(parentId));
        return AdminApiResponse.success(service.tree(parentId, siteId));
    }

    @GetMapping("/all")
    public AdminApiResponse<List<FormOneshotEntity>> all(
            HttpServletRequest request, @RequestParam Long siteId) {
        sites.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(service.all(siteId));
    }

    @GetMapping("/{id}")
    public AdminApiResponse<FormOneshotEntity> detail(
            HttpServletRequest request, @PathVariable Integer id) {
        return AdminApiResponse.success(category(request, id));
    }

    @PostMapping
    public AdminApiResponse<FormOneshotEntity> create(
            HttpServletRequest request,
            @RequestParam Long siteId,
            @RequestBody FormOneshotEntity entity) {
        sites.assertCanAccessSite(request, siteId);
        parent(request, siteId, entity.getParentId());
        try {
            return AdminApiResponse.success(service.create(siteId, entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping("/{id}")
    public AdminApiResponse<Boolean> update(
            HttpServletRequest request,
            @PathVariable Integer id,
            @RequestBody FormOneshotEntity entity) {
        var existing = category(request, id);
        parent(request, existing.getSiteProjectId(), entity.getParentId());
        entity.setSiteProjectId(existing.getSiteProjectId());
        entity.setSiteRootId(existing.getSiteRootId());
        if (NativeFormImportService.importedIdentity(existing.getIdentifies()))
            entity.setIdentifies(existing.getIdentifies());
        try {
            entity.setId(id);
            return AdminApiResponse.success(service.update(entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping("/{id}")
    public AdminApiResponse<Integer> delete(HttpServletRequest request, @PathVariable Integer id) {
        category(request, id);
        return AdminApiResponse.success(service.deleteCascade(id));
    }

    @PutMapping("/{id}/sort")
    public AdminApiResponse<Boolean> updateSort(
            HttpServletRequest request, @PathVariable Integer id, @RequestParam int sort) {
        category(request, id);
        return AdminApiResponse.success(service.updateSort(id, sort));
    }

    @ExceptionHandler(com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> denied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "无权访问该站点表单", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalid(
            IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }
}

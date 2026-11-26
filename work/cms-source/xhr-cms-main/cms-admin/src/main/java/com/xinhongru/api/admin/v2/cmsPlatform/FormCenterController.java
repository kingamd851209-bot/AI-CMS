package com.xinhongru.api.admin.v2.cmsPlatform;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.FormContentEntity;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.service.cmsPlatform.CrossSiteContentService;
import com.xinhongru.service.cmsPlatform.FormConfigService;
import com.xinhongru.service.cmsPlatform.impl.FormCenterServiceImpl;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 表单内容：按站点下所选表单分类（class_id）列出内容 + 批量操作 */
@RestController
@RequestMapping("/api/admin/v2/cms-platform/form-center")
public class FormCenterController {
    private final CrossSiteContentService<FormContentEntity> service;

    private final FormConfigService categories;
    private final SiteContextService sites;

    public FormCenterController(
            FormCenterServiceImpl impl, FormConfigService categories, SiteContextService sites) {
        this.service = impl;
        this.categories = categories;
        this.sites = sites;
    }

    private void category(HttpServletRequest request, Integer id) {
        FormOneshotEntity row = id == null || id <= 0 ? null : categories.getById(id);
        if (row == null || Boolean.TRUE.equals(row.getIsDeleted()))
            throw new IllegalArgumentException("表单分类不存在");
        sites.assertCanAccessSite(request, row.getSiteProjectId());
    }

    private FormContentEntity record(HttpServletRequest request, Integer id) {
        FormContentEntity row = service.getById(id);
        if (row == null) throw new IllegalArgumentException("留言不存在");
        category(request, row.getClassid());
        return row;
    }

    @GetMapping("/contents")
    public AdminApiResponse<Map<String, Object>> contents(
            HttpServletRequest request, @RequestParam Map<String, String> allParams) {
        Integer classId = toInt(allParams.get("classId"));
        category(request, classId);
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
                        service.listByClass(classId, page, size, status, keyword, filters),
                        "total",
                        service.countByClass(classId, status, keyword, filters),
                        "page",
                        page,
                        "size",
                        size));
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    @GetMapping("/{id}")
    public AdminApiResponse<FormContentEntity> detail(
            HttpServletRequest request, @PathVariable Integer id) {
        return AdminApiResponse.success(record(request, id));
    }

    @PostMapping
    public AdminApiResponse<FormContentEntity> create(
            HttpServletRequest request, @RequestBody FormContentEntity entity) {
        category(request, entity.getClassid());
        try {
            return AdminApiResponse.success(service.create(entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @PutMapping("/{id}")
    public AdminApiResponse<Boolean> update(
            HttpServletRequest request,
            @PathVariable Integer id,
            @RequestBody FormContentEntity entity) {
        var existing = record(request, id);
        if (entity.getClassid() != null
                && !Objects.equals(entity.getClassid(), existing.getClassid()))
            throw new IllegalArgumentException("不能改变留言所属分类");
        entity.setClassid(existing.getClassid());
        try {
            entity.setId(id);
            return AdminApiResponse.success(service.update(entity));
        } catch (RuntimeException ex) {
            return new AdminApiResponse<>(500, ex.getMessage(), null);
        }
    }

    @DeleteMapping("/{id}")
    public AdminApiResponse<Boolean> delete(HttpServletRequest request, @PathVariable Integer id) {
        record(request, id);
        return AdminApiResponse.success(service.delete(id));
    }

    @PostMapping("/batch-option")
    public AdminApiResponse<Integer> batchOption(
            HttpServletRequest request,
            @RequestParam(required = false) Integer classId,
            @RequestParam List<Integer> ids,
            @RequestParam String option,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String note) {
        if (ids == null || ids.isEmpty() || ids.size() > 1000)
            throw new IllegalArgumentException("请选择1至1000条留言");
        if (classId != null) category(request, classId);
        for (Integer id : ids) {
            var row = record(request, id);
            if (classId != null && !Objects.equals(row.getClassid(), classId))
                throw new IllegalArgumentException("留言不属于指定分类");
        }
        return AdminApiResponse.success(service.batchOption(classId, ids, option, status, note));
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

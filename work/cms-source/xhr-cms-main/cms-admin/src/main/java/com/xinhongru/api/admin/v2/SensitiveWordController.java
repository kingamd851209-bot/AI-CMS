package com.xinhongru.api.admin.v2;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.SensitiveWordEntity;
import com.xinhongru.mapper.SensitiveWordMapper;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/v2/sensitive-word")
public class SensitiveWordController {
    @Autowired private SensitiveWordMapper sensitiveWordMapper;
    @Autowired private SiteProjectMapper aiSiteProjectMapper;

    @GetMapping
    public AdminApiResponse<List<ListItem>> list(
            @RequestParam(required = false) String word,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer level) {
        QueryWrapper<SensitiveWordEntity> qw =
                new QueryWrapper<SensitiveWordEntity>().eq("deleted", 0);
        if (word != null && !word.isBlank()) {
            qw.like("word", word.trim());
        }
        if (action != null && !action.isBlank()) {
            qw.eq("action", action.trim());
        }
        if (status != null) {
            qw.eq("status", status);
        }
        if (level != null) {
            qw.eq("level", level);
        }
        qw.orderByDesc("create_time");
        List<SensitiveWordEntity> list = sensitiveWordMapper.selectList(qw);
        Map<Long, String> siteNames = getSiteNames();
        List<ListItem> result = new ArrayList<>();
        for (SensitiveWordEntity e : list) {
            result.add(toListItem(e, siteNames));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping
    public AdminApiResponse<SensitiveWordEntity> create(@RequestBody SensitiveWordEntity entity) {
        normalizeScope(entity);
        if (!"global".equals(entity.getScope())
                && (entity.getSiteIds() == null || entity.getSiteIds().isBlank())) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        entity.setLevel(entity.getLevel() != null ? entity.getLevel() : 1);
        entity.setAction(entity.getAction() != null ? entity.getAction() : "reject");
        sensitiveWordMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/{id}")
    public AdminApiResponse<SensitiveWordEntity> update(
            @PathVariable Long id, @RequestBody SensitiveWordEntity entity) {
        normalizeScope(entity);
        if (!"global".equals(entity.getScope())
                && (entity.getSiteIds() == null || entity.getSiteIds().isBlank())) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        sensitiveWordMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/{id}")
    public AdminApiResponse<Void> delete(@PathVariable Long id) {
        SensitiveWordEntity entity = sensitiveWordMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            sensitiveWordMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    /** 归一化作用范围：scope 为空时默认 site；global 时清空 site_ids */
    private void normalizeScope(SensitiveWordEntity entity) {
        if (entity.getScope() == null || entity.getScope().isBlank()) {
            entity.setScope("site");
        }
        entity.setScope(entity.getScope().trim().toLowerCase());
        if ("global".equals(entity.getScope())) {
            entity.setSiteIds(null);
        }
    }

    private ListItem toListItem(SensitiveWordEntity e, Map<Long, String> siteNames) {
        boolean global = "global".equals(e.getScope());
        String siteIds = global ? "" : effectiveSiteIds(e);
        String siteNamesStr = global ? "全局" : joinSiteNames(siteIds, siteNames);
        return new ListItem(
                e.getId(),
                e.getWord(),
                e.getAction(),
                e.getReplacement(),
                e.getLevel(),
                e.getScope(),
                siteIds,
                siteNamesStr,
                e.getStatus(),
                e.getCreateTime());
    }

    private String effectiveSiteIds(SensitiveWordEntity e) {
        if (e.getSiteIds() != null && !e.getSiteIds().isBlank()) {
            return e.getSiteIds().trim();
        }
        return "";
    }

    private String joinSiteNames(String siteIds, Map<Long, String> siteNames) {
        if (siteIds == null || siteIds.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String id : siteIds.split(",")) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                String name = siteNames.get(Long.valueOf(trimmed));
                names.add(name != null && !name.isEmpty() ? name : trimmed);
            } catch (NumberFormatException ex) {
                names.add(trimmed);
            }
        }
        return String.join("、", names);
    }

    private Map<Long, String> getSiteNames() {
        List<SiteProjectEntity> sites =
                aiSiteProjectMapper.selectList(
                        new QueryWrapper<SiteProjectEntity>().eq("deleted", 0));
        return sites.stream()
                .collect(
                        Collectors.toMap(
                                SiteProjectEntity::getId,
                                s -> s.getProjectName() != null ? s.getProjectName() : "",
                                (a, b) -> a));
    }

    public record ListItem(
            Long id,
            String word,
            String action,
            String replacement,
            Integer level,
            String scope,
            String siteIds,
            String siteNames,
            Integer status,
            LocalDateTime createTime) {}
}

package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinhongru.entity.PlatformContentEntity;
import com.xinhongru.mapper.PlatformContentMapper;
import com.xinhongru.service.cmsPlatform.PlatformContentService;
import com.xinhongru.service.cmsPlatform.PlatformDataType;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 平台内容通用实现：所有类别共用 t_platform_contents，按 data_type 隔离。 */
@Service
public class PlatformContentServiceImpl implements PlatformContentService {
    /** 允许作为动态搜索条件的列白名单（对应 modelSearch 可用字段） */
    private static final Set<String> SEARCHABLE_COLUMNS =
            Set.of(
                    "title",
                    "tags",
                    "single_line1",
                    "single_line2",
                    "single_line3",
                    "single_line4",
                    "single_line5",
                    "single_line6",
                    "single_line7",
                    "single_line8",
                    "single_line9",
                    "single_line10",
                    "single_line11",
                    "single_line12",
                    "single_line13",
                    "single_line14",
                    "single_line15",
                    "single_line16",
                    "single_line17",
                    "single_line18",
                    "single_line19",
                    "single_line20",
                    "note1",
                    "note2",
                    "note3",
                    "note4",
                    "note5",
                    "note6",
                    "note7",
                    "note8",
                    "note9",
                    "note10",
                    "note11",
                    "note12",
                    "note13",
                    "note14",
                    "note15");

    private final PlatformContentMapper contentMapper;

    public PlatformContentServiceImpl(PlatformContentMapper contentMapper) {
        this.contentMapper = contentMapper;
    }

    @Override
    public List<PlatformContentEntity> listByClass(
            PlatformDataType dataType,
            Integer classId,
            int page,
            int size,
            Integer status,
            String keyword,
            Map<String, Object> filters) {
        QueryWrapper<PlatformContentEntity> qw =
                baseQuery(dataType, classId, status, keyword, filters);
        qw.orderByAsc("sort").orderByAsc("id");
        Page<PlatformContentEntity> result =
                contentMapper.selectPage(new Page<>(Math.max(page, 1), Math.max(size, 1)), qw);
        return result.getRecords();
    }

    @Override
    public long countByClass(
            PlatformDataType dataType,
            Integer classId,
            Integer status,
            String keyword,
            Map<String, Object> filters) {
        return contentMapper.selectCount(baseQuery(dataType, classId, status, keyword, filters));
    }

    private QueryWrapper<PlatformContentEntity> baseQuery(
            PlatformDataType dataType,
            Integer classId,
            Integer status,
            String keyword,
            Map<String, Object> filters) {
        QueryWrapper<PlatformContentEntity> qw =
                new QueryWrapper<PlatformContentEntity>()
                        .eq("data_type", dataType.code())
                        .eq("class_id", classId)
                        .eq("deleted", 0);
        if (status != null) {
            qw.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like("title", keyword);
        }
        applySearchFilters(qw, filters);
        return qw;
    }

    private void applySearchFilters(
            QueryWrapper<PlatformContentEntity> qw, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String col = camelToSnake(entry.getKey());
            if (!SEARCHABLE_COLUMNS.contains(col)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            String raw = String.valueOf(value).trim();
            List<String> multiValues = parseMultiValue(raw);
            if (multiValues.size() > 1) {
                // 多选字段：命中任一即匹配（列名来自白名单，值走参数绑定）
                qw.and(
                        w -> {
                            for (int i = 0; i < multiValues.size(); i++) {
                                if (i > 0) {
                                    w.or();
                                }
                                w.apply(col + " LIKE {0}", "%," + multiValues.get(i) + ",%");
                            }
                        });
            } else {
                qw.like(col, raw);
            }
        }
    }

    /** 解析多选字段的 ,a,b, 形式取值；非该形式返回空列表。 多选值以两侧逗号包裹存储，因此可用 LIKE '%,a,%' 精确匹配单个值。 */
    private List<String> parseMultiValue(String raw) {
        if (raw.length() < 3 || !raw.startsWith(",") || !raw.endsWith(",")) {
            return List.of();
        }
        String inner = raw.substring(1, raw.length() - 1);
        if (inner.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    public PlatformContentEntity getById(PlatformDataType dataType, Integer id) {
        if (id == null) {
            return null;
        }
        PlatformContentEntity entity = contentMapper.selectById(id);
        if (entity == null
                || entity.getDataType() == null
                || entity.getDataType() != dataType.code()) {
            return null;
        }
        return entity;
    }

    @Override
    public PlatformContentEntity create(PlatformDataType dataType, PlatformContentEntity entity) {
        entity.setDataType(dataType.code());
        if (entity.getStatus() == null) {
            entity.setStatus(0);
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        if (entity.getHits() == null) {
            entity.setHits(0);
        }
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        contentMapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(PlatformDataType dataType, PlatformContentEntity entity) {
        if (entity.getId() == null) {
            return false;
        }
        // 更新时限定 data_type，避免跨类别误改
        return contentMapper.update(
                        entity,
                        new UpdateWrapper<PlatformContentEntity>()
                                .eq("id", entity.getId())
                                .eq("data_type", dataType.code()))
                == 1;
    }

    @Override
    public boolean delete(PlatformDataType dataType, Integer id) {
        if (id == null) {
            return false;
        }
        return contentMapper.update(
                        null,
                        new UpdateWrapper<PlatformContentEntity>()
                                .set("deleted", 1)
                                .eq("id", id)
                                .eq("data_type", dataType.code()))
                == 1;
    }

    @Override
    public int batchOption(
            PlatformDataType dataType,
            Integer classId,
            List<Integer> ids,
            String option,
            Integer status,
            String note) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        UpdateWrapper<PlatformContentEntity> uw = new UpdateWrapper<>();
        uw.in("id", ids).eq("data_type", dataType.code());
        if (classId != null) {
            uw.eq("class_id", classId);
        }
        switch (option == null ? "" : option) {
            case "publish" -> uw.set("status", 3);
            case "draft" -> uw.set("status", 0);
            case "audit" -> uw.set("status", 1);
            case "approve" -> uw.set("status", status == null ? 2 : status);
            case "top" -> uw.set("menu_top", 1);
            case "recommend" -> uw.set("menu_elite", 1);
            case "delete" -> uw.set("deleted", 1);
            default -> {
                return 0;
            }
        }
        return contentMapper.update(null, uw);
    }
}

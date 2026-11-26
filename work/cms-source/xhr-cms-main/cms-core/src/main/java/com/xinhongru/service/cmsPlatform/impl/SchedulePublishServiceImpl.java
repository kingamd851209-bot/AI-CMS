package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinhongru.entity.SchedulePublishEntity;
import com.xinhongru.entity.SchedulePublishHistoryEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.SchedulePublishHistoryMapper;
import com.xinhongru.mapper.SchedulePublishMapper;
import com.xinhongru.service.cmsPlatform.SchedulePublishService;

import jakarta.annotation.Resource;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
public class SchedulePublishServiceImpl implements SchedulePublishService {
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Resource private OneShotMapper oneShotMapper;
    @Resource private SchedulePublishMapper schedulePublishMapper;
    @Resource private SchedulePublishHistoryMapper historyMapper;

    @Override
    public List<SchedulePublishEntity> list(Long siteId, String keyword) {
        QueryWrapper<SchedulePublishEntity> qw =
                new QueryWrapper<SchedulePublishEntity>()
                        .eq("site_id", siteId)
                        .eq("deleted", 0);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(
                    w ->
                            w.like("cron_expression", keyword.trim())
                                    .or()
                                    .like("cron_description", keyword.trim())
                                    .or()
                                    .like("remark", keyword.trim()));
        }
        qw.orderByDesc("id");
        return schedulePublishMapper.selectList(qw);
    }

    @Override
    public SchedulePublishEntity getById(Long siteId, Long id) {
        return schedulePublishMapper.selectOne(new QueryWrapper<SchedulePublishEntity>()
                .eq("site_id", siteId).eq("id", id).eq("deleted", 0));
    }

    @Override
    public SchedulePublishEntity create(Long siteId, SchedulePublishEntity entity) {
        entity.setColumnIds(requireSiteColumns(siteId, entity.getColumnIds()));
        LocalDateTime now = LocalDateTime.now();
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        entity.setId(null);
        entity.setSiteId(siteId);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        schedulePublishMapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(Long siteId, SchedulePublishEntity entity) {
        String columnIds = entity.getColumnIds() == null
                ? null : requireSiteColumns(siteId, entity.getColumnIds());
        var changes = scopedUpdate(siteId, entity.getId())
                .set(entity.getCronExpression() != null, "cron_expression", entity.getCronExpression())
                .set(entity.getCronDescription() != null, "cron_description", entity.getCronDescription())
                .set(columnIds != null, "column_ids", columnIds)
                .set(entity.getColumnNames() != null, "column_names", entity.getColumnNames())
                .set(entity.getRemark() != null, "remark", entity.getRemark())
                .set(entity.getEnabled() != null, "enabled", entity.getEnabled());
        return schedulePublishMapper.update(null, changes) == 1;
    }

    private String requireSiteColumns(Long siteId, String columnIds) {
        if (columnIds == null || columnIds.isBlank())
            throw new IllegalArgumentException("请选择栏目范围");
        JsonNode values;
        try {
            values = JSON.readTree(columnIds);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("栏目范围必须是栏目编号的 JSON 数组");
        }
        if (!values.isArray() || values.isEmpty())
            throw new IllegalArgumentException("栏目范围必须是非空的栏目编号数组");
        var ids = new LinkedHashSet<Integer>();
        for (JsonNode value : values) {
            if (!value.isInt() || value.intValue() <= 0 || !ids.add(value.intValue()))
                throw new IllegalArgumentException("栏目范围须包含互不重复的正整数编号");
        }
        var ordered = List.copyOf(ids);
        var menus = oneShotMapper.getModelByIds(ordered);
        if (menus.size() != ids.size()
                || menus.stream().anyMatch(menu -> !Objects.equals(siteId, menu.getSiteProjectId())))
            throw new IllegalArgumentException("栏目范围包含已删除或不属于当前站点的栏目");
        return JSON.writeValueAsString(ordered);
    }

    private UpdateWrapper<SchedulePublishEntity> scopedUpdate(Long siteId, Long id) {
        return new UpdateWrapper<SchedulePublishEntity>()
                .eq("site_id", siteId).eq("id", id).eq("deleted", 0)
                .set("update_time", LocalDateTime.now());
    }

    @Override
    public boolean delete(Long siteId, Long id) {
        return schedulePublishMapper.update(
                        null,
                        scopedUpdate(siteId, id).set("deleted", 1))
                == 1;
    }

    @Override
    public boolean toggleEnabled(Long siteId, Long id, Integer enabled) {
        return schedulePublishMapper.update(
                        null,
                        scopedUpdate(siteId, id).set("enabled", enabled == null || enabled == 0 ? 0 : 1))
                == 1;
    }

    @Override
    public List<SchedulePublishHistoryEntity> history(Long siteId, Long scheduleId) {
        if (getById(siteId, scheduleId) == null) return List.of();
        return historyMapper.selectList(
                new QueryWrapper<SchedulePublishHistoryEntity>()
                        .eq("schedule_id", scheduleId)
                        .eq("deleted", 0)
                        .orderByDesc("run_time")
                        .orderByDesc("id"));
    }

    @Override
    public boolean validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        String expr = cron.trim();
        // Spring CronExpression 为 6 字段（秒在前），标准 5 字段格式补秒位后校验
        if (expr.split("\\s+").length == 5) {
            expr = "0 " + expr;
        }
        return CronExpression.isValidExpression(expr);
    }
}

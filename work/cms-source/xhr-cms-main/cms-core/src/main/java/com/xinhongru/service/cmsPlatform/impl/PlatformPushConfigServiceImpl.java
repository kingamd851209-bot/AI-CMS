package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinhongru.entity.PlatformPushConfigEntity;
import com.xinhongru.mapper.PlatformPushConfigMapper;
import com.xinhongru.service.cmsPlatform.PlatformPushConfigService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlatformPushConfigServiceImpl implements PlatformPushConfigService {
    @Resource private PlatformPushConfigMapper mapper;

    @Override
    public List<PlatformPushConfigEntity> list(String configType, Integer categoryId) {
        return mapper.selectList(
                new QueryWrapper<PlatformPushConfigEntity>()
                        .eq("config_type", configType)
                        .eq("category_id", categoryId)
                        .eq("deleted", 0)
                        .orderByAsc("id"));
    }

    @Override
    public PlatformPushConfigEntity getById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public PlatformPushConfigEntity create(PlatformPushConfigEntity entity) {
        if (entity.getDeleted() == null) {
            entity.setDeleted(0);
        }
        if (entity.getTargetStatus() == null) {
            entity.setTargetStatus(0);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        mapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(PlatformPushConfigEntity entity) {
        entity.setUpdateTime(LocalDateTime.now());
        return mapper.updateById(entity) == 1;
    }

    @Override
    public boolean delete(Long id) {
        return mapper.update(
                        null,
                        new UpdateWrapper<PlatformPushConfigEntity>()
                                .set("deleted", 1)
                                .eq("id", id))
                == 1;
    }
}

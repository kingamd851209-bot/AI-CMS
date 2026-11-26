package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinhongru.entity.PlatformOneshotEntity;
import com.xinhongru.mapper.PlatformOneshotMapper;
import com.xinhongru.service.cmsPlatform.PlatformDataType;
import com.xinhongru.service.cmsPlatform.PlatformOneshotService;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 平台分类/元数据配置通用实现：所有类别共用 t_platform_oneshot，按 data_type 隔离。 */
@Service
public class PlatformOneshotServiceImpl implements PlatformOneshotService {
    private final PlatformOneshotMapper oneshotMapper;

    public PlatformOneshotServiceImpl(PlatformOneshotMapper oneshotMapper) {
        this.oneshotMapper = oneshotMapper;
    }

    @Override
    public List<PlatformOneshotEntity> tree(PlatformDataType dataType, long parentId) {
        return oneshotMapper.selectList(
                new QueryWrapper<PlatformOneshotEntity>()
                        .eq("data_type", dataType.code())
                        .eq("parent_id", parentId)
                        .eq("is_deleted", 0)
                        .orderByAsc("sort")
                        .orderByAsc("id"));
    }

    @Override
    public List<PlatformOneshotEntity> all(PlatformDataType dataType) {
        return oneshotMapper.selectList(
                new QueryWrapper<PlatformOneshotEntity>()
                        .eq("data_type", dataType.code())
                        .eq("is_deleted", 0)
                        .orderByAsc("sort")
                        .orderByAsc("id"));
    }

    @Override
    public PlatformOneshotEntity getById(PlatformDataType dataType, Integer id) {
        if (id == null) {
            return null;
        }
        PlatformOneshotEntity entity = oneshotMapper.selectById(id);
        if (entity == null
                || entity.getDataType() == null
                || entity.getDataType() != dataType.code()) {
            return null;
        }
        return entity;
    }

    @Override
    public PlatformOneshotEntity create(PlatformDataType dataType, PlatformOneshotEntity entity) {
        entity.setDataType(dataType.code());
        applyDefaults(entity);
        oneshotMapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(PlatformDataType dataType, PlatformOneshotEntity entity) {
        if (entity.getId() == null) {
            return false;
        }
        // 更新时限定 data_type，避免跨类别误改
        return oneshotMapper.update(
                        entity,
                        new UpdateWrapper<PlatformOneshotEntity>()
                                .eq("id", entity.getId())
                                .eq("data_type", dataType.code()))
                == 1;
    }

    @Override
    public int deleteCascade(PlatformDataType dataType, Integer id) {
        if (id == null) {
            return 0;
        }
        List<Integer> ids = new ArrayList<>();
        collectSubtreeIds(dataType, id, ids);
        ids.add(id);
        return oneshotMapper.update(
                null,
                new UpdateWrapper<PlatformOneshotEntity>()
                        .set("deleted", 1)
                        .set("is_deleted", 1)
                        .in("id", ids)
                        .eq("data_type", dataType.code()));
    }

    @Override
    public boolean updateSort(PlatformDataType dataType, Integer id, int sort) {
        if (id == null) {
            return false;
        }
        return oneshotMapper.update(
                        null,
                        new UpdateWrapper<PlatformOneshotEntity>()
                                .set("sort", sort)
                                .eq("id", id)
                                .eq("data_type", dataType.code()))
                == 1;
    }

    private void collectSubtreeIds(PlatformDataType dataType, Integer parentId, List<Integer> acc) {
        List<PlatformOneshotEntity> children =
                oneshotMapper.selectList(
                        new QueryWrapper<PlatformOneshotEntity>()
                                .eq("data_type", dataType.code())
                                .eq("parent_id", parentId));
        for (PlatformOneshotEntity child : children) {
            if (child.getId() == null) {
                continue;
            }
            acc.add(child.getId());
            collectSubtreeIds(dataType, child.getId(), acc);
        }
    }

    /** 新增分类的默认值（跨站点：不写入站点归属） */
    private void applyDefaults(PlatformOneshotEntity entity) {
        if (entity.getPageType() == null) {
            entity.setPageType(1);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(0);
        }
        if (entity.getIsDeleted() == null) {
            entity.setIsDeleted(false);
        }
        if (entity.getIsCanEdit() == null) {
            entity.setIsCanEdit(true);
        }
        if (entity.getIsCanSort() == null) {
            entity.setIsCanSort(true);
        }
        if (entity.getIsShowCrumbs() == null) {
            entity.setIsShowCrumbs(true);
        }
        if (entity.getIsLoop() == null) {
            entity.setIsLoop(true);
        }
        if (entity.getHasManageChannel() == null) {
            entity.setHasManageChannel(true);
        }
        if (entity.getHasFrontChannel() == null) {
            entity.setHasFrontChannel(true);
        }
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(new Date());
        }
        entity.setSiteProjectId(null);
        entity.setSiteRootId(null);
    }
}

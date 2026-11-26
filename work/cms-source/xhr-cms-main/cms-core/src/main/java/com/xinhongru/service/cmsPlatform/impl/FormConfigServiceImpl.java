package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.service.cmsPlatform.FormConfigService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class FormConfigServiceImpl implements FormConfigService {
    @Resource private FormOneshotMapper formOneshotMapper;

    @Override
    public List<FormOneshotEntity> tree(long parentId, Long siteId) {
        return formOneshotMapper.selectList(
                new QueryWrapper<FormOneshotEntity>()
                        .eq("parent_id", parentId)
                        .eq(siteId != null, "site_project_id", siteId)
                        .eq("is_deleted", 0)
                        .orderByAsc("sort")
                        .orderByAsc("id"));
    }

    @Override
    public List<FormOneshotEntity> all(Long siteId) {
        return formOneshotMapper.selectList(
                new QueryWrapper<FormOneshotEntity>()
                        .eq(siteId != null, "site_project_id", siteId)
                        .eq("is_deleted", 0)
                        .orderByAsc("sort")
                        .orderByAsc("id"));
    }

    @Override
    public FormOneshotEntity getById(Integer id) {
        return formOneshotMapper.selectById(id);
    }

    @Override
    public FormOneshotEntity create(Long siteId, FormOneshotEntity entity) {
        applyDefaults(entity, siteId);
        formOneshotMapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(FormOneshotEntity entity) {
        return formOneshotMapper.updateById(entity) == 1;
    }

    @Override
    public int deleteCascade(Integer id) {
        FormOneshotEntity category = formOneshotMapper.selectById(id);
        if (category == null || category.getSiteProjectId() == null)
            throw new IllegalArgumentException("表单分类不存在");
        List<Integer> ids = new ArrayList<>();
        collectSubtreeIds(id, category.getSiteProjectId(), ids);
        ids.add(id);
        return formOneshotMapper.update(
                null,
                new UpdateWrapper<FormOneshotEntity>()
                        .set("deleted", 1)
                        .set("is_deleted", 1)
                        .in("id", ids));
    }

    @Override
    public boolean updateSort(Integer id, int sort) {
        return formOneshotMapper.update(
                        null, new UpdateWrapper<FormOneshotEntity>().set("sort", sort).eq("id", id))
                == 1;
    }

    private void collectSubtreeIds(Integer parentId, Long siteId, List<Integer> acc) {
        List<FormOneshotEntity> children =
                formOneshotMapper.selectList(
                        new QueryWrapper<FormOneshotEntity>()
                                .eq("parent_id", parentId)
                                .eq("site_project_id", siteId));
        for (FormOneshotEntity c : children) {
            Integer id = c.getId();
            if (id == null) {
                continue;
            }
            if (acc.contains(id)) continue;
            acc.add(id);
            collectSubtreeIds(id, siteId, acc);
        }
    }

    private void applyDefaults(FormOneshotEntity e, Long siteId) {
        if (e.getPageType() == null) {
            e.setPageType(1);
        }
        if (e.getStatus() == null) {
            e.setStatus(0);
        }
        if (e.getIsDeleted() == null) {
            e.setIsDeleted(false);
        }
        if (e.getIsCanEdit() == null) {
            e.setIsCanEdit(true);
        }
        if (e.getIsCanSort() == null) {
            e.setIsCanSort(true);
        }
        if (e.getIsShowCrumbs() == null) {
            e.setIsShowCrumbs(true);
        }
        if (e.getIsLoop() == null) {
            e.setIsLoop(true);
        }
        if (e.getHasManageChannel() == null) {
            e.setHasManageChannel(true);
        }
        if (e.getHasFrontChannel() == null) {
            e.setHasFrontChannel(true);
        }
        if (e.getIsForm() == null) {
            e.setIsForm(true);
        }
        if (e.getFormCaptcha() == null) {
            e.setFormCaptcha(0);
        }
        if (e.getCreateTime() == null) {
            e.setCreateTime(new Date());
        }
        // 站点隔离：写入站点ID
        e.setSiteProjectId(siteId);
    }
}

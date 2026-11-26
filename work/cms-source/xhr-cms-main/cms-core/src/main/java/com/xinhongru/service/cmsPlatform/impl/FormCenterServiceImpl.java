package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinhongru.entity.FormContentEntity;
import com.xinhongru.mapper.FormContentMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

@Service
public class FormCenterServiceImpl extends AbstractCrossSiteContentServiceImpl<FormContentEntity> {
    @Resource private FormContentMapper formContentMapper;

    @Override
    protected BaseMapper<FormContentEntity> mapper() {
        return formContentMapper;
    }

    @Override
    protected void applyDefaults(FormContentEntity e) {
        if (e.getStatus() == null) {
            e.setStatus(0);
        }
        if (e.getSort() == null) {
            e.setSort(0);
        }
        if (e.getHits() == null) {
            e.setHits(0);
        }
        if (e.getCreateTime() == null) {
            e.setCreateTime(now());
        }
    }
}

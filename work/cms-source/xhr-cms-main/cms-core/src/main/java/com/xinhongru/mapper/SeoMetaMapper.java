package com.xinhongru.mapper;

import com.xinhongru.entity.SeoMetaEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeoMetaMapper extends CmsMapper<SeoMetaEntity> {
    List<SeoMetaEntity> selectBySiteId(@Param("siteId") Long siteId);
}

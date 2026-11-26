package com.xinhongru.mapper;

import com.xinhongru.entity.SeoGeoEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeoGeoMapper extends CmsMapper<SeoGeoEntity> {
    List<SeoGeoEntity> selectBySiteId(@Param("siteId") Long siteId);
}

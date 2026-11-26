package com.xinhongru.mapper;

import com.xinhongru.entity.SeoSitemapEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeoSitemapMapper extends CmsMapper<SeoSitemapEntity> {
    List<SeoSitemapEntity> selectBySiteId(@Param("siteId") Long siteId);
}

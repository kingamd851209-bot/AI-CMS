package com.xinhongru.mapper;

import com.xinhongru.entity.SeoHreflangEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeoHreflangMapper extends CmsMapper<SeoHreflangEntity> {
    List<SeoHreflangEntity> selectBySiteId(@Param("siteId") Long siteId);
}

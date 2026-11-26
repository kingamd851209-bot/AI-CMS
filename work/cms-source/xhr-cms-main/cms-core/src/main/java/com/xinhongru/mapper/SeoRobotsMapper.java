package com.xinhongru.mapper;

import com.xinhongru.entity.SeoRobotsEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeoRobotsMapper extends CmsMapper<SeoRobotsEntity> {
    List<SeoRobotsEntity> selectBySiteId(@Param("siteId") Long siteId);
}

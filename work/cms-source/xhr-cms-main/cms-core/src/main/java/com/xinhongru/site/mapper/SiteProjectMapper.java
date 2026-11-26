package com.xinhongru.site.mapper;

import com.xinhongru.persistence.CmsMapper;
import com.xinhongru.site.entity.SiteProjectEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteProjectMapper extends CmsMapper<SiteProjectEntity> {
    @Select("SELECT * FROM cms_ai_site_project WHERE id=#{siteId} AND deleted=0 FOR UPDATE")
    SiteProjectEntity selectByIdForUpdate(Long siteId);
}

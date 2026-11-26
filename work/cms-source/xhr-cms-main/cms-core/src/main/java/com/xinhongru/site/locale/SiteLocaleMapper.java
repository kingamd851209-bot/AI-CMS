package com.xinhongru.site.locale;

import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SiteLocaleMapper extends CmsMapper<SiteLocaleEntity> {
    @Select(
            "SELECT id, site_id, language_id, is_default, enabled, required_for_publish,"
                + " path_prefix, fallback_language_id, sort, create_time, update_time, deleted FROM"
                + " cms_site_locale WHERE site_id = #{siteId}")
    List<SiteLocaleEntity> selectBySiteIdIncludingDeleted(@Param("siteId") Long siteId);

    @Update(
            "UPDATE cms_site_locale SET is_default = #{isDefault}, enabled = #{enabled},"
                    + " required_for_publish = #{requiredForPublish}, path_prefix = #{pathPrefix},"
                    + " fallback_language_id = #{fallbackLanguageId}, sort = #{sort}, update_time ="
                    + " #{updateTime}, deleted = 0 WHERE id = #{id}")
    int restoreAndUpdate(SiteLocaleEntity locale);
}

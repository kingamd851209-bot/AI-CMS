package com.xinhongru.mapper;

import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FormOneshotMapper extends CmsMapper<FormOneshotEntity> {
    @Select(
            """
            SELECT * FROM t_from_oneshot
            WHERE site_project_id=#{siteId} AND identifies LIKE CONCAT(#{prefix}, '%')
            FOR UPDATE
            """)
    List<FormOneshotEntity> lockImportedForms(
            @Param("siteId") Long siteId, @Param("prefix") String prefix);
}

package com.xinhongru.cms.search;

import com.xinhongru.entity.OneshotEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PublicSearchMapper {
    List<OneshotEntity> menus(@Param("siteId") Long siteId);

    long count(
            @Param("q") PublicSearchQuery query,
            @Param("menus") List<Integer> menus,
            @Param("contentOnly") boolean contentOnly);

    List<PublicSearchRow> page(
            com.baomidou.mybatisplus.core.metadata.IPage<PublicSearchRow> page,
            @Param("q") PublicSearchQuery query,
            @Param("menus") List<Integer> menus,
            @Param("contentOnly") boolean contentOnly);

    List<com.xinhongru.entity.ContentEntity> projectionBatch(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("after") Integer after,
            @Param("limit") Integer limit);

    int writeContentProjections(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("updates") List<java.util.Map<String, Object>> updates);

    int writeSingleProjection(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("text") String text);
}

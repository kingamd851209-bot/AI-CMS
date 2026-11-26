package com.xinhongru.mapper;

import com.xinhongru.entity.MenuEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 系统菜单 Mapper */
@Mapper
public interface SystemMenuMapper extends CmsMapper<MenuEntity> {
    List<MenuEntity> selectChildrenWithState(@Param("parentId") Long parentId);
}

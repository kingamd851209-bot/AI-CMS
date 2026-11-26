package com.xinhongru.mapper;

import com.xinhongru.entity.ModuleEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.TitleDto;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/2 15:22
 */
@Mapper
public interface ModuleMapper {
    /**
     * 添加模块
     *
     * @param moduleEntity
     * @return
     */
    Integer add(ModuleEntity moduleEntity);

    /**
     * 修改模块
     *
     * @param moduleEntity
     * @return
     */
    Boolean edit(ModuleEntity moduleEntity);

    /**
     * 删除模块
     *
     * @param ids
     * @return
     */
    Boolean delete(int... ids);

    /**
     * 获取模块列表
     *
     * @return
     */
    List<ModuleEntity> getAllList();

    /**
     * 通过模块ID获取模块对象
     *
     * @param id
     * @return
     */
    ModuleEntity getModuleEntityById(@Param("id") Integer id);

    /**
     * 修改排序号
     *
     * @param sortDto
     * @return
     */
    Boolean updateSort(SortDto sortDto);

    /**
     * 修改模块名称
     *
     * @param titleDto
     * @return
     */
    Boolean updateTitle(TitleDto titleDto);

    /**
     * 删除模块
     *
     * @param idDto
     * @return
     */
    Boolean updateIsDeleted(IdDto idDto);
}

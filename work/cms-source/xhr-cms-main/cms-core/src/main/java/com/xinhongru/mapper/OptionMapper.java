package com.xinhongru.mapper;

import com.xinhongru.entity.OptionsEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 网站配置dao Created at 2021/8/9. */
@Mapper
public interface OptionMapper {
    /**
     * 删除网站配置
     *
     * @param name
     * @return
     */
    int deleteOptionByName(@Param("name") String name);

    int addOption(OptionsEntity options);

    /**
     * 更新网站配置
     *
     * @param options
     * @return
     */
    int updateOptionByName(OptionsEntity options);

    /***
     * 根据名称获取网站配置
     * @param name
     * @return
     */
    OptionsEntity getOptionByName(@Param("name") String name);

    /**
     * 获取全部网站配置
     *
     * @return
     */
    List<OptionsEntity> getOptions();
}

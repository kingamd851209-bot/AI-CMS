package com.xinhongru.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Created at 2021/8/9. */
@Mapper
public interface LogMapper {
    List<LogEntity> getLogs(IPage<LogEntity> page, @Param("uid") Integer uid);

    List<LogEntity> getLogList(IPage<LogEntity> page, @Param("query") LogDto query);

    /**
     * 添加日志
     *
     * @param logEntity
     * @return
     */
    int addLog(LogEntity logEntity);

    /**
     * 获取日志
     *
     * @return
     * @param uid
     */
    List<LogEntity> getLogs(@Param("uid") Integer uid);

    /**
     * 带查询的日志列表
     *
     * @param logDto
     * @return
     */
    List<LogEntity> getLogList(@Param("query") LogDto logDto);

    /**
     * 根据日志ID获取日志明细
     *
     * @param id
     * @return
     */
    LogEntity getLogDetails(@Param("id") Integer id);
}

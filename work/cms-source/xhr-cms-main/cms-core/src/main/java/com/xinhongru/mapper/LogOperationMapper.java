package com.xinhongru.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogOperationEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/5 11:18
 */
@Mapper
public interface LogOperationMapper {
    List<LogOperationEntity> getLogList(
            IPage<LogOperationEntity> page, @Param("query") LogDto query);

    /**
     * 添加操作日志
     *
     * @param logOperationEntity
     * @return
     */
    Boolean add(LogOperationEntity logOperationEntity);

    /**
     * 根据栏目ID和数据ID查询操作记录
     *
     * @param classId
     * @param infoId
     * @return
     */
    List<LogOperationEntity> getListByClassIdAndInfoId(
            @Param("classId") Integer classId, @Param("infoId") Integer infoId);

    /**
     * 查询最近操作日志
     *
     * @param limit 条数
     * @return
     */
    List<LogOperationEntity> getRecentLogs(@Param("limit") int limit);

    /**
     * 分页查询操作日志
     *
     * @param logDto 过滤/分页条件
     * @return
     */
    List<LogOperationEntity> getLogList(@Param("query") LogDto logDto);

    /**
     * 查询单条操作日志
     *
     * @param id
     * @return
     */
    LogOperationEntity getLogById(@Param("id") Integer id);
}

package com.xinhongru.service.logOperation;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.dto.LogDto;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.entity.LogOperationEntity;

import java.util.List;
import java.util.Queue;

/**
 * @Description 内容管理模块的操作日志记录 @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/5 11:19
 */
public interface LogOperationService {
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
    List<LogOperationEntity> getListByClassIdAndInfoId(Integer classId, Integer infoId);

    /**
     * 查询最近操作日志
     *
     * @param limit 条数
     * @return
     */
    List<LogOperationEntity> getRecentLogs(int limit);

    /**
     * 分页查询操作日志
     *
     * @param logDto 过滤/分页条件
     * @return
     */
    PageResult<LogOperationEntity> getLogList(LogDto logDto);

    /**
     * 查询单条操作日志
     *
     * @param id
     * @return
     */
    LogOperationEntity getLogById(Integer id);

    /**
     * 组装更新字段表格
     *
     * @param logTableDtoQueue
     * @return
     */
    String getUpdateTableHtml(Queue<LogTableDto> logTableDtoQueue);

    /**
     * 简单日志记录
     *
     * @param fieldName
     * @param updateBefore
     * @param updateAfter
     * @return
     */
    String getSimpleUpdateTableHtml(String fieldName, Object updateBefore, Object updateAfter);
}

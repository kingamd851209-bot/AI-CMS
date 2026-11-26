package com.xinhongru.service.log;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.responseVo.logVo.LogHtmlVo;

import jakarta.servlet.http.HttpServletRequest;

/** 用户请求日志 Created at 2021/8/9. */
public interface LogService {
    /**
     * 添加日志
     *
     * @param logEntity
     */
    void addLog(LogEntity logEntity);

    /**
     * 删除日志
     *
     * @param id
     * @return
     */
    void deleteLogById(Integer id);

    /**
     * 获取日志
     *
     * @param pageNum
     * @param pageSize
     * @param request
     * @return
     */
    PageResult<LogEntity> getLogs(int pageNum, int pageSize, HttpServletRequest request);

    /**
     * 获取日志对象集合
     *
     * @param logDto
     * @param httpServletRequest
     * @return
     */
    LogHtmlVo getLogList(LogDto logDto, HttpServletRequest httpServletRequest);

    /**
     * 获取日志明细
     *
     * @param idDto
     * @return
     */
    LogEntity getLogDetails(IdDto idDto);
}

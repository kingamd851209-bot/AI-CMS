package com.xinhongru.service.logOperation.impl;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.LogDto;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.entity.LogOperationEntity;
import com.xinhongru.mapper.LogOperationMapper;
import com.xinhongru.persistence.Pages;
import com.xinhongru.service.logOperation.LogOperationService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @Description @ClassName LogOperationServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/6/5 11:19
 */
@Slf4j
@Service
public class LogOperationServiceImpl implements LogOperationService {
    private LogOperationMapper logOperationMapper;

    @Autowired
    public void setLogOperationMapper(LogOperationMapper logOperationMapper) {
        this.logOperationMapper = logOperationMapper;
    }

    @Override
    public Boolean add(LogOperationEntity logOperationEntity) {
        return logOperationMapper.add(logOperationEntity);
    }

    @Override
    public List<LogOperationEntity> getListByClassIdAndInfoId(Integer classId, Integer infoId) {
        return logOperationMapper.getListByClassIdAndInfoId(classId, infoId);
    }

    @Override
    public List<LogOperationEntity> getRecentLogs(int limit) {
        return logOperationMapper.getRecentLogs(limit);
    }

    @Override
    public PageResult<LogOperationEntity> getLogList(LogDto logDto) {
        return Pages.query(
                logDto.getPage(),
                logDto.getLimit(),
                page -> logOperationMapper.getLogList(page, logDto));
    }

    @Override
    public LogOperationEntity getLogById(Integer id) {
        return logOperationMapper.getLogById(id);
    }

    @Override
    public String getUpdateTableHtml(Queue<LogTableDto> logTableDtoQueue) {
        if (logTableDtoQueue.isEmpty()) {
            return WebConst.NOTHING_HAS_BEEN_MODIFIED;
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<table class=\"layui-table\">\n"
                        + "                                <colgroup>\n"
                        + "                                    <col width=\"16%\">\n"
                        + "                                    <col width=\"42%\">\n"
                        + "                                    <col width=\"42%\">\n"
                        + "                                </colgroup>\n"
                        + "                                <thead>\n"
                        + "                                <tr>\n"
                        + "                                    <th>字段名</th>\n"
                        + "                                    <th>变更前</th>\n"
                        + "                                    <th>变更后</th>\n"
                        + "                                </tr>\n"
                        + "                                </thead>\n"
                        + "                                <tbody>");
        if (CollectionUtils.isNotEmpty(logTableDtoQueue)) {
            for (LogTableDto logTableDto : logTableDtoQueue) {
                stringBuilder.append(
                        "<tr>\n"
                                + "                                    <td"
                                + " style=\"vertical-align:top;text-align:left;\">"
                                + logTableDto.getFieldName()
                                + "</td>\n"
                                + "                                    <td"
                                + " style=\"vertical-align:top;text-align:left;\">"
                                + logTableDto.getUpdateBefore()
                                + "</td>\n"
                                + "                                    <td"
                                + " style=\"vertical-align:top;text-align:left;\">"
                                + logTableDto.getUpdateAfter()
                                + "</td>\n"
                                + "                                </tr>");
            }
        }
        stringBuilder.append("</tbody>\n" + "                            </table>");
        return stringBuilder.toString();
    }

    @Override
    public String getSimpleUpdateTableHtml(
            String fieldName, Object updateBefore, Object updateAfter) {
        Queue<LogTableDto> logTableDtoQueue = new LinkedList<>();
        logTableDtoQueue.add(
                new LogTableDto()
                        .setFieldName(fieldName)
                        .setUpdateBefore(updateBefore)
                        .setUpdateAfter(updateAfter));
        return getUpdateTableHtml(logTableDtoQueue);
    }
}

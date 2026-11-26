package com.xinhongru.service.impl;

import com.xinhongru.entity.ContentHistoryEntity;
import com.xinhongru.mapper.ContentHistoryMapper;
import com.xinhongru.service.ContentHistoryService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** TODO 内容版本 @Author chang @Date 2023/7/30 08:27 @Version 1.0.0 */
@Service
public class ContentHistoryServiceImpl implements ContentHistoryService {
    private ContentHistoryMapper contentHistoryMapper;

    @Autowired
    public void setContentHistoryMapper(final ContentHistoryMapper contentHistoryMapper) {
        this.contentHistoryMapper = contentHistoryMapper;
    }

    @Override
    public ContentHistoryEntity getById(final Integer historyId) {
        return contentHistoryMapper.getById(historyId);
    }

    @Override
    public void updateById(final ContentHistoryEntity historyEntity) {
        contentHistoryMapper.updateById(historyEntity);
    }

    @Override
    public void save(final ContentHistoryEntity historyEntity) {
        contentHistoryMapper.save(historyEntity);
    }

    @Override
    public List<ContentHistoryEntity> list(final Integer infoId, final Integer classId) {
        return contentHistoryMapper.list(infoId, classId);
    }
}

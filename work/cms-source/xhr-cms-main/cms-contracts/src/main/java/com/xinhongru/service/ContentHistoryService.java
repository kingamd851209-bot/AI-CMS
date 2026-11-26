package com.xinhongru.service;

import com.xinhongru.entity.ContentHistoryEntity;

import java.util.List;

/**
 * @Description: 内容历史反馈 @Version: V1.0
 */
public interface ContentHistoryService {
    ContentHistoryEntity getById(Integer historyId);

    void updateById(ContentHistoryEntity historyEntity);

    void save(ContentHistoryEntity historyEntity);

    List<ContentHistoryEntity> list(Integer infoId, Integer classId);
}

package com.xinhongru.mapper;

import com.xinhongru.entity.ContentHistoryEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

import java.util.List;

/** 持久层 */
@Mapper
@Component
public interface ContentHistoryMapper {
    ContentHistoryEntity getById(@Param("historyId") Integer historyId);

    void updateById(ContentHistoryEntity historyEntity);

    void save(ContentHistoryEntity historyEntity);

    List<ContentHistoryEntity> list(
            @Param("infoId") Integer infoId, @Param("classId") Integer classId);
}

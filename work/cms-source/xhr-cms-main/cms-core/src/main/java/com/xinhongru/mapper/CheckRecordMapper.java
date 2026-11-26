package com.xinhongru.mapper;

import com.xinhongru.entity.CheckRecordEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

import java.util.List;

/** 持久层 */
@Mapper
@Component
public interface CheckRecordMapper {
    CheckRecordEntity getById(@Param("id") Integer id);

    void updateById(CheckRecordEntity checkRecordEntity);

    void save(CheckRecordEntity checkRecordEntity);

    void updateCheckStatus3(Integer checkRecordEntity);

    long countByContentId(@Param("contentId") Integer contentId);

    List<CheckRecordEntity> list(
            @Param("submitMenuIdList") List<Integer> submitMenuIdList,
            @Param("title") String title);

    List<CheckRecordEntity> listAuditRecords(
            @Param("submitMenuIdList") List<Integer> submitMenuIdList,
            @Param("title") String title);
}

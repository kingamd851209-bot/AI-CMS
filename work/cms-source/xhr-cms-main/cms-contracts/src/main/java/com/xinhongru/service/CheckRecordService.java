package com.xinhongru.service;

import com.xinhongru.entity.CheckRecordEntity;

import java.util.List;

/**
 * @Description: 审批提交记录 @Version: V1.0
 */
public interface CheckRecordService {
    CheckRecordEntity getById(Integer id);

    void updateById(CheckRecordEntity checkRecordEntity);

    void save(CheckRecordEntity add);

    void updateCheckStatus3(Integer contentId);

    long countByContentId(Integer contentId);

    List<CheckRecordEntity> list(List<Integer> submitMenuIdList, String title);

    List<CheckRecordEntity> listAuditRecords(List<Integer> submitMenuIdList, String title);
}

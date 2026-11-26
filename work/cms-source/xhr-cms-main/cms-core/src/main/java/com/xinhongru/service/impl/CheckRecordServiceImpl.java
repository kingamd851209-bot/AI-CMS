package com.xinhongru.service.impl;

import com.xinhongru.entity.CheckRecordEntity;
import com.xinhongru.mapper.CheckRecordMapper;
import com.xinhongru.service.CheckRecordService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** TODO 审批记录 @Author chang @Date 2023/7/30 08:27 @Version 1.0.0 */
@Service
public class CheckRecordServiceImpl implements CheckRecordService {
    private CheckRecordMapper checkRecordMapper;

    @Autowired
    public void setCheckRecordMapper(final CheckRecordMapper checkRecordMapper) {
        this.checkRecordMapper = checkRecordMapper;
    }

    @Override
    public CheckRecordEntity getById(final Integer id) {
        return checkRecordMapper.getById(id);
    }

    @Override
    public void updateById(final CheckRecordEntity checkRecordEntity) {
        checkRecordMapper.updateById(checkRecordEntity);
    }

    @Override
    public void save(final CheckRecordEntity checkRecordEntity) {
        checkRecordMapper.save(checkRecordEntity);
    }

    @Override
    public void updateCheckStatus3(final Integer contentId) {
        checkRecordMapper.updateCheckStatus3(contentId);
    }

    @Override
    public long countByContentId(final Integer contentId) {
        return checkRecordMapper.countByContentId(contentId);
    }

    @Override
    public List<CheckRecordEntity> list(final List<Integer> submitMenuIdList, final String title) {
        return checkRecordMapper.list(submitMenuIdList, title);
    }

    @Override
    public List<CheckRecordEntity> listAuditRecords(
            final List<Integer> submitMenuIdList, final String title) {
        return checkRecordMapper.listAuditRecords(submitMenuIdList, title);
    }
}

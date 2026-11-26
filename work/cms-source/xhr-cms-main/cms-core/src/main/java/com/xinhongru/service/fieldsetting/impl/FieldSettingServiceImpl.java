package com.xinhongru.service.fieldsetting.impl;

import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.mapper.FieldSettingMapper;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.service.fieldsetting.FieldSettingService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FieldSettingServiceImpl implements FieldSettingService {
    private FieldSettingMapper fieldSettingDao;
    private com.xinhongru.mapper.OneShotMapper oneShotMapper;
    private com.xinhongru.cms.search.PublicSearchProjectionService publicSearchProjectionService;

    @Autowired
    public void setOneShotMapper(com.xinhongru.mapper.OneShotMapper value) {
        this.oneShotMapper = value;
    }

    @Autowired
    public void setPublicSearchProjectionService(
            com.xinhongru.cms.search.PublicSearchProjectionService value) {
        this.publicSearchProjectionService = value;
    }

    private HttpServletRequest request;

    public void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    @Autowired
    public void setFieldSettingDao(FieldSettingMapper fieldSettingDao) {
        this.fieldSettingDao = fieldSettingDao;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public boolean appendModelFields(
            Long siteId,
            Integer menuId,
            String expectedDigest,
            List<ContentFieldDefinition> additions) {
        var current = oneShotMapper.lockFieldConfiguration(siteId, menuId.longValue());
        if (current == null || !ContentFieldConfigDigest.of(current).equals(expectedDigest))
            return false;
        boolean single = Integer.valueOf(0).equals(current.getPageType());
        String updated =
                ContentModelCodec.append(
                        single ? current.getMenuField() : current.getModelField(), additions);
        if (oneShotMapper.updateFieldConfig(
                        menuId,
                        single ? updated : current.getMenuField(),
                        single ? current.getModelField() : updated,
                        current.getModelList(),
                        current.getModelSearch())
                != 1) return false;
        publicSearchProjectionService.rebuildMenu(siteId, menuId);
        return true;
    }

    @Override
    public List<ContentFieldDefinition> getFieldSettingList(Integer type) {
        List<ContentFieldDefinition> fieldSettingList = fieldSettingDao.getFieldSettingList(type);
        return fieldSettingList;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public ResultVO updateModelField(SetFieldDto setFieldDto) {
        if (setFieldDto == null || setFieldDto.getId() == null)
            throw new IllegalArgumentException("字段配置缺少栏目");
        var before = oneShotMapper.getModelById(setFieldDto.getId());
        if (before == null || before.getSiteProjectId() == null)
            throw new IllegalArgumentException("字段配置栏目不存在");
        var current =
                oneShotMapper.lockFieldConfiguration(
                        before.getSiteProjectId(), setFieldDto.getId().longValue());
        if (current == null) throw new IllegalArgumentException("字段配置栏目已变化");

        setFieldDto.setFieldListDisplayJson(
                ContentModelCodec.write(setFieldDto.getFieldListDisplay()));
        setFieldDto.setFieldOptionsJson(ContentModelCodec.write(setFieldDto.getFieldOptions()));
        setFieldDto.setFieldSearchJson(ContentModelCodec.write(setFieldDto.getFieldSearch()));
        Boolean updateModelField = fieldSettingDao.updateModelField(setFieldDto);
        if (updateModelField) {
            publicSearchProjectionService.rebuildMenu(current.getSiteProjectId(), current.getId());
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }
}

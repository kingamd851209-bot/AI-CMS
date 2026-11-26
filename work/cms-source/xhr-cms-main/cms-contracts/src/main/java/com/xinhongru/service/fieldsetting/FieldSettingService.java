package com.xinhongru.service.fieldsetting;

import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;
import com.xinhongru.responseVo.ResultVO;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FieldSettingService {
    boolean appendModelFields(
            Long siteId,
            Integer menuId,
            String expectedDigest,
            java.util.List<com.xinhongru.content.model.ContentFieldDefinition> additions);

    List<ContentFieldDefinition> getFieldSettingList(@Param("type") Integer type);

    /**
     * 更新栏目勾选字段
     *
     * @param setFieldDto
     * @return
     */
    ResultVO updateModelField(SetFieldDto setFieldDto);
}

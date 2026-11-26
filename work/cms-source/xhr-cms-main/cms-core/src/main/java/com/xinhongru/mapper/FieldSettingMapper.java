package com.xinhongru.mapper;

import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

import java.util.List;

@Mapper
@Component
public interface FieldSettingMapper {
    List<ContentFieldDefinition> getFieldSettingList(@Param("type") Integer type);

    Boolean updateModelField(SetFieldDto setFieldDto);
}

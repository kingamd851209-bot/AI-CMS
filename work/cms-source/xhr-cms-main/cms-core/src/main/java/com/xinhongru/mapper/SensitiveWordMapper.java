package com.xinhongru.mapper;

import com.xinhongru.entity.SensitiveWordEntity;
import com.xinhongru.persistence.CmsMapper;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

@Mapper
@Component
public interface SensitiveWordMapper extends CmsMapper<SensitiveWordEntity> {}

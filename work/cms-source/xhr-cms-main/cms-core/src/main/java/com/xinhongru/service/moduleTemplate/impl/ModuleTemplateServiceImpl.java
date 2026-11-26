package com.xinhongru.service.moduleTemplate.impl;

import com.xinhongru.entity.ModuleTemplateEntity;
import com.xinhongru.mapper.ModuleTemplateMapper;
import com.xinhongru.service.moduleTemplate.ModuleTemplateService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description @ClassName ModuleTemplateServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/4/2 22:01
 */
@Slf4j
@Service
public class ModuleTemplateServiceImpl implements ModuleTemplateService {
    private ModuleTemplateMapper moduleTemplateMapper;

    @Autowired
    public void setModuleTemplateMapper(ModuleTemplateMapper moduleTemplateMapper) {
        this.moduleTemplateMapper = moduleTemplateMapper;
    }

    @Override
    public Boolean add(ModuleTemplateEntity moduleTemplateEntity) {
        return moduleTemplateMapper.add(moduleTemplateEntity);
    }

    @Override
    public Boolean deleteByModuleId(Integer moduleId) {
        return moduleTemplateMapper.deleteByModuleId(moduleId);
    }

    @Override
    public List<ModuleTemplateEntity> getModuleTemplateListByModuleId(Integer moduleId) {
        return moduleTemplateMapper.getModuleTemplateListByModuleId(moduleId);
    }
}

package com.xinhongru.service.moduleTemplate;

import com.xinhongru.entity.ModuleTemplateEntity;

import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/2 22:01
 */
@Service
public interface ModuleTemplateService {
    /**
     * 添加模板栏目
     *
     * @param moduleTemplateEntity
     * @return
     */
    Boolean add(ModuleTemplateEntity moduleTemplateEntity);

    /**
     * 根据模块ID删除模板栏目
     *
     * @param moduleId
     * @return
     */
    Boolean deleteByModuleId(@Param("moduleId") Integer moduleId);

    /**
     * 通过模块ID获取模板栏目
     *
     * @param moduleId
     * @return
     */
    List<ModuleTemplateEntity> getModuleTemplateListByModuleId(@Param("moduleId") Integer moduleId);
}

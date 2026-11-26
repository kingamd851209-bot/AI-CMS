package com.xinhongru.service.module;

import com.xinhongru.entity.ModuleEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.TitleDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.module.ModuleMenuVo;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description @ClassName ModuleService @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/2
 * 15:18
 */
@Service
public interface ModuleService {
    /**
     * 添加模块
     *
     * @param moduleEntity
     * @return
     */
    Integer add(ModuleEntity moduleEntity);

    /**
     * 修改模块
     *
     * @param moduleEntity
     * @return
     */
    Boolean edit(ModuleEntity moduleEntity);

    /**
     * 删除模块
     *
     * @param ids
     * @return
     */
    Boolean delete(int... ids);

    /**
     * 获取模块列表
     *
     * @return
     */
    List<ModuleEntity> getAllList();

    /**
     * 通过ID获取模块对象
     *
     * @param id
     * @return
     */
    ModuleEntity getModuleEntityById(Integer id);

    /**
     * 添加或修改对象
     *
     * @param moduleEntity
     * @return
     */
    ResultVO addOrUpdate(ModuleEntity moduleEntity);

    /**
     * 获取模块的栏目对象
     *
     * @return
     * @param moduleEntity
     */
    List<ModuleMenuVo> getModuleMenuVos(ModuleEntity moduleEntity);

    /**
     * 修改模块排序
     *
     * @param sortDto
     * @return
     */
    ResultVO updateSort(SortDto sortDto);

    /**
     * 修改模块标题
     *
     * @param titleDto
     * @return
     */
    ResultVO updateTitle(TitleDto titleDto);

    /**
     * 按照支持的栏目显示特定的模型
     *
     * @param parentId
     * @return
     */
    List<ModuleEntity> getAllList(Integer parentId);

    /**
     * 删除模块
     *
     * @param idDto
     * @return
     */
    ResultVO deleteModule(IdDto idDto);

    /**
     * 根据栏目ID获取模块集合
     *
     * @param menuId
     * @return
     */
    List<ModuleEntity> getAllListByMenuIds(String menuId);
}

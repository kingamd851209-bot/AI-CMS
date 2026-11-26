package com.xinhongru.service.module.impl;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.ModuleEntity;
import com.xinhongru.entity.ModuleTemplateEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.mapper.ModuleMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.TitleDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.module.ModuleMenuVo;
import com.xinhongru.service.module.ModuleService;
import com.xinhongru.service.moduleTemplate.ModuleTemplateService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description 模块管理 @ClassName ModuleImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/2
 * 15:18
 */
@Slf4j
@Service
public class ModuleImpl implements ModuleService {
    private ModuleMapper moduleMapper;
    private OneShotMapper oneShotMapper;
    private ModuleTemplateService moduleTemplateService;

    @Autowired
    public void setModuleTemplateService(ModuleTemplateService moduleTemplateService) {
        this.moduleTemplateService = moduleTemplateService;
    }

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    @Autowired
    public void setModuleMapper(ModuleMapper moduleMapper) {
        this.moduleMapper = moduleMapper;
    }

    @Override
    public Integer add(ModuleEntity moduleEntity) {
        return moduleMapper.add(moduleEntity);
    }

    @Override
    public Boolean edit(ModuleEntity moduleEntity) {
        return moduleMapper.edit(moduleEntity);
    }

    @Override
    public Boolean delete(int... ids) {
        return moduleMapper.delete(ids);
    }

    @Override
    public List<ModuleEntity> getAllList() {
        return moduleMapper.getAllList();
    }

    @Override
    public ModuleEntity getModuleEntityById(Integer id) {
        return moduleMapper.getModuleEntityById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResultVO addOrUpdate(ModuleEntity moduleEntity) {
        Boolean flag = false;
        Integer moduleId = 0;
        if (moduleEntity.getId() == null || moduleEntity.getId() == 0) {
            moduleMapper.add(moduleEntity);
            moduleId = moduleEntity.getId();
            if (moduleId > 0) {
                flag = true;
            }
        } else {
            moduleId = moduleEntity.getId();
            flag = moduleMapper.edit(moduleEntity);
        }
        if (flag) {
            // 插入模块模板栏目
            moduleTemplateService.deleteByModuleId(moduleId);
            String[] menuIdArr = moduleEntity.getMenuIds().split(",");
            OneshotEntity modelById;
            ModuleTemplateEntity moduleTemplateEntity;
            for (String menuId : menuIdArr) {
                modelById = oneShotMapper.getModelById(Integer.valueOf(menuId));
                moduleTemplateEntity = new ModuleTemplateEntity();
                moduleTemplateEntity.setClassName(modelById.getClassName());
                moduleTemplateEntity.setClassEnName(modelById.getClassEnName());
                moduleTemplateEntity.setCreateTime(new Date());
                // 如果前台列表地址不包含参数则增加一个参数{classId} 未来用来替换为栏目的id值 这样可以确保栏目添加后 前台地址可以正常使用
                if (StringUtils.isNotBlank(modelById.getListPageUrl())
                        && !modelById.getListPageUrl().contains("{classId}")) {
                    modelById.setListPageUrl(modelById.getListPageUrl().replace(".", "{classId}."));
                }
                moduleTemplateEntity.setListPageUrl(modelById.getListPageUrl());
                moduleTemplateEntity.setDetailPageUrl(modelById.getDetailPageUrl());
                moduleTemplateEntity.setParentId(modelById.getParentId());
                moduleTemplateEntity.setSort(modelById.getSort());
                moduleTemplateEntity.setMenuField(modelById.getMenuField());
                moduleTemplateEntity.setIdentifies(modelById.getIdentifies());
                moduleTemplateEntity.setModelField(modelById.getModelField());
                moduleTemplateEntity.setModelList(modelById.getModelList());
                moduleTemplateEntity.setModelSearch(modelById.getModelSearch());
                moduleTemplateEntity.setSetting(modelById.getSetting());
                moduleTemplateEntity.setHasManageChannel(modelById.getHasManageChannel());
                moduleTemplateEntity.setIsCanAddArticle(modelById.getIsCanAddArticle());
                // 如果后台地址不包含参数则增加一个参数 未来用来替换为栏目的id值 这样可以确保栏目添加后 后台地址可以正常使用
                if (!modelById.getUrl().contains("{id}")) {
                    modelById.setUrl(modelById.getUrl().replace(".", "{id}."));
                }
                moduleTemplateEntity.setUrl(modelById.getUrl());
                moduleTemplateEntity.setHasFrontChannel(modelById.getHasFrontChannel());
                moduleTemplateEntity.setPageType(modelById.getPageType());
                moduleTemplateEntity.setIsSubMenu(modelById.getIsSubMenu());
                // 管理员自行添加的栏目 肯定可以自行删除
                moduleTemplateEntity.setIsCanDeleted(true);
                moduleTemplateEntity.setIsCanCascadeDeleted(modelById.getIsCanCascadeDeleted());
                // 管理员自行添加的栏目 肯定不是模块基础栏目
                moduleTemplateEntity.setIsModuleMenu(false);
                moduleTemplateEntity.setIsCanAddSub(modelById.getIsCanAddSub());
                moduleTemplateEntity.setIsCanEdit(modelById.getIsCanEdit());
                moduleTemplateEntity.setIsCanSort(modelById.getIsCanSort());
                moduleTemplateEntity.setIsCanShowHidden(modelById.getIsCanShowHidden());
                moduleTemplateEntity.setIsShowCrumbs(modelById.getIsShowCrumbs());
                moduleTemplateEntity.setWebsitePublic(modelById.getWebsitePublic());
                moduleTemplateEntity.setIsLoop(modelById.getIsLoop());
                moduleTemplateEntity.setHits(WebConst.ZERO);
                moduleTemplateEntity.setPublished(false);
                moduleTemplateEntity.setModuleId(moduleId);
                moduleTemplateService.add(moduleTemplateEntity);
            }
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    public List<ModuleMenuVo> getModuleMenuVos(ModuleEntity moduleEntity) {
        List<ModuleMenuVo> moduleMenuVos = new ArrayList<>();
        List<OneshotEntity> allMenu = oneShotMapper.getAllMenu();

        // 前后台都不显示的栏目需要排除
        allMenu =
                allMenu.stream()
                        .filter(item -> item.getHasManageChannel() || item.getHasFrontChannel())
                        .collect(Collectors.toList());

        String split = treeIndent() + treeBranch();
        int parentId = 0;
        recursiveForSetting(moduleMenuVos, allMenu, parentId, split, moduleEntity);
        return moduleMenuVos;
    }

    @Override
    public ResultVO updateSort(SortDto sortDto) {
        ModuleEntity modelById = moduleMapper.getModuleEntityById(sortDto.getId());
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        Boolean updateSort = moduleMapper.updateSort(sortDto);
        if (updateSort) {
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    public ResultVO updateTitle(TitleDto titleDto) {
        ModuleEntity modelById = moduleMapper.getModuleEntityById(titleDto.getId());
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        Boolean updateTitle = moduleMapper.updateTitle(titleDto);
        if (updateTitle) {
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    public List<ModuleEntity> getAllList(Integer parentId) {
        List<ModuleEntity> allList = getAllList();
        return allList.stream()
                .filter(
                        item ->
                                Arrays.asList(item.getCanAddMenuIds().split(","))
                                        .contains(String.valueOf(parentId)))
                .collect(Collectors.toList());
    }

    @Override
    public ResultVO deleteModule(IdDto idDto) {
        ModuleEntity moduleEntityById = moduleMapper.getModuleEntityById(idDto.getId());
        if (moduleEntityById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        Boolean updateIsDeleted = moduleMapper.updateIsDeleted(idDto);
        if (updateIsDeleted) {
            return ResultVO.success(ErrorCodeEnum.DELETE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.DELETE_FAIL);
        }
    }

    @Override
    public List<ModuleEntity> getAllListByMenuIds(String menuId) {
        List<ModuleEntity> allList = getAllList();
        return allList.stream()
                .filter(item -> Arrays.asList(item.getCanAddMenuIds().split(",")).contains(menuId))
                .collect(Collectors.toList());
    }

    private void recursiveForSetting(
            List<ModuleMenuVo> moduleMenuVos,
            List<OneshotEntity> allMenu,
            int parentId,
            String split,
            ModuleEntity moduleEntity) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getParentId() == parentId)
                        .collect(Collectors.toList());
        ModuleMenuVo moduleMenuVo;
        String classNameHtml;

        for (OneshotEntity oneshotEntity : oneshotsByParentId) {
            moduleMenuVo = new ModuleMenuVo();
            moduleMenuVo.setId(oneshotEntity.getId());
            // 栏目名称
            classNameHtml =
                    split
                            + "<input type=\"checkbox\" name=\"menuIds\" value=\""
                            + oneshotEntity.getId()
                            + "\" id=\"menuIds_"
                            + oneshotEntity.getId()
                            + "\""
                            + " style=\"margin-left:3px;cursor: pointer;\""
                            + (getCheckedByMenuIds(oneshotEntity, moduleEntity))
                            + "> "
                            + "<label style=\"font-weight:unset;cursor: pointer;\" for=\"menuIds_"
                            + oneshotEntity.getId()
                            + "\">"
                            + oneshotEntity.getClassName()
                            + "</label>\n";
            // 栏目拥有的操作权限
            if (oneshotEntity.getIsCanAddSub()) {
                classNameHtml +=
                        "<span id=\"menu-id-"
                                + oneshotEntity.getId()
                                + "\" style=\"margin-left: 15px;\">";
                classNameHtml +=
                        "<input id=\"canAddMenuIds_"
                                + oneshotEntity.getId()
                                + "\" type=\"checkbox\" name=\"canAddMenuIds\" value=\""
                                + oneshotEntity.getId()
                                + "\""
                                + (getCheckedByCanAddMenuIds(oneshotEntity, moduleEntity))
                                + ">";
                classNameHtml +=
                        "<label style=\"margin-left:3px;font-weight:unset;cursor: pointer;\""
                                + " for=\"canAddMenuIds_"
                                + oneshotEntity.getId()
                                + "\">设为模板栏目</label>";
                classNameHtml += "</span>";
            }
            moduleMenuVo.setClassName(classNameHtml);
            moduleMenuVos.add(moduleMenuVo);

            recursiveForSetting(
                    moduleMenuVos,
                    allMenu,
                    oneshotEntity.getId(),
                    treeIndent() + split,
                    moduleEntity);
        }
    }

    private static String treeIndent() {
        return "<span style=\"display:inline-block;width:18px;height:18px;\"></span>";
    }

    private static String treeBranch() {
        return "<span style=\"display:inline-block;width:18px;height:18px;\">-</span>";
    }

    /**
     * 获取设为模板栏目选中状态
     *
     * @param oneshotEntity
     * @param moduleEntity
     * @return
     */
    private String getCheckedByCanAddMenuIds(
            OneshotEntity oneshotEntity, ModuleEntity moduleEntity) {
        if (moduleEntity == null || StringUtils.isBlank(moduleEntity.getCanAddMenuIds())) {
            return "";
        }
        boolean contains =
                Arrays.asList(moduleEntity.getCanAddMenuIds().split(","))
                        .contains(String.valueOf(oneshotEntity.getId()));
        if (contains) {
            return " checked=\"checked\"";
        }
        return "";
    }

    /**
     * 获取栏目选中状态
     *
     * @param oneshotEntity
     * @param moduleEntity
     * @return
     */
    private String getCheckedByMenuIds(OneshotEntity oneshotEntity, ModuleEntity moduleEntity) {
        if (moduleEntity == null || StringUtils.isBlank(moduleEntity.getMenuIds())) {
            return "";
        }
        boolean contains =
                Arrays.asList(moduleEntity.getMenuIds().split(","))
                        .contains(String.valueOf(oneshotEntity.getId()));
        if (contains) {
            return " checked=\"checked\"";
        }
        return "";
    }
}

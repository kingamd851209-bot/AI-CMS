package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.oneshot.MenuModuleEditDto;
import com.xinhongru.requestDto.oneshot.OneshotSwitchDto;
import com.xinhongru.requestDto.oneshot.OneshotTitleDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.responseVo.oneshot.OpenEditOneshotDto;
import com.xinhongru.responseVo.oneshot.SeoDto;
import com.xinhongru.service.module.ModuleService;
import com.xinhongru.service.oneshot.OneShotService;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Description 栏目管理 @ClassName MenuManageController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/24 11:20
 */
@Api("栏目管理")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/menu")
public class MenuManageController {
    private OneShotService oneShotService;
    private ModuleService moduleService;

    @Autowired
    public void setModuleService(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @Autowired
    public void setOneShotService(OneShotService oneShotService) {
        this.oneShotService = oneShotService;
    }

    /**
     * 栏目管理 - 基础配置
     *
     * @param oneshotDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/index")
    @SystemLog(value = "栏目管理 - 进入基础配置页", classId = 3)
    public String index(
            OneshotDto oneshotDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        model.addAttribute(
                "view",
                oneShotService.getAllContentMenuManageAndFrontNotAllFalse(oneshotDto, request));
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 其他配置
     *
     * @param oneshotDto
     * @param model
     * @return
     */
    @GetMapping("/other")
    @SystemLog(value = "栏目管理 - 进入其他配置页", classId = 4)
    public String other(OneshotDto oneshotDto, HttpServletRequest request, Model model) {
        model.addAttribute("view", oneShotService.getAllContentMenu(oneshotDto, request));
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 对外开放
     *
     * @return
     */
    @GetMapping("/open")
    @SystemLog(value = "栏目管理 - 进入栏目管理 - 对外开放页", classId = 10)
    public String open() {
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 对外开放 获取数据
     *
     * @param oneshotDto
     * @return
     */
    @GetMapping("/openGetMenuData")
    @ResponseBody
    public ResultVO<List<OneshotEntity>> openGetMenuData(
            OneshotDto oneshotDto, HttpServletRequest request) {
        return ResultVO.success(oneShotService.getEditableMenu(oneshotDto, request));
    }

    /**
     * 栏目管理 - 修改开关类型的字段
     *
     * @param oneshotSwitchDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @ResponseBody
    @PostMapping("/updateSwitch")
    @SystemLog(
            value = "栏目管理 - 其他配置 - 修改开关类型的字段",
            classIds = {4, 10})
    public ResultVO updateSwitch(
            @RequestBody @Validated OneshotSwitchDto oneshotSwitchDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        return oneShotService.updateSwitch(oneshotSwitchDto, request);
    }

    /**
     * 栏目管理 - 基础配置 -修改排序号
     *
     * @param sortDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @ResponseBody
    @PostMapping("/updateSort")
    @SystemLog(
            value = "栏目管理 - 修改排序号",
            classIds = {3, 10})
    public ResultVO updateSort(
            @RequestBody @Validated SortDto sortDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        return oneShotService.updateSort(sortDto, request);
    }

    /**
     * 栏目管理 - 基础配置 - 快速修改栏目名称
     *
     * @param oneshotTitleDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @ResponseBody
    @PostMapping("/updateTitle")
    @SystemLog(
            value = "栏目管理 - 快速修改栏目名称",
            classIds = {3, 4, 10})
    public ResultVO updateSEO(
            @RequestBody @Validated OneshotTitleDto oneshotTitleDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        return oneShotService.updateTitle(oneshotTitleDto, request);
    }

    /**
     * 栏目管理 - 基础配置 -修改栏目SEO信息
     *
     * @param id
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/seo/{id}")
    @SystemLog(
            value = "栏目管理 - 进入修改栏目SEO信息",
            classIds = {3, 10})
    public String getSeo(
            @PathVariable Integer id,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        OneshotEntity modelById = oneShotService.getModelById(id);
        SeoDto seoDto = new SeoDto();
        if (modelById != null) {
            seoDto.setId(id)
                    .setSeoTitle(modelById.getSeoTitle())
                    .setSeoKeywords(modelById.getSeoKeywords())
                    .setSeoDescription(modelById.getSeoDescription());
        }
        model.addAttribute("view", seoDto);
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 保存SEO提交信息
     *
     * @param seoDto
     * @param httpServletRequest
     * @param response
     * @param model
     * @return
     */
    @ResponseBody
    @PostMapping("/seo")
    @SystemLog(
            value = "栏目管理 - 保存SEO信息",
            classIds = {3, 10})
    public ResultVO updateSEO(
            @RequestBody @Validated SeoDto seoDto,
            HttpServletRequest httpServletRequest,
            HttpServletResponse response,
            Model model) {
        return oneShotService.updateSEO(seoDto, httpServletRequest);
    }

    /**
     * 栏目管理 - 进入栏目修改页面
     *
     * @param id
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/edit/{id}")
    @SystemLog(
            value = "栏目管理 - 进入栏目修改页面",
            classIds = {3, 10})
    public String edit(
            @PathVariable Integer id,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        OneshotEntity modelById = oneShotService.getModelById(id);
        EditOneshotDto editOneshotDto = new EditOneshotDto();
        editOneshotDto.setOneshotEntities(
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request));
        if (modelById != null) {
            editOneshotDto.setId(id);
            editOneshotDto.setParentId(modelById.getParentId());
            editOneshotDto.setClassName(modelById.getClassName());
            editOneshotDto.setClassEnName(modelById.getClassEnName());
            editOneshotDto.setHasManageChannel(modelById.getHasManageChannel());
            editOneshotDto.setHasFrontChannel(modelById.getHasFrontChannel());
            editOneshotDto.setShowPosition(modelById.getShowPosition());
            editOneshotDto.setPageType(modelById.getPageType());
            editOneshotDto.setUrl(modelById.getUrl());
            editOneshotDto.setSetting(modelById.getSetting());
            editOneshotDto.setListPageUrl(modelById.getListPageUrl());
            editOneshotDto.setListPageTemplate(modelById.getListPageTemplate());
            editOneshotDto.setDetailPageUrl(modelById.getDetailPageUrl());
            editOneshotDto.setDetailPageTemplate(modelById.getDetailPageTemplate());
            editOneshotDto.setIdentifies(modelById.getIdentifies());
        }
        model.addAttribute("view", editOneshotDto);
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 对外开放 - 进入通过模块添加栏目页面
     *
     * @param parentId
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/open/add/{parentId}")
    @SystemLog(
            value = "栏目管理 - 对外开放 - 进入通过模块添加栏目页面",
            classIds = {10})
    public String openAdd(
            @PathVariable Integer parentId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        OneshotEntity modelById = oneShotService.getModelById(parentId);
        if (modelById == null) {
            throw new BusinessException("目标栏目不存在");
        }
        if (!modelById.getIsCanAddSub()) {
            throw new BusinessException("当前栏目不具备添加子栏目的权限");
        }

        MenuModuleEditDto menuModuleEditDto = new MenuModuleEditDto();
        menuModuleEditDto.setParentId(parentId);
        menuModuleEditDto.setModuleEntities(moduleService.getAllList(parentId));
        // 自定义数据源 - 业务分类
        menuModuleEditDto.setBusinessEntities(oneShotService.getBusinessEntityList());
        model.addAttribute("view", menuModuleEditDto);
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 对外开放 - 通过模块添加栏目
     *
     * @param menuModuleEditDto
     * @param request
     * @param response
     * @return
     */
    @ResponseBody
    @PostMapping("/open/add")
    @SystemLog(
            value = "栏目管理 - 对外开放 - 通过模块添加栏目",
            classIds = {10})
    public ResultVO addEditByModuleId(
            @RequestBody @Validated MenuModuleEditDto menuModuleEditDto,
            HttpServletRequest request,
            HttpServletResponse response) {
        return oneShotService.addEditByModuleId(menuModuleEditDto, request);
    }

    /**
     * 栏目管理 - 对外开放 - 进入通过模块修改栏目页面
     *
     * @param id
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/open/edit/{id}")
    @SystemLog(
            value = "栏目管理 - 对外开放 - 进入通过模块修改栏目页面",
            classIds = {10})
    public String openEdit(
            @PathVariable Integer id,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        OneshotEntity modelById = oneShotService.getModelById(id);
        if (modelById == null) {
            throw new BusinessException("不合法的请求");
        }
        EditOneshotDto editOneshotDto = new EditOneshotDto();
        editOneshotDto.setId(id);
        editOneshotDto.setParentId(modelById.getParentId());
        editOneshotDto.setClassName(modelById.getClassName());
        editOneshotDto.setClassEnName(modelById.getClassEnName());
        editOneshotDto.setHasManageChannel(modelById.getHasManageChannel());
        editOneshotDto.setHasFrontChannel(modelById.getHasFrontChannel());
        editOneshotDto.setPageType(modelById.getPageType());
        editOneshotDto.setUrl(modelById.getUrl());
        editOneshotDto.setSetting(modelById.getSetting());
        editOneshotDto.setListPageUrl(modelById.getListPageUrl());
        editOneshotDto.setDetailPageUrl(modelById.getDetailPageUrl());
        editOneshotDto.setIdentifies(modelById.getIdentifies());
        editOneshotDto.setShowPosition(modelById.getShowPosition());
        model.addAttribute("view", editOneshotDto);
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 对外开放 - 保存通过模块修改栏目信息
     *
     * @param openEditOneshotDto
     * @param httpServletRequest
     * @param response
     * @return
     */
    @ResponseBody
    @PostMapping("/open/edit")
    @SystemLog(
            value = "栏目管理 - 对外开放 - 保存通过模块修改栏目信息",
            classIds = {10})
    public ResultVO openEdit(
            @RequestBody @Validated OpenEditOneshotDto openEditOneshotDto,
            HttpServletRequest httpServletRequest,
            HttpServletResponse response) {
        return oneShotService.updateEditOpen(openEditOneshotDto, httpServletRequest);
    }

    /**
     * 栏目管理 - 进入添加栏目页面
     *
     * @param parentId
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/add/{parentId}")
    @SystemLog(
            value = "栏目管理 - 进入添加栏目页面",
            classIds = {3, 10})
    public String add(
            @PathVariable Integer parentId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        OneshotEntity modelById = oneShotService.getModelById(parentId);
        EditOneshotDto editOneshotDto = new EditOneshotDto();
        editOneshotDto.setId(0);
        editOneshotDto.setHasManageChannel(true);
        editOneshotDto.setHasFrontChannel(true);
        editOneshotDto.setPageType(0);

        editOneshotDto.setOneshotEntities(
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request));
        if (modelById != null) {
            editOneshotDto.setParentId(parentId);
        } else {
            editOneshotDto.setParentId(0);
        }
        model.addAttribute("view", editOneshotDto);
        return "redirect:/admin/v2/cms/menus";
    }

    /**
     * 栏目管理 - 添加栏目保存
     *
     * @param editOneshotDto
     * @param httpServletRequest
     * @param response
     * @return
     */
    @ResponseBody
    @PostMapping("/edit")
    @SystemLog(
            value = "栏目管理 - 添加栏目保存",
            classIds = {3})
    public ResultVO edit(
            @RequestBody @Validated EditOneshotDto editOneshotDto,
            HttpServletRequest httpServletRequest,
            HttpServletResponse response) {
        return oneShotService.updateEdit(editOneshotDto, httpServletRequest);
    }

    /**
     * 栏目管理 - 删除栏目
     *
     * @param idDto
     * @param httpServletRequest
     * @param response
     * @return
     */
    @ResponseBody
    @PostMapping("/delete")
    @SystemLog(
            value = "栏目管理 - 删除栏目",
            classIds = {3, 10})
    public ResultVO edit(
            @RequestBody @Validated IdDto idDto,
            HttpServletRequest httpServletRequest,
            HttpServletResponse response) {
        return oneShotService.deleteMenu(idDto, httpServletRequest);
    }
}

package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.model.OptionDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.fieldHtmlVo.FieldHtmlVo;
import com.xinhongru.responseVo.fieldHtmlVo.ModelFieldHtmlVo;
import com.xinhongru.responseVo.model.ModelManageSeoVo;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContextService;

import io.swagger.annotations.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * @Description 栏目管理 @ClassName MenuManageController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/24 11:20
 */
@Api("内容管理")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/model_manage")
public class ModelManageController {
    private OneShotService oneShotService;
    private ContentService contentService;
    private LogService logService;
    private SiteContextService siteContextService;

    @Autowired
    public void setOneShotService(OneShotService oneShotService) {
        this.oneShotService = oneShotService;
    }

    @Autowired
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setSiteContextService(SiteContextService siteContextService) {
        this.siteContextService = siteContextService;
    }

    /**
     * 列表栏目 - 进入列表页面
     *
     * @param classid
     * @param model
     * @return
     */
    @SystemLog(value = "列表栏目 - 进入列表页面", classIdParamName = "classid")
    @ApiOperation(value = "列表数据绑定")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "xgid", value = "关联Id"),
        @ApiImplicitParam(name = "page", value = "页码"),
        @ApiImplicitParam(name = "limit", value = "每页显示条数")
    })
    @GetMapping("/{classid}")
    public String list(
            @PathVariable int classid,
            HttpServletRequest request,
            @RequestParam(name = "xgid", required = false, defaultValue = "0") int xgid,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            Model model,
            HttpServletResponse httpServletResponse) {
        OneshotEntity modelById = oneShotService.getModelById(classid);
        if (modelById == null) {
            return "redirect:/admin/login";
        }
        if (StringUtils.isBlank(modelById.getModelField())) {
            httpServletResponse.setContentType("text/html;charset=utf-8");
            PrintWriter out = null;
            try {
                out = httpServletResponse.getWriter();
                out.print(
                        "<script>alert('尚未设置字段，请先设置字段');window.location.href='/admin/menu/index?go="
                                + modelById.getId()
                                + "';</script>");
                out.flush();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
                return "redirect:/admin/menu/index?go=" + modelById.getId() + "";
            }
        }
        ContentCond contentCond = new ContentCond();
        contentCond.setClassid(classid);
        contentCond.setXgid(xgid);

        ModelFieldHtmlVo modelFieldHtmlVo =
                oneShotService.getContentList(modelById, contentCond, page, limit, request);

        if (request.getParameter("status") != null) {
            model.addAttribute(
                    "status", Integer.valueOf(request.getParameter("status")).intValue());
        }
        model.addAttribute("view", modelFieldHtmlVo);
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 列表栏目 - 进入列表修改数据
     *
     * @param id
     * @param model
     * @return
     */
    @SystemLog(value = "列表栏目 - 进入列表修改数据", classIdParamName = "classid")
    @GetMapping("/{classid}/edit/{id}")
    public String listEdit(
            @PathVariable Integer classid,
            @PathVariable Integer id,
            Model model,
            HttpServletRequest request,
            HttpServletResponse httpServletResponse) {
        ContentEntity contentEntity;
        OneshotEntity modelById = oneShotService.getModelById(classid);
        if (id == 0) {
            contentEntity = new ContentEntity();
        } else {
            contentEntity = contentService.getArticleById(id);

            // 栏目或数据不合法
            if (contentEntity == null || modelById == null) {
                return "redirect:/admin/login";
            }
            // 数据不合法
            if (!contentEntity.getClassid().equals(classid)) {
                return "redirect:/admin/login";
            }
            // 未设置字段
            if (StringUtils.isBlank(modelById.getModelField())) {
                return "redirect:/admin/login";
            }
        }

        FieldHtmlVo fieldHtmlVo =
                oneShotService.getContentFieldDefinitionModelList(
                        modelById, contentEntity, request);
        ModelFieldHtmlVo modelFieldHtmlVo = new ModelFieldHtmlVo();
        modelFieldHtmlVo.setClassid(classid);
        modelFieldHtmlVo.setXgid(contentEntity.getXgid());
        modelFieldHtmlVo.setId(id);
        modelFieldHtmlVo.setClassName(modelById.getClassName());
        modelFieldHtmlVo.setHtml(fieldHtmlVo.getHtml());
        modelFieldHtmlVo.setScript(fieldHtmlVo.getScript());

        modelFieldHtmlVo.setIsCheck(fieldHtmlVo.getIsCheck());

        model.addAttribute("view", modelFieldHtmlVo);
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 列表栏目 - 进入列表数据添加页面
     *
     * @param model
     * @return
     */
    @SystemLog(value = "列表栏目 - 进入列表数据添加页面", classIdParamName = "classid")
    @GetMapping("/{classid}/add")
    public String listEdit(
            @PathVariable Integer classid, Model model, HttpServletRequest httpServletRequest) {
        ContentEntity contentEntity = new ContentEntity();
        contentEntity.setId(0);
        OneshotEntity modelById = oneShotService.getModelById(classid);

        FieldHtmlVo fieldHtmlVo =
                oneShotService.getContentFieldDefinitionModelList(
                        modelById, contentEntity, httpServletRequest);
        ModelFieldHtmlVo modelFieldHtmlVo = new ModelFieldHtmlVo();
        modelFieldHtmlVo.setClassid(classid);
        if (httpServletRequest.getParameter("xgid") != null) {
            modelFieldHtmlVo.setXgid(
                    Integer.valueOf(httpServletRequest.getParameter("xgid")).intValue());
        } else {
            modelFieldHtmlVo.setXgid(0);
        }
        modelFieldHtmlVo.setId(0);
        modelFieldHtmlVo.setClassName(modelById.getClassName());
        modelFieldHtmlVo.setHtml(fieldHtmlVo.getHtml());
        modelFieldHtmlVo.setScript(fieldHtmlVo.getScript());

        modelFieldHtmlVo.setIsCheck(fieldHtmlVo.getIsCheck());

        model.addAttribute("view", modelFieldHtmlVo);
        return "redirect:/admin/v2/cms/content";
    }

    /** 列表栏目 - 添加/修改数据保存 */
    @SystemLog(value = "列表栏目 - 添加/修改数据保存", classIdParamName = "contentEntity.classid")
    @ResponseBody
    @PostMapping(value = "/{classid}/edit")
    public ResultVO saveContent(
            @RequestBody @Validated ContentEntity contentEntity,
            HttpServletRequest httpServletRequest) {
        try {
            requireLegacyWriteAllowed(httpServletRequest);
            ResultVO resultVO;
            if (contentEntity.getEntryIntoForceTime() != null
                    && contentEntity.getExpirationTime() != null
                    && contentEntity
                                    .getEntryIntoForceTime()
                                    .compareTo(contentEntity.getExpirationTime())
                            > 0) {
                return ResultVO.fail(
                        ErrorCodeEnum.EXPIRATION_TIME_MUST_BE_GREATER_THAN_ENTRY_INTO_FORCE_TIME);
            } else if (contentEntity.getId() == null || contentEntity.getId() == 0) {
                resultVO = contentService.addArticle(contentEntity, httpServletRequest);
            } else {
                resultVO = contentService.updateContentByCid(contentEntity, httpServletRequest);
            }
            return resultVO;
        } catch (Exception e) {
            log.error(e.getMessage());
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    /**
     * 内容管理 -修改排序号
     *
     * @param sortDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @SystemLog(value = "列表栏目 - 修改排序号", classIdParamName = "classid")
    @ResponseBody
    @PostMapping("/{classid}/updateSort")
    public ResultVO updateSort(
            @PathVariable("classid") Integer classid,
            @RequestBody @Validated SortDto sortDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        requireLegacyWriteAllowed(request);
        return contentService.updateSort(classid, sortDto, request);
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
    @SystemLog(value = "列表栏目 - 进入SEO信息修改页面", classIdParamName = "classid")
    @GetMapping("/{classid}/seo/{id}")
    public String getSeo(
            @PathVariable Integer classid,
            @PathVariable Integer id,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        ContentEntity contentEntity = contentService.getSeoById(classid, id);
        ModelManageSeoVo modelManageSeoVo = new ModelManageSeoVo();
        if (contentEntity != null) {
            modelManageSeoVo
                    .setClassid(contentEntity.getClassid())
                    .setId(id)
                    .setSeoTitle(contentEntity.getSeoTitle())
                    .setSeoKeywords(contentEntity.getSeoKeywords())
                    .setSeoDescription(contentEntity.getSeoDescription());
        } else {
            modelManageSeoVo.setClassid(contentEntity.getClassid()).setId(id);
        }
        model.addAttribute("view", modelManageSeoVo);
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 保存SEO提交信息
     *
     * @param manageSeoVo
     * @param request
     * @param response
     * @param model
     * @return
     */
    @SystemLog(value = "列表栏目 - 保存SEO信息", classIdParamName = "classid")
    @ResponseBody
    @PostMapping("/{classid}/seo")
    public ResultVO updateTitle(
            @PathVariable Integer classid,
            @RequestBody @Validated ModelManageSeoVo manageSeoVo,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (manageSeoVo == null || manageSeoVo.getId() == null) {
            throw new IllegalArgumentException("内容 ID 无效");
        }
        if (manageSeoVo.getClassid() != null && !classid.equals(manageSeoVo.getClassid())) {
            throw new IllegalArgumentException("栏目与内容数据不一致");
        }
        ContentEntity content = contentService.getArticleById(manageSeoVo.getId());
        if (content == null || !classid.equals(content.getClassid())) {
            throw new IllegalArgumentException("栏目与内容数据不一致");
        }
        manageSeoVo.setClassid(classid);
        requireLegacyWriteAllowed(request);
        return contentService.updateSEO(manageSeoVo, request);
    }

    /**
     * 单一删除数据
     *
     * @param idDto
     * @param request
     * @param response
     * @return
     */
    @SystemLog(value = "列表栏目 - 单一数据删除", classIdParamName = "classid")
    @ResponseBody
    @PostMapping("/{classid}/delete")
    public ResultVO edit(
            @PathVariable("classid") Integer classid,
            @RequestBody @Validated IdDto idDto,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireLegacyWriteAllowed(request);
        return contentService.deleteInfoById(classid, idDto, request);
    }

    /**
     * 各种推荐之类的操作
     *
     * @param classid
     * @param optionDto
     * @param httpServletRequest
     * @param httpServletResponse
     * @return
     */
    @SystemLog(value = "列表栏目 - 各种推荐之类的操作", classIdParamName = "classid")
    @ResponseBody
    @PostMapping("/{classid}/option")
    public ResultVO updateOption(
            @PathVariable Integer classid,
            @RequestBody @Validated OptionDto optionDto,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        requireLegacyWriteAllowed(httpServletRequest);
        return contentService.updateOption(optionDto, classid, httpServletRequest);
    }

    private void requireLegacyWriteAllowed(HttpServletRequest request) {
        if (siteContextService == null) {
            throw new IllegalStateException("站点上下文服务未初始化");
        }
        siteContextService.requireCurrentSite(request);
    }

    /**
     * 数据导出
     *
     * @param classid
     * @param contentCond
     * @param httpServletRequest
     * @param httpServletResponse
     * @return
     */
    @SystemLog(value = "栏目列表 - 进入数据导出页面", classIdParamName = "classid")
    @GetMapping("/{classid}/output")
    public void outputExcel(
            @PathVariable Integer classid,
            ContentCond contentCond,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        contentService.outputExcel(classid, contentCond, httpServletRequest, httpServletResponse);
    }
}

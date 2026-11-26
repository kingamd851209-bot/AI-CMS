package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.fieldHtmlVo.FieldHtmlVo;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.utils.AuthorityUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/admin/menu_manage")
public class MenuEditController {
    private OneShotService oneShotService;

    private LogService logService;

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setOneShotService(OneShotService oneShotService) {
        this.oneShotService = oneShotService;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileController.class);

    /**
     * 单篇
     *
     * @param id
     * @param model
     * @return
     */
    @SystemLog(value = "单篇栏目 - 进入修改页面", classIdParamName = "id")
    @GetMapping("/{id}")
    public String edit(
            @PathVariable Integer id,
            Model model,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        OneshotEntity modelById = oneShotService.getModelById(id);
        if (modelById == null) {
            return "redirect:/admin/logout";
        }
        if (StringUtils.isBlank(modelById.getMenuField())) {
            httpServletResponse.setContentType(WebConst.CONTENT_TYPE_TEXT_HTML_UTF8);
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
        // 栏目权限判断
        String responseResult =
                AuthorityUtils.checkAuthorityForView(id, httpServletRequest, httpServletResponse);
        if (responseResult != null) {
            return responseResult;
        }

        FieldHtmlVo fieldHtmlVo = oneShotService.getContentFieldDefinitionList(modelById);
        fieldHtmlVo.setId(id).setClassName(modelById.getClassName());
        model.addAttribute("view", fieldHtmlVo);
        return "redirect:/admin/v2/cms/menus";
    }

    /** 保存内容 */
    @SystemLog(value = "单篇栏目 - 保存单篇内容", classIdParamName = "oneshotEntity.id")
    @ResponseBody
    @PostMapping(value = "/edit")
    public ResultVO saveContent(
            @RequestBody @Validated OneshotEntity oneshotEntity,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        if (oneshotEntity.getExtensionFields() != null) {
            throw new IllegalArgumentException("扩展字段请通过原生单页保存接口按字段提交，旧接口不接受扩展JSON");
        }
        try {
            if (oneshotEntity.getId() == null
                    || !AuthorityUtils.hasGroupSetting(httpServletRequest, oneshotEntity.getId())
                    || !AuthorityUtils.hasOtherSetting(
                            httpServletRequest,
                            oneshotEntity.getId(),
                            WebConst.UPDATE_AUTHORITY_ID))
                return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
            OneshotEntity existing = oneShotService.getModelById(oneshotEntity.getId());
            if (existing == null) return ResultVO.fail(ErrorCodeEnum.MENU_DATA_NOT_FOUND);
            oneshotEntity.setExtensionFields(existing.getExtensionFields());
            ContentExtensionFields.validateRequired(
                    oneshotEntity.getExtensionFields(),
                    ContentModelCodec.read(existing.getMenuField()));
            if (StringUtils.isBlank(oneshotEntity.getSingleLine1())) {
                oneshotEntity.setSingleLine1("");
            }
            if (StringUtils.isBlank(oneshotEntity.getSingleLine6())) {
                oneshotEntity.setSingleLine6("");
            }
            if (StringUtils.isBlank(oneshotEntity.getSingleLine5())) {
                oneshotEntity.setSingleLine5("");
            }
            return oneShotService.update(oneshotEntity, httpServletRequest);
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }
}

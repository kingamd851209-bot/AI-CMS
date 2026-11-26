package com.xinhongru.controller.admin;

import com.alibaba.fastjson.JSON;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.entity.*;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.service.CheckRecordService;
import com.xinhongru.service.ContentHistoryService;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.service.user.UserService;
import com.xinhongru.utils.AuthorityUtils;
import com.xinhongru.utils.TaleUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/** 数据版本历史 */
@Api("数据版本历史")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/check/")
public class CheckController {
    @Autowired private CheckRecordService checkRecordService;

    @Autowired private OneShotMapper oneShotDao;

    @Autowired private UserService userService;

    @Autowired private ContentHistoryService contentHistoryService;

    /**
     * 获取数据历史版本
     *
     * @return
     */
    @GetMapping("/index")
    public String index(
            @RequestParam(value = "page", required = false) Integer pageNo,
            @RequestParam(value = "title", required = false) String title,
            HttpServletRequest request,
            Model model) {
        if (pageNo == null) {
            pageNo = 1;
        }
        // 当前用户权限
        UserEntity loginUser = TaleUtils.getLoginUser(request);
        loginUser = userService.getUserInfoById(loginUser.getId(), false);
        List<OneshotEntity> menuList = oneShotDao.getAllNewsMenu();
        if (CollectionUtils.isEmpty(menuList)) {
            return "redirect:/admin/v2/cms/content";
        }
        // 获取有审核权限栏目 Id
        List<Integer> powerMenuId = new ArrayList<>();
        for (OneshotEntity menu : menuList) {
            if (AuthorityUtils.hasOtherSetting(
                    loginUser, menu.getId(), WebConst.CHECK_AUTHORITY_ID)) {
                powerMenuId.add(menu.getId());
            }
            ;
        }
        if (CollectionUtils.isEmpty(powerMenuId)) {
            return "redirect:/admin/v2/cms/content";
        }
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 获取数据历史版本明细
     *
     * @param model
     * @return
     */
    @GetMapping("/detail")
    public String detail(@RequestParam("historyId") Integer historyId, Model model) {
        ContentHistoryEntity entity = contentHistoryService.getById(historyId);
        if (entity == null || MemberCredentials.isDirectory(entity.getMenuId())) {
            return "redirect:/admin/v2/cms/content";
        }
        entity.setFields(JSON.parseArray(entity.getComparison(), LogTableDto.class));
        model.addAttribute("view", entity);
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 获取数据历史版本明细
     *
     * @param model
     * @return
     */
    @GetMapping("/check")
    public String check(@RequestParam("id") Integer id, Model model) {
        CheckRecordEntity entity = checkRecordService.getById(id);
        if (entity == null) {
            return "redirect:/admin/v2/cms/content";
        }
        model.addAttribute("entity", entity);
        return "redirect:/admin/v2/cms/content";
    }

    /**
     * 获取数据历史版本明细
     *
     * @return
     */
    @PostMapping("/checkSubmit")
    @ResponseBody
    public ResultVO<?> checkSubmit(
            @RequestParam("id") Integer id,
            @RequestParam("checkStatus") Integer checkStatus,
            @RequestParam("checkDescription") String checkDescription,
            HttpServletRequest request) {
        throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException.conflict(
                "NATIVE_CONTENT_ROUTE_REQUIRED", "旧审批接口已停用，请使用原生内容工作台校验当前字段并审核");
    }
}

package com.xinhongru.controller.admin;

import com.xinhongru.exception.BusinessException;
import com.xinhongru.service.logOperation.LogOperationService;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @Description @ClassName LogOperationController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/6/5 11:32
 */
@Api("内容操作日志")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/log-operation")
public class LogOperationController {
    private LogOperationService logOperationService;

    @Autowired
    public void setLogOperationService(LogOperationService logOperationService) {
        this.logOperationService = logOperationService;
    }

    /**
     * 获取操作日志明细
     *
     * @param classId
     * @param infoId
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/index/{classId}/{infoId}")
    public String index(
            @PathVariable("classId") Integer classId,
            @PathVariable("infoId") Integer infoId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        model.addAttribute("view", logOperationService.getListByClassIdAndInfoId(classId, infoId));
        return "redirect:/admin/v2/system/logs";
    }
}

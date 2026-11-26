package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.dto.LogDto;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.service.log.LogService;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @Description @ClassName LogController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/3
 * 20:56
 */
@Api("系统日志")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/log")
public class LogController {
    private LogService logService;

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    /**
     * 系统日志 - 查看日志列表
     *
     * @param logDto
     * @param bindingResult
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/index")
    @SystemLog(value = "系统日志 - 进入日志列表页面", classId = 13)
    public String index(
            @Validated LogDto logDto,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        model.addAttribute("view", logService.getLogList(logDto, request));
        return "redirect:/admin/v2/system/logs";
    }

    /**
     * 系统日志 - 查看日志明细
     *
     * @param idDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/details/{id}")
    @SystemLog(value = "系统日志 - 查看日志明细", classId = 13)
    public String index(
            @Validated IdDto idDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        model.addAttribute("view", logService.getLogDetails(idDto));
        return "redirect:/admin/v2/system/logs";
    }
}

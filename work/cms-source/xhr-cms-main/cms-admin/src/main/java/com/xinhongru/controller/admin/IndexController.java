package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Created at 2021/4/30. */
@Api("后台首页")
@Controller("adminIndexController")
public class IndexController {
    /**
     * 进入后台首页
     *
     * @param request
     * @param model
     * @return
     */
    @SystemLog(value = "进入后台首页", classId = 1)
    @ApiOperation("进入首页")
    @GetMapping({"/admin", "/admin/index"})
    public String index(HttpServletRequest request, Model model) {
        return "redirect:/admin/v2";
    }

    /**
     * 进入前端首页
     *
     * @param model
     * @return
     */
    @GetMapping(value = {"/", "/index.html"})
    public String index(Model model) {
        return "redirect:/admin/v2";
    }
}

package com.xinhongru.controller.admin;

import com.alibaba.fastjson.JSONObject;
import com.xinhongru.annotation.SystemLog;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.api.admin.v2.SystemAdminV2ApiController;
import com.xinhongru.site.context.SiteContextService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Created at 2021/4/30. */
@Api("系统设置")
@Controller
@RequestMapping("/admin/system")
public class SettingController {
    @Autowired private SystemAdminV2ApiController settings;
    @Autowired private SiteContextService sites;

    /**
     * 系统设置 - 进入设置页
     *
     * @return
     */
    @SystemLog(value = "系统设置 - 进入设置页", classId = 7)
    @ApiOperation("进入设置页")
    @GetMapping(value = "/setting")
    public String setting(HttpServletRequest request) {
        sites.requireSuperAdmin(request);
        return "redirect:/admin/v2/system/settings";
    }

    /**
     * 系统设置 - 保存系统设置
     *
     * @param jsonObject
     * @param request
     * @return
     */
    @ApiOperation("保存系统设置")
    @PostMapping(value = "/setting")
    @ResponseBody
    public ResultVO saveSetting(@RequestBody JSONObject jsonObject, HttpServletRequest request) {
        settings.saveSettings(request, jsonObject.getInnerMap());
        return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
    }
}

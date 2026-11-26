package com.xinhongru.controller.admin;

import com.alibaba.fastjson.JSONObject;
import com.xinhongru.utils.WechatUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 宋杰
 * @date 2022/8/16
 */
@Slf4j
@Api("微信相关接口")
@RestController
@ConditionalOnProperty(
        prefix = "cms.wechat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequestMapping(value = {"/api/admin/wechat", "/admin/wechat"})
public class WechatController {
    private final WechatUtils wechatUtils;

    public WechatController(WechatUtils wechatUtils) {
        this.wechatUtils = wechatUtils;
    }

    @ApiOperation("微文导入")
    @PostMapping(value = "/wechatUpload")
    public JSONObject wechatUpload(
            @ApiParam(name = "url", value = "微文地址", required = true)
                    @RequestParam(name = "url", required = true)
                    String url) {
        log.info("--> 进入微文导入，url={" + url + "}");
        return wechatUtils.wechatUpload(url);
    }
}

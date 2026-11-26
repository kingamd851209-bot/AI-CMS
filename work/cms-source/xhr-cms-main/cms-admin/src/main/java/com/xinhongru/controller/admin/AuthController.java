package com.xinhongru.controller.admin;

import com.xinhongru.constant.WebConst;
import com.xinhongru.requestDto.LoginDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.security.SessionIdentityService;
import com.xinhongru.service.imageCode.ImageCodeService;
import com.xinhongru.service.imageCode.ImageCodeService.Purpose;
import com.xinhongru.service.user.UserService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Created at 2021/4/30.
 *
 * @author 永远的神
 */
@Slf4j
@Api("登录相关接口")
@Controller
@Validated
public class AuthController {
    @Autowired private SessionIdentityService sessions;

    @Value("${cms.admin.frontend-base-url:}")
    private String frontendBaseUrl;

    private UserService userService;

    private ImageCodeService imageCodeService;

    @Autowired
    public void setImageCodeService(ImageCodeService imageCodeService) {
        this.imageCodeService = imageCodeService;
    }

    @Autowired
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @ApiOperation("跳转登录页")
    @GetMapping(value = "/admin/login")
    public void login(HttpServletResponse response) throws IOException {
        if (frontendBaseUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "管理端地址未配置，请设置 cms.admin.frontend-base-url");
        }
        response.sendRedirect(frontendBaseUrl.replaceAll("/+$", "") + "/admin/login");
    }

    @ApiOperation("登录")
    @PostMapping(value = {"/api/admin/login", "/admin/login"})
    @ResponseBody
    public ResultVO toLogin(
            @RequestBody @Validated LoginDto loginDto,
            HttpServletResponse response,
            HttpServletRequest request) {
        ResultVO resultVO =
                imageCodeService.checkImageCode(
                        Purpose.ADMIN_LOGIN,
                        loginDto.getImageCodeKey(), loginDto.getImageCodeValue());
        if (!resultVO.getSuccess()) {
            return resultVO;
        }
        return userService.toLogin(loginDto, response, request);
    }

    @ApiOperation("获取后台登录加密公钥")
    @GetMapping(value = {"/api/admin/login/public-key", "/admin/login/public-key"})
    @ResponseBody
    public ResultVO<LoginPublicKeyResponse> loginPublicKey() {
        return ResultVO.success(new LoginPublicKeyResponse(WebConst.RSA_PUBLIC_KEY));
    }

    public record LoginPublicKeyResponse(String publicKey) {}

    /**
     * 注销
     *
     * @param session
     * @param response
     */
    @RequestMapping("/admin/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        try {
            response.sendRedirect("/admin/login");
        } catch (IOException e) {
            e.printStackTrace();
            log.error("注销失败", e);
        }
    }
}

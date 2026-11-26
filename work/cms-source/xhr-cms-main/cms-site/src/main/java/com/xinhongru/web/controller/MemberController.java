package com.xinhongru.web.controller;

import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.requestDto.imageCodeDto.ImageCodeDto;
import com.xinhongru.responseVo.imageCode.ImageCodeVO;
import com.xinhongru.security.SessionCsrfService;
import com.xinhongru.security.SessionIdentityService;
import com.xinhongru.service.imageCode.ImageCodeService;
import com.xinhongru.service.imageCode.ImageCodeService.Purpose;
import com.xinhongru.service.member.MemberService;
import com.xinhongru.web.pojo.MemberLoginDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

/**
 * Browser members use the same Redis-backed session cookie as administrators, with a separate
 * identity realm.
 */
@RestController
@RequestMapping("/member")
public class MemberController {
    private final MemberService members;
    private final ImageCodeService captchas;
    private final SessionIdentityService sessions;
    private final SessionCsrfService csrf;

    public MemberController(
            MemberService members,
            ImageCodeService captchas,
            SessionIdentityService sessions,
            SessionCsrfService csrf) {
        this.members = members;
        this.captchas = captchas;
        this.sessions = sessions;
        this.csrf = csrf;
    }

    @GetMapping("/captcha")
    public ResultVO<ImageCodeVO> captcha(@Validated ImageCodeDto dimensions) {
        return captchas.getImageCode(Purpose.MEMBER_LOGIN, dimensions);
    }

    @PostMapping("/login")
    public ResultVO login(
            @RequestBody MemberLoginDto credentials,
            HttpServletRequest request,
            HttpServletResponse response) {
        csrf.requireValidWriteRequest(request);
        ResultVO result =
                captchas.checkImageCode(
                        Purpose.MEMBER_LOGIN, credentials.getImageCodeKey(), credentials.getImageCodeValue());
        return result.getSuccess() ? members.toLogin(credentials, response, request) : result;
    }

    @RequestMapping(
            value = "/isLogin",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResultVO current(HttpServletRequest request) {
        return members.isLogin(request);
    }

    @PostMapping("/logOut")
    public ResultVO logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return ResultVO.success();
    }
}

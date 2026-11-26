package com.xinhongru.service.member;

import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.web.pojo.MemberLoginDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Service;

/**
 * @Description @Copyright (c) 1998-2023 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName MemberService @Author guoyangyang
 * <519156724@qq.com> @Version V1.0.0 @Since 1.0 @Date 2024/7/25 16:30
 */
@Service
public interface MemberService {
    /**
     * 会员登录
     *
     * @param loginDto
     * @param response
     * @param request
     * @return
     */
    ResultVO toLogin(
            MemberLoginDto loginDto, HttpServletResponse response, HttpServletRequest request);

    /**
     * 判断用户是否登录
     *
     * @param httpServletRequest
     * @return
     */
    ResultVO isLogin(HttpServletRequest httpServletRequest);
}

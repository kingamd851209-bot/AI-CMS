package com.xinhongru.controller.admin;

import com.xinhongru.requestDto.imageCodeDto.ImageCodeDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.imageCode.ImageCodeVO;
import com.xinhongru.service.imageCode.ImageCodeService;
import com.xinhongru.service.imageCode.ImageCodeService.Purpose;
import com.xinhongru.utils.RequestOriginUtils;

import io.swagger.annotations.Api;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * @Description @ClassName ImageCodeController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/6 14:00
 */
@Api("图形验证码")
@Slf4j
@RestController
@RequestMapping({"/api/image-code", "/image-code"})
public class ImageCodeController {
    private ImageCodeService imageCodeService;

    @Autowired
    public void setImageCodeService(ImageCodeService imageCodeService) {
        this.imageCodeService = imageCodeService;
    }

    /**
     * 获取图形验证码
     *
     * @param imageCodeDto 图形验证码参数
     * @return 图形验证码对象
     */
    @GetMapping("/get")
    public ResultVO<ImageCodeVO> getImageCode(
            @Validated ImageCodeDto imageCodeDto, HttpServletRequest request) {
        return imageCodeService.getImageCode(purpose(request), imageCodeDto);
    }

    /**
     * 校验验证码是否有效
     *
     * @param codeKey 用户输入值
     * @param codeValue 验证码key key从/get接口中获取
     * @return 图形验证码校验结果
     */
    @PostMapping("/check")
    public ResultVO<Boolean> checkImageCode(
            @RequestParam String codeKey, @RequestParam String codeValue, HttpServletRequest request) {
        return imageCodeService.checkImageCode(purpose(request), codeKey, codeValue);
    }

    private Purpose purpose(HttpServletRequest request) {
        String referrer = request.getHeader("Referer");
        // 旧会员登录页沿用图形验证码通用地址，新模板使用专用入口。
        if (RequestOriginUtils.isSameOrigin(request, referrer)
                && (request.getContextPath() + "/member/login.html")
                        .equals(URI.create(referrer.trim()).getRawPath())) {
            return Purpose.MEMBER_LOGIN;
        }
        return Purpose.ADMIN_LOGIN;
    }
}

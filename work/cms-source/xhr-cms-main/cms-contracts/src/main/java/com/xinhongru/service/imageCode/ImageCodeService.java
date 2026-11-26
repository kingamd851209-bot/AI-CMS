package com.xinhongru.service.imageCode;

import com.xinhongru.requestDto.imageCodeDto.ImageCodeDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.imageCode.ImageCodeVO;

/**
 * @Description 图形验证码服务 @ClassName ImageCodeService @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/6 14:22
 */
public interface ImageCodeService {
    enum Purpose {
        ADMIN_LOGIN,
        MEMBER_LOGIN,
        PUBLIC_FORM
    }
    /**
     * 获取图形验证码
     *
     * @return
     */
    ResultVO<ImageCodeVO> getImageCode(Purpose purpose, ImageCodeDto imageCodeDto);

    /**
     * 校验图形验证码
     *
     * @return
     */
    ResultVO<Boolean> checkImageCode(Purpose purpose, String codeKey, String codeValue);
}

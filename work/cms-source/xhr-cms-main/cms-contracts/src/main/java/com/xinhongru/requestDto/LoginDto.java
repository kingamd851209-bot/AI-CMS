package com.xinhongru.requestDto;

import com.xinhongru.annotation.PasswordStrengthCheck;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 管理员登录对象 @ClassName LoginDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/9
 * 22:07
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class LoginDto implements Serializable {
    @ApiModelProperty(value = "用户名", required = true)
    private String username;

    @ApiModelProperty(value = "密码", required = true)
    @PasswordStrengthCheck(message = "账号或密码不对")
    private String password;

    /** 图形验证码键 */
    @ApiModelProperty(value = "图形验证码键", required = true, example = "")
    private String imageCodeKey;

    @ApiModelProperty(value = "图形验证码值", required = true, example = "")
    private String imageCodeValue;

    @ApiModelProperty(value = "记住我", required = true)
    private String reMemberMe;
}

package com.xinhongru.requestDto.profile;

import com.xinhongru.annotation.PasswordStrengthCheck;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName UserDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/29 20:07
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class UpdatePwdDto implements Serializable {
    @ApiModelProperty(value = "旧密码", required = true)
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @ApiModelProperty(value = "新密码", required = true)
    @NotBlank(message = "新密码不能为空")
    @PasswordStrengthCheck(message = "新密码必须由数字、小写字母、大写字母、特殊字符组成，且长度介于8-24位")
    private String password;

    @ApiModelProperty(value = "确认新密码", required = true)
    @NotBlank(message = "确认新密码不能为空")
    @PasswordStrengthCheck(message = "确认新密码必须由数字、小写字母、大写字母、特殊字符组成，且长度介于8-24位")
    private String repass;
}

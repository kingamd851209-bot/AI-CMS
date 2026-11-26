package com.xinhongru.requestDto.user;

import com.xinhongru.annotation.PasswordStrengthCheck;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName UserDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/27 17:57
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class UserDto implements Serializable {
    /** 主键编号 */
    @ApiModelProperty(value = "用户ID")
    @Min(value = 0, message = "用户ID不能小于0")
    @Max(value = Integer.MAX_VALUE, message = "用户ID不能大于最大数字")
    private Integer id;

    @ApiModelProperty(value = "管理员组ID")
    @Min(value = 0, message = "管理员组ID不能小于0")
    @Max(value = Integer.MAX_VALUE, message = "用户ID不能大于最大数字")
    private Integer groupId;

    /** 用户名 */
    @ApiModelProperty(value = "用户名", required = true)
    @NotBlank(message = "账号不能为空")
    private String username;

    /** 密码 */
    @ApiModelProperty(value = "密码")
    @PasswordStrengthCheck(message = "密码必须由数字、小写字母、大写字母、特殊字符组成，且长度介于8-24位")
    private String password;

    /** 确认密码 */
    @ApiModelProperty(value = "确认密码")
    @PasswordStrengthCheck(message = "确认密码必须由数字、小写字母、大写字母、特殊字符组成，且长度介于8-24位")
    private String repass;

    /** email */
    @ApiModelProperty(value = "邮箱", required = true)
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 用户显示的名称 */
    @ApiModelProperty(value = "姓名", required = true)
    @NotBlank(message = "姓名不能为空")
    private String screenName;
}

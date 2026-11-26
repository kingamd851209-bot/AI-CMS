package com.xinhongru.web.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @Copyright (c) 1998-2023 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName MemberLoginDto @Author guoyangyang
 * <519156724@qq.com> @Version V1.0.0 @Since 1.0 @Date 2024/7/25 16:24
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemberLoginDto implements Serializable {
    @ApiModelProperty(value = "用户名", required = true)
    private String username;

    @ApiModelProperty(value = "密码", required = true)
    private String password;

    /** 图形验证码键 */
    @ApiModelProperty(value = "图形验证码键", required = true, example = "")
    private String imageCodeKey;

    @ApiModelProperty(value = "图形验证码值", required = true, example = "")
    private String imageCodeValue;
}

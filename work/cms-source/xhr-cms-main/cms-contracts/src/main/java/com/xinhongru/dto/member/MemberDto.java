package com.xinhongru.dto.member;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @Copyright (c) 1998-2022 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName MemberDto @Author guoyangyang <519156724@qq.com> @Version
 * V1.0.0 @Since 1.0 @Date 2023/4/13 20:56
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class MemberDto implements Serializable {
    @ApiModelProperty(value = "会员ID")
    private Integer id;

    @ApiModelProperty(value = "登录名")
    private String userName;
}

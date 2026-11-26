package com.xinhongru.web.resonseVo;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className ChildCompanyDTO
 * @date 2024/3/5 10:54
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChildCompanyDTO implements Serializable {
    @ApiModelProperty(value = "访问地址")
    private String url;

    @ApiModelProperty(value = "公司名称")
    private String companyName;
}

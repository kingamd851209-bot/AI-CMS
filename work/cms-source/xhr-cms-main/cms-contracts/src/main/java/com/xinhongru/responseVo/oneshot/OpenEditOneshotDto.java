package com.xinhongru.responseVo.oneshot;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 添加修改栏目响应对象 @ClassName EditOneshotDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/26 11:46
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class OpenEditOneshotDto implements Serializable {
    /** 栏目ID */
    private Integer id;

    /** 栏目名称 */
    @ApiModelProperty(value = "栏目名称")
    @NotBlank(message = "栏目名称不能为空")
    private String className;

    /** 栏目英文名称 */
    @ApiModelProperty(value = "栏目英文名称")
    private String classEnName;

    /** 专题栏目显示位置 */
    private String showPosition;
}

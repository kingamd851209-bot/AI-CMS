package com.xinhongru.responseVo.imageCode;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 图形验证码响应对象 @ClassName ImageCodeVO @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/6 14:26
 */
@Data
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@ApiModel
public class ImageCodeVO implements Serializable {
    private static final long serialVersionUID = 2378159296657754547L;

    @ApiModelProperty(value = "图形验证码键")
    private String codeKey;

    @ApiModelProperty(value = "base64的图形验证码")
    private String base64Image;
}

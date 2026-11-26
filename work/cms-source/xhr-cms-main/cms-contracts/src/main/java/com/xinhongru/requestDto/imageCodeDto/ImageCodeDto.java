package com.xinhongru.requestDto.imageCodeDto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 图形验证码请求对象 @ClassName ImageCodeDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/6 14:30
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@ApiModel
public class ImageCodeDto implements Serializable {
    private static final long serialVersionUID = -1449471839413316805L;

    @ApiModelProperty(value = "验证码宽度", example = "130")
    @Min(value = 1, message = "验证码宽度不能小于1像素")
    @Max(value = 1920, message = "验证码宽度不能大于1920像素")
    private Integer width = 130;

    @ApiModelProperty(value = "验证码高度", example = "48")
    @Min(value = 1, message = "验证码高度不能小于1像素")
    @Max(value = 1080, message = "验证码高度不能大于1080像素")
    private Integer height = 48;

    @ApiModelProperty(value = "验证码位数", example = "6")
    @Min(value = 4, message = "验证码高度不能小于4位")
    @Max(value = 8, message = "验证码高度不能大于8位")
    private Integer length = 4;

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        if (width > 1920) {
            width = 1920;
        } else if (width <= 0) {
            width = 130;
        }
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        if (height > 1080) {
            height = 1080;
        } else if (height <= 0) {
            height = 48;
        }
        this.height = height;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        if (length > 8) {
            length = 8;
        } else if (length <= 0) {
            length = 4;
        }
        this.length = length;
    }
}

package com.xinhongru.requestDto.cutImageDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 图片裁切对象 @ClassName CutImageDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/4/28 16:49
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class CutImageDto implements Serializable {
    @NotBlank(message = "字段名称不能为空")
    private String fieldName;

    @NotBlank(message = "原始图片路径不能为空")
    private String imgSrc;

    @NotNull(message = "建议尺寸宽度不能为空")
    @Min(value = 0, message = "建议尺寸宽度不能小于0")
    @Max(value = 1920, message = "建议尺寸宽度不能大于1920")
    private Integer minWidth;

    @NotNull(message = "建议尺寸高度不能为空")
    @Min(value = 0, message = "建议尺寸高度不能小于0")
    @Max(value = 3840, message = "建议尺寸高度不能大于3840")
    private Integer minHeight;

    @NotNull(message = "缩放比例不能为空")
    @Min(value = 0, message = "缩放比例不能小于0")
    private float zoom;

    @NotNull(message = "缩放方式不能为空")
    @Min(value = 0, message = "最小值不能小于0")
    @Max(value = 2, message = "最大值不能大于2")
    private Integer zoomType;

    @NotNull(message = "原图宽度不能为空")
    @Min(value = 0, message = "原图宽度不能小于0")
    @Max(value = 19200, message = "原图宽度不能大于19200")
    private float imageWidth;

    @NotNull(message = "原图高度不能为空")
    @Min(value = 0, message = "原图高度不能小于0")
    @Max(value = 19200, message = "原图高度不能大于19200")
    private float imageHeight;

    /** 最大宽度 */
    private float maxWidth;

    /** 最大高度 */
    private float maxHeight;
}

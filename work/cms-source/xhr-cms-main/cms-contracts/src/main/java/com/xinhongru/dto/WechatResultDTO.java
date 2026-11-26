package com.xinhongru.dto;

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
 * @className WechatResultDTO
 * @date 2024/4/16 13:43
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WechatResultDTO implements Serializable {
    @ApiModelProperty(value = "html代码")
    private String result;

    @ApiModelProperty(
            value = "图片文件压缩包绝对路径",
            example = "image_zip -> http://weiwendaoru.hongru.com.cn/images_638488673611959315.zip")
    private String image_zip;

    @ApiModelProperty(value = "错误明细")
    private String error_msg;

    @ApiModelProperty(value = "错误代码")
    private Integer error_code;

    @ApiModelProperty(value = "图片路径解压目录")
    private String folder_path;
}

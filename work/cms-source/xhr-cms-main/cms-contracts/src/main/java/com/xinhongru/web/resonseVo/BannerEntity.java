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
 * @className BannerEntity
 * @date 2024/1/13 10:33
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BannerEntity implements Serializable {
    @ApiModelProperty(value = "banner标题")
    private String bannerTitle;

    @ApiModelProperty(value = "banner简介")
    private String bannerNote;

    @ApiModelProperty(value = "banner图片")
    private String bannerPhoto;
}

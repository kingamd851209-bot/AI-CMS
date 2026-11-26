package com.xinhongru.responseVo.model;

import com.xinhongru.responseVo.oneshot.SeoDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName ModelManageSeoVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/5
 * 17:05
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class ModelManageSeoVo extends SeoDto {
    private Integer classid;
}

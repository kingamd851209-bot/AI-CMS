package com.xinhongru.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.common.entity.IntegerEntity;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className BusinessEntity
 * @date 2024/1/18 21:25
 * @since 1.0
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessEntity extends IntegerEntity<BusinessEntity> {
    @ApiModelProperty(value = "分类名称")
    private String title;
}

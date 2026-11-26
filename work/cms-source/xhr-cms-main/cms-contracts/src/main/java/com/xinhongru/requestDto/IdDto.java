package com.xinhongru.requestDto;

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
 * @Description id参数对象 @ClassName IdDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/26
 * 21:05
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class IdDto implements Serializable {
    /** 主键Id */
    @ApiModelProperty(value = "主键Id")
    @Min(value = 1, message = "id不能小于1")
    @Max(value = Integer.MAX_VALUE, message = "id不能大于int最大值")
    private Integer id;
}

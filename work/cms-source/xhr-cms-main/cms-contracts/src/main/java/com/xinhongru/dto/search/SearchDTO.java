package com.xinhongru.dto.search;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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
 * @className SearchDTO
 * @date 2022/12/27 12:00
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchDTO implements Serializable {
    @Min(value = 1, message = "页码不能小于1")
    @Max(value = Integer.MAX_VALUE, message = "页码最大不允许超过int最大值")
    private Integer pageIndex = 1;

    private String key;

    @Min(value = 1, message = "每页显示条数")
    @Max(value = 100, message = "每页最多只能显示100条记录")
    private Integer limit = 10;

    /** 开始位置 */
    private Integer startNum;
}

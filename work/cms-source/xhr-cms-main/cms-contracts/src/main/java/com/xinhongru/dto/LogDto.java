package com.xinhongru.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * @Description @ClassName LogDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/3 21:01
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class LogDto implements Serializable {
    /** 页码 */
    @Min(value = 1, message = "页码不能小于1")
    private Integer page = 1;

    /** 每页显示条数 */
    @Min(value = 1, message = "每页至少显示一条数据")
    @Max(value = 100, message = "每页最多显示一百条记录")
    private Integer limit = 20;

    /** 管理员ID */
    @Min(value = 1, message = "管理员ID不能小于零")
    private Integer userId = 0;

    /** 开始时间 */
    @PastOrPresent(message = "开始时间不能是未来时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate beginTime;

    /** 结束时间 */
    @PastOrPresent(message = "结束时间不能是未来时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate endTime;

    /** 搜索关键词 */
    private String key;

    /** 管理员Id集合 */
    private List<Integer> userIds;
}

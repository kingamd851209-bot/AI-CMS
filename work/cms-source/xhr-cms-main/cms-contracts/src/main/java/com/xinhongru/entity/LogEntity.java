package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 日志类 Created at 2021/8/9. */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class LogEntity extends IntegerEntity<LogEntity> {
    /** 产生的动作 */
    private String action;

    /** 入参 */
    private String requestData;

    /** 管理员ID */
    private Integer userId;

    /** 日志产生的ip */
    private String ip;

    /** 日志创建时间 */
    private LocalDateTime createTime;

    /** 请求地址 */
    private String url;

    /** 请求方法 */
    private String httpMethod;

    /** 执行class方法 */
    private String classMethod;

    /** 耗时 单位毫秒 */
    private Long spendTime;

    /** 响应结果 */
    private String responseData;

    /** 操作人 */
    private String userName;
}

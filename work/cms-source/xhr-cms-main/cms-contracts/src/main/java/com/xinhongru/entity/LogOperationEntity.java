package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * @Description @ClassName LogOperationEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/5
 * 11:18
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class LogOperationEntity extends IntegerEntity<LogOperationEntity> {
    /** 事件描述 */
    private String action;

    /** 入参数据 */
    private String requestData;

    /** 管理员编号 */
    private Integer userId;

    /** ip地址 */
    private String ip;

    /** 操作时间 */
    private LocalDateTime created = LocalDateTime.now();

    /** 响应结果 */
    private String responseData;

    /** 管理员登录名 */
    private String userName;

    /** 栏目ID */
    private Integer classId;

    /** 数据ID */
    private Integer infoId = 0;
}

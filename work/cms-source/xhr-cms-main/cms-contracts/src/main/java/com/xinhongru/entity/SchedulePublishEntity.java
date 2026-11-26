package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 定时发布配置（按站点隔离） */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("t_schedule_publish")
public class SchedulePublishEntity extends LongEntity<SchedulePublishEntity> {
    /** 站点ID（站点隔离） */
    private Long siteId;

    /** 发布 cron 表达式 */
    private String cronExpression;

    /** cron 描述 */
    private String cronDescription;

    /** 发布栏目范围（栏目id JSON 数组） */
    private String columnIds;

    /** 发布栏目范围显示快照 */
    private String columnNames;

    /** 备注 */
    private String remark;

    /** 生效状态 0关闭 1开启 */
    private Integer enabled;

    private Integer deleted;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}

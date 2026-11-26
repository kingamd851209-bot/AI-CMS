package com.xinhongru.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 审批记录实体 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class CheckRecordEntity extends IntegerEntity<CheckRecordEntity> {
    /** 文章 Id */
    private Integer contentId;

    /** 内容标题 */
    private String contentTitle;

    /** 版本 Id */
    private Integer contentHistoryId;

    /** 栏目 Id */
    private Integer submitMenuId;

    /** 栏目名称 */
    private String submitMenuName;

    /** 提交人 Id */
    private Integer submitUserId;

    /** 提交人名称 */
    private String submitUserName;

    /** 提交描述 */
    private String submitDescription;

    /** 提交时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime submitTime = LocalDateTime.now();

    /** 审批人 Id */
    private Integer checkUserId;

    /** 审批人名称 */
    private String checkUserName;

    /** 审批状态，0待审批，1通过，2未通过，3作废 */
    private Integer checkStatus;

    /** 审批备注 */
    private String checkDescription;

    /** 审批时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime checkTime;

    /** 操作类型：add、del、update */
    private String type;
}

package com.xinhongru.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.IntegerEntity;
import com.xinhongru.dto.LogTableDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 文章实体版本 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ContentHistoryEntity extends IntegerEntity<ContentHistoryEntity> {
    /** 文章 Id */
    private Integer contentId;

    /** 栏目 Id */
    private Integer menuId;

    /** 内容标题 */
    private String title;

    /** 提交时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime createTime = LocalDateTime.now();

    /** 版本内容 */
    private String params;

    /** 更新字段比对 */
    private String comparison;

    /** 更新内容 */
    private String updateDescription;

    /** 用户 Id */
    private Integer userId;

    /** 用户账号 */
    private String userAccount;

    /** 用户名 */
    private String userName;

    /** 审核状态 （0待审批，1通过，2未通过，3作废） */
    private int checkStatus;

    /** 审核描述 */
    private String checkDescription;

    /** 字段 比对 */
    private List<LogTableDto> fields;
}

package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 内容中台推送记录：一键分发逐条留痕 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_platform_push_record")
public class PlatformPushRecordEntity extends LongEntity<PlatformPushRecordEntity> {
    /** 中台数据ID（源内容ID） */
    private Integer sourceContentId;

    /** 数据类型 product/news/form/solution */
    private String contentType;

    /** 目标站点ID */
    private Long siteId;

    /** 目标站点栏目ID */
    private Integer columnId;

    /** 推送生成的目标内容ID */
    private Integer targetContentId;

    /** 字段映射关系JSON [{productField,siteField}] */
    private String fieldMapping;

    /** 推送的源字段内容JSON */
    private String fieldContent;

    /** 推送配置ID */
    private Long pushConfigId;

    /** 推送状态 SUCCESS/FAILED */
    private String pushStatus;

    /** 推送类型 INSERT新增 UPDATE更新 */
    private String pushType;

    /** 推送目标状态 0草稿 1待审 2已审核 3已发布 */
    private Integer targetStatus;

    /** 推送时间 */
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd HH:mm:ss",
            timezone = "GMT+8")
    private LocalDateTime pushTime;

    /** 推送人ID */
    private Long pushUserId;

    /** 推送人 */
    private String pushUser;

    /** 失败原因 */
    private String errorMessage;

    /** 逻辑删除 */
    private Integer deleted;
}

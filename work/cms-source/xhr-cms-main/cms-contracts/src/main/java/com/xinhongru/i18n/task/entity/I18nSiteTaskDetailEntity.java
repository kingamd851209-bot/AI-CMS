package com.xinhongru.i18n.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 多语言站点生成任务明细（逐栏目） */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_i18n_site_task_detail")
public class I18nSiteTaskDetailEntity extends LongEntity<I18nSiteTaskDetailEntity> {
    /** 主任务ID */
    private Long taskId;

    /** 源栏目ID */
    private Integer sourceMenuId;

    /** 源栏目名称 */
    private String sourceMenuName;

    /** 源栏目数据模型结构 */
    private String sourceModelJson;

    /** 源栏目类型 0单页 1列表 */
    private Integer sourceMenuType;

    /** 源栏目模版(list/detail) */
    private String sourceTemplate;

    /** 源栏目内容数量 */
    private Integer sourceContentCount;

    /** 目标栏目ID */
    private Integer targetMenuId;

    /** 目标栏目名称 */
    private String targetMenuName;

    /** 目标栏目数据模型结构 */
    private String targetModelJson;

    /** 目标栏目类型 0单页 1列表 */
    private Integer targetMenuType;

    /** 目标栏目模版 */
    private String targetTemplate;

    /** 目标栏目内容数量 */
    private Integer targetContentCount;

    /** 数据模型是否已同步 */
    private Boolean modelSynced;

    /** 模版是否已同步 */
    private Boolean templateSynced;

    /** 内容是否已同步 */
    private Boolean contentSynced;

    /** 同步说明：全部同步/已同步部分 */
    private String syncNote;

    /** 最近同步时间 */
    private LocalDateTime syncTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

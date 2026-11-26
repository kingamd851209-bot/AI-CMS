package com.xinhongru.i18n.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 多语言站点生成主任务 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_i18n_site_task")
public class I18nSiteTaskEntity extends LongEntity<I18nSiteTaskEntity> {
    /** 同步/生成状态：待处理 */
    public static final String STATUS_PENDING = "PENDING";

    /** 同步/生成状态：进行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 同步/生成状态：成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 同步/生成状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 同步/生成状态：部分完成 */
    public static final String STATUS_PARTIAL = "PARTIAL";

    /** 原站点ID */
    private Long sourceSiteId;

    /** 原站点名称 */
    private String sourceSiteName;

    /** 原站点语言 */
    private String sourceLanguage;

    /** 目标站点ID */
    private Long targetSiteId;

    /** 目标站点名称 */
    private String targetSiteName;

    /** 目标站点语言 */
    private String targetLanguage;

    /** 栏目生成状态 */
    private String columnStatus;

    /** 栏目数据模型同步状态 */
    private String modelSyncStatus;

    /** 栏目模版同步状态 */
    private String templateSyncStatus;

    /** 栏目数据内容同步状态 */
    private String contentSyncStatus;

    /** 静态资源同步状态 */
    private String assetSyncStatus;

    private String remark;
    private Long operatorId;
    private String operatorName;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

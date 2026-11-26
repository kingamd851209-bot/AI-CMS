package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 内容中台 推送配置：某分类推送到某站点栏目的字段映射与目标状态 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_platform_push_config")
public class PlatformPushConfigEntity extends LongEntity<PlatformPushConfigEntity> {
    /** 类型 product/news */
    private String configType;

    /** 内容中台分类ID */
    private Integer categoryId;

    /** 目标站点ID */
    private Long siteId;

    /** 目标站点栏目ID */
    private Integer siteColumnId;

    /** 推送目标默认状态 0草稿 1待审 2已审核 3已发布 */
    private Integer targetStatus;

    /** 字段映射JSON [{productField,siteField}] */
    private String fieldMapping;

    /** 逻辑删除 */
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createBy;
}

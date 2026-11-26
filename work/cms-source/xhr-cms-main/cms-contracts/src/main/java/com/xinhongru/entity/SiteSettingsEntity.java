package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 站点设置：栏目上线配置（301/302/多语言回退/跨站映射） */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_site_settings")
public class SiteSettingsEntity extends LongEntity<SiteSettingsEntity> {
    private Long siteId;
    private Integer columnId;
    private String columnName;
    private Integer parentId;

    /** list/detail */
    private String kind;

    private String pageUrl;

    @TableField("redirect_301")
    private String redirect301;

    @TableField("redirect_302")
    private String redirect302;

    private String fallbackLang;

    /** [{siteId,columnId}] */
    private String crossMappingJson;

    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

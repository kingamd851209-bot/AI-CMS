package com.xinhongru.site.locale;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_site_locale")
public class SiteLocaleEntity extends LongEntity<SiteLocaleEntity> {
    private Long siteId;
    private Long languageId;
    private Integer isDefault;
    private Integer enabled;
    private Integer requiredForPublish;
    private String pathPrefix;
    private Long fallbackLanguageId;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic private Integer deleted;
}

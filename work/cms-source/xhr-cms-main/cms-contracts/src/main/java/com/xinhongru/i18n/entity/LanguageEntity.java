package com.xinhongru.i18n.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_language")
public class LanguageEntity extends LongEntity<LanguageEntity> {
    private static final long serialVersionUID = 1L;

    private String languageCode;

    private String languageName;

    private String nativeName;

    private String locale;

    private Integer isDefault;

    private Integer enabled;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic private Integer deleted;
}

package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sensitive_word")
public class SensitiveWordEntity extends LongEntity<SensitiveWordEntity> {
    private String word;

    private String action;

    private String replacement;

    private Integer level;

    private String remark;

    private String scope;

    private String siteIds;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createBy;

    @TableLogic private Boolean deleted;

    public SensitiveWordEntity() {}

    public SensitiveWordEntity(
            Long id,
            String word,
            String action,
            String replacement,
            Integer level,
            String remark,
            String scope,
            String siteIds,
            Integer status,
            LocalDateTime createTime) {
        this.id = id;
        this.word = word;
        this.action = action;
        this.replacement = replacement;
        this.level = level;
        this.remark = remark;
        this.scope = scope;
        this.siteIds = siteIds;
        this.status = status;
        this.createTime = createTime;
    }
}

package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 网站配置项 Created at 2021/4/28. */
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class OptionsEntity extends IntegerEntity<OptionsEntity> {
    public OptionsEntity(String name, String value, String description) {
        this.id = 0;
        this.name = name;
        this.value = value;
        this.description = description;
    }

    /** 名称 */
    private String name;

    /** 内容 */
    private String value;

    /** 备注 */
    private String description;
}

package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 系统菜单实体 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("t_menu")
public class MenuEntity extends LongEntity<MenuEntity> {
    private static final long serialVersionUID = 1L;

    private Long parentId;

    private String menuKey;

    private String title;

    private String path;

    private String icon;

    private String category;

    private Integer sortOrder;

    private Boolean visible;

    private Boolean enabled;

    private Boolean requiresSystemAdmin;

    private String componentPath;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic private Boolean deleted;

    /** 上级菜单名称（非表字段，分页列表展示用） */
    @TableField(exist = false)
    private String parentTitle;

    /** 是否存在未删除的直属子菜单（非表字段，用于树表懒加载） */
    @TableField(exist = false)
    private Boolean hasChildren;
}

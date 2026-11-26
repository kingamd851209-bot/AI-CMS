package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * @Description 模块模板栏目 @ClassName ModuleEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/4/2 15:07
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ModuleTemplateEntity extends IntegerEntity<ModuleTemplateEntity> {
    /** 栏目名称 */
    @ApiModelProperty(value = "栏目名称", required = true)
    @NotBlank(message = "栏目名称不能为空")
    private String className;

    /** 栏目英文名称 */
    private String classEnName;

    /** 创建时间 */
    private Date createTime;

    /** 前台栏目页地址 */
    @ApiModelProperty(value = "前台栏目页地址")
    private String listPageUrl;

    /** 前台内容页地址 */
    @ApiModelProperty(value = "前台内容页地址")
    private String detailPageUrl;

    /** 父级ID */
    private Integer parentId;

    /** 排序 */
    private Integer sort;

    /** 单篇栏目字段 */
    private String menuField;

    /** 唯一值 */
    private String identifies;

    /** 列表栏目字段 */
    private String modelField;

    /** 列表栏目列表显示字段 */
    private String modelList;

    /** 列表类型栏目列表搜索的字段数据 */
    private String modelSearch;

    /** 权限集合 */
    private String setting;

    /** 是否后台显示 */
    private Boolean hasManageChannel;

    /** 后台访问路径 */
    private String url;

    /** 是否前台显示 */
    private Boolean hasFrontChannel;

    /** 页面类型 0单篇 1列表 */
    private Integer pageType;

    /** 是否为子栏目 */
    private Boolean isSubMenu;

    /** 是否可以删除 */
    private Boolean isCanDeleted;

    /** 是否为模块基础栏目 默认为否 */
    private Boolean isModuleMenu;

    /** 是否可以级联删除(当删除父栏目时同时删除此栏目) 默认为否 表示不可级联删除 */
    private Boolean isCanCascadeDeleted;

    /** 是否可以添加子栏目 */
    private Boolean isCanAddSub;

    /** 是否可以修改 */
    private Boolean isCanEdit;

    /** 是否可以排序 */
    private Boolean isCanSort;

    /** 是否可以由管理员控制显示隐藏 默认为是 */
    private Boolean isCanShowHidden;

    /** 是否在面包屑导航显示 */
    private Boolean IsShowCrumbs;

    /** 是否在根据父级栏目遍历子栏目显示 */
    private Boolean isLoop;

    /** 模块ID */
    private Integer moduleId;

    /** 是否为整站公共栏目 默认为否 */
    private Boolean websitePublic;

    /** 浏览次数 */
    private Integer hits;

    /** 是否已发布 */
    private Boolean published;

    private Boolean isAddArticle;

    private Boolean isCanAddArticle;
}

package com.xinhongru.responseVo.oneshot;

import com.xinhongru.entity.OneshotEntity;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @Description 添加修改栏目响应对象 @ClassName EditOneshotDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/26 11:46
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class EditOneshotDto implements Serializable {
    /** 栏目ID */
    private Integer id;

    /** 站点项目ID */
    private Long siteProjectId;

    /** 站点根栏目ID */
    private Integer siteRootId;

    /** 父级ID */
    @ApiModelProperty(value = "父级ID")
    @NotNull(message = "父级ID不能为空")
    @Min(value = 0, message = "父级ID不能小于0")
    private Integer parentId;

    /** 栏目名称 */
    @ApiModelProperty(value = "栏目名称")
    @NotBlank(message = "栏目名称不能为空")
    private String className;

    /** 栏目英文名称 */
    @ApiModelProperty(value = "栏目英文名称")
    private String classEnName;

    /** 是否后台显示 */
    @ApiModelProperty(value = "是否后台显示")
    @NotNull(message = "是否后台显示不能为空")
    private Boolean hasManageChannel;

    /** 是否前台显示 */
    @NotNull(message = "是否前台显示不能为空")
    private Boolean hasFrontChannel;

    /** 专题显示位置 */
    private String showPosition;

    /** 栏目类型 0单篇 1列表 */
    @NotNull(message = "栏目类型不能为空")
    @Min(value = 0, message = "栏目类型不能小于0")
    @Max(value = 1, message = "栏目类型不能大于1")
    private Integer pageType;

    /** 后台地址 */
    private String url;

    /** 包含操作 */
    private String setting;

    /** 前台栏目页地址 */
    private String listPageUrl;

    /** 前台列表页模板 */
    private String listPageTemplate;

    /** 前台内容页地址 */
    private String detailPageUrl;

    /** 前台内容页模板 */
    private String detailPageTemplate;

    /** 唯一标识 */
    private String identifies;

    /** 栏目列表 */
    private List<OneshotEntity> oneshotEntities;

    // 以下字段是模块添加栏目时多出来的字段 在普通字段添加时可以忽略
    /** 创建时间 */
    private Integer sort;

    /** 创建时间 */
    private Date createTime;

    /** 单篇栏目字段 */
    private String menuField;

    /** 列表栏目字段 */
    private String modelField;

    /** 列表栏目列表显示字段 */
    private String modelList;

    /** 列表栏目搜索显示字段 */
    private String modelSearch;

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
    private Boolean isShowCrumbs;

    /** 是否在根据父级栏目遍历子栏目显示 */
    private Boolean isLoop;

    /** 是否为整站公共栏目 默认为否 */
    private Boolean websitePublic;

    /** 浏览次数 */
    private Integer hits;

    /** 是否已发布 */
    private Boolean published;

    /** 是否允许前端会员添加文章 */
    private Boolean isAddArticle;

    /** 是否开启允许会员添加文章功能 */
    private Boolean isCanAddArticle;
}

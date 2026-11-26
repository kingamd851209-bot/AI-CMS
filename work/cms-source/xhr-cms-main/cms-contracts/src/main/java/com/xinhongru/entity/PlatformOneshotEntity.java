package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/** 平台分类/元数据配置（产品/新闻/案例/解决方案 共表，跨站点不关联站点过滤，data_type 区分类别） */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("t_platform_oneshot")
public class PlatformOneshotEntity extends IntegerEntity<PlatformOneshotEntity> {
    /** 数据类型：1 产品 2 新闻 3 案例 4 解决方案 */
    private Integer dataType;

    /** 站点项目ID（跨站点场景不使用） */
    private Long siteProjectId;

    /** 站点根分类ID（跨站点场景不使用） */
    private Integer siteRootId;

    /** 分类名称 */
    private String className;

    /** 分类英文名称 */
    private String classEnName;

    private String singleLine1;
    private String singleLine2;
    private String singleLine3;
    private String singleLine4;
    private String singleLine5;
    private String singleLine6;
    private String singleLine7;
    private String singleLine8;
    private String singleLine9;
    private String singleLine10;
    private String singleLine11;
    private String singleLine12;
    private String singleLine13;
    private String singleLine14;
    private String singleLine15;
    private String singleLine16;
    private String singleLine17;
    private String singleLine18;
    private String singleLine19;
    private String singleLine20;
    private String singleLine21;
    private String singleLine22;
    private String singleLine23;
    private String singleLine24;
    private String singleLine25;
    private String singleLine26;
    private String singleLine27;
    private String singleLine28;
    private String singleLine29;
    private String singleLine30;
    private String note1;
    private String note2;
    private String note3;
    private String note4;
    private String note5;
    private String note6;
    private String note7;
    private String note8;
    private String note9;
    private String note10;
    private String note11;
    private String note12;
    private String note13;
    private String note14;
    private String note15;
    private String photo1;
    private String photo2;
    private String photo3;
    private String photo4;
    private String photo5;
    private String photo6;
    private String photo7;
    private String photo8;
    private String photo9;
    private String photo10;
    private String photo11;
    private String photo12;
    private String photo13;
    private String photo14;
    private String photo15;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date beginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date endTime;

    private String file1;
    private String file2;
    private String file3;
    private String file4;
    private String content1;
    private String content2;
    private String content3;
    private String content4;
    private String content5;
    private String content6;
    private String media1;
    private String media2;
    private String media3;
    private String media4;

    /** 前台列表页地址 */
    private String listPageUrl;

    /** 前台列表页模板 */
    private String listPageTemplate;

    /** 前台内容页地址 */
    private String detailPageUrl;

    /** 前台内容页模板 */
    private String detailPageTemplate;

    /** seo标题 */
    private String seoTitle;

    /** seo关键字 */
    private String seoKeywords;

    /** seo描述 */
    private String seoDescription;

    /** 父级分类ID 顶级为0 */
    private int parentId;

    /** 排序号 默认为0 */
    private int sort;

    /** 单篇分类设置的字段 */
    private String menuField;

    /** 分类自定义唯一标识 */
    private String identifies;

    /** 列表类型字段 */
    private String modelField;

    /** 列表类型分类列表展示的字段数据 */
    private String modelList;

    /** 列表类型分类列表搜索的字段数据 */
    private String modelSearch;

    /** 权限ID集合 */
    private String setting;

    /** 是否后台展示 */
    private Boolean hasManageChannel;

    /** 后台地址 */
    private String url;

    /** 是否前台展示 */
    private Boolean hasFrontChannel;

    /** 专题分类显示位置 */
    private String showPosition;

    /** 页面类型 0单篇 1列表 */
    private Integer pageType;

    /** 访问次数 */
    private Integer hits;

    /** 是否在父栏目列表显示跳转列（二级栏目入口）默认为否 */
    private Boolean isSubMenu;

    /** 从父栏目跳转进入本栏目时用于筛选的字段名（对应内容 modelField 的 fieldName） */
    private String parentListFilterField;

    /** 是否可以删除 默认为不可删除 */
    private Boolean isCanDeleted;

    /** 是否为模块基础分类 默认为否 */
    private Boolean isModuleMenu;

    /** 是否可以级联删除(当删除父分类时同时删除此分类) 默认为否 */
    private Boolean isCanCascadeDeleted;

    /** 是否可以添加子分类 默认为否 */
    private Boolean isCanAddSub;

    /** 是否可以修改 默认为是 */
    private Boolean isCanEdit;

    /** 是否可以排序 默认为是 */
    private Boolean isCanSort;

    /** 是否可以由管理员控制显示隐藏 默认为是 */
    private Boolean isCanShowHidden;

    /** 是否在面包屑展示 默认为是 */
    private Boolean isShowCrumbs;

    /** 是否在根据父级分类遍历子分类显示 默认为是 */
    private Boolean isLoop;

    /** 是否删除 默认为否 */
    private Boolean isDeleted;

    /** 后台ICON样式名 */
    private String iconClass;

    /** 是否为系统分类 默认为否 */
    private Boolean isSystem;

    /** 审核状态：0草稿 1待审 2已审核 3已发布 */
    private Integer status;

    /** 是否已经发布过静态页面 默认为否 */
    private Boolean published;

    /** 是否整站公共分类 */
    private Boolean websitePublic;

    /** 是否允许前端会员添加文章 */
    private Boolean isAddArticle;

    /** 是否开启允许会员添加文章功能 */
    private Boolean isCanAddArticle;

    /** 是否表单分类 */
    private Boolean isForm;

    /** 子节点（非表字段） */
    @TableField(exist = false)
    private List<PlatformOneshotEntity> subs;

    /** 节点深度（非表字段） */
    @TableField(exist = false)
    private int depth;
}

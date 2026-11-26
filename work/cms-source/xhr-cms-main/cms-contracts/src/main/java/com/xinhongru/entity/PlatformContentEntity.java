package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 平台内容（产品/新闻/案例/解决方案 共表，跨站点不关联站点过滤，data_type 区分类别） */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("t_platform_contents")
public class PlatformContentEntity extends IntegerEntity<PlatformContentEntity> {
    /** 数据类型：1 产品 2 新闻 3 案例 4 解决方案 */
    private Integer dataType;

    /** 内容标题 */
    private String title;

    /** 标题合并数（非表字段） */
    @TableField(exist = false)
    private Integer titleMergeNum;

    /** 发布时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime createTime = LocalDateTime.now();

    /** 开始时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime beginTime;

    /** 结束时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime endTime;

    /** 生效时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime entryIntoForceTime = LocalDateTime.now();

    /** 失效时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime expirationTime = LocalDateTime.of(9999, 12, 31, 23, 59);

    /** 内容文字 */
    private String content1;

    private String content2;
    private String content3;
    private String content4;
    private String content5;
    private String content6;

    /** 内容状态 */
    private Integer status;

    /** 标签列表 */
    private String tags;

    /** 点击次数 */
    private Integer hits;

    /** 分类编号 */
    @TableField("class_id")
    private Integer classid;

    /** 首页置顶 */
    private Boolean indexTop;

    /** 首页推荐 */
    private Boolean indexElite;

    /** 首页热门 */
    private Boolean indexHot;

    /** 分类置顶 */
    private Boolean menuTop;

    /** 分类推荐 */
    private Boolean menuElite;

    /** 分类推荐 */
    private Boolean menuHot;

    /** 频道页置顶 */
    private Boolean channelTop;

    /** 频道页推荐 */
    private Boolean channelElite;

    /** 频道页推荐 */
    private Boolean channelHot;

    /** 导航推荐 */
    private Boolean navElite;

    /** 右侧推荐 */
    private Boolean rightElite;

    /** 左侧推荐 */
    private Boolean leftElite;

    /** 排序号 */
    private Integer sort;

    /** 备用单行文本 */
    private String singleLine1;

    private String singleLine2;
    private String singleLine3;
    private String singleLine4;
    private String singleLine5;
    private String singleLine6;
    private String singleLine7;

    /** 合并数（非表字段） */
    @TableField(exist = false)
    private Integer singleLine7MergeNum;

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

    /** 关联数据ID */
    private Integer xgid;

    /** 备用多行文本 */
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

    /** 备用图片 */
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

    /** seo标题 */
    private String seoTitle;

    /** seo关键字 */
    private String seoKeywords;

    /** seo描述 */
    private String seoDescription;

    /** 视频 */
    private String media1;

    private String media2;
    private String media3;
    private String media4;

    /** 文件 */
    private String file1;

    private String file2;
    private String file3;
    private String file4;

    /** 提交说明（非表字段） */
    @TableField(exist = false)
    private String submitDescription;

    /** 详情页地址（非表字段） */
    @TableField(exist = false)
    private String detailPageUrl;
}

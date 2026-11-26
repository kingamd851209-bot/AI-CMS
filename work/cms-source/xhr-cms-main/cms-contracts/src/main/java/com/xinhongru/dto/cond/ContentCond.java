package com.xinhongru.dto.cond;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/** 文章查询条件 Created at 2021/8/9. */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class ContentCond implements Serializable {
    /** 分类编号 */
    private Integer classid;

    private boolean includeRowVersion;

    private List<Integer> seedIds;

    /** 关联数据ID */
    private Integer xgid;

    /** 状态 */
    private Integer status;

    /** 状态（多选：IN 查询） */
    private List<Integer> statusList;

    /** 首页置顶 */
    private Integer indexTop;

    /** 首页热门 */
    private Integer indexHot;

    /** 首页推荐 */
    private Integer indexElite;

    /** 频道页置顶 */
    private Integer channelTop;

    /** 频道页热门 */
    private Integer channelHot;

    /** 频道页推荐 */
    private Integer channelElite;

    /** 栏目页置顶 */
    private Integer menuTop;

    /** 栏目页热门 */
    private Integer menuHot;

    /** 栏目页推荐 */
    private Integer menuElite;

    /** 导航推荐 */
    private Integer navElite;

    /** 右侧推荐 */
    private Integer rightElite;

    /** 左侧推荐 */
    private Integer leftElite;

    /** 标题 */
    private String title;

    /** 标签 */
    private String tags;

    /** 单行文本 */
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

    /** 多行文本 */
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

    /** 内容匹配 */
    private String content1;

    private String content2;
    private String content3;
    private String content4;
    private String content5;
    private String content6;

    /** 发布时间开始时间戳 */
    private String startTime_createTime;

    /** 发布时间结束时间戳 */
    private String endTime_createTime;

    /** 仅由已授权后台查询组装，不接受客户端站点或SQL表达式。 */
    private Long siteProjectId;

    private String extensionSortKey;
    private boolean extensionSortDescending;
    private boolean extensionSortNumeric;

    /** 公开模板查询固定沿原公开有效期规则。 */
    private boolean publishedTemplateQuery;

    public String getExtensionSortPath() {
        if (extensionSortKey == null) return null;
        if (!com.xinhongru.content.model.ContentExtensionFields.isName(extensionSortKey))
            throw new IllegalArgumentException("扩展排序字段无效");
        return "$." + extensionSortKey;
    }
}

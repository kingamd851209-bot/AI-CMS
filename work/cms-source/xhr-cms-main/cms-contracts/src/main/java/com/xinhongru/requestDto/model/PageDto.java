package com.xinhongru.requestDto.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description 列表分页构造对象 @ClassName PageDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/4
 * 8:49
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class PageDto implements Serializable {
    @ApiModelProperty(value = "页数", required = true, example = "1")
    @Min(value = 1, message = "页数不能小于1")
    @Max(value = Integer.MAX_VALUE, message = "页数不能大于int最大值")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数量")
    private Integer limit = 20;

    /** */
    @ApiModelProperty(value = "开始行号")
    private Integer beginNumber = 0;

    /** 文章的主键编号 */
    private Integer id;

    /** 内容标题 */
    private String title;

    /** 内容生成时的GMT unix时间戳 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date createTime = new Date(System.currentTimeMillis());

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date beginTime = new Date(System.currentTimeMillis());

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date endTime = new Date(System.currentTimeMillis());

    /** 内容文字 */
    private String content1;

    /** 内容文字 */
    private String content2;

    /** 内容文字 */
    private String content3;

    /** 内容文字 */
    private String content4;

    /** 内容文字 */
    private String content5;

    /** 内容文字 */
    private String content6;

    /** 内容状态 */
    private Boolean status;

    /** 标签列表 */
    private String tags;

    /** 分类编号 */
    private Integer classid;

    /** 首页置顶 */
    private Boolean indexTop;

    /** 首页推荐 */
    private Boolean indexElite;

    /** 首页热门 */
    private Boolean indexHot;

    /** 栏目置顶 */
    private Boolean menuTop;

    /** 栏目推荐 */
    private Boolean menuElite;

    /** 栏目推荐 */
    private Boolean menuHot;

    /** 频道页置顶 */
    private Boolean channelTop;

    /** 频道页推荐 */
    private Boolean channelElite;

    /** 频道页推荐 */
    private Boolean channelHot;

    /** 右侧推荐 */
    private Boolean rightElite;

    /** 左侧推荐 */
    private Boolean leftElite;

    /** 备用单行文本 */
    private String singleLine1;

    /** 备用单行文本 */
    private String singleLine2;

    /** 备用单行文本 */
    private String singleLine3;

    /** 备用单行文本 */
    private String singleLine4;

    /** 备用单行文本 */
    private String singleLine5;

    /** 备用单行文本 */
    private String singleLine6;

    /** 备用单行文本 */
    private String singleLine7;

    /** 备用单行文本 */
    private String singleLine8;

    /** 备用单行文本 */
    private String singleLine9;

    /** 备用单行文本 */
    private String singleLine10;

    /** 备用单行文本 */
    private String singleLine11;

    /** 备用单行文本 */
    private String singleLine12;

    /** 备用单行文本 */
    private String singleLine13;

    /** 备用单行文本 */
    private String singleLine14;

    /** 备用单行文本 */
    private String singleLine15;

    /** 备用单行文本 */
    private String singleLine16;

    /** 备用单行文本 */
    private String singleLine17;

    /** 备用单行文本 */
    private String singleLine18;

    /** 备用单行文本 */
    private String singleLine19;

    /** 备用单行文本 */
    private String singleLine20;

    /** 关联数据ID */
    private Integer xgid;

    /** 备用多行文本 */
    private String note1;

    /** 备用多行文本 */
    private String note2;

    /** 备用多行文本 */
    private String note3;

    /** 备用多行文本 */
    private String note4;

    /** 备用多行文本 */
    private String note5;

    /** 备用多行文本 */
    private String note6;

    /** 备用多行文本 */
    private String note7;

    /** 备用多行文本 */
    private String note8;

    /** 备用多行文本 */
    private String note9;

    /** 备用多行文本 */
    private String note10;

    /** 备用多行文本 */
    private String note11;

    /** 备用多行文本 */
    private String note12;

    /** 备用多行文本 */
    private String note13;

    /** 备用多行文本 */
    private String note14;
}

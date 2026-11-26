package com.xinhongru.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 文章实体 Created at 2021/8/9. */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ContentEntity extends IntegerEntity<ContentEntity> {
    /** 服务端派生搜索文本；不接受客户端绑定，不进入业务JSON/历史快照。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    private String publicSearchText;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    private Integer version;

    /** 新增业务字段值；旧固定字段保持原样。 */
    private String extensionFields;

    public java.util.Map<String, Object> getExtensionValues() {
        return com.xinhongru.content.model.ContentExtensionFields.read(extensionFields);
    }

    /**
     * 内容业务编码：新增内容时默认取自身 id，多站点场景下按 (contentCode, siteProjectId) 定位当前站点的内容
     *
     * <p>注意与栏目表 t_oneshot.biz_code 区分：content_code 标识内容，biz_code 标识栏目。
     */
    private String contentCode;

    /** 站点项目ID：内容所属站点 */
    private Long siteProjectId;

    /** 来源站点ID：多语言增量同步稳定关联，历史数据保持为空 */
    private Long sourceSiteId;

    /** 来源站点内容ID：与 sourceSiteId 共同构成来源关联 */
    private Integer sourceContentId;

    /** 内容标题 */
    private String title;

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

    /** 生效时间。默认立即生效，按数据库秒精度截断，避免入库时进位到下一秒。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime entryIntoForceTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    /** 失效时间 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime expirationTime = LocalDateTime.of(9999, 12, 31, 23, 59);

    /** 内容更改时的GMT unix时间戳 */
    // private Integer modified;

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
    private Integer status;

    /** 标签列表 */
    private String tags;

    /** 点击次数 */
    private Integer hits;

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

    private Integer singleLine7MergeNum;

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

    /** 备用多行文本 */
    private String note15;

    /** 备用图片 */
    private String photo1;

    /** 备用图片 */
    private String photo2;

    /** 备用图片 */
    private String photo3;

    /** 备用图片 */
    private String photo4;

    /** 备用图片 */
    private String photo5;

    /** 备用图片 */
    private String photo6;

    /** 备用图片 */
    private String photo7;

    /** 备用图片 */
    private String photo8;

    /** 备用图片 */
    private String photo9;

    /** 备用图片 */
    private String photo10;

    /** 备用图片 */
    private String photo11;

    /** 备用图片 */
    private String photo12;

    /** 备用图片 */
    private String photo13;

    /** 备用图片 */
    private String photo14;

    /** 备用图片 */
    private String photo15;

    /** seo标题 */
    private String seoTitle;

    /** seo关键字 */
    private String seoKeywords;

    /** seo描述 */
    private String seoDescription;

    /** 视频 */
    private String media1;

    /** 视频 */
    private String media2;

    /** 视频 */
    private String media3;

    /** 视频 */
    private String media4;

    /** 文件 */
    private String file1;

    /** 文件 */
    private String file2;

    /** 文件 */
    private String file3;

    /** 文件 */
    private String file4;

    /** 提交说明 */
    private String submitDescription;

    private String detailPageUrl;
}

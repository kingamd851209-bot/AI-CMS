package com.xinhongru.web.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * @Description @ClassName AjaxForm @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/21 16:28
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class AjaxForm implements Serializable {
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

    /** 标签列表 */
    private String tags;

    /** 分类编号 */
    @NotNull(message = "缺少分类参数")
    private Integer classId;

    /** 状态（前台表单提交置 1=可见）；为 null 时不写入，沿用表默认值 */
    private Integer status;

    /** 站点项目ID（多站点）；为 null 时不写入 */
    private Long siteProjectId;

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

    /** 图形验证码键 */
    @NotBlank(message = "请重新获取验证码")
    private String codeKey;

    /** */
    @NotBlank(message = "请输入验证码")
    private String code;
}

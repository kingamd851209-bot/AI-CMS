package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.IntegerEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 一键推送的目标内容（写入目标站点栏目 t_contents） */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("t_contents")
public class TargetContentEntity extends IntegerEntity<TargetContentEntity> {
    private Long siteProjectId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    private String publicSearchText;

    @TableField("class_id")
    private Integer classid;

    private String title;

    private String tags;

    private Integer status;

    private Integer sort;

    private Integer hits;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("begin_time")
    private LocalDateTime beginTime;

    @TableField("end_time")
    private LocalDateTime endTime;

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

    private String media1;
    private String media2;
    private String media3;
    private String media4;

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
}

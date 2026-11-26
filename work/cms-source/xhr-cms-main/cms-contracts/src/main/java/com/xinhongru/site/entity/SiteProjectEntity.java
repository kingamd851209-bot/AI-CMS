package com.xinhongru.site.entity;

import com.alibaba.fastjson.annotation.JSONField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_ai_site_project")
public class SiteProjectEntity extends LongEntity<SiteProjectEntity> {
    private static final long serialVersionUID = 1L;

    private String projectName;

    private String projectType;

    private String projectCode;

    private Integer rootMenuId;

    private String siteMode;

    private Integer isCurrent;

    private String templateEntryPath;

    private String templatePackageName;

    private String templatePackageStatus;

    private String traditionalTemplatePackageName;

    private String traditionalTemplatePackageVersion;

    private String traditionalTemplateManifest;

    private String traditionalTemplateRoot;

    private String uiPackageName;

    private String uiPackageVersion;

    private String uiPackageStatus;

    /** 父级站点ID（站点层级结构） */
    private Long parentId;

    private String sampleBaseUrl;

    private String siteDomain;

    private String publishDirectory;

    private String defaultLanguage;

    private String siteDescription;

    private Long currentBatchId;

    private Long lastRecognitionBatchId;

    private String status;

    private String applyStatus;

    private String previewStatus;

    /** 站点服务端口 (8080-9000) */
    private Integer siteServicePort;

    /** 本站资源根地址（HTTP(S)，不自动追加站点编码）；旧裸主机值保留 UI 服务兜底语义。 */
    private String siteServiceIp;

    /** HTML 页面访问认证，同时作用于 UI 静态服务和站点服务。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Boolean previewAccessEnabled;

    @JsonIgnore
    @JSONField(serialize = false)
    @TableField(updateStrategy = FieldStrategy.NEVER)
    @ToString.Exclude
    private String previewAccessPassword;

    /** 站点服务状态: STOPPED/RUNNING */
    private String siteServiceStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic private Integer deleted;
}

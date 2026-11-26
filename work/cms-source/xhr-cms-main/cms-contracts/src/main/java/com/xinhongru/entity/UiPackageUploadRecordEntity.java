package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** UIPackage 上传记录：每次上传（首次上传 / 升级覆盖）保留一条 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_ai_ui_package_upload_record")
public class UiPackageUploadRecordEntity extends LongEntity<UiPackageUploadRecordEntity> {
    /** 站点ID */
    private Long siteId;

    /** 站点编码 */
    private String projectCode;

    /** UIPackage 版本标识 */
    private String version;

    /** 上传的 ZIP 文件名 */
    private String packageName;

    /** ZIP 文件字节数 */
    private Long fileSize;

    /** 上传类型 UPLOAD首次上传 UPGRADE升级覆盖 */
    private String uploadType;

    /** 上传人ID */
    private Long uploadUserId;

    /** 上传人 */
    private String uploadUser;

    /** 上传时间 */
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd HH:mm:ss",
            timezone = "GMT+8")
    private LocalDateTime uploadTime;

    /** 逻辑删除 */
    private Integer deleted;
}

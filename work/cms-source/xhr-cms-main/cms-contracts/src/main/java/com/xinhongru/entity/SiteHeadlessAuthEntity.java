package com.xinhongru.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.xinhongru.common.entity.LongEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Headless 站点接入认证配置（每站点一对 appKey/appSecret）。
 *
 * <p>站点类型（{@code cms_ai_site_project.site_mode}）为逗号分隔多值，含 {@code HEADLESS} 的站点使用本配置鉴权， 独立于后台登录会话。
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_site_headless_auth")
public class SiteHeadlessAuthEntity extends LongEntity<SiteHeadlessAuthEntity> {
    /** 站点ID（唯一） */
    private Long siteId;

    /** 接入 appKey */
    private String appKey;

    /** 接入 appSecret */
    private String appSecret;

    /** 是否启用：1启用 0停用 */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 最近轮换时间 */
    private LocalDateTime rotateTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

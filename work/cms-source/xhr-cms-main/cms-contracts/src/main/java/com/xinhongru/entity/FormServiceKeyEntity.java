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
 * 表单服务接口认证 key（每站点一把）。
 *
 * <p>站点服务上暴露的表单接口以该 key 鉴权，独立于后台登录会话；enabled=1 才可用。
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_form_service_key")
public class FormServiceKeyEntity extends LongEntity<FormServiceKeyEntity> {
    /** 站点ID（唯一） */
    private Long siteId;

    /** 认证 key */
    private String apiKey;

    /** 是否启用：1启用 0停用 */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 最近轮换时间 */
    private LocalDateTime rotateTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

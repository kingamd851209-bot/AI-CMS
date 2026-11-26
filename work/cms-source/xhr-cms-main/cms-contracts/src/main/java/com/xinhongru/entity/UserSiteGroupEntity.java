package com.xinhongru.entity;

import com.xinhongru.common.entity.LongEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

/** 用户-站点-管理员组关联：一个用户可管理多个站点，每站点关联该站点的一个管理员组。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class UserSiteGroupEntity extends LongEntity<UserSiteGroupEntity> {
    private Integer userId;
    private Long siteId;
    private Integer groupId;
    private Date created;
}

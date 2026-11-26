package com.xinhongru.service.userSiteGroup;

import com.xinhongru.entity.UserSiteGroupEntity;

import java.util.List;

/** 用户-站点-管理员组关联服务。 */
public interface UserSiteGroupService {
    /** 查询用户在某站点的管理员组id（无关联返回 null） */
    Integer getGroupIdByUserIdAndSiteId(Integer userId, Long siteId);

    /** 查询用户全部站点-组关联 */
    List<UserSiteGroupEntity> listByUserId(Integer userId);

    /** 保存用户的多站点-组关联（先删后插，校验组属于对应站点） */
    void saveUserSiteGroups(Integer userId, List<SiteGroupRef> refs);

    /** 删除用户的全部关联（删除用户时级联） */
    void deleteByUserId(Integer userId);

    record SiteGroupRef(Integer siteId, Integer groupId) {}
}

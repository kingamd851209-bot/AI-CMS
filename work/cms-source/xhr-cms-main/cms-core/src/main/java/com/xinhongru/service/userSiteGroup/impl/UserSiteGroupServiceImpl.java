package com.xinhongru.service.userSiteGroup.impl;

import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;
import com.xinhongru.mapper.UserSiteGroupMapper;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 用户-站点-管理员组关联服务实现。 */
@Service
public class UserSiteGroupServiceImpl implements UserSiteGroupService {
    private final UserSiteGroupMapper userSiteGroupMapper;
    private final UserGroupService userGroupService;

    public UserSiteGroupServiceImpl(
            UserSiteGroupMapper userSiteGroupMapper, UserGroupService userGroupService) {
        this.userSiteGroupMapper = userSiteGroupMapper;
        this.userGroupService = userGroupService;
    }

    @Override
    public Integer getGroupIdByUserIdAndSiteId(Integer userId, Long siteId) {
        if (userId == null || siteId == null) {
            return null;
        }
        UserSiteGroupEntity entity = userSiteGroupMapper.selectByUserIdAndSiteId(userId, siteId);
        return entity == null ? null : entity.getGroupId();
    }

    @Override
    public List<UserSiteGroupEntity> listByUserId(Integer userId) {
        return userSiteGroupMapper.selectByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUserSiteGroups(Integer userId, List<SiteGroupRef> refs) {
        userSiteGroupMapper.deleteByUserId(userId);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        List<UserSiteGroupEntity> entities = new ArrayList<>();
        Set<SiteGroupRef> seenRelations = new HashSet<>();
        for (SiteGroupRef ref : refs) {
            if (ref == null || ref.siteId() == null || ref.groupId() == null) {
                throw new IllegalArgumentException("站点及管理员组不能为空");
            }
            Long siteId = ref.siteId().longValue();
            if (!seenRelations.add(ref)) {
                throw new IllegalArgumentException("站点管理员组关系重复");
            }
            if (refs.size() > 1 && siteId == 0L) {
                throw new IllegalArgumentException("平台管理员只能选择一个平台组且不能混选站点组");
            }
            UserGroupEntity group = userGroupService.getUserGroupById(ref.groupId());
            if (group == null || !Objects.equals(group.getSiteId(), siteId)) {
                throw new IllegalArgumentException(
                        "管理员组不属于对应站点，siteId=" + ref.siteId() + ", groupId=" + ref.groupId());
            }
            entities.add(
                    new UserSiteGroupEntity()
                            .setUserId(userId)
                            .setSiteId(siteId)
                            .setGroupId(ref.groupId())
                            .setCreated(new Date()));
        }
        if (!entities.isEmpty()) {
            userSiteGroupMapper.insertBatch(entities);
        }
    }

    @Override
    public void deleteByUserId(Integer userId) {
        userSiteGroupMapper.deleteByUserId(userId);
    }
}

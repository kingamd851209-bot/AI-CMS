package com.xinhongru.service.user;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.mapper.UserMapper;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminUserQueryService {
    private final UserMapper userDao;
    private final UserGroupService userGroupService;

    public AdminUserQueryService(UserMapper userDao, UserGroupService userGroupService) {
        this.userDao = userDao;
        this.userGroupService = userGroupService;
    }

    public List<UserEntity> visibleUsers(HttpServletRequest httpServletRequest) {
        List<UserEntity> userList = userDao.getUserList();
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        if (!TaleUtils.isFullAdmin(httpServletRequest)) {
            userList =
                    userList.stream()
                            .filter(item -> !item.getId().equals(WebConst.SUPER_ADMIN_ID))
                            .collect(Collectors.toList());
        }
        // 获取当前登录用户的所有子集管理员组
        List<UserGroupEntity> childUserGroupList =
                userGroupService.getChildUserGroupList(loginUser.getGroupId(), httpServletRequest);
        List<Integer> childGroupIdList =
                childUserGroupList.stream()
                        .map(userGroupEntity -> userGroupEntity.getId())
                        .collect(Collectors.toList());

        userList =
                userList.stream()
                        .filter(
                                userEntity ->
                                        ArrayUtils.contains(
                                                childGroupIdList.toArray(),
                                                userEntity.getGroupId()))
                        .collect(Collectors.toList());

        Optional<UserGroupEntity> userGroupEntity;
        for (UserEntity userEntity : userList) {
            userGroupEntity =
                    childUserGroupList.stream()
                            .filter(item -> item.getId().equals(userEntity.getGroupId()))
                            .findFirst();
            if (userGroupEntity.isPresent()) {
                userEntity.setGroupName(userGroupEntity.get().getTitle());
            } else {
                userEntity.setGroupName(WebConst.UNKNOWN);
            }
        }

        return userList;
    }
}

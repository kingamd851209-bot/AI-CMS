package com.xinhongru.persistence;

import com.xinhongru.entity.*;
import com.xinhongru.mapper.*;
import com.xinhongru.security.identity.AccountDirectory;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisAccountDirectory implements AccountDirectory {
    private final UserMapper users;
    private final UserGroupMapper groups;
    private final UserSiteGroupMapper assignments;
    private final ContentMapper members;

    public MyBatisAccountDirectory(
            UserMapper users,
            UserGroupMapper groups,
            UserSiteGroupMapper assignments,
            ContentMapper members) {
        this.users = users;
        this.groups = groups;
        this.assignments = assignments;
        this.members = members;
    }

    public UserEntity admin(Integer id) {
        return users.getUserInfoById(id);
    }

    public UserGroupEntity group(Integer id) {
        return id == null ? null : groups.getUserGroupById(id);
    }

    public List<UserSiteGroupEntity> assignments(Integer id) {
        return assignments.selectByUserId(id);
    }

    public ContentEntity member(Integer id) {
        return members.getActiveArticleById(id);
    }
}

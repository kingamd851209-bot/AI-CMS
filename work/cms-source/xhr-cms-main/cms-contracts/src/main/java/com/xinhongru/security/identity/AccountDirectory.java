package com.xinhongru.security.identity;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;

import java.util.List;

/** Read-only identity port. Authentication does not depend on business service implementations. */
public interface AccountDirectory {
    UserEntity admin(Integer id);

    UserGroupEntity group(Integer id);

    List<UserSiteGroupEntity> assignments(Integer userId);

    ContentEntity member(Integer id);
}

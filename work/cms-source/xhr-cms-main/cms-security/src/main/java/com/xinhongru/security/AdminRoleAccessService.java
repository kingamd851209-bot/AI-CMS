package com.xinhongru.security;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;
import com.xinhongru.security.identity.AccountDirectory;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class AdminRoleAccessService {
    private final AccountDirectory accounts;

    public AdminRoleAccessService(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    public boolean pending(UserEntity user) {
        if (Objects.equals(user.getId(), WebConst.SUPER_ADMIN_ID)
                || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername())) return false;
        if (active(user.getGroupId())) return false;
        List<UserSiteGroupEntity> refs = accounts.assignments(user.getId());
        return refs == null || refs.stream().noneMatch(ref -> active(ref.getGroupId()));
    }

    private boolean active(Integer id) {
        if (id == null || id <= 0) return false;
        UserGroupEntity group = accounts.group(id);
        return group != null
                && !Boolean.FALSE.equals(group.getEnabled())
                && !Boolean.TRUE.equals(group.getDeleted());
    }
}

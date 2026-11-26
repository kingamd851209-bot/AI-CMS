package com.xinhongru.utils;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Created at 2021/4/30. */
@Component
public class TaleUtils {
    private static SiteContextService siteContextService;
    private static UserSiteGroupService userSiteGroupService;
    private static AdminPermissionService adminPermissionService;

    @Autowired
    public void setSiteContextService(SiteContextService siteContextService) {
        TaleUtils.siteContextService = siteContextService;
    }

    @Autowired
    public void setUserSiteGroupService(UserSiteGroupService userSiteGroupService) {
        TaleUtils.userSiteGroupService = userSiteGroupService;
    }

    @Autowired
    public void setAdminPermissionService(AdminPermissionService adminPermissionService) {
        TaleUtils.adminPermissionService = adminPermissionService;
    }

    /**
     * 返回当前登录用户
     *
     * @return
     */
    public static UserEntity getLoginUser(HttpServletRequest request) {
        return SessionIdentity.admin(request);
    }

    public static Integer getUserGroupId(HttpServletRequest request) {
        UserEntity loginUser = getLoginUser(request);
        if (loginUser == null) {
            return null;
        }
        // 超级管理员不按站点取组
        if (WebConst.SUPER_ADMIN_ID.equals(loginUser.getId())) {
            return loginUser.getGroupId();
        }
        // 按当前站点取生效组，未命中回退默认组
        Long siteId = siteContextService.current(request).getSiteId();
        if (siteId != null) {
            Integer groupId =
                    userSiteGroupService.getGroupIdByUserIdAndSiteId(loginUser.getId(), siteId);
            if (groupId != null) {
                return groupId;
            }
        }
        return loginUser.getGroupId();
    }

    /** 是否全站管理员：超管账号（id=13 或用户名 supera），或平台组（site_id=0）成员。 平台组成员完全等同超级管理员，站点/栏目/菜单全部放行。 */
    public static boolean isFullAdmin(HttpServletRequest request) {
        if (request == null) {
            return isFullAdmin();
        }
        if (isExplicitSuperAdmin(getLoginUser(request))) {
            return true;
        }
        return adminPermissionService != null && adminPermissionService.snapshot(request).isAll();
    }

    /** 无显式 request 时（模板渲染等）从 RequestContextHolder 取当前请求判定 */
    public static boolean isFullAdmin() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return isFullAdmin(servletRequestAttributes.getRequest());
        }
        return false;
    }

    private static boolean isExplicitSuperAdmin(UserEntity user) {
        return user != null
                && (WebConst.SUPER_ADMIN_ID.equals(user.getId())
                        || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername()));
    }
}

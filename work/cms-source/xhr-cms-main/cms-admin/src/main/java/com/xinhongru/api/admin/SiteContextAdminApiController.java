package com.xinhongru.api.admin;

import com.xinhongru.entity.MenuEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.AdminPermissionSnapshot;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.service.menu.SystemMenuService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextRequiredException;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/site-context")
public class SiteContextAdminApiController {
    private final SiteContextService siteContextService;
    private final UserService userService;

    @Autowired
    public SiteContextAdminApiController(
            SiteContextService siteContextService, UserService userService) {
        this.siteContextService = siteContextService;
        this.userService = userService;
    }

    public SiteContextAdminApiController(SiteContextService siteContextService) {
        this(siteContextService, null);
    }

    private AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    private SystemMenuService systemMenuService;

    @Autowired
    public void setSystemMenuService(SystemMenuService systemMenuService) {
        this.systemMenuService = systemMenuService;
    }

    @GetMapping("/current")
    public AdminApiResponse<SiteContextResponse> current(HttpServletRequest request) {
        return AdminApiResponse.success(response(request, siteContextService.current(request)));
    }

    @PostMapping("/switch")
    public AdminApiResponse<SiteContextResponse> switchSite(
            HttpServletRequest request,
            @RequestBody(required = false) SwitchSiteRequest switchRequest) {
        Long siteId = switchRequest == null ? null : switchRequest.siteId();
        return AdminApiResponse.success(
                response(request, siteContextService.switchTo(request, siteId)));
    }

    @PostMapping("/clear")
    public AdminApiResponse<SiteContextResponse> clear(HttpServletRequest request) {
        return AdminApiResponse.success(response(request, siteContextService.clear(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }

    @ExceptionHandler(SiteContextRequiredException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleSiteContextRequired(
            SiteContextRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, ex.getMessage(), null));
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleAccessDenied(SiteAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AdminApiResponse<>(403, ex.getMessage(), null));
    }

    public record SwitchSiteRequest(Long siteId) {}

    public record SiteContextResponse(
            Long siteId,
            Integer rootMenuId,
            String siteName,
            String siteCode,
            boolean globalMode,
            CurrentUser currentUser,
            PermissionCapabilities capabilities) {}

    public record PermissionCapabilities(
            boolean canManageSystem,
            boolean canAccessPermissionCenter,
            boolean canManageSystemSettings,
            boolean platformAdmin,
            List<String> systemMenuPaths) {}

    public record CurrentUser(
            Integer id,
            String username,
            String screenName,
            String groupName,
            Integer groupId,
            String groupSetting,
            String otherSetting) {}

    private SiteContextResponse response(HttpServletRequest request, SiteContext context) {
        SiteContext normalized = context == null ? SiteContext.global() : context;
        CurrentUser user = currentUser(request);
        PermissionCapabilities capabilities = permissionCapabilities(user, request);
        return new SiteContextResponse(
                normalized.getSiteId(),
                normalized.getRootMenuId(),
                normalized.getSiteName(),
                normalized.getSiteCode(),
                normalized.isGlobalMode(),
                user,
                capabilities);
    }

    private PermissionCapabilities permissionCapabilities(
            CurrentUser user, HttpServletRequest request) {
        if (user == null) {
            return new PermissionCapabilities(false, false, false, false, List.of());
        }
        // RBAC 退役后，使用简化权限模型：基于 groupSetting 和超级管理员判断
        boolean isSuperAdmin =
                user.id() != null && user.id() == 13 || "supera".equals(user.username());

        String groupSetting = user.groupSetting() != null ? user.groupSetting() : "";
        boolean hasSystemManageFlag =
                Arrays.stream(groupSetting.split(",")).map(String::trim).anyMatch("6"::equals);

        AdminPermissionSnapshot snapshot =
                permissionService == null ? null : permissionService.snapshot(request);

        // 平台管理：超级管理员或绑定平台组（site_id=0），可跨站点管理；其余为站点管理
        boolean platformAdmin = isSuperAdmin || (snapshot != null && snapshot.isAll());

        boolean canManageSystem = isSuperAdmin || hasSystemManageFlag;
        boolean canManageSystemSettings = isSuperAdmin || hasSystemManageFlag;
        boolean canAccessPermissionCenter = isSuperAdmin || hasSystemManageFlag;

        return new PermissionCapabilities(
                canManageSystem,
                canAccessPermissionCenter,
                canManageSystemSettings,
                platformAdmin,
                resolveSystemMenuPaths(snapshot, isSuperAdmin));
    }

    /** 解析「菜单配置」授权的系统菜单路径。 返回 null 表示全放行（超级管理员 / 平台组）；返回空列表表示未授予任何系统菜单。 */
    private List<String> resolveSystemMenuPaths(
            AdminPermissionSnapshot snapshot, boolean isSuperAdmin) {
        if (isSuperAdmin || (snapshot != null && snapshot.isAll())) {
            return null;
        }
        if (snapshot == null || systemMenuService == null) {
            return List.of();
        }
        Set<Integer> allowed = snapshot.systemMenuIds();
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        List<MenuEntity> menus = systemMenuService.getMenuTree();
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (MenuEntity menu : menus) {
            if (menu == null
                    || menu.getId() == null
                    || !allowed.contains(menu.getId().intValue())) {
                continue;
            }
            if (!Boolean.TRUE.equals(menu.getVisible())) {
                continue;
            }
            String path = menu.getPath();
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        UserEntity sessionUser = SessionIdentity.admin(request);
        if (sessionUser == null || sessionUser.getId() == null) {
            return null;
        }
        UserEntity user =
                userService == null
                        ? null
                        : userService.getUserInfoById(sessionUser.getId(), false);
        if (user == null) {
            user = sessionUser;
        }
        // 使用「当前站点生效管理员组」的权限串（超管走默认组原始权限）
        AdminPermissionSnapshot snapshot =
                permissionService == null ? null : permissionService.snapshot(request);
        Integer groupId =
                snapshot != null && snapshot.groupId() != null
                        ? snapshot.groupId()
                        : user.getGroupId();
        String groupSetting = snapshot != null ? snapshot.groupSetting() : user.getGroupSetting();
        String otherSetting = snapshot != null ? snapshot.otherSetting() : user.getOtherSetting();
        return new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getScreenName(),
                user.getGroupName(),
                groupId,
                groupSetting,
                otherSetting);
    }
}

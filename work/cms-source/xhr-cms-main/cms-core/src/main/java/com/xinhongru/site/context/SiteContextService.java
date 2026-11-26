package com.xinhongru.site.context;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.AdminPermissionSnapshot;
import com.xinhongru.security.AdminSecurityExceptionHandler;
import com.xinhongru.service.user.UserService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class SiteContextService {
    private static final String AI_SITE = "AI_SITE";
    static final String PINNED_SITE_CONTEXT_ATTRIBUTE =
            SiteContextService.class.getName() + ".PINNED_SITE_CONTEXT";

    private final SiteProjectMapper projectMapper;
    private final OneShotMapper oneShotMapper;
    private final UserService userService;

    public SiteContextService(SiteProjectMapper projectMapper) {
        this(projectMapper, null, null);
    }

    public SiteContextService(SiteProjectMapper projectMapper, OneShotMapper oneShotMapper) {
        this(projectMapper, oneShotMapper, null);
    }

    @Autowired
    public SiteContextService(
            SiteProjectMapper projectMapper, OneShotMapper oneShotMapper, UserService userService) {
        this.projectMapper = Objects.requireNonNull(projectMapper, "projectMapper");
        this.oneShotMapper = oneShotMapper;
        this.userService = userService;
    }

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    private AdminPermissionService permissionService;

    public SiteContext current(HttpServletRequest request) {
        Object pinned = request.getAttribute(PINNED_SITE_CONTEXT_ATTRIBUTE);
        if (pinned instanceof SiteContext context) {
            return context;
        }
        HttpSession session = request.getSession();
        Long sessionSiteId = sessionSiteId(session);
        if (sessionSiteId != null) {
            SiteProjectEntity sessionSite = findActiveSiteById(sessionSiteId);
            if (sessionSite != null) {
                if (canAccessRequestSite(request, sessionSite)) {
                    return toContext(sessionSite);
                }
                session.removeAttribute(WebConst.CURRENT_SESSION_SITE);
            } else {
                session.removeAttribute(WebConst.CURRENT_SESSION_SITE);
            }
        }
        return SiteContext.global();
    }

    public SiteContext switchTo(HttpServletRequest request, Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("siteId is required");
        }
        SiteProjectEntity site = findActiveSiteById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在、未绑定根栏目或已删除");
        }
        assertCanAccessSite(request, site);
        storeSessionSite(request.getSession(), site);
        return toContext(site);
    }

    public SiteContext clear(HttpServletRequest request) {
        request.getSession().removeAttribute(WebConst.CURRENT_SESSION_SITE);
        return SiteContext.global();
    }

    public SiteContext requireCurrentSite(HttpServletRequest request) {
        SiteContext context = current(request);
        if (context.isGlobalMode() || context.getRootMenuId() == null) {
            throw new SiteContextRequiredException("请先创建或选择站点");
        }
        return context;
    }

    public long requireCurrentSiteId(HttpServletRequest request) {
        SiteContext context = current(request);
        if (context.isGlobalMode() || context.getSiteId() == null) {
            throw AdminSecurityExceptionHandler.AdminSecurityException.conflict(
                    "SITE_CONTEXT_REQUIRED", "请先选择站点后重试");
        }
        return context.getSiteId();
    }

    void pinCurrentRequest(HttpServletRequest request, SiteContext context) {
        if (request == null
                || context == null
                || context.isGlobalMode()
                || context.getSiteId() == null) {
            throw new IllegalArgumentException("请求站点上下文无效");
        }
        Object pinned = request.getAttribute(PINNED_SITE_CONTEXT_ATTRIBUTE);
        if (pinned instanceof SiteContext existing
                && !Objects.equals(existing.getSiteId(), context.getSiteId())) {
            throw AdminSecurityExceptionHandler.AdminSecurityException.forbidden(
                    "RESOURCE_SITE_MISMATCH", "请求资源不属于当前站点");
        }
        if (pinned == null) {
            request.setAttribute(PINNED_SITE_CONTEXT_ATTRIBUTE, context);
        }
    }

    public List<SiteProjectEntity> listAvailableSites(HttpServletRequest request) {
        UserEntity user = permissionUser(request);
        if (isSuperAdmin(user)) {
            return queryAvailableSites(null);
        }
        if (user == null) {
            return List.of();
        }
        AdminPermissionSnapshot snapshot = permissionService.snapshot(request);
        if (snapshot.canAccessAllSites()) {
            return queryAvailableSites(null);
        }
        Set<Long> accessible = snapshot.accessibleSiteIds();
        if (accessible == null || accessible.isEmpty()) {
            return List.of();
        }
        return queryAvailableSites(accessible);
    }

    private List<SiteProjectEntity> queryAvailableSites(Set<Long> siteIds) {
        QueryWrapper<SiteProjectEntity> wrapper =
                new QueryWrapper<SiteProjectEntity>()
                        .eq("deleted", 0)
                        .eq("project_type", AI_SITE)
                        .isNotNull("root_menu_id")
                        .orderByDesc("is_current")
                        .orderByDesc("update_time")
                        .orderByDesc("id");
        if (siteIds != null) {
            wrapper.in("id", siteIds);
        }
        return projectMapper.selectList(wrapper);
    }

    public void requireSuperAdmin(HttpServletRequest request) {
        if (isFullAdmin(request)) {
            return;
        }
        throw new SiteAccessDeniedException("只有超级管理员可以维护站点");
    }

    /** 超管账号或平台组（site_id=0）成员均视为全站管理员 */
    private boolean isFullAdmin(HttpServletRequest request) {
        UserEntity user = permissionUser(request);
        if (isSuperAdmin(user)) {
            return true;
        }
        return permissionService != null && permissionService.snapshot(request).isAll();
    }

    /** 显式资源站点编码必须解析为真实站点后再校验账号访问权限。 */
    public SiteProjectEntity requireAccessibleSiteCode(HttpServletRequest request, String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("站点编码无效");
        SiteProjectEntity site =
                projectMapper.selectOne(
                        new QueryWrapper<SiteProjectEntity>()
                                .eq("project_code", code)
                                .eq("deleted", 0)
                                .isNotNull("root_menu_id")
                                .last("limit 1"));
        if (site == null) throw new IllegalArgumentException("站点不存在或未绑定根栏目");
        assertCanAccessSite(request, site);
        return site;
    }

    public void assertCanAccessSite(HttpServletRequest request, SiteProjectEntity site) {
        if (canAccessRequestSite(request, site)) {
            return;
        }
        throw new SiteAccessDeniedException("无权访问该站点");
    }

    public void assertCanAccessSite(HttpServletRequest request, Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("siteId is required");
        }
        SiteProjectEntity site = findActiveSiteById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在、未绑定根栏目或已删除");
        }
        assertCanAccessSite(request, site);
    }

    /**
     * Verify that a menu belongs to the selected site structurally. Authorization is evaluated
     * separately by RBAC, so this method cannot accidentally re-enable legacy group permissions.
     */
    public void assertMenuInCurrentSite(HttpServletRequest request, Integer menuId) {
        if (menuId == null || menuId <= 0) {
            throw new IllegalArgumentException("栏目 ID 无效");
        }
        SiteContext context = requireCurrentSite(request);
        if (Objects.equals(context.getRootMenuId(), menuId)) {
            return;
        }
        if (oneShotMapper == null) {
            throw new SiteAccessDeniedException("当前站点无法校验栏目范围");
        }
        List<OneshotEntity> menus = oneShotMapper.selectBySiteRoot(context.getRootMenuId());
        boolean exists =
                (menus == null ? List.<OneshotEntity>of() : menus)
                        .stream()
                                .filter(Objects::nonNull)
                                .filter(menu -> !Boolean.TRUE.equals(menu.getIsDeleted()))
                                .map(OneshotEntity::getId)
                                .anyMatch(menuId::equals);
        if (!exists) {
            throw new SiteAccessDeniedException("栏目不属于当前站点");
        }
    }

    /** Validates several menu ids against one site-tree read. */
    public void assertMenusInCurrentSite(HttpServletRequest request, List<Integer> menuIds) {
        List<Integer> requested =
                menuIds == null
                        ? List.of()
                        : menuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) return;
        if (requested.stream().anyMatch(menuId -> menuId <= 0)) {
            throw new IllegalArgumentException("栏目 ID 无效");
        }
        SiteContext context = requireCurrentSite(request);
        Set<Integer> available = new LinkedHashSet<>();
        if (context.getRootMenuId() != null) available.add(context.getRootMenuId());
        if (oneShotMapper != null) {
            List<OneshotEntity> menus = oneShotMapper.selectBySiteRoot(context.getRootMenuId());
            (menus == null ? List.<OneshotEntity>of() : menus)
                    .stream()
                            .filter(Objects::nonNull)
                            .filter(menu -> !Boolean.TRUE.equals(menu.getIsDeleted()))
                            .map(OneshotEntity::getId)
                            .filter(Objects::nonNull)
                            .forEach(available::add);
        }
        if (!available.containsAll(requested)) {
            throw new SiteAccessDeniedException("栏目不属于当前站点");
        }
    }

    private boolean canAccessRequestSite(HttpServletRequest request, SiteProjectEntity site) {
        if (site == null || site.getRootMenuId() == null) {
            return false;
        }
        if (permissionService == null) {
            return false;
        }
        AdminPermissionSnapshot snapshot = permissionService.snapshot(request);
        if (snapshot.canAccessAllSites()) {
            return true;
        }
        Set<Long> accessible = snapshot.accessibleSiteIds();
        return accessible != null && accessible.contains(site.getId());
    }

    private UserEntity permissionUser(HttpServletRequest request) {
        UserEntity sessionUser = TaleUtils.getLoginUser(request);
        if (sessionUser == null || sessionUser.getId() == null || userService == null) {
            return sessionUser;
        }
        UserEntity persisted = userService.getUserInfoById(sessionUser.getId(), false);
        return persisted == null ? sessionUser : persisted;
    }

    private boolean isSuperAdmin(UserEntity user) {
        return user != null
                && (WebConst.SUPER_ADMIN_ID.equals(user.getId())
                        || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername()));
    }

    private SiteProjectEntity findActiveSiteById(Long siteId) {
        return projectMapper.selectOne(
                new QueryWrapper<SiteProjectEntity>()
                        .eq("id", siteId)
                        .eq("deleted", 0)
                        .eq("project_type", AI_SITE)
                        .isNotNull("root_menu_id")
                        .last("limit 1"));
    }

    private Long sessionSiteId(HttpSession session) {
        Object value = session.getAttribute(WebConst.CURRENT_SESSION_SITE);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void storeSessionSite(HttpSession session, SiteProjectEntity site) {
        session.setAttribute(WebConst.CURRENT_SESSION_SITE, site.getId());
    }

    private SiteContext toContext(SiteProjectEntity site) {
        return new SiteContext(
                site.getId(),
                site.getRootMenuId(),
                site.getProjectName(),
                site.getProjectCode(),
                false);
    }
}

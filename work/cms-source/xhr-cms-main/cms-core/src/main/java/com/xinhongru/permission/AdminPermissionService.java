package com.xinhongru.permission;

import com.alibaba.fastjson.JSON;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 管理员组权限快照的唯一解析入口（性能优先、逻辑集中）。
 *
 * <p>按「当前登录用户 + 站点」解析一次的只读快照：超管或平台组（site_id=0）短路全放行； 普通用户经 t_user_site_group 取站点可见范围与当前站点生效组， 回退
 * t_usergroups 的 group_setting / menu_setting / other_setting 解析集合。 同一请求内多次调用复用 request 级缓存。
 *
 * <p>注意：获取当前站点直接读 session，不依赖 SiteContextService.current() （current 会再次走权限，避免递归）。
 */
@Service
public class AdminPermissionService {
    private static final String CACHE_ATTR_PREFIX =
            AdminPermissionService.class.getName() + ".snapshot.";

    private final UserService userService;
    private final UserSiteGroupService userSiteGroupService;
    private final UserGroupService userGroupService;

    public AdminPermissionService(
            UserService userService,
            UserSiteGroupService userSiteGroupService,
            UserGroupService userGroupService) {
        this.userService = userService;
        this.userSiteGroupService = userSiteGroupService;
        this.userGroupService = userGroupService;
    }

    /** 当前登录用户在「当前站点」下的权限快照 */
    public AdminPermissionSnapshot snapshot(HttpServletRequest request) {
        return snapshot(request, null);
    }

    /** 数据中台操作校验：受限平台组按 platform_setting 判定，未配置/超管/站点组放行；不通过返回 403 */
    public void requirePlatformOperate(
            HttpServletRequest request, String type, Integer categoryId, int optionId) {
        if (!snapshot(request).canPlatformOperate(type, categoryId, optionId)) {
            throw AdminSecurityException.forbidden("PLATFORM_OPERATION_DENIED", "无权执行该数据中台操作");
        }
    }

    /** 当前登录用户在某站点下的权限快照（siteId 为 null 时用 session 当前站点） */
    public AdminPermissionSnapshot snapshot(HttpServletRequest request, Long siteId) {
        Long effectiveSiteId = siteId != null ? siteId : currentSessionSiteId(request);
        String key = CACHE_ATTR_PREFIX + effectiveSiteId;
        Object cached = request.getAttribute(key);
        if (cached instanceof AdminPermissionSnapshot snap) {
            return snap;
        }
        AdminPermissionSnapshot snap = resolve(request, effectiveSiteId);
        request.setAttribute(key, snap);
        return snap;
    }

    private AdminPermissionSnapshot resolve(HttpServletRequest request, Long effectiveSiteId) {
        UserEntity login = TaleUtils.getLoginUser(request);
        if (login == null || login.getId() == null) {
            return empty(effectiveSiteId);
        }
        UserEntity persisted = userService.getUserInfoById(login.getId(), false);
        UserEntity principal = persisted != null ? persisted : login;

        return resolvePrincipal(principal, effectiveSiteId);
    }

    /** Background tools recheck the persisted actor without retaining a servlet request/cache. */
    public AdminPermissionSnapshot snapshotForActor(Integer actorId, Long siteId) {
        if (actorId == null) return empty(siteId);
        UserEntity principal = userService.getUserInfoById(actorId, false);
        if (principal == null || !Integer.valueOf(0).equals(principal.getActivated())) {
            return empty(siteId);
        }
        return resolvePrincipal(principal, siteId);
    }

    private AdminPermissionSnapshot resolvePrincipal(UserEntity principal, Long effectiveSiteId) {

        // 超级管理员：全放行，集合置 null
        if (isSuperAdmin(principal)) {
            return new AdminPermissionSnapshot(
                    effectiveSiteId,
                    principal.getId(),
                    true,
                    principal.getGroupId(),
                    principal.getGroupSetting(),
                    principal.getOtherSetting(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        // 普通用户：一次查询拿全部站点-组关联
        // siteId=0（平台组）单独识别为「平台组归属」；其余真实站点进可见站点集合
        List<UserSiteGroupEntity> refs = userSiteGroupService.listByUserId(principal.getId());
        Set<Long> accessibleSiteIds = new LinkedHashSet<>();
        Integer platformGroupId = null;
        Map<Integer, UserGroupEntity> groupsById = new HashMap<>();
        if (refs != null) {
            for (UserSiteGroupEntity ref : refs) {
                if (ref.getSiteId() == null || ref.getGroupId() == null) {
                    continue;
                }
                if (!groupsById.containsKey(ref.getGroupId())) {
                    groupsById.put(ref.getGroupId(), userGroupService.getUserGroupById(ref.getGroupId()));
                }
                UserGroupEntity candidate = groupsById.get(ref.getGroupId());
                if (candidate == null || Boolean.TRUE.equals(candidate.getDeleted())
                        || !Objects.equals(candidate.getSiteId(), ref.getSiteId())) {
                    continue;
                }
                if (WebConst.PLATFORM_SITE_ID.equals(ref.getSiteId())) {
                    platformGroupId = ref.getGroupId();
                } else {
                    accessibleSiteIds.add(ref.getSiteId());
                }
            }
        }

        List<UserGroupEntity> selectedGroups = new java.util.ArrayList<>();
        boolean matchedSite = false;
        Integer groupId = null;
        UserGroupEntity group = null;
        if (platformGroupId != null) {
            // 平台组归属：生效组即平台组
            groupId = platformGroupId;
            group = groupsById.get(platformGroupId);
        } else {
            // 当前站点命中的生效组
            if (refs != null && effectiveSiteId != null) {
                for (UserSiteGroupEntity ref : refs) {
                    if (Objects.equals(ref.getSiteId(), effectiveSiteId)) {
                        matchedSite = true;
                        UserGroupEntity candidate = groupsById.get(ref.getGroupId());
                        if (candidate != null && !Boolean.TRUE.equals(candidate.getDeleted())
                                && Objects.equals(candidate.getSiteId(), effectiveSiteId)) {
                            selectedGroups.add(candidate);
                        }
                    }
                }
            }
            // 未命中 → 回退默认组（t_users.group_id），并保证至少该默认组所属站点可见
            selectedGroups.sort(java.util.Comparator.comparing(UserGroupEntity::getId));
            if (!selectedGroups.isEmpty()) {
                group = selectedGroups.stream()
                        .filter(item -> Objects.equals(item.getId(), principal.getGroupId()))
                        .findFirst().orElse(selectedGroups.get(0));
                groupId = group.getId();
            } else if (!matchedSite && principal.getGroupId() != null) {
                if (!groupsById.containsKey(principal.getGroupId())) {
                    groupsById.put(principal.getGroupId(), userGroupService.getUserGroupById(principal.getGroupId()));
                }
                group = groupsById.get(principal.getGroupId());
                if (group != null && !Boolean.TRUE.equals(group.getDeleted())
                        && (WebConst.PLATFORM_SITE_ID.equals(group.getSiteId())
                            || effectiveSiteId == null || Objects.equals(group.getSiteId(), effectiveSiteId))) {
                    groupId = group.getId();
                    if (group.getSiteId() != null
                            && !WebConst.PLATFORM_SITE_ID.equals(group.getSiteId())) {
                        accessibleSiteIds.add(group.getSiteId());
                    }
                } else {
                    group = null;
                }
            }
        }

        // 平台组（site_id=0）：站点/栏目/菜单完全放行；数据中台按平台组合类别权限受限（未配置即放行）
        if (group != null && WebConst.PLATFORM_SITE_ID.equals(group.getSiteId())) {
            boolean platformRestricted = StringUtils.isNotBlank(group.getPlatformSetting());
            Map<String, Set<Integer>> platformPerms =
                    platformRestricted
                            ? PlatformPermission.parsePlatformSetting(
                                    group.getPlatformSetting(), group.getPlatformOtherSetting())
                            : null;
            return new AdminPermissionSnapshot(
                    effectiveSiteId,
                    principal.getId(),
                    true,
                    group.getId(),
                    group.getGroupSetting(),
                    group.getOtherSetting(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    platformRestricted,
                    platformPerms == null ? null : new LinkedHashSet<>(platformPerms.keySet()),
                    platformPerms);
        }

        if (group == null) {
            return new AdminPermissionSnapshot(
                    effectiveSiteId,
                    principal.getId(),
                    false,
                    groupId,
                    "",
                    "",
                    "",
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    accessibleSiteIds,
                    Collections.emptySet(),
                    false,
                    null,
                    null);
        }
        if (selectedGroups.isEmpty()) selectedGroups.add(group);
        Map<Integer, Set<Integer>> merged = new java.util.TreeMap<>();
        Set<Integer> systemMenus = new java.util.TreeSet<>();
        for (UserGroupEntity item : selectedGroups) {
            Map<Integer, Set<Integer>> options = parseOtherSetting(item.getGroupSetting(), item.getOtherSetting());
            for (Integer menuId : parseGroupSetting(item.getGroupSetting())) {
                merged.computeIfAbsent(menuId, ignored -> new java.util.TreeSet<>())
                        .addAll(options.getOrDefault(menuId, Collections.emptySet()));
            }
            systemMenus.addAll(parseMenuSetting(item.getMenuSetting()));
        }
        String mergedGroups = merged.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        String mergedOptions = merged.values().stream()
                .map(options -> options.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining(";"));
        return new AdminPermissionSnapshot(
                effectiveSiteId,
                principal.getId(),
                false,
                groupId,
                mergedGroups,
                mergedOptions,
                JSON.toJSONString(systemMenus),
                merged.keySet(),
                merged,
                accessibleSiteIds,
                systemMenus,
                false,
                null,
                null);
    }

    private static boolean isSuperAdmin(UserEntity user) {
        return user != null
                && (WebConst.SUPER_ADMIN_ID.equals(user.getId())
                        || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername()));
    }

    private static Long currentSessionSiteId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
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

    private static AdminPermissionSnapshot empty(Long siteId) {
        return new AdminPermissionSnapshot(
                siteId,
                null,
                false,
                null,
                "",
                "",
                "",
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptySet(),
                false,
                null,
                null);
    }

    /** group_setting 逗号分隔栏目 id → Set */
    static Set<Integer> parseGroupSetting(String groupSetting) {
        if (StringUtils.isBlank(groupSetting)) {
            return new LinkedHashSet<>();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (String item : groupSetting.split(",")) {
            try {
                ids.add(Integer.parseInt(item.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略非数字
            }
        }
        return ids;
    }

    /** other_setting 分号分段、与 group_setting 逐位对齐、段内逗号分隔 optionId → Map<栏目id, Set<optionId>> */
    static Map<Integer, Set<Integer>> parseOtherSetting(String groupSetting, String otherSetting) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        if (StringUtils.isBlank(groupSetting) || StringUtils.isBlank(otherSetting)) {
            return result;
        }
        String[] menuKeys = groupSetting.split(",");
        String[] segments = otherSetting.split(";", menuKeys.length);
        for (int i = 0; i < menuKeys.length; i++) {
            Integer menuId;
            try {
                menuId = Integer.parseInt(menuKeys[i].trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            String segment = i < segments.length ? segments[i] : "";
            Set<Integer> options = new LinkedHashSet<>();
            if (StringUtils.isNotBlank(segment)) {
                for (String option : segment.split(",")) {
                    try {
                        options.add(Integer.parseInt(option.trim()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字
                    }
                }
            }
            if (!options.isEmpty()) {
                result.put(menuId, options);
            }
        }
        return result;
    }

    /** menu_setting JSON 数组（菜单id） → Set */
    static Set<Integer> parseMenuSetting(String menuSetting) {
        if (StringUtils.isBlank(menuSetting)) {
            return new LinkedHashSet<>();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            for (Object item : JSON.parseArray(menuSetting)) {
                if (item instanceof Number number) {
                    ids.add(number.intValue());
                }
            }
        } catch (Exception ignored) {
            return new LinkedHashSet<>();
        }
        return ids;
    }
}

package com.xinhongru.api.admin;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.utils.AuthorityUtils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 管理员账号的目标账号和组归属授权。 */
@Service
public class AdminAccountAccess {
    private final AdminAuthenticationService authentication;
    private final UserGroupService groups;
    private final UserSiteGroupService assignments;

    public AdminAccountAccess(
            AdminAuthenticationService authentication, UserGroupService groups,
            UserSiteGroupService assignments) {
        this.authentication = authentication;
        this.groups = groups;
        this.assignments = assignments;
    }

    public Scope requireScope(HttpServletRequest request) {
        Scope scope = new Scope(authentication.requireLoginSessionUser(request));
        if (!scope.superAdmin && scope.actorGroups.isEmpty()) {
            throw denied();
        }
        return scope;
    }

    public static boolean protectedAccount(UserEntity user) {
        return Integer.valueOf(1).equals(user.getId())
                || WebConst.SUPER_ADMIN_ID.equals(user.getId())
                || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername());
    }

    public static void requireCreatableUsername(String username) {
        if (WebConst.SUPER_ADMIN_NAME.equalsIgnoreCase(username)) {
            throw denied();
        }
    }

    private static AdminSecurityException denied() {
        return AdminSecurityException.forbidden("ADMIN_ACCOUNT_ACCESS_DENIED", "无权管理该账号或管理员组");
    }

    public final class Scope {
        private final UserEntity actor;
        private final boolean superAdmin;
        private final Map<Integer, UserGroupEntity> groupCache = new HashMap<>();
        private final Set<UserGroupEntity> actorGroups = new HashSet<>();
        private final Map<Integer, List<UserSiteGroupEntity>> assignmentCache = new HashMap<>();

        private Scope(UserEntity actor) {
            this.actor = actor;
            this.superAdmin = WebConst.SUPER_ADMIN_ID.equals(actor.getId())
                    || WebConst.SUPER_ADMIN_NAME.equals(actor.getUsername());
            if (superAdmin) {
                return;
            }
            List<UserSiteGroupEntity> refs = relations(actor);
            refs.forEach(ref -> addActorGroup(group(ref.getGroupId()), ref.getSiteId()));
            UserGroupEntity primary = group(actor.getGroupId());
            if (primary != null && refs.stream().noneMatch(ref -> Objects.equals(ref.getSiteId(), primary.getSiteId()))) {
                addActorGroup(primary, primary.getSiteId());
            }
        }

        private void addActorGroup(UserGroupEntity group, Long site) {
            if (active(group) && site != null && site.equals(group.getSiteId())
                    && (WebConst.PLATFORM_SITE_ID.equals(site)
                        || AuthorityUtils.hasGroupSetting(group.getGroupSetting(), WebConst.ADMIN_MANAGE_MENU_ID))) {
                actorGroups.add(group);
            }
        }

        public boolean platform() {
            return superAdmin || actorGroups.stream()
                    .anyMatch(group -> WebConst.PLATFORM_SITE_ID.equals(group.getSiteId()));
        }

        public boolean canManageGroup(UserGroupEntity group) {
            return group != null && (WebConst.PLATFORM_SITE_ID.equals(group.getSiteId())
                    ? superAdmin
                    : managesGroup(group));
        }

        public void requireGroupAccess(UserGroupEntity group) {
            if (!canManageGroup(group)) {
                throw denied();
            }
        }

        public boolean canUseParent(UserGroupEntity group) {
            return active(group) && (canManageGroup(group) || actorGroups.stream()
                    .anyMatch(own -> group.getId().equals(own.getId())
                            && !WebConst.PLATFORM_SITE_ID.equals(own.getSiteId())));
        }

        public void prepareGroupSave(UserGroupEntity body, UserGroupEntity before, Long siteId) {
            if (siteId == null || (WebConst.PLATFORM_SITE_ID.equals(siteId) && !superAdmin)) {
                throw denied();
            }
            if (before != null) {
                requireGroupAccess(before);
                if (!siteId.equals(before.getSiteId())) {
                    throw denied();
                }
            }
            body.setSiteId(siteId);
            Integer parentId = body.getParentId();
            if (parentId == null) {
                parentId = before == null ? 0 : before.getParentId();
                body.setParentId(parentId);
            }
            if (before != null && Objects.equals(parentId, before.getParentId())) {
                return;
            }
            if (Integer.valueOf(0).equals(parentId)) {
                if (!platform()) throw denied();
                return;
            }
            UserGroupEntity parent = group(parentId);
            if (!canUseParent(parent) || !siteId.equals(parent.getSiteId())) {
                throw denied();
            }
            Set<Integer> seen = new HashSet<>();
            if (before != null) seen.add(before.getId());
            while (parent != null) {
                if (!seen.add(parent.getId())) {
                    throw AdminSecurityException.badRequest("ADMIN_GROUP_CYCLE", "管理员组的父级关系不能形成循环");
                }
                parent = group(parent.getParentId());
            }
        }

        public boolean canGrant(UserGroupEntity candidate) {
            if (!active(candidate) || candidate.getSiteId() == null) {
                return false;
            }
            return managesGroup(candidate);
        }

        private boolean managesGroup(UserGroupEntity candidate) {
            if (superAdmin) {
                return true;
            }
            if (candidate == null || candidate.getSiteId() == null) {
                return false;
            }
            Long siteId = candidate.getSiteId();
            if (!WebConst.PLATFORM_SITE_ID.equals(siteId) && platform()) {
                return true;
            }
            Set<Integer> visited = new HashSet<>();
            visited.add(candidate.getId());
            UserGroupEntity node = candidate;
            while (node.getParentId() != null && node.getParentId() > 0
                    && visited.add(node.getParentId())) {
                Integer parent = node.getParentId();
                if (actorGroups.stream().anyMatch(group -> parent.equals(group.getId())
                        && siteId.equals(group.getSiteId()))) {
                    return true;
                }
                node = group(parent);
                if (node == null || !siteId.equals(node.getSiteId())) {
                    break;
                }
            }
            return false;
        }

        public boolean canRead(UserEntity target) {
            if (target == null) {
                return false;
            }
            if (superAdmin || actor.getId().equals(target.getId())) {
                return true;
            }
            return canEditRelations(target);
        }

        private boolean canEditRelations(UserEntity target) {
            if (protectedAccount(target) || actor.getId().equals(target.getId())) {
                return false;
            }
            List<UserSiteGroupEntity> refs = relations(target);
            UserGroupEntity primary = group(target.getGroupId());
            boolean platformTarget = refs.stream().anyMatch(ref -> WebConst.PLATFORM_SITE_ID.equals(ref.getSiteId()))
                    || (primary != null && WebConst.PLATFORM_SITE_ID.equals(primary.getSiteId()));
            if (platformTarget) {
                return platform() && managesGroup(primary) && refs.stream().allMatch(this::canManage);
            }
            return managesGroup(primary) || refs.stream().anyMatch(this::canManage);
        }

        public void requireRead(UserEntity target) {
            if (!canRead(target)) {
                throw denied();
            }
        }

        public void requireAccountWrite(UserEntity target) {
            if (!canWriteAccount(target)) {
                throw denied();
            }
        }

        public boolean canWriteAccount(UserEntity target) {
            return target != null && !protectedAccount(target) && !actor.getId().equals(target.getId())
                    && (superAdmin || (canEditRelations(target)
                        && (target.getGroupId() == null || target.getGroupId() <= 0
                            || managesGroup(group(target.getGroupId())))
                        && relations(target).stream().allMatch(this::canManage)));
        }

        public List<UserSiteGroupEntity> protectedRelations(UserEntity target) {
            if (target == null || protectedAccount(target) || actor.getId().equals(target.getId())
                    || (!superAdmin && !canEditRelations(target))) {
                throw denied();
            }
            List<UserSiteGroupEntity> refs = relations(target);
            var preserved = new java.util.ArrayList<>(refs.stream()
                    .filter(ref -> !canManage(ref)).toList());
            UserGroupEntity primary = group(target.getGroupId());
            if (!superAdmin && primary != null && primary.getSiteId() != null
                    && !managesGroup(primary)
                    && refs.stream().noneMatch(ref -> primary.getSiteId().equals(ref.getSiteId()))) {
                preserved.add(new UserSiteGroupEntity()
                        .setSiteId(primary.getSiteId()).setGroupId(primary.getId()));
            }
            return preserved;
        }

        public void requirePrimaryGroup(Integer groupId, UserEntity target) {
            UserGroupEntity candidate = group(groupId);
            if (!canGrant(candidate)) {
                throw denied();
            }
            if (target != null && !relations(target).isEmpty()
                    && relations(target).stream().noneMatch(ref -> groupId.equals(ref.getGroupId())
                        && candidate.getSiteId().equals(ref.getSiteId()))) {
                throw denied();
            }
        }

        public boolean canManage(UserSiteGroupEntity ref) {
            UserGroupEntity group = group(ref.getGroupId());
            return group != null && Objects.equals(ref.getSiteId(), group.getSiteId())
                    && managesGroup(group);
        }

        private List<UserSiteGroupEntity> relations(UserEntity user) {
            return assignmentCache.computeIfAbsent(user.getId(), assignments::listByUserId);
        }

        private UserGroupEntity group(Integer id) {
            if (id == null || id <= 0) {
                return null;
            }
            if (!groupCache.containsKey(id)) {
                groupCache.put(id, groups.getUserGroupById(id));
            }
            return groupCache.get(id);
        }
    }

    private static boolean active(UserGroupEntity group) {
        return group != null && !Boolean.FALSE.equals(group.getEnabled())
                && !Boolean.TRUE.equals(group.getDeleted());
    }
}

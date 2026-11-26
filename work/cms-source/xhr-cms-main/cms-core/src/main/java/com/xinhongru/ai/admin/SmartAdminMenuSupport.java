package com.xinhongru.ai.admin;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.utils.TaleUtils;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SmartAdminMenuSupport {
    public static final int SMART_SITE_ROOT_ID = 990000;
    public static final int AI_CONFIG_ID = 990001;
    public static final int CMS_MENU_MANAGEMENT_ID = 990008;

    private SmartAdminMenuSupport() {}

    public static List<OneshotEntity> withSmartSiteMenus(
            List<OneshotEntity> visibleMenus, UserEntity userEntity) {
        List<OneshotEntity> menus =
                visibleMenus == null ? new ArrayList<>() : new ArrayList<>(visibleMenus);
        if (!shouldExposeSmartMenus(userEntity)) {
            return menus;
        }
        Set<Integer> existingIds =
                menus.stream()
                        .map(OneshotEntity::getId)
                        .collect(Collectors.toCollection(HashSet::new));
        smartSiteMenusFor(userEntity).stream()
                .filter(menu -> !existingIds.contains(menu.getId()))
                .forEach(menus::add);
        return menus;
    }

    public static boolean shouldExposeSmartMenus(UserEntity userEntity) {
        if (userEntity == null) {
            return false;
        }
        if (isSuperAdmin(userEntity)) {
            return true;
        }
        Set<String> permissionIds = permissionIds(userEntity);
        return smartSiteMenus().stream()
                .map(OneshotEntity::getId)
                .map(String::valueOf)
                .anyMatch(permissionIds::contains);
    }

    public static boolean canAccessSmartMenu(UserEntity userEntity, int menuId) {
        if (userEntity == null) {
            return false;
        }
        return isSuperAdmin(userEntity)
                || permissionIds(userEntity).contains(String.valueOf(menuId));
    }

    private static boolean isSuperAdmin(UserEntity userEntity) {
        return WebConst.SUPER_ADMIN_ID.equals(userEntity.getId())
                || WebConst.SUPER_ADMIN_NAME.equals(userEntity.getUsername())
                || TaleUtils.isFullAdmin();
    }

    private static Set<String> permissionIds(UserEntity userEntity) {
        String groupSetting = userEntity == null ? null : userEntity.getGroupSetting();
        if (StringUtils.isBlank(groupSetting)) {
            return Set.of();
        }
        return Arrays.stream(groupSetting.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }

    private static List<OneshotEntity> smartSiteMenusFor(UserEntity userEntity) {
        if (isSuperAdmin(userEntity)) {
            return smartSiteMenus();
        }
        Set<String> permissionIds = permissionIds(userEntity);
        List<OneshotEntity> menus = new ArrayList<>();
        if (permissionIds.contains(String.valueOf(SMART_SITE_ROOT_ID))
                || smartSiteMenus().stream()
                        .filter(menu -> menu.getParentId() == SMART_SITE_ROOT_ID)
                        .map(OneshotEntity::getId)
                        .map(String::valueOf)
                        .anyMatch(permissionIds::contains)) {
            menus.add(menu(SMART_SITE_ROOT_ID, "站点智能管理", 0, 380, "", "fa-magic"));
        }
        smartSiteMenus().stream()
                .filter(menu -> menu.getParentId() == SMART_SITE_ROOT_ID)
                .filter(menu -> permissionIds.contains(String.valueOf(menu.getId())))
                .forEach(menus::add);
        return menus;
    }

    private static List<OneshotEntity> smartSiteMenus() {
        List<OneshotEntity> menus = new ArrayList<>();
        menus.add(menu(SMART_SITE_ROOT_ID, "站点智能管理", 0, 380, "", "fa-magic"));
        menus.add(
                menu(
                        CMS_MENU_MANAGEMENT_ID,
                        "栏目管理",
                        SMART_SITE_ROOT_ID,
                        8,
                        "/admin/v2/cms/menus",
                        "fa-sitemap"));
        menus.add(
                menu(
                        AI_CONFIG_ID,
                        "AI 接口配置",
                        SMART_SITE_ROOT_ID,
                        20,
                        "/admin/v2/ai/config",
                        "fa-plug"));
        return menus;
    }

    private static OneshotEntity menu(
            int id, String className, int parentId, int sort, String url, String iconClass) {
        return new OneshotEntity()
                .setId(id)
                .setClassName(className)
                .setParentId(parentId)
                .setSort(sort)
                .setUrl(url)
                .setIconClass(iconClass)
                .setHasManageChannel(true)
                .setHasFrontChannel(false)
                .setPageType(0)
                .setIsSubMenu(false)
                .setIsSystem(true)
                .setIsCanDeleted(false)
                .setIsCanAddSub(false)
                .setIsCanEdit(false)
                .setIsCanSort(true);
    }
}

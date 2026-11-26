package com.xinhongru.permission;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 当前登录用户在当前站点下的管理员组权限快照（只读、单请求解析一次）。 数据来源：t_user_site_group（站点可见范围） +
 * t_usergroups（group_setting/menu_setting/other_setting）。 集合为 null
 * 表示「全放行」（超级管理员或平台组成员）；非超管且非平台组时集合永不为 null（无权限即为空集）。
 */
public class AdminPermissionSnapshot {
    /** 当前站点，用于请求级缓存 key */
    private final Long siteId;

    private final Integer userId;
    private final boolean superAdmin;

    /** 生效管理员组（当前站点命中或回退默认组） */
    private final Integer groupId;

    private final String groupSetting;
    private final String otherSetting;
    private final String menuSetting;

    /** group_setting 解析：有栏目操作权限的栏目 id 集合；null=ALL（超管） */
    private final Set<Integer> menuIdSet;

    /** other_setting 解析：栏目id → 可执行操作 optionId 集合（与 group_setting 逐位对齐）；null=ALL（超管） */
    private final Map<Integer, Set<Integer>> optionByMenuId;

    /** t_user_site_group 站点可见范围；null=ALL（超级管理员或平台组成员） */
    private final Set<Long> accessibleSiteIds;

    /** menu_setting 解析：可见系统菜单 id 集合；null=ALL（超管） */
    private final Set<Integer> systemMenuIds;

    /** 数据中台权限是否受限：生效组为平台组（site_id=0）且 platform_setting 非空；false=全放行 */
    private final boolean platformRestricted;

    /** platform_setting 解析：已配置的数据中台类别键（{@code 类型:类别id}，如 product:12） */
    private final Set<String> platformCategoryKeys;

    /** platform_other_setting 解析：类别键 → 可执行操作 optionId 集合（1新增/2编辑/3删除/4审核/5查看） */
    private final Map<String, Set<Integer>> platformOptionByKey;

    public AdminPermissionSnapshot(
            Long siteId,
            Integer userId,
            boolean superAdmin,
            Integer groupId,
            String groupSetting,
            String otherSetting,
            String menuSetting,
            Set<Integer> menuIdSet,
            Map<Integer, Set<Integer>> optionByMenuId,
            Set<Long> accessibleSiteIds,
            Set<Integer> systemMenuIds,
            boolean platformRestricted,
            Set<String> platformCategoryKeys,
            Map<String, Set<Integer>> platformOptionByKey) {
        this.siteId = siteId;
        this.userId = userId;
        this.superAdmin = superAdmin;
        this.groupId = groupId;
        this.groupSetting = groupSetting;
        this.otherSetting = otherSetting;
        this.menuSetting = menuSetting;
        this.menuIdSet = immutableOrNull(menuIdSet);
        this.optionByMenuId = immutableOrNull(optionByMenuId);
        this.accessibleSiteIds = immutableOrNull(accessibleSiteIds);
        this.systemMenuIds = immutableOrNull(systemMenuIds);
        this.platformRestricted = platformRestricted;
        this.platformCategoryKeys = immutableOrNull(platformCategoryKeys);
        this.platformOptionByKey = immutableOrNull(platformOptionByKey);
    }

    /** 超级管理员：全放行（站点 + 菜单） */
    public boolean isAll() {
        return superAdmin;
    }

    /** 是否可见/可操作全部站点：超级管理员，或平台组（site_id=0）成员 */
    public boolean canAccessAllSites() {
        return superAdmin || accessibleSiteIds == null;
    }

    public Long siteId() {
        return siteId;
    }

    public Integer userId() {
        return userId;
    }

    public Integer groupId() {
        return groupId;
    }

    public String groupSetting() {
        return groupSetting;
    }

    public String otherSetting() {
        return otherSetting;
    }

    public String menuSetting() {
        return menuSetting;
    }

    /** 是否有某栏目（group_setting）权限；超管恒 true */
    public boolean hasMenuAccess(Integer menuId) {
        if (superAdmin || menuId == null) {
            return superAdmin;
        }
        return menuIdSet != null && menuIdSet.contains(menuId);
    }

    /** 是否在某栏目上可执行某个操作（other_setting）；超管恒 true */
    public boolean canOperate(Integer menuId, Integer optionId) {
        if (superAdmin || menuId == null || optionId == null) {
            return superAdmin;
        }
        if (menuIdSet == null || !menuIdSet.contains(menuId)) {
            return false;
        }
        Set<Integer> options = optionByMenuId == null ? null : optionByMenuId.get(menuId);
        return options != null && options.contains(optionId);
    }

    /** 是否可显示某系统菜单（menu_setting）；超管恒 true */
    public boolean hasSystemMenu(Integer menuId) {
        if (superAdmin || menuId == null) {
            return superAdmin;
        }
        return systemMenuIds != null && systemMenuIds.contains(menuId);
    }

    /** 数据中台权限是否受限（生效组为平台组且已配置 platform_setting）；false=全放行 */
    public boolean platformRestricted() {
        return platformRestricted;
    }

    /** 是否可对数据中台某类别执行某操作。 未受限（超管 / 站点组 / 未配置的平台组）恒放行；受限时要求类别键命中且操作命中。 */
    public boolean canPlatformOperate(String type, Integer categoryId, Integer optionId) {
        if (!platformRestricted) {
            return true;
        }
        if (type == null || categoryId == null || optionId == null) {
            return false;
        }
        String key = PlatformPermission.key(type, categoryId);
        if (platformCategoryKeys == null || !platformCategoryKeys.contains(key)) {
            return false;
        }
        Set<Integer> options = platformOptionByKey == null ? null : platformOptionByKey.get(key);
        return options != null && options.contains(optionId);
    }

    /** 可见站点 id 集合；null=ALL（超管） */
    public Set<Long> accessibleSiteIds() {
        return accessibleSiteIds;
    }

    public Set<Integer> menuIdSet() {
        return menuIdSet;
    }

    public Map<Integer, Set<Integer>> optionByMenuId() {
        return optionByMenuId;
    }

    public Set<Integer> systemMenuIds() {
        return systemMenuIds;
    }

    private static <T> Set<T> immutableOrNull(Set<T> value) {
        return value == null ? null : Collections.unmodifiableSet(value);
    }

    private static <K, V> Map<K, V> immutableOrNull(Map<K, V> value) {
        return value == null ? null : Collections.unmodifiableMap(value);
    }
}

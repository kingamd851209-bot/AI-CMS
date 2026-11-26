package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.api.admin.AdminAccountAccess.Scope;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.service.cmsPlatform.PlatformCategoryCatalogService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.utils.RequestOriginUtils;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 管理员组管理（复刻老CMS /admin/user_group/index）：树形组列表、添加子组、修改、删除、启用状态切换。 */
@RestController
@RequestMapping("/api/admin/v2/system/admin-groups")
public class AdminUserGroupV2ApiController {
    private final UserGroupService userGroupService;
    private final UserService userService;
    private final OneShotService oneShotService;
    private final AdminAuthenticationService authenticationService;
    private final LogService logService;
    private final SiteContextService siteContextService;
    private final PlatformCategoryCatalogService platformCategoryCatalogService;
    private final AdminAccountAccess accountAccess;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 栏目操作权限名称（与老CMS UserGroupServiceImpl.settingTitleArr 一致，下标+1 即 optionId） */
    private static final String[] SETTING_TITLES = {
        "添加", "修改", "删除", "发布/草稿", "查看", "排序", "复制", "首页置顶", "首页热门", "首页推荐", "频道置顶", "频道热门", "频道推荐",
        "栏目置顶", "栏目热门", "栏目推荐", "导航推荐", "右侧推荐", "左侧推荐", "导出数据", "导入数据", "审核状态", "审核"
    };

    public AdminUserGroupV2ApiController(
            UserGroupService userGroupService,
            UserService userService,
            OneShotService oneShotService,
            AdminAuthenticationService authenticationService,
            LogService logService,
            SiteContextService siteContextService,
            PlatformCategoryCatalogService platformCategoryCatalogService,
            AdminAccountAccess accountAccess) {
        this.userGroupService = userGroupService;
        this.userService = userService;
        this.oneShotService = oneShotService;
        this.authenticationService = authenticationService;
        this.logService = logService;
        this.siteContextService = siteContextService;
        this.platformCategoryCatalogService = platformCategoryCatalogService;
        this.accountAccess = accountAccess;
    }

    /** 管理员组树形列表（scope=platform 取平台组，否则取当前站点组，支持 keyword/enabled/parentId 过滤，附带父级组名称） */
    @GetMapping
    public AdminApiResponse<List<AdminGroupView>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer parentId,
            @RequestParam(required = false) String scope) {
        Scope access = accountAccess.requireScope(request);
        boolean platform = isPlatformScope(scope);
        Long siteId;
        if (platform) {
            requirePlatformAdmin(request);
            siteId = WebConst.PLATFORM_SITE_ID;
        } else {
            siteId = currentSiteId(request);
            if (siteId == null) {
                return AdminApiResponse.success(List.of());
            }
        }
        List<UserGroupEntity> groups =
                userGroupService.getUserGroupListBySite(siteId, 0, request).stream()
                        .filter(access::canManageGroup).toList();
        Map<Integer, String> parentTitleById =
                groups.stream()
                        .collect(
                                Collectors.toMap(
                                        UserGroupEntity::getId,
                                        group -> cleanTitle(group.getTitle()),
                                        (a, b) -> a));
        Map<Integer, Long> userCountByGroupId =
                userService.getUserList(request).stream()
                        .filter(user -> user.getGroupId() != null)
                        .collect(
                                Collectors.groupingBy(
                                        UserEntity::getGroupId, Collectors.counting()));
        return AdminApiResponse.success(
                groups.stream()
                        .filter(group -> matchesFilters(group, keyword, enabled, parentId))
                        .map(
                                group ->
                                        AdminGroupView.from(
                                                group,
                                                userCountByGroupId
                                                        .getOrDefault(group.getId(), 0L)
                                                        .intValue(),
                                                parentTitleById.getOrDefault(
                                                        group.getParentId(), "")))
                        .toList());
    }

    private boolean matchesFilters(
            UserGroupEntity group, String keyword, Boolean enabled, Integer parentId) {
        if (StringUtils.isNotBlank(keyword)
                && !StringUtils.containsIgnoreCase(cleanTitle(group.getTitle()), keyword.trim())) {
            return false;
        }
        if (enabled != null && !enabled.equals(group.getEnabled())) {
            return false;
        }
        return parentId == null || parentId.equals(group.getParentId());
    }

    /** 父级管理员组下拉（标题带缩进；scope=platform 取平台组，否则取当前站点组） */
    @GetMapping("/dropdown")
    public AdminApiResponse<List<GroupDropdownItem>> dropdown(
            HttpServletRequest request, @RequestParam(required = false) String scope) {
        Scope access = accountAccess.requireScope(request);
        Long siteId;
        if (isPlatformScope(scope)) {
            requirePlatformAdmin(request);
            siteId = WebConst.PLATFORM_SITE_ID;
        } else {
            siteId = currentSiteId(request);
            if (siteId == null) {
                return AdminApiResponse.success(List.of());
            }
        }
        List<UserGroupEntity> list =
                userGroupService.getDropdownListBySite(
                        siteId, 0, request);
        return AdminApiResponse.success(list.stream().filter(access::canUseParent).map(GroupDropdownItem::from).toList());
    }

    /** 栏目权限树工作区（栏目树含操作权限 + 菜单权限 + 全局权限 + 数据中台类别权限目录） */
    @GetMapping("/permissions")
    public AdminApiResponse<PermissionWorkspaceResponse> permissions(HttpServletRequest request) {
        requireLoginUser(request);
        List<OneshotEntity> siteMenus =
                oneShotService.getAllContentMenu(new OneshotDto(0), request);
        return AdminApiResponse.success(
                new PermissionWorkspaceResponse(
                        sitePermissionTree(siteMenus),
                        List.of(),
                        List.of(),
                        platformCategoryCatalogService.catalog()));
    }

    /** 管理员组详情 */
    @GetMapping("/{id}")
    public AdminApiResponse<AdminGroupView> detail(
            HttpServletRequest request, @PathVariable Integer id) {
        Scope access = accountAccess.requireScope(request);
        UserGroupEntity target = requireGroup(id);
        requireManageable(access, currentSiteId(request), target);
        return AdminApiResponse.success(AdminGroupView.from(target, 0));
    }

    /** 新增/修改管理员组 */
    @PostMapping
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AdminApiResponse<AdminGroupView> save(
            HttpServletRequest request, @RequestBody UserGroupEntity body) {
        Scope access = accountAccess.requireScope(request);
        Long siteId = currentSiteId(request);
        if (StringUtils.isBlank(body.getTitle())) {
            throw new IllegalArgumentException("管理员组名称不能为空");
        }
        // 去除列表展示叠加的缩进前缀，避免把 "-" 写回数据库导致每次编辑累加
        body.setTitle(cleanTitle(body.getTitle()));
        if (body.getId() == null) {
            body.setId(0);
        }
        if (StringUtils.isBlank(body.getGroupSetting())) {
            body.setGroupSetting("0");
        }
        if (StringUtils.isBlank(body.getOtherSetting())) {
            body.setOtherSetting("");
        }
        if (body.getEnabled() == null) {
            body.setEnabled(Boolean.TRUE);
        }
        UserGroupEntity before = body.getId() == 0 ? null : requireGroup(body.getId());
        if (before == null) {
            // 平台标签页新增时前端传 siteId=0，其余归当前站点
            if (WebConst.PLATFORM_SITE_ID.equals(body.getSiteId())) {
                siteId = WebConst.PLATFORM_SITE_ID;
            }
        } else {
            requireManageable(access, siteId, before);
            siteId = before.getSiteId();
        }
        access.prepareGroupSave(body, before, siteId);
        requireSuccess(userGroupService.addOrUpdateUserGroup(body));
        writeAuditLog(
                request,
                before == null ? "新增管理员组" : "保存管理员组",
                "{\"groupId\":"
                        + body.getId()
                        + ",\"title\":\""
                        + safeJson(body.getTitle())
                        + "\"}",
                "success");
        return AdminApiResponse.success(AdminGroupView.from(requireGroup(body.getId()), 0));
    }

    /** 切换管理员组启用状态 */
    @PostMapping("/{id}/switch")
    public AdminApiResponse<AdminGroupView> switchStatus(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody SwitchRequest body) {
        Scope access = accountAccess.requireScope(request);
        if (id == 1 || id == 2) {
            throw new IllegalArgumentException("顶级管理员组不可切换状态");
        }
        UserGroupEntity target = requireGroup(id);
        if (isPlatformGroup(target)) {
            throw new IllegalArgumentException("平台组不允许切换状态");
        }
        requireSameSite(currentSiteId(request), target);
        access.requireGroupAccess(target);
        String fieldName =
                StringUtils.defaultIfBlank(body == null ? null : body.fieldName(), "Enabled");
        requireSuccess(
                userGroupService.updateSwitch(new UserGroupSwitchDto(id, fieldName), request));
        writeAuditLog(
                request,
                "启停管理员组",
                "{\"groupId\":" + id + ",\"fieldName\":\"" + safeJson(fieldName) + "\"}",
                "success");
        return AdminApiResponse.success(AdminGroupView.from(requireGroup(id), 0));
    }

    /** 删除管理员组（校验顶级组、子组、组内用户、站点归属） */
    @DeleteMapping("/{id}")
    public AdminApiResponse<Void> delete(HttpServletRequest request, @PathVariable Integer id) {
        Scope access = accountAccess.requireScope(request);
        if (id == 1 || id == 2) {
            throw new IllegalArgumentException("顶级管理员组不能删除");
        }
        UserGroupEntity target = requireGroup(id);
        if (isPlatformGroup(target)) {
            throw new IllegalArgumentException("平台组不允许删除");
        }
        requireSameSite(currentSiteId(request), target);
        access.requireGroupAccess(target);
        if (!userGroupService.getUserGroupListByParentId(id).isEmpty()) {
            throw new IllegalArgumentException(ErrorCodeEnum.HAS_CHILD_USER_GROUP_DATA.getMsg());
        }
        if (!userService.getUserListByGroupId(id).isEmpty()) {
            throw new IllegalArgumentException(ErrorCodeEnum.HAS_USER_IN_GROUP.getMsg());
        }
        if (!userGroupService.deleteUserGroup(id)) {
            throw new IllegalStateException("管理员组删除失败");
        }
        writeAuditLog(request, "删除管理员组", "{\"groupId\":" + id + "}", "success");
        return AdminApiResponse.success();
    }

    public record AdminGroupView(
            Integer id,
            String title,
            String rawTitle,
            Integer parentId,
            String parentTitle,
            String note,
            Boolean enabled,
            String groupSetting,
            String otherSetting,
            String menuSetting,
            String platformSetting,
            String platformOtherSetting,
            String createTime,
            Integer userCount,
            Long siteId) {
        static AdminGroupView from(UserGroupEntity item, Integer userCount) {
            return from(item, userCount, "");
        }

        static AdminGroupView from(UserGroupEntity item, Integer userCount, String parentTitle) {
            String displayTitle = cleanTitle(item.getTitle());
            return new AdminGroupView(
                    item.getId(),
                    displayTitle,
                    displayTitle,
                    item.getParentId(),
                    StringUtils.defaultString(parentTitle),
                    item.getNote(),
                    item.getEnabled(),
                    item.getGroupSetting(),
                    item.getOtherSetting(),
                    item.getMenuSetting(),
                    item.getPlatformSetting(),
                    item.getPlatformOtherSetting(),
                    formatDate(item.getCreateTime()),
                    userCount == null ? 0 : userCount,
                    item.getSiteId());
        }
    }

    /** 去除列表展示时叠加的树形缩进前缀（"-"）与服务端拼接的 HTML span 标签，得到可编辑/可保存的真实组名 */
    static String cleanTitle(String title) {
        String t =
                StringUtils.defaultString(title)
                        // 服务层 recursive() 为老CMS树形展示拼接的 span 装饰（缩进块/折叠符/标题样式）
                        .replaceAll("</?span[^>]*>", " ")
                        .replaceAll("<[^>]+>", " ");
        t = StringUtils.defaultString(t).trim();
        while (t.startsWith("-")) {
            t = t.substring(1).trim();
        }
        return StringUtils.defaultIfBlank(t, "未命名");
    }

    public record GroupDropdownItem(Integer id, String title) {
        static GroupDropdownItem from(UserGroupEntity item) {
            return new GroupDropdownItem(item.getId(), cleanTitle(item.getTitle()));
        }
    }

    public record PermissionWorkspaceResponse(
            List<AdminPermissionNode> sitePermissions,
            List<Object> menuPermissions,
            List<Object> globalPermissions,
            List<PlatformCategoryCatalogService.PlatformModuleCategories> platformModules) {}

    public record OperationOption(Integer id, String title) {}

    /** 栏目权限树节点（含该栏目的操作权限选项，如添加/修改/删除/发布/排序/审核等） */
    public record AdminPermissionNode(
            Integer id,
            Integer parentId,
            String key,
            String label,
            String scope,
            List<AdminPermissionNode> children,
            List<OperationOption> operationOptions) {
        AdminPermissionNode withChildren(
                Map<Integer, List<AdminPermissionNode>> childrenByParentId) {
            return new AdminPermissionNode(
                    id,
                    parentId,
                    key,
                    label,
                    scope,
                    childrenByParentId.getOrDefault(id, List.of()).stream()
                            .map(child -> child.withChildren(childrenByParentId))
                            .toList(),
                    operationOptions);
        }
    }

    public record SwitchRequest(String fieldName) {}

    private List<AdminPermissionNode> sitePermissionTree(List<OneshotEntity> siteMenus) {
        List<AdminPermissionNode> flat =
                siteMenus.stream()
                        .filter(menu -> menu.getId() != null)
                        .map(this::sitePermissionNode)
                        .toList();
        return permissionTree(flat);
    }

    private AdminPermissionNode sitePermissionNode(OneshotEntity menu) {
        return new AdminPermissionNode(
                menu.getId(),
                menu.getParentId(),
                String.valueOf(menu.getId()),
                cleanMenuLabel(menu.getClassName(), menu.getId()),
                "SITE",
                List.of(),
                operationOptions(menu));
    }

    /** 去除服务层 recursive() 为老CMS树形展示拼接的 img 缩进占位与 span/a 包装，得到纯文本栏目名 */
    static String cleanMenuLabel(String className, Integer menuId) {
        String label =
                StringUtils.defaultString(className)
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        return StringUtils.defaultIfBlank(label, "栏目 #" + menuId);
    }

    private List<AdminPermissionNode> permissionTree(List<AdminPermissionNode> flat) {
        Map<Integer, List<AdminPermissionNode>> childrenByParentId = new LinkedHashMap<>();
        Set<Integer> ids = new HashSet<>();
        for (AdminPermissionNode node : flat) {
            ids.add(node.id());
            childrenByParentId
                    .computeIfAbsent(
                            node.parentId() == null ? 0 : node.parentId(),
                            ignored -> new ArrayList<>())
                    .add(node);
        }
        List<AdminPermissionNode> roots =
                flat.stream()
                        .filter(
                                node ->
                                        node.parentId() == null
                                                || node.parentId() <= 0
                                                || !ids.contains(node.parentId()))
                        .toList();
        return roots.stream().map(node -> node.withChildren(childrenByParentId)).toList();
    }

    /** 解析栏目可用的操作权限选项（仅 pageType=1 且配置了 setting 的栏目，optionId 1~23 对应 SETTING_TITLES） */
    private List<OperationOption> operationOptions(OneshotEntity menu) {
        if (menu.getPageType() == null) {
            return List.of();
        }
        if (StringUtils.isBlank(menu.getSetting())) {
            // 未配置操作权限的栏目：默认提供全部标准操作权限，保证每个栏目后都有复选框
            List<OperationOption> all = new ArrayList<>();
            for (int i = 0; i < SETTING_TITLES.length; i++) {
                all.add(new OperationOption(i + 1, SETTING_TITLES[i]));
            }
            return all;
        }
        List<OperationOption> options = new ArrayList<>();
        for (String raw : menu.getSetting().split(",")) {
            int id;
            try {
                id = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (id >= 1 && id <= SETTING_TITLES.length) {
                options.add(new OperationOption(id, SETTING_TITLES[id - 1]));
            }
        }
        return options;
    }

    private UserEntity requireLoginUser(HttpServletRequest request) {
        return authenticationService.requireLoginSessionUser(request);
    }

    private UserGroupEntity requireGroup(Integer id) {
        UserGroupEntity group = userGroupService.getUserGroupById(id);
        if (group == null) {
            throw new IllegalArgumentException("管理员组不存在");
        }
        return group;
    }

    private Long currentSiteId(HttpServletRequest request) {
        return siteContextService.current(request).getSiteId();
    }

    private void requireSameSite(Long siteId, UserGroupEntity group) {
        if (siteId == null || group.getSiteId() == null || !siteId.equals(group.getSiteId())) {
            throw new IllegalArgumentException("无权操作其他站点的管理员组");
        }
    }

    private void requireManageable(Scope access, Long siteId, UserGroupEntity group) {
        access.requireGroupAccess(group);
        if (!isPlatformGroup(group)) {
            requireSameSite(siteId, group);
        }
    }

    private boolean isPlatformGroup(UserGroupEntity group) {
        return WebConst.PLATFORM_SITE_ID.equals(group.getSiteId());
    }

    private boolean isPlatformScope(String scope) {
        return "platform".equalsIgnoreCase(StringUtils.defaultString(scope).trim());
    }

    /** 平台组（跨站点共享）仅平台管理可见可改 */
    private void requirePlatformAdmin(HttpServletRequest request) {
        if (!TaleUtils.isFullAdmin(request)) {
            throw new IllegalArgumentException("无权访问平台组");
        }
    }

    private void requireSuccess(ResultVO<?> result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(
                    result == null || result.getMsg() == null ? "操作失败" : result.getMsg());
        }
    }

    private void writeAuditLog(
            HttpServletRequest request, String action, String requestData, String responseData) {
        UserEntity loginUser = requireLoginUser(request);
        LogEntity log =
                new LogEntity()
                        .setAction(action)
                        .setRequestData(requestData)
                        .setResponseData(responseData)
                        .setUserId(loginUser.getId())
                        .setUserName(loginUser.getUsername())
                        .setIp(RequestOriginUtils.clientIp(request))
                        .setCreateTime(LocalDateTime.now())
                        .setUrl(request.getRequestURI())
                        .setHttpMethod(request.getMethod())
                        .setClassMethod(getClass().getName())
                        .setSpendTime(0L);
        logService.addLog(log);
    }

    private String safeJson(String value) {
        return StringUtils.defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatDate(Date value) {
        if (value == null) {
            return "";
        }
        return DATE_TIME_FORMATTER.format(
                LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault()));
    }
}

package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.api.admin.AdminAccountAccess.Scope;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.entity.UserSiteGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.security.password.Sm3PasswordHashService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.utils.RequestOriginUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 管理员管理（复刻老CMS /admin/user/index）：账号列表、添加、修改、删除。 */
@RestController
@RequestMapping("/api/admin/v2/system/admin-users")
public class AdminUsersV2ApiController {
    private final UserService userService;
    private final UserGroupService userGroupService;
    private final UserSiteGroupService userSiteGroupService;
    private final Sm3PasswordHashService passwordHashService;
    private final AdminAuthenticationService authenticationService;
    private final LogService logService;
    private final SiteContextService siteContextService;
    private final AdminAccountAccess accountAccess;

    /** 邮箱格式校验（与 @Email 保持一致的最小可用规则） */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 密码强度提示：与登录侧 PasswordStrengthCheck 使用同一正则，避免存下永远无法登录的密码 */
    private static final String PASSWORD_STRENGTH_MESSAGE = "密码需为 8-24 位，且必须包含大写字母、小写字母、数字和特殊字符";

    /** 平台组伪站点展示名（site_id=0） */
    private static final String PLATFORM_SITE_NAME = "平台（全站）";

    public AdminUsersV2ApiController(
            UserService userService,
            UserGroupService userGroupService,
            UserSiteGroupService userSiteGroupService,
            Sm3PasswordHashService passwordHashService,
            AdminAuthenticationService authenticationService,
            LogService logService,
            SiteContextService siteContextService,
            AdminAccountAccess accountAccess) {
        this.userService = userService;
        this.userGroupService = userGroupService;
        this.userSiteGroupService = userSiteGroupService;
        this.passwordHashService = passwordHashService;
        this.authenticationService = authenticationService;
        this.logService = logService;
        this.siteContextService = siteContextService;
        this.accountAccess = accountAccess;
    }

    /** 管理员账号列表 */
    @GetMapping
    public AdminApiResponse<List<SystemAdminV2ApiController.UserView>> list(
            HttpServletRequest request) {
        Scope scope = accountAccess.requireScope(request);
        List<UserEntity> users = userService.listAllAdminUsers(request);
        Map<Long, String> siteNameById = siteNameById(request);
        Map<Integer, String> groupNameById = groupNameById(request);
        return AdminApiResponse.success(
                users.stream().filter(scope::canRead)
                        .map(user -> toUserView(user, scope, siteNameById, groupNameById)).toList());
    }

    /** 所属管理员组下拉（按当前站点） */
    @GetMapping("/groups")
    public AdminApiResponse<List<GroupOption>> groups(HttpServletRequest request) {
        Scope scope = accountAccess.requireScope(request);
        Long siteId = siteContextService.current(request).getSiteId();
        if (siteId == null) {
            return AdminApiResponse.success(List.of());
        }
        List<UserGroupEntity> list =
                userGroupService.getDropdownListBySite(
                        siteId, 0, request);
        return AdminApiResponse.success(list.stream().filter(scope::canGrant).map(GroupOption::from).toList());
    }

    /** 所有站点及每站点的管理员组（编辑用户-站点-组用）；末尾追加「平台（全站）」条目（site_id=0） */
    @GetMapping("/site-groups")
    public AdminApiResponse<List<SiteGroupOption>> siteGroups(HttpServletRequest request) {
        Scope scope = accountAccess.requireScope(request);
        List<SiteGroupOption> result =
                new ArrayList<>(
                        siteContextService.listAvailableSites(request).stream()
                                .map(
                                        site ->
                                                new SiteGroupOption(
                                                        site.getId(),
                                                        site.getProjectName(),
                                                        userGroupService
                                                                .getDropdownListBySite(
                                                                        site.getId(), 0, request)
                                                                .stream()
                                                                .filter(scope::canGrant)
                                                                .map(GroupOption::from)
                                                                .toList()))
                                .filter(site -> !site.groups().isEmpty())
                                .toList());
        if (scope.platform()) result.add(
                new SiteGroupOption(
                        WebConst.PLATFORM_SITE_ID,
                        PLATFORM_SITE_NAME,
                        userGroupService
                                .getDropdownListBySite(WebConst.PLATFORM_SITE_ID, 0, request)
                                .stream()
                                .filter(scope::canGrant)
                                .map(GroupOption::from)
                                .toList()));
        return AdminApiResponse.success(result);
    }

    /** 管理员账号详情 */
    @GetMapping("/{id}")
    public AdminApiResponse<SystemAdminV2ApiController.UserView> detail(
            HttpServletRequest request, @PathVariable Integer id) {
        Scope scope = accountAccess.requireScope(request);
        UserEntity target = requireUser(id);
        scope.requireRead(target);
        return AdminApiResponse.success(
                toUserView(target, scope, siteNameById(request), groupNameById(request)));
    }

    /** 新增/修改管理员账号 */
    @PostMapping
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<SystemAdminV2ApiController.UserView> save(
            HttpServletRequest request, @RequestBody UserRequest body) {
        Scope scope = accountAccess.requireScope(request);
        if (body.id() != null && body.id() < 0) {
            throw new IllegalArgumentException("用户编号无效");
        }
        UserEntity existing = body.id() != null && body.id() > 0 ? lockUser(body.id()) : null;
        List<UserSiteGroupEntity> protectedRelations = existing == null ? List.of()
                : scope.protectedRelations(existing);
        Set<Long> manageable = siteContextService.listAvailableSites(request).stream()
                .map(SiteProjectEntity::getId).collect(Collectors.toSet());
        List<UserSiteGroupService.SiteGroupRef> siteGroupRefs =
                normalizeSiteGroups(
                        scope, manageable, body.siteGroups() == null ? List.of() : body.siteGroups());
        if (existing != null) {
            List<UserSiteGroupService.SiteGroupRef> preserved = protectedRelations.stream()
                    .map(ref -> new UserSiteGroupService.SiteGroupRef(ref.getSiteId().intValue(), ref.getGroupId()))
                    .toList();
            if (!preserved.isEmpty() && siteGroupRefs.stream().anyMatch(ref -> ref.siteId() == 0)) {
                throw new IllegalArgumentException("存在不可管理站点的授权，不能转换为平台管理员");
            }
            siteGroupRefs = new ArrayList<>(siteGroupRefs);
            siteGroupRefs.addAll(preserved);
        }
        Integer previousGroup = existing == null ? null : existing.getGroupId();
        Integer defaultGroupId = siteGroupRefs.stream()
                .map(UserSiteGroupService.SiteGroupRef::groupId)
                .filter(id -> Objects.equals(id, previousGroup)).findFirst()
                .orElse(siteGroupRefs.stream()
                        .map(UserSiteGroupService.SiteGroupRef::groupId).min(Integer::compareTo).orElseThrow());
        UserEntity entity =
                new UserEntity()
                        .setId(body.id() == null ? 0 : body.id())
                        .setUsername(StringUtils.trimToEmpty(body.username()))
                        .setScreenName(StringUtils.trimToEmpty(body.screenName()))
                        .setEmail(StringUtils.trimToEmpty(body.email()))
                        .setGroupId(defaultGroupId);
        if (StringUtils.isBlank(entity.getScreenName())) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (StringUtils.isBlank(entity.getEmail())) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        if (!EMAIL_PATTERN.matcher(entity.getEmail()).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (!Objects.equals(body.password(), body.repass())) {
            throw new IllegalArgumentException(ErrorCodeEnum.TWICE_PASSWORD_NOT_THE_SAME.getMsg());
        }
        boolean resettingPassword = StringUtils.isNotBlank(body.password());
        boolean accountWrite = existing == null || scope.canWriteAccount(existing);
        if (!accountWrite) {
            if (resettingPassword
                    || !Objects.equals(entity.getScreenName(), StringUtils.trimToEmpty(existing.getScreenName()))
                    || !Objects.equals(entity.getEmail(), StringUtils.trimToEmpty(existing.getEmail()))) {
                scope.requireAccountWrite(existing);
            }
            entity.setScreenName(null).setEmail(null);
        }
        if (resettingPassword) {
            requireStrongPassword(body.password());
        }
        if (entity.getId() == 0) {
            AdminAccountAccess.requireCreatableUsername(entity.getUsername());
            if (StringUtils.isBlank(entity.getUsername())) {
                throw new IllegalArgumentException("账号不能为空");
            }
            if (!resettingPassword) {
                throw new IllegalArgumentException("新建账号必须设置密码");
            }
            if (userService.hasUsername(entity.getUsername()) > 0) {
                throw new IllegalArgumentException(ErrorCodeEnum.USER_ALREADY_EXISTS.getMsg());
            }
            if (userService.hasEmail(entity.getEmail()) > 0) {
                throw new IllegalArgumentException(ErrorCodeEnum.EMAIL_ALREADY_EXISTS.getMsg());
            }
            entity.setPassword(passwordHashService.hash(body.password()));
            entity.setCreated((int) (System.currentTimeMillis() / 1000));
            if (!userService.addUser(entity)) {
                throw new IllegalStateException("用户保存失败");
            }
            writeAuditLog(
                    request,
                    "新增管理员账号",
                    "{\"userId\":"
                            + entity.getId()
                            + ",\"username\":\""
                            + safeJson(entity.getUsername())
                            + "\"}",
                    "success");
        } else {
            if (accountWrite && emailTakenByOther(entity.getId(), entity.getEmail())) {
                throw new IllegalArgumentException(ErrorCodeEnum.EMAIL_ALREADY_EXISTS.getMsg());
            }
            if (resettingPassword) {
                entity.setPassword(passwordHashService.hash(body.password()));
            }
            if (userService.updateUserInfo(entity) <= 0) {
                throw new IllegalStateException("用户保存失败");
            }
            writeAuditLog(
                    request,
                    resettingPassword ? "保存管理员账号并重置密码" : "保存管理员账号",
                    "{\"userId\":"
                            + entity.getId()
                            + ",\"username\":\""
                            + safeJson(entity.getUsername())
                            + "\"}",
                    "success");
        }
        userSiteGroupService.saveUserSiteGroups(entity.getId(), siteGroupRefs);
        return AdminApiResponse.success(
                toUserView(
                        requireUser(entity.getId()),
                        scope,
                        siteNameById(request),
                        groupNameById(request)));
    }

    /** 启用/停用管理员账号（超级管理员与当前登录账号不可停用） */
    @PostMapping("/{id}/status")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<SystemAdminV2ApiController.UserView> switchStatus(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody StatusRequest body) {
        Scope scope = accountAccess.requireScope(request);
        UserEntity target = lockUser(id);
        scope.requireAccountWrite(target);
        boolean enabled = body == null || !Boolean.FALSE.equals(body.enabled());
        if (userService.updateUserInfo(new UserEntity().setId(id).setActivated(enabled ? 0 : 1))
                <= 0) {
            throw new IllegalStateException("账号状态更新失败");
        }
        writeAuditLog(
                request,
                enabled ? "启用管理员账号" : "停用管理员账号",
                "{\"userId\":" + id + ",\"username\":\"" + safeJson(target.getUsername()) + "\"}",
                "success");
        return AdminApiResponse.success(
                toUserView(requireUser(id), scope, siteNameById(request), groupNameById(request)));
    }

    /** 删除管理员账号（超级管理员不可删除） */
    @DeleteMapping("/{id}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<Void> delete(HttpServletRequest request, @PathVariable Integer id) {
        Scope scope = accountAccess.requireScope(request);
        scope.requireAccountWrite(lockUser(id));
        if (!userService.deleteUser(id)) {
            throw new IllegalStateException("用户删除失败");
        }
        userSiteGroupService.deleteByUserId(id);
        writeAuditLog(request, "删除管理员账号", "{\"userId\":" + id + "}", "success");
        return AdminApiResponse.success();
    }

    public record UserRequest(
            Integer id,
            String username,
            String password,
            String repass,
            String email,
            String screenName,
            Integer groupId,
            List<UserSiteGroupService.SiteGroupRef> siteGroups) {}

    /** enabled=true 表示启用（activated=0），false 表示停用（activated=1） */
    public record StatusRequest(Boolean enabled) {}

    public record SiteGroupOption(Long siteId, String siteName, List<GroupOption> groups) {}

    public record GroupOption(Integer id, String title) {
        static GroupOption from(UserGroupEntity item) {
            return new GroupOption(item.getId(), item.getTitle());
        }
    }

    private UserEntity requireLoginUser(HttpServletRequest request) {
        return authenticationService.requireLoginSessionUser(request);
    }

    private UserEntity requireUser(Integer id) {
        UserEntity user = userService.getUserInfoById(id, false);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (StringUtils.isBlank(user.getGroupName()) && user.getGroupId() != null) {
            user.setGroupName(userGroupService.getUserGroupNameByGroupId(user.getGroupId()));
        }
        return user;
    }

    private UserEntity lockUser(Integer id) {
        UserEntity user = userService.lockUserById(id);
        if (user == null) {
            throw AdminSecurityException.notFound("ADMIN_ACCOUNT_NOT_FOUND", "管理员账号不存在");
        }
        return user;
    }

    private SystemAdminV2ApiController.UserView toUserView(
            UserEntity user, Scope scope, Map<Long, String> siteNameById, Map<Integer, String> groupNameById) {
        return SystemAdminV2ApiController.UserView.from(
                user, null, siteGroupsFor(user.getId(), scope, siteNameById, groupNameById));
    }

    /** 一次性构建 siteId -> 站点名 映射，避免在每个用户上重复执行站点权限评估 */
    private Map<Long, String> siteNameById(HttpServletRequest request) {
        return siteContextService.listAvailableSites(request).stream()
                .collect(
                        Collectors.toMap(
                                SiteProjectEntity::getId,
                                SiteProjectEntity::getProjectName,
                                (a, b) -> a));
    }

    /** 一次性构建 groupId -> 组名 映射，避免对每个用户/组单独查询数据库 */
    private Map<Integer, String> groupNameById(HttpServletRequest request) {
        return userGroupService.getChildUserGroupList(0, request).stream()
                .collect(
                        Collectors.toMap(
                                UserGroupEntity::getId, UserGroupEntity::getTitle, (a, b) -> a));
    }

    private List<SystemAdminV2ApiController.SiteGroupOption> siteGroupsFor(
            Integer userId, Scope scope, Map<Long, String> siteNameById, Map<Integer, String> groupNameById) {
        return userSiteGroupService.listByUserId(userId).stream()
                .filter(scope::canManage)
                .map(
                        entity ->
                                new SystemAdminV2ApiController.SiteGroupOption(
                                        entity.getSiteId(),
                                        siteNameOf(entity.getSiteId(), siteNameById),
                                        entity.getGroupId(),
                                        StringUtils.defaultString(
                                                groupNameById.get(entity.getGroupId()))))
                .toList();
    }

    /** 平台组伪站点（site_id=0）没有对应真实站点名，统一展示为「平台（全站）」 */
    private static String siteNameOf(Long siteId, Map<Long, String> siteNameById) {
        if (siteId == null) {
            return null;
        }
        if (WebConst.PLATFORM_SITE_ID.equals(siteId)) {
            return PLATFORM_SITE_NAME;
        }
        return siteNameById.get(siteId);
    }

    /** 校验每条站点组关系及平台组互斥规则。 */
    private List<UserSiteGroupService.SiteGroupRef> normalizeSiteGroups(
            Scope scope, Set<Long> accessibleSiteIds, List<UserSiteGroupService.SiteGroupRef> refs) {
        Set<UserSiteGroupService.SiteGroupRef> seenRelations = new HashSet<>();
        List<UserSiteGroupService.SiteGroupRef> normalized = new ArrayList<>();
        for (UserSiteGroupService.SiteGroupRef ref : refs) {
            if (ref == null || ref.siteId() == null) {
                throw new IllegalArgumentException("请选择站点");
            }
            Long siteId = ref.siteId().longValue();
            boolean platform = WebConst.PLATFORM_SITE_ID.equals(siteId);
            if (!seenRelations.add(ref)) {
                throw new IllegalArgumentException("站点管理员组关系重复");
            }
            if (!platform && !accessibleSiteIds.contains(siteId)) {
                throw new IllegalArgumentException("站点不存在或您无权管理该站点");
            }
            if (ref.groupId() == null) {
                throw new IllegalArgumentException(platform ? "请选择平台组" : "请为所选站点指定管理员组");
            }
            UserGroupEntity group = userGroupService.getUserGroupById(ref.groupId());
            if (group == null) {
                throw new IllegalArgumentException("管理员组不存在");
            }
            if (!siteId.equals(group.getSiteId())) {
                throw new IllegalArgumentException(platform ? "所选不是平台组" : "管理员组不属于所选站点");
            }
            if (!scope.canGrant(group)) {
                throw AdminSecurityException.forbidden("ADMIN_ACCOUNT_ACCESS_DENIED", "无权管理该管理员组");
            }
            normalized.add(ref);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("请选择站点管理员组或平台组");
        }
        boolean hasPlatform =
                normalized.stream()
                        .anyMatch(
                                ref -> WebConst.PLATFORM_SITE_ID.equals(ref.siteId().longValue()));
        if (hasPlatform && normalized.size() > 1) {
            throw new IllegalArgumentException("平台组与站点管理员组不能同时选择");
        }
        return normalized;
    }

    /** 与登录侧 PasswordStrengthCheck 保持同一强度规则，避免存下永远无法登录的密码 */
    private void requireStrongPassword(String password) {
        if (!StringUtils.defaultString(password).matches(WebConst.PASSWORD_VALIDATE_PATTERN)) {
            throw new IllegalArgumentException(PASSWORD_STRENGTH_MESSAGE);
        }
    }

    /** 邮箱是否已被其他账号占用（编辑时排除自身） */
    private boolean emailTakenByOther(Integer userId, String email) {
        if (userService.hasEmail(email) <= 0) {
            return false;
        }
        UserEntity current = userService.getUserInfoById(userId, false);
        return current == null
                || !email.equalsIgnoreCase(StringUtils.trimToEmpty(current.getEmail()));
    }

    private String message(RuntimeException ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, message(ex, "管理员请求参数无效"), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdminApiResponse<>(409, message(ex, "管理员操作失败"), null));
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
}

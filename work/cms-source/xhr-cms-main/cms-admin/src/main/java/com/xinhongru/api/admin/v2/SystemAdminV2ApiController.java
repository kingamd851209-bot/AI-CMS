package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.api.admin.AdminAccountAccess.Scope;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.xinhongru.common.page.PageResult;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.LogOperationEntity;
import com.xinhongru.entity.OptionsEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.logVo.LogHtmlVo;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.security.password.Sm3PasswordHashService;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.security.upload.SafeSvg;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.logOperation.LogOperationService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.service.option.OptionService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.utils.RequestOriginUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/admin/v2/system")
public class SystemAdminV2ApiController {
    @Autowired private AdminAccountAccess accountAccess;
    @Autowired private UserSiteGroupService userSiteGroupService;
    private final UserService userService;
    private final UserGroupService userGroupService;
    private final LogService logService;
    private final LogOperationService logOperationService;
    private final OptionService optionService;
    private final OneShotService oneShotService;
    private final Sm3PasswordHashService passwordHashService;
    private final PublicStorageRoots publicStorage;
    private final ResourceReferenceResolver resourceResolver;
    private final AdminAuthenticationService authenticationService;
    private SiteContextService siteContextService;
    private static final long MAX_IMAGE_BACKGROUND_SIZE = 8L * 1024 * 1024;
    private static final long MAX_VIDEO_BACKGROUND_SIZE = 80L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_LOGO_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "svg");
    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = Set.of("mp4", "webm");
    private static final Set<String> ALLOWED_BACKGROUND_MEDIA_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "mp4", "webm");
    private static final Map<String, Set<String>> ALLOWED_VIDEO_MIME_TYPES =
            Map.of(
                    "mp4", Set.of("video/mp4"),
                    "webm", Set.of("video/webm"));
    private static final Set<String> ALLOWED_BRANDING_URL_PREFIXES =
            Set.of("/assets/global/", "/media/", "/static/");
    private static final Set<String> BRANDING_LOGO_OPTION_NAMES =
            Set.of("systemLogoUrl", "system.logoUrl");
    private static final Set<String> BRANDING_BACKGROUND_MEDIA_OPTION_NAMES =
            Set.of("loginBackgroundUrl", "login.backgroundUrl", "loginBackgroundPosterUrl");
    private static final Set<String> BRANDING_MEDIA_OPTION_NAMES =
            Stream.concat(
                            BRANDING_LOGO_OPTION_NAMES.stream(),
                            BRANDING_BACKGROUND_MEDIA_OPTION_NAMES.stream())
                    .collect(Collectors.toUnmodifiableSet());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Map<String, String> BRANDING_CANONICAL_OPTION_NAMES =
            Map.of(
                    "systemLogoUrl", "system.logoUrl",
                    "sidebarBackgroundStyle", "system.sidebarBackgroundStyle",
                    "systemName", "system.name",
                    "loginTitle", "login.title",
                    "loginSubtitle", "login.description",
                    "loginBackgroundType", "login.backgroundType",
                    "loginBackgroundUrl", "login.backgroundUrl");
    private static final Map<String, String> BRANDING_OPTION_FIELD_NAMES =
            Map.ofEntries(
                    Map.entry("system.logoUrl", "systemLogoUrl"),
                    Map.entry("systemLogoUrl", "systemLogoUrl"),
                    Map.entry("system.sidebarBackgroundStyle", "sidebarBackgroundStyle"),
                    Map.entry("sidebarBackgroundStyle", "sidebarBackgroundStyle"),
                    Map.entry("system.name", "systemName"),
                    Map.entry("systemName", "systemName"),
                    Map.entry("login.title", "loginTitle"),
                    Map.entry("loginTitle", "loginTitle"),
                    Map.entry("login.description", "loginSubtitle"),
                    Map.entry("loginSubtitle", "loginSubtitle"),
                    Map.entry("login.backgroundType", "loginBackgroundType"),
                    Map.entry("loginBackgroundType", "loginBackgroundType"),
                    Map.entry("login.backgroundUrl", "loginBackgroundUrl"),
                    Map.entry("loginBackgroundUrl", "loginBackgroundUrl"),
                    Map.entry("loginBackgroundPosterUrl", "loginBackgroundPosterUrl"));
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<SettingsGroupDefinition> SETTINGS_GROUP_DEFINITIONS =
            List.of(
                    new SettingsGroupDefinition("brand-login", "品牌登录", "系统 Logo、登录页文案和登录媒体资源配置。"),
                    new SettingsGroupDefinition("site-basic", "站点基础", "后台标题、网站标题和备案等基础配置。"),
                    new SettingsGroupDefinition("security-policy", "安全策略", "影响脚本注入、外部验证和安全边界的配置。"),
                    new SettingsGroupDefinition("seo-defaults", "SEO 默认值", "前台页面默认标题、关键词和描述。"),
                    new SettingsGroupDefinition("system-switches", "系统开关", "安装、运行状态和全局行为开关。"),
                    new SettingsGroupDefinition(
                            "other", "历史兼容", "旧 options 表暂未归类的历史配置，保存修改前必须确认。"));
    private static final List<SettingsOptionDefinition> SETTINGS_OPTION_DEFINITIONS =
            List.of(
                    new SettingsOptionDefinition(
                            "system.logoUrl",
                            "系统 Logo 地址",
                            "后台和登录页可选 Logo 图片地址，必须使用站内资源路径。",
                            "brand-login",
                            "url",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "system.name",
                            "系统名称",
                            "新版后台品牌名称，品牌配置区保存后同样写入旧 options 表。",
                            "brand-login",
                            "text",
                            "鸿儒 CMS 3.0",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "system.sidebarBackgroundStyle",
                            "侧边栏背景样式",
                            "新版后台侧边栏背景样式，支持 binary、stars、solid 或 none。",
                            "brand-login",
                            "select",
                            "binary",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "login.title",
                            "登录页标题",
                            "登录页主标题。",
                            "brand-login",
                            "text",
                            "鸿儒 CMS 3.0",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "login.description",
                            "登录页说明",
                            "登录页主说明文案。",
                            "brand-login",
                            "textarea",
                            "现代化多站点内容管理平台，融合 AI 模板识别、可控字段绑定和多语言内容生成能力。",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "login.backgroundType",
                            "登录背景类型",
                            "登录页背景媒体类型，支持 image 或 video。",
                            "brand-login",
                            "select",
                            "video",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "login.backgroundUrl",
                            "登录背景地址",
                            "登录页图片或视频地址，必须使用站内资源路径。",
                            "brand-login",
                            "media",
                            "/media/login-background.mp4",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "loginBackgroundPosterUrl",
                            "登录背景封面",
                            "视频背景加载前或失败时的兜底图片地址。",
                            "brand-login",
                            "media",
                            "/media/login-background-poster.jpg",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "loginOverlayOpacity",
                            "登录遮罩透明度",
                            "登录页背景遮罩透明度，取值 0 到 0.85。",
                            "brand-login",
                            "number",
                            "0.48",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "manage_title",
                            "后台系统名称",
                            "旧后台页面标题和登录页管理中心名称。",
                            "site-basic",
                            "text",
                            "CMS后台管理中心",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "site_title",
                            "网站标题",
                            "前台页面未单独配置 SEO 时使用的默认标题。",
                            "site-basic",
                            "text",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "site_record",
                            "备案号",
                            "页面底部展示的备案信息。",
                            "site-basic",
                            "text",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "baidu_site_verification",
                            "百度网站验证码",
                            "搜索平台站点验证标识。",
                            "security-policy",
                            "text",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "google_site_verification",
                            "Google 网站验证码",
                            "搜索平台站点验证标识。",
                            "security-policy",
                            "text",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "statistics_code",
                            "第三方统计代码",
                            "会注入前台页面的统计脚本，保存修改前必须二次确认。",
                            "security-policy",
                            "textarea",
                            "",
                            true,
                            true),
                    new SettingsOptionDefinition(
                            "site_keywords",
                            "默认关键词",
                            "前台页面未单独配置 SEO 时使用的关键词。",
                            "seo-defaults",
                            "textarea",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "site_description",
                            "默认描述",
                            "前台页面未单独配置 SEO 时使用的描述。",
                            "seo-defaults",
                            "textarea",
                            "",
                            false,
                            false),
                    new SettingsOptionDefinition(
                            "allow_install",
                            "允许安装开关",
                            "影响系统安装入口或初始化能力，保存修改前必须二次确认。",
                            "system-switches",
                            "switch",
                            "",
                            true,
                            true));
    private static final Map<String, String> BRANDING_DEFAULTS =
            Map.ofEntries(
                    Map.entry("systemLogoUrl", "/media/system-logo.svg"),
                    Map.entry("systemName", "鸿儒 CMS 3.0"),
                    Map.entry("sidebarBackgroundStyle", "binary"),
                    Map.entry("loginEyebrow", "智能内容管理平台"),
                    Map.entry("loginTitle", "鸿儒 CMS 3.0"),
                    Map.entry("loginSubtitle", "现代化多站点内容管理平台，融合 AI 模板识别、可控字段绑定和多语言内容生成能力。"),
                    Map.entry("loginFormTitle", "欢迎回来"),
                    Map.entry("loginFormSubtitle", "请输入管理员账号、密码和验证码。"),
                    Map.entry("loginBackgroundType", "video"),
                    Map.entry("loginBackgroundUrl", "/media/login-background.mp4"),
                    Map.entry("loginBackgroundPosterUrl", "/media/login-background-poster.jpg"),
                    Map.entry("loginBackgroundFit", "cover"),
                    Map.entry("loginBackgroundPosition", "center center"),
                    Map.entry("loginOverlayOpacity", "0.48"),
                    Map.entry("loginHighlights", "多站点统一管理\nAI 辅助模板绑定\n内容模型驱动多语言"));
    private static final String INTEGRATION_SECRET_MASK = "********";
    private static final Map<String, String> INTEGRATION_DEFAULTS =
            Map.ofEntries(
                    Map.entry("ai.url", ""),
                    Map.entry("ai.apiKey", ""),
                    Map.entry("ai.defaultModel", ""),
                    Map.entry("moss.pushUrl", ""),
                    Map.entry("bpp.url", ""),
                    Map.entry("bpp.processDefinition", ""),
                    Map.entry("bpp.callbackUrl", ""));
    private static final Map<String, String> INTEGRATION_OPTION_NAMES =
            Map.ofEntries(
                    Map.entry("ai.url", "aiUrl"),
                    Map.entry("ai.apiKey", "aiApiKey"),
                    Map.entry("ai.defaultModel", "aiDefaultModel"),
                    Map.entry("moss.pushUrl", "mossPushUrl"),
                    Map.entry("bpp.url", "bppUrl"),
                    Map.entry("bpp.processDefinition", "bppProcessDefinition"),
                    Map.entry("bpp.callbackUrl", "bppCallbackUrl"));

    @Autowired
    public SystemAdminV2ApiController(
            UserService userService,
            UserGroupService userGroupService,
            LogService logService,
            LogOperationService logOperationService,
            OptionService optionService,
            OneShotService oneShotService,
            Sm3PasswordHashService passwordHashService,
            PublicStorageRoots publicStorage,
            ResourceReferenceResolver resourceResolver,
            AdminAuthenticationService authenticationService) {
        this.userService = userService;
        this.userGroupService = userGroupService;
        this.logService = logService;
        this.logOperationService = logOperationService;
        this.optionService = optionService;
        this.oneShotService = oneShotService;
        this.passwordHashService = passwordHashService;
        this.publicStorage = publicStorage;
        this.resourceResolver = resourceResolver;
        this.authenticationService = authenticationService;
    }

    @Autowired
    public void setSiteContextService(SiteContextService siteContextService) {
        this.siteContextService = siteContextService;
    }

    @GetMapping("/users")
    public AdminApiResponse<UserWorkspaceResponse> users(HttpServletRequest request) {
        Scope scope = accountAccess.requireScope(request);
        Integer topParentId = userGroupService.getTopParentId(request);
        List<UserGroupEntity> groupEntities =
                userGroupService.getDropdownList(topParentId, request).stream()
                        .filter(scope::canGrant).toList();
        List<UserEntity> userEntities = userService.getUserList(request).stream().filter(scope::canRead).toList();
        Map<Integer, Long> userCountByGroupId =
                userEntities.stream()
                        .filter(user -> user.getGroupId() != null)
                        .collect(
                                Collectors.groupingBy(
                                        UserEntity::getGroupId,
                                        LinkedHashMap::new,
                                        Collectors.counting()));
        List<UserGroupView> groups =
                groupEntities.stream()
                        .map(
                                group ->
                                        UserGroupView.from(
                                                group,
                                                userCountByGroupId
                                                        .getOrDefault(group.getId(), 0L)
                                                        .intValue(),
                                                null))
                        .toList();
        Map<Integer, UserGroupView> groupById = new LinkedHashMap<>();
        groups.forEach(group -> groupById.put(group.id(), group));
        return AdminApiResponse.success(
                new UserWorkspaceResponse(
                        userEntities.stream()
                                .map(user -> UserView.from(user, groupById.get(user.getGroupId())))
                                .toList(),
                        groups,
                        topParentId,
                        null,
                        null));
    }

    @GetMapping("/profile")
    public AdminApiResponse<UserView> profile(HttpServletRequest request) {
        UserEntity loginUser = requireLoginUser(request);
        UserEntity persisted = userService.getUserInfoById(loginUser.getId(), false);
        return AdminApiResponse.success(UserView.from(persisted == null ? loginUser : persisted));
    }

    @PostMapping("/profile")
    public AdminApiResponse<UserView> saveProfile(
            HttpServletRequest request, @RequestBody ProfileRequest body) {
        UserEntity loginUser = requireLoginUser(request);
        String screenName = StringUtils.trimToEmpty(body == null ? null : body.screenName());
        String mobile = body == null ? null : body.mobile();
        String email = StringUtils.trimToEmpty(body == null ? null : body.email());
        if (StringUtils.isBlank(screenName) || StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("姓名和邮箱不能为空");
        }
        UserEntity update =
                new UserEntity().setId(loginUser.getId()).setScreenName(screenName).setEmail(email);
        if (mobile != null) {
            update.setHomeUrl(StringUtils.trimToEmpty(mobile));
        }
        if (userService.updateUserInfo(update) <= 0) {
            throw new IllegalStateException("个人资料保存失败");
        }
        loginUser.setScreenName(screenName);
        if (mobile != null) {
            loginUser.setHomeUrl(StringUtils.trimToEmpty(mobile));
        }
        loginUser.setEmail(email);
        request.setAttribute(SessionIdentity.ADMIN_USER, loginUser);
        UserEntity persisted = userService.getUserInfoById(loginUser.getId(), false);
        return AdminApiResponse.success(UserView.from(persisted == null ? loginUser : persisted));
    }

    @PostMapping("/profile/password")
    public AdminApiResponse<Void> changePassword(
            HttpServletRequest request, @RequestBody PasswordChangeRequest body) {
        UserEntity loginUser = requireLoginUser(request);
        String oldPassword = StringUtils.trimToEmpty(body == null ? null : body.oldPassword());
        String password = StringUtils.trimToEmpty(body == null ? null : body.password());
        String repass = StringUtils.trimToEmpty(body == null ? null : body.repass());
        if (StringUtils.isBlank(oldPassword)) {
            throw new IllegalArgumentException("请填写原密码");
        }
        if (StringUtils.isBlank(password)) {
            throw new IllegalArgumentException("请填写新密码");
        }
        if (StringUtils.isBlank(repass)) {
            throw new IllegalArgumentException("请再次确认新密码");
        }
        if (!password.equals(repass)) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
        requireStrongPassword(password);
        if (password.equals(oldPassword)) {
            throw new IllegalArgumentException("新密码不能与旧密码相同");
        }
        if (!passwordHashService.matches(oldPassword, loginUser.getPassword())) {
            throw new IllegalArgumentException("旧密码错误");
        }
        String hashedPassword = passwordHashService.hash(password);
        UserEntity update = new UserEntity().setId(loginUser.getId()).setPassword(hashedPassword);
        if (userService.updateUserInfo(update) <= 0) {
            throw new IllegalStateException("密码修改失败");
        }
        loginUser.setPassword(hashedPassword);
        request.setAttribute(SessionIdentity.ADMIN_USER, loginUser);
        writeAuditLog(request, "修改个人密码", "{\"userId\":" + loginUser.getId() + "}", "success");
        return AdminApiResponse.success();
    }

    @GetMapping("/users/{id}")
    public AdminApiResponse<UserView> user(HttpServletRequest request, @PathVariable Integer id) {
        Scope scope = accountAccess.requireScope(request);
        UserEntity target = requireUser(id);
        scope.requireRead(target);
        return AdminApiResponse.success(UserView.from(target));
    }

    @PostMapping("/users")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<UserView> saveUser(
            HttpServletRequest request, @RequestBody UserRequest body) {
        Scope scope = accountAccess.requireScope(request);
        UserEntity entity =
                new UserEntity()
                        .setId(body.id() == null ? 0 : body.id())
                        .setUsername(StringUtils.trimToEmpty(body.username()))
                        .setScreenName(StringUtils.trimToEmpty(body.screenName()))
                        .setEmail(StringUtils.trimToEmpty(body.email()))
                        .setGroupId(body.groupId());
        if (StringUtils.isBlank(entity.getScreenName()) || StringUtils.isBlank(entity.getEmail())) {
            throw new IllegalArgumentException("姓名和邮箱不能为空");
        }
        if (entity.getGroupId() == null || entity.getGroupId() <= 0) {
            throw new IllegalArgumentException("请选择管理员组");
        }
        UserEntity target = entity.getId() == 0 ? null : lockUser(entity.getId());
        if (target != null) {
            scope.requireAccountWrite(target);
        }
        scope.requirePrimaryGroup(entity.getGroupId(), target);
        if (entity.getId() == 0) {
            AdminAccountAccess.requireCreatableUsername(entity.getUsername());
            if (StringUtils.isBlank(entity.getUsername())) {
                throw new IllegalArgumentException("账号不能为空");
            }
            if (StringUtils.isBlank(body.password())) {
                throw new IllegalArgumentException("新建账号必须设置密码");
            }
            requireStrongPassword(body.password());
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
            if (StringUtils.isNotBlank(body.password())) {
                requireStrongPassword(body.password());
                entity.setPassword(passwordHashService.hash(body.password()));
            }
            if (userService.updateUserInfo(entity) <= 0) {
                throw new IllegalStateException("用户保存失败");
            }
            writeAuditLog(
                    request,
                    StringUtils.isNotBlank(body.password()) ? "保存管理员账号并重置密码" : "保存管理员账号",
                    "{\"userId\":"
                            + entity.getId()
                            + ",\"username\":\""
                            + safeJson(entity.getUsername())
                            + "\"}",
                    "success");
        }
        return AdminApiResponse.success(UserView.from(requireUser(entity.getId())));
    }

    @PostMapping("/users/{id}/reset-password")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<Void> resetPassword(
            HttpServletRequest request,
            @PathVariable Integer id,
            @RequestBody ResetPasswordRequest body) {
        Scope scope = accountAccess.requireScope(request);
        scope.requireAccountWrite(lockUser(id));
        String password = StringUtils.trimToEmpty(body == null ? null : body.password());
        if (StringUtils.isBlank(password)) {
            throw new IllegalArgumentException("请填写新密码");
        }
        requireStrongPassword(password);
        UserEntity update =
                new UserEntity().setId(id).setPassword(passwordHashService.hash(password));
        if (userService.updateUserInfo(update) <= 0) {
            throw new IllegalStateException("密码重置失败");
        }
        writeAuditLog(request, "重置管理员密码", "{\"userId\":" + id + "}", "success");
        return AdminApiResponse.success();
    }

    @PostMapping("/users/{id}/disable")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<UserView> disableUser(
            HttpServletRequest request, @PathVariable Integer id) {
        Scope scope = accountAccess.requireScope(request);
        scope.requireAccountWrite(lockUser(id));
        UserEntity update = new UserEntity().setId(id).setActivated(1);
        if (userService.updateUserInfo(update) <= 0) {
            throw new IllegalStateException("账号禁用失败");
        }
        writeAuditLog(request, "禁用管理员账号", "{\"userId\":" + id + "}", "success");
        UserEntity disabled = requireUser(id);
        disabled.setActivated(1);
        return AdminApiResponse.success(UserView.from(disabled));
    }

    @DeleteMapping("/users/{id}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminApiResponse<Void> deleteUser(HttpServletRequest request, @PathVariable Integer id) {
        Scope scope = accountAccess.requireScope(request);
        scope.requireAccountWrite(lockUser(id));
        if (!userService.deleteUser(id)) {
            throw new IllegalStateException("用户删除失败");
        }
        userSiteGroupService.deleteByUserId(id);
        writeAuditLog(request, "删除管理员账号", "{\"userId\":" + id + "}", "success");
        return AdminApiResponse.success();
    }

    @GetMapping("/groups")
    public AdminApiResponse<List<UserGroupView>> groups(HttpServletRequest request) {
        Scope access = accountAccess.requireScope(request);
        return AdminApiResponse.success(
                userGroupService
                        .getUserGroupList(userGroupService.getTopParentId(request), request)
                        .stream()
                        .filter(access::canManageGroup)
                        .map(group -> UserGroupView.from(group, 0, null))
                        .toList());
    }

    @GetMapping("/groups/{id}")
    public AdminApiResponse<UserGroupView> group(
            HttpServletRequest request, @PathVariable Integer id) {
        UserGroupEntity group = requireGroup(id);
        accountAccess.requireScope(request).requireGroupAccess(group);
        return AdminApiResponse.success(UserGroupView.from(group, 0, null));
    }

    @PostMapping("/groups")
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AdminApiResponse<UserGroupView> saveGroup(
            HttpServletRequest request, @RequestBody UserGroupEntity body) {
        Scope access = accountAccess.requireScope(request);
        if (StringUtils.isBlank(body.getTitle())) {
            throw new IllegalArgumentException("管理员组名称不能为空");
        }
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
        access.prepareGroupSave(body, before, before == null ? currentSiteId(request) : before.getSiteId());
        requireSuccess(userGroupService.addOrUpdateUserGroup(body));
        boolean permissionsChanged =
                before == null
                        || !Objects.equals(
                                StringUtils.defaultString(before.getGroupSetting()),
                                StringUtils.defaultString(body.getGroupSetting()))
                        || !Objects.equals(
                                StringUtils.defaultString(before.getOtherSetting()),
                                StringUtils.defaultString(body.getOtherSetting()));
        writeAuditLog(
                request,
                permissionsChanged ? "保存管理员组权限" : "保存管理员组",
                "{\"groupId\":"
                        + body.getId()
                        + ",\"title\":\""
                        + safeJson(body.getTitle())
                        + "\"}",
                "success");
        UserGroupEntity group = requireGroup(body.getId());
        return AdminApiResponse.success(UserGroupView.from(group, 0, null));
    }

    @PostMapping("/groups/{id}/switch")
    public AdminApiResponse<UserGroupView> switchGroup(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody SwitchRequest body) {
        if (id == 1 || id == 2) {
            throw new IllegalArgumentException("顶级管理员组不可切换状态");
        }
        requireWritableGroupStatus(request, id);
        requireSuccess(
                userGroupService.updateSwitch(
                        new UserGroupSwitchDto(id, body.fieldName()), request));
        writeAuditLog(
                request,
                "启停管理员组",
                "{\"groupId\":" + id + ",\"fieldName\":\"" + safeJson(body.fieldName()) + "\"}",
                "success");
        UserGroupEntity group = requireGroup(id);
        return AdminApiResponse.success(UserGroupView.from(group, 0, null));
    }

    @DeleteMapping("/groups/{id}")
    public AdminApiResponse<Void> deleteGroup(
            HttpServletRequest request, @PathVariable Integer id) {
        if (id == 1 || id == 2) {
            throw new IllegalArgumentException("顶级管理员组不能删除");
        }
        requireWritableGroupStatus(request, id);
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

    private void requireWritableGroupStatus(HttpServletRequest request, Integer id) {
        UserGroupEntity target = requireGroup(id);
        accountAccess.requireScope(request).requireGroupAccess(target);
        if (WebConst.PLATFORM_SITE_ID.equals(target.getSiteId())) {
            throw new IllegalArgumentException("平台组不允许启停或删除");
        }
    }

    @GetMapping("/logs")
    public AdminApiResponse<LogListResponse> logs(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer userId,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
                    LocalDate beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
                    LocalDate endTime) {
        LogDto query =
                new LogDto()
                        .setPage(page)
                        .setLimit(limit)
                        .setUserId(userId)
                        .setKey(key)
                        .setBeginTime(beginTime)
                        .setEndTime(endTime);
        LogHtmlVo view = logService.getLogList(query, request);
        return AdminApiResponse.success(
                new LogListResponse(
                        view.getLogEntities(),
                        view.getUserEntities().stream().map(UserView::from).toList(),
                        view.getTotal(),
                        page,
                        limit));
    }

    @GetMapping("/logs/{id}")
    public AdminApiResponse<LogEntity> logDetail(
            HttpServletRequest request, @PathVariable Integer id) {
        UserEntity loginUser = requireLoginUser(request);
        LogEntity log = logService.getLogDetails(new IdDto(id));
        if (log == null) {
            throw new IllegalArgumentException("日志不存在");
        }
        return AdminApiResponse.success(log);
    }

    @GetMapping("/operation-logs")
    public AdminApiResponse<List<LogOperationEntity>> operationLogs(
            HttpServletRequest request,
            @RequestParam Integer classId,
            @RequestParam Integer infoId) {
        return AdminApiResponse.success(
                logOperationService.getListByClassIdAndInfoId(classId, infoId));
    }

    @GetMapping("/operation-log-list")
    public AdminApiResponse<OperationLogListResponse> operationLogList(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer userId,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
                    LocalDate beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd")
                    LocalDate endTime) {
        LogDto query =
                new LogDto()
                        .setPage(page)
                        .setLimit(limit)
                        .setUserId(userId)
                        .setKey(key)
                        .setBeginTime(beginTime)
                        .setEndTime(endTime);
        PageResult<LogOperationEntity> pageInfo = logOperationService.getLogList(query);
        return AdminApiResponse.success(
                new OperationLogListResponse(pageInfo.getList(), pageInfo.getTotal(), page, limit));
    }

    @GetMapping("/operation-logs/{id}")
    public AdminApiResponse<LogOperationEntity> operationLogDetail(
            HttpServletRequest request, @PathVariable Integer id) {
        LogOperationEntity log = logOperationService.getLogById(id);
        if (log == null) {
            throw new IllegalArgumentException("操作日志不存在");
        }
        return AdminApiResponse.success(log);
    }

    @GetMapping("/settings")
    public AdminApiResponse<SettingsResponse> settings(HttpServletRequest request) {
        siteContextService.requireSuperAdmin(request);
        return settings();
    }

    public AdminApiResponse<SettingsResponse> settings() {
        List<OptionsEntity> options = visibleSettingsOptions();
        Map<String, String> values = settingsValues(options);
        return AdminApiResponse.success(
                new SettingsResponse(options, values, settingsGroups(options, values), null));
    }

    @GetMapping("/settings/catalog")
    public AdminApiResponse<SettingsCatalogResponse> settingsCatalog(HttpServletRequest request) {
        siteContextService.requireSuperAdmin(request);
        return settingsCatalog();
    }

    public AdminApiResponse<SettingsCatalogResponse> settingsCatalog() {
        List<OptionsEntity> options = visibleSettingsOptions();
        Map<String, String> values = settingsValues(options);
        List<SettingsGroup> groups = settingsGroups(options, values);
        return AdminApiResponse.success(
                new SettingsCatalogResponse(
                        options, values, groups, settingsCatalogItems(groups, values)));
    }

    @PostMapping("/settings")
    public AdminApiResponse<SettingsResponse> saveSettings(
            HttpServletRequest request, @RequestBody Map<String, ?> body) {
        siteContextService.requireSuperAdmin(request);
        UserEntity operator = requireLoginUser(request);
        SettingsSavePayload payload = parseSettingsSavePayload(body);
        List<OptionsEntity> beforeOptions = optionService.getOptions();
        Map<String, String> beforeValues = settingsValues(beforeOptions);
        Map<String, String> normalized = normalizeSettingsOptions(payload.values());
        normalized.entrySet().removeIf(entry -> INTEGRATION_SECRET_MASK.equals(entry.getValue())
                && isSensitiveSettingKey(entry.getKey(), settingsDefinition(entry.getKey())));
        requireSafeSettingsConfig(normalized);
        normalized.replaceAll(
                (key, value) ->
                        BRANDING_MEDIA_OPTION_NAMES.contains(key)
                                ? normalizeUrl(value, "")
                                : value);
        requireConfirmedSensitiveChanges(
                normalized, payload.confirmedSensitiveKeys(), beforeValues);
        requireConfirmedLegacyChanges(normalized, payload.confirmedLegacyKeys(), beforeValues);
        SettingsAuditSummary audit =
                settingsAuditSummary(normalized, beforeValues, operator, LocalDateTime.now());
        optionService.saveOptions(normalized);
        writeAuditLog(
                request,
                "保存系统配置",
                settingsAuditPayload(audit, payload.confirmedSensitiveKeys()),
                "success");
        return settingsWithAudit(audit);
    }

    public AdminApiResponse<SettingsResponse> saveSettings(Map<String, ?> body) {
        SettingsSavePayload payload = parseSettingsSavePayload(body);
        List<OptionsEntity> beforeOptions = optionService.getOptions();
        Map<String, String> beforeValues = settingsValues(beforeOptions);
        Map<String, String> normalized = normalizeSettingsOptions(payload.values());
        normalized.entrySet().removeIf(entry -> INTEGRATION_SECRET_MASK.equals(entry.getValue())
                && isSensitiveSettingKey(entry.getKey(), settingsDefinition(entry.getKey())));
        requireSafeSettingsConfig(normalized);
        normalized.replaceAll(
                (key, value) ->
                        BRANDING_MEDIA_OPTION_NAMES.contains(key)
                                ? normalizeUrl(value, "")
                                : value);
        requireConfirmedSensitiveChanges(
                normalized, payload.confirmedSensitiveKeys(), beforeValues);
        requireConfirmedLegacyChanges(normalized, payload.confirmedLegacyKeys(), beforeValues);
        optionService.saveOptions(normalized);
        return settings();
    }

    @GetMapping("/branding")
    public AdminApiResponse<BrandingResponse> branding(HttpServletRequest request) {
        return branding();
    }

    public AdminApiResponse<BrandingResponse> branding() {
        BrandingSettings settings = brandingSettings();
        return AdminApiResponse.success(
                new BrandingResponse(settings, brandingOptions(), brandingUrls(settings)));
    }

    @PostMapping("/branding")
    public AdminApiResponse<BrandingResponse> saveBranding(
            HttpServletRequest request, @RequestBody BrandingSettings body) {
        siteContextService.requireSuperAdmin(request);
        BrandingSettings normalized = normalizeBranding(body);
        optionService.saveOptions(normalized.toOptions());
        writeAuditLog(
                request,
                "保存系统品牌配置",
                "{\"keys\":\"" + String.join(",", normalized.toOptions().keySet()) + "\"}",
                "success");
        return branding();
    }

    public AdminApiResponse<BrandingResponse> saveBranding(BrandingSettings body) {
        BrandingSettings normalized = normalizeBranding(body);
        optionService.saveOptions(normalized.toOptions());
        return branding();
    }

    @GetMapping("/integration")
    public AdminApiResponse<IntegrationSettings> integration(HttpServletRequest request) {
        siteContextService.requireSuperAdmin(request);
        return integration();
    }

    public AdminApiResponse<IntegrationSettings> integration() {
        Map<String, String> values = new LinkedHashMap<>(INTEGRATION_DEFAULTS);
        Map<String, String> rawValues = new LinkedHashMap<>();
        optionService
                .getOptions()
                .forEach(
                        option ->
                                rawValues.put(
                                        option.getName(),
                                        StringUtils.defaultString(option.getValue())));
        INTEGRATION_DEFAULTS
                .keySet()
                .forEach(
                        name -> {
                            if (rawValues.containsKey(name)) {
                                values.put(name, rawValues.get(name));
                            }
                        });
        return AdminApiResponse.success(
                new IntegrationSettings(
                        values.get("ai.url"),
                        maskIntegrationSecret(values.get("ai.apiKey")),
                        values.get("ai.defaultModel"),
                        values.get("moss.pushUrl"),
                        values.get("bpp.url"),
                        values.get("bpp.processDefinition"),
                        values.get("bpp.callbackUrl")));
    }

    @PostMapping("/integration")
    public AdminApiResponse<IntegrationSettings> saveIntegration(
            HttpServletRequest request, @RequestBody IntegrationSettings body) {
        siteContextService.requireSuperAdmin(request);
        Map<String, String> changes = integrationChanges(body);
        optionService.saveOptions(changes);
        writeAuditLog(
                request,
                "保存集成配置",
                "{\"keys\":\"" + String.join(",", changes.keySet()) + "\"}",
                "success");
        return integration();
    }

    public AdminApiResponse<IntegrationSettings> saveIntegration(IntegrationSettings body) {
        optionService.saveOptions(integrationChanges(body));
        return integration();
    }

    public record IntegrationSettings(
            String aiUrl,
            String aiApiKey,
            String aiDefaultModel,
            String mossPushUrl,
            String bppUrl,
            String bppProcessDefinition,
            String bppCallbackUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Set<String> confirmedSensitiveKeys) {
        public IntegrationSettings(
                String aiUrl, String aiApiKey, String aiDefaultModel, String mossPushUrl,
                String bppUrl, String bppProcessDefinition, String bppCallbackUrl) {
            this(aiUrl, aiApiKey, aiDefaultModel, mossPushUrl, bppUrl, bppProcessDefinition,
                    bppCallbackUrl, null);
        }

        Map<String, String> toOptions() {
            Map<String, String> values = new LinkedHashMap<>();
            INTEGRATION_OPTION_NAMES.forEach(
                    (optionName, fieldName) -> {
                        String fieldValue =
                                switch (fieldName) {
                                    case "aiUrl" -> StringUtils.defaultString(aiUrl);
                                    case "aiApiKey" -> StringUtils.defaultString(aiApiKey);
                                    case "aiDefaultModel" ->
                                            StringUtils.defaultString(aiDefaultModel);
                                    case "mossPushUrl" -> StringUtils.defaultString(mossPushUrl);
                                    case "bppUrl" -> StringUtils.defaultString(bppUrl);
                                    case "bppProcessDefinition" ->
                                            StringUtils.defaultString(bppProcessDefinition);
                                    case "bppCallbackUrl" ->
                                            StringUtils.defaultString(bppCallbackUrl);
                                    default -> "";
                                };
                        values.put(optionName, fieldValue);
                    });
            return values;
        }
    }

    private Map<String, String> integrationChanges(IntegrationSettings body) {
        Map<String, String> current = currentIntegrationValues();
        String suppliedKey = StringUtils.trimToNull(body == null ? null : body.aiApiKey());
        String currentKey = current.get("ai.apiKey");
        boolean changeKey = suppliedKey != null && !INTEGRATION_SECRET_MASK.equals(suppliedKey)
                && !Objects.equals(currentKey, suppliedKey);
        if (changeKey && (body.confirmedSensitiveKeys() == null
                || !body.confirmedSensitiveKeys().contains("ai.apiKey"))) {
            throw new IllegalArgumentException("敏感配置修改需要二次确认，请确认后再保存：AI API 密钥（ai.apiKey）");
        }
        Map<String, String> changes = new IntegrationSettings(
                normalizeIntegrationUrl(
                        body == null ? null : body.aiUrl(), current.get("ai.url"), "AI URL"),
                suppliedKey,
                StringUtils.trimToEmpty(body == null ? null : body.aiDefaultModel()),
                normalizeIntegrationUrl(
                        body == null ? null : body.mossPushUrl(),
                        current.get("moss.pushUrl"),
                        "MOSS 推送 URL"),
                normalizeIntegrationUrl(
                        body == null ? null : body.bppUrl(), current.get("bpp.url"), "BPP URL"),
                StringUtils.trimToEmpty(body == null ? null : body.bppProcessDefinition()),
                normalizeIntegrationUrl(
                        body == null ? null : body.bppCallbackUrl(),
                        current.get("bpp.callbackUrl"),
                        "BPP 回调 URL")).toOptions();
        if (!changeKey) changes.remove("ai.apiKey");
        return changes;
    }

    private Map<String, String> currentIntegrationValues() {
        Map<String, String> current = new LinkedHashMap<>();
        optionService
                .getOptions()
                .forEach(
                        option ->
                                current.put(
                                        option.getName(),
                                        StringUtils.defaultString(option.getValue())));
        return current;
    }

    private String normalizeIntegrationUrl(String value, String currentValue, String label) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            return StringUtils.defaultString(currentValue);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException(label + "必须使用 http(s) 协议地址，如 https://example.com");
        }
        return trimmed;
    }

    private String maskIntegrationSecret(String value) {
        return StringUtils.isBlank(value) ? "" : INTEGRATION_SECRET_MASK;
    }

    @PostMapping(value = "/branding/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminApiResponse<BrandingMediaUploadResponse> uploadBrandingBackground(
            HttpServletRequest request, @RequestParam("file") MultipartFile file)
            throws IOException {
        siteContextService.requireSuperAdmin(request);
        BrandingMediaUploadResponse response = storeBrandingBackground(file);
        writeAuditLog(
                request,
                "上传系统品牌背景",
                "{\"mediaType\":\""
                        + safeJson(response.mediaType())
                        + "\",\"url\":\""
                        + safeJson(response.url())
                        + "\",\"extension\":\""
                        + safeJson(fileExtension(file.getOriginalFilename()))
                        + "\"}",
                "success");
        return AdminApiResponse.success(response);
    }

    public AdminApiResponse<BrandingMediaUploadResponse> uploadBrandingBackground(
            MultipartFile file) throws IOException {
        return AdminApiResponse.success(storeBrandingBackground(file));
    }

    @PostMapping(value = "/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminApiResponse<BrandingMediaUploadResponse> uploadBrandingLogo(
            HttpServletRequest request, @RequestParam("file") MultipartFile file)
            throws IOException {
        siteContextService.requireSuperAdmin(request);
        BrandingMediaUploadResponse response = storeBrandingLogo(file);
        writeAuditLog(
                request,
                "上传系统品牌 Logo",
                "{\"mediaType\":\""
                        + safeJson(response.mediaType())
                        + "\",\"url\":\""
                        + safeJson(response.url())
                        + "\",\"extension\":\""
                        + safeJson(fileExtension(file.getOriginalFilename()))
                        + "\"}",
                "success");
        return AdminApiResponse.success(response);
    }

    public AdminApiResponse<BrandingMediaUploadResponse> uploadBrandingLogo(MultipartFile file)
            throws IOException {
        return AdminApiResponse.success(storeBrandingLogo(file));
    }

    private BrandingMediaUploadResponse storeBrandingBackground(MultipartFile file)
            throws IOException {
        String extension = validateBrandingMediaFile(file, true, false, "背景");
        return storeBrandingMedia(file, extension, mediaType(extension), "login-background-");
    }

    private BrandingMediaUploadResponse storeBrandingLogo(MultipartFile file) throws IOException {
        String extension = validateBrandingMediaFile(file, false, true, "Logo");
        return storeBrandingMedia(file, extension, "image", "system-logo-");
    }

    private BrandingMediaUploadResponse storeBrandingMedia(
            MultipartFile file, String extension, String mediaType, String filePrefix)
            throws IOException {
        Path mediaDirectory = publicStorage.directory("system/branding");
        Files.createDirectories(mediaDirectory);
        String fileName = filePrefix + UUID.randomUUID() + "." + extension;
        Path target = mediaDirectory.resolve(fileName);
        try (var input = file.getInputStream();
                var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            if ("svg".equals(extension)) output.write(SafeSvg.read(input));
            else input.transferTo(output);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
        return new BrandingMediaUploadResponse(
                publicStorage.reference(mediaDirectory.resolve(fileName)),
                resourceResolver.resolve(
                        publicStorage.reference(mediaDirectory.resolve(fileName)), null),
                mediaType);
    }

    private String validateBrandingMediaFile(
            MultipartFile file, boolean allowVideo, boolean allowSvgLogo, String label)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择" + label + "文件");
        }
        String extension = fileExtension(file.getOriginalFilename());
        if (!isAllowedBrandingUploadExtension(extension, allowVideo, allowSvgLogo)) {
            String formats = allowVideo ? "jpg、png、webp、mp4、webm" : "jpg、png、webp、svg";
            throw new IllegalArgumentException(unsupportedBrandingMediaMessage(label, formats));
        }
        String mediaType = mediaType(extension);
        if (!allowVideo && !"image".equals(mediaType)) {
            throw new IllegalArgumentException(label + " 文件格式不支持，仅支持 jpg、png、webp、svg");
        }
        validateMediaContent(file, extension, mediaType);
        long maxSize =
                "video".equals(mediaType) ? MAX_VIDEO_BACKGROUND_SIZE : MAX_IMAGE_BACKGROUND_SIZE;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException(label + "文件过大，请先压缩后上传");
        }
        return extension;
    }

    private boolean isAllowedBrandingUploadExtension(
            String extension, boolean allowVideo, boolean allowSvgLogo) {
        if (allowVideo) {
            return ALLOWED_BACKGROUND_MEDIA_EXTENSIONS.contains(extension);
        }
        return allowSvgLogo
                ? ALLOWED_LOGO_IMAGE_EXTENSIONS.contains(extension)
                : ALLOWED_IMAGE_EXTENSIONS.contains(extension);
    }

    private String unsupportedBrandingMediaMessage(String label, String formats) {
        String prefix = "背景".equals(label) ? "背景" : label + " ";
        return prefix + "文件格式不支持，仅支持 " + formats;
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleSiteAccessDenied(
            SiteAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AdminApiResponse<>(403, exception.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, message(ex, "系统管理请求参数无效"), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdminApiResponse<>(409, message(ex, "系统管理操作失败"), null));
    }

    private UserEntity requireUser(Integer id) {
        UserEntity user = userService.getUserInfoById(id, false);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    private UserEntity lockUser(Integer id) {
        UserEntity user = userService.lockUserById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    private UserEntity requireLoginUser(HttpServletRequest request) {
        return authenticationService.requireLoginSessionUser(request);
    }

    private Long currentSiteId(HttpServletRequest request) {
        return siteContextService.current(request).getSiteId();
    }

    private UserGroupEntity requireGroup(Integer id) {
        UserGroupEntity group = userGroupService.getUserGroupById(id);
        if (group == null) {
            throw new IllegalArgumentException("管理员组不存在");
        }
        return group;
    }

    private void requireSuccess(ResultVO<?> result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(
                    result == null || result.getMsg() == null ? "操作失败" : result.getMsg());
        }
    }

    /** 与登录侧 PasswordStrengthCheck 保持同一强度规则，避免存下永远无法登录的密码 */
    private void requireStrongPassword(String password) {
        if (!StringUtils.defaultString(password).matches(WebConst.PASSWORD_VALIDATE_PATTERN)) {
            throw new IllegalArgumentException("密码需为 8-24 位，且必须包含大写字母、小写字母、数字和特殊字符");
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

    private List<OptionsEntity> visibleSettingsOptions() {
        List<OptionsEntity> options = optionService.getOptions();
        if (options == null) return List.of();
        return options.stream().map(option -> {
            if (!isSensitiveSettingKey(option.getName(), settingsDefinition(option.getName()))) return option;
            return new OptionsEntity(option.getName(), maskIntegrationSecret(option.getValue()),
                    option.getDescription()).setId(option.getId());
        }).toList();
    }

    private AdminApiResponse<SettingsResponse> settingsWithAudit(SettingsAuditSummary audit) {
        List<OptionsEntity> options = visibleSettingsOptions();
        Map<String, String> values = settingsValues(options);
        return AdminApiResponse.success(
                new SettingsResponse(options, values, settingsGroups(options, values), audit));
    }

    private String settingsAuditPayload(
            SettingsAuditSummary audit, Set<String> confirmedSensitiveKeys) {
        Set<String> sensitiveKeys = new HashSet<>(confirmedSensitiveKeys);
        audit.changedKeys().stream()
                .filter(
                        key -> {
                            SettingsOptionDefinition definition = settingsDefinition(key);
                            return isSensitiveSettingKey(key, definition);
                        })
                .forEach(sensitiveKeys::add);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditId", audit.id());
        payload.put("changedKeys", audit.changedKeys());
        payload.put("sensitiveKeys", new ArrayList<>(sensitiveKeys));
        payload.put("operator", audit.operator());
        payload.put("changedAt", audit.changedAt());
        payload.put("summary", audit.summary());
        payload.put("changes", audit.changes());
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("系统配置审计摘要生成失败", ex);
        }
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME_FORMATTER.format(value);
    }

    private static String permissionSummaryFor(String groupSetting) {
        List<Integer> permissions =
                StringUtils.defaultString(groupSetting)
                        .lines()
                        .flatMap(line -> List.of(line.split(",")).stream())
                        .map(StringUtils::trimToEmpty)
                        .filter(StringUtils::isNotBlank)
                        .filter(item -> !"0".equals(item))
                        .map(
                                item -> {
                                    try {
                                        return Integer.parseInt(item);
                                    } catch (NumberFormatException ex) {
                                        return null;
                                    }
                                })
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        return permissions.isEmpty() ? "未授权栏目" : permissions.size() + " 个栏目";
    }

    private String message(RuntimeException ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    public record UserWorkspaceResponse(
            List<UserView> users,
            List<UserGroupView> groups,
            Integer topParentId,
            Object permissionWorkspace,
            Object rbacPermissionCatalog) {}

    public record SiteGroupOption(
            Long siteId, String siteName, Integer groupId, String groupName) {}

    public record UserView(
            Integer id,
            String username,
            String email,
            String screenName,
            String groupName,
            Integer groupId,
            Integer activated,
            String status,
            String lastLoginTime,
            String lastLoginIp,
            Integer loginTimes,
            String permissionSummary,
            String mobile,
            List<SiteGroupOption> siteGroups) {
        static UserView from(UserEntity item) {
            return from(item, null, List.of());
        }

        static UserView from(UserEntity item, UserGroupView group) {
            return from(item, group, List.of());
        }

        static UserView from(
                UserEntity item, UserGroupView group, List<SiteGroupOption> siteGroups) {
            String groupSetting =
                    StringUtils.defaultIfBlank(
                            item.getGroupSetting(), group == null ? "" : group.groupSetting());
            return new UserView(
                    item.getId(),
                    item.getUsername(),
                    item.getEmail(),
                    item.getScreenName(),
                    StringUtils.defaultIfBlank(
                            item.getGroupName(), group == null ? "" : group.title()),
                    item.getGroupId(),
                    item.getActivated(),
                    Integer.valueOf(1).equals(item.getActivated()) ? "停用" : "启用",
                    formatDateTime(item.getLastLoginTime()),
                    item.getLastLoginIp(),
                    item.getLoginTimes(),
                    permissionSummaryFor(groupSetting),
                    StringUtils.defaultString(item.getHomeUrl()),
                    siteGroups == null ? List.of() : siteGroups);
        }
    }

    public record UserGroupView(
            Integer id,
            String title,
            Integer parentId,
            String note,
            Boolean enabled,
            String groupSetting,
            String otherSetting,
            Integer userCount,
            List<Object> permissionItems,
            List<Object> unknownPermissionItems,
            Object legacyPermissionPreview) {
        static UserGroupView from(UserGroupEntity item) {
            return from(item, 0, null);
        }

        static UserGroupView from(UserGroupEntity item, Integer userCount) {
            return from(item, userCount, null);
        }

        static UserGroupView from(
                UserGroupEntity item, Integer userCount, Object legacyPermissionPreview) {
            return new UserGroupView(
                    item.getId(),
                    item.getTitle(),
                    item.getParentId(),
                    item.getNote(),
                    item.getEnabled(),
                    item.getGroupSetting(),
                    item.getOtherSetting(),
                    userCount == null ? 0 : userCount,
                    List.of(),
                    List.of(),
                    legacyPermissionPreview);
        }
    }

    public record UserRequest(
            Integer id,
            String username,
            String password,
            String email,
            String screenName,
            Integer groupId) {}

    public record ResetPasswordRequest(String password) {}

    public record ProfileRequest(String screenName, String mobile, String email) {
        public ProfileRequest(String screenName, String email) {
            this(screenName, "", email);
        }
    }

    public record PasswordChangeRequest(String oldPassword, String password, String repass) {}

    public record SwitchRequest(String fieldName) {}

    public record LogListResponse(
            List<LogEntity> rows, List<UserView> users, long total, int page, int pageSize) {}

    public record OperationLogListResponse(
            List<LogOperationEntity> rows, long total, int page, int pageSize) {}

    public record SettingsResponse(
            List<OptionsEntity> options,
            Map<String, String> values,
            List<SettingsGroup> groups,
            SettingsAuditSummary audit) {}

    public record SettingsCatalogResponse(
            List<OptionsEntity> options,
            Map<String, String> values,
            List<SettingsGroup> groups,
            List<SettingsCatalogItem> catalog) {}

    public record SettingsGroup(
            String key, String label, String description, List<SettingsOptionMetadata> items) {}

    public record SettingsOptionMetadata(
            String key,
            String label,
            String description,
            String groupKey,
            String groupLabel,
            String controlType,
            String defaultValue,
            boolean sensitive,
            boolean requiresConfirm,
            String lastModifiedBy,
            String lastModifiedAt) {}

    public record SettingsCatalogItem(
            String key,
            String label,
            String description,
            String groupKey,
            String groupLabel,
            String controlType,
            String defaultValue,
            String defaultValueDescription,
            String currentValue,
            String currentValueSummary,
            boolean sensitive,
            boolean requiresConfirm,
            boolean legacyUnknown,
            String lastModifiedBy,
            String lastModifiedAt) {}

    public record SettingsAuditSummary(
            String id,
            List<String> changedKeys,
            String operator,
            String changedAt,
            String summary,
            List<String> changes) {}

    public record BrandingResponse(
            BrandingSettings settings, List<BrandingOption> options, Map<String, String> urls) {}

    public record BrandingOption(
            String key, String label, String description, String controlType) {}

    public record BrandingMediaUploadResponse(String reference, String url, String mediaType) {}

    public record BrandingSettings(
            String systemLogoUrl,
            String systemName,
            String loginEyebrow,
            String loginTitle,
            String loginSubtitle,
            String loginFormTitle,
            String loginFormSubtitle,
            String loginBackgroundType,
            String loginBackgroundUrl,
            String loginBackgroundPosterUrl,
            String loginBackgroundFit,
            String loginBackgroundPosition,
            Double loginOverlayOpacity,
            String loginHighlights,
            String sidebarBackgroundStyle) {
        Map<String, String> toOptions() {
            Map<String, String> values = new LinkedHashMap<>();
            putBrandingOption(values, "systemLogoUrl", StringUtils.defaultString(systemLogoUrl));
            putBrandingOption(values, "systemName", StringUtils.defaultString(systemName));
            putBrandingOption(
                    values,
                    "sidebarBackgroundStyle",
                    StringUtils.defaultString(sidebarBackgroundStyle));
            putBrandingOption(values, "loginEyebrow", StringUtils.defaultString(loginEyebrow));
            putBrandingOption(values, "loginTitle", StringUtils.defaultString(loginTitle));
            putBrandingOption(values, "loginSubtitle", StringUtils.defaultString(loginSubtitle));
            putBrandingOption(values, "loginFormTitle", StringUtils.defaultString(loginFormTitle));
            putBrandingOption(
                    values, "loginFormSubtitle", StringUtils.defaultString(loginFormSubtitle));
            putBrandingOption(
                    values, "loginBackgroundType", StringUtils.defaultString(loginBackgroundType));
            putBrandingOption(
                    values, "loginBackgroundUrl", StringUtils.defaultString(loginBackgroundUrl));
            putBrandingOption(
                    values,
                    "loginBackgroundPosterUrl",
                    StringUtils.defaultString(loginBackgroundPosterUrl));
            putBrandingOption(
                    values, "loginBackgroundFit", StringUtils.defaultString(loginBackgroundFit));
            putBrandingOption(
                    values,
                    "loginBackgroundPosition",
                    StringUtils.defaultString(loginBackgroundPosition));
            putBrandingOption(
                    values,
                    "loginOverlayOpacity",
                    loginOverlayOpacity == null
                            ? BRANDING_DEFAULTS.get("loginOverlayOpacity")
                            : String.valueOf(loginOverlayOpacity));
            putBrandingOption(
                    values, "loginHighlights", StringUtils.defaultString(loginHighlights));
            return values;
        }
    }

    private static void putBrandingOption(
            Map<String, String> values, String fieldName, String value) {
        String canonicalName = BRANDING_CANONICAL_OPTION_NAMES.get(fieldName);
        if (canonicalName != null) {
            values.put(canonicalName, value);
        }
        values.put(fieldName, value);
    }

    private Map<String, String> brandingUrls(BrandingSettings settings) {
        return Map.of(
                "systemLogoUrl",
                resourceResolver.resolve(settings.systemLogoUrl(), null),
                "loginBackgroundUrl",
                resourceResolver.resolve(settings.loginBackgroundUrl(), null),
                "loginBackgroundPosterUrl",
                resourceResolver.resolve(settings.loginBackgroundPosterUrl(), null));
    }

    private BrandingSettings brandingSettings() {
        Map<String, String> values = new LinkedHashMap<>(BRANDING_DEFAULTS);
        Map<String, String> rawValues = new LinkedHashMap<>();
        optionService
                .getOptions()
                .forEach(
                        option ->
                                rawValues.put(
                                        option.getName(),
                                        StringUtils.defaultString(option.getValue())));
        BRANDING_DEFAULTS
                .keySet()
                .forEach(
                        fieldName -> {
                            String canonicalName = BRANDING_CANONICAL_OPTION_NAMES.get(fieldName);
                            if (canonicalName != null && rawValues.containsKey(canonicalName)) {
                                values.put(fieldName, rawValues.get(canonicalName));
                            } else if (rawValues.containsKey(fieldName)) {
                                values.put(fieldName, rawValues.get(fieldName));
                            }
                        });
        return normalizeBranding(
                new BrandingSettings(
                        values.get("systemLogoUrl"),
                        values.get("systemName"),
                        values.get("loginEyebrow"),
                        values.get("loginTitle"),
                        values.get("loginSubtitle"),
                        values.get("loginFormTitle"),
                        values.get("loginFormSubtitle"),
                        values.get("loginBackgroundType"),
                        values.get("loginBackgroundUrl"),
                        values.get("loginBackgroundPosterUrl"),
                        values.get("loginBackgroundFit"),
                        values.get("loginBackgroundPosition"),
                        parseOverlayOpacity(values.get("loginOverlayOpacity")),
                        values.get("loginHighlights"),
                        values.get("sidebarBackgroundStyle")));
    }

    private BrandingSettings normalizeBranding(BrandingSettings body) {
        String backgroundType =
                normalizeChoice(
                        body == null ? null : body.loginBackgroundType(),
                        "video",
                        Set.of("image", "video"));
        String backgroundFit =
                normalizeChoice(
                        body == null ? null : body.loginBackgroundFit(),
                        "cover",
                        Set.of("cover", "contain", "fill"));
        String sidebarBackgroundStyle =
                normalizeChoice(
                        body == null ? null : body.sidebarBackgroundStyle(),
                        "binary",
                        Set.of("binary", "stars", "solid", "none"));
        Double overlayOpacity = body == null ? null : body.loginOverlayOpacity();
        double normalizedOpacity =
                overlayOpacity == null ? 0.48D : Math.max(0D, Math.min(0.85D, overlayOpacity));
        return new BrandingSettings(
                normalizeUrl(body == null ? null : body.systemLogoUrl(), ""),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.systemName(),
                        BRANDING_DEFAULTS.get("systemName")),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginEyebrow(),
                        BRANDING_DEFAULTS.get("loginEyebrow")),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginTitle(),
                        BRANDING_DEFAULTS.get("loginTitle")),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginSubtitle(),
                        BRANDING_DEFAULTS.get("loginSubtitle")),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginFormTitle(),
                        BRANDING_DEFAULTS.get("loginFormTitle")),
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginFormSubtitle(),
                        BRANDING_DEFAULTS.get("loginFormSubtitle")),
                backgroundType,
                normalizeUrl(
                        body == null ? null : body.loginBackgroundUrl(),
                        BRANDING_DEFAULTS.get("loginBackgroundUrl")),
                normalizeUrl(
                        body == null ? null : body.loginBackgroundPosterUrl(),
                        BRANDING_DEFAULTS.get("loginBackgroundPosterUrl")),
                backgroundFit,
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginBackgroundPosition(),
                        BRANDING_DEFAULTS.get("loginBackgroundPosition")),
                normalizedOpacity,
                StringUtils.defaultIfBlank(
                        body == null ? null : body.loginHighlights(),
                        BRANDING_DEFAULTS.get("loginHighlights")),
                sidebarBackgroundStyle);
    }

    private List<BrandingOption> brandingOptions() {
        return List.of(
                new BrandingOption("system.logoUrl", "系统 Logo", "后台左上角和登录页可选 Logo 图片地址", "url"),
                new BrandingOption("system.name", "系统名称", "显示在浏览器标题和后台主界面的系统名称", "text"),
                new BrandingOption(
                        "system.sidebarBackgroundStyle",
                        "侧边栏背景样式",
                        "新版后台侧边栏背景样式，支持 binary、stars、solid 或 none",
                        "select"),
                new BrandingOption("login.title", "登录页标题", "登录页左侧主标题", "text"),
                new BrandingOption("login.description", "登录页说明", "登录页左侧系统介绍文案", "textarea"),
                new BrandingOption("login.backgroundType", "背景类型", "支持 image 或 video", "select"),
                new BrandingOption("login.backgroundUrl", "背景文件", "登录页图片或视频地址", "media"),
                new BrandingOption("loginBackgroundPosterUrl", "视频封面", "视频加载失败时的兜底图片", "media"),
                new BrandingOption("loginOverlayOpacity", "遮罩透明度", "0 到 0.85，数值越大文字越清晰", "number"));
    }

    private String normalizeChoice(String value, String defaultValue, Set<String> allowedValues) {
        String normalized = StringUtils.defaultIfBlank(value, defaultValue).trim().toLowerCase();
        if (!allowedValues.contains(normalized)) {
            throw new IllegalArgumentException("配置值不支持：" + value);
        }
        return normalized;
    }

    private String normalizeUrl(String value, String defaultValue) {
        String normalized = StringUtils.defaultIfBlank(value, defaultValue).trim();
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        normalized = resourceResolver.context(null).canonical(normalized);
        if (normalized.startsWith("assets:/global/")) {
            publicStorage.resolveReference(normalized, null);
            return normalized;
        }

        if (hasUnsafePathCharacters(normalized)
                || !normalized.startsWith("/")
                || normalized.startsWith("//")) {
            throw new IllegalArgumentException(
                    "资源地址必须为空，或使用 /assets/global/、/media/、/static/ 下的站内路径");
        }
        String path = urlPath(normalized);
        String decodedPath = decodeUrlPath(path);
        if (hasUnsafePathCharacters(decodedPath) || containsParentPathSegment(decodedPath)) {
            throw new IllegalArgumentException(
                    "资源地址必须为空，或使用 /assets/global/、/media/、/static/ 下的站内路径");
        }
        String canonicalPath = normalizeUrlPath(decodedPath);
        if (ALLOWED_BRANDING_URL_PREFIXES.stream().noneMatch(canonicalPath::startsWith)) {
            throw new IllegalArgumentException(
                    "资源地址必须为空，或使用 /assets/global/、/media/、/static/ 下的站内路径");
        }
        return normalized;
    }

    private String urlPath(String value) {
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int endIndex;
        if (queryIndex < 0) {
            endIndex = fragmentIndex;
        } else if (fragmentIndex < 0) {
            endIndex = queryIndex;
        } else {
            endIndex = Math.min(queryIndex, fragmentIndex);
        }
        return endIndex < 0 ? value : value.substring(0, endIndex);
    }

    private String decodeUrlPath(String value) {
        String decoded = value;
        for (int index = 0; index < 3 && decoded.contains("%"); index++) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                return decoded;
            }
            decoded = next;
        }
        return decoded;
    }

    private boolean hasUnsafePathCharacters(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current < 0x20 || current == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private boolean containsParentPathSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeUrlPath(String path) {
        try {
            String normalized = Path.of(path).normalize().toString().replace('\\', '/');
            return normalized.startsWith("/") ? normalized : "/" + normalized;
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("资源地址必须为空，或使用 /assets/global/、/static/ 下的站内路径", ex);
        }
    }

    private Map<String, String> normalizeSettingsOptions(Map<String, String> body) {
        Map<String, String> rawNormalized = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        if (body == null) {
            return normalized;
        }
        body.forEach(
                (key, value) -> {
                    rawNormalized.put(key, value);
                });
        normalized.putAll(rawNormalized);
        BRANDING_CANONICAL_OPTION_NAMES.forEach(
                (fieldName, canonicalName) -> {
                    String selectedValue = null;
                    if (rawNormalized.containsKey(canonicalName)) {
                        selectedValue = rawNormalized.get(canonicalName);
                    } else if (rawNormalized.containsKey(fieldName)) {
                        selectedValue = rawNormalized.get(fieldName);
                    }
                    if (selectedValue != null) {
                        putBrandingOption(normalized, fieldName, selectedValue);
                    }
                });
        return normalized;
    }

    private SettingsSavePayload parseSettingsSavePayload(Map<String, ?> body) {
        if (body == null || body.isEmpty()) {
            return new SettingsSavePayload(new LinkedHashMap<>(), Set.of(), Set.of(), Set.of());
        }
        Object valuesPayload = body.get("values");
        if (valuesPayload instanceof Map<?, ?> valuesMap) {
            return new SettingsSavePayload(
                    stringMap(valuesMap),
                    stringSet(body.get("confirmedSensitiveKeys")),
                    stringSet(body.get("confirmedLegacyKeys")),
                    stringSet(body.get("confirmedDangerousKeys")));
        }
        return new SettingsSavePayload(stringMap(body), Set.of(), Set.of(), Set.of());
    }

    private Map<String, String> stringMap(Map<?, ?> source) {
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    if (key != null) {
                        values.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                    }
                });
        return values;
    }

    private Set<String> stringSet(Object source) {
        Set<String> values = new HashSet<>();
        if (source instanceof Collection<?> collection) {
            collection.forEach(
                    item -> {
                        if (item != null) {
                            values.add(String.valueOf(item));
                        }
                    });
        } else if (source instanceof String text) {
            for (String item : text.split(",")) {
                if (StringUtils.isNotBlank(item)) {
                    values.add(item.trim());
                }
            }
        }
        return values;
    }

    private void requireConfirmedSensitiveChanges(
            Map<String, String> values,
            Set<String> confirmedSensitiveKeys,
            Map<String, String> currentValues) {
        if (values.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        values.forEach(
                (key, value) -> {
                    SettingsOptionDefinition definition = settingsDefinition(key);
                    if (definition != null
                            && definition.requiresConfirm()
                            && !Objects.equals(value, currentValues.get(key))
                            && !confirmedSensitiveKeys.contains(key)) {
                        missing.add(definition.label() + "（" + key + "）");
                    }
                });
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("敏感配置修改需要二次确认，请确认后再保存：" + String.join("、", missing));
        }
    }

    private void requireConfirmedLegacyChanges(
            Map<String, String> values,
            Set<String> confirmedLegacyKeys,
            Map<String, String> currentValues) {
        List<String> missing = new ArrayList<>();
        values.forEach(
                (key, value) -> {
                    if (settingsDefinition(key) == null
                            && !BRANDING_OPTION_FIELD_NAMES.containsKey(key)
                            && !Objects.equals(value, currentValues.get(key))
                            && !confirmedLegacyKeys.contains(key)) {
                        missing.add(key);
                    }
                });
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "历史兼容配置修改需要二次确认，请确认后再保存：" + String.join("、", missing));
        }
    }

    private void requireSafeSettingsConfig(Map<String, String> values) {
        List<String> uploadPrompts = new ArrayList<>();
        List<String> securityPrompts = new ArrayList<>();
        values.forEach(
                (key, value) -> {
                    String text = StringUtils.defaultString(value);
                    if (BRANDING_MEDIA_OPTION_NAMES.contains(key) && StringUtils.isNotBlank(text)) {
                        String pathRisk = siteResourcePathRisk(text);
                        if (pathRisk != null) {
                            uploadPrompts.add(pathRisk + "（" + key + "）");
                        }
                        String extension = pathExtension(text);
                        if (BRANDING_LOGO_OPTION_NAMES.contains(key)
                                && StringUtils.isNotBlank(extension)
                                && !ALLOWED_LOGO_IMAGE_EXTENSIONS.contains(extension)) {
                            uploadPrompts.add("Logo 图片仅支持 jpg、jpeg、png、webp、svg（" + key + "）");
                        }
                        if (BRANDING_BACKGROUND_MEDIA_OPTION_NAMES.contains(key)
                                && StringUtils.isNotBlank(extension)
                                && !ALLOWED_BACKGROUND_MEDIA_EXTENSIONS.contains(extension)) {
                            uploadPrompts.add("背景媒体仅支持 jpg、jpeg、png、webp、mp4、webm（" + key + "）");
                        }
                    }
                    if ("statistics_code".equals(key) && text.toLowerCase().contains("http://")) {
                        securityPrompts.add("禁止保存 http 明文脚本");
                    }
                });
        List<String> prompts = new ArrayList<>();
        if (!uploadPrompts.isEmpty()) {
            prompts.add("上传存储配置存在风险：" + String.join("；", uploadPrompts));
        }
        if (!securityPrompts.isEmpty()) {
            prompts.add("安全策略配置存在风险：" + String.join("；", securityPrompts));
        }
        if (!prompts.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", prompts));
        }
    }

    private String siteResourcePathRisk(String value) {
        try {
            normalizeUrl(value, "");
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    private boolean isAllowedSiteResourcePath(String canonicalPath) {
        return ALLOWED_BRANDING_URL_PREFIXES.stream()
                .anyMatch(
                        prefix ->
                                canonicalPath.startsWith(prefix)
                                        || canonicalPath.equals(
                                                StringUtils.removeEnd(prefix, "/")));
    }

    private String pathExtension(String path) {
        String cleanPath;
        try {
            cleanPath = decodeUrlPath(urlPath(path)).toLowerCase();
        } catch (IllegalArgumentException ex) {
            return "";
        }
        int slashIndex = cleanPath.lastIndexOf('/');
        int dotIndex = cleanPath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex < slashIndex || dotIndex == cleanPath.length() - 1) {
            return "";
        }
        return cleanPath.substring(dotIndex + 1);
    }

    private Map<String, String> settingsValues(List<OptionsEntity> options) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> rawValues = new LinkedHashMap<>();
        SETTINGS_OPTION_DEFINITIONS.forEach(
                definition -> values.put(definition.key(), definition.defaultValue()));
        if (options == null) {
            options = List.of();
        }
        options.forEach(
                option -> {
                    String value = StringUtils.defaultString(option.getValue());
                    rawValues.put(option.getName(), value);
                    values.put(option.getName(), value);
                });
        BRANDING_CANONICAL_OPTION_NAMES.forEach(
                (fieldName, canonicalName) -> {
                    if (rawValues.containsKey(canonicalName)) {
                        values.put(canonicalName, rawValues.get(canonicalName));
                        values.put(fieldName, rawValues.get(canonicalName));
                    } else if (rawValues.containsKey(fieldName)) {
                        values.put(canonicalName, rawValues.get(fieldName));
                    }
                });
        return values;
    }

    private List<SettingsGroup> settingsGroups(
            List<OptionsEntity> options, Map<String, String> values) {
        Map<String, OptionsEntity> optionByName = new LinkedHashMap<>();
        if (options == null) {
            options = List.of();
        }
        options.forEach(option -> optionByName.put(option.getName(), option));
        List<SettingsGroup> groups = new ArrayList<>();
        Set<String> includedKeys = new HashSet<>();
        SETTINGS_GROUP_DEFINITIONS.forEach(
                groupDefinition -> {
                    List<SettingsOptionMetadata> items = new ArrayList<>();
                    SETTINGS_OPTION_DEFINITIONS.stream()
                            .filter(
                                    definition ->
                                            groupDefinition.key().equals(definition.groupKey()))
                            .forEach(
                                    definition -> {
                                        items.add(metadata(definition, groupDefinition));
                                        includedKeys.add(definition.key());
                                    });
                    if ("other".equals(groupDefinition.key())) {
                        optionByName.values().stream()
                                .filter(option -> !includedKeys.contains(option.getName()))
                                .filter(
                                        option ->
                                                !BRANDING_OPTION_FIELD_NAMES.containsKey(
                                                        option.getName()))
                                .forEach(
                                        option ->
                                                items.add(
                                                        metadata(
                                                                option,
                                                                groupDefinition,
                                                                values.get(option.getName()))));
                    }
                    groups.add(
                            new SettingsGroup(
                                    groupDefinition.key(),
                                    groupDefinition.label(),
                                    groupDefinition.description(),
                                    items));
                });
        return groups;
    }

    private List<SettingsCatalogItem> settingsCatalogItems(
            List<SettingsGroup> groups, Map<String, String> values) {
        List<SettingsCatalogItem> catalog = new ArrayList<>();
        groups.forEach(
                group ->
                        group.items()
                                .forEach(
                                        item -> {
                                            boolean legacyUnknown =
                                                    settingsDefinition(item.key()) == null
                                                            && !BRANDING_OPTION_FIELD_NAMES
                                                                    .containsKey(item.key());
                                            boolean sensitive =
                                                    isSensitiveSettingKey(
                                                                    item.key(),
                                                                    settingsDefinition(item.key()))
                                                            || item.sensitive();
                                            String currentValue =
                                                    StringUtils.defaultString(
                                                            values.get(item.key()));
                                            catalog.add(
                                                    new SettingsCatalogItem(
                                                            item.key(),
                                                            item.label(),
                                                            item.description(),
                                                            group.key(),
                                                            group.label(),
                                                            item.controlType(),
                                                            item.defaultValue(),
                                                            "默认值："
                                                                    + formatDefaultDescription(
                                                                            item.defaultValue()),
                                                            currentValue,
                                                            valueSummary(currentValue, sensitive),
                                                            sensitive,
                                                            item.requiresConfirm() || legacyUnknown,
                                                            legacyUnknown,
                                                            item.lastModifiedBy(),
                                                            item.lastModifiedAt()));
                                        }));
        return catalog;
    }

    private SettingsAuditSummary settingsAuditSummary(
            Map<String, String> nextValues,
            Map<String, String> beforeValues,
            UserEntity operator,
            LocalDateTime changedAt) {
        List<String> changedKeys =
                nextValues.entrySet().stream()
                        .filter(
                                entry ->
                                        !Objects.equals(
                                                entry.getValue(), beforeValues.get(entry.getKey())))
                        .map(Entry::getKey)
                        .toList();
        List<String> changes =
                changedKeys.stream()
                        .map(
                                key -> {
                                    SettingsOptionDefinition definition = settingsDefinition(key);
                                    boolean sensitive = isSensitiveSettingKey(key, definition);
                                    return key
                                            + "："
                                            + valueSummary(beforeValues.get(key), sensitive)
                                            + " -> "
                                            + valueSummary(nextValues.get(key), sensitive);
                                })
                        .toList();
        String changedAtText = formatDateTime(changedAt);
        String operatorName =
                StringUtils.defaultIfBlank(operator.getUsername(), operator.getScreenName());
        String summary =
                changes.isEmpty()
                        ? "未检测到配置值变化；操作人 " + operatorName
                        : String.join("；", changes) + "；操作人 " + operatorName;
        return new SettingsAuditSummary(
                "settings-audit-" + changedAt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                changedKeys,
                operatorName,
                changedAtText,
                summary,
                changes);
    }

    private SettingsOptionMetadata metadata(
            SettingsOptionDefinition definition, SettingsGroupDefinition groupDefinition) {
        return new SettingsOptionMetadata(
                definition.key(),
                definition.label(),
                definition.description(),
                groupDefinition.key(),
                groupDefinition.label(),
                definition.controlType(),
                definition.defaultValue(),
                definition.sensitive(),
                definition.requiresConfirm(),
                "",
                "");
    }

    private SettingsOptionMetadata metadata(
            OptionsEntity option, SettingsGroupDefinition groupDefinition, String value) {
        String controlType = StringUtils.defaultString(value).length() > 80 ? "textarea" : "text";
        boolean sensitive = isSensitiveSettingKey(option.getName(), null);
        return new SettingsOptionMetadata(
                option.getName(),
                StringUtils.defaultIfBlank(option.getDescription(), option.getName()),
                StringUtils.defaultIfBlank(option.getDescription(), "旧 options 表历史扩展配置。"),
                groupDefinition.key(),
                groupDefinition.label(),
                controlType,
                "",
                sensitive,
                true,
                "",
                "");
    }

    private SettingsOptionDefinition settingsDefinition(String key) {
        return SETTINGS_OPTION_DEFINITIONS.stream()
                .filter(definition -> definition.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private boolean isSensitiveSettingKey(String key, SettingsOptionDefinition definition) {
        if (definition != null && definition.sensitive()) {
            return true;
        }
        String normalized = StringUtils.defaultString(key).toLowerCase();
        return normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("private");
    }

    private String valueSummary(String value, boolean sensitive) {
        String text = StringUtils.defaultString(value);
        if (sensitive && StringUtils.isNotBlank(text)) {
            return "已隐藏敏感值（长度 " + text.length() + "）";
        }
        return StringUtils.isBlank(text) ? "空" : text;
    }

    private String formatDefaultDescription(String value) {
        return StringUtils.isBlank(value) ? "空" : value;
    }

    private Double parseOverlayOpacity(String value) {
        try {
            return Double.parseDouble(
                    StringUtils.defaultIfBlank(
                            value, BRANDING_DEFAULTS.get("loginOverlayOpacity")));
        } catch (NumberFormatException ignored) {
            return 0.48D;
        }
    }

    private String fileExtension(String originalFilename) {
        String name = StringUtils.defaultString(originalFilename).trim().toLowerCase();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            throw new IllegalArgumentException("无法识别背景文件格式");
        }
        return name.substring(dotIndex + 1);
    }

    private String mediaType(String extension) {
        if (ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            return "image";
        }
        if ("svg".equals(extension)) {
            return "image";
        }
        if (ALLOWED_VIDEO_EXTENSIONS.contains(extension)) {
            return "video";
        }
        throw new IllegalArgumentException("背景文件格式不支持，仅支持 jpg、png、webp、mp4、webm");
    }

    private void validateMediaContent(MultipartFile file, String extension, String mediaType)
            throws IOException {
        String contentType =
                StringUtils.substringBefore(
                                StringUtils.defaultString(file.getContentType()).toLowerCase(), ";")
                        .trim();
        if ("image".equals(mediaType) && !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("图片背景文件类型不正确");
        }
        if ("video".equals(mediaType)
                && !ALLOWED_VIDEO_MIME_TYPES
                        .getOrDefault(extension, Set.of())
                        .contains(contentType)) {
            throw new IllegalArgumentException("视频背景文件类型不正确");
        }
        byte[] header = file.getInputStream().readNBytes(16);
        if ("png".equals(extension)
                && !startsWith(header, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47})) {
            throw new IllegalArgumentException("PNG 背景文件内容不正确");
        }
        if (("jpg".equals(extension) || "jpeg".equals(extension))
                && !startsWith(header, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            throw new IllegalArgumentException("JPG 背景文件内容不正确");
        }
        if ("webp".equals(extension)
                && !(startsWith(header, "RIFF".getBytes())
                        && containsAt(header, "WEBP".getBytes(), 8))) {
            throw new IllegalArgumentException("WEBP 背景文件内容不正确");
        }
        if ("svg".equals(extension) && !looksLikeSvg(header)) {
            throw new IllegalArgumentException("SVG Logo 文件内容不正确");
        }
        if (("mp4".equals(extension) || "webm".equals(extension))
                && !looksLikeSupportedVideo(header, extension)) {
            throw new IllegalArgumentException("视频背景文件内容不正确");
        }
    }

    private boolean looksLikeSvg(byte[] header) {
        String start = new String(header, StandardCharsets.UTF_8).stripLeading().toLowerCase();
        return start.startsWith("<svg") || start.startsWith("<?xml") && start.contains("<svg");
    }

    private boolean looksLikeSupportedVideo(byte[] header, String extension) {
        if ("mp4".equals(extension)) {
            return containsAt(header, "ftyp".getBytes(), 4);
        }
        return startsWith(header, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAt(byte[] value, byte[] expected, int offset) {
        if (value.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private record SettingsSavePayload(
            Map<String, String> values,
            Set<String> confirmedSensitiveKeys,
            Set<String> confirmedLegacyKeys,
            Set<String> confirmedDangerousKeys) {}

    private record SettingsGroupDefinition(String key, String label, String description) {}

    private record SettingsOptionDefinition(
            String key,
            String label,
            String description,
            String groupKey,
            String controlType,
            String defaultValue,
            boolean sensitive,
            boolean requiresConfirm) {}
}

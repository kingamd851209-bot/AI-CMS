package com.xinhongru.api.admin.v2;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.api.admin.SiteManagementAdminApiController;
import com.xinhongru.cms.column.MenuRecognitionService;
import com.xinhongru.cms.i18n.MenuTranslationEngineRegistry;
import com.xinhongru.cms.i18n.TranslateOutcome;
import com.xinhongru.cms.template.TraditionalTemplatePackageService;
import com.xinhongru.cms.uipackage.UiPackageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.i18n.mapper.LanguageMapper;
import com.xinhongru.i18n.task.entity.I18nSiteTaskEntity;
import com.xinhongru.i18n.task.service.I18nSiteTaskService;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.AdminPermissionSnapshot;
import com.xinhongru.service.site.SiteHeadlessAuthService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextRequiredException;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.locale.SiteLocaleService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/admin/v2/sites")
public class SiteManagementV2ApiController {
    private static final Logger LOG = LoggerFactory.getLogger(SiteManagementV2ApiController.class);

    private final AiSiteProjectService projectService;
    private final SiteContextService siteContextService;
    private final OneShotMapper oneShotMapper;
    private SiteLocaleService siteLocaleService;
    private TraditionalTemplatePackageService traditionalTemplatePackageService;
    private UiPackageService uiPackageService;
    private MenuRecognitionService menuRecognitionService;
    private MenuTranslationEngineRegistry translationEngines;

    public SiteManagementV2ApiController(
            AiSiteProjectService projectService,
            SiteContextService siteContextService,
            OneShotMapper oneShotMapper) {
        this.projectService = projectService;
        this.siteContextService = siteContextService;
        this.oneShotMapper = oneShotMapper;
    }

    @Autowired
    public void setSiteLocaleService(SiteLocaleService siteLocaleService) {
        this.siteLocaleService = siteLocaleService;
    }

    private AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Autowired(required = false)
    public void setTraditionalTemplatePackageService(
            TraditionalTemplatePackageService traditionalTemplatePackageService) {
        this.traditionalTemplatePackageService = traditionalTemplatePackageService;
    }

    @Autowired
    public void setUiPackageService(UiPackageService uiPackageService) {
        this.uiPackageService = uiPackageService;
    }

    private I18nSiteTaskService i18nSiteTaskService;

    @Autowired(required = false)
    public void setI18nSiteTaskService(I18nSiteTaskService i18nSiteTaskService) {
        this.i18nSiteTaskService = i18nSiteTaskService;
    }

    @Autowired
    public void setMenuRecognitionService(MenuRecognitionService menuRecognitionService) {
        this.menuRecognitionService = menuRecognitionService;
    }

    @Autowired
    public void setTranslationEngines(MenuTranslationEngineRegistry translationEngines) {
        this.translationEngines = translationEngines;
    }

    private SiteHeadlessAuthService headlessAuthService;

    @Autowired(required = false)
    public void setHeadlessAuthService(SiteHeadlessAuthService headlessAuthService) {
        this.headlessAuthService = headlessAuthService;
    }

    @Autowired private LanguageMapper languageMapper;

    @GetMapping
    public AdminApiResponse<List<SiteManagementView>> sites(HttpServletRequest request) {
        AdminPermissionSnapshot snapshot = permissionService.snapshot(request);
        List<SiteProjectEntity> sites =
                snapshot.canAccessAllSites()
                        ? projectService.listSites()
                        : projectService.listSites(snapshot.accessibleSiteIds());
        return AdminApiResponse.success(sites.stream().map(SiteManagementView::from).toList());
    }

    @GetMapping("/{siteId}")
    public AdminApiResponse<SiteManagementView> site(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(SiteManagementView.from(projectService.getSite(siteId)));
    }

    @GetMapping("/{siteId}/locales")
    public AdminApiResponse<List<SiteLocaleService.LocaleView>> locales(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(localeService().list(siteId));
    }

    @GetMapping("/{siteId}/locale-options")
    public AdminApiResponse<List<SiteLocaleService.LanguageOption>> localeOptions(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(localeService().languageOptions());
    }

    @PostMapping("/{siteId}/locales")
    public AdminApiResponse<List<SiteLocaleService.LocaleView>> configureLocales(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SiteLocaleService.ConfigureCommand body) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(localeService().configure(siteId, body));
    }

    @PostMapping
    public AdminApiResponse<SiteManagementView> createSite(
            HttpServletRequest request, @RequestBody AiSiteProjectService.CreateSiteRequest body) {
        siteContextService.requireSuperAdmin(request);
        AiSiteProjectService.CreateSiteRequest safe =
                body == null
                        ? new AiSiteProjectService.CreateSiteRequest(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "CMS_MANAGED",
                                null,
                                null,
                                null)
                        : new AiSiteProjectService.CreateSiteRequest(
                                body.siteName(),
                                body.siteCode(),
                                body.siteDomain(),
                                body.sampleBaseUrl(),
                                body.defaultLanguage(),
                                body.templateEntryPath(),
                                body.siteDescription(),
                                body.siteMode(),
                                body.siteServiceIp(),
                                body.parentSiteId(),
                                body.siteServicePort());
        SiteProjectEntity created = projectService.createSite(safe);
        ensureHeadlessAuth(created);
        return AdminApiResponse.success(SiteManagementView.from(created));
    }

    /** 站点类型含 HEADLESS 时，确保已生成 Headless 接入认证（appKey/appSecret） */
    private void ensureHeadlessAuth(SiteProjectEntity site) {
        if (site == null || site.getId() == null || headlessAuthService == null) {
            return;
        }
        if (AiSiteProjectService.hasMode(site.getSiteMode(), "HEADLESS")) {
            headlessAuthService.ensure(site.getId());
        }
    }

    @DeleteMapping("/{siteId}")
    public AdminApiResponse<SiteManagementView> deleteSite(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        SiteProjectEntity deleted = projectService.deleteSite(siteId);
        siteContextService.clear(request);
        return AdminApiResponse.success(SiteManagementView.from(deleted));
    }

    @PutMapping("/{siteId}")
    public AdminApiResponse<SiteManagementView> updateSite(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody UpdateSiteRequest body) {
        siteContextService.requireSuperAdmin(request);
        AiSiteProjectService.UpdateSiteRequest serviceRequest =
                body == null
                        ? null
                        : new AiSiteProjectService.UpdateSiteRequest(
                                body.siteName(),
                                body.siteCode(),
                                body.siteDomain(),
                                body.siteMode(),
                                body.siteDescription(),
                                body.defaultLanguage(),
                                body.siteServiceIp(),
                                body.parentSiteId(),
                                body.siteServicePort());
        SiteProjectEntity updated = projectService.updateSite(siteId, serviceRequest);
        ensureHeadlessAuth(updated);
        return AdminApiResponse.success(SiteManagementView.from(updated));
    }

    @PostMapping("/{siteId}/switch-current")
    public AdminApiResponse<SiteContext> switchCurrent(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(siteContextService.switchTo(request, siteId));
    }

    @PostMapping("/{siteId}/bind-root")
    public AdminApiResponse<SiteManagementView> bindRoot(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SiteManagementAdminApiController.BindRootRequest body) {
        siteContextService.requireSuperAdmin(request);
        Integer rootMenuId = body == null ? null : body.rootMenuId();
        return AdminApiResponse.success(
                SiteManagementView.from(projectService.bindRootMenu(siteId, rootMenuId)));
    }

    @PostMapping("/{siteId}/publish-directory")
    public AdminApiResponse<SiteManagementView> updatePublishDirectory(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody PublishDirectoryRequest body) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(
                SiteManagementView.from(
                        projectService.updatePublishDirectory(
                                siteId, body == null ? null : body.publishDirectory())));
    }

    @GetMapping("/{siteId}/template-package")
    public AdminApiResponse<TemplatePackageView> templatePackage(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(TemplatePackageView.from(projectService.getSite(siteId)));
    }

    @PostMapping({"/{siteId}/traditional-template-package", "/{siteId}/template-package"})
    public AdminApiResponse<
                    TraditionalTemplatePackageService.TraditionalTemplatePackageUploadResult>
            uploadTraditionalTemplatePackage(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestParam("file") MultipartFile file)
                    throws IOException {
        siteContextService.requireSuperAdmin(request);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP file is required");
        }
        return AdminApiResponse.success(
                traditionalTemplatePackageService()
                        .uploadTraditionalTemplatePackage(
                                siteId, file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping({
        "/{siteId}/traditional-template-package/clear",
        "/{siteId}/template-package/clear"
    })
    public AdminApiResponse<SiteManagementView> clearTraditionalTemplatePackage(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(
                SiteManagementView.from(
                        traditionalTemplatePackageService()
                                .clearTraditionalTemplatePackage(siteId)));
    }

    // ==================== UIPackage Management ====================

    @GetMapping("/{siteId}/ui-package")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> getUiPackageStatus(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().getUiPackageStatus(siteId));
    }

    @PostMapping("/{siteId}/ui-package")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> uploadUiPackage(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam("file") MultipartFile file) {
        siteContextService.requireSuperAdmin(request);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP 文件不能为空");
        }
        try {
            UserEntity user = TaleUtils.getLoginUser(request);
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            return AdminApiResponse.success(
                    uiPackageService()
                            .uploadUiPackage(
                                    siteId, filename, bytes, loginUserId(user), displayName(user)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 上传失败: " + e.getMessage(), e);
        }
    }

    /** 升级 UIPackage：版本号不变，原地覆盖现有版本目录中的同名文件 */
    @PostMapping("/{siteId}/ui-package/upgrade")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> upgradeUiPackage(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam("file") MultipartFile file) {
        siteContextService.requireSuperAdmin(request);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP 文件不能为空");
        }
        try {
            UserEntity user = TaleUtils.getLoginUser(request);
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            return AdminApiResponse.success(
                    uiPackageService()
                            .upgradeUiPackage(
                                    siteId, filename, bytes, loginUserId(user), displayName(user)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 升级失败: " + e.getMessage(), e);
        }
    }

    /** 重新上传 UIPackage：生成新版本，旧包整体被替换 */
    @PostMapping("/{siteId}/ui-package/reupload")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> reuploadUiPackage(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam("file") MultipartFile file) {
        siteContextService.requireSuperAdmin(request);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP 文件不能为空");
        }
        try {
            UserEntity user = TaleUtils.getLoginUser(request);
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            return AdminApiResponse.success(
                    uiPackageService()
                            .reuploadUiPackage(
                                    siteId, filename, bytes, loginUserId(user), displayName(user)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 重新上传失败: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{siteId}/ui-package/upload-records")
    public AdminApiResponse<List<UiPackageService.UiPackageUploadRecordView>>
            listUiPackageUploadRecords(HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().listUploadRecords(siteId));
    }

    private Long loginUserId(UserEntity user) {
        return user == null || user.getId() == null ? null : user.getId().longValue();
    }

    private String displayName(UserEntity user) {
        if (user == null) {
            return null;
        }
        String screenName = user.getScreenName();
        return screenName != null && !screenName.isBlank() ? screenName : user.getUsername();
    }

    @DeleteMapping("/{siteId}/ui-package")
    public AdminApiResponse<Void> deleteUiPackage(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        uiPackageService().deleteUiPackage(siteId);
        return AdminApiResponse.success(null);
    }

    @PostMapping("/{siteId}/ui-package/start")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> startUiPackage(
            HttpServletRequest request, @PathVariable Long siteId) {
        // 普通用户组（对该站点有访问权限即可）也能启动 UIPackage 服务
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().startService(siteId));
    }

    @PostMapping("/{siteId}/ui-package/stop")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> stopUiPackage(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.requireSuperAdmin(request);
        return AdminApiResponse.success(uiPackageService().stopService(siteId));
    }

    @PutMapping("/{siteId}/ui-package/port")
    public AdminApiResponse<UiPackageService.UiPackageStatusView> setUiPackagePort(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SetUiPackagePortRequest body) {
        siteContextService.requireSuperAdmin(request);
        if (body == null || body.port() == null) {
            throw new IllegalArgumentException("请指定端口");
        }
        return AdminApiResponse.success(uiPackageService().setPort(siteId, body.port()));
    }

    @GetMapping("/{siteId}/ui-package/files")
    public AdminApiResponse<List<UiPackageStorageService.FileInfo>> listUiPackageFiles(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().listUiPackageFiles(siteId));
    }

    @GetMapping("/{siteId}/ui-package/access-url")
    public AdminApiResponse<String> getUiPackageAccessUrl(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().getAccessUrl(siteId));
    }

    @GetMapping("/{siteId}/ui-package/file-content")
    public AdminApiResponse<String> getUiPackageFileContent(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam String path) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(uiPackageService().getFileContent(siteId, path));
    }

    // ==================== Menu Recognition ====================

    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.GONE)
    @PostMapping("/{siteId}/recognize-columns")
    public AdminApiResponse<MenuRecognitionService.RecognitionResult> recognizeColumns(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody RecognizeColumnsRequest body) {
        siteContextService.assertCanAccessSite(request, siteId);
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @GetMapping("/{siteId}/existing-columns")
    public AdminApiResponse<MenuRecognitionService.ExistingMenusView> getExistingColumns(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        return AdminApiResponse.success(menuRecognitionService().getExistingMenus(siteId));
    }

    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.GONE)
    @PostMapping("/{siteId}/save-recognized-columns")
    public AdminApiResponse<MenuRecognitionService.SaveResult> saveRecognizedColumns(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SaveRecognizedColumnsRequest body) {
        siteContextService.requireSuperAdmin(request);
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    private MenuRecognitionService menuRecognitionService() {
        if (menuRecognitionService == null) {
            throw new IllegalStateException("栏目识别服务未初始化");
        }
        return menuRecognitionService;
    }

    public record RecognizeColumnsRequest(String filePath, String additionalInstructions) {}

    public record SetUiPackagePortRequest(Integer port) {}

    public record SaveRecognizedColumnsRequest(List<MenuRecognitionService.RecognizedMenu> menus) {}

    private SiteLocaleService localeService() {
        if (siteLocaleService == null) {
            throw new IllegalStateException("站点语言服务未初始化");
        }
        return siteLocaleService;
    }

    private TraditionalTemplatePackageService traditionalTemplatePackageService() {
        if (traditionalTemplatePackageService == null) {
            throw new IllegalStateException("传统模板包服务未初始化");
        }
        return traditionalTemplatePackageService;
    }

    private UiPackageService uiPackageService() {
        if (uiPackageService == null) {
            throw new IllegalStateException("UIPackage 服务未初始化");
        }
        return uiPackageService;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, message(ex, "站点管理请求无效"), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdminApiResponse<>(409, message(ex, "站点当前状态不允许该操作"), null));
    }

    @ExceptionHandler(SiteContextRequiredException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleSiteContextRequired(
            SiteContextRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, message(ex, "请先选择站点"), null));
    }

    @ExceptionHandler(SiteAccessDeniedException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleAccessDenied(SiteAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AdminApiResponse<>(403, message(ex, "无权访问该站点"), null));
    }

    private String message(RuntimeException ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    // ==================== I18n Site Generation ====================

    @GetMapping("/{siteId}/menu-tree")
    public AdminApiResponse<List<MenuTreeNode>> getMenuTree(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        SiteProjectEntity site = projectService.getSite(siteId);
        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId == null) {
            return AdminApiResponse.success(List.of());
        }
        List<OneshotEntity> menus = oneShotMapper.selectBySiteRoot(rootMenuId);
        if (menus == null || menus.isEmpty()) {
            OneshotEntity rootMenu = oneShotMapper.getModelById(rootMenuId);
            if (rootMenu != null && !Boolean.TRUE.equals(rootMenu.getIsDeleted())) {
                return AdminApiResponse.success(
                        List.of(
                                new MenuTreeNode(
                                        rootMenu.getId(), 0, rootMenu.getClassName(), List.of())));
            }
            return AdminApiResponse.success(List.of());
        }
        List<MenuTreeNode> tree = buildMenuTree(menus, rootMenuId);
        return AdminApiResponse.success(tree);
    }

    @PostMapping("/{siteId}/translate-menus-preview")
    public AdminApiResponse<TranslateMenusPreviewResponse> translateMenusPreview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TranslateMenusPreviewRequest body) {
        siteContextService.assertCanAccessSite(request, siteId);

        if (body == null || body.targetLanguage() == null || body.targetLanguage().isBlank()) {
            throw new IllegalArgumentException("目标语言不能为空");
        }
        if (body.menuIds() == null || body.menuIds().isEmpty()) {
            return AdminApiResponse.success(
                    new TranslateMenusPreviewResponse(List.of(), null, null));
        }

        // 获取选中的菜单
        List<OneshotEntity> menus = new ArrayList<>();
        for (Integer menuId : body.menuIds()) {
            OneshotEntity menu = oneShotMapper.getModelById(menuId);
            if (menu != null && !Boolean.TRUE.equals(menu.getIsDeleted())) {
                menus.add(menu);
            }
        }

        if (menus.isEmpty()) {
            return AdminApiResponse.success(
                    new TranslateMenusPreviewResponse(List.of(), null, null));
        }

        // 翻译（按引擎路由；同时保留 prompt 与 AI 原始响应，供前端查看）
        TranslateOutcome outcome =
                translationEngines.of(body.engine()).translate(menus, body.targetLanguage());
        Map<Integer, String> translations = outcome.translations();

        // 构建结果
        List<TranslatedMenuPreview> result = new ArrayList<>();
        for (OneshotEntity menu : menus) {
            String translatedName = translations.get(menu.getId());
            if (translatedName == null || translatedName.isBlank()) {
                translatedName = menu.getClassName();
            }
            result.add(
                    new TranslatedMenuPreview(menu.getId(), menu.getClassName(), translatedName));
        }

        return AdminApiResponse.success(
                new TranslateMenusPreviewResponse(result, outcome.prompt(), outcome.rawResponse()));
    }

    /** 只读返回翻译 prompt（不调用 AI），供前端 agent 引擎经 OpenCode 代理执行。 */
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.GONE)
    @PostMapping("/{siteId}/translate-menus-preview/prompt")
    public AdminApiResponse<Map<String, String>> translateMenusPreviewPrompt(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TranslateMenusPreviewRequest body) {
        siteContextService.assertCanAccessSite(request, siteId);
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @PostMapping("/{siteId}/generate-i18n-site")
    public AdminApiResponse<I18nSiteGenerationResult> generateI18nSite(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody GenerateI18nSiteRequest body) {
        siteContextService.requireSuperAdmin(request);

        if (body == null || body.targetLanguage() == null || body.targetLanguage().isBlank()) {
            throw new IllegalArgumentException("目标语言不能为空");
        }
        if (body.siteName() == null || body.siteName().isBlank()) {
            throw new IllegalArgumentException("站点名称不能为空");
        }
        if (body.siteCode() == null || body.siteCode().isBlank()) {
            throw new IllegalArgumentException("站点编码不能为空");
        }
        if (body.selectedMenuIds() == null || body.selectedMenuIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个栏目");
        }

        SiteProjectEntity sourceSite = projectService.getSite(siteId);
        Integer sourceRootMenuId = sourceSite.getRootMenuId();
        if (sourceRootMenuId == null) {
            throw new IllegalArgumentException("源站点未绑定根栏目");
        }

        // 获取源站点的所有栏目
        List<OneshotEntity> allMenus = oneShotMapper.selectBySiteRoot(sourceRootMenuId);
        if (allMenus == null || allMenus.isEmpty()) {
            OneshotEntity rootMenu = oneShotMapper.getModelById(sourceRootMenuId);
            if (rootMenu != null && !Boolean.TRUE.equals(rootMenu.getIsDeleted())) {
                allMenus = new ArrayList<>();
                allMenus.add(rootMenu);
            } else {
                throw new IllegalArgumentException("源站点没有栏目");
            }
        }

        // 创建新站点
        AiSiteProjectService.CreateSiteRequest createRequest =
                new AiSiteProjectService.CreateSiteRequest(
                        body.siteName(),
                        body.siteCode(),
                        body.siteDomain(),
                        null,
                        body.targetLanguage(),
                        null,
                        sourceSite.getSiteDescription(),
                        sourceSite.getSiteMode(),
                        body.siteServiceIp(),
                        null,
                        body.siteServicePort());

        // 筛选出选中的栏目
        Set<Integer> selectedIdSet = new HashSet<>(body.selectedMenuIds());
        List<OneshotEntity> selectedMenus =
                allMenus.stream()
                        .filter(menu -> selectedIdSet.contains(menu.getId()))
                        .filter(menu -> !Boolean.TRUE.equals(menu.getIsDeleted()))
                        .toList();

        // 翻译选中栏目的名称：优先复用前端翻译预览结果，仅未覆盖的栏目才调用 AI
        Map<Integer, String> translations = new HashMap<>();
        if (body.menuTranslations() != null) {
            for (MenuTranslation mt : body.menuTranslations()) {
                if (mt != null
                        && mt.menuId() != null
                        && mt.translatedName() != null
                        && !mt.translatedName().isBlank()) {
                    translations.put(mt.menuId(), mt.translatedName());
                }
            }
        }
        List<OneshotEntity> untranslatedMenus =
                selectedMenus.stream()
                        .filter(menu -> !translations.containsKey(menu.getId()))
                        .toList();
        if (!untranslatedMenus.isEmpty()) {
            translations.putAll(
                    translationEngines
                            .of(body.engine())
                            .translate(untranslatedMenus, body.targetLanguage())
                            .translations());
        }

        // Translation must succeed before creating any persistent site state.
        SiteProjectEntity newSite = projectService.createSite(createRequest);

        // 复制栏目到新站点
        Map<Integer, Integer> idMapping = new HashMap<>(); // 旧ID -> 新ID
        int copiedCount = 0;

        // 先复制根栏目
        OneshotEntity sourceRoot = oneShotMapper.getModelById(sourceRootMenuId);
        if (sourceRoot == null || Boolean.TRUE.equals(sourceRoot.getIsDeleted())) {
            throw new IllegalArgumentException("源站点根栏目不存在或已删除");
        }

        // 根节点使用新站点编码作为名称
        String rootName = body.siteCode();

        OneshotEntity newRoot =
                new OneshotEntity()
                        .setSiteProjectId(newSite.getId())
                        .setSiteRootId(null) // 稍后更新
                        .setParentId(0)
                        .setClassName(rootName)
                        .setClassEnName(body.siteCode()) // 使用新的站点编码
                        .setHasManageChannel(sourceRoot.getHasManageChannel())
                        .setHasFrontChannel(sourceRoot.getHasFrontChannel())
                        .setIsSubMenu(sourceRoot.getIsSubMenu())
                        .setIsCanDeleted(sourceRoot.getIsCanDeleted())
                        .setIsCanAddSub(sourceRoot.getIsCanAddSub())
                        .setIsCanEdit(sourceRoot.getIsCanEdit())
                        .setIsCanSort(sourceRoot.getIsCanSort())
                        .setIsCanShowHidden(sourceRoot.getIsCanShowHidden())
                        .setIsShowCrumbs(sourceRoot.getIsShowCrumbs())
                        .setIsLoop(sourceRoot.getIsLoop())
                        .setIsSystem(false)
                        .setIsDeleted(false)
                        .setPageType(sourceRoot.getPageType())
                        .setSort(sourceRoot.getSort())
                        .setCreateTime(new Date());

        if (sourceRoot.getBizCode() != null && !sourceRoot.getBizCode().isBlank()) {
            // 需求：新站点栏目沿用原站点的业务编码，便于按 (bizCode, siteId) 定位对应栏目
            newRoot.setBizCode(sourceRoot.getBizCode());
        }
        oneShotMapper.insertSiteRootMenu(newRoot);
        // 源栏目没有业务编码时才退回取自身 id
        if (newRoot.getBizCode() == null || newRoot.getBizCode().isBlank()) {
            oneShotMapper.assignBizcodeToOwnId(newRoot.getId());
        }
        idMapping.put(sourceRootMenuId, newRoot.getId());
        copiedCount++;

        // 更新根栏目的 siteRootId
        newRoot.setSiteRootId(newRoot.getId());
        oneShotMapper.updateSiteScopeByRoot(newSite.getId(), newRoot.getId());

        // 复制子栏目（按层级顺序）
        Map<Integer, List<OneshotEntity>> menusByParent =
                allMenus.stream()
                        .filter(m -> !m.getId().equals(sourceRootMenuId)) // 排除根栏目
                        .collect(Collectors.groupingBy(OneshotEntity::getParentId));

        copyChildMenus(
                sourceRootMenuId,
                newRoot.getId(),
                newSite.getId(),
                menusByParent,
                idMapping,
                translations,
                selectedIdSet);
        copiedCount = idMapping.size();

        // 绑定根栏目到新站点
        projectService.bindRootMenu(newSite.getId(), newRoot.getId());

        // 生成一条多语言站点生成主任务（当前仅站点与栏目已生成，模型/模版/内容/静态资源待同步）
        Long taskId = createGenerationTask(request, sourceSite, newSite, idMapping);

        return AdminApiResponse.success(
                new I18nSiteGenerationResult(
                        newSite.getId(),
                        newSite.getProjectName(),
                        newSite.getProjectCode(),
                        copiedCount,
                        taskId));
    }

    /** 生成多语言站点后创建主任务与明细，返回主任务ID */
    private Long createGenerationTask(
            HttpServletRequest request,
            SiteProjectEntity sourceSite,
            SiteProjectEntity newSite,
            Map<Integer, Integer> idMapping) {
        if (i18nSiteTaskService == null) {
            return null;
        }
        UserEntity user = TaleUtils.getLoginUser(request);
        I18nSiteTaskEntity task =
                new I18nSiteTaskEntity()
                        .setSourceSiteId(sourceSite.getId())
                        .setSourceSiteName(sourceSite.getProjectName())
                        .setSourceLanguage(sourceSite.getDefaultLanguage())
                        .setTargetSiteId(newSite.getId())
                        .setTargetSiteName(newSite.getProjectName())
                        .setTargetLanguage(newSite.getDefaultLanguage())
                        .setColumnStatus(I18nSiteTaskEntity.STATUS_SUCCESS)
                        .setOperatorId(user == null ? null : user.getId().longValue())
                        .setOperatorName(user == null ? null : user.getUsername());
        return i18nSiteTaskService.createTask(task, idMapping);
    }

    private void copyChildMenus(
            Integer sourceParentId,
            Integer newParentId,
            Long newSiteId,
            Map<Integer, List<OneshotEntity>> menusByParent,
            Map<Integer, Integer> idMapping,
            Map<Integer, String> translations,
            Set<Integer> selectedIdSet) {
        List<OneshotEntity> children = menusByParent.get(sourceParentId);
        if (children == null || children.isEmpty()) {
            return;
        }

        for (OneshotEntity child : children) {
            if (Boolean.TRUE.equals(child.getIsDeleted())) {
                continue;
            }

            // 只复制选中的栏目及其父级链
            if (!selectedIdSet.contains(child.getId())
                    && !hasSelectedDescendant(child.getId(), menusByParent, selectedIdSet)) {
                continue;
            }

            String translatedName = translations.get(child.getId());
            if (translatedName == null || translatedName.isBlank()) {
                translatedName = child.getClassName();
            }

            OneshotEntity newMenu =
                    new OneshotEntity()
                            .setSiteProjectId(newSiteId)
                            .setSiteRootId(
                                    idMapping.values().stream().findFirst().orElse(newParentId))
                            .setParentId(newParentId)
                            .setClassName(translatedName)
                            .setClassEnName(child.getClassEnName())
                            .setHasManageChannel(child.getHasManageChannel())
                            .setHasFrontChannel(child.getHasFrontChannel())
                            .setIsSubMenu(child.getIsSubMenu())
                            .setIsCanDeleted(child.getIsCanDeleted())
                            .setIsCanAddSub(child.getIsCanAddSub())
                            .setIsCanEdit(child.getIsCanEdit())
                            .setIsCanSort(child.getIsCanSort())
                            .setIsCanShowHidden(child.getIsCanShowHidden())
                            .setIsShowCrumbs(child.getIsShowCrumbs())
                            .setIsLoop(child.getIsLoop())
                            .setIsSystem(false)
                            .setIsDeleted(false)
                            .setPageType(child.getPageType())
                            .setSort(child.getSort())
                            .setCreateTime(new Date())
                            .setMenuField(child.getMenuField())
                            .setModelField(child.getModelField())
                            .setModelList(child.getModelList())
                            .setListPageUrl(child.getListPageUrl())
                            .setListPageTemplate(child.getListPageTemplate())
                            .setDetailPageUrl(child.getDetailPageUrl())
                            .setDetailPageTemplate(child.getDetailPageTemplate())
                            .setSeoTitle(child.getSeoTitle())
                            .setSeoKeywords(child.getSeoKeywords())
                            .setSeoDescription(child.getSeoDescription());

            if (child.getBizCode() != null && !child.getBizCode().isBlank()) {
                // 需求：新站点栏目沿用原站点的业务编码
                newMenu.setBizCode(child.getBizCode());
            }
            oneShotMapper.insertSiteRootMenu(newMenu);
            // 源栏目没有业务编码时才退回取自身 id
            if (newMenu.getBizCode() == null || newMenu.getBizCode().isBlank()) {
                oneShotMapper.assignBizcodeToOwnId(newMenu.getId());
            }
            idMapping.put(child.getId(), newMenu.getId());

            // 递归复制子栏目
            copyChildMenus(
                    child.getId(),
                    newMenu.getId(),
                    newSiteId,
                    menusByParent,
                    idMapping,
                    translations,
                    selectedIdSet);
        }
    }

    private boolean hasSelectedDescendant(
            Integer menuId,
            Map<Integer, List<OneshotEntity>> menusByParent,
            Set<Integer> selectedIdSet) {
        List<OneshotEntity> children = menusByParent.get(menuId);
        if (children == null || children.isEmpty()) {
            return false;
        }
        for (OneshotEntity child : children) {
            if (selectedIdSet.contains(child.getId())) {
                return true;
            }
            if (hasSelectedDescendant(child.getId(), menusByParent, selectedIdSet)) {
                return true;
            }
        }
        return false;
    }

    private List<MenuTreeNode> buildMenuTree(List<OneshotEntity> menus, Integer rootMenuId) {
        Map<Integer, List<OneshotEntity>> byParent =
                menus.stream().collect(Collectors.groupingBy(OneshotEntity::getParentId));

        OneshotEntity rootMenu = oneShotMapper.getModelById(rootMenuId);
        String rootName = rootMenu != null ? rootMenu.getClassName() : "根栏目";

        MenuTreeNode root = new MenuTreeNode(rootMenuId, 0, rootName, new ArrayList<>());
        buildTreeRecursive(root, byParent);
        return List.of(root);
    }

    private void buildTreeRecursive(
            MenuTreeNode parent, Map<Integer, List<OneshotEntity>> byParent) {
        List<OneshotEntity> children = byParent.get(parent.id());
        if (children == null || children.isEmpty()) {
            return;
        }
        List<MenuTreeNode> childNodes = new ArrayList<>();
        for (OneshotEntity child : children) {
            if (Boolean.TRUE.equals(child.getIsDeleted())) {
                continue;
            }
            MenuTreeNode childNode =
                    new MenuTreeNode(
                            child.getId(),
                            child.getParentId(),
                            child.getClassName(),
                            new ArrayList<>());
            buildTreeRecursive(childNode, byParent);
            childNodes.add(childNode);
        }
        parent.children().addAll(childNodes);
    }

    public record MenuTreeNode(
            Integer id, Integer parentId, String className, List<MenuTreeNode> children) {}

    public record GenerateI18nSiteRequest(
            String targetLanguage,
            String siteName,
            String siteCode,
            String siteDomain,
            Integer siteServicePort,
            String siteServiceIp,
            List<Integer> selectedMenuIds,
            List<MenuTranslation> menuTranslations,
            String engine) {}

    /** 前端翻译预览得到的栏目译文，生成时复用避免重复调用 AI */
    public record MenuTranslation(Integer menuId, String translatedName) {}

    public record I18nSiteGenerationResult(
            Long siteId, String siteName, String siteCode, int translatedMenuCount, Long taskId) {}

    public record TranslateMenusPreviewRequest(
            String targetLanguage, List<Integer> menuIds, String engine) {}

    public record TranslatedMenuPreview(
            Integer menuId, String originalName, String translatedName) {}

    public record TranslateMenusPreviewResponse(
            List<TranslatedMenuPreview> items, String prompt, String rawResponse) {}

    public record PublishDirectoryRequest(String publishDirectory) {}

    public record UpdateSiteRequest(
            String siteName,
            String siteCode,
            String siteDomain,
            String siteMode,
            String siteDescription,
            String defaultLanguage,
            String siteServiceIp,
            Long parentSiteId,
            Integer siteServicePort) {}

    public record SiteManagementView(
            Long siteId,
            Long parentSiteId,
            String projectName,
            String projectCode,
            String siteDomain,
            String publishDirectory,
            Integer rootMenuId,
            TemplatePackageView templatePackage,
            Long lastRecognitionBatchId,
            String applyStatus,
            String previewStatus,
            String status,
            Long currentBatchId,
            String siteMode,
            String siteDescription,
            String defaultLanguage,
            String uiPackageStatus,
            LocalDateTime createTime,
            String siteServiceIp,
            Integer siteServicePort) {
        static SiteManagementView from(SiteProjectEntity site) {
            return new SiteManagementView(
                    site.getId(),
                    site.getParentId(),
                    site.getProjectName(),
                    site.getProjectCode(),
                    site.getSiteDomain(),
                    site.getPublishDirectory(),
                    site.getRootMenuId(),
                    TemplatePackageView.from(site),
                    site.getLastRecognitionBatchId(),
                    site.getApplyStatus(),
                    site.getPreviewStatus(),
                    site.getStatus(),
                    site.getCurrentBatchId(),
                    site.getSiteMode() == null || site.getSiteMode().isBlank()
                            ? "CMS_MANAGED"
                            : site.getSiteMode(),
                    site.getSiteDescription(),
                    site.getDefaultLanguage(),
                    site.getUiPackageStatus() == null ? "NOT_UPLOADED" : site.getUiPackageStatus(),
                    site.getCreateTime(),
                    site.getSiteServiceIp(),
                    site.getSiteServicePort());
        }
    }

    public record TemplatePackageView(
            String status, String name, Long currentBatchId, Long lastRecognitionBatchId) {
        static TemplatePackageView from(SiteProjectEntity site) {
            return new TemplatePackageView(
                    site.getTraditionalTemplatePackageVersion() == null
                            ? "NOT_UPLOADED"
                            : "UPLOADED",
                    site.getTraditionalTemplatePackageName(),
                    null,
                    null);
        }
    }

    // ==================== Site Logs ====================

    @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}")
    private String uiPackageRoot;

    @GetMapping("/{siteId}/logs")
    public AdminApiResponse<List<LogFileView>> listSiteLogs(
            HttpServletRequest request, @PathVariable Long siteId) {
        siteContextService.assertCanAccessSite(request, siteId);
        SiteProjectEntity site = projectService.getSite(siteId);
        String projectCode = site.getProjectCode();
        if (projectCode == null || projectCode.isBlank()) {
            return AdminApiResponse.success(List.of());
        }

        Path logsDir = Paths.get(uiPackageRoot, projectCode, "logs");
        if (!Files.exists(logsDir) || !Files.isDirectory(logsDir)) {
            return AdminApiResponse.success(List.of());
        }

        try (Stream<Path> files = Files.list(logsDir)) {
            List<LogFileView> logFiles =
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".log"))
                            .map(
                                    p -> {
                                        try {
                                            return new LogFileView(
                                                    p.getFileName().toString(),
                                                    Files.size(p),
                                                    Files.getLastModifiedTime(p).toMillis());
                                        } catch (IOException e) {
                                            return null;
                                        }
                                    })
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingLong(LogFileView::lastModified).reversed())
                            .toList();
            return AdminApiResponse.success(logFiles);
        } catch (IOException e) {
            throw new IllegalStateException("读取日志目录失败: " + e.getMessage());
        }
    }

    @GetMapping("/{siteId}/logs/{filename}")
    public AdminApiResponse<LogContentView> getSiteLogContent(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable String filename,
            @RequestParam(defaultValue = "100") int lines,
            @RequestParam(defaultValue = "0") long offset) {
        siteContextService.assertCanAccessSite(request, siteId);
        SiteProjectEntity site = projectService.getSite(siteId);
        String projectCode = site.getProjectCode();
        if (projectCode == null || projectCode.isBlank()) {
            throw new IllegalArgumentException("站点编码未配置");
        }

        // 安全检查：防止路径遍历攻击
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("非法文件名");
        }

        Path logFile = Paths.get(uiPackageRoot, projectCode, "logs", filename);
        if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) {
            throw new IllegalArgumentException("日志文件不存在: " + filename);
        }

        try {
            long fileSize = Files.size(logFile);
            long lastModified = Files.getLastModifiedTime(logFile).toMillis();

            List<String> contentLines;
            if (offset == 0) {
                contentLines = readLastLines(logFile, lines);
            } else {
                contentLines = readLinesFromOffset(logFile, offset, lines);
            }

            return AdminApiResponse.success(
                    new LogContentView(
                            filename,
                            fileSize,
                            lastModified,
                            contentLines,
                            offset == 0 ? Math.max(0, fileSize - 50000) : offset));
        } catch (IOException e) {
            throw new IllegalStateException("读取日志文件失败: " + e.getMessage());
        }
    }

    private List<String> readLastLines(Path file, int lines) throws IOException {
        List<String> result = new ArrayList<>();
        long fileLength = Files.size(file);
        if (fileLength == 0) {
            return result;
        }

        // 读取文件末尾的字节（最多 64KB）
        int bufferSize = (int) Math.min(fileLength, 65536);
        byte[] buffer = new byte[bufferSize];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(fileLength - bufferSize);
            raf.readFully(buffer);
        }

        // 如果不是从文件开头开始，跳过第一行（可能不完整）
        int start = 0;
        if (bufferSize < fileLength) {
            for (int i = 0; i < buffer.length; i++) {
                if (buffer[i] == '\n') {
                    start = i + 1;
                    break;
                }
            }
        }

        // 按 UTF-8 解码并按行分割
        String content = new String(buffer, start, buffer.length - start, StandardCharsets.UTF_8);
        String[] allLines = content.split("\n");
        int totalLines = allLines.length;

        // 取最后 lines 行
        int fromIndex = Math.max(0, totalLines - lines);
        for (int i = fromIndex; i < totalLines; i++) {
            String line = allLines[i];
            // 去掉行尾的 \r
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            result.add(line);
        }
        return result;
    }

    private List<String> readLinesFromOffset(Path file, long offset, int lines) throws IOException {
        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[(int) Math.min(65536, raf.length() - offset)];
            raf.readFully(buffer);
            String content = new String(buffer, StandardCharsets.UTF_8);
            String[] allLines = content.split("\n");
            for (int i = 0; i < allLines.length && result.size() < lines; i++) {
                String line = allLines[i];
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                result.add(line);
            }
        }
        return result;
    }

    public record LogFileView(String name, long size, long lastModified) {}

    public record LogContentView(
            String filename,
            long fileSize,
            long lastModified,
            List<String> lines,
            long nextOffset) {}
}

package com.xinhongru.api.admin.v2;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.cms.content.record.ContentRecordService;
import com.xinhongru.cms.content.record.NativeContentAdminQueryService;
import com.xinhongru.cms.i18n.ContentTranslateOutcome;
import com.xinhongru.cms.i18n.ContentTranslationItem;
import com.xinhongru.cms.i18n.ContentTranslationService;
import com.xinhongru.cms.template.TraditionalTemplatePackageService;
import com.xinhongru.common.page.PageResult;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.CheckRecordEntity;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.ContentHistoryEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;
import com.xinhongru.requestDto.model.OptionDto;
import com.xinhongru.requestDto.oneshot.OneshotSwitchDto;
import com.xinhongru.requestDto.oneshot.OneshotTitleDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.builderHtml.BuilderHtmlVo;
import com.xinhongru.responseVo.builderHtml.NodeVo;
import com.xinhongru.responseVo.model.ModelManageSeoVo;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.responseVo.oneshot.SeoDto;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.service.CheckRecordService;
import com.xinhongru.service.ContentHistoryService;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.service.fieldsetting.FieldSettingService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.CmsResourceContextService;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.context.SiteResourceGuard;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.utils.TaleUtils;
import com.xinhongru.web.select.SelectQuerySyntax;
import com.xinhongru.web.service.MenuService;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/v2/cms")
public class CmsCoreV2ApiController {
    private static final Pattern MENU_NAME_HTML_TAG = Pattern.compile("<[^>]+>");

    private static final Set<String> LEGACY_FIELD_TYPES =
            Set.of(
                    "SingleLine",
                    "MultiLine",
                    "Editor",
                    "Content",
                    "Html",
                    "Note",
                    "DateTime",
                    "Date",
                    "Time",
                    "Int",
                    "Number",
                    "Enum",
                    "Image",
                    "File",
                    "Video",
                    "Media");
    private final OneShotService oneShotService;
    private final MenuService menuService;
    private final ContentService contentService;
    private final ContentMapper contentMapper;
    private final ContentHistoryService contentHistoryService;
    private final CheckRecordService checkRecordService;

    private AdminPermissionService permissionService;
    private OneShotMapper oneShotMapper;
    private static final int SINGLE_PAGE_STATUS_DRAFT = 0;
    private static final int SINGLE_PAGE_STATUS_APPROVED = 2;
    private static final int SINGLE_PAGE_STATUS_PUBLISHED = 3;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    private final FieldSettingService fieldSettingService;
    private final SiteContextService siteContextService;
    private CmsResourceContextService cmsResourceContextService;
    private SiteResourceGuard siteResourceGuard;
    private final AiSiteProjectService projectService;
    private TraditionalTemplatePackageService traditionalTemplatePackageService;
    private NativeContentAdminQueryService nativeContentAdminQueryService;
    private ContentRecordService nativeContentRecordService;

    @Autowired private com.xinhongru.security.upload.ResourceHtml extensionResourceHtml;
    @Autowired private com.xinhongru.cms.content.record.SinglePageFieldValues singlePageFieldValues;

    @Autowired
    private com.xinhongru.security.upload.ResourceReferenceResolver extensionResourceResolver;

    @Autowired
    private com.xinhongru.cms.content.record.ContentExtensionResources extensionResources;

    private ContentTranslationService contentTranslationService;

    public CmsCoreV2ApiController(
            OneShotService oneShotService,
            MenuService menuService,
            ContentService contentService,
            ContentMapper contentMapper,
            ContentHistoryService contentHistoryService,
            CheckRecordService checkRecordService,
            FieldSettingService fieldSettingService,
            SiteContextService siteContextService) {
        this(
                oneShotService,
                menuService,
                contentService,
                contentMapper,
                contentHistoryService,
                checkRecordService,
                fieldSettingService,
                siteContextService,
                null);
    }

    @Autowired
    public CmsCoreV2ApiController(
            OneShotService oneShotService,
            MenuService menuService,
            ContentService contentService,
            ContentMapper contentMapper,
            ContentHistoryService contentHistoryService,
            CheckRecordService checkRecordService,
            FieldSettingService fieldSettingService,
            SiteContextService siteContextService,
            AiSiteProjectService projectService) {
        this.oneShotService = oneShotService;
        this.menuService = menuService;
        this.contentService = contentService;
        this.contentMapper = contentMapper;
        this.contentHistoryService = contentHistoryService;
        this.checkRecordService = checkRecordService;
        this.fieldSettingService = fieldSettingService;
        this.siteContextService = siteContextService;
        this.projectService = projectService;
    }

    @Autowired(required = false)
    public void setSiteResourceGuard(SiteResourceGuard siteResourceGuard) {
        this.siteResourceGuard = siteResourceGuard;
    }

    @Autowired(required = false)
    public void setCmsResourceContextService(CmsResourceContextService cmsResourceContextService) {
        this.cmsResourceContextService = cmsResourceContextService;
    }

    @Autowired(required = false)
    public void setTraditionalTemplatePackageService(
            TraditionalTemplatePackageService traditionalTemplatePackageService) {
        this.traditionalTemplatePackageService = traditionalTemplatePackageService;
    }

    @Autowired(required = false)
    public void setNativeContentAdminQueryService(
            NativeContentAdminQueryService nativeContentAdminQueryService) {
        this.nativeContentAdminQueryService = nativeContentAdminQueryService;
    }

    @Autowired(required = false)
    public void setNativeContentRecordService(ContentRecordService nativeContentRecordService) {
        this.nativeContentRecordService = nativeContentRecordService;
    }

    @Autowired(required = false)
    public void setContentTranslationService(ContentTranslationService contentTranslationService) {
        this.contentTranslationService = contentTranslationService;
    }

    private ContentTranslationService contentTranslation() {
        if (contentTranslationService == null) {
            throw new IllegalStateException("内容翻译服务未配置");
        }
        return contentTranslationService;
    }

    private NativeContentAdminQueryService nativeContentQueries() {
        if (nativeContentAdminQueryService == null) {
            throw new IllegalStateException("NativeContentAdminQueryService 未配置");
        }
        return nativeContentAdminQueryService;
    }

    private ContentRecordService nativeContentRecords() {
        if (nativeContentRecordService == null) throw new IllegalStateException("原生内容保存服务未配置");
        return nativeContentRecordService;
    }

    @GetMapping("/menus")
    public AdminApiResponse<?> menus(
            HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean contentOnly,
            @RequestParam(required = false) Integer parentId,
            @RequestParam(required = false) Long siteId) {
        SiteSummary site = resolveSite(request, siteId);
        // 懒加载模式：只返回指定父节点的直接子节点
        if (parentId != null) {
            return AdminApiResponse.success(getChildMenus(request, site, parentId, contentOnly));
        }
        // 全量加载模式：返回完整的树结构（向后兼容）
        // 显式指定站点时直接按该站点栏目返回，避免被当前登录站点上下文过滤
        List<MenuNode> flat =
                (siteId != null && !contentOnly)
                        ? scopedMenusForSite(site)
                        : (contentOnly
                                ? scopedContentMenus(request, site)
                                : scopedConfigMenus(request, site));
        return AdminApiResponse.success(new MenuWorkspaceResponse(site, flat, buildTree(flat)));
    }

    /** 按站点 ID 直接取栏目导航（不经过当前登录站点上下文过滤，用于跨站映射等场景）。 */
    private List<MenuNode> scopedMenusForSite(SiteSummary site) {
        if (site.siteId() == null) {
            return List.of();
        }
        List<OneshotEntity> menus = oneShotService.getAllMenuBySiteProjectId(site.siteId());
        List<MenuNode> flat =
                (menus == null ? List.<OneshotEntity>of() : menus)
                        .stream().map(MenuNode::from).toList();
        return annotateNavigationMenus(site, contentMenusForSite(site, flat));
    }

    private List<MenuNode> getChildMenus(
            HttpServletRequest request, SiteSummary site, Integer parentId, boolean contentOnly) {
        List<OneshotEntity> allMenus =
                contentOnly
                        ? oneShotService.getAllContentMenu(new OneshotDto(0), request)
                        : oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request);
        if (allMenus == null) allMenus = List.of();

        // 过滤出当前站点的菜单
        List<MenuNode> scopedMenus =
                allMenus.stream()
                        .filter(m -> belongsToCurrentSite(m, site))
                        .map(MenuNode::from)
                        .toList();

        // 收集所有父节点ID，用于判断是否有子节点
        Set<Integer> parentIds =
                scopedMenus.stream()
                        .map(m -> m.parentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        // 找出 parentId 下的直接子节点，并设置 hasChildren
        return scopedMenus.stream()
                .filter(m -> Objects.equals(m.parentId, parentId))
                .map(
                        m ->
                                new MenuNode(
                                        m.id(),
                                        m.parentId(),
                                        m.className(),
                                        m.classEnName(),
                                        m.parentName(),
                                        m.sort(),
                                        m.pageType(),
                                        m.hasManageChannel(),
                                        m.hasFrontChannel(),
                                        m.isSystem(),
                                        m.isCanDeleted(),
                                        m.isCanEdit(),
                                        m.isCanSort(),
                                        m.url(),
                                        m.listPageTemplate(),
                                        m.detailPageTemplate(),
                                        m.siteProjectId(),
                                        m.siteRootId(),
                                        m.bizCode(),
                                        m.manageable(),
                                        m.manageReason(),
                                        m.contentRoute(),
                                        m.contentManageable(),
                                        m.navigationOnly(),
                                        m.siteScoped(),
                                        m.invalidReason(),
                                        parentIds.contains(m.id()), // hasChildren
                                        null, // children 为 null 表示未加载
                                        m.isSubMenu(),
                                        m.listPageUrl(),
                                        m.detailPageUrl()))
                .toList();
    }

    private boolean belongsToCurrentSite(OneshotEntity menu, SiteSummary site) {
        if (site == null || site.rootMenuId() == null) return true;
        Integer siteRootId = site.rootMenuId();
        return Objects.equals(menu.getSiteRootId(), siteRootId)
                || Objects.equals(menu.getId(), siteRootId);
    }

    @GetMapping("/native-content")
    public AdminApiResponse<NativeContentAdminQueryService.NativeContentListResponse>
            nativeContentList(
                    HttpServletRequest request,
                    @RequestParam Long menuId,
                    @RequestParam(required = false) List<String> statuses,
                    @RequestParam(required = false) String variantState,
                    @RequestParam(required = false) String keyword,
                    @RequestParam(required = false) String groupField,
                    @RequestParam(required = false) String groupValue,
                    @RequestParam(required = false) List<Integer> seedIds,
                    @RequestParam(defaultValue = "1") int page,
                    @RequestParam(defaultValue = "20") int limit) {
        long siteId = nativeSiteId(request);
        if (groupField != null || groupValue != null || seedIds != null) {
            requireMenuPermission(request, Math.toIntExact(menuId));
        }
        Map<String, String> fieldFilters = collectFieldFilters(request);
        return AdminApiResponse.success(
                nativeContentQueries()
                        .list(
                                siteId,
                                menuId,
                                statuses,
                                variantState,
                                keyword,
                                fieldFilters,
                                groupField,
                                groupValue,
                                seedIds,
                                page,
                                limit));
    }

    @GetMapping("/native-content/groups")
    public AdminApiResponse<List<ContentMapper.NativeContentGroup>> nativeContentGroups(
            HttpServletRequest request,
            @RequestParam Long menuId,
            @RequestParam String groupField) {
        long siteId = nativeSiteId(request);
        requireMenuPermission(request, Math.toIntExact(menuId));
        return AdminApiResponse.success(
                nativeContentQueries().contentGroups(siteId, menuId, groupField));
    }

    @PostMapping("/native-content/groups/assign")
    public AdminApiResponse<ContentRecordService.GroupAssignmentResult> assignNativeContentGroup(
            HttpServletRequest request, @RequestBody AssignNativeContentGroupRequest body) {
        if (body == null || body.menuId() == null || body.menuId() <= 0)
            throw new IllegalArgumentException("请选择有效的内容栏目");
        long siteId = nativeSiteId(request);
        requireCurrentSiteMenu(request, body.menuId());
        requireCmsOperation(request, body.menuId(), WebConst.UPDATE_AUTHORITY_ID);
        try {
            return AdminApiResponse.success(
                    nativeContentRecords()
                            .assignDraftGroup(
                                    siteId,
                                    body.menuId(),
                                    body.groupField(),
                                    body.groupValue(),
                                    body.records(),
                                    actorId(request)));
        } catch (ContentRecordService.DraftGroupConflict ex) {
            throw AdminSecurityException.conflict("CMS_DRAFT_GROUP_CONFLICT", ex.getMessage());
        }
    }

    public record AssignNativeContentGroupRequest(
            Integer menuId,
            String groupField,
            String groupValue,
            List<ContentRecordService.GroupAssignmentRow> records) {}

    /** 收集 modelSearch 动态搜索字段的过滤参数（排除既有保留参数）。 */
    private Map<String, String> collectFieldFilters(HttpServletRequest request) {
        Map<String, String> filters = new LinkedHashMap<>();
        Set<String> reserved =
                Set.of(
                        "menuId",
                        "languageId",
                        "statuses",
                        "variantState",
                        "keyword",
                        "groupField",
                        "groupValue",
                        "seedIds",
                        "page",
                        "limit");
        for (Enumeration<String> names = request.getParameterNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            if (reserved.contains(name)) continue;
            String value = request.getParameter(name);
            if (value != null
                    && !value.isEmpty()
                    && (ContentExtensionFields.isName(name)
                            || ((name.startsWith("min_") || name.startsWith("max_"))
                                    && ContentExtensionFields.isName(name.substring(4)))
                            || !value.isBlank())) {
                filters.put(name, value);
            }
        }
        return filters;
    }

    @GetMapping("/native-content/editor")
    public AdminApiResponse<NativeContentAdminQueryService.NativeContentEditorDetail>
            nativeContentEditor(
                    HttpServletRequest request,
                    @RequestParam Long menuId,
                    @RequestParam Long languageId,
                    @RequestParam(required = false) Long recordId,
                    @RequestParam(required = false) Long modelVersionId,
                    @RequestParam(required = false) String modelKey) {
        long siteId = nativeSiteId(request);
        return AdminApiResponse.success(
                nativeContentQueries()
                        .editor(siteId, menuId, languageId, recordId, modelVersionId, modelKey));
    }

    @PostMapping("/native-content/drafts")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AdminApiResponse<ContentRecordService.ContentMutationResult> saveNativeDraft(
            HttpServletRequest request, @RequestBody SaveNativeDraftRequest body) {
        if (body == null || body.menuId() == null)
            throw new IllegalArgumentException("menuId is required");
        long siteId = nativeSiteId(request);
        OneshotEntity menu = requireCurrentSiteMenu(request, Math.toIntExact(body.menuId()));
        requireCmsOperation(
                request,
                menu.getId(),
                body.recordId() == null || body.recordId() == 0
                        ? WebConst.ADD_AUTHORITY_ID
                        : WebConst.UPDATE_AUTHORITY_ID);
        menu = lockFieldConfiguration(request, menu.getId());
        Map<String, Object> changedValues =
                new java.util.LinkedHashMap<>(nullSafe(body.sharedPayload()));
        changedValues.putAll(nullSafe(body.payload()));
        Set<String> changedFields = changedValues.keySet();
        if (changedFields.contains("xgid")) requireNativeOwnerPermission(request, siteId, menu);
        requireReferenceSourcePermission(
                request,
                siteId,
                com.xinhongru.content.model.ContentModelCodec.read(menu.getModelField()).stream()
                        .filter(f -> changedFields.contains(f.getFieldName()))
                        .toList(),
                changedValues);
        return AdminApiResponse.success(
                nativeContentRecords()
                        .saveDraft(
                                new ContentRecordService.SaveDraftCommand(
                                        siteId,
                                        body.menuId(),
                                        body.recordId(),
                                        nullSafe(body.sharedPayload()),
                                        nullSafe(body.payload()),
                                        actorId(request),
                                        body.rowVersion())));
    }

    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.GONE)
    @PostMapping("/native-content/translate-preview/prompt")
    public AdminApiResponse<Map<String, String>> contentTranslatePrompt(
            HttpServletRequest request, @RequestBody ContentTranslatePreviewRequest body) {
        validateContentTranslateRequest(body);
        long siteId = nativeSiteId(request);
        requireManageableContentMenu(request, Math.toIntExact(body.menuId()));
        return new AdminApiResponse<>(410, "该开发入口已停用", null);
    }

    @PostMapping("/native-content/translate-preview")
    public AdminApiResponse<ContentTranslatePreviewResponse> contentTranslatePreview(
            HttpServletRequest request, @RequestBody ContentTranslatePreviewRequest body) {
        validateContentTranslateRequest(body);
        long siteId = nativeSiteId(request);
        requireManageableContentMenu(request, Math.toIntExact(body.menuId()));
        ContentTranslateOutcome outcome =
                contentTranslation()
                        .translate(
                                siteId,
                                body.menuId(),
                                body.languageId(),
                                body.recordId(),
                                body.fieldKeys(),
                                body.targetLanguage(),
                                body.engine());
        return AdminApiResponse.success(
                new ContentTranslatePreviewResponse(
                        outcome.items(), outcome.prompt(), outcome.rawResponse()));
    }

    private void validateContentTranslateRequest(ContentTranslatePreviewRequest body) {
        if (body == null || body.menuId() == null || body.menuId() <= 0) {
            throw new IllegalArgumentException("menuId is required");
        }
        if (body.recordId() == null || body.recordId() <= 0) {
            throw new IllegalArgumentException("recordId is required");
        }
        if (body.languageId() == null || body.languageId() <= 0) {
            throw new IllegalArgumentException("languageId is required");
        }
        if (body.targetLanguage() == null || body.targetLanguage().isBlank()) {
            throw new IllegalArgumentException("目标语言不能为空");
        }
        if (body.fieldKeys() == null || body.fieldKeys().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个字段");
        }
    }

    public record ContentTranslatePreviewRequest(
            Long menuId,
            Long recordId,
            Long languageId,
            String targetLanguage,
            String engine,
            List<String> fieldKeys) {}

    public record ContentTranslatePreviewResponse(
            List<ContentTranslationItem> items, String prompt, String rawResponse) {}

    private Map<String, Object> nullSafe(Map<String, Object> map) {
        return map != null ? map : Map.of();
    }

    // Request DTOs for native content endpoints
    public record SaveNativeDraftRequest(
            Long recordId,
            Long menuId,
            Map<String, Object> sharedPayload,
            Map<String, Object> payload,
            String rowVersion) {}

    // TODO: cms_content_record 退役后端点已删除
    @GetMapping("/native-content/select-options")
    public AdminApiResponse<List<SelectOptionItem>> nativeContentSelectOptions(
            HttpServletRequest request,
            @RequestParam Long menuId,
            @RequestParam String query,
            @RequestParam(required = false) String parentValue) {
        long siteId = nativeSiteId(request);
        return AdminApiResponse.success(
                resolveDynamicSelectQuery(
                        siteId,
                        Math.toIntExact(menuId),
                        query,
                        parentValue,
                        id -> {
                            requireSelectionMenu(siteId, id);
                            requireMenuPermission(request, id);
                        }));
    }

    List<SelectOptionItem> resolveDynamicSelectQuery(Long siteId, Integer menuId, String query) {
        return resolveDynamicSelectQuery(siteId, menuId, query, null);
    }

    List<SelectOptionItem> resolveDynamicSelectQuery(
            Long siteId, Integer menuId, String query, String parentValue) {
        return resolveDynamicSelectQuery(
                siteId, menuId, query, parentValue, id -> requireSelectionMenu(siteId, id));
    }

    private void requireReferenceSourcePermission(
            HttpServletRequest request, Long siteId, List<ContentFieldDefinition> fields) {
        if (fields == null) return;
        for (ContentFieldDefinition field : fields) {
            if (!com.xinhongru.content.model.ContentReferences.applies(field)
                    || !ContentFieldDefinition.enabled(field.getIsOpen())) continue;
            for (Integer sourceId : com.xinhongru.content.model.ContentReferences.menuIds(field)) {
                requireSelectionMenu(siteId, sourceId);
                requireMenuPermission(request, sourceId);
            }
        }
    }

    private void requireReferenceSourcePermission(
            HttpServletRequest request,
            Long siteId,
            List<ContentFieldDefinition> fields,
            Map<String, Object> values) {
        if (fields == null || values == null) return;
        requireReferenceSourcePermission(
                request,
                siteId,
                fields.stream()
                        .filter(field -> values.containsKey(field.getFieldName()))
                        .filter(field -> ContentFieldDefinition.enabled(field.getIsOpen()))
                        .filter(field -> !ContentFieldDefinition.enabled(field.getIsReadOnly()))
                        .filter(com.xinhongru.content.model.ContentReferences::applies)
                        .filter(
                                field ->
                                        !com.xinhongru.content.model.ContentReferences.ids(
                                                        field, values.get(field.getFieldName()))
                                                .isEmpty())
                        .toList());
    }

    private void requireSelectionMenu(Long siteId, Integer menuId) {
        if (siteId == null || siteId <= 0 || menuId == null || menuId <= 0)
            throw new IllegalArgumentException("栏目不属于当前站点");
        OneshotEntity menu = oneShotService.getModelById(menuId);
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
            throw AdminSecurityException.forbidden("RESOURCE_SITE_MISMATCH", "栏目不属于当前站点");
    }

    private void requireNativeOwnerPermission(
            HttpServletRequest request, Long siteId, OneshotEntity menu) {
        if (!Boolean.TRUE.equals(menu.getIsSubMenu())) return;
        requireSelectionMenu(siteId, menu.getParentId());
        requireMenuPermission(request, menu.getParentId());
    }

    private List<SelectOptionItem> resolveDynamicSelectQuery(
            Long siteId,
            Integer menuId,
            String query,
            String parentValue,
            java.util.function.Consumer<Integer> requireAccess) {
        requireAccess.accept(menuId);
        if (query == null || !query.startsWith("{$") || !query.endsWith("$}")) {
            return List.of();
        }
        // {$<父栏目id,...>_children$} → 父栏目的全部直接子栏目并集作为选项
        List<Integer> childrenParentIds = SelectQuerySyntax.parseChildrenParentIds(query);
        if (!childrenParentIds.isEmpty()) {
            return childrenMenuOptions(childrenParentIds, requireAccess);
        }
        String inner = query.substring(2, query.length() - 2);
        String[] parts = inner.split("_");
        List<Integer> classIdList = new ArrayList<>();
        String titleField = "title";
        String valueField = "title";
        boolean isMenuSelect = false;

        // 解析语法：{$classId_titleField_valueField$}, {$classId_titleField_valueField_maxSelect$},
        // {$parentId_child_identifies$}, {$parentId_child_identifies_maxSelect$}
        if (parts.length > 2 && "child".equals(parts[1])) {
            // {$parentId_child_identifies$} 或 {$parentId_child_identifies_maxSelect$}
            requireAccess.accept(Integer.valueOf(parts[0]));
            List<OneshotEntity> menuList =
                    menuService.getOneshotListByParentIdAndIdentifies(
                            Integer.valueOf(parts[0]), parts.length > 3 ? parts[2] : parts[1]);
            for (OneshotEntity menu : menuList) {
                classIdList.add(menu.getId());
            }
            titleField = "className";
            valueField = "id";
            isMenuSelect = true;
        } else if (parts.length > 1 && inner.contains("child_")) {
            // {$child_identifies$}
            List<OneshotEntity> menuList =
                    menuService.getOneshotListByParentIdAndIdentifies(menuId, parts[1]);
            for (OneshotEntity menu : menuList) {
                classIdList.add(menu.getId());
            }
            titleField = "className";
            valueField = "id";
            isMenuSelect = true;
        } else if (parts.length > 2) {
            // {$classId_titleField_valueField$} 或 {$classId,classId2_titleField_valueField$}
            addClassIds(classIdList, parts[0]);
            titleField = parts[1];
            valueField = parts[2];
        } else if (parts.length > 1) {
            // {$classId_titleField$} 或 {$classId,classId2_titleField$}
            addClassIds(classIdList, parts[0]);
            titleField = parts[1];
        } else if (parts.length > 0) {
            // {$classId$} 或 {$classId,classId2$}
            addClassIds(classIdList, parts[0]);
        }

        if (classIdList.isEmpty()) {
            return List.of();
        }

        classIdList.forEach(requireAccess);
        List<SelectOptionItem> result = new ArrayList<>();
        if (isMenuSelect) {
            // 栏目选择
            for (Integer classId : classIdList) {
                OneshotEntity menu = menuService.getModelById(classId);
                if (menu != null) {
                    result.add(
                            new SelectOptionItem(
                                    String.valueOf(menu.getId()), menu.getClassName(), null));
                }
            }
        } else {
            // 内容选择：涉及多个栏目时按来源栏目分组（group = 栏目名称）
            List<ContentEntity> contentList =
                    contentService.getArticleByClassIdForSelect(classIdList);
            // 级联过滤：仅保留 xgid ∈ 所选上级内容 id 集合的内容（内容 xgid 指向其所属上级内容；上级可多选，逗号分隔）
            if (parentValue != null && !parentValue.isBlank()) {
                Set<String> parentIds =
                        Arrays.stream(parentValue.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toSet());
                contentList =
                        contentList.stream()
                                .filter(c -> parentIds.contains(String.valueOf(c.getXgid())))
                                .toList();
            }
            boolean grouped = classIdList.size() > 1;
            Map<Integer, String> menuNames = new LinkedHashMap<>();
            if (grouped) {
                for (Integer classId : classIdList) {
                    OneshotEntity menu = menuService.getModelById(classId);
                    if (menu != null) {
                        menuNames.put(classId, menu.getClassName());
                    }
                }
            }
            for (ContentEntity content : contentList) {
                if (!classIdList.contains(content.getClassid())
                        || (content.getSiteProjectId() != null
                                && !Objects.equals(content.getSiteProjectId(), siteId)))
                    throw AdminSecurityException.forbidden("RESOURCE_SITE_MISMATCH", "内容不属于当前站点栏目");
                String title = getFieldValue(content, titleField);
                String value = getFieldValue(content, valueField);
                String group = grouped ? menuNames.get(content.getClassid()) : null;
                result.add(new SelectOptionItem(value, title, group));
            }
        }
        return result;
    }

    /** 构建「多个父栏目的全部直接子栏目并集」下拉选项（按出现顺序去重）：值=子栏目 id，label=子栏目名。 */
    List<SelectOptionItem> childrenMenuOptions(Long siteId, List<Integer> parentIds) {
        return childrenMenuOptions(parentIds, id -> requireSelectionMenu(siteId, id));
    }

    private List<SelectOptionItem> childrenMenuOptions(
            List<Integer> parentIds, java.util.function.Consumer<Integer> requireAccess) {
        parentIds.forEach(requireAccess);
        List<SelectOptionItem> result = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Integer parentId : parentIds) {
            for (OneshotEntity menu : menuService.getOneshotListByParentId(parentId)) {
                if (menu.getId() != null && seen.add(menu.getId())) {
                    requireAccess.accept(menu.getId());
                    result.add(
                            new SelectOptionItem(
                                    String.valueOf(menu.getId()), menu.getClassName(), null));
                }
            }
        }
        return result;
    }

    /** 解析逗号分隔的栏目 id 片段（如 "101,102,103"）并追加到目标列表，忽略非法片段。 */
    private void addClassIds(List<Integer> target, String csv) {
        if (csv == null) {
            return;
        }
        for (String id : csv.split(",")) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                target.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 非法栏目 id 片段，跳过
            }
        }
    }

    private String getFieldValue(ContentEntity content, String fieldName) {
        if ("title".equalsIgnoreCase(fieldName)) {
            return content.getTitle() != null ? content.getTitle() : "";
        } else if ("id".equalsIgnoreCase(fieldName)) {
            return content.getId() != null ? String.valueOf(content.getId()) : "";
        }
        // 默认返回 title
        return content.getTitle() != null ? content.getTitle() : "";
    }

    public record SelectOptionItem(String value, String label, String group) {}

    // TODO: cms_content_record 退役后端点已删除
    @GetMapping("/navigation")
    public AdminApiResponse<ContentNavigationResponse> navigation(HttpServletRequest request) {
        SiteSummary site = currentSite(request);
        if (site.globalMode() || site.rootMenuId() == null) {
            return AdminApiResponse.success(
                    new ContentNavigationResponse(null, null, site, List.of(), List.of()));
        }
        List<MenuNode> flat =
                oneShotService.getAllContentMenu(new OneshotDto(0), request).stream()
                        .map(MenuNode::from)
                        .toList();
        List<MenuNode> scoped = annotateNavigationMenus(site, contentMenusForSite(site, flat));
        return AdminApiResponse.success(
                new ContentNavigationResponse(
                        site.siteId(), site.rootMenuId(), site, scoped, buildTree(scoped)));
    }

    @GetMapping("/templates/overview")
    public AdminApiResponse<TemplateWorkspaceResponse> templateOverview(
            HttpServletRequest request) {
        SiteSummary site = currentSite(request);
        List<OneshotEntity> menus = scopedTemplateMenus(request, site);
        List<MenuNode> flat =
                annotateNavigationMenus(site, menus.stream().map(MenuNode::from).toList());
        Set<Integer> menuIds =
                flat.stream()
                        .map(MenuNode::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<NodeVo> builderNodes =
                oneShotService.getBuilderNodes(request).stream()
                        .filter(node -> node.getId() != null && menuIds.contains(node.getId()))
                        .toList();
        return AdminApiResponse.success(
                new TemplateWorkspaceResponse(
                        site,
                        flat,
                        buildTree(flat),
                        builderNodes,
                        templateBindingSummaries(menus)));
    }

    @PostMapping("/templates/publish-tasks")
    public AdminApiResponse<Void> createPublishTask(
            HttpServletRequest request, @RequestBody BuilderHtmlVo body) {
        requireSuccess(oneShotService.builderHtmlTask(request, body));
        return AdminApiResponse.success();
    }

    @GetMapping("/menus/{id}")
    public AdminApiResponse<MenuDetail> menu(HttpServletRequest request, @PathVariable Integer id) {
        OneshotEntity menu = requireCurrentSiteMenu(request, id);
        // 查询是否有子节点
        List<OneshotEntity> allMenus =
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request);
        boolean hasChildren =
                allMenus != null
                        && allMenus.stream().anyMatch(m -> Objects.equals(m.getParentId(), id));
        return AdminApiResponse.success(MenuDetail.from(menu, hasChildren));
    }

    @PostMapping("/menus")
    public AdminApiResponse<MenuDetail> saveMenu(
            HttpServletRequest request, @RequestBody EditOneshotDto body) {
        EditOneshotDto dto = normalizeMenu(body);
        validateTraditionalTemplatePaths(
                request, dto.getListPageTemplate(), dto.getDetailPageTemplate());
        ResultVO<?> result = oneShotService.updateEdit(dto, request);
        requireSuccess(result);
        Integer id =
                dto.getId() == null || dto.getId() <= 0 ? asInteger(result.getData()) : dto.getId();
        return AdminApiResponse.success(MenuDetail.from(requireMenu(id)));
    }

    @PostMapping("/menus/{id}/title")
    public AdminApiResponse<MenuDetail> updateMenuTitle(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody TitleRequest body) {
        requireCurrentSiteMenu(request, id);
        requireSuccess(oneShotService.updateTitle(new OneshotTitleDto(id, body.value()), request));
        return AdminApiResponse.success(MenuDetail.from(requireCurrentSiteMenu(request, id)));
    }

    @PostMapping("/menus/{id}/sort")
    public AdminApiResponse<MenuDetail> updateMenuSort(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody SortRequest body) {
        requireMenu(id);
        requireSuccess(oneShotService.updateSort(new SortDto(id, body.sort()), request));
        return AdminApiResponse.success(MenuDetail.from(requireMenu(id)));
    }

    @PostMapping("/menus/{id}/switch")
    public AdminApiResponse<MenuDetail> updateMenuSwitch(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody SwitchRequest body) {
        requireMenu(id);
        requireSuccess(
                oneShotService.updateSwitch(new OneshotSwitchDto(id, body.fieldName()), request));
        return AdminApiResponse.success(MenuDetail.from(requireMenu(id)));
    }

    @GetMapping("/single/{menuId}")
    public AdminApiResponse<SinglePageDetailResponse> singlePageDetail(
            HttpServletRequest request, @PathVariable Integer menuId) {
        OneshotEntity menu = requireManageableSinglePageMenu(request, menuId);
        List<ContentFieldDefinition> fields = parseSortedMenuFields(menu.getMenuField());
        SinglePageRow row = SinglePageRow.from(menu, fields);
        return AdminApiResponse.success(
                new SinglePageDetailResponse(
                        MenuDetail.from(menu), fields, row, singlePageStatus(menu)));
    }

    @PostMapping("/single/{menuId}")
    @org.springframework.transaction.annotation.Transactional
    public AdminApiResponse<SinglePageDetailResponse> saveSinglePage(
            HttpServletRequest request,
            @PathVariable Integer menuId,
            @RequestBody SinglePageSaveRequest body) {
        OneshotEntity menu = requireManageableSinglePageMenu(request, menuId);
        menu = lockFieldConfiguration(request, menuId);
        requireCmsOperation(request, menuId, WebConst.UPDATE_AUTHORITY_ID);
        MenuNode lockedMenu = MenuNode.from(menu);
        if (!isManageChannelEnabled(lockedMenu) || !isSinglePageEntry(lockedMenu))
            throw new IllegalArgumentException("目标栏目已不是可维护单页，请重新读取");
        List<ContentFieldDefinition> fields = parseSortedMenuFields(menu.getMenuField());
        Map<String, Object> values = body == null ? Map.of() : body.values();
        requireReferenceSourcePermission(
                request,
                menu.getSiteProjectId(),
                fields.stream()
                        .filter(f -> values != null && values.containsKey(f.getFieldName()))
                        .toList(),
                values);
        Long currentSiteId = siteContextService.requireCurrentSiteId(request);
        if (!Objects.equals(menu.getSiteProjectId(), currentSiteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        OneshotEntity update = new OneshotEntity().setId(menu.getId());
        update.setExtensionFields(menu.getExtensionFields());
        singlePageFieldValues.apply(update, menu, fields, values, currentSiteId);
        requireSuccess(oneShotService.update(update, request));

        OneshotEntity responseMenu = new OneshotEntity();
        BeanUtils.copyProperties(menu, responseMenu);
        singlePageFieldValues.apply(responseMenu, fields, values, currentSiteId);
        SinglePageRow row = SinglePageRow.from(responseMenu, fields);
        return AdminApiResponse.success(
                new SinglePageDetailResponse(
                        MenuDetail.from(responseMenu),
                        fields,
                        row,
                        singlePageStatus(responseMenu)));
    }

    /** 单页：审核通过（草稿/待审 → 已审核） */
    @PostMapping("/single/{menuId}/audit")
    @org.springframework.transaction.annotation.Transactional
    public AdminApiResponse<SinglePageDetailResponse> auditSinglePage(
            HttpServletRequest request, @PathVariable Integer menuId) {
        requireManageableSinglePageMenu(request, menuId);
        requireCmsOperation(request, menuId, WebConst.CHECK_AUTHORITY_ID);
        OneshotEntity menu = lockFieldConfiguration(request, menuId);
        if (Integer.valueOf(SINGLE_PAGE_STATUS_PUBLISHED).equals(menu.getStatus())) {
            throw new IllegalArgumentException("已发布的单页请先取消发布");
        }
        oneShotMapper.updateStatus(menuId, SINGLE_PAGE_STATUS_APPROVED);
        return singlePageResponse(requireMenu(menuId));
    }

    /** 单页：发布（草稿/已审核 → 已发布） */
    @PostMapping("/single/{menuId}/publish")
    @org.springframework.transaction.annotation.Transactional
    public AdminApiResponse<SinglePageDetailResponse> publishSinglePage(
            HttpServletRequest request, @PathVariable Integer menuId) {
        requireManageableSinglePageMenu(request, menuId);
        requireCmsOperation(request, menuId, WebConst.APPROVAL_AUTHORITY_ID);
        lockFieldConfiguration(request, menuId);
        oneShotMapper.updateStatus(menuId, SINGLE_PAGE_STATUS_PUBLISHED);
        return singlePageResponse(requireMenu(menuId));
    }

    /** 单页：取消发布（已发布 → 草稿） */
    @PostMapping("/single/{menuId}/unpublish")
    public AdminApiResponse<SinglePageDetailResponse> unpublishSinglePage(
            HttpServletRequest request, @PathVariable Integer menuId) {
        requireManageableSinglePageMenu(request, menuId);
        requireCmsOperation(request, menuId, WebConst.APPROVAL_AUTHORITY_ID);
        oneShotService.withdrawSinglePage(siteContextService.requireCurrentSiteId(request), menuId);
        return singlePageResponse(requireMenu(menuId));
    }

    /** 单页：弹窗审核（接收审核状态与备注，写审核记录） */
    @PostMapping("/single/{menuId}/audit-v2")
    @org.springframework.transaction.annotation.Transactional
    public AdminApiResponse<SinglePageDetailResponse> auditSinglePageV2(
            HttpServletRequest request,
            @PathVariable Integer menuId,
            @RequestBody SinglePageAuditRequest body) {
        requireManageableSinglePageMenu(request, menuId);
        requireCmsOperation(request, menuId, WebConst.CHECK_AUTHORITY_ID);
        Integer checkStatus = body == null ? null : body.checkStatus();
        if (checkStatus == null || checkStatus < 0 || checkStatus > 3) {
            throw new IllegalArgumentException("审核状态无效，必须为 0-3");
        }
        String checkDescription = StringUtils.trimToNull(body.checkDescription());
        OneshotEntity menu = lockFieldConfiguration(request, menuId);
        // 状态机：0待审批→待审(1) 1通过→已发布(3) 2未通过/3作废→草稿(0)
        int targetStatus =
                switch (checkStatus) {
                    case 0 -> 1;
                    case 1 -> SINGLE_PAGE_STATUS_PUBLISHED;
                    default -> SINGLE_PAGE_STATUS_DRAFT;
                };
        if (Integer.valueOf(SINGLE_PAGE_STATUS_PUBLISHED).equals(menu.getStatus())
                && targetStatus != SINGLE_PAGE_STATUS_DRAFT) {
            throw new IllegalArgumentException("已发布的单页请先取消发布");
        }
        if (targetStatus == SINGLE_PAGE_STATUS_DRAFT) {
            oneShotService.withdrawSinglePage(menu.getSiteProjectId(), menuId);
        } else {
            oneShotMapper.updateStatus(menuId, targetStatus);
        }

        UserEntity loginUser = TaleUtils.getLoginUser(request);
        CheckRecordEntity record = new CheckRecordEntity();
        record.setContentId(menuId);
        record.setContentTitle(menu.getClassName());
        record.setSubmitMenuId(menuId);
        record.setSubmitMenuName(menu.getClassName());
        record.setCheckStatus(checkStatus);
        record.setCheckDescription(checkDescription);
        record.setCheckTime(LocalDateTime.now());
        record.setSubmitTime(LocalDateTime.now());
        if (loginUser != null) {
            record.setSubmitUserId(loginUser.getId());
            record.setSubmitUserName(loginUser.getScreenName());
            record.setCheckUserId(loginUser.getId());
            record.setCheckUserName(loginUser.getScreenName());
        }
        checkRecordService.save(record);
        return singlePageResponse(requireMenu(menuId));
    }

    /** 单页：审核历史 */
    @GetMapping("/single/{menuId}/history")
    public AdminApiResponse<ContentHistoryResponse> singlePageHistory(
            HttpServletRequest request, @PathVariable Integer menuId) {
        requireManageableSinglePageMenu(request, menuId);
        List<ContentAuditRecordView> auditTrail =
                checkRecordService.listAuditRecords(List.of(menuId), null).stream()
                        .filter(Objects::nonNull)
                        .filter(record -> Objects.equals(record.getContentId(), menuId))
                        .map(ContentAuditRecordView::from)
                        .toList();
        return AdminApiResponse.success(new ContentHistoryResponse(List.of(), auditTrail));
    }

    @PostMapping("/menus/{id}/seo")
    public AdminApiResponse<MenuDetail> updateMenuSeo(
            HttpServletRequest request, @PathVariable Integer id, @RequestBody SeoDto body) {
        requireMenu(id);
        body.setId(id);
        requireSuccess(oneShotService.updateSEO(body, request));
        return AdminApiResponse.success(MenuDetail.from(requireMenu(id)));
    }

    @PostMapping("/menus/{id}/template")
    public AdminApiResponse<MenuDetail> updateMenuTemplate(
            HttpServletRequest request,
            @PathVariable Integer id,
            @RequestBody MenuTemplateRequest body) {
        requireMenu(id);
        OneshotEntity update = new OneshotEntity();
        update.setId(id);
        update.setListPageTemplate(body.listPageTemplate());
        update.setDetailPageTemplate(body.detailPageTemplate());
        requireSuccess(oneShotService.update(update, request));
        return AdminApiResponse.success(MenuDetail.from(requireMenu(id)));
    }

    @DeleteMapping("/menus/{id}")
    public AdminApiResponse<Void> deleteMenu(HttpServletRequest request, @PathVariable Integer id) {
        requireMenu(id);
        requireSuccess(oneShotService.deleteMenu(new IdDto(id), request));
        return AdminApiResponse.success();
    }

    @GetMapping("/fields/catalog")
    public AdminApiResponse<List<ContentFieldDefinition>> fieldCatalog(
            HttpServletRequest request, @RequestParam(defaultValue = "0") Integer type) {
        return AdminApiResponse.success(fieldSettingService.getFieldSettingList(type));
    }

    @GetMapping("/fields/menus/{menuId}")
    public AdminApiResponse<FieldWorkspaceResponse> menuFields(
            HttpServletRequest request,
            @PathVariable Integer menuId,
            @RequestParam(defaultValue = "0") Integer type) {
        OneshotEntity menu = requireCurrentSiteMenu(request, menuId);
        requireMenuPermission(request, menuId);
        String optionsJson = type == 1 ? menu.getMenuField() : menu.getModelField();
        List<ContentFieldDefinition> catalog =
                fieldSettingService.getFieldSettingList(legacyCatalogType(type));
        List<ContentFieldDefinition> fieldOptions = parseOptions(optionsJson);
        catalog = extensionCatalog(catalog, fieldOptions);
        List<ContentFieldDefinition> fieldListDisplay = parseOptions(menu.getModelList());
        List<ContentFieldDefinition> fieldSearch = parseOptions(menu.getModelSearch());
        return AdminApiResponse.success(
                new FieldWorkspaceResponse(
                        MenuDetail.from(menu),
                        type,
                        fieldOptions,
                        fieldListDisplay,
                        fieldSearch,
                        catalog,
                        fieldAttributeGuides(),
                        List.of(),
                        fieldInspections(fieldOptions, fieldListDisplay, fieldSearch),
                        legacyAudit(
                                Objects.equals(type, 1) ? "menuField" : "modelField",
                                optionsJson,
                                fieldOptions,
                                catalog),
                        null,
                        com.xinhongru.content.model.ContentFieldConfigDigest.of(menu)));
    }

    /** 批量获取多个栏目的字段配置（type 按各栏目 pageType 推导：0=单篇→menuField，其余→modelField）。 */
    @GetMapping("/fields/menus/batch")
    public AdminApiResponse<List<BatchFieldOptions>> menuFieldsBatch(
            HttpServletRequest request, @RequestParam("menuIds") List<Integer> menuIds) {
        List<BatchFieldOptions> result = new ArrayList<>();
        if (menuIds != null) {
            for (Integer menuId : menuIds) {
                if (menuId == null) {
                    continue;
                }
                try {
                    OneshotEntity menu = requireMenu(menuId);
                    int type = menu.getPageType() != null && menu.getPageType() == 0 ? 1 : 0;
                    String optionsJson = type == 1 ? menu.getMenuField() : menu.getModelField();
                    result.add(new BatchFieldOptions(menuId, type, parseOptions(optionsJson)));
                } catch (Exception e) {
                    result.add(new BatchFieldOptions(menuId, 0, List.of()));
                }
            }
        }
        return AdminApiResponse.success(result);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping("/fields/menus/{menuId}")
    public AdminApiResponse<FieldWorkspaceResponse> saveMenuFields(
            HttpServletRequest request,
            @PathVariable Integer menuId,
            @RequestBody SetFieldDto body) {
        OneshotEntity menu = lockFieldConfiguration(request, menuId);
        String expected = request.getHeader("X-CMS-Field-Config-Digest");
        if (expected != null
                && !expected.equals(
                        com.xinhongru.content.model.ContentFieldConfigDigest.of(menu))) {
            throw new IllegalArgumentException("字段配置已变化，请重新读取并确认；本次修改未保存");
        }
        Integer type = requireFieldType(body);
        body.setFieldOptions(
                com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                        parseCurrentFieldOptions(menu, type), body.getFieldOptions()));
        validateFieldPayload(
                body, fieldSettingService.getFieldSettingList(legacyCatalogType(type)));
        requireReferenceSourcePermission(request, menu.getSiteProjectId(), body.getFieldOptions());
        if (Objects.equals(type, 1)) {
            body.setFieldListDisplay(
                    parseOptionsForWrite(menu.getModelList(), "modelList", "保存字段配置"));
            body.setFieldSearch(
                    parseOptionsForWrite(menu.getModelSearch(), "modelSearch", "保存字段配置"));
        }
        body.setId(menuId);
        requireSuccess(fieldSettingService.updateModelField(body));
        return menuFields(request, menuId, body.getType());
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping("/fields/menus/{menuId}/preset")
    public AdminApiResponse<FieldWorkspaceResponse> previewMenuFieldPreset(
            HttpServletRequest request,
            @PathVariable Integer menuId,
            @RequestBody FieldPresetRequest body) {
        OneshotEntity menu = requireCurrentSiteMenu(request, menuId);
        requireMenuPermission(request, menuId);
        validatePresetRequest(body);
        if (Boolean.TRUE.equals(body.confirm())) menu = lockFieldConfiguration(request, menuId);
        Integer type = body.type();
        List<ContentFieldDefinition> catalog =
                fieldSettingService.getFieldSettingList(legacyCatalogType(type));
        FieldPreset preset = FieldPreset.of(body.presetKey());
        List<ContentFieldDefinition> currentOptions = parseCurrentFieldOptions(menu, type);
        List<ContentFieldDefinition> options = presetOptions(catalog, preset, currentOptions);
        Set<String> presetManagedNames = presetManagedFieldNames(catalog, preset);
        List<ContentFieldDefinition> currentListDisplay =
                parseOptionsForWrite(menu.getModelList(), "modelList", "应用字段方案");
        List<ContentFieldDefinition> currentFieldSearch =
                parseOptionsForWrite(menu.getModelSearch(), "modelSearch", "应用字段方案");
        List<ContentFieldDefinition> listDisplay =
                presetListOrSearchFields(options, currentListDisplay, presetManagedNames, true);
        List<ContentFieldDefinition> fieldSearch =
                presetListOrSearchFields(options, currentFieldSearch, presetManagedNames, false);
        FieldPresetDiff presetDiff =
                presetDiff(currentOptions, options, preset)
                        .withPreviewChecksum(
                                presetPreviewChecksum(
                                        menu,
                                        type,
                                        preset.key(),
                                        currentOptions,
                                        currentListDisplay,
                                        currentFieldSearch,
                                        options,
                                        listDisplay,
                                        fieldSearch));
        if (Boolean.TRUE.equals(body.confirm())) {
            if (StringUtils.isBlank(body.previewChecksum())
                    || !Objects.equals(body.previewChecksum(), presetDiff.previewChecksum())) {
                throw new IllegalArgumentException("字段方案预览已过期，请重新预览后再确认");
            }
            SetFieldDto dto =
                    new SetFieldDto()
                            .setId(menuId)
                            .setType(type)
                            .setFieldOptions(options)
                            .setFieldListDisplay(listDisplay)
                            .setFieldSearch(fieldSearch);
            dto.setFieldOptions(
                    com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                            currentOptions, dto.getFieldOptions()));
            validateFieldPayload(dto, catalog);
            requireSuccess(fieldSettingService.updateModelField(dto));
            menu = requireCurrentSiteMenu(request, menuId);
            presetDiff = presetDiff.asApplied();
        }
        return AdminApiResponse.success(
                new FieldWorkspaceResponse(
                        MenuDetail.from(menu),
                        type,
                        options,
                        listDisplay,
                        fieldSearch,
                        catalog,
                        fieldAttributeGuides(),
                        List.of(),
                        fieldInspections(options, listDisplay, fieldSearch),
                        legacyAudit(
                                Objects.equals(type, 1) ? "menuField" : "modelField",
                                Objects.equals(type, 1)
                                        ? menu.getMenuField()
                                        : menu.getModelField(),
                                parseCurrentFieldOptions(menu, type),
                                catalog),
                        presetDiff,
                        com.xinhongru.content.model.ContentFieldConfigDigest.of(menu)));
    }

    @GetMapping("/content")
    public AdminApiResponse<ContentListResponse> contentList(
            HttpServletRequest request,
            @RequestParam Integer classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Integer status) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        List<ContentFieldDefinition> fields = parseOptions(menu.getModelField()).stream()
                .filter(field -> !MemberCredentials.isPasswordField(classId, field.getFieldName()))
                .toList();
        ContentCond cond = new ContentCond().setClassid(classId).setTitle(title).setStatus(status);
        List<ContentFieldDefinition> configuredColumns = parseOptions(menu.getModelList()).stream()
                .filter(field -> !MemberCredentials.isPasswordField(classId, field.getFieldName()))
                .toList();
        if (configuredColumns.isEmpty()) {
            configuredColumns =
                    fields.stream()
                            .filter(
                                    field ->
                                            "on".equals(field.getIsList())
                                                    || "title".equals(field.getFieldName()))
                            .toList();
        }
        List<ContentFieldDefinition> columns = configuredColumns;
        Supplier<ContentListResponse> legacyRead =
                () -> {
                    PageResult<ContentEntity> pageInfo =
                            contentService.getArticlesByCond(menu, cond, page, limit);
                    return new ContentListResponse(
                            MenuDetail.from(menu),
                            fields,
                            columns,
                            buildContentBatchActions(),
                            previewUrlTemplate(menu),
                            pageInfo.getList().stream()
                                    .map(content -> ContentRow.from(menu, content, fields))
                                    .toList(),
                            pageInfo.getTotal(),
                            pageInfo.getPageNum(),
                            pageInfo.getPageSize());
                };
        SiteSummary site = currentSite(request);
        return AdminApiResponse.success(legacyRead.get());
    }

    @GetMapping("/content/{classId}/audit-records")
    public AdminApiResponse<ContentAuditRecordResponse> contentAuditRecords(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        requireManageableContentMenu(request, classId);
        String titleKeyword = StringUtils.trimToNull(keyword);
        List<ContentAuditRecordView> records =
                checkRecordService.listAuditRecords(List.of(classId), titleKeyword).stream()
                        .filter(Objects::nonNull)
                        .filter(
                                record ->
                                        status == null
                                                || Objects.equals(record.getCheckStatus(), status))
                        .map(ContentAuditRecordView::from)
                        .toList();
        return AdminApiResponse.success(new ContentAuditRecordResponse(records));
    }

    @GetMapping("/content/{classId}/{id}/history")
    public AdminApiResponse<ContentHistoryResponse> contentHistory(
            HttpServletRequest request, @PathVariable Integer classId, @PathVariable Integer id) {
        requireManageableContentMenu(request, classId);
        requireContentInMenu(classId, id);
        List<ContentHistoryItem> histories =
                contentHistoryService.list(id, classId).stream()
                        .filter(Objects::nonNull)
                        .map(ContentHistoryItem::from)
                        .toList();
        List<ContentAuditRecordView> auditTrail =
                checkRecordService.listAuditRecords(List.of(classId), null).stream()
                        .filter(Objects::nonNull)
                        .filter(record -> Objects.equals(record.getContentId(), id))
                        .map(ContentAuditRecordView::from)
                        .toList();
        return AdminApiResponse.success(new ContentHistoryResponse(histories, auditTrail));
    }

    @PostMapping("/content/{classId}/{id}/history/{historyId}/rollback")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AdminApiResponse<?> rollbackContentHistory(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @PathVariable Integer id,
            @PathVariable Integer historyId) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        Long siteId = siteContextService.requireCurrentSiteId(request);
        if (!Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        requireContentInMenu(classId, id);
        requireCmsOperation(request, classId, WebConst.UPDATE_AUTHORITY_ID);
        menu = lockFieldConfiguration(request, classId);
        ContentHistoryEntity historical = contentHistoryService.getById(historyId);
        if (historical == null
                || !Objects.equals(historical.getContentId(), id)
                || !Objects.equals(historical.getMenuId(), classId))
            throw new IllegalArgumentException("历史版本不存在或不属于当前内容");
        ContentEntity snapshot;
        try {
            snapshot = JSON.parseObject(historical.getParams(), ContentEntity.class);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("该版本数据快照解析失败", invalid);
        }
        if (snapshot == null
                || !Objects.equals(snapshot.getId(), id)
                || !Objects.equals(snapshot.getClassid(), classId)
                || (snapshot.getSiteProjectId() != null
                        && !Objects.equals(snapshot.getSiteProjectId(), siteId)))
            throw new IllegalArgumentException("历史快照不属于当前站点栏目内容");
        var definitions = ContentModelCodec.read(menu.getModelField());
        if (Boolean.TRUE.equals(menu.getIsSubMenu())
                && definitions.stream()
                        .anyMatch(
                                field ->
                                        "xgid".equals(field.getFieldName())
                                                && ContentFieldDefinition.enabled(field.getIsOpen())
                                                && !ContentFieldDefinition.enabled(
                                                        field.getIsReadOnly()))) {
            var current = contentMapper.lockActiveArticleInSiteMenu(siteId, classId, id);
            if (current == null) throw new IllegalArgumentException("内容不存在或不属于当前站点栏目");
            if (snapshot.getXgid() != null
                    && !Objects.equals(snapshot.getXgid(), current.getXgid()))
                requireNativeOwnerPermission(request, siteId, menu);
        }
        requireReferenceSourcePermission(
                request,
                siteId,
                definitions,
                ContentExtensionFields.read(snapshot.getExtensionFields()));

        UserEntity user = TaleUtils.getLoginUser(request);
        boolean changed =
                nativeContentRecords()
                        .rollbackHistory(
                                siteId,
                                classId,
                                id,
                                historyId,
                                user == null ? null : user.getId().longValue());
        return changed ? AdminApiResponse.success() : AdminApiResponse.success("该版本与当前内容一致，无需回滚");
    }

    @GetMapping("/content/{classId}/{id}/preview")
    public AdminApiResponse<ContentPreviewResponse> contentPreview(
            HttpServletRequest request, @PathVariable Integer classId, @PathVariable Integer id) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        ContentEntity content = requireContentInMenu(classId, id);
        List<ContentFieldDefinition> fields = parseOptions(menu.getModelField());
        String templatePath =
                StringUtils.defaultString(
                        StringUtils.defaultIfBlank(
                                menu.getDetailPageTemplate(), menu.getListPageTemplate()));
        boolean templateMissing = StringUtils.isBlank(templatePath);
        List<String> missingFields =
                fields.stream()
                        .filter(
                                field ->
                                        "on"
                                                .equalsIgnoreCase(
                                                        StringUtils.defaultString(
                                                                field.getIsRequired())))
                        .filter(
                                field ->
                                        StringUtils.isBlank(
                                                String.valueOf(
                                                        readValue(content, field.getFieldName())
                                                                        == null
                                                                ? ""
                                                                : readValue(
                                                                        content,
                                                                        field.getFieldName()))))
                        .map(
                                field ->
                                        StringUtils.defaultIfBlank(
                                                field.getTitle(),
                                                StringUtils.defaultIfBlank(
                                                        field.getFromName(), field.getFieldName())))
                        .toList();
        List<String> publishTaskFailures = previewPublishTaskFailures(menu);
        String readableError = "";
        if (templateMissing) {
            readableError = "栏目 %s 未配置内容页模板，无法确认静态预览模板。".formatted(classId);
        } else if (!missingFields.isEmpty()) {
            readableError = "内容缺少必填字段：" + String.join("、", missingFields);
        } else if (!publishTaskFailures.isEmpty()) {
            readableError = "静态发布任务存在失败项：" + String.join("；", publishTaskFailures);
        }
        return AdminApiResponse.success(
                new ContentPreviewResponse(
                        previewUrl(menu, content),
                        templatePath,
                        templateMissing,
                        missingFields,
                        publishTaskFailures,
                        readableError));
    }

    @GetMapping("/content/{classId}/{id}")
    public AdminApiResponse<ContentDetailResponse> contentDetail(
            HttpServletRequest request, @PathVariable Integer classId, @PathVariable Integer id) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        ContentEntity content =
                id == 0
                        ? new ContentEntity().setId(0).setClassid(classId)
                        : requireContentInMenu(classId, id);
        List<ContentFieldDefinition> fields = contentEditorFields(menu);
        return AdminApiResponse.success(
                new ContentDetailResponse(
                        MenuDetail.from(menu), fields, ContentRow.from(menu, content, fields)));
    }

    private ContentDetailResponse contentDetailResponse(
            OneshotEntity menu, Integer classId, Integer contentId) {
        ContentEntity content = requireContentInMenu(classId, contentId);
        List<ContentFieldDefinition> fields = contentEditorFields(menu);
        return new ContentDetailResponse(
                MenuDetail.from(menu), fields, ContentRow.from(menu, content, fields));
    }

    private List<ContentFieldDefinition> contentEditorFields(OneshotEntity menu) {
        List<ContentFieldDefinition> fields = parseOptions(menu.getModelField());
        for (ContentFieldDefinition field : fields) {
            if (MemberCredentials.isPasswordField(menu.getId(), field.getFieldName())) {
                field.setDefaultValue(null).setValue(null).setIsRequired(null)
                        .setInputType(null).setFieldType("password").setBak("留空保留原密码");
            }
        }
        return fields;
    }

    @GetMapping("/i18n-content/{sourceContentId}")
    public AdminApiResponse<Void> i18nContentDetail(
            HttpServletRequest request,
            @PathVariable Integer sourceContentId,
            @RequestParam Integer classId,
            @RequestParam Long languageId,
            @RequestParam(defaultValue = "INDEPENDENT") String structureMode) {
        throw AdminSecurityException.conflict("NATIVE_CONTENT_ROUTE_REQUIRED", "请使用原生多语言内容工作台");
    }

    @PostMapping("/i18n-content/{sourceContentId}")
    public AdminApiResponse<Void> saveI18nContent(
            HttpServletRequest request,
            @PathVariable Integer sourceContentId,
            @RequestParam Integer classId,
            @RequestParam Long languageId,
            @RequestParam(defaultValue = "INDEPENDENT") String structureMode) {
        throw AdminSecurityException.conflict("NATIVE_CONTENT_ROUTE_REQUIRED", "请使用原生多语言内容工作台");
    }

    @PostMapping("/content/{classId}")
    public AdminApiResponse<ContentDetailResponse> saveContent(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody ContentEntity body) {
        boolean creating = body == null || body.getId() == null || body.getId() == 0;
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        body.setClassid(classId);
        if (body.getEntryIntoForceTime() != null
                && body.getExpirationTime() != null
                && body.getEntryIntoForceTime().compareTo(body.getExpirationTime()) > 0) {
            throw new IllegalArgumentException("失效时间必须大于生效时间");
        }
        if (!creating) {
            requireContentInMenu(classId, body.getId());
        }
        ResultVO<?> result =
                creating
                        ? contentService.addArticle(body, request)
                        : contentService.updateContentByCid(body, request);
        requireSuccess(result);
        Integer id = body.getId();
        if (id == null || id <= 0) {
            throw new IllegalStateException(creating ? "内容保存成功但未返回内容 ID" : "内容 ID 无效");
        }
        return AdminApiResponse.success(contentDetailResponse(menu, classId, id));
    }

    @PostMapping("/content/{classId}/{id}/sort")
    public AdminApiResponse<ContentDetailResponse> updateContentSort(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @PathVariable Integer id,
            @RequestBody SortRequest body) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        requireContentInMenu(classId, id);
        requireSuccess(contentService.updateSort(classId, new SortDto(id, body.sort()), request));
        return AdminApiResponse.success(contentDetailResponse(menu, classId, id));
    }

    @PostMapping("/content/{classId}/{id}/seo")
    public AdminApiResponse<ContentDetailResponse> updateContentSeo(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @PathVariable Integer id,
            @RequestBody SeoDto body) {
        OneshotEntity menu = requireManageableContentMenu(request, classId);
        requireContentInMenu(classId, id);
        ModelManageSeoVo seo = new ModelManageSeoVo().setClassid(classId);
        seo.setId(id)
                .setSeoTitle(body.getSeoTitle())
                .setSeoKeywords(body.getSeoKeywords())
                .setSeoDescription(body.getSeoDescription());
        requireSuccess(contentService.updateSEO(seo, request));
        return AdminApiResponse.success(contentDetailResponse(menu, classId, id));
    }

    @PostMapping("/content/{classId}/option")
    public AdminApiResponse<Void> updateContentOption(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody OptionDto body) {
        requireManageableContentMenu(request, classId);
        requireContentsInMenu(classId, body == null ? null : body.getIds());
        requireSuccess(contentService.updateOption(body, classId, request));
        return AdminApiResponse.success();
    }

    @PostMapping("/content/{classId}/batch-option")
    public AdminApiResponse<BatchOperationResponse> batchContentOption(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody OptionDto body) {
        throw AdminSecurityException.conflict("NATIVE_CONTENT_ROUTE_REQUIRED", "请使用原生多语言内容工作台");
    }

    @PostMapping("/content/{classId}/batch-delete")
    public AdminApiResponse<BatchOperationResponse> batchDeleteContent(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody BatchIdsRequest body) {
        validateBatchIds(body == null ? null : body.ids());
        requireManageableContentMenu(request, classId);
        requireCmsOperation(request, classId, WebConst.DELETE_AUTHORITY_ID);

        List<BatchSuccess> successes = new ArrayList<>();
        List<BatchFailure> failures = new ArrayList<>();
        for (Integer id : body.ids()) {
            ContentEntity content = null;
            try {
                content = requireContentInMenu(classId, id);
                String title = contentTitle(content, id);
                requireSuccess(contentService.deleteInfoById(classId, new IdDto(id), request));
                successes.add(new BatchSuccess(id, title, "已删除"));
            } catch (RuntimeException ex) {
                failures.add(new BatchFailure(id, contentTitle(content, id), message(ex, "删除失败")));
            }
        }
        return AdminApiResponse.success(
                BatchOperationResponse.of(body.ids().size(), successes, failures));
    }

    @PostMapping("/content/{classId}/audit")
    public AdminApiResponse<BatchOperationResponse> auditContent(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody AuditContentRequest body) {
        throw AdminSecurityException.conflict("NATIVE_CONTENT_ROUTE_REQUIRED", "请使用原生多语言内容工作台");
    }

    @PostMapping("/content/{classId}/audit-v2")
    public AdminApiResponse<BatchOperationResponse> auditContentV2(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @RequestBody AuditContentV2Request body) {
        validateBatchIds(body == null ? null : body.ids());
        if (body.checkStatus() == null || body.checkStatus() < 0 || body.checkStatus() > 3) {
            throw new IllegalArgumentException("审核状态无效，必须为 0-3");
        }
        requireManageableContentMenu(request, classId);
        requireCmsOperation(request, classId, WebConst.CHECK_AUTHORITY_ID);

        UserEntity loginUser = TaleUtils.getLoginUser(request);
        OneshotEntity menu = oneShotService.getModelById(classId);
        String menuName = menu != null ? menu.getClassName() : null;

        List<BatchSuccess> successes = new ArrayList<>();
        List<BatchFailure> failures = new ArrayList<>();
        for (Integer id : body.ids()) {
            ContentEntity content = null;
            try {
                content = requireContentInMenu(classId, id);
                String title = contentTitle(content, id);

                // 映射审核状态到内容状态：0(待审批)→2(待审), 1(通过)→1(已发布), 2(未通过)→3(已驳回), 3(作废)→4(作废)
                int contentStatus = mapAuditStatusToContentStatus(body.checkStatus());
                // 审核只写工作流字段，避免把读取时的旧正文和搜索投影覆盖回去。
                ContentEntity auditUpdate = new ContentEntity().setId(id).setStatus(contentStatus);
                auditUpdate.setCreateTime(body.checkStatus() == 1 ? LocalDateTime.now() : null);
                // 实体的新增默认值不适用于局部更新，保留内容原有有效期。
                auditUpdate.setEntryIntoForceTime(null);
                auditUpdate.setExpirationTime(null);
                contentMapper.updateArticleById(auditUpdate);

                // 插入审核记录
                CheckRecordEntity checkRecord = new CheckRecordEntity();
                checkRecord.setContentId(id);
                checkRecord.setContentTitle(title);
                checkRecord.setSubmitMenuId(classId);
                checkRecord.setSubmitMenuName(menuName);
                checkRecord.setCheckStatus(body.checkStatus());
                checkRecord.setCheckDescription(body.checkDescription());
                checkRecord.setCheckTime(LocalDateTime.now());
                checkRecord.setSubmitTime(LocalDateTime.now());
                if (loginUser != null) {
                    checkRecord.setSubmitUserId(loginUser.getId());
                    checkRecord.setSubmitUserName(loginUser.getScreenName());
                    checkRecord.setCheckUserId(loginUser.getId());
                    checkRecord.setCheckUserName(loginUser.getScreenName());
                }
                checkRecordService.save(checkRecord);

                successes.add(new BatchSuccess(id, title, checkStatusText(body.checkStatus())));
            } catch (RuntimeException ex) {
                failures.add(
                        new BatchFailure(id, contentTitle(content, id), message(ex, "审核操作失败")));
            }
        }
        return AdminApiResponse.success(
                BatchOperationResponse.of(body.ids().size(), successes, failures));
    }

    @PatchMapping("/content/{classId}/{id}/recommend")
    public AdminApiResponse<Void> updateContentRecommend(
            HttpServletRequest request,
            @PathVariable Integer classId,
            @PathVariable Integer id,
            @RequestBody UpdateRecommendRequest body) {
        requireManageableContentMenu(request, classId);
        requireContentInMenu(classId, id);
        // 根据字段名映射到optionType
        int optionType =
                switch (body.field()) {
                    case "indexTop" -> body.value() ? 1 : 2;
                    case "indexHot" -> body.value() ? 3 : 4;
                    case "indexElite" -> body.value() ? 5 : 6;
                    case "menuTop" -> body.value() ? 13 : 14;
                    case "menuHot" -> body.value() ? 15 : 16;
                    case "menuElite" -> body.value() ? 17 : 18;
                    default ->
                            throw new IllegalArgumentException(
                                    "unsupported recommend field: " + body.field());
                };
        // 直接调用 mapper 绕过服务层权限检查
        contentMapper.updateElite(List.of(id), classId, String.valueOf(optionType));
        return AdminApiResponse.success();
    }

    @DeleteMapping("/content/{classId}/{id}")
    public AdminApiResponse<Void> deleteContent(
            HttpServletRequest request, @PathVariable Integer classId, @PathVariable Integer id) {
        requireManageableContentMenu(request, classId);
        requireCmsOperation(request, classId, WebConst.DELETE_AUTHORITY_ID);
        requireContentInMenu(classId, id);
        requireSuccess(contentService.deleteInfoById(classId, new IdDto(id), request));
        return AdminApiResponse.success();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AdminApiResponse<>(400, message(ex, "CMS 请求参数无效"), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AdminApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdminApiResponse<>(409, message(ex, "CMS 当前状态不允许该操作"), null));
    }

    private SiteSummary currentSite(HttpServletRequest request) {
        SiteContext context = siteContextService.current(request);

        SiteProjectEntity site = projectService.getSite(context.getSiteId());
        return new SiteSummary(
                context.getSiteId(),
                context.getRootMenuId(),
                context.getSiteName(),
                context.getSiteCode(),
                context.isGlobalMode(),
                siteModeSummary(context.getSiteId(), site),
                templatePackageSummary(context.getSiteId(), site));
    }

    /** 按指定站点解析栏目范围；siteId 为空时回退到当前登录站点上下文。 */
    private SiteSummary resolveSite(HttpServletRequest request, Long siteId) {
        if (siteId == null) {
            return currentSite(request);
        }
        siteContextService.assertCanAccessSite(request, siteId);
        SiteProjectEntity site = projectService.getSite(siteId);
        return new SiteSummary(
                siteId,
                site.getRootMenuId(),
                site.getProjectName(),
                site.getProjectCode(),
                false,
                siteModeSummary(siteId, site),
                templatePackageSummary(siteId, site));
    }

    private String siteModeSummary(Long siteId, SiteProjectEntity site) {
        if (projectService == null || siteId == null) {
            return "CMS_MANAGED";
        }
        try {
            // SiteProjectEntity site = projectService.getSite(siteId);
            String siteMode = site == null ? null : site.getSiteMode();
            return siteMode == null || siteMode.isBlank() ? "CMS_MANAGED" : siteMode;
        } catch (RuntimeException ignored) {
            return "CMS_MANAGED";
        }
    }

    private TemplatePackageInfo templatePackageSummary(Long siteId, SiteProjectEntity site) {
        if (projectService == null || siteId == null) {
            return null;
        }
        try {
            // SiteProjectEntity site = projectService.getSite(siteId);
            if (site == null) {
                return null;
            }
            return new TemplatePackageInfo(
                    site.getTemplatePackageName(),
                    site.getTemplatePackageStatus(),
                    site.getCurrentBatchId(),
                    site.getLastRecognitionBatchId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private OneshotEntity requireMenu(Integer id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("栏目 ID 无效");
        }
        OneshotEntity menu = oneShotService.getModelById(id);
        if (menu == null) {
            throw new IllegalArgumentException("栏目不存在");
        }
        return menu;
    }

    private OneshotEntity lockFieldConfiguration(HttpServletRequest request, Integer menuId) {
        OneshotEntity permitted = requireCurrentSiteMenu(request, menuId);
        requireMenuPermission(request, menuId);
        if (permitted.getSiteProjectId() == null) throw new IllegalArgumentException("栏目未归属站点");
        OneshotEntity locked =
                oneShotMapper.lockFieldConfiguration(
                        permitted.getSiteProjectId(), menuId.longValue());
        if (locked == null
                || !Objects.equals(locked.getId(), menuId)
                || !Objects.equals(locked.getSiteProjectId(), permitted.getSiteProjectId())) {
            throw new IllegalArgumentException("栏目已变化或不属于当前站点，请重新读取");
        }
        return locked;
    }

    private OneshotEntity requireCurrentSiteMenu(HttpServletRequest request, Integer id) {
        SiteSummary site = currentSite(request);
        if (cmsResourceContextService != null && site.siteId() != null) {
            cmsResourceContextService.requireMenu(site.siteId(), id);
        }
        return requireMenu(id);
    }

    private OneshotEntity requireManageableContentMenu(
            HttpServletRequest request, Integer classId) {
        OneshotEntity menu = requireMenu(classId);
        requireMenuPermission(request, classId);
        SiteSummary site = currentSite(request);
        MenuNode menuNode = MenuNode.from(menu);
        Set<Integer> confirmedScope = Set.of(menu.getId());
        if (!isNavigationManageable(site, confirmedScope, menuNode)) {
            throw new IllegalArgumentException(
                    "栏目 %s 不是可管理内容栏目（reason=%s）"
                            .formatted(
                                    classId,
                                    StringUtils.defaultIfBlank(
                                            manageReason(site, confirmedScope, menuNode),
                                            "navigation-only")));
        }
        return menu;
    }

    private OneshotEntity requireManageableSinglePageMenu(
            HttpServletRequest request, Integer menuId) {
        OneshotEntity menu = requireMenu(menuId);
        requireMenuPermission(request, menuId);
        MenuNode menuNode = MenuNode.from(menu);
        if (!isManageChannelEnabled(menuNode)) {
            throw new IllegalArgumentException("栏目 %s 未开启后台管理".formatted(menuId));
        }
        if (!isSinglePageEntry(menuNode)) {
            throw new IllegalArgumentException("栏目 %s 不是单页栏目".formatted(menuId));
        }
        return menu;
    }

    /** 菜单级访问校验：超管放行，普通用户仅可通过 group_setting 授权的栏目（未授权 403） */
    private void requireMenuPermission(HttpServletRequest request, Integer menuId) {
        if (permissionService == null) {
            return;
        }
        if (!permissionService.snapshot(request).hasMenuAccess(menuId)) {
            throw AdminSecurityException.forbidden("CMS_MENU_ACCESS_DENIED", "无权管理栏目 " + menuId);
        }
    }

    /** 单页状态：基于 t_oneshot.status（0草稿 1待审 2已审核 3已发布）；未开启前台且未发布=DISABLED */
    private static String singlePageStatus(OneshotEntity menu) {
        Integer status = menu.getStatus();
        int s = status == null ? SINGLE_PAGE_STATUS_DRAFT : status;
        if (s == SINGLE_PAGE_STATUS_PUBLISHED) {
            return "PUBLISHED";
        }
        if (s == SINGLE_PAGE_STATUS_APPROVED) {
            return "APPROVED";
        }
        if (s == 1) {
            return "PENDING_REVIEW";
        }
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel())) {
            return "DISABLED";
        }
        return "DRAFT";
    }

    /** 栏目操作权限校验：栏目访问级 + other_setting optionId */
    private void requireCmsOperation(HttpServletRequest request, Integer menuId, int optionId) {
        requireMenuPermission(request, menuId);
        if (permissionService != null
                && !permissionService.snapshot(request).canOperate(menuId, optionId)) {
            throw AdminSecurityException.forbidden("CMS_OPERATION_DENIED", "无权执行该操作");
        }
    }

    private AdminApiResponse<SinglePageDetailResponse> singlePageResponse(OneshotEntity menu) {
        List<ContentFieldDefinition> fields = parseSortedMenuFields(menu.getMenuField());
        SinglePageRow row = SinglePageRow.from(menu, fields);
        return AdminApiResponse.success(
                new SinglePageDetailResponse(
                        MenuDetail.from(menu), fields, row, singlePageStatus(menu)));
    }

    private List<MenuNode> scopedConfigMenus(HttpServletRequest request, SiteSummary site) {
        List<MenuNode> allMenus = scopedAllMenus(request, site);
        return allMenus.isEmpty() ? scopedContentMenus(request, site) : allMenus;
    }

    private List<MenuNode> scopedAllMenus(HttpServletRequest request, SiteSummary site) {
        List<OneshotEntity> menus =
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request);
        List<MenuNode> requestAwareMenus =
                (menus == null ? List.<OneshotEntity>of() : menus)
                        .stream().map(MenuNode::from).toList();
        return annotateNavigationMenus(site, contentMenusForSite(site, requestAwareMenus));
    }

    private List<MenuNode> scopedContentMenus(HttpServletRequest request, SiteSummary site) {
        List<OneshotEntity> menus = oneShotService.getAllContentMenu(new OneshotDto(0), request);
        List<MenuNode> requestAwareMenus =
                (menus == null ? List.<OneshotEntity>of() : menus)
                        .stream().map(MenuNode::from).toList();
        return annotateNavigationMenus(site, contentMenusForSite(site, requestAwareMenus));
    }

    private List<OneshotEntity> scopedTemplateMenus(HttpServletRequest request, SiteSummary site) {
        List<OneshotEntity> menus =
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request);
        List<OneshotEntity> availableMenus = menus == null ? List.of() : menus;
        Set<Integer> scopedIds =
                contentMenusForSite(site, availableMenus.stream().map(MenuNode::from).toList())
                        .stream()
                        .map(MenuNode::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        return availableMenus.stream()
                .filter(menu -> menu.getId() != null && scopedIds.contains(menu.getId()))
                .toList();
    }

    private ContentEntity requireContentInMenu(Integer classId, Integer id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("内容 ID 无效");
        }
        ContentEntity content = contentService.getArticleById(id);
        if (content == null || !Objects.equals(content.getClassid(), classId)) {
            throw new IllegalArgumentException("内容 %s 不属于当前栏目 %s".formatted(id, classId));
        }
        return content;
    }

    private void requireContentsInMenu(Integer classId, List<Integer> ids) {
        if (ids == null) {
            return;
        }
        for (Integer id : ids) {
            requireContentInMenu(classId, id);
        }
    }

    private void validateBatchIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("至少选择一条内容");
        }
    }

    /** 映射审核状态到内容状态：0(待审批)→2(待审), 1(通过)→1(已发布), 2(未通过)→3(已驳回), 3(作废)→4(作废) */
    private int mapAuditStatusToContentStatus(Integer checkStatus) {
        return switch (checkStatus == null ? -1 : checkStatus) {
            case 0 -> 2; // 待审批 → 待审
            case 1 -> 1; // 通过 → 已发布
            case 2 -> 3; // 未通过 → 已驳回
            case 3 -> 4; // 作废 → 作废
            default -> throw new IllegalArgumentException("无效的审核状态: " + checkStatus);
        };
    }

    private String checkStatusText(Integer checkStatus) {
        return switch (checkStatus == null ? -1 : checkStatus) {
            case 0 -> "待审批";
            case 1 -> "通过";
            case 2 -> "未通过";
            case 3 -> "作废";
            default -> "未知状态";
        };
    }

    private static String contentAuditStatusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case 0 -> "待审批";
            case 1 -> "通过";
            case 2 -> "未通过";
            case 3 -> "作废";
            default -> "未知";
        };
    }

    private List<String> previewPublishTaskFailures(OneshotEntity menu) {
        Map<String, Object> setting =
                record(parseJsonObject(menu == null ? null : menu.getSetting()));
        Object direct = setting.get("publishTaskFailures");
        if (direct == null) {
            direct = record(setting.get("templateBinding")).get("publishTaskFailures");
        }
        if (direct instanceof List<?> list) {
            return list.stream()
                    .map(this::text)
                    .map(CmsCoreV2ApiController::previewFailureText)
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }
        String value = text(direct);
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        return List.of(previewFailureText(value));
    }

    private static String previewFailureText(String reason) {
        return switch (StringUtils.defaultString(reason)) {
            case "single-page-non-content" -> "单页栏目不是内容详情页，无法生成内容预览任务";
            case "unsupported-page-type" -> "栏目类型不支持内容预览任务";
            case "missing-manage-channel" -> "栏目缺少内容管理入口，无法生成内容预览任务";
            case "external-link" -> "外部链接栏目无法生成内容预览任务";
            default -> reason;
        };
    }

    private List<BatchAction> buildContentBatchActions() {
        return List.of(
                new BatchAction("publish", "发布", 25, null, false, false),
                new BatchAction("draft", "取消发布", 26, null, false, false),
                new BatchAction("menu-top", "栏目置顶", 13, null, false, false),
                new BatchAction("menu-untop", "取消置顶", 14, null, false, false),
                new BatchAction("menu-elite", "栏目推荐", 17, null, false, false),
                new BatchAction("menu-unelite", "取消推荐", 18, null, false, false),
                new BatchAction("delete", "删除", null, null, true, true),
                new BatchAction("audit-approve", "审核通过", null, "approve", false, false),
                new BatchAction("audit-reject", "驳回", null, "reject", false, false));
    }

    private static String previewUrlTemplate(OneshotEntity menu) {
        if (menu == null) {
            return "";
        }
        if (StringUtils.isNotBlank(menu.getDetailPageUrl())) {
            return menu.getDetailPageUrl();
        }
        if (StringUtils.isNotBlank(menu.getListPageUrl())) {
            return menu.getListPageUrl();
        }
        return "";
    }

    private static String previewUrl(OneshotEntity menu, ContentEntity content) {
        if (content == null) {
            return "";
        }
        String template = StringUtils.defaultString(content.getDetailPageUrl());
        if (StringUtils.isBlank(template)) {
            template = previewUrlTemplate(menu);
        }
        if (StringUtils.isBlank(template)) {
            return "";
        }
        return template.replace(
                        "{id}", String.valueOf(content.getId() == null ? 0 : content.getId()))
                .replace(
                        "{classId}",
                        String.valueOf(
                                content.getClassid() == null
                                        ? menu == null ? 0 : menu.getId()
                                        : content.getClassid()));
    }

    private static RelationSummary relationSummary(ContentEntity content) {
        if (content == null) {
            return new RelationSummary(List.of(), List.of(), List.of());
        }
        List<String> homepageRefs = new ArrayList<>();
        if (Boolean.TRUE.equals(content.getIndexTop())) {
            homepageRefs.add("首页置顶");
        }
        if (Boolean.TRUE.equals(content.getIndexElite())) {
            homepageRefs.add("首页推荐");
        }
        List<String> menuRefs = new ArrayList<>();
        if (Boolean.TRUE.equals(content.getMenuTop())) {
            menuRefs.add("栏目置顶");
        }
        if (Boolean.TRUE.equals(content.getMenuElite())) {
            menuRefs.add("栏目推荐");
        }
        List<String> relatedContents = new ArrayList<>();
        if (content.getXgid() != null && content.getXgid() > 0) {
            relatedContents.add("关联内容 #" + content.getXgid());
        }
        return new RelationSummary(homepageRefs, menuRefs, relatedContents);
    }

    private String contentTitle(ContentEntity content, Integer id) {
        if (content != null && StringUtils.isNotBlank(content.getTitle())) {
            return content.getTitle();
        }
        return String.valueOf(id);
    }

    private EditOneshotDto normalizeMenu(EditOneshotDto body) {
        if (body == null) {
            throw new IllegalArgumentException("栏目数据不能为空");
        }
        if (body.getParentId() == null) {
            body.setParentId(0);
        }
        if (StringUtils.isBlank(body.getClassName())) {
            throw new IllegalArgumentException("栏目名称不能为空");
        }
        if (body.getHasManageChannel() == null) {
            body.setHasManageChannel(Boolean.TRUE);
        }
        if (body.getHasFrontChannel() == null) {
            body.setHasFrontChannel(Boolean.TRUE);
        }
        if (body.getPageType() == null) {
            body.setPageType(1);
        }
        if (StringUtils.isBlank(body.getUrl())) {
            body.setUrl(
                    body.getPageType() == 0
                            ? "/admin/menu_manage/{id}"
                            : "/admin/model_manage/{id}");
        }
        return body;
    }

    private void validateTraditionalTemplatePaths(
            HttpServletRequest request, String listTemplate, String detailTemplate) {
        if (traditionalTemplatePackageService == null
                || (StringUtils.isBlank(listTemplate) && StringUtils.isBlank(detailTemplate))) {
            return;
        }
        SiteSummary site = currentSite(request);
        if (site.siteId() == null) {
            throw new IllegalStateException("请先选择站点后再保存传统模板路径");
        }
        traditionalTemplatePackageService.requireActiveHtmlTemplate(site.siteId(), listTemplate);
        traditionalTemplatePackageService.requireActiveHtmlTemplate(site.siteId(), detailTemplate);
    }

    private List<MenuNode> buildTree(List<MenuNode> flat) {
        Map<Integer, MutableMenuNode> map =
                flat.stream()
                        .collect(
                                Collectors.toMap(
                                        MenuNode::id, MutableMenuNode::new, (left, right) -> left));
        List<MutableMenuNode> roots = new ArrayList<>();
        for (MutableMenuNode node : map.values()) {
            MutableMenuNode parent = map.get(node.parentId);
            if (parent == null || node.parentId == 0 || Objects.equals(node.id, node.parentId)) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        return roots.stream()
                .sorted(Comparator.comparingInt(MutableMenuNode::sort))
                .map(MutableMenuNode::toNode)
                .toList();
    }

    private List<MenuNode> contentMenusForSite(SiteSummary site, List<MenuNode> flat) {
        if (site.globalMode() || site.rootMenuId() == null) {
            return flat;
        }
        Integer rootMenuId = site.rootMenuId();
        Set<Integer> scopedIds =
                flat.stream()
                        .filter(
                                menu ->
                                        Objects.equals(menu.id(), rootMenuId)
                                                || Objects.equals(menu.parentId(), rootMenuId)
                                                || Objects.equals(menu.siteRootId(), rootMenuId))
                        .map(MenuNode::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean changed;
        do {
            changed = false;
            for (MenuNode menu : flat) {
                if (menu.id() != null
                        && scopedIds.contains(menu.parentId())
                        && scopedIds.add(menu.id())) {
                    changed = true;
                }
            }
        } while (changed);

        return flat.stream().filter(menu -> scopedIds.contains(menu.id())).toList();
    }

    private List<MenuNode> annotateNavigationMenus(SiteSummary site, List<MenuNode> scoped) {
        Set<Integer> scopedIds =
                scoped.stream()
                        .map(MenuNode::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        // 创建 ID 到名称的映射，用于显示父级栏目名称
        Map<Integer, String> nameById =
                scoped.stream()
                        .filter(m -> m.id() != null && m.className() != null)
                        .collect(Collectors.toMap(MenuNode::id, MenuNode::className, (a, b) -> a));
        return scoped.stream()
                .map(
                        menu -> {
                            boolean siteScoped = isSiteScoped(site, scopedIds, menu);
                            boolean contentManageable = isContentManageable(site, scopedIds, menu);
                            boolean manageable = isNavigationManageable(site, scopedIds, menu);
                            String parentName =
                                    menu.parentId() != null ? nameById.get(menu.parentId()) : null;
                            return menu.withCapabilities(
                                    siteScoped,
                                    contentManageable,
                                    invalidReason(site, scopedIds, menu),
                                    manageable,
                                    manageReason(site, scopedIds, menu),
                                    manageable ? contentRoute(menu) : null,
                                    parentName);
                        })
                .toList();
    }

    private boolean isSiteScoped(SiteSummary site, Set<Integer> scopedIds, MenuNode menu) {
        return site.globalMode() || site.rootMenuId() == null || scopedIds.contains(menu.id());
    }

    private boolean isContentManageable(SiteSummary site, Set<Integer> scopedIds, MenuNode menu) {
        return isSiteScoped(site, scopedIds, menu)
                && !isSiteRootNode(site, menu)
                && isManageChannelEnabled(menu)
                && isManageableContentEntry(menu);
    }

    private boolean isNavigationManageable(
            SiteSummary site, Set<Integer> scopedIds, MenuNode menu) {
        return isContentManageable(site, scopedIds, menu)
                && !isSinglePageNonContent(menu)
                && !isExternalLink(menu.url());
    }

    private String invalidReason(SiteSummary site, Set<Integer> scopedIds, MenuNode menu) {
        if (!isSiteScoped(site, scopedIds, menu)) {
            return "out-of-site-scope";
        }
        if (isSiteRootNode(site, menu)) {
            return "site-root";
        }
        if (!isManageChannelEnabled(menu)) {
            return "missing-manage-channel";
        }
        if (!isManageableContentEntry(menu)) {
            return "unsupported-page-type";
        }
        return null;
    }

    private String manageReason(SiteSummary site, Set<Integer> scopedIds, MenuNode menu) {
        if (!isSiteScoped(site, scopedIds, menu)) {
            return "out-of-site-scope";
        }
        if (isSiteRootNode(site, menu)) {
            return "site-root";
        }
        if (!isManageChannelEnabled(menu)) {
            return "missing-manage-channel";
        }
        if (isSinglePageNonContent(menu)) {
            return "single-page-non-content";
        }
        if (isExternalLink(menu.url())) {
            return "external-link";
        }
        if (!isManageableContentEntry(menu)) {
            return "unsupported-page-type";
        }
        return null;
    }

    private boolean isSiteRootNode(SiteSummary site, MenuNode menu) {
        return site.rootMenuId() != null && Objects.equals(site.rootMenuId(), menu.id());
    }

    private boolean isManageablePageType(Integer pageType) {
        return Objects.equals(pageType, 0) || Objects.equals(pageType, 1);
    }

    private boolean isManageChannelEnabled(MenuNode menu) {
        return !Boolean.FALSE.equals(menu.hasManageChannel());
    }

    private boolean isManageableContentEntry(MenuNode menu) {
        return isManageablePageType(menu.pageType()) || isLegacyContentManageUrl(menu.url());
    }

    private String contentRoute(MenuNode menu) {
        return menu.id() == null ? null : "/admin/v2/cms/content/" + menu.id();
    }

    private boolean isLegacyContentManageUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int hashIndex = normalized.indexOf('#');
        if (hashIndex >= 0) {
            normalized = normalized.substring(0, hashIndex);
        }
        return normalized.equals("/admin/model_manage")
                || normalized.startsWith("/admin/model_manage/")
                || normalized.equals("/admin/model_manage/{id}")
                || normalized.equals("/admin/content/history/list")
                || normalized.startsWith("/admin/content/history/list/")
                || normalized.equals("/admin/check/index")
                || normalized.startsWith("/admin/check/index/")
                || normalized.equals("/admin/check/detail")
                || normalized.equals("/admin/check/check");
    }

    private boolean isSinglePageNonContent(MenuNode menu) {
        return isSinglePageEntry(menu);
    }

    private boolean isSinglePageEntry(MenuNode menu) {
        return Objects.equals(menu.pageType(), 0) || isLegacySinglePageManageUrl(menu.url());
    }

    private boolean isLegacySinglePageManageUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int hashIndex = normalized.indexOf('#');
        if (hashIndex >= 0) {
            normalized = normalized.substring(0, hashIndex);
        }
        return normalized.equals("/admin/menu_manage")
                || normalized.startsWith("/admin/menu_manage/")
                || normalized.equals("/admin/menu_manage/{id}");
    }

    private boolean isExternalLink(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.startsWith("http://")
                || normalized.startsWith("https://")
                || normalized.startsWith("//");
    }

    private static Object legacyFieldValue(Object target, String fieldName) {
        try {
            Field field = EntityFields.find(target.getClass(), fieldName);
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static void legacyFieldSet(Object target, String fieldName, Object value) {
        try {
            Field field = EntityFields.find(target.getClass(), fieldName);
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            if (value == null && field.getType().isPrimitive()) {
                return;
            }
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException ignored) {
            // 忽略过期字段配置或类型不匹配的字段
        }
    }

    private static String legacyNormalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text.trim();
    }

    private List<ContentFieldDefinition> parseOptions(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<ContentFieldDefinition> options = ContentModelCodec.read(json);
            return options == null ? List.of() : options;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** 单页字段按 sort 降序（大值在前），与列表/编辑字段顺序（NativeContentAdminQueryService）一致。 */
    private List<ContentFieldDefinition> parseSortedMenuFields(String json) {
        List<ContentFieldDefinition> options =
                parseOptions(json).stream()
                        .filter(
                                field ->
                                        !ContentExtensionFields.isName(field.getFieldName())
                                                || ContentFieldDefinition.enabled(
                                                        field.getIsOpen()))
                        .toList();
        if (options.size() <= 1) {
            return options;
        }
        List<ContentFieldDefinition> sorted = new ArrayList<>(options);
        sorted.sort(
                (a, b) ->
                        Integer.compare(
                                b.getSort() != null ? b.getSort() : 0,
                                a.getSort() != null ? a.getSort() : 0));
        return sorted;
    }

    private List<ContentFieldDefinition> parseOptionsForWrite(
            String json, String storageKey, String action) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<ContentFieldDefinition> options = ContentModelCodec.read(json);
            return options == null ? List.of() : options;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "历史字段"
                            + fieldStorageLabel(storageKey)
                            + " JSON 解析失败，请先修复 "
                            + storageKey
                            + " 后再"
                            + action);
        }
    }

    private String fieldStorageLabel(String storageKey) {
        if (Objects.equals(storageKey, "modelList")) {
            return "列表";
        }
        if (Objects.equals(storageKey, "modelSearch")) {
            return "搜索";
        }
        return "配置";
    }

    private int legacyCatalogType(Integer fieldStorageType) {
        return Objects.equals(fieldStorageType, 1) ? 0 : 1;
    }

    private Integer requireFieldType(SetFieldDto body) {
        if (body == null) {
            throw new IllegalArgumentException("字段配置不能为空");
        }
        if (!Objects.equals(body.getType(), 0) && !Objects.equals(body.getType(), 1)) {
            throw new IllegalArgumentException("字段配置类型无效");
        }
        return body.getType();
    }

    private static List<ContentFieldDefinition> extensionCatalog(
            List<ContentFieldDefinition> catalog, List<ContentFieldDefinition> saved) {
        List<ContentFieldDefinition> result = new ArrayList<>(catalog);
        for (ContentFieldDefinition field : saved)
            if (ContentExtensionFields.isName(field.getFieldName())) result.add(field);
        return result;
    }

    private void validateFieldPayload(SetFieldDto body, List<ContentFieldDefinition> catalog) {
        List<ContentFieldDefinition> enabledOptions =
                body.getFieldOptions() == null ? List.of() : body.getFieldOptions();
        body.setFieldOptions(enabledOptions);
        // 允许清空配置：未启用任何字段时，列表/搜索字段也应为空，无需校验
        if (enabledOptions.isEmpty()) {
            return;
        }
        Set<String> enabledFields = validateFieldNames(enabledOptions, "启用字段");
        Set<String> catalogFields =
                (catalog == null ? List.<ContentFieldDefinition>of() : catalog)
                        .stream()
                                .map(ContentFieldDefinition::getFieldName)
                                .filter(StringUtils::isNotBlank)
                                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ContentFieldDefinition field : enabledOptions) {
            if (ContentExtensionFields.isName(field.getFieldName())) {
                ContentExtensionFields.validateDefinition(field);
                if ("Editor".equals(field.getFieldType()) && field.getDefaultValue() != null)
                    field.setDefaultValue(
                            com.xinhongru.cms.content.record.ContentExtensionHtml.clean(
                                    field.getDefaultValue()));
            } else if (!catalogFields.contains(field.getFieldName())) {
                throw new IllegalArgumentException("启用字段必须来自字段目录或合法新增业务字段");
            }
        }
        for (ContentFieldDefinition field :
                body.getFieldListDisplay() == null
                        ? List.<ContentFieldDefinition>of()
                        : body.getFieldListDisplay())
            validateExtensionQueryConfiguration(field, enabledOptions, false);
        for (ContentFieldDefinition field :
                body.getFieldSearch() == null
                        ? List.<ContentFieldDefinition>of()
                        : body.getFieldSearch())
            validateExtensionQueryConfiguration(field, enabledOptions, true);
        validateKnownFieldTypes(body.getFieldOptions());
        validateKnownFieldTypes(body.getFieldListDisplay());
        validateKnownFieldTypes(body.getFieldSearch());
        if (Objects.equals(body.getType(), 0)
                && (body.getFieldListDisplay() == null || body.getFieldListDisplay().isEmpty())) {
            throw new IllegalArgumentException("列表模式至少选择一个列表字段");
        }
        if (Objects.equals(body.getType(), 0)
                && (body.getFieldSearch() == null || body.getFieldSearch().isEmpty())) {
            throw new IllegalArgumentException("列表模式至少选择一个搜索字段");
        }
        if (Objects.equals(body.getType(), 0)) {
            validateFieldSubset(body.getFieldListDisplay(), enabledFields, "列表字段");
            validateFieldSubset(body.getFieldSearch(), enabledFields, "搜索字段");
        }
    }

    private static void validateExtensionQueryConfiguration(
            ContentFieldDefinition selected, List<ContentFieldDefinition> fields, boolean search) {
        if (!ContentExtensionFields.isName(selected.getFieldName())) return;
        ContentFieldDefinition actual =
                fields.stream()
                        .filter(
                                field ->
                                        Objects.equals(
                                                field.getFieldName(), selected.getFieldName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("新增查询字段未在模型中定义"));
        if (!ContentExtensionFields.supportsQuery(actual)
                || !ContentFieldDefinition.enabled(actual.getIsOpen())
                || !ContentFieldDefinition.enabled(
                        search ? actual.getIsSearch() : actual.getIsList())
                || !Objects.equals(actual.getFieldType(), selected.getFieldType()))
            throw new IllegalArgumentException("新增查询字段仅支持模型已启用并授权的文本、固定枚举和时间字段");
    }

    private void validateKnownFieldTypes(List<ContentFieldDefinition> fields) {
        for (ContentFieldDefinition field :
                fields == null ? List.<ContentFieldDefinition>of() : fields) {
            if (StringUtils.isBlank(field.getFieldType())) {
                continue;
            }
            if (!LEGACY_FIELD_TYPES.contains(field.getFieldType())) {
                throw new IllegalArgumentException("未知字段类型：" + field.getFieldType());
            }
        }
    }

    private void validatePresetRequest(FieldPresetRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("字段方案不能为空");
        }
        if (!Objects.equals(body.type(), 0) && !Objects.equals(body.type(), 1)) {
            throw new IllegalArgumentException("字段配置类型无效");
        }
        if (!FieldPreset.supports(body.presetKey())) {
            throw new IllegalArgumentException("字段方案无效");
        }
    }

    private List<FieldAttributeGuide> fieldAttributeGuides() {
        return List.of(
                new FieldAttributeGuide(
                        "isOpen",
                        "启用",
                        "进入 modelField/menuField 旧字段 JSON，决定内容录入是否显示该字段。",
                        "关闭后旧内容中的字段值仍保留，但新录入不会展示。"),
                new FieldAttributeGuide(
                        "isList",
                        "列表显示",
                        "进入 modelList 旧 JSON，决定内容列表页和旧后台列表列。",
                        "列表列过多会影响旧后台表格可读性。"),
                new FieldAttributeGuide(
                        "isSearch", "搜索", "进入 modelSearch 旧 JSON，决定旧内容列表可搜索字段。", "仅适合标题、时间等可索引字段。"),
                new FieldAttributeGuide("isColor", "颜色", "按旧颜色字段控件渲染，保存颜色值。", "普通文本误设后会改变录入控件。"),
                new FieldAttributeGuide(
                        "isCoordinate", "坐标", "按旧坐标字段控件渲染，存储经纬度或坐标文本。", "旧模板若按文本读取，需要确认格式兼容。"),
                new FieldAttributeGuide("isReadOnly", "只读", "内容录入时展示但不允许编辑。", "旧数据修复场景可能需要临时关闭。"),
                new FieldAttributeGuide(
                        "isRequired",
                        "必填",
                        "进入 modelField/menuField 校验配置。",
                        "旧数据为空时需要补录，否则后续保存可能被阻断。"),
                new FieldAttributeGuide("isTel", "电话", "启用旧电话格式校验。", "历史电话格式不统一时可能保存失败。"),
                new FieldAttributeGuide(
                        "isEmail", "邮箱", "启用旧邮箱格式校验。", "会触发邮箱格式校验，旧数据格式不一致时保存可能失败。"),
                new FieldAttributeGuide("isIdCard", "身份证", "启用旧身份证格式校验。", "仅应用于真实证件号字段。"),
                new FieldAttributeGuide("isNumber", "数字", "启用旧数字格式校验。", "带单位或范围的文本不适合启用。"),
                new FieldAttributeGuide("isWebUrl", "网址", "启用旧网址格式校验。", "相对路径或站内短链可能不符合完整 URL 校验。"),
                new FieldAttributeGuide("defaultValue", "默认值", "写入旧字段默认值，新内容录入时预填。", "不会批量修改已有内容。"),
                new FieldAttributeGuide("bak", "备注", "作为旧后台字段说明或占位提示。", "只影响说明，不改变模板输出。"),
                new FieldAttributeGuide(
                        "maxLength", "长度", "控制旧文本字段最大长度。", "小于历史值长度时，后续保存可能被截断或失败。"),
                new FieldAttributeGuide("sort", "排序", "控制旧字段录入和展示顺序。", "排序相同会按旧系统默认顺序兜底。"));
    }

    private Set<String> validateFieldNames(List<ContentFieldDefinition> fields, String label) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (ContentFieldDefinition field : fields) {
            if (field == null || StringUtils.isBlank(field.getFieldName())) {
                throw new IllegalArgumentException(label + "必须包含字段标识");
            }
            if (!field.getFieldName().matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("字段标识只能包含字母、数字和下划线：" + field.getFieldName());
            }
            if (!fieldNames.add(field.getFieldName())) {
                throw new IllegalArgumentException(label + "不能重复：" + field.getFieldName());
            }
        }
        return fieldNames;
    }

    private void validateFieldSubset(
            List<ContentFieldDefinition> fields, Set<String> enabledFields, String label) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Set<String> fieldNames = validateFieldNames(fields, label);
        if (!enabledFields.containsAll(fieldNames)) {
            throw new IllegalArgumentException(label + "必须来自已启用字段");
        }
    }

    private List<ContentFieldDefinition> parseCurrentFieldOptions(
            OneshotEntity menu, Integer type) {
        return parseOptions(Objects.equals(type, 1) ? menu.getMenuField() : menu.getModelField());
    }

    private Map<String, ContentFieldDefinition> fieldOptionsByName(
            List<ContentFieldDefinition> options) {
        Map<String, ContentFieldDefinition> fieldsByName = new LinkedHashMap<>();
        for (ContentFieldDefinition option : options) {
            if (option != null && StringUtils.isNotBlank(option.getFieldName())) {
                fieldsByName.put(option.getFieldName(), option);
            }
        }
        return fieldsByName;
    }

    private List<ContentFieldDefinition> presetOptions(
            List<ContentFieldDefinition> catalog,
            FieldPreset preset,
            List<ContentFieldDefinition> existingOptions) {
        Map<String, ContentFieldDefinition> existingByName = fieldOptionsByName(existingOptions);
        Set<String> presetFieldNames = new LinkedHashSet<>();
        List<ContentFieldDefinition> options = new ArrayList<>();
        for (ContentFieldDefinition field : catalog) {
            String alias = preset.resolve(field.getFieldName());
            if (alias == null) {
                continue;
            }
            presetFieldNames.add(field.getFieldName());
            ContentFieldDefinition existing = existingByName.get(field.getFieldName());
            options.add(
                    new ContentFieldDefinition()
                            .setIsOpen("on")
                            .setFieldName(field.getFieldName())
                            .setFromName(
                                    optionText(
                                            existing == null ? null : existing.getFromName(),
                                            field.getFromName()))
                            .setFieldType(
                                    optionText(
                                            existing == null ? null : existing.getFieldType(),
                                            field.getFieldType()))
                            .setIsList(preset.listAliases().contains(alias) ? "on" : "")
                            .setIsColor(
                                    optionFlag(
                                            existing == null ? null : existing.getIsColor(),
                                            field.getIsColor()))
                            .setIsCoordinate(
                                    optionFlag(
                                            existing == null ? null : existing.getIsCoordinate(),
                                            field.getIsCoordinate()))
                            .setIsSearch(preset.searchAliases().contains(alias) ? "on" : "")
                            .setIsReadOnly(
                                    optionFlag(
                                            existing == null ? null : existing.getIsReadOnly(),
                                            field.getIsReadOnly()))
                            .setIsRequired(
                                    preset.requiredAliases().contains(alias)
                                            ? "on"
                                            : optionFlag(
                                                    existing == null
                                                            ? null
                                                            : existing.getIsRequired(),
                                                    field.getIsRequired()))
                            .setIsTel(
                                    optionFlag(
                                            existing == null ? null : existing.getIsTel(),
                                            field.getIsTel()))
                            .setIsEmail(
                                    optionFlag(
                                            existing == null ? null : existing.getIsEmail(),
                                            field.getIsEmail()))
                            .setIsIdCard(
                                    optionFlag(
                                            existing == null ? null : existing.getIsIdCard(),
                                            field.getIsIdCard()))
                            .setIsNumber(
                                    optionFlag(
                                            existing == null ? null : existing.getIsNumber(),
                                            field.getIsNumber()))
                            .setIsWebUrl(
                                    "linkUrl".equals(alias)
                                            ? "on"
                                            : optionFlag(
                                                    existing == null
                                                            ? null
                                                            : existing.getIsWebUrl(),
                                                    field.getIsWebUrl()))
                            .setTitle(
                                    optionText(
                                            existing == null ? null : existing.getTitle(),
                                            preset.titles()
                                                    .getOrDefault(
                                                            alias,
                                                            StringUtils.defaultIfBlank(
                                                                    field.getTitle(),
                                                                    field.getFromName()))))
                            .setDefaultValue(
                                    optionText(
                                            existing == null ? null : existing.getDefaultValue(),
                                            field.getDefaultValue()))
                            .setBak(
                                    optionText(
                                            existing == null ? null : existing.getBak(),
                                            field.getBak()))
                            .setMaxLength(
                                    optionNumber(
                                            existing == null ? null : existing.getMaxLength(),
                                            field.getMaxLength()))
                            .setSort(
                                    optionNumber(
                                            existing == null ? null : existing.getSort(),
                                            field.getSort())));
        }
        for (ContentFieldDefinition existing :
                existingOptions == null ? List.<ContentFieldDefinition>of() : existingOptions) {
            if (existing != null
                    && StringUtils.isNotBlank(existing.getFieldName())
                    && !presetFieldNames.contains(existing.getFieldName())) {
                options.add(existing);
            }
        }
        return options;
    }

    private Set<String> presetManagedFieldNames(
            List<ContentFieldDefinition> catalog, FieldPreset preset) {
        return (catalog == null ? List.<ContentFieldDefinition>of() : catalog)
                .stream()
                        .filter(
                                field ->
                                        field != null
                                                && preset.resolve(field.getFieldName()) != null)
                        .map(ContentFieldDefinition::getFieldName)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<ContentFieldDefinition> presetListOrSearchFields(
            List<ContentFieldDefinition> options,
            List<ContentFieldDefinition> currentFields,
            Set<String> presetManagedNames,
            boolean listField) {
        List<ContentFieldDefinition> merged =
                new ArrayList<>(
                        (options == null ? List.<ContentFieldDefinition>of() : options)
                                .stream()
                                        .filter(
                                                field ->
                                                        listField
                                                                ? "on".equals(field.getIsList())
                                                                : "on".equals(field.getIsSearch()))
                                        .toList());
        Set<String> mergedNames =
                merged.stream()
                        .map(ContentFieldDefinition::getFieldName)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> optionNames =
                (options == null ? List.<ContentFieldDefinition>of() : options)
                        .stream()
                                .map(ContentFieldDefinition::getFieldName)
                                .filter(StringUtils::isNotBlank)
                                .collect(Collectors.toSet());
        for (ContentFieldDefinition current :
                currentFields == null ? List.<ContentFieldDefinition>of() : currentFields) {
            if (current == null || StringUtils.isBlank(current.getFieldName())) {
                continue;
            }
            if (presetManagedNames.contains(current.getFieldName())
                    || !optionNames.contains(current.getFieldName())
                    || mergedNames.contains(current.getFieldName())) {
                continue;
            }
            merged.add(current);
            mergedNames.add(current.getFieldName());
        }
        return merged;
    }

    private FieldPresetDiff presetDiff(
            List<ContentFieldDefinition> existingOptions,
            List<ContentFieldDefinition> nextOptions,
            FieldPreset preset) {
        Map<String, ContentFieldDefinition> existing = fieldOptionsByName(existingOptions);
        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Set<String> nextNames = new LinkedHashSet<>();
        for (ContentFieldDefinition next :
                nextOptions == null ? List.<ContentFieldDefinition>of() : nextOptions) {
            if (next == null || StringUtils.isBlank(next.getFieldName())) {
                continue;
            }
            nextNames.add(next.getFieldName());
            ContentFieldDefinition before = existing.get(next.getFieldName());
            if (before == null) {
                added.add(next.getFieldName());
            } else if (fieldOptionChanged(before, next)) {
                modified.add(next.getFieldName());
            } else {
                retained.add(next.getFieldName());
            }
        }
        for (String beforeName : existing.keySet()) {
            if (!nextNames.contains(beforeName)) {
                removed.add(beforeName);
            }
        }
        String summary =
                "新增字段："
                        + formatFieldNames(added)
                        + "\n修改字段："
                        + formatFieldNames(modified)
                        + "\n保留字段："
                        + formatFieldNames(retained)
                        + (removed.isEmpty() ? "" : "\n将禁用/移除字段：" + formatFieldNames(removed));
        return new FieldPresetDiff(
                preset.key(), false, added, modified, retained, removed, summary, null);
    }

    private String presetPreviewChecksum(
            OneshotEntity menu,
            Integer type,
            String presetKey,
            List<ContentFieldDefinition> currentOptions,
            List<ContentFieldDefinition> currentListDisplay,
            List<ContentFieldDefinition> currentFieldSearch,
            List<ContentFieldDefinition> nextOptions,
            List<ContentFieldDefinition> nextListDisplay,
            List<ContentFieldDefinition> nextFieldSearch) {
        String source =
                String.join(
                        "\n",
                        "type=" + type,
                        "preset=" + StringUtils.defaultString(presetKey),
                        "optionsRaw="
                                + StringUtils.defaultString(
                                        Objects.equals(type, 1)
                                                ? menu.getMenuField()
                                                : menu.getModelField()),
                        "listRaw=" + StringUtils.defaultString(menu.getModelList()),
                        "searchRaw=" + StringUtils.defaultString(menu.getModelSearch()),
                        "currentOptions=" + JSON.toJSONString(currentOptions),
                        "currentList=" + JSON.toJSONString(currentListDisplay),
                        "currentSearch=" + JSON.toJSONString(currentFieldSearch),
                        "nextOptions=" + JSON.toJSONString(nextOptions),
                        "nextList=" + JSON.toJSONString(nextListDisplay),
                        "nextSearch=" + JSON.toJSONString(nextFieldSearch));
        return digest(source);
    }

    private String digest(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] bytes =
                    sha256.digest(
                            StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            return "len:" + StringUtils.defaultString(value).length();
        }
    }

    private boolean fieldOptionChanged(ContentFieldDefinition before, ContentFieldDefinition next) {
        return !Objects.equals(
                        StringUtils.defaultString(before.getTitle()),
                        StringUtils.defaultString(next.getTitle()))
                || !Objects.equals(flag(before.getIsList()), flag(next.getIsList()))
                || !Objects.equals(flag(before.getIsSearch()), flag(next.getIsSearch()))
                || !Objects.equals(flag(before.getIsRequired()), flag(next.getIsRequired()))
                || !Objects.equals(flag(before.getIsWebUrl()), flag(next.getIsWebUrl()));
    }

    private String formatFieldNames(List<String> names) {
        return names == null || names.isEmpty() ? "无" : String.join("、", names);
    }

    private List<FieldInspection> fieldInspections(
            List<ContentFieldDefinition> fieldOptions,
            List<ContentFieldDefinition> fieldListDisplay,
            List<ContentFieldDefinition> fieldSearch) {
        Set<String> listNames =
                fieldListDisplay == null
                        ? Set.of()
                        : fieldListDisplay.stream()
                                .filter(Objects::nonNull)
                                .map(ContentFieldDefinition::getFieldName)
                                .filter(StringUtils::isNotBlank)
                                .collect(Collectors.toSet());
        Set<String> searchNames =
                fieldSearch == null
                        ? Set.of()
                        : fieldSearch.stream()
                                .filter(Objects::nonNull)
                                .map(ContentFieldDefinition::getFieldName)
                                .filter(StringUtils::isNotBlank)
                                .collect(Collectors.toSet());
        return (fieldOptions == null ? List.<ContentFieldDefinition>of() : fieldOptions)
                .stream()
                        .filter(Objects::nonNull)
                        .map(
                                field ->
                                        new FieldInspection(
                                                field.getFieldName(),
                                                field.getFieldType(),
                                                StringUtils.defaultIfBlank(
                                                        field.getFromName(), field.getTitle()),
                                                StringUtils.defaultString(field.getDefaultValue()),
                                                stringFlag(field.getIsRequired()),
                                                stringFlag(field.getIsOpen()),
                                                stringFlag(field.getIsList())
                                                        || listNames.contains(field.getFieldName()),
                                                stringFlag(field.getIsSearch())
                                                        || searchNames.contains(
                                                                field.getFieldName()),
                                                stringFlag(field.getIsReadOnly()),
                                                isTitleField(field),
                                                validationRules(field),
                                                StringUtils.defaultString(field.getBak()),
                                                field.getSort() == null ? 0 : field.getSort()))
                        .toList();
    }

    private LegacyFieldAudit legacyAudit(
            String storageKey,
            String rawJson,
            List<ContentFieldDefinition> fieldOptions,
            List<ContentFieldDefinition> catalog) {
        Set<String> catalogNames =
                (catalog == null ? List.<ContentFieldDefinition>of() : catalog)
                        .stream()
                                .map(ContentFieldDefinition::getFieldName)
                                .filter(StringUtils::isNotBlank)
                                .collect(Collectors.toSet());
        List<String> unrecognizedItems = new ArrayList<>();
        List<String> repairSuggestions = new ArrayList<>();
        for (ContentFieldDefinition field :
                fieldOptions == null ? List.<ContentFieldDefinition>of() : fieldOptions) {
            if (field == null || StringUtils.isBlank(field.getFieldName())) {
                continue;
            }
            if (StringUtils.isNotBlank(field.getFieldType())
                    && !LEGACY_FIELD_TYPES.contains(field.getFieldType())) {
                unrecognizedItems.add(field.getFieldName() + ": " + field.getFieldType());
                repairSuggestions.add(
                        "字段 "
                                + field.getFieldName()
                                + " 使用无法识别的旧控件 "
                                + field.getFieldType()
                                + "，请改为 SingleLine/MultiLine/Editor/Image/File/DateTime/Number/Enum"
                                + " 等受支持类型");
            } else if (!catalogNames.isEmpty() && !catalogNames.contains(field.getFieldName())) {
                unrecognizedItems.add(field.getFieldName() + ": 不在字段目录");
                repairSuggestions.add(
                        "字段 " + field.getFieldName() + " 不在当前字段目录，请确认栏目类型或补充字段目录后再保存");
            }
        }
        String summary = StringUtils.defaultString(rawJson);
        if (summary.length() > 800) {
            summary = summary.substring(0, 800) + "...";
        }
        return new LegacyFieldAudit(
                storageKey,
                summary,
                fieldOptions == null ? 0 : fieldOptions.size(),
                unrecognizedItems,
                repairSuggestions);
    }

    private boolean isTitleField(ContentFieldDefinition field) {
        String normalizedFieldName = normalizeFieldName(field.getFieldName());
        String normalizedTitle =
                normalizeFieldName(
                        StringUtils.defaultIfBlank(field.getTitle(), field.getFromName()));
        return "title".equals(normalizedFieldName)
                || "classname".equals(normalizedFieldName)
                || "标题".equals(normalizedTitle)
                || normalizedTitle.contains("title");
    }

    private List<String> validationRules(ContentFieldDefinition field) {
        List<String> rules = new ArrayList<>();
        if (stringFlag(field.getIsRequired())) {
            rules.add("required");
        }
        if (stringFlag(field.getIsTel())) {
            rules.add("tel");
        }
        if (stringFlag(field.getIsEmail())) {
            rules.add("email");
        }
        if (stringFlag(field.getIsIdCard())) {
            rules.add("idCard");
        }
        if (stringFlag(field.getIsNumber())) {
            rules.add("number");
        }
        if (stringFlag(field.getIsWebUrl())) {
            rules.add("webUrl");
        }
        return rules;
    }

    private boolean stringFlag(String value) {
        return "on".equals(value) || "true".equals(value) || "1".equals(value);
    }

    private String optionText(String current, String fallback) {
        return current == null ? StringUtils.defaultString(fallback) : current;
    }

    private Integer optionNumber(Integer current, Integer fallback) {
        return current == null ? fallback == null ? 0 : fallback : current;
    }

    private String optionFlag(String current, String fallback) {
        return "on".equals(flag(current)) ? "on" : flag(fallback);
    }

    private String flag(String value) {
        return "on".equals(value) || "true".equals(value) || "1".equals(value) ? "on" : "";
    }

    private static String normalizeFieldName(String value) {
        return StringUtils.defaultString(value).replaceAll("[_\\-\\s]", "").toLowerCase();
    }

    private void requireSuccess(ResultVO<?> result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(
                    result == null || result.getMsg() == null ? "操作失败" : result.getMsg());
        }
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.isNumeric(text)) {
            return Integer.parseInt(text);
        }
        throw new IllegalStateException("保存成功但未返回栏目 ID");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private List<TemplateBindingSummary> templateBindingSummaries(List<OneshotEntity> menus) {
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }
        return menus.stream()
                .filter(menu -> menu.getId() != null)
                .map(this::templateBindingSummary)
                .toList();
    }

    private TemplateBindingSummary templateBindingSummary(OneshotEntity menu) {
        Map<String, Object> setting = templateBindingSetting(menu);
        List<String> currentTemplatePaths = currentTemplatePaths(menu);
        List<String> suggestedTemplatePaths =
                firstNonEmptyStrings(
                        setting.get("suggestedTemplatePaths"),
                        setting.get("templatePaths"),
                        setting.get("templates"),
                        setting.get("paths"));
        List<Map<String, Object>> relationCandidates =
                firstNonEmptyRecords(
                        setting.get("relationCandidates"),
                        setting.get("complexRelations"),
                        setting.get("relations"),
                        setting.get("relationships"));
        LastAppliedRecord lastAppliedRecord = lastAppliedRecord(setting.get("lastAppliedRecord"));
        return new TemplateBindingSummary(
                menu.getId(),
                currentTemplatePaths,
                suggestedTemplatePaths,
                number(setting.get("aiConfidence")),
                0,
                0,
                0,
                relationCandidates.size(),
                number(setting.get("previewIssueCount")),
                lastAppliedRecord);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> templateBindingSetting(OneshotEntity menu) {
        if (StringUtils.isBlank(menu.getSetting())) {
            return Map.of();
        }
        try {
            Object parsed = parseJsonObject(menu.getSetting());
            if (!(parsed instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object templateBinding = root.get("templateBinding");
            if (templateBinding instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return (Map<String, Object>) root;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private Object parseJsonObject(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Object parsed = JSON.parse(json);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private List<String> currentTemplatePaths(OneshotEntity menu) {
        return uniqueText(
                List.of(
                        StringUtils.defaultString(menu.getListPageTemplate()),
                        StringUtils.defaultString(menu.getDetailPageTemplate())));
    }

    private LastAppliedRecord lastAppliedRecord(Object value) {
        Map<String, Object> record = record(value);
        if (record.isEmpty()) {
            return null;
        }
        return new LastAppliedRecord(
                longNumber(record.get("batchId")),
                text(record.get("appliedBy")),
                text(record.get("appliedAt")),
                longNumber(record.get("auditContractId")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> record(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<Map<String, Object>> records(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> record = record(item);
            if (!record.isEmpty()) {
                records.add(record);
            }
        }
        return records;
    }

    private List<Map<String, Object>> firstNonEmptyRecords(Object... values) {
        for (Object value : values) {
            List<Map<String, Object>> records = records(value);
            if (!records.isEmpty()) {
                return records;
            }
        }
        return List.of();
    }

    private List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return uniqueText(list.stream().map(this::text).toList());
        }
        String text = text(value);
        return StringUtils.isBlank(text) ? List.of() : List.of(text);
    }

    private List<String> firstNonEmptyStrings(Object... values) {
        for (Object value : values) {
            List<String> strings = strings(value);
            if (!strings.isEmpty()) {
                return strings;
            }
        }
        return List.of();
    }

    private List<String> uniqueText(List<String> values) {
        return values.stream()
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Object value) {
        Long number = longNumber(value);
        return number == null ? 0 : number.intValue();
    }

    private Long longNumber(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String message(RuntimeException ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    public record SiteSummary(
            Long siteId,
            Integer rootMenuId,
            String siteName,
            String siteCode,
            boolean globalMode,
            String siteMode,
            TemplatePackageInfo templatePackage) {}

    public record TemplatePackageInfo(
            String name, String status, Long currentBatchId, Long lastRecognitionBatchId) {}

    public record MenuWorkspaceResponse(
            SiteSummary currentSite, List<MenuNode> flat, List<MenuNode> tree) {}

    public record ContentNavigationResponse(
            Long siteId,
            Integer rootMenuId,
            SiteSummary currentSite,
            List<MenuNode> menus,
            List<MenuNode> tree) {}

    public record TemplateWorkspaceResponse(
            SiteSummary currentSite,
            List<MenuNode> flat,
            List<MenuNode> tree,
            List<NodeVo> builderNodes,
            List<TemplateBindingSummary> bindingSummaries) {}

    public record TemplateBindingSummary(
            Integer menuId,
            List<String> currentTemplatePaths,
            List<String> suggestedTemplatePaths,
            int aiConfidence,
            int fieldCandidateCount,
            int unconfirmedFieldCount,
            int lowConfidenceCount,
            int relationCandidateCount,
            int previewIssueCount,
            LastAppliedRecord lastAppliedRecord) {}

    public record LastAppliedRecord(
            Long batchId, String appliedBy, String appliedAt, Long auditContractId) {}

    public record MenuNode(
            Integer id,
            Integer parentId,
            String className,
            String classEnName,
            String parentName,
            Integer sort,
            Integer pageType,
            Boolean hasManageChannel,
            Boolean hasFrontChannel,
            Boolean isSystem,
            Boolean isCanDeleted,
            Boolean isCanEdit,
            Boolean isCanSort,
            String url,
            String listPageTemplate,
            String detailPageTemplate,
            Long siteProjectId,
            Integer siteRootId,
            String bizCode,
            @JsonProperty("manageable") Boolean manageable,
            @JsonProperty("manageReason") String manageReason,
            @JsonProperty("contentRoute") String contentRoute,
            Boolean contentManageable,
            Boolean navigationOnly,
            Boolean siteScoped,
            String invalidReason,
            Boolean hasChildren,
            List<MenuNode> children,
            Boolean isSubMenu,
            String listPageUrl,
            String detailPageUrl) {
        static MenuNode from(OneshotEntity item) {
            return new MenuNode(
                    item.getId(),
                    item.getParentId(),
                    normalizeMenuName(item.getClassName()),
                    item.getClassEnName(),
                    null,
                    item.getSort(),
                    item.getPageType(),
                    item.getHasManageChannel(),
                    item.getHasFrontChannel(),
                    item.getIsSystem(),
                    item.getIsCanDeleted(),
                    item.getIsCanEdit(),
                    item.getIsCanSort(),
                    item.getUrl(),
                    item.getListPageTemplate(),
                    item.getDetailPageTemplate(),
                    item.getSiteProjectId(),
                    item.getSiteRootId(),
                    item.getBizCode(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    item.getIsSubMenu(),
                    item.getListPageUrl(),
                    item.getDetailPageUrl());
        }

        MenuNode withCapabilities(
                boolean siteScoped,
                boolean contentManageable,
                String invalidReason,
                boolean manageable,
                String manageReason,
                String contentRoute,
                String parentName) {
            return new MenuNode(
                    id,
                    parentId,
                    className,
                    classEnName,
                    parentName,
                    sort,
                    pageType,
                    hasManageChannel,
                    hasFrontChannel,
                    isSystem,
                    isCanDeleted,
                    isCanEdit,
                    isCanSort,
                    url,
                    listPageTemplate,
                    detailPageTemplate,
                    siteProjectId,
                    siteRootId,
                    bizCode,
                    manageable,
                    manageReason,
                    contentRoute,
                    contentManageable,
                    !contentManageable,
                    siteScoped,
                    invalidReason,
                    hasChildren,
                    children,
                    isSubMenu,
                    listPageUrl,
                    detailPageUrl);
        }
    }

    public record MenuDetail(
            Integer id,
            Integer parentId,
            String className,
            String classEnName,
            Integer sort,
            Integer pageType,
            Boolean hasManageChannel,
            Boolean hasFrontChannel,
            String url,
            String identifies,
            String listPageUrl,
            String listPageTemplate,
            String detailPageUrl,
            String detailPageTemplate,
            String seoTitle,
            String seoKeywords,
            String seoDescription,
            String setting,
            Long siteProjectId,
            Integer siteRootId,
            String bizCode,
            Boolean hasMenuField,
            Boolean hasModelField,
            Boolean hasChildren,
            Boolean isSubMenu,
            Boolean isCanDeleted) {
        static MenuDetail from(OneshotEntity item) {
            return new MenuDetail(
                    item.getId(),
                    item.getParentId(),
                    normalizeMenuName(item.getClassName()),
                    item.getClassEnName(),
                    item.getSort(),
                    item.getPageType(),
                    item.getHasManageChannel(),
                    item.getHasFrontChannel(),
                    item.getUrl(),
                    item.getIdentifies(),
                    item.getListPageUrl(),
                    item.getListPageTemplate(),
                    item.getDetailPageUrl(),
                    item.getDetailPageTemplate(),
                    item.getSeoTitle(),
                    item.getSeoKeywords(),
                    item.getSeoDescription(),
                    item.getSetting(),
                    item.getSiteProjectId(),
                    item.getSiteRootId(),
                    item.getBizCode(),
                    hasFieldConfig(item.getMenuField()),
                    hasFieldConfig(item.getModelField()),
                    null, // hasChildren 需要额外计算
                    item.getIsSubMenu(),
                    item.getIsCanDeleted());
        }

        static MenuDetail from(OneshotEntity item, boolean hasChildren) {
            return new MenuDetail(
                    item.getId(),
                    item.getParentId(),
                    normalizeMenuName(item.getClassName()),
                    item.getClassEnName(),
                    item.getSort(),
                    item.getPageType(),
                    item.getHasManageChannel(),
                    item.getHasFrontChannel(),
                    item.getUrl(),
                    item.getIdentifies(),
                    item.getListPageUrl(),
                    item.getListPageTemplate(),
                    item.getDetailPageUrl(),
                    item.getDetailPageTemplate(),
                    item.getSeoTitle(),
                    item.getSeoKeywords(),
                    item.getSeoDescription(),
                    item.getSetting(),
                    item.getSiteProjectId(),
                    item.getSiteRootId(),
                    item.getBizCode(),
                    hasFieldConfig(item.getMenuField()),
                    hasFieldConfig(item.getModelField()),
                    hasChildren,
                    item.getIsSubMenu(),
                    item.getIsCanDeleted());
        }

        private static boolean hasFieldConfig(String fieldJson) {
            if (fieldJson == null || fieldJson.isBlank()) return false;
            try {
                JSONArray arr = JSON.parseArray(fieldJson);
                return arr != null && !arr.isEmpty();
            } catch (Exception e) {
                return false;
            }
        }
    }

    public record BatchFieldOptions(
            Integer menuId, Integer type, List<ContentFieldDefinition> fieldOptions) {}

    public record FieldWorkspaceResponse(
            MenuDetail menu,
            Integer type,
            List<ContentFieldDefinition> fieldOptions,
            List<ContentFieldDefinition> fieldListDisplay,
            List<ContentFieldDefinition> fieldSearch,
            List<ContentFieldDefinition> catalog,
            List<FieldAttributeGuide> attributeGuides,
            List<FieldCandidateView> aiCandidates,
            List<FieldInspection> fieldInspections,
            LegacyFieldAudit legacyAudit,
            FieldPresetDiff presetDiff,
            String configDigest) {}

    public record FieldPresetRequest(
            Integer type, String presetKey, Boolean confirm, String previewChecksum) {}

    public record FieldAttributeGuide(String key, String title, String description, String risk) {}

    public record FieldCandidateView(
            Long id,
            String fieldName,
            String recommendedLegacyField,
            String recommendedTitle,
            Integer confidence,
            String source,
            String evidence,
            Boolean needManualConfirm,
            Boolean rulePersistenceEligible,
            String rulePersistenceMessage) {}

    public record FieldInspection(
            String fieldName,
            String controlType,
            String alias,
            String defaultValue,
            Boolean required,
            Boolean display,
            Boolean list,
            Boolean search,
            Boolean readOnly,
            Boolean titleField,
            List<String> validationRules,
            String remark,
            Integer sort) {}

    public record LegacyFieldAudit(
            String storageKey,
            String rawJsonSummary,
            Integer parsedFieldCount,
            List<String> unrecognizedItems,
            List<String> repairSuggestions) {}

    public record FieldPresetDiff(
            String presetKey,
            Boolean applied,
            List<String> added,
            List<String> modified,
            List<String> retained,
            List<String> removed,
            String summary,
            String previewChecksum) {
        private FieldPresetDiff asApplied() {
            return new FieldPresetDiff(
                    presetKey, true, added, modified, retained, removed, summary, previewChecksum);
        }

        private FieldPresetDiff withPreviewChecksum(String previewChecksum) {
            return new FieldPresetDiff(
                    presetKey,
                    applied,
                    added,
                    modified,
                    retained,
                    removed,
                    summary,
                    previewChecksum);
        }
    }

    private record FieldPreset(
            String key,
            List<String> aliases,
            List<String> listAliases,
            List<String> searchAliases,
            List<String> requiredAliases,
            Map<String, String> titles) {
        private static final Map<String, List<String>> FIELD_ALIASES =
                Map.ofEntries(
                        Map.entry(
                                "title",
                                List.of("title", "singleLine1", "Title", "name", "className")),
                        Map.entry("author", List.of("singleLine2", "author", "writer")),
                        Map.entry("source", List.of("singleLine3", "source", "origin")),
                        Map.entry(
                                "linkUrl",
                                List.of(
                                        "singleLine4",
                                        "linkUrl",
                                        "url",
                                        "webUrl",
                                        "outLink",
                                        "externalLink",
                                        "externalUrl",
                                        "link")),
                        Map.entry(
                                "summary",
                                List.of(
                                        "note1",
                                        "multiLine1",
                                        "summary",
                                        "intro",
                                        "description",
                                        "shortDescription",
                                        "brief",
                                        "note")),
                        Map.entry(
                                "content",
                                List.of(
                                        "content1",
                                        "content",
                                        "body",
                                        "text",
                                        "details",
                                        "detail")),
                        Map.entry(
                                "createTime",
                                List.of(
                                        "createTime",
                                        "publishTime",
                                        "pubTime",
                                        "releaseTime",
                                        "addTime")),
                        Map.entry(
                                "entryTime",
                                List.of(
                                        "entryIntoForceTime",
                                        "entryTime",
                                        "beginTime",
                                        "startTime",
                                        "effectiveTime",
                                        "validStartTime")),
                        Map.entry(
                                "expirationTime",
                                List.of(
                                        "expirationTime",
                                        "expireTime",
                                        "endTime",
                                        "invalidTime",
                                        "validEndTime")),
                        Map.entry(
                                "photo",
                                List.of(
                                        "photo1",
                                        "photo",
                                        "image",
                                        "img",
                                        "pic",
                                        "thumbnail",
                                        "cover")),
                        Map.entry(
                                "file",
                                List.of(
                                        "file1",
                                        "file",
                                        "attachment",
                                        "download",
                                        "fileUrl",
                                        "attach")));

        private static FieldPreset of(String key) {
            String normalizedKey = StringUtils.defaultIfBlank(key, "text-news");
            return switch (normalizedKey) {
                case "image-news" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of(
                                        "title",
                                        "author",
                                        "source",
                                        "linkUrl",
                                        "summary",
                                        "content",
                                        "createTime",
                                        "entryTime",
                                        "expirationTime",
                                        "photo"),
                                List.of("title", "photo", "createTime"),
                                List.of("title", "createTime"),
                                List.of("title", "photo"),
                                Map.of(
                                        "title",
                                        "标题",
                                        "author",
                                        "作者",
                                        "source",
                                        "来源",
                                        "linkUrl",
                                        "外部链接",
                                        "summary",
                                        "简介",
                                        "content",
                                        "内容",
                                        "createTime",
                                        "发布时间",
                                        "entryTime",
                                        "生效时间",
                                        "expirationTime",
                                        "过期时间",
                                        "photo",
                                        "图片"));
                case "download" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of(
                                        "title",
                                        "file",
                                        "createTime",
                                        "entryTime",
                                        "expirationTime"),
                                List.of("title", "file", "createTime"),
                                List.of("title", "createTime"),
                                List.of("title", "file"),
                                Map.of(
                                        "title",
                                        "标题",
                                        "file",
                                        "附件",
                                        "createTime",
                                        "发布时间",
                                        "entryTime",
                                        "生效时间",
                                        "expirationTime",
                                        "过期时间"));
                case "word-link" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of(
                                        "title",
                                        "linkUrl",
                                        "createTime",
                                        "entryTime",
                                        "expirationTime"),
                                List.of("title", "linkUrl", "createTime"),
                                List.of("title", "createTime"),
                                List.of("title", "linkUrl"),
                                Map.of(
                                        "title",
                                        "标题",
                                        "linkUrl",
                                        "外部链接",
                                        "createTime",
                                        "发布时间",
                                        "entryTime",
                                        "生效时间",
                                        "expirationTime",
                                        "过期时间"));
                case "image-link" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of(
                                        "title",
                                        "linkUrl",
                                        "photo",
                                        "createTime",
                                        "entryTime",
                                        "expirationTime"),
                                List.of("title", "photo", "linkUrl", "createTime"),
                                List.of("title", "createTime"),
                                List.of("title", "linkUrl", "photo"),
                                Map.of(
                                        "title",
                                        "标题",
                                        "linkUrl",
                                        "外部链接",
                                        "photo",
                                        "图片",
                                        "createTime",
                                        "发布时间",
                                        "entryTime",
                                        "生效时间",
                                        "expirationTime",
                                        "过期时间"));
                case "oneshot-page" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of("title", "summary", "content", "photo"),
                                List.of(),
                                List.of(),
                                List.of("title", "content"),
                                Map.of(
                                        "title", "标题", "summary", "简介", "content", "内容", "photo",
                                        "图片"));
                case "effective-expiration" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of("entryTime", "expirationTime"),
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of("entryTime", "生效时间", "expirationTime", "过期时间"));
                case "publish-time" ->
                        new FieldPreset(
                                normalizedKey,
                                List.of("createTime"),
                                List.of("createTime"),
                                List.of("createTime"),
                                List.of(),
                                Map.of("createTime", "发布时间"));
                default ->
                        new FieldPreset(
                                "text-news",
                                List.of(
                                        "title",
                                        "author",
                                        "source",
                                        "linkUrl",
                                        "summary",
                                        "content",
                                        "createTime",
                                        "entryTime",
                                        "expirationTime"),
                                List.of("title", "createTime"),
                                List.of("title", "createTime"),
                                List.of("title"),
                                Map.of(
                                        "title",
                                        "标题",
                                        "author",
                                        "作者",
                                        "source",
                                        "来源",
                                        "linkUrl",
                                        "外部链接",
                                        "summary",
                                        "简介",
                                        "content",
                                        "内容",
                                        "createTime",
                                        "发布时间",
                                        "entryTime",
                                        "生效时间",
                                        "expirationTime",
                                        "过期时间"));
            };
        }

        private static boolean supports(String key) {
            if (StringUtils.isBlank(key)) {
                return false;
            }
            return Set.of(
                            "text-news",
                            "image-news",
                            "download",
                            "word-link",
                            "image-link",
                            "oneshot-page",
                            "effective-expiration",
                            "publish-time")
                    .contains(key);
        }

        private String resolve(String fieldName) {
            return aliases.stream()
                    .filter(alias -> matches(fieldName, alias))
                    .findFirst()
                    .orElse(null);
        }

        private boolean matches(String fieldName, String alias) {
            String normalizedFieldName = normalizeFieldName(fieldName);
            return FIELD_ALIASES.getOrDefault(alias, List.of(alias)).stream()
                    .map(CmsCoreV2ApiController::normalizeFieldName)
                    .anyMatch(normalizedFieldName::equals);
        }
    }

    public record ContentListResponse(
            MenuDetail menu,
            List<ContentFieldDefinition> fields,
            List<ContentFieldDefinition> columns,
            List<BatchAction> batchActions,
            String previewUrlTemplate,
            List<ContentRow> rows,
            long total,
            int page,
            int pageSize) {}

    public record ContentDetailResponse(
            MenuDetail menu, List<ContentFieldDefinition> fields, ContentRow content) {}

    public record SinglePageDetailResponse(
            MenuDetail menu,
            List<ContentFieldDefinition> fields,
            SinglePageRow content,
            String status) {}

    public record SinglePageSaveRequest(Map<String, Object> values) {}

    public record SinglePageAuditRequest(Integer checkStatus, String checkDescription) {}

    public record ContentHistoryResponse(
            List<ContentHistoryItem> histories, List<ContentAuditRecordView> auditTrail) {}

    public record ContentHistoryItem(
            Integer oldContentId,
            Integer contentId,
            String title,
            String operatorName,
            String operatorAccount,
            LocalDateTime operationTime,
            Integer status,
            String statusText,
            String diffSummary,
            String updateDescription,
            List<LogTableDto> fields) {
        static ContentHistoryItem from(ContentHistoryEntity history) {
            boolean memberDirectory = MemberCredentials.isDirectory(history.getMenuId());
            List<LogTableDto> fields = parseHistoryFields(history.getComparison());
            if (memberDirectory) {
                // 旧比较数据仅记录显示名，无法安全识别其真实字段。
                fields = fields.stream()
                        .filter(Objects::nonNull)
                        .filter(field -> StringUtils.isNotBlank(field.getFieldKey()))
                        .filter(field -> !MemberCredentials.isPasswordField(history.getMenuId(), field.getFieldKey()))
                        .toList();
            }
            return new ContentHistoryItem(
                    history.getId(),
                    history.getContentId(),
                    history.getTitle(),
                    StringUtils.defaultIfBlank(history.getUserName(), history.getUserAccount()),
                    history.getUserAccount(),
                    history.getCreateTime(),
                    history.getCheckStatus(),
                    CmsCoreV2ApiController.contentAuditStatusText(history.getCheckStatus()),
                    memberDirectory ? history.getUpdateDescription() : StringUtils.defaultIfBlank(
                            history.getComparison(), history.getUpdateDescription()),
                    history.getUpdateDescription(),
                    fields);
        }

        private static List<LogTableDto> parseHistoryFields(String comparison) {
            if (StringUtils.isBlank(comparison)) {
                return List.of();
            }
            try {
                return JSON.parseArray(comparison, LogTableDto.class);
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    public record ContentAuditRecordResponse(List<ContentAuditRecordView> records) {}

    public record ContentAuditRecordView(
            Integer id,
            Integer contentId,
            String contentTitle,
            Integer contentHistoryId,
            Integer submitMenuId,
            String submitMenuName,
            String submitterName,
            String submitDescription,
            LocalDateTime submitTime,
            String checkerName,
            String checkDescription,
            LocalDateTime checkTime,
            Integer status,
            String statusText,
            String type) {
        static ContentAuditRecordView from(CheckRecordEntity record) {
            return new ContentAuditRecordView(
                    record.getId(),
                    record.getContentId(),
                    record.getContentTitle(),
                    record.getContentHistoryId(),
                    record.getSubmitMenuId(),
                    record.getSubmitMenuName(),
                    record.getSubmitUserName(),
                    record.getSubmitDescription(),
                    record.getSubmitTime(),
                    record.getCheckUserName(),
                    record.getCheckDescription(),
                    record.getCheckTime(),
                    record.getCheckStatus(),
                    CmsCoreV2ApiController.contentAuditStatusText(record.getCheckStatus()),
                    record.getType());
        }
    }

    public record ContentPreviewResponse(
            String previewUrl,
            String templatePath,
            boolean templateMissing,
            List<String> missingFields,
            List<String> publishTaskFailures,
            String readableError) {}

    public record BatchIdsRequest(List<Integer> ids) {}

    public record AuditContentRequest(List<Integer> ids, String action) {}

    public record AuditContentV2Request(
            List<Integer> ids, Integer checkStatus, String checkDescription) {}

    public record BatchFailure(Integer id, String title, String message) {}

    public record BatchSuccess(Integer id, String title, String message) {}

    public record BatchAction(
            String key,
            String label,
            Integer optionType,
            String action,
            Boolean danger,
            Boolean confirmRequired) {}

    public record RelationSummary(
            List<String> homepageRefs, List<String> menuRefs, List<String> relatedContents) {}

    public record BatchOperationResponse(
            int total,
            int successCount,
            int failureCount,
            List<BatchSuccess> successes,
            List<BatchFailure> failures) {
        static BatchOperationResponse of(
                int total, List<BatchSuccess> successes, List<BatchFailure> failures) {
            return new BatchOperationResponse(
                    total, successes.size(), failures.size(), successes, failures);
        }
    }

    public record ContentRow(
            Integer id,
            Integer classid,
            String title,
            Integer status,
            Integer sort,
            LocalDateTime createTime,
            String seoTitle,
            String seoKeywords,
            String seoDescription,
            String detailPageUrl,
            String previewUrl,
            Boolean indexTop,
            Boolean indexElite,
            Boolean menuTop,
            Boolean menuElite,
            Integer xgid,
            RelationSummary relationSummary,
            Map<String, Object> values) {
        static ContentRow from(
                OneshotEntity menu, ContentEntity item, List<ContentFieldDefinition> fields) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (ContentFieldDefinition field : fields) {
                if (field.getFieldName() != null
                        && !MemberCredentials.isPasswordField(item.getClassid(), field.getFieldName())) {
                    values.put(field.getFieldName(), readValue(item, field.getFieldName()));
                }
            }
            values.put("seoTitle", item.getSeoTitle());
            values.put("seoKeywords", item.getSeoKeywords());
            values.put("seoDescription", item.getSeoDescription());
            values.put("detailPageUrl", item.getDetailPageUrl());
            values.put("previewUrl", CmsCoreV2ApiController.previewUrl(menu, item));
            return new ContentRow(
                    item.getId(),
                    item.getClassid(),
                    item.getTitle(),
                    item.getStatus(),
                    item.getSort(),
                    item.getCreateTime(),
                    item.getSeoTitle(),
                    item.getSeoKeywords(),
                    item.getSeoDescription(),
                    item.getDetailPageUrl(),
                    CmsCoreV2ApiController.previewUrl(menu, item),
                    item.getIndexTop(),
                    item.getIndexElite(),
                    item.getMenuTop(),
                    item.getMenuElite(),
                    item.getXgid(),
                    CmsCoreV2ApiController.relationSummary(item),
                    values);
        }
    }

    public record SinglePageRow(
            Integer id, Integer menuId, String className, Map<String, Object> values) {
        static SinglePageRow from(OneshotEntity item, List<ContentFieldDefinition> fields) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (ContentFieldDefinition field : fields) {
                if (field.getFieldName() != null) {
                    values.put(field.getFieldName(), readValue(item, field.getFieldName()));
                }
            }
            return new SinglePageRow(
                    item.getId(), item.getId(), normalizeMenuName(item.getClassName()), values);
        }
    }

    public record TitleRequest(String value) {}

    public record SortRequest(Integer sort) {}

    public record SwitchRequest(String fieldName) {}

    public record MenuTemplateRequest(String listPageTemplate, String detailPageTemplate) {}

    private static String normalizeMenuName(String value) {
        if (value == null) {
            return "";
        }
        // getAllContentMenu 仍会返回带旧版 HTML 装饰的名称，树节点只展示纯文本。
        return (value.indexOf('<') >= 0 ? MENU_NAME_HTML_TAG.matcher(value).replaceAll("") : value)
                .trim();
    }

    private static Object readValue(ContentEntity item, String fieldName) {
        if (ContentExtensionFields.isName(fieldName))
            return item.getExtensionValues().get(fieldName);
        try {
            Field field = EntityFields.find(ContentEntity.class, fieldName);
            if (!field.canAccess(item)) {
                field.setAccessible(true);
            }
            return field.get(item);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static Object readValue(OneshotEntity item, String fieldName) {
        if (ContentExtensionFields.isName(fieldName))
            return item.getExtensionValues().get(fieldName);
        try {
            Field field = EntityFields.find(OneshotEntity.class, fieldName);
            if (!field.canAccess(item)) {
                field.setAccessible(true);
            }
            return field.get(item);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private long actorId(HttpServletRequest request) {
        UserEntity user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) {
            throw AdminSecurityException.unauthorized("ADMIN_AUTHENTICATION_REQUIRED", "未登录或登录已过期");
        }
        return user.getId().longValue();
    }

    private long nativeSiteId(HttpServletRequest request) {
        Long siteId = currentSite(request).siteId();
        if (siteId == null || siteId <= 0) throw new IllegalStateException("请先选择站点后重试");
        return siteId;
    }

    public record UpdateRecommendRequest(String field, Boolean value) {}

    private static class MutableMenuNode {
        private final Integer id;
        private final Integer parentId;
        private final int sort;
        private final MenuNode source;
        private final List<MutableMenuNode> children = new ArrayList<>();

        private MutableMenuNode(MenuNode source) {
            this.id = source.id();
            this.parentId = source.parentId();
            this.sort = source.sort() == null ? 0 : source.sort();
            this.source = source;
        }

        private int sort() {
            return sort;
        }

        private MenuNode toNode() {
            return new MenuNode(
                    source.id(),
                    source.parentId(),
                    source.className(),
                    source.classEnName(),
                    source.parentName(),
                    source.sort(),
                    source.pageType(),
                    source.hasManageChannel(),
                    source.hasFrontChannel(),
                    source.isSystem(),
                    source.isCanDeleted(),
                    source.isCanEdit(),
                    source.isCanSort(),
                    source.url(),
                    source.listPageTemplate(),
                    source.detailPageTemplate(),
                    source.siteProjectId(),
                    source.siteRootId(),
                    source.bizCode(),
                    source.manageable(),
                    source.manageReason(),
                    source.contentRoute(),
                    source.contentManageable(),
                    source.navigationOnly(),
                    source.siteScoped(),
                    source.invalidReason(),
                    !children.isEmpty(), // hasChildren
                    children.stream()
                            .sorted(Comparator.comparingInt(MutableMenuNode::sort))
                            .map(MutableMenuNode::toNode)
                            .toList(),
                    source.isSubMenu(),
                    source.listPageUrl(),
                    source.detailPageUrl());
        }
    }
}

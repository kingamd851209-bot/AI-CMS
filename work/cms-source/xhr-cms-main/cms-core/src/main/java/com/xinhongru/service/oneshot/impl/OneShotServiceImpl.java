package com.xinhongru.service.oneshot.impl;

import com.alibaba.fastjson.JSON;
import com.xinhongru.ai.admin.SmartAdminMenuSupport;
import com.xinhongru.common.page.PageResult;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ColumnAttributeNames;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.*;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.i18n.render.I18nRenderContext;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.operations.OperationExecutionContext;
import com.xinhongru.operations.OperationTask;
import com.xinhongru.operations.OperationTaskDispatcher;
import com.xinhongru.operations.OperationTaskExecutionAdapter;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.fieldHtmlDto.FieldHtmlDto;
import com.xinhongru.requestDto.oneshot.MenuModuleEditDto;
import com.xinhongru.requestDto.oneshot.OneshotSwitchDto;
import com.xinhongru.requestDto.oneshot.OneshotTitleDto;
import com.xinhongru.resource.ResourceAccessProvider;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.builderHtml.BuilderHtmlVo;
import com.xinhongru.responseVo.builderHtml.NodeVo;
import com.xinhongru.responseVo.fieldHtmlVo.FieldHtmlVo;
import com.xinhongru.responseVo.fieldHtmlVo.ModelFieldHtmlVo;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.responseVo.oneshot.OpenEditOneshotDto;
import com.xinhongru.responseVo.oneshot.SeoDto;
import com.xinhongru.responseVo.searchHtml.SearchHtmlVo;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.service.logOperation.LogOperationService;
import com.xinhongru.service.module.ModuleService;
import com.xinhongru.service.moduleTemplate.ModuleTemplateService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.site.locale.SiteLocaleService;
import com.xinhongru.site.storage.SiteStoragePathResolver;
import com.xinhongru.site.storage.StaticPaginationFiles;
import com.xinhongru.utils.*;
import com.xinhongru.web.service.InfoService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** */
@Slf4j
@Service
public class OneShotServiceImpl implements OneShotService, OperationTaskDispatcher.Handler {
    private com.xinhongru.cms.search.PublicSearchProjectionService publicSearchProjectionService;

    @Autowired
    public void setPublicSearchProjectionService(
            com.xinhongru.cms.search.PublicSearchProjectionService service) {
        this.publicSearchProjectionService = service;
    }

    private OneShotMapper oneShotDao;
    private ModuleService moduleService;
    private ModuleTemplateService moduleTemplateService;
    private ContentService contentService;
    private UserService userService;
    private UserGroupService userGroupService;
    private LogOperationService logOperationService;
    private ContentLinkService contentLinks;
    @Autowired private ContentMapper contentRecords;
    private TransactionTemplate staticWriteTransaction;
    private TransactionTemplate staticWithdrawalRecovery;
    @Autowired private InfoService contentQueries;
    @Autowired private com.xinhongru.cms.template.SiteWorkbenchContextService workbenchContext;
    @Autowired private SiteLocaleService siteLocaleService;
    @Autowired private ObjectProvider<com.xinhongru.cms.template.NativeHomePublication> nativeHomePublication;
    @org.springframework.beans.factory.annotation.Value("${cms.workbench.enabled:false}")
    private boolean workbenchEnabled;
    private static final RestTemplate STATIC_RENDER_CLIENT = staticRenderClient();
    private CmsBuilderHtmlConfigProperties cmsBuilderHtmlConfigProperties;
    private ExecutorService executorService;
    private SiteContextService siteContextService;
    private SiteStoragePathResolver siteStoragePathResolver;
    @Autowired private ObjectProvider<ResourceAccessProvider> siteResourceAccess;

    @Autowired
    void setStaticWriteTransactionManager(PlatformTransactionManager manager) {
        this.staticWriteTransaction = new TransactionTemplate(manager);
        this.staticWithdrawalRecovery = new TransactionTemplate(manager);
        staticWithdrawalRecovery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static RestTemplate staticRenderClient() {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .proxy(ProxySelector.of(null))
                                .build());
        factory.setReadTimeout(Duration.ofMinutes(10));
        return new RestTemplate(factory);
    }

    @Autowired
    public void setSiteStoragePathResolver(SiteStoragePathResolver siteStoragePathResolver) {
        this.siteStoragePathResolver = siteStoragePathResolver;
    }

    @Autowired
    public void setSiteContextService(SiteContextService siteContextService) {
        this.siteContextService = siteContextService;
    }

    @Autowired
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Autowired
    public void setCmsBuilderHtmlConfigProperties(
            CmsBuilderHtmlConfigProperties cmsBuilderHtmlConfigProperties) {
        this.cmsBuilderHtmlConfigProperties = cmsBuilderHtmlConfigProperties;
    }

    @Autowired
    public void setContentLinkService(ContentLinkService contentLinks) {
        this.contentLinks = contentLinks;
    }

    @Autowired
    public void setLogOperationService(LogOperationService logOperationService) {
        this.logOperationService = logOperationService;
    }

    @Autowired
    public void setUserGroupService(UserGroupService userGroupService) {
        this.userGroupService = userGroupService;
    }

    @Autowired
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @Autowired
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Autowired
    public void setModuleTemplateService(ModuleTemplateService moduleTemplateService) {
        this.moduleTemplateService = moduleTemplateService;
    }

    @Autowired
    public void setModuleService(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @Autowired
    public void setOneShotDao(OneShotMapper oneShotDao) {
        this.oneShotDao = oneShotDao;
    }

    @Override
    public int save(OneshotEntity oneshotEntity) {
        return oneShotDao.save(oneshotEntity);
    }

    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO update(OneshotEntity oneshotEntity, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = lockSearchMenu(oneshotEntity.getId());
        ResultVO scope = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (scope != null) return scope;
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        } else {
            oneshotEntity.setPublicSearchText(
                    com.xinhongru.cms.search.PublicSearchText.forSinglePage(
                            ContentModelCodec.read(modelById.getMenuField()),
                            oneshotEntity,
                            modelById));
            int queryCount = oneShotDao.update(oneshotEntity);
            // 避免出现parentId日志影响 追加parentId字段
            oneshotEntity.setParentId(modelById.getParentId());
            // 获取单篇栏目字段设置
            List<ContentFieldDefinition> fieldSettingEntityList =
                    ContentModelCodec.read(modelById.getMenuField());
            Queue<LogTableDto> logTableDtoQueue =
                    StringDeal.compareDifferences(modelById, oneshotEntity, fieldSettingEntityList);
            if (queryCount > 0 && CollectionUtils.isNotEmpty(logTableDtoQueue)) {
                // 操作日志明细
                UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
                LogOperationEntity logOperationEntity = new LogOperationEntity();
                logOperationEntity
                        .setAction("修改栏目信息，修改了" + logTableDtoQueue.size() + "个字段")
                        .setCreated(LocalDateTime.now())
                        .setInfoId(0)
                        .setClassId(oneshotEntity.getId())
                        .setRequestData(JSON.toJSONString(oneshotEntity))
                        .setResponseData(logOperationService.getUpdateTableHtml(logTableDtoQueue))
                        .setUserName(loginUser.getUsername())
                        .setUserId(loginUser.getId())
                        .setIp(RequestOriginUtils.clientIp(httpServletRequest));
                logOperationService.add(logOperationEntity);
                if (modelById.getWebsitePublic()) {
                    // 重置全量发布
                    resetFullRelease();
                }
            }
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        }
    }

    @Override
    public OneshotEntity getModelById(int id) {
        return oneShotDao.getModelById(id);
    }

    @Override
    public FieldHtmlVo getContentFieldDefinitionList(OneshotEntity oneshotEntity) {
        StringBuilder stringBuilderHtml = new StringBuilder();
        StringBuilder stringBuilderScript = new StringBuilder();
        List<ContentFieldDefinition> fieldSettingEntityList =
                ContentModelCodec.read(oneshotEntity.getMenuField());

        fieldSettingEntityList =
                fieldSettingEntityList.stream()
                        .sorted((a, b) -> Integer.compare(b.getSort(), a.getSort()))
                        .collect(Collectors.toList());

        FieldHtmlDto fieldHtmlDto;
        Class<? extends OneshotEntity> modelByIdClass = oneshotEntity.getClass();
        Field[] fields = EntityFields.all(modelByIdClass);

        // 2026-1-23 10:20:11 新增是否超级管理员字段
        Boolean isSuperAdmin = isSuperAdmin();
        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntityList) {
            fieldHtmlDto =
                    getFieldHtmlDto(
                            oneshotEntity, fields, fieldSettingEntity, oneshotEntity.getId());
            // 2026-1-23 10:20:11 新增是否超级管理员字段
            fieldHtmlDto.setIsSuperAdmin(isSuperAdmin);
            // 2026-1-23 10:20:11 赋值栏目对象
            fieldHtmlDto.setOneshotEntity(oneshotEntity);
            stringBuilderHtml.append(FieldHtmlUtils.getFieldHtml(fieldHtmlDto));
            stringBuilderScript.append(FieldHtmlUtils.getFieldScript(fieldHtmlDto));
        }
        FieldHtmlVo fieldHtmlVo = new FieldHtmlVo();
        fieldHtmlVo.setHtml(stringBuilderHtml.toString());
        fieldHtmlVo.setScript(stringBuilderScript.toString());
        return fieldHtmlVo;
    }

    private static Boolean isSuperAdmin() {
        // 超管账号或平台组（site_id=0）成员，完全等同超级管理员
        return TaleUtils.isFullAdmin();
    }

    @Override
    public FieldHtmlVo getModelListDisplayFieldList(String modelList, ContentEntity contentEntity) {
        StringBuilder stringBuilderTitleHtml = new StringBuilder();
        StringBuilder stringBuilderListHtml = new StringBuilder();
        List<ContentFieldDefinition> fieldSettingEntityList = ContentModelCodec.read(modelList);
        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntityList) {
            stringBuilderTitleHtml.append(FieldHtmlUtils.getListTitleHtml(fieldSettingEntity));
        }
        fieldSettingEntityList =
                fieldSettingEntityList.stream()
                        .sorted((a, b) -> Integer.compare(b.getSort(), a.getSort()))
                        .collect(Collectors.toList());

        FieldHtmlDto fieldHtmlDto;
        Class<? extends ContentEntity> contentEntityClass = contentEntity.getClass();

        Field[] fields = EntityFields.all(contentEntityClass);
        int i = 0;
        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntityList) {
            fieldHtmlDto =
                    getFieldHtmlDto(
                            contentEntity, fields, fieldSettingEntity, contentEntity.getClassid());
            stringBuilderListHtml.append(
                    FieldHtmlUtils.getListFieldHtml(
                            fieldHtmlDto, contentEntity, i, contentEntity.getClassid()));
            i++;
        }
        FieldHtmlVo fieldHtmlVo = new FieldHtmlVo();
        fieldHtmlVo.setTitleHtml(stringBuilderTitleHtml.toString());
        fieldHtmlVo.setHtml(stringBuilderListHtml.toString());
        return fieldHtmlVo;
    }

    /**
     * 动态获取字段对象的值
     *
     * @param obj
     * @param fields
     * @param fieldSettingEntity
     * @return
     */
    private FieldHtmlDto getFieldHtmlDto(
            Object obj,
            Field[] fields,
            ContentFieldDefinition fieldSettingEntity,
            Integer classId) {
        FieldHtmlDto fieldHtmlDto;
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String fieldValue = "";
        for (Field field : fields) {
            fieldValue = "";
            if (field.getName().equals(fieldSettingEntity.getFieldName())) {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                try {
                    if (field.get(obj) instanceof Date) {
                        // 时间类型特殊处理
                        fieldValue = dateFormat.format((Date) field.get(obj));
                    } else if (field.get(obj) instanceof LocalDateTime) {
                        fieldValue =
                                ((LocalDateTime) field.get(obj))
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    } else if (field.get(obj) instanceof LocalDate) {
                        fieldValue =
                                ((LocalDate) field.get(obj))
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    } else {
                        if (field.get(obj) == null) {
                            fieldValue = "";
                        } else {
                            fieldValue = String.valueOf(field.get(obj));
                        }
                    }
                    break;
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    fieldValue = "";
                }
            }
        }
        fieldHtmlDto = new FieldHtmlDto();
        fieldHtmlDto.setFieldValue(fieldValue);
        fieldHtmlDto.setValue(fieldValue);
        fieldHtmlDto.setId(fieldSettingEntity.getId());
        fieldHtmlDto.setClassId(classId);
        fieldHtmlDto.setFieldName(fieldSettingEntity.getFieldName());
        fieldHtmlDto.setFromName(fieldSettingEntity.getFromName());
        fieldHtmlDto.setFieldType(fieldSettingEntity.getFieldType());
        fieldHtmlDto.setIsOpen(fieldSettingEntity.getIsOpen());
        fieldHtmlDto.setIsList(fieldSettingEntity.getIsList());
        fieldHtmlDto.setIsSearch(fieldSettingEntity.getIsSearch());
        fieldHtmlDto.setIsReadOnly(fieldSettingEntity.getIsReadOnly());
        fieldHtmlDto.setIsRequired(fieldSettingEntity.getIsRequired());
        fieldHtmlDto.setIsTel(fieldSettingEntity.getIsTel());
        fieldHtmlDto.setIsEmail(fieldSettingEntity.getIsEmail());
        fieldHtmlDto.setIsIdCard(fieldSettingEntity.getIsIdCard());
        fieldHtmlDto.setIsNumber(fieldSettingEntity.getIsNumber());
        fieldHtmlDto.setIsWebUrl(fieldSettingEntity.getIsWebUrl());
        fieldHtmlDto.setIsColor(fieldSettingEntity.getIsColor());
        fieldHtmlDto.setIsCoordinate(fieldSettingEntity.getIsCoordinate());
        fieldHtmlDto.setTitle(fieldSettingEntity.getTitle());
        fieldHtmlDto.setDefaultValue(fieldSettingEntity.getDefaultValue());
        fieldHtmlDto.setBak(fieldSettingEntity.getBak());
        fieldHtmlDto.setMaxLength(fieldSettingEntity.getMaxLength());
        fieldHtmlDto.setSort(fieldSettingEntity.getSort());
        return fieldHtmlDto;
    }

    @Override
    public FieldHtmlVo getContentFieldDefinitionModelList(
            OneshotEntity oneshotEntity, ContentEntity contentEntity, HttpServletRequest request) {
        StringBuilder stringBuilderHtml = new StringBuilder();
        StringBuilder stringBuilderScript = new StringBuilder();
        List<ContentFieldDefinition> fieldSettingEntityList =
                ContentModelCodec.read(oneshotEntity.getModelField());

        // 招金矿业定制功能，由于招聘表单字段过多，内置单行文本字段不够用，借用了多行文本字段，所以此处做特殊处理
        if (oneshotEntity.getId().intValue() == 250 || oneshotEntity.getId().intValue() == 251) {
            fieldSettingEntityList.forEach(
                    item -> {
                        if (item.getFieldName().equals("note4")
                                || item.getFieldName().equals("note5")
                                || item.getFieldName().equals("note6")
                                || item.getFieldName().equals("note7")) {
                            item.setFieldType("SingleLine");
                        }
                    });
        }

        fieldSettingEntityList =
                fieldSettingEntityList.stream()
                        .sorted((a, b) -> Integer.compare(b.getSort(), a.getSort()))
                        .collect(Collectors.toList());

        FieldHtmlDto fieldHtmlDto;
        Class<? extends ContentEntity> contentEntityClass = contentEntity.getClass();

        Field[] fields = EntityFields.all(contentEntityClass);
        // 2026-1-23 10:20:11 新增是否超级管理员字段
        Boolean isSuperAdmin = isSuperAdmin();
        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntityList) {
            fieldHtmlDto =
                    getFieldHtmlDto(
                            contentEntity, fields, fieldSettingEntity, oneshotEntity.getId());
            // 2026-1-23 10:20:11 新增是否超级管理员字段
            fieldHtmlDto.setIsSuperAdmin(isSuperAdmin);
            // 2026-1-23 10:20:11 赋值栏目对象
            fieldHtmlDto.setOneshotEntity(oneshotEntity);
            stringBuilderHtml.append(FieldHtmlUtils.getFieldHtml(fieldHtmlDto));
            stringBuilderScript.append(FieldHtmlUtils.getFieldScript(fieldHtmlDto));
        }
        FieldHtmlVo fieldHtmlVo = new FieldHtmlVo();
        fieldHtmlVo.setHtml(stringBuilderHtml.toString());
        fieldHtmlVo.setScript(stringBuilderScript.toString());

        // 当前用户审核权限
        // 栏目置顶权限
        UserEntity loginUser = TaleUtils.getLoginUser(request);
        loginUser = userService.getUserInfoById(loginUser.getId(), false);

        // 是否开启审核
        Integer userGroupId = TaleUtils.getUserGroupId(request);

        if (StringUtils.isNotBlank(oneshotEntity.getSetting())
                && oneshotEntity.getSetting().contains(WebConst.CHECK_AUTHORITY_ID + "")
                && !AuthorityUtils.hasOtherSetting(
                        request, contentEntity.getClassid(), WebConst.CHECK_AUTHORITY_ID)
                && userGroupId > 2) {
            fieldHtmlVo.setIsCheck(Boolean.TRUE);
        } else {
            fieldHtmlVo.setIsCheck(Boolean.FALSE);
        }
        return fieldHtmlVo;
    }

    /**
     * 栏目管理 - 基础配置 - 其他配置 栏目集合
     *
     * @param oneshotDto
     * @return
     */
    /** 获取所有菜单原始数据（带缓存） 缓存 5 分钟，减少数据库查询压力 */
    @Cacheable(value = "allMenu")
    public List<OneshotEntity> getAllMenuCached() {
        return oneShotDao.getAllMenu();
    }

    @Override
    public List<OneshotEntity> getAllMenu(OneshotDto oneshotDto) {
        List<OneshotEntity> oneshotEntities = new ArrayList<>();
        List<OneshotEntity> allMenu = getAllMenuCached();

        String split =
                "<img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\""
                    + " width=\"18\" height=\"18\">";
        int parentId = oneshotDto.getParentId();
        // 如果不是顶级栏目 则需要将当前父级也追加到集合中
        if (parentId != 0) {
            recursiveFirstParent(oneshotEntities, allMenu, parentId, split);
        } else {
            recursive(oneshotEntities, allMenu, parentId, split);
        }
        return oneshotEntities;
    }

    private List<OneshotEntity> getAllMenu(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest) {
        List<OneshotEntity> oneshotEntities = new ArrayList<>();
        List<OneshotEntity> allMenu =
                scopeMenusByCurrentSite(getAllMenuCached(), httpServletRequest);

        String split =
                "<img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\""
                    + " width=\"18\" height=\"18\">";
        int parentId = oneshotDto.getParentId();
        if (parentId != 0) {
            recursiveFirstParent(oneshotEntities, allMenu, parentId, split);
        } else {
            recursive(oneshotEntities, allMenu, parentId, split);
        }
        return oneshotEntities;
    }

    /**
     * 栏目管理 - 其他配置 开关类型
     *
     * @param oneshotSwitchDto
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateSwitch(
            OneshotSwitchDto oneshotSwitchDto, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = oneShotDao.getModelById(oneshotSwitchDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (oneshotSwitchDto.getFieldName().equals(WebConst.HAS_FRONT_CHANNEL)
                && !modelById.getIsCanShowHidden()) {
            return ResultVO.fail(ErrorCodeEnum.HAS_FRONT_CHANNEL_UPDATE_FAIL);
        }
        if (oneshotSwitchDto.getFieldName().equals(WebConst.IS_ADD_ARTICLE)
                && !modelById.getIsCanAddArticle()) {
            return ResultVO.fail(ErrorCodeEnum.IS_ADD_ARTICLE_UPDATE_FAIL);
        }
        Boolean updateSwitch = oneShotDao.updateSwitch(oneshotSwitchDto);
        if (updateSwitch) {
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction(
                            "修改栏目【"
                                    + ColumnAttributeNames.getMenuAttrNameByFieldName(
                                            oneshotSwitchDto.getFieldName())
                                    + "】属性")
                    .setCreated(LocalDateTime.now())
                    .setInfoId(0)
                    .setClassId(oneshotSwitchDto.getId())
                    .setRequestData(JSON.toJSONString(oneshotSwitchDto))
                    .setResponseData(JSON.toJSONString(updateSwitch))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
            // 重置全量发布
            resetFullRelease();
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    /** 重置全量发布栏目 */
    private void resetFullRelease() {
        Runnable rebuild =
                () -> {
                    RedisUtils.delete(WebConst.REDIS_FULL_PUBLISH_KEY);
                    List<OneshotEntity> allFontMenu = oneShotDao.getAllFontMenu();
                    HashMap<String, OneshotEntity> integerOneshotEntityHashMap = new HashMap<>();
                    for (OneshotEntity fontMenu : allFontMenu) {
                        integerOneshotEntityHashMap.put(String.valueOf(fontMenu.getId()), fontMenu);
                    }
                    RedisUtils.setHash(
                            WebConst.REDIS_FULL_PUBLISH_KEY, integerOneshotEntityHashMap);
                };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) executorService.execute(rebuild);
                        }
                    });
        } else {
            executorService.execute(rebuild);
        }
    }

    /**
     * 更新排序
     *
     * @param sortDto
     * @param httpServletRequest
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateSort(SortDto sortDto, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = oneShotDao.getModelById(sortDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!modelById.getIsCanSort()) {
            return ResultVO.fail(ErrorCodeEnum.CAN_NOT_SORT);
        }
        Boolean updateSort = oneShotDao.updateSort(sortDto);
        if (updateSort) {
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("将排序号修改为：" + sortDto.getSort())
                    .setCreated(LocalDateTime.now())
                    .setInfoId(0)
                    .setClassId(sortDto.getId())
                    .setRequestData(JSON.toJSONString(sortDto))
                    .setResponseData(JSON.toJSONString(updateSort))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
            // 重置全量发布
            resetFullRelease();
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    /**
     * 更新栏目标题
     *
     * @param oneshotTitleDto
     * @param httpServletRequest
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateTitle(
            OneshotTitleDto oneshotTitleDto, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = oneShotDao.getModelById(oneshotTitleDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!modelById.getIsCanEdit()) {
            return ResultVO.fail(ErrorCodeEnum.CAN_NOT_EDIT);
        }
        modelById = lockSearchMenu(oneshotTitleDto.getId());
        siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) return siteScopeResult;
        if (modelById == null) return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        if (!Boolean.TRUE.equals(modelById.getIsCanEdit()))
            return ResultVO.fail(ErrorCodeEnum.CAN_NOT_EDIT);
        Boolean updateTitle = oneShotDao.updateTitle(oneshotTitleDto);
        if (updateTitle) {
            publicSearchProjectionService.rebuildMenu(
                    modelById.getSiteProjectId(), modelById.getId());
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("快速修改栏目名称为：" + oneshotTitleDto.getClassName())
                    .setCreated(LocalDateTime.now())
                    .setInfoId(0)
                    .setClassId(oneshotTitleDto.getId())
                    .setRequestData(JSON.toJSONString(oneshotTitleDto))
                    .setResponseData(
                            logOperationService.getSimpleUpdateTableHtml(
                                    WebConst.UPDATE_CLASS_NAME,
                                    modelById.getClassName(),
                                    oneshotTitleDto.getClassName()))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
            // 重置全量发布
            resetFullRelease();

            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    /**
     * 更新栏目SEO信息
     *
     * @param seoDto
     * @param httpServletRequest
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateSEO(SeoDto seoDto, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = oneShotDao.getModelById(seoDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
        Boolean updateSEO = oneShotDao.updateSEO(seoDto);
        if (updateSEO) {
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("修改栏目SEO信息")
                    .setCreated(LocalDateTime.now())
                    .setInfoId(0)
                    .setClassId(seoDto.getId())
                    .setRequestData(JSON.toJSONString(seoDto))
                    .setResponseData(JSON.toJSONString(updateSEO))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);

            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    /**
     * 获取下拉递归栏目
     *
     * @param oneshotDto
     * @return
     */
    @Override
    public List<OneshotEntity> getAllMenuForDropdownList(OneshotDto oneshotDto) {
        return flattenMenuDescendants(getAllMenuCached(), oneshotDto.getParentId());
    }

    @Override
    public List<OneshotEntity> getAllMenuForDropdownList(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest) {
        List<OneshotEntity> allMenu =
                scopeMenusByCurrentSite(getAllMenuCached(), httpServletRequest);
        return flattenMenuDescendants(allMenu, oneshotDto.getParentId());
    }

    @Override
    public List<OneshotEntity> getAllMenuForDropdownList(OneshotDto oneshotDto, Long siteId) {
        List<OneshotEntity> allMenu;
        if (siteId == null) {
            allMenu = getAllMenuCached();
        } else {
            allMenu = oneShotDao.selectBySiteProjectId(siteId);
        }

        return flattenMenuDescendants(allMenu, oneshotDto.getParentId());
    }

    @Override
    public List<OneshotEntity> getAllMenuBySiteProjectId(Long siteId) {
        if (siteId == null) {
            return getAllMenuCached();
        }
        return oneShotDao.selectBySiteProjectId(siteId);
    }

    /**
     * 添加、修改栏目
     *
     * @param editOneshotDto
     * @param httpServletRequest
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateEdit(
            EditOneshotDto editOneshotDto, HttpServletRequest httpServletRequest) {
        Integer id = editOneshotDto.getId();
        if (id != null && id > 0) {
            // 修改栏目
            ResultVO siteScopeResult =
                    validateCurrentSiteScopeForEdit(editOneshotDto, httpServletRequest);
            if (siteScopeResult != null) {
                return siteScopeResult;
            }
            OneshotEntity locked = lockSearchMenu(id);
            siteScopeResult = validateCurrentSiteScopeForMenu(locked, httpServletRequest);
            if (siteScopeResult != null) return siteScopeResult;
            if (locked == null) return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
            if (changesWorkbenchRoute(locked.getListPageTemplate(), locked.getListPageUrl(),
                            editOneshotDto.getListPageUrl())
                    || changesWorkbenchRoute(locked.getDetailPageTemplate(), locked.getDetailPageUrl(),
                            editOneshotDto.getDetailPageUrl()))
                throw new IllegalArgumentException("此栏目使用工作台公开应用，请在工作台预览并切换正式地址");
            Boolean updateEdit = oneShotDao.updateEdit(editOneshotDto);
            if (updateEdit) {
                publicSearchProjectionService.rebuildMenu(locked.getSiteProjectId(), id);
                // 操作日志明细
                UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
                LogOperationEntity logOperationEntity = new LogOperationEntity();
                logOperationEntity
                        .setAction("修改栏目信息")
                        .setCreated(LocalDateTime.now())
                        .setInfoId(0)
                        .setClassId(editOneshotDto.getId())
                        .setRequestData(JSON.toJSONString(editOneshotDto))
                        .setResponseData(JSON.toJSONString(updateEdit))
                        .setUserName(loginUser.getUsername())
                        .setUserId(loginUser.getId())
                        .setIp(RequestOriginUtils.clientIp(httpServletRequest));
                logOperationService.add(logOperationEntity);
                // 重置全量发布
                resetFullRelease();

                return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
            } else {
                return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
            }
        } else {
            // 添加栏目
            ResultVO siteScopeResult =
                    applyCurrentSiteScopeForCreate(editOneshotDto, httpServletRequest);
            if (siteScopeResult != null) {
                return siteScopeResult;
            }
            oneShotDao.insertMenu(editOneshotDto);
            if (editOneshotDto.getId() > 0) {
                // 新增栏目默认 biz_code 取自身 id，便于多站点按 (biz_code, site_project_id) 定位
                oneShotDao.assignBizcodeToOwnId(editOneshotDto.getId());
                // 默认将排序号设置为当前id
                oneShotDao.updateSort(new SortDto(editOneshotDto.getId(), editOneshotDto.getId()));
                // 操作日志明细
                UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
                LogOperationEntity logOperationEntity = new LogOperationEntity();
                logOperationEntity
                        .setAction("新增栏目")
                        .setCreated(LocalDateTime.now())
                        .setInfoId(0)
                        .setClassId(editOneshotDto.getId())
                        .setRequestData(JSON.toJSONString(editOneshotDto))
                        .setResponseData(JSON.toJSONString(editOneshotDto))
                        .setUserName(loginUser.getUsername())
                        .setUserId(loginUser.getId())
                        .setIp(RequestOriginUtils.clientIp(httpServletRequest));
                logOperationService.add(logOperationEntity);
                // 重置全量发布
                resetFullRelease();

                return ResultVO.saveSuccess(editOneshotDto.getId());
            } else {
                return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
            }
        }
    }

    private static boolean changesWorkbenchRoute(String template, String current, String requested) {
        return template != null && template.startsWith("cms-applied/")
                && requested != null && !Objects.equals(current, requested);
    }

    /**
     * 删除栏目
     *
     * @param idDto
     * @param httpServletRequest
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO deleteMenu(IdDto idDto, HttpServletRequest httpServletRequest) {
        OneshotEntity modelById = oneShotDao.getModelById(idDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(modelById, httpServletRequest);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        if (loginUser == null) {
            return ResultVO.fail(ErrorCodeEnum.USER_NOT_LOGIN);
        }
        if (!modelById.getIsCanDeleted()) {
            log.warn(
                    "{} 试图越权删除禁止删除栏目【{} - {}】，已经被系统阻止了。",
                    loginUser.getUsername(),
                    modelById.getClassName(),
                    modelById.getId());
            return ResultVO.fail(ErrorCodeEnum.CAN_NOT_DELETE);
        }
        // 检查是否存在非级联删除的子栏目
        Integer count = oneShotDao.getChildCountExcludeCascade(idDto);
        if (count > 0) {
            return ResultVO.fail(ErrorCodeEnum.HAS_CHILD_DATA);
        }
        // 检查当前栏目是否为级联删除栏目,如果为级联删除栏目则不可独立删除
        if (modelById.getIsCanCascadeDeleted()) {
            log.warn(
                    "{} 试图越权删除级联删除栏目【{} - {}】，已经被系统阻止了。",
                    loginUser.getUsername(),
                    modelById.getClassName(),
                    modelById.getId());
            return ResultVO.fail(ErrorCodeEnum.CASCADE_CAN_NOT_DELETE);
        }
        // 检查当前栏目是否为模块基础栏目,如果为模块基础栏目则不可删除
        if (modelById.getIsModuleMenu()) {
            log.warn(
                    "{} 试图越权删除模块基础栏目【{} - {}】，已经被系统阻止了。",
                    loginUser.getUsername(),
                    modelById.getClassName(),
                    modelById.getId());
            return ResultVO.fail(ErrorCodeEnum.MODULE_MENU_CAN_NOT_DELETE);
        }
        // 检查栏目下是否有数据，如果有数据需要先删除数据才可以删除栏目
        List<ContentEntity> contentEntityList = contentService.getArticleByClassId(idDto.getId());
        if (CollectionUtils.isNotEmpty(contentEntityList)) {
            log.warn("栏目【{} - {}】中存在数据无法删除，已经被系统阻止了。", modelById.getClassName(), modelById.getId());
            return ResultVO.fail(
                    ErrorCodeEnum.THERE_IS_DATA_UNDER_THE_COLUMN_AND_CANNOT_BE_DELETED);
        }
        Boolean updateIsDeleted = oneShotDao.updateIsDeleted(idDto);
        if (updateIsDeleted) {
            // 操作日志明细
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("删除栏目")
                    .setCreated(LocalDateTime.now())
                    .setInfoId(0)
                    .setClassId(idDto.getId())
                    .setRequestData(JSON.toJSONString(idDto))
                    .setResponseData(JSON.toJSONString(updateIsDeleted))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
            // 重置全量发布
            resetFullRelease();

            return ResultVO.success(ErrorCodeEnum.DELETE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.DELETE_FAIL);
        }
    }

    /**
     * 获取当前登录用户拥有权限的栏目集合
     *
     * @param httpServletRequest
     * @return
     */
    private List<OneshotEntity> getOneshotEntities(HttpServletRequest httpServletRequest) {
        List<OneshotEntity> allMenu = oneShotDao.getAllLeftMenu();

        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        UserEntity userEntity = userService.getUserInfoById(loginUser.getId(), false);
        boolean superAdmin = TaleUtils.isFullAdmin(httpServletRequest);
        allMenu =
                allMenu.stream()
                        .filter(
                                item ->
                                        (superAdmin
                                                        || AuthorityUtils.hasGroupSetting(
                                                                userEntity, item.getId()))
                                                //                                &&
                                                // !item.getIsSubMenu()
                                                && item.getHasManageChannel())
                        .collect(Collectors.toList());

        if (!superAdmin) {
            List<Integer> masterIds = new ArrayList<>();
            masterIds.add(2);
            masterIds.add(3);
            masterIds.add(4);
            masterIds.add(9);
            allMenu =
                    allMenu.stream()
                            .filter(item -> !masterIds.contains(item.getId()))
                            .collect(Collectors.toList());
        }
        allMenu = scopeMenusByCurrentSite(allMenu, httpServletRequest);
        return SmartAdminMenuSupport.withSmartSiteMenus(allMenu, userEntity);
    }

    private List<OneshotEntity> scopeMenusByCurrentSite(
            List<OneshotEntity> allMenu, HttpServletRequest httpServletRequest) {
        SiteContext currentSite = currentSite(httpServletRequest);
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return allMenu;
        }
        Integer rootMenuId = currentSite.getRootMenuId();
        return allMenu.stream()
                .filter(item -> belongsToCurrentSite(item, rootMenuId) || isGlobalAdminMenu(item))
                .collect(Collectors.toList());
    }

    private ResultVO validateCurrentSiteScopeForEdit(
            EditOneshotDto editOneshotDto, HttpServletRequest httpServletRequest) {
        SiteContext currentSite = currentSite(httpServletRequest);
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return null;
        }
        Integer siteRootId = currentSite.getRootMenuId();
        OneshotEntity target = oneShotDao.getModelById(editOneshotDto.getId());
        ResultVO siteScopeResult = validateCurrentSiteScopeForMenu(target, currentSite);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }

        Integer parentId = editOneshotDto.getParentId();
        if (parentId == null) {
            return null;
        }
        if (parentId == 0) {
            return Objects.equals(target.getId(), siteRootId)
                    ? null
                    : ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        OneshotEntity parent = oneShotDao.getModelById(parentId);
        return validateCurrentSiteScopeForMenu(parent, currentSite);
    }

    private ResultVO validateCurrentSiteScopeForMenuId(
            Integer id, HttpServletRequest httpServletRequest) {
        SiteContext currentSite = currentSite(httpServletRequest);
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return null;
        }
        return validateCurrentSiteScopeForMenu(oneShotDao.getModelById(id), currentSite);
    }

    private ResultVO validateCurrentSiteScopeForMenu(
            OneshotEntity modelById, HttpServletRequest httpServletRequest) {
        SiteContext currentSite = currentSite(httpServletRequest);
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return null;
        }
        return validateCurrentSiteScopeForMenu(modelById, currentSite);
    }

    private ResultVO validateCurrentSiteScopeForMenu(
            OneshotEntity modelById, SiteContext currentSite) {
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!belongsToCurrentSite(modelById, currentSite.getRootMenuId())) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        return null;
    }

    private ResultVO applyCurrentSiteScopeForCreate(
            EditOneshotDto editOneshotDto, HttpServletRequest httpServletRequest) {
        SiteContext currentSite = currentSite(httpServletRequest);
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return null;
        }
        Integer siteRootId = currentSite.getRootMenuId();
        Integer parentId = editOneshotDto.getParentId() == null ? 0 : editOneshotDto.getParentId();
        if (parentId == 0) {
            parentId = siteRootId;
        }

        OneshotEntity parent = oneShotDao.getModelById(parentId);
        if (parent == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!belongsToCurrentSite(parent, siteRootId)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        editOneshotDto
                .setParentId(parentId)
                .setSiteProjectId(currentSite.getSiteId())
                .setSiteRootId(siteRootId);
        return null;
    }

    private ResultVO applyCurrentSiteScopeForModuleCreate(
            MenuModuleEditDto menuModuleEditDto, SiteContext currentSite) {
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return null;
        }
        Integer siteRootId = currentSite.getRootMenuId();
        Integer parentId =
                menuModuleEditDto.getParentId() == null ? 0 : menuModuleEditDto.getParentId();
        if (parentId == 0) {
            parentId = siteRootId;
        }

        OneshotEntity parent = oneShotDao.getModelById(parentId);
        if (parent == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!belongsToCurrentSite(parent, siteRootId)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        menuModuleEditDto.setParentId(parentId);
        return null;
    }

    private void applyCurrentSiteScope(EditOneshotDto editOneshotDto, SiteContext currentSite) {
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            return;
        }
        editOneshotDto
                .setSiteProjectId(currentSite.getSiteId())
                .setSiteRootId(currentSite.getRootMenuId());
    }

    private boolean belongsToCurrentSite(OneshotEntity item, Integer siteRootId) {
        return item != null
                && (Objects.equals(item.getSiteRootId(), siteRootId)
                        || Objects.equals(item.getId(), siteRootId));
    }

    private boolean isGlobalAdminMenu(OneshotEntity item) {
        return item != null
                && Boolean.TRUE.equals(item.getIsSystem())
                && Boolean.TRUE.equals(item.getHasManageChannel());
    }

    /** 与字段配置/正文写使用同一行锁，避免标题或许可重建覆盖并发结果。 */
    private OneshotEntity lockSearchMenu(Integer id) {
        if (id == null) return null;
        OneshotEntity located = oneShotDao.getModelById(id);
        if (located == null || located.getSiteProjectId() == null) return null;
        return oneShotDao.lockFieldConfiguration(located.getSiteProjectId(), id.longValue());
    }

    private SiteContext currentSite(HttpServletRequest httpServletRequest) {
        if (siteContextService == null || httpServletRequest == null) {
            return SiteContext.global();
        }
        return siteContextService.current(httpServletRequest);
    }

    static Long requirePublishSite(List<OneshotEntity> menus) {
        Long siteId = null;
        for (OneshotEntity menu : menus) {
            if (menu == null || menu.getSiteProjectId() == null)
                throw new IllegalStateException("STATIC_PUBLISH_SITE_CODE_MISSING: 发布栏目未绑定站点");
            if (siteId != null && !siteId.equals(menu.getSiteProjectId()))
                throw new IllegalStateException("发布栏目跨越多个站点，禁止发布");
            siteId = menu.getSiteProjectId();
        }
        return Objects.requireNonNull(siteId);
    }

    static String requireRenderOrigin(ResourceAccessProvider provider, String siteCode) {
        String url = provider == null ? null : provider.accessUrl(siteCode);
        if (StringUtils.isBlank(url)) throw new IllegalStateException("当前站点服务未运行，无法生成静态页面");
        URI origin = URI.create(url);
        if (!"http".equals(origin.getScheme())
                || origin.getHost() == null
                || origin.getPort() < 1
                || origin.getPort() > 65535
                || origin.getUserInfo() != null
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IllegalStateException("当前站点服务的渲染地址无效");
        // 统一和独立模式都按站点绑定独占监听端口，站点域名仅用于生成公开访问地址。
        return "http://127.0.0.1:" + origin.getPort();
    }

    /**
     * 通过模块添加栏目
     *
     * @param menuModuleEditDto
     * @return
     */
    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO addEditByModuleId(
            MenuModuleEditDto menuModuleEditDto, HttpServletRequest httpServletRequest) {
        //        if (!menuModuleEditDto.getSiteDomain().startsWith("http://") &&
        // !menuModuleEditDto.getSiteDomain().startsWith("https://")) {
        //            return ResultVO.fail("子公司域名必须以http://或https://开头");
        //        }
        //        if (menuModuleEditDto.getSiteDomain().endsWith("/")) {
        //            return ResultVO.fail("子域名不能以/结尾");
        //        }
        ModuleEntity moduleEntityById =
                moduleService.getModuleEntityById(menuModuleEditDto.getModuleId());
        if (moduleEntityById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        // 模块配置栏目的原始ID集合,就是以哪些栏目为模板的ID集合，此值正常情况不为空
        String menuIds = moduleEntityById.getMenuIds();
        if (StringUtils.isBlank(menuIds)) {
            return ResultVO.fail(ErrorCodeEnum.MODULE_NOT_SET_MENU_TEMPLATE);
        }
        List<String> menuIdList = Arrays.asList(menuIds.split(","));

        // 旧新栏目对照集合 键 旧栏目ID 值 新栏目ID
        List<Integer> newMenuIdList = new ArrayList<>();
        // 当前模块设置的栏目对象
        List<ModuleTemplateEntity> moduleTemplateListByModuleId =
                moduleTemplateService.getModuleTemplateListByModuleId(
                        menuModuleEditDto.getModuleId());
        // 正常情况下不可能存在这种情况的，除非直接从数据库中插入，但是为了程序的健壮，还是增加了此判断
        if (CollectionUtils.isEmpty(moduleTemplateListByModuleId)) {
            return ResultVO.fail(ErrorCodeEnum.MODULE_NOT_SET_MENU_TEMPLATE);
        }
        SiteContext currentSite = currentSite(httpServletRequest);
        ResultVO siteScopeResult =
                applyCurrentSiteScopeForModuleCreate(menuModuleEditDto, currentSite);
        if (siteScopeResult != null) {
            return siteScopeResult;
        }
        // 如果模块为子栏目则先创建一个栏目，然后再将模块里的栏目添加到新创建的栏目中
        if (moduleEntityById.getIsChildren()) {
            EditOneshotDto editOneshotDto = new EditOneshotDto();
            editOneshotDto.setParentId(menuModuleEditDto.getParentId());
            editOneshotDto.setClassName(menuModuleEditDto.getClassName());
            editOneshotDto.setClassEnName(menuModuleEditDto.getClassEnName());
            editOneshotDto.setHasManageChannel(true);
            editOneshotDto.setHasFrontChannel(true);
            editOneshotDto.setPageType(0);
            editOneshotDto.setUrl("");
            editOneshotDto.setSetting("");
            editOneshotDto.setListPageUrl("");
            editOneshotDto.setDetailPageUrl("");
            editOneshotDto.setIdentifies("");
            editOneshotDto.setIsCanDeleted(true);
            applyCurrentSiteScope(editOneshotDto, currentSite);

            oneShotDao.insertMenu(editOneshotDto);
            Integer insertMenuId = editOneshotDto.getId();
            // 新增栏目默认 bizcode 取自身 id
            oneShotDao.assignBizcodeToOwnId(insertMenuId);

            // 修改排序号
            oneShotDao.updateSort(new SortDto(insertMenuId, insertMenuId));

            Integer topMenuId = insertMenuId;
            newMenuIdList.add(insertMenuId);

            // 赋予新增栏目的权限
            addSetting(insertMenuId, httpServletRequest, "");
            // 然后之后的模块的相对顶级栏目的parentId都以insertMenuId作为父级Id，模块里的栏目自身存在父子关系除外
            Integer parentId;
            for (ModuleTemplateEntity moduleTemplateEntity : moduleTemplateListByModuleId) {
                // 检查将要添加的栏目是否存在原始的父子关系
                if (!menuIdList.contains(String.valueOf(moduleTemplateEntity.getParentId()))) {
                    // 检查将要添加的栏目是否存在原始的父子关系
                    if (!menuIdList.contains(String.valueOf(moduleTemplateEntity.getParentId()))) {
                        parentId = topMenuId;
                    } else {
                        parentId =
                                newMenuIdList.get(
                                        menuIdList.indexOf(
                                                String.valueOf(
                                                        moduleTemplateEntity.getParentId())));
                    }
                    Integer newMenuId =
                            addMenuByModuleTemplate(
                                    menuModuleEditDto,
                                    httpServletRequest,
                                    newMenuIdList,
                                    false,
                                    parentId,
                                    moduleTemplateEntity,
                                    currentSite);
                    // 为动态添加的栏目设置可以添加的模块
                    if (moduleTemplateEntity.getIsCanAddSub()) {
                        List<ModuleEntity> moduleEntities;
                        for (String menuId : menuIdList) {
                            moduleEntities = moduleService.getAllListByMenuIds(menuId);
                            for (ModuleEntity moduleEntity : moduleEntities) {
                                moduleEntity.setCanAddMenuIds(
                                        moduleEntity.getCanAddMenuIds() + "," + newMenuId);
                                moduleService.addOrUpdate(moduleEntity);
                            }
                        }
                    }
                }
            }
        } else {
            // 如果模块为非子栏目类型 则直接使用第一个栏目作为模板，将栏目的名称和栏目的英文名称改为用户自定义输入的信息
            Boolean isFirst = true;
            Integer parentId;
            Integer newMenuId;
            for (ModuleTemplateEntity moduleTemplateEntity : moduleTemplateListByModuleId) {
                if (!menuIdList.contains(String.valueOf(moduleTemplateEntity.getParentId()))) {
                    // 如果不存在父子关系
                    parentId = menuModuleEditDto.getParentId();
                } else {
                    // 如果存在父子关系
                    parentId =
                            newMenuIdList.get(
                                    menuIdList.indexOf(
                                            String.valueOf(moduleTemplateEntity.getParentId())));
                }
                if (isFirst) {
                    newMenuId =
                            addMenuByModuleTemplate(
                                    menuModuleEditDto,
                                    httpServletRequest,
                                    newMenuIdList,
                                    true,
                                    parentId,
                                    moduleTemplateEntity,
                                    currentSite);
                    // 更新所属业务分类和域名绑定
                    // oneShotDao.setBusinessIdAndDomain(menuModuleEditDto.getBusinessId(),
                    // menuModuleEditDto.getSiteDomain(), newMenuId);
                    isFirst = false;
                } else {
                    newMenuId =
                            addMenuByModuleTemplate(
                                    menuModuleEditDto,
                                    httpServletRequest,
                                    newMenuIdList,
                                    false,
                                    parentId,
                                    moduleTemplateEntity,
                                    currentSite);
                }
                // 为动态添加的栏目设置可以添加的模块
                if (moduleTemplateEntity.getIsCanAddSub()) {
                    List<ModuleEntity> moduleEntities;
                    for (String menuId : menuIdList) {
                        moduleEntities = moduleService.getAllListByMenuIds(menuId);
                        for (ModuleEntity moduleEntity : moduleEntities) {
                            moduleEntity.setCanAddMenuIds(
                                    moduleEntity.getCanAddMenuIds() + "," + newMenuId);
                            moduleService.addOrUpdate(moduleEntity);
                        }
                    }
                }
            }
        }

        // 操作日志明细
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        LogOperationEntity logOperationEntity = new LogOperationEntity();
        logOperationEntity
                .setAction("通过模块添加栏目")
                .setCreated(LocalDateTime.now())
                .setInfoId(0)
                .setClassId(newMenuIdList.get(0))
                .setRequestData(JSON.toJSONString(menuModuleEditDto))
                .setResponseData(JSON.toJSONString(newMenuIdList))
                .setUserName(loginUser.getUsername())
                .setUserId(loginUser.getId())
                .setIp(RequestOriginUtils.clientIp(httpServletRequest));
        logOperationService.add(logOperationEntity);
        // 重置全量发布
        resetFullRelease();

        return ResultVO.saveSuccess(newMenuIdList.get(0));
    }

    @Override
    public List<OneshotEntity> getAllContentMenu(OneshotDto oneshotDto) {
        List<OneshotEntity> allMenu = getAllMenu(oneshotDto);
        return allMenu.stream().filter(item -> !item.getIsSystem()).collect(Collectors.toList());
    }

    @Override
    public List<OneshotEntity> getAllContentMenu(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest) {
        List<OneshotEntity> allMenu = getAllMenu(oneshotDto, httpServletRequest);
        return allMenu.stream().filter(item -> !item.getIsSystem()).collect(Collectors.toList());
    }

    @Override
    public List<OneshotEntity> getAllContentMenuManageAndFrontNotAllFalse(OneshotDto oneshotDto) {
        List<OneshotEntity> allContentMenu = getAllContentMenu(oneshotDto);

        allContentMenu =
                allContentMenu.stream()
                        .filter(item -> (item.getHasManageChannel() || item.getHasFrontChannel()))
                        .collect(Collectors.toList());
        return allContentMenu;
    }

    @Override
    public List<OneshotEntity> getAllContentMenuManageAndFrontNotAllFalse(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest) {
        List<OneshotEntity> allContentMenu = getAllContentMenu(oneshotDto, httpServletRequest);

        allContentMenu =
                allContentMenu.stream()
                        .filter(item -> (item.getHasManageChannel() || item.getHasFrontChannel()))
                        .collect(Collectors.toList());
        return allContentMenu;
    }

    @Override
    public ModelFieldHtmlVo getContentList(
            OneshotEntity modelById,
            ContentCond contentCond,
            int page,
            int limit,
            HttpServletRequest httpServletRequest) {
        // 拼搜索条件
        SearchHtmlVo searchHtml = getSearchCondition(contentCond, httpServletRequest);
        // 拼接的搜索条件判断
        ContentCond searchCondition = searchHtml.getContentCond();
        // 将传过来的搜索条件按照健值对方式存储，用于前台显示判断
        Map searchMap = searchHtml.getMap();
        // 根据条件获取数据
        PageResult<ContentEntity> contentPage =
                contentService.getArticlesByCond(modelById, searchCondition, page, limit);
        // 根据条件获取数据总记录数
        int count = contentService.getArticlesCountByCond(searchCondition);
        String urlSearch = searchHtml.getPageUrlCondition();
        String queryString = httpServletRequest.getQueryString();
        if (StringUtils.isNotBlank(queryString)) {
            Pattern pattern = Pattern.compile(WebConst.REGEX_URL_PAGE);
            Matcher matcher = pattern.matcher(queryString);
            while (matcher.find()) {
                queryString = queryString.replace(matcher.group(0), "");
            }
            urlSearch = queryString;
        }
        String stringBuilderPageHtml =
                PageUtils.getStringBuilderPageHtml(page, limit, urlSearch, count);

        // 列表页数据
        StringBuilder stringBuilderListHtml = new StringBuilder();
        // 栏目推荐项
        StringBuilder stringBuilderRecommendHtml = new StringBuilder();
        // 搜索项HTML
        StringBuilder stringBuilderSearchHtml = new StringBuilder();
        // 搜索项JS
        StringBuilder stringBuilderSearchJsHtml = new StringBuilder();
        // 搜索项筛选类型 发布状态、首页推荐、首页热门、首页置顶等
        StringBuilder stringBuilderSearchOptionsHtml = new StringBuilder();

        List<ContentFieldDefinition> fieldSettingEntitySearchList =
                ContentModelCodec.read(modelById.getModelSearch());
        String listSearchHtml;
        String listSearchJSHtml;
        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntitySearchList) {
            listSearchHtml =
                    FieldHtmlUtils.getListSearchHtml(
                            fieldSettingEntity, searchMap, modelById.getId());
            stringBuilderSearchHtml.append(listSearchHtml);
            listSearchJSHtml = FieldHtmlUtils.getListSearchJSHtml(fieldSettingEntity, searchMap);
            stringBuilderSearchJsHtml.append(listSearchJSHtml);
        }

        String statusTitle = "";

        int i = 0;
        // 添加
        boolean hasAdd = false;
        // 导出
        boolean hasOutput = false;
        // 导入
        boolean hasImport = false;
        // 修改
        boolean hasEdit = false;
        // 删除
        boolean hasDelete = false;
        // 预览
        boolean hasDetail = false;

        // 查看
        boolean hasView = false;

        // 设置SEO
        boolean hasSeo = false;
        // 排序
        boolean hasSort = false;
        // 头部审核、关闭
        boolean hasStatus = false;
        // 首页置顶
        boolean hasIndexTop = false;
        // 首页推荐
        boolean hasIndexElite = false;
        // 首页热门
        boolean hasIndexHot = false;
        // 频道置顶
        boolean hasChannelTop = false;
        // 频道热门
        boolean hasChannelHot = false;
        // 频道推荐
        boolean hasChannelElite = false;
        // 栏目置顶
        boolean hasMenuTop = false;
        // 栏目热门
        boolean hasMenuHot = false;
        // 栏目推荐
        boolean hasMenuElite = false;
        // 右侧推荐
        boolean hasRightElite = false;
        // 左侧推荐
        boolean hasLeftElite = false;
        // 主导航推荐
        boolean hasNavElite = false;
        // 审核状态列表
        boolean hasStatusList = false;

        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        loginUser = userService.getUserInfoById(loginUser.getId(), false);
        // 添加权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.ADD_AUTHORITY_ID)) {
            hasAdd = true;
        }
        // 修改权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.UPDATE_AUTHORITY_ID)) {
            hasEdit = true;
        }
        // 删除权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.DELETE_AUTHORITY_ID)) {
            hasDelete = true;
        }
        // 查看权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.VIEW_AUTHORITY_ID)) {
            hasView = true;
        }
        // 浏览量权限
        if (StringUtils.isNotBlank(modelById.getDetailPageUrl())) {
            hasDetail = true;
        }
        // SEO权限
        if (hasDetail && hasEdit) {
            hasSeo = true;
        }
        // 排序权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.SORT_AUTHORITY_ID)) {
            hasSort = true;
        }
        // 审核权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.APPROVAL_AUTHORITY_ID)) {
            hasStatus = true;
        }
        // 审核状态列表
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.STATUS_AUTHORITY_ID)) {
            hasStatusList = true;
            stringBuilderSearchOptionsHtml.append(
                    "\n"
                        + "<select class=\"form-control\" name=\"status\">\n"
                        + "                                    <option value=\"\">发布状态</option>\n"
                        + "                                    <option value=\"1\""
                            + getSelectedString(searchCondition.getStatus(), WebConst.ONE)
                            + ">已发布</option>\n"
                            + "                                    <option value=\"0\""
                            + getSelectedString(searchCondition.getStatus(), WebConst.ZERO)
                            + ">草稿</option>\n"
                            + "                                </select>\n");
        }
        // 首页置顶权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.INDEX_TOP_AUTHORITY_ID)) {
            hasIndexTop = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "首页置顶",
                    "indexTop",
                    searchCondition.getIndexTop());
        }
        // 首页热门权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.INDEX_HOT_AUTHORITY_ID)) {
            hasIndexHot = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "首页热门",
                    "indexHot",
                    searchCondition.getIndexHot());
        }
        // 首页推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.INDEX_ELITE_AUTHORITY_ID)) {
            hasIndexElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "首页推荐",
                    "indexElite",
                    searchCondition.getIndexElite());
        }
        // 频道置顶权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.CHANNEL_TOP_AUTHORITY_ID)) {
            hasChannelTop = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "频道置顶",
                    "channelTop",
                    searchCondition.getChannelTop());
        }
        // 频道热门权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.CHANNEL_HOT_AUTHORITY_ID)) {
            hasChannelHot = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "频道热门",
                    "channelHot",
                    searchCondition.getChannelHot());
        }
        // 频道推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.CHANNEL_ELITE_AUTHORITY_ID)) {
            hasChannelElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "频道推荐",
                    "channelElite",
                    searchCondition.getChannelElite());
        }
        // 栏目置顶权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.MENU_TOP_AUTHORITY_ID)) {
            hasMenuTop = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "栏目置顶",
                    "menuTop",
                    searchCondition.getMenuTop());
        }
        // 栏目热门权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.MENU_HOT_AUTHORITY_ID)) {
            hasMenuHot = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "栏目热门",
                    "menuHot",
                    searchCondition.getMenuHot());
        }
        // 栏目推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.MENU_ELITE_AUTHORITY_ID)) {
            hasMenuElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "栏目推荐",
                    "menuElite",
                    searchCondition.getMenuElite());
        }
        // 导航推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.NAV_ELITE_AUTHORITY_ID)) {
            hasNavElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "导航推荐",
                    "navElite",
                    searchCondition.getNavElite());
        }
        // 右侧推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.RIGHT_ELITE_AUTHORITY_ID)) {
            hasRightElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "右侧推荐",
                    "rightElite",
                    searchCondition.getRightElite());
        }
        // 左侧推荐权限
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.LEFT_ELITE_AUTHORITY_ID)) {
            hasLeftElite = true;
            builderOptionsHtml(
                    stringBuilderSearchOptionsHtml,
                    "左侧推荐",
                    "leftElite",
                    searchCondition.getLeftElite());
        }
        // 导出数据
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.DATA_OUTPUT_AUTHORITY_ID)) {
            hasOutput = true;
        }
        // 导入数据
        if (AuthorityUtils.hasOtherSetting(
                loginUser, modelById.getId(), WebConst.DATA_INPUT_AUTHORITY_ID)) {
            hasImport = true;
        }

        List<OneshotEntity> childMenu = oneShotDao.getChildMenu(modelById.getId());

        // 列表页标题
        String modelListDisplayFieldTitle =
                getModelListDisplayFieldTitle(
                        modelById.getModelList(),
                        hasDetail,
                        hasSeo,
                        hasSort,
                        hasStatusList,
                        childMenu,
                        count,
                        hasEdit);
        int index = 0;

        List<ContentFieldDefinition> fieldSettingEntityList =
                ContentModelCodec.read(modelById.getModelField());
        // 总字段数
        int fieldSize = fieldSettingEntityList.size();
        // 编辑器、多行字段集合
        List<ContentFieldDefinition> editorList =
                fieldSettingEntityList.stream()
                        .filter(
                                item ->
                                        item.getFieldType().equals("Editor")
                                                || item.getFieldType().equals("MultiLine"))
                        .collect(Collectors.toList());
        // 编辑器字段个数
        int editorFieldSize = editorList.size();

        String previewUrl;

        for (ContentEntity contentEntityInfo : contentPage.getList()) {
            // 列表显示字段绑定
            FieldHtmlVo fieldHtmlVo =
                    getModelListDisplayFieldList(modelById.getModelList(), contentEntityInfo);

            // 复选框
            stringBuilderListHtml.append(
                    "<tr><td style=\"text-align:center;vertical-align: middle;\">\n"
                        + "                                    <input type=\"checkbox\" name=\"Id\""
                        + " value=\""
                            + contentEntityInfo.getId()
                            + "\">\n"
                            + "                                </td>\n");
            // Id
            stringBuilderListHtml.append(
                    "<td style=\"text-align:center;vertical-align: middle;\">\n"
                            + contentEntityInfo.getId()
                            + "</td>\n");
            // 动态设置字段
            stringBuilderListHtml.append(fieldHtmlVo.getHtml());

            // 关联子栏目
            for (OneshotEntity menu : childMenu) {
                // 存在多级相关栏目时给地址增加父级相关ID参数
                String urlXgid;
                if (contentCond.getXgid() > 0) {
                    urlXgid = "&pXgid=" + contentCond.getXgid();
                } else {
                    urlXgid = "";
                }
                stringBuilderListHtml.append(
                        "<td style=\"text-align:center;vertical-align: middle;\">\n");
                stringBuilderListHtml.append(
                        "<a class=\"btn btn-default btn-sm\" href=\"/admin/model_manage/"
                                + menu.getId()
                                + "?xgid="
                                + contentEntityInfo.getId()
                                + urlXgid
                                + "\">"
                                + menu.getClassName()
                                + "</a>\n");
                stringBuilderListHtml.append("</td>\n");
            }

            // 浏览量
            if (hasDetail) {
                stringBuilderListHtml.append(
                        "<td style=\"text-align:center;vertical-align: middle;\">\n"
                                + "                         <span class=\"label label-danger"
                                + " info-hits\">"
                                + contentEntityInfo.getHits()
                                + "</span>\n"
                                + "                     </td>\n");
            }

            // 排序
            if (hasSort) {
                stringBuilderListHtml.append(
                        "<td style=\"text-align:center;vertical-align: middle;\">\n"
                                + "                         <span class=\"label label-danger"
                                + " info-sort-span\" \n"
                                + "                              "
                                + " data-action-url=\"/admin/model_manage/"
                                + modelById.getId()
                                + "/updateSort\" \n"
                                + "                               title=\"点击快速修改排序号\"\n"
                                + "                               data-id=\""
                                + contentEntityInfo.getId()
                                + "\">"
                                + contentEntityInfo.getSort()
                                + "</span>\n"
                                + "                     </td>\n");
            }

            // 状态
            if (hasStatusList) {
                if (contentEntityInfo.getStatus() == 1) {
                    if (contentEntityInfo.getEntryIntoForceTime().compareTo(LocalDateTime.now())
                            > 0) {
                        statusTitle = "<span class=\"label label-info\">已发布待上线</span>\n";
                    } else if (contentEntityInfo.getExpirationTime().compareTo(LocalDateTime.now())
                            < 0) {
                        statusTitle = "<span class=\"label label-default\">已过期</span>\n";
                    } else {
                        statusTitle = "<span class=\"label label-success\">已发布</span>\n";
                    }
                } else if (contentEntityInfo.getStatus() == 0) {
                    statusTitle = "<span class=\"label label-default\">草稿</span>\n";
                } else if (contentEntityInfo.getStatus() == 2) {
                    statusTitle = "<span class=\"label label-default\">待审核</span>\n";
                } else if (contentEntityInfo.getStatus() == 3) {
                    statusTitle = "<span class=\"label label-default\">未通过</span>\n";
                }
                stringBuilderListHtml.append(
                        "<td style=\"text-align:center;vertical-align: middle;\">"
                                + statusTitle
                                + "</td>\n");
            }

            // 操作权限
            stringBuilderListHtml.append(
                    "<td style=\"text-align:center;vertical-align: middle;\"><div"
                            + " class=\"btn-group\">\n");
            if (hasEdit) {
                // 修改
                stringBuilderListHtml.append(
                        "<button type=\"button\" class=\"btn btn-danger btn-sm info-update\"\n"
                                + "                             data-url=\"/admin/model_manage/"
                                + contentEntityInfo.getClassid()
                                + "/edit/"
                                + contentEntityInfo.getId()
                                + "\"\n"
                                + "                             data-class-name=\""
                                + modelById.getClassName()
                                + "\" data-field-size=\""
                                + fieldSize
                                + "\" data-editor-size=\""
                                + editorFieldSize
                                + "\">\n"
                                + "                         <i class=\"fa fa-pencil\""
                                + " aria-hidden=\"true\"></i> 修改\n"
                                + "                     </button>\n");
            }

            // 删除
            if (hasDelete) {
                stringBuilderListHtml.append(
                        "<button type=\"button\" class=\"btn btn-default btn-sm info-delete\"\n"
                                + "                             data-id=\""
                                + contentEntityInfo.getId()
                                + "\" data-url=\"/admin/model_manage/"
                                + modelById.getId()
                                + "/delete\">\n"
                                + "                         <i class=\"fa fa-trash\""
                                + " aria-hidden=\"true\"></i> 删除\n"
                                + "                     </button>\n");
            }

            // 按钮下拉组合
            stringBuilderListHtml.append(
                    "<button type=\"button\" class=\"btn btn-primary btn-sm dropdown-toggle\""
                            + " data-toggle=\"dropdown\">更多 ");
            stringBuilderListHtml.append("		<span class=\"caret\"></span>");
            stringBuilderListHtml.append("	</button>");
            stringBuilderListHtml.append("	<ul class=\"dropdown-menu\" role=\"menu\">");
            // SEO设置
            if (hasSeo || hasView) {
                // SEO设置
                if (hasSeo) {
                    stringBuilderListHtml.append(
                            "<li><a href=\"javascript:void(0);\" class=\"info-seo\"\n"
                                    + "                             data-url=\"/admin/model_manage/"
                                    + modelById.getId()
                                    + "/seo/"
                                    + contentEntityInfo.getId()
                                    + "\">\n"
                                    + "                         <i class=\"fa fa-search\""
                                    + " aria-hidden=\"true\"></i> SEO设置\n"
                                    + "                     </a></li>\n");
                    // 页面预览
                    previewUrl =
                            "/preview/details"
                                    + modelById.getId()
                                    + "_"
                                    + contentEntityInfo.getId()
                                    + ".html";
                    if (StringUtils.isNotBlank(contentEntityInfo.getSingleLine4())) {
                        previewUrl = contentEntityInfo.getSingleLine4();
                    } else if (StringUtils.isNotBlank(contentEntityInfo.getFile1())) {
                        previewUrl = contentEntityInfo.getFile1();
                    }
                    stringBuilderListHtml.append(
                            "<li><a href=\""
                                    + previewUrl
                                    + "\" target=\"_blank\">\n"
                                    + "                         <i class=\"fa"
                                    + " fa-internet-explorer\" aria-hidden=\"true\"></i> 预览页面\n"
                                    + "                     </a></li>\n");
                }
                if (hasView) {
                    stringBuilderListHtml.append(
                            "<li><a href=\"javascript:void(0);\" class=\"info-view\"\n"
                                    + "                             data-url=\"/admin/model_manage/"
                                    + modelById.getId()
                                    + "/edit/"
                                    + contentEntityInfo.getId()
                                    + "\">\n"
                                    + "                         <i class=\"fa fa-search\""
                                    + " aria-hidden=\"true\"></i> 查看\n"
                                    + "                     </a></li>\n");
                }
            }

            // 查看操作日志
            if (hasEdit) {
                stringBuilderListHtml.append(
                        "<li><a href=\"javascript:void(0);\" class=\"info-history-details\"\n"
                                + "                             data-id=\""
                                + contentEntityInfo.getId()
                                + "\" data-url=\"/admin/content/history/list/"
                                + contentEntityInfo.getClassid()
                                + "/"
                                + contentEntityInfo.getId()
                                + "\">\n"
                                + "                         <i class=\"fa fa-eye\""
                                + " aria-hidden=\"true\"></i> 审核信息\n"
                                + "                     </a></li>\n");
            }

            // 这是一个分割线
            //            stringBuilderListHtml.append("		<li class=\"divider\"></li>");

            stringBuilderListHtml.append("	</ul>");

            stringBuilderListHtml.append("</div>\n" + "           </td>\n</tr>\n");
            index++;
        }

        // 根据推荐权限设置推荐项
        // 首页置顶
        if (hasIndexTop) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"1\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>首页置顶</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"2\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 首页热门
        if (hasIndexHot) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"3\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>首页热门</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"4\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 首页推荐
        if (hasIndexElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"5\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>首页推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"6\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 频道置顶
        if (hasChannelTop) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"7\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>频道置顶</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"8\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 频道热门
        if (hasChannelHot) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"9\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>频道热门</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"10\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 频道推荐
        if (hasChannelElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"11\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>频道推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"12\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 栏目置顶
        if (hasMenuTop) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"13\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>栏目置顶</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"14\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 栏目热门
        if (hasMenuHot) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"15\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>栏目热门</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"16\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 栏目推荐
        if (hasMenuElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"17\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>栏目推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"18\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 导航推荐
        if (hasNavElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"19\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>导航推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"20\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 右侧推荐
        if (hasRightElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"21\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>右侧推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"22\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }
        // 左侧推荐
        if (hasLeftElite) {
            stringBuilderRecommendHtml.append(
                    "<div class=\"btn-group\" style=\"margin-right: 5px;margin-bottom: 5px;\">\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"23\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-up\"></i>\n"
                            + "                         <span>左侧推荐</span></button>\n"
                            + "                     <button class=\"btn btn-default info-options\""
                            + " data-option=\"24\"\n"
                            + "                             data-url=\"/admin/model_manage/"
                            + modelById.getId()
                            + "/option\"><i\n"
                            + "                             class=\"fa fa-arrow-down\"></i>\n"
                            + "                         <span>取消</span></button>\n"
                            + "                 </div>");
        }

        ModelFieldHtmlVo modelFieldHtmlVo = new ModelFieldHtmlVo();
        modelFieldHtmlVo.setClassid(modelById.getId());
        modelFieldHtmlVo.setXgid(contentCond.getXgid());
        // 如果父级相关id不为空则赋值
        if (httpServletRequest.getParameter("pXgid") != null) {
            modelFieldHtmlVo.setPXgid(
                    Integer.valueOf(httpServletRequest.getParameter("pXgid")).intValue());
        } else {
            modelFieldHtmlVo.setPXgid(0);
        }
        modelFieldHtmlVo.setParentId(modelById.getParentId());
        modelFieldHtmlVo.setIsStatus(hasStatus);
        modelFieldHtmlVo.setIsDelete(hasDelete);
        modelFieldHtmlVo.setIsAdd(hasAdd);
        modelFieldHtmlVo.setIsOutput(hasOutput);
        modelFieldHtmlVo.setIsImport(hasImport);
        modelFieldHtmlVo.setClassName(modelById.getClassName());
        modelFieldHtmlVo.setTitleHtml(modelListDisplayFieldTitle);
        modelFieldHtmlVo.setHtml(stringBuilderListHtml.toString());
        modelFieldHtmlVo.setRecommendHtml(stringBuilderRecommendHtml.toString());
        modelFieldHtmlVo.setSearchHtml(stringBuilderSearchHtml.toString());
        modelFieldHtmlVo.setSearchJsHtml(stringBuilderSearchJsHtml.toString());
        modelFieldHtmlVo.setSearchOptionsHtml(stringBuilderSearchOptionsHtml.toString());
        modelFieldHtmlVo.setPageHtml(stringBuilderPageHtml);
        return modelFieldHtmlVo;
    }

    /**
     * 构建下拉搜索条件的html代码
     *
     * @param stringBuilderSearchOptionsHtml
     * @param optionTitle 如:首页推荐、首页热门
     * @param fieldName 数据字段名
     * @param source 搜索值
     */
    private void builderOptionsHtml(
            StringBuilder stringBuilderSearchOptionsHtml,
            String optionTitle,
            String fieldName,
            Integer source) {
        stringBuilderSearchOptionsHtml.append(
                "\n<select class=\"form-control\" name=\""
                        + fieldName
                        + "\">\n"
                        + "                                    <option value=\"\">"
                        + optionTitle
                        + "</option>\n"
                        + "                                    <option value=\"1\""
                        + getSelectedString(source, WebConst.ONE)
                        + ">是</option>\n"
                        + "                                    <option value=\"0\""
                        + getSelectedString(source, WebConst.ZERO)
                        + ">否</option>\n"
                        + "                                </select>\n");
    }

    /**
     * 获取选中状态字符串
     *
     * @param source
     * @param target
     * @return
     */
    private String getSelectedString(Integer source, Integer target) {
        if (source == null) {
            return "";
        }
        if (source.equals(target)) {
            return " selected=\"selected\"";
        }
        return "";
    }

    @Override
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    @Transactional(rollbackFor = Exception.class)
    public ResultVO updateEditOpen(
            OpenEditOneshotDto openEditOneshotDto, HttpServletRequest httpServletRequest) {
        Integer id = openEditOneshotDto.getId();
        if (id != null && id > 0) {
            ResultVO siteScopeResult = validateCurrentSiteScopeForOpenEdit(id, httpServletRequest);
            if (siteScopeResult != null) {
                return siteScopeResult;
            }
            // 修改栏目
            EditOneshotDto editOneshotDto = new EditOneshotDto();
            editOneshotDto
                    .setId(id)
                    .setClassName(openEditOneshotDto.getClassName())
                    .setClassEnName(openEditOneshotDto.getClassEnName())
                    .setShowPosition(openEditOneshotDto.getShowPosition());

            OneshotEntity locked = lockSearchMenu(id);
            siteScopeResult = validateCurrentSiteScopeForMenu(locked, httpServletRequest);
            if (siteScopeResult != null) return siteScopeResult;
            if (locked == null) return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
            Boolean updateEdit = oneShotDao.updateEdit(editOneshotDto);
            if (updateEdit) {
                publicSearchProjectionService.rebuildMenu(locked.getSiteProjectId(), id);
                // 操作日志明细
                UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
                LogOperationEntity logOperationEntity = new LogOperationEntity();
                logOperationEntity
                        .setAction("栏目管理（开放） - 修改栏目信息")
                        .setCreated(LocalDateTime.now())
                        .setInfoId(0)
                        .setClassId(openEditOneshotDto.getId())
                        .setRequestData(JSON.toJSONString(openEditOneshotDto))
                        .setResponseData(JSON.toJSONString(updateEdit))
                        .setUserName(loginUser.getUsername())
                        .setUserId(loginUser.getId())
                        .setIp(RequestOriginUtils.clientIp(httpServletRequest));
                logOperationService.add(logOperationEntity);
                // 重置全量发布
                resetFullRelease();

                return ResultVO.saveSuccess(id);
            } else {
                return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
            }
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    private ResultVO validateCurrentSiteScopeForOpenEdit(
            Integer id, HttpServletRequest httpServletRequest) {
        return validateCurrentSiteScopeForMenuId(id, httpServletRequest);
    }

    @Override
    public List<NodeVo> getBuilderNodes(HttpServletRequest httpServletRequest) {
        // 获取当前站点的所有栏目（不过滤 hasManageChannel，确保父节点完整）
        SiteContext currentSite = currentSite(httpServletRequest);
        List<OneshotEntity> oneshotEntities;
        if (currentSite.isGlobalMode() || currentSite.getRootMenuId() == null) {
            oneshotEntities = oneShotDao.getAllLeftMenu();
        } else {
            oneshotEntities = oneShotDao.selectBySiteRoot(currentSite.getRootMenuId());
        }

        // 只过滤系统栏目
        if (CollectionUtils.isNotEmpty(oneshotEntities)) {
            oneshotEntities =
                    oneshotEntities.stream()
                            .filter(item -> !Boolean.TRUE.equals(item.getIsSystem()))
                            .collect(Collectors.toList());
        }

        // 收集所有需要包含的栏目 ID（包括祖先节点）
        Set<Integer> allIds = new HashSet<>();
        for (OneshotEntity entity : oneshotEntities) {
            allIds.add(entity.getId());
        }

        // 确保所有父节点都被包含
        Map<Integer, OneshotEntity> entityMap = new HashMap<>();
        for (OneshotEntity entity : oneshotEntities) {
            entityMap.put(entity.getId(), entity);
        }

        // 添加缺失的父节点
        for (OneshotEntity entity : new ArrayList<>(oneshotEntities)) {
            Integer parentId = entity.getParentId();
            while (parentId != null && parentId > 0 && !entityMap.containsKey(parentId)) {
                OneshotEntity parent = oneShotDao.getModelById(parentId);
                if (parent != null && !Boolean.TRUE.equals(parent.getIsSystem())) {
                    entityMap.put(parentId, parent);
                    allIds.add(parentId);
                    parentId = parent.getParentId();
                } else {
                    break;
                }
            }
        }

        List<NodeVo> nodeVoList = new ArrayList<>();
        for (OneshotEntity oneshotEntity : entityMap.values()) {
            NodeVo nodeVo = new NodeVo();
            nodeVo.setId(oneshotEntity.getId());
            nodeVo.setPId(oneshotEntity.getParentId());
            nodeVo.setName(oneshotEntity.getClassName());
            nodeVo.setOpen(true);

            // 有列表页或详情页 URL 的栏目可以勾选发布
            if (StringUtils.isNotBlank(oneshotEntity.getListPageUrl())
                    || StringUtils.isNotBlank(oneshotEntity.getDetailPageUrl())) {
                nodeVo.setChkDisabled(false);
            } else {
                // 检查是否有子节点
                boolean hasChildren =
                        entityMap.values().stream()
                                .anyMatch(item -> oneshotEntity.getId().equals(item.getParentId()));
                nodeVo.setChkDisabled(!hasChildren);
            }
            nodeVoList.add(nodeVo);
        }
        return nodeVoList;
    }

    @Override
    public List<NodeVo> getBuilderNodes(HttpServletRequest httpServletRequest, Integer parentId) {
        SiteContext currentSite = currentSite(httpServletRequest);
        List<OneshotEntity> oneshotEntities;

        // 确定要加载的父节点 ID
        Integer effectiveParentId = parentId;
        if (effectiveParentId == null) {
            // 如果没有指定 parentId，从站点根节点开始
            if (!currentSite.isGlobalMode() && currentSite.getRootMenuId() != null) {
                effectiveParentId = currentSite.getRootMenuId();
            } else {
                effectiveParentId = 0;
            }
        }

        // 获取指定父节点的直接子节点
        oneshotEntities = oneShotDao.getChildMenu(effectiveParentId);

        // 过滤系统栏目
        if (CollectionUtils.isNotEmpty(oneshotEntities)) {
            oneshotEntities =
                    oneshotEntities.stream()
                            .filter(item -> !Boolean.TRUE.equals(item.getIsSystem()))
                            .collect(Collectors.toList());
        }

        List<NodeVo> nodeVoList = new ArrayList<>();
        if (oneshotEntities != null) {
            for (OneshotEntity oneshotEntity : oneshotEntities) {
                NodeVo nodeVo = new NodeVo();
                nodeVo.setId(oneshotEntity.getId());
                nodeVo.setPId(oneshotEntity.getParentId());
                nodeVo.setName(oneshotEntity.getClassName());
                nodeVo.setOpen(false);

                // 有列表页或详情页 URL 的栏目可以勾选发布
                if (StringUtils.isNotBlank(oneshotEntity.getListPageUrl())
                        || StringUtils.isNotBlank(oneshotEntity.getDetailPageUrl())) {
                    nodeVo.setChkDisabled(false);
                } else {
                    // 检查是否有子节点
                    List<OneshotEntity> children = oneShotDao.getChildMenu(oneshotEntity.getId());
                    boolean hasChildren =
                            children != null
                                    && children.stream()
                                            .anyMatch(
                                                    item ->
                                                            !Boolean.TRUE.equals(
                                                                    item.getIsSystem()));
                    nodeVo.setChkDisabled(!hasChildren);
                }
                nodeVoList.add(nodeVo);
            }
        }
        return nodeVoList;
    }

    @Override
    public void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            final String taskId) {
        builderHtml(
                httpServletRequest,
                httpServletResponse,
                taskId,
                null,
                null,
                null,
                null,
                OneShotService.PageBuildProgress.none());
    }

    @Override
    public void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            final String taskId,
            String languageCode) {
        builderHtml(
                httpServletRequest,
                httpServletResponse,
                taskId,
                languageCode,
                null,
                null,
                null,
                OneShotService.PageBuildProgress.none());
    }

    @Override
    public void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            final String taskId,
            String languageCode,
            OperationExecutionContext operationContext) {
        builderHtml(
                httpServletRequest,
                httpServletResponse,
                taskId,
                languageCode,
                operationContext,
                null,
                null,
                OneShotService.PageBuildProgress.none());
    }

    @Override
    public void builderHtmlToStaging(
            HttpServletRequest request,
            HttpServletResponse response,
            String releaseId,
            String languageCode,
            List<Integer> menuIds,
            Path stagingRoot) {
        if (stagingRoot == null || menuIds == null || menuIds.isEmpty())
            throw new IllegalArgumentException("release staging 参数无效");
        builderHtml(
                request,
                response,
                releaseId,
                languageCode,
                null,
                menuIds,
                stagingRoot.toString(),
                OneShotService.PageBuildProgress.none());
    }

    @Override
    public void builderHtmlToStaging(
            HttpServletRequest request,
            HttpServletResponse response,
            String releaseId,
            String languageCode,
            List<Integer> menuIds,
            Path stagingRoot,
            OneShotService.PageBuildProgress progress) {
        if (stagingRoot == null || menuIds == null || menuIds.isEmpty())
            throw new IllegalArgumentException("release staging 参数无效");
        builderHtml(
                request,
                response,
                releaseId,
                languageCode,
                null,
                menuIds,
                stagingRoot.toString(),
                progress == null ? OneShotService.PageBuildProgress.none() : progress);
    }

    private void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            final String taskId,
            String languageCode,
            OperationExecutionContext operationContext,
            List<Integer> forcedMenuIds,
            String targetRoot,
            OneShotService.PageBuildProgress pageProgress) {
        assertOperationActive(operationContext);
        long beginTime = System.currentTimeMillis();
        log.info("准备发布静态页面");
        try {
            final List<Integer> menuIds =
                    forcedMenuIds == null
                            ? (List<Integer>) RedisUtils.getEntity(taskId, List.class)
                            : forcedMenuIds;
            if (CollectionUtils.isEmpty(menuIds))
                throw new IllegalStateException("静态发布任务已过期或未选择栏目");

            List<OneshotEntity> oneshotEntityList =
                    forcedMenuIds == null
                            ? getOneshotEntities(httpServletRequest)
                            : oneShotDao.getAllLeftMenu();
            oneshotEntityList =
                    oneshotEntityList.stream()
                            .filter(item -> menuIds.contains(item.getId()))
                            .collect(Collectors.toList());
            if (oneshotEntityList.size() != new HashSet<>(menuIds).size())
                throw new IllegalStateException("部分待发布栏目已变化或不属于当前站点");
            if (CollectionUtils.isNotEmpty(oneshotEntityList)) {
                log.info("即将发布的栏目:{}", JSON.toJSONString(oneshotEntityList));
                // 输出进度是否成功
                Boolean roll;
                // 栏目对象
                OneshotEntity oneshotEntity;
                Long siteProjectId = requirePublishSite(oneshotEntityList);
                oneshotEntityList =
                        oneshotEntityList.stream()
                                .map(
                                        item -> {
                                            var current =
                                                    oneShotDao.selectPublicBindingMenu(
                                                            siteProjectId,
                                                            item.getId().longValue());
                                            if (current == null
                                                    || !siteProjectId.equals(
                                                            current.getSiteProjectId())
                                                    || !item.getId().equals(current.getId()))
                                                throw new IllegalStateException("待发布栏目已变化或不属于当前站点");
                                            validatePublishMenu(current, targetRoot == null);
                                            return current;
                                        })
                                .toList();
                Set<Integer> detailOnlyMenus =
                        oneshotEntityList.stream()
                                .filter(menu -> publishDetailsOnly(menu, targetRoot == null))
                                .map(OneshotEntity::getId)
                                .collect(Collectors.toSet());
                String skippedLists =
                        detailOnlyMenus.isEmpty()
                                ? ""
                                : " 本次跳过未绑定模板的列表页（栏目 ID："
                                        + detailOnlyMenus.stream().sorted().map(String::valueOf)
                                                .collect(Collectors.joining("、"))
                                        + "），请绑定列表模板后另行发布。";
                // 先完成校验，避免在失败时已经提交进度响应。
                HtmlProgressBar.Start("正在加载..." + skippedLists, httpServletResponse);
                String siteCode = siteStoragePathResolver.requireSiteCode(siteProjectId);
                String localHostUrl =
                        requireRenderOrigin(siteResourceAccess.getIfAvailable(), siteCode);
                // 保存根目录
                String saveRoot =
                        StringUtils.defaultIfBlank(
                                targetRoot, cmsBuilderHtmlConfigProperties.getSaveRoot());
                if (StringUtils.isBlank(saveRoot)) {
                    throw new RuntimeException("尚未配置静态页保存目录请配置cms.html.save-root属性");
                }
                if (targetRoot == null) {
                    // 直接发布：在保存根目录下追加站点编码作为隔离目录；release staging 由事务层负责作用域，不在此叠加
                    saveRoot = siteStoragePathResolver.siteRootPath(saveRoot, siteCode);
                }
                I18nRenderContext renderContext =
                        I18nRenderContext.of(languageCode, localHostUrl, saveRoot);
                GeneratedPagePaths generatedPages = new GeneratedPagePaths();
                // 生成页面计数器
                Integer pageCount = 0;
                // 栏目总数
                Integer menuSize = oneshotEntityList.size();
                // 每个栏目的百分比
                Double menuPercent = Double.valueOf(StringDeal.getPercent(1, menuSize, 2));

                for (int i = 0; i < menuSize; i++) {
                    renewAndAssertOperation(operationContext);
                    oneshotEntity = oneshotEntityList.get(i);
                    roll =
                            HtmlProgressBar.Roll(
                                    "正在生成栏目 - ["
                                            + oneshotEntity.getClassName()
                                            + "] "
                                            + i
                                            + "/"
                                            + menuSize
                                            + ",累计已生成"
                                            + pageCount
                                            + "个页面",
                                    menuPercent * i,
                                    httpServletResponse);
                    if (!roll) {
                        break;
                    }
                    if (!detailOnlyMenus.contains(oneshotEntity.getId())) {
                        pageCount =
                                builderOneShotHtml(
                                        httpServletResponse,
                                        oneshotEntity,
                                        renderContext,
                                        pageCount,
                                        menuSize,
                                        i,
                                        operationContext,
                                        pageProgress,
                                        generatedPages);
                    }
                    // 生成内容页
                    pageCount =
                            builderContentHtml(
                                    httpServletResponse,
                                    oneshotEntity,
                                    renderContext,
                                    pageCount,
                                    menuSize,
                                    menuPercent,
                                    i,
                                    operationContext,
                                    pageProgress,
                                    generatedPages);
                }
                HtmlProgressBar.Roll(pageCount == 0 ? "未生成静态页面" : "生成完毕", 100, httpServletResponse);
                assertOperationActive(operationContext);

                if (httpServletResponse != null)
                    try {
                        long endTime = System.currentTimeMillis();
                        long durationTime = endTime - beginTime;
                        Duration duration = Duration.ofMillis(durationTime);
                        String builderResult =
                                (pageCount == 0 ? "未生成静态页面，总共用时:" : "生成完毕，总共用时:")
                                        + (StringDeal.getTimeDurationString(durationTime))
                                        + "，共生成页面:"
                                        + pageCount
                                        + "个，平均每个页面耗时:"
                                        + (pageCount == 0 ? 0 : durationTime / pageCount)
                                        + "毫秒。"
                                        + skippedLists;
                        httpServletResponse.setContentType("text/html;charset=utf-8");
                        httpServletResponse
                                .getWriter()
                                .write(
                                        builderResult
                                                + "<span hidden data-cms-static-count=\"" + pageCount + "\"></span>"
                                                + "<script>if (typeof window.parent.setCloseBtnTxt === 'function') window.parent.setCloseBtnTxt();</script>");
                        httpServletResponse.flushBuffer();
                    } catch (IOException e) {
                        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
                            httpServletResponse.setStatus(200);
                            httpServletResponse.setContentType("text/html;charset=utf-8");
                            log.info("客户端手动终止请求:{}", e.getMessage());
                        } else {
                            log.error(e.getMessage());
                        }
                    }
            } else {
                assertOperationActive(operationContext);
                if (httpServletResponse != null)
                    try {
                        httpServletResponse.setContentType("text/html;charset=utf-8");
                        httpServletResponse.getWriter().write("没有要发布的栏目");
                        httpServletResponse.flushBuffer();
                    } catch (IOException e) {
                        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
                            httpServletResponse.setStatus(200);
                            httpServletResponse.setContentType("text/html;charset=utf-8");
                            log.info("客户端手动终止请求:{}", e.getMessage());
                        } else {
                            log.error(e.getMessage());
                        }
                    }
            }
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    private static void assertOperationActive(OperationExecutionContext context) {
        if (context != null) {
            context.assertActive();
        }
    }

    private static void renewAndAssertOperation(OperationExecutionContext context) {
        if (context != null) {
            context.renew();
            context.assertActive();
        }
    }

    @Override
    public List<OneshotEntity> getEditableMenu(OneshotDto oneshotDto) {
        List<OneshotEntity> allMenu = getAllMenuCached();
        return allMenu.stream().filter(item -> !item.getIsSystem()).collect(Collectors.toList());
    }

    @Override
    public List<OneshotEntity> getEditableMenu(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest) {
        List<OneshotEntity> allMenu =
                scopeMenusByCurrentSite(getAllMenuCached(), httpServletRequest);
        return allMenu.stream().filter(item -> !item.getIsSystem()).collect(Collectors.toList());
    }

    @Override
    public List<BusinessEntity> getBusinessEntityList() {
        return oneShotDao.getBusinessEntityList();
    }

    /**
     * 发布栏目页
     *
     * @param httpServletResponse
     * @param oneshotEntity
     * @param localHostUrl
     * @param saveRoot
     * @param pageCount
     * @param menuSize
     * @param i
     * @return
     */
    private Integer builderOneShotHtml(
            HttpServletResponse httpServletResponse,
            OneshotEntity oneshotEntity,
            I18nRenderContext renderContext,
            Integer pageCount,
            Integer menuSize,
            int i,
            OperationExecutionContext operationContext,
            OneShotService.PageBuildProgress pageProgress,
            GeneratedPagePaths generatedPages) {
        Boolean roll;
        String listPageUrl;
        if (StringUtils.isNotBlank(oneshotEntity.getListPageUrl())
                || Objects.equals(oneshotEntity.getId(), oneshotEntity.getSiteRootId())) {
            roll =
                    HtmlProgressBar.Roll(
                            "正在生成栏目页 - ["
                                    + oneshotEntity.getClassName()
                                    + "] "
                                    + i
                                    + "/"
                                    + menuSize
                                    + ",累计已生成"
                                    + pageCount
                                    + "个页面",
                            Double.valueOf(StringDeal.getPercent(i, menuSize, 2)),
                            httpServletResponse);
            if (!roll) {
                //                break;
                return pageCount;
            }
            listPageUrl =
                    contentLinks
                            .getListUrl(oneshotEntity)
                            .replace(
                                    "${className}",
                                    Objects.toString(oneshotEntity.getClassName(), ""));
            if (StringUtils.isBlank(oneshotEntity.getListPageUrl())) listPageUrl = "/";
            if (listPageUrl.startsWith(WebConst.PROTOCOL_HTTP_PREFIX)
                    || listPageUrl.startsWith(WebConst.PROTOCOL_HTTPS_PREFIX)) {
                return pageCount;
            }
            pageCount =
                    builderListPageHtml(
                            renderContext,
                            pageCount,
                            listPageUrl,
                            listPageUrl,
                            oneshotEntity,
                            i,
                            menuSize,
                            httpServletResponse,
                            operationContext,
                            pageProgress,
                            generatedPages);
            if (Objects.equals(oneshotEntity.getId(), oneshotEntity.getSiteRootId())) {
                var aliases = nativeHomeAliases(oneshotEntity).orElse(List.of());
                for (String alias : aliases) {
                    if (Objects.equals(staticFilePath(renderContext, listPageUrl), staticFilePath(renderContext, alias)))
                        continue;
                    pageCount = builderListPageHtml(renderContext, pageCount, alias, alias, oneshotEntity,
                            i, menuSize, httpServletResponse, operationContext, pageProgress, generatedPages);
                }
            }
            /*
            if (!listPageUrl.startsWith(WebConst.PROTOCOL_HTTP_PREFIX) && !listPageUrl.startsWith(WebConst.PROTOCOL_HTTPS_PREFIX) && listPageUrl.endsWith(WebConst.HTML_SUFFIX)) {
                pageCount = builderListPageHtml(localHostUrl, saveRoot, pageCount, listPageUrl, localHostUrl + listPageUrl, oneshotEntity, i, menuSize, httpServletResponse);
            } else if (oneshotEntity.getId().equals(WebConst.HOME_ID)) {
                listPageUrl = "/index20231208.html";
                pageCount = builderListPageHtml(localHostUrl, saveRoot, pageCount, listPageUrl, localHostUrl + listPageUrl, oneshotEntity, i, menuSize, httpServletResponse);
            } else {
                log.warn("栏目页地址{}不符合发布页面的条件", listPageUrl);
            }*/
        }
        return pageCount;
    }

    /**
     * 发布栏目页和列表及分页
     *
     * @param localHostUrl 动态域名前缀
     * @param saveRoot 保存html根目录
     * @param pageCount 生成页面数
     * @param savePath 保存html路径 后半截
     * @param href 动态地址完整路径
     * @return
     */
    private Integer builderListPageHtml(
            I18nRenderContext renderContext,
            Integer pageCount,
            String savePath,
            String href,
            OneshotEntity oneshotEntity,
            Integer i,
            Integer menuSize,
            HttpServletResponse httpServletResponse,
            OperationExecutionContext operationContext,
            OneShotService.PageBuildProgress pageProgress,
            GeneratedPagePaths generatedPages) {
        String normalizedSavePath = normalizePagePath(renderContext, savePath);
        if (normalizedSavePath == null) throw new IllegalArgumentException("栏目静态发布路径不是当前站点的有效路由");
        if (!generatedPages.claim(normalizedSavePath)) {
            log.info("栏目页地址{}已经在此次发布中发布过了,此处直接跳过", href);
            return pageCount;
        }

        String htmlResult;
        Boolean roll =
                HtmlProgressBar.Roll(
                        "正在生成栏目页 - ["
                                + oneshotEntity.getClassName()
                                + "] "
                                + i
                                + "/"
                                + menuSize
                                + ",累计已生成"
                                + pageCount
                                + "个页面",
                        Double.valueOf(StringDeal.getPercent(i, menuSize, 2)),
                        httpServletResponse);
        if (!roll) {
            //                break;
            return pageCount;
        }
        log.info("准备请求 {} 来创建静态页 {}", href, normalizedSavePath);
        try {
            htmlResult = renderStaticPage(renderContext, href, oneshotEntity, false);
        } catch (RestClientException e) {
            e.printStackTrace();
            HtmlProgressBar.Roll(
                    "发布源页面 " + href + " 自身异常",
                    Double.valueOf(StringDeal.getPercent(i, menuSize, 2)),
                    httpServletResponse);
            throw new IllegalStateException("发布源页面 " + href + " 生成失败：" + e.getMessage(), e);
        }
        href = renderContext.dynamicUrl() + normalizedSavePath;
        var pages =
                new ArrayDeque<>(
                        listPageLinks(renderContext, Jsoup.parse(htmlResult, href), oneshotEntity));
        var scheduledPaths = new HashSet<String>();
        pages.forEach(page -> scheduledPaths.add(page.publicPath()));
        final String filePath = staticFilePath(renderContext, normalizedSavePath);
        Path root = Path.of(renderContext.saveRoot()).toAbsolutePath().normalize();
        String configuredRoute = oneshotEntity.getListPageUrl();
        var previous = new LinkedHashMap<Path, BasicFileAttributes>();
        var legacyPages = new HashSet<Path>();
        if (configuredRoute != null && configuredRoute.contains("{pageIndex}")) {
            String route = paginationRoute(configuredRoute, oneshotEntity, oneshotEntity.getId());
            if (normalizedSavePath.equals(normalizePagePath(renderContext, route.replace("{pageIndex}", "1")))) {
                try {
                    var candidates = StaticPaginationFiles.existingFiles(root, route);
                    legacyPages.addAll(StaticPaginationFiles.linkedFiles(root,
                            Path.of(filePath).toAbsolutePath().normalize(), candidates,
                            "div.page a[href], [data-cms-pagination=true] a[href]"));
                    for (Path old : candidates) {
                        try {
                            previous.put(old, Files.readAttributes(
                                    old, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
                        } catch (NoSuchFileException alreadyRemoved) {
                            // 并行发布已清理该文件。
                        }
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException("无法读取栏目原有静态分页", ex);
                }
            }
        }
        final String htmlContent = htmlResult;
        pageProgress.beforePage(normalizedSavePath);
        writeStaticFile(filePath, htmlContent, operationContext, null, oneshotEntity, null);
        pageProgress.afterPage(normalizedSavePath);

        pageCount++;

        // 分页生成
        while (!pages.isEmpty()) {
            PageLink pageLink = pages.removeFirst();
            href = pageLink.href();
            String pagePath = pageLink.publicPath();
            if (!generatedPages.claim(pagePath)) continue;
            String pageHtml = "";
            String pageFilePath = staticFilePath(renderContext, pagePath);
            try {
                pageHtml = renderStaticPage(renderContext, href, oneshotEntity, false);
            } catch (RestClientException e) {
                e.printStackTrace();
                HtmlProgressBar.Roll(
                        "发布源页面 " + href + " 自身异常",
                        Double.valueOf(StringDeal.getPercent(i, menuSize, 2)),
                        httpServletResponse);
                throw new IllegalStateException("发布源页面 " + href + " 生成失败：" + e.getMessage(), e);
            }
            String finalPageHtml = pageHtml;
            listPageLinks(renderContext, Jsoup.parse(pageHtml, href), oneshotEntity).stream()
                    .filter(next -> scheduledPaths.add(next.publicPath()))
                    .forEach(pages::addLast);
            pageProgress.beforePage(pagePath);
            writeStaticFile(pageFilePath, finalPageHtml, operationContext, null, oneshotEntity, null);
            pageProgress.afterPage(pagePath);
            pageCount++;
        }
        for (String current : generatedPages.paths) {
            previous.remove(Path.of(staticFilePath(renderContext, current)).toAbsolutePath().normalize());
        }
        if (!previous.isEmpty()) {
            runStaticWrite(operationContext, () -> {
                requireCurrentStaticList(oneshotEntity);
                for (var entry : previous.entrySet()) {
                    try {
                        Path old = entry.getKey();
                        if (!StaticPaginationFiles.isPublishedFile(root, old)) continue;
                        var saved = entry.getValue();
                        var current = Files.readAttributes(old, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!Objects.equals(saved.fileKey(), current.fileKey())
                                || !saved.lastModifiedTime().equals(current.lastModifiedTime())
                                || saved.size() != current.size()) continue;
                        String proof = null;
                        if (workbenchEnabled) {
                            Long site = oneshotEntity.getSiteProjectId();
                            String code = siteStoragePathResolver.requireSiteCode(site);
                            proof = workbenchContext.staticArtifactProof(site, code, old, Files.readString(old));
                            if (proof != null && !proof.equals(StaticRenderProof.of(site,
                                    oneshotEntity.getId(), false, oneshotEntity.getListPageTemplate()))) continue;
                        }
                        if (proof == null && !legacyPages.contains(old)) continue;
                        Files.deleteIfExists(old);
                    } catch (IOException ex) {
                        throw new IllegalStateException("无法清理栏目旧静态分页", ex);
                    }
                }
            });
        }
        return pageCount;
    }

    @Override
    public ResultVO builderHtmlTask(
            HttpServletRequest httpServletRequest, BuilderHtmlVo builderHtmlVo) {
        String taskId =
                WebConst.BUILDER_HTML_TASK_PREFIX + RandomStringUtils.randomAlphanumeric(16);
        RedisUtils.set(taskId, builderHtmlVo.getMenuIds(), 10L, TimeUnit.SECONDS);
        return ResultVO.success(taskId);
    }

    /**
     * 发布内容页面
     *
     * @param httpServletResponse
     * @param oneshotEntity
     * @param localHostUrl
     * @param saveRoot
     * @param htmlCount
     * @param menuCount
     * @param menuPercent
     * @param i
     * @return
     */
    private Integer builderContentHtml(
            HttpServletResponse httpServletResponse,
            OneshotEntity oneshotEntity,
            I18nRenderContext renderContext,
            Integer htmlCount,
            Integer menuCount,
            Double menuPercent,
            int i,
            OperationExecutionContext operationContext,
            OneShotService.PageBuildProgress pageProgress,
            GeneratedPagePaths generatedPages) {
        int contentSizeByMenu;
        Boolean roll;
        ContentEntity contentEntity;
        String htmlResult;
        List<ContentEntity> contentListByClassId;
        String detailUrl;
        double percent = 0.0d;

        Document document;
        Elements elementsPage;
        if (StringUtils.isBlank(oneshotEntity.getDetailPageUrl())) {
            return htmlCount;
        }

        Boolean hasHashSmallKey =
                RedisUtils.hasHashSmallKey(
                        WebConst.REDIS_FULL_PUBLISH_KEY, String.valueOf(oneshotEntity.getId()));
        int batchSize = 500;
        for (int page = 1; ; page++) {
            contentListByClassId =
                    contentQueries.getArticleByClassId(oneshotEntity.getId(), batchSize, 0, page);
            if (CollectionUtils.isEmpty(contentListByClassId)) break;
            if (StringUtils.isBlank(oneshotEntity.getDetailPageTemplate()))
                throw new IllegalArgumentException(
                        "待发布详情缺少正式模板：" + oneshotEntity.getClassName() + "（" + oneshotEntity.getId() + "）");

            contentSizeByMenu = contentListByClassId.size();
            for (int j = 0; j < contentSizeByMenu; j++) {
                renewAndAssertOperation(operationContext);
                contentEntity = contentListByClassId.get(j);
                percent =
                        Double.valueOf(StringDeal.getPercent(i, menuCount, 2))
                                + Double.valueOf(StringDeal.getPercent(j, contentSizeByMenu, 2))
                                        * menuPercent
                                        / 100;
                percent = StringDeal.formatDouble(percent, 2);
                log.info(
                        "正在生成内容页 - ["
                                + oneshotEntity.getClassName()
                                + " - "
                                + contentEntity.getTitle()
                                + "] "
                                + j
                                + "/"
                                + contentSizeByMenu
                                + "");
                roll =
                        HtmlProgressBar.Roll(
                                "正在生成内容页 - ["
                                        + oneshotEntity.getClassName()
                                        + "] "
                                        + j
                                        + "/"
                                        + contentSizeByMenu
                                        + ",累计已生成"
                                        + htmlCount
                                        + "个页面",
                                percent,
                                httpServletResponse);
                if (!roll) {
                    return htmlCount;
                }
                detailUrl = contentLinks.getDetailUrl(contentEntity, oneshotEntity);
                if (!detailUrl.startsWith(WebConst.PROTOCOL_HTTP_PREFIX)
                        && !detailUrl.startsWith(WebConst.PROTOCOL_HTTPS_PREFIX)
                        && !detailUrl.startsWith("javascript:")) {
                    detailUrl = normalizePagePath(renderContext, detailUrl);
                    if (detailUrl == null
                            || !detailUrl.endsWith(WebConst.HTML_SUFFIX)
                            || !generatedPages.claim(detailUrl)) continue;
                    //                        long beginTime = System.currentTimeMillis();
                    String href = renderContext.dynamicUrl() + detailUrl;
                    log.info("准备请求 {} 来创建静态页 {}", href, detailUrl);
                    try {
                        htmlResult = renderStaticPage(renderContext, href, oneshotEntity, true);
                    } catch (RestClientException e) {
                        e.printStackTrace();
                        HtmlProgressBar.Roll(
                                "发布源页面 " + href + " 自身异常", percent, httpServletResponse);
                        throw new IllegalStateException(
                                "发布源页面 " + href + " 生成失败：" + e.getMessage(), e);
                    }
                    final String filePath = staticFilePath(renderContext, detailUrl);
                    final String htmlContent = htmlResult;
                    final ContentEntity contentEntity1 = contentEntity;
                    pageProgress.beforePage(detailUrl);
                    writeStaticFile(
                            filePath,
                            htmlContent,
                            operationContext,
                            () -> contentService.updatePublished(contentEntity1),
                            oneshotEntity, contentEntity1.getId());
                    pageProgress.afterPage(detailUrl);

                    htmlCount++;
                    // 分页生成
                    document = Jsoup.parse(htmlResult, href);
                    elementsPage = document.select("div.page a");
                    if (CollectionUtils.isEmpty(elementsPage)) {
                        continue;
                    }
                    for (PageLink pageLink :
                            orderedPageLinks(
                                    renderContext,
                                    elementsPage,
                                    pagePathPattern(
                                            oneshotEntity.getDetailPageUrl(),
                                            oneshotEntity,
                                            contentEntity.getId()))) {
                        href = pageLink.href();
                        String pagePath = pageLink.publicPath();
                        if (!generatedPages.claim(pagePath)) continue;
                        String pageHtml = "";
                        String pageFilePath = staticFilePath(renderContext, pagePath);
                        try {
                            pageHtml = renderStaticPage(renderContext, href, oneshotEntity, true);
                        } catch (RestClientException e) {
                            e.printStackTrace();
                            HtmlProgressBar.Roll(
                                    "发布源页面 " + href + " 自身异常", percent, httpServletResponse);
                            throw new IllegalStateException(
                                    "发布源页面 " + href + " 生成失败：" + e.getMessage(), e);
                        }
                        String finalPageHtml = pageHtml;
                        pageProgress.beforePage(pagePath);
                        writeStaticFile(pageFilePath, finalPageHtml, operationContext, null,
                                oneshotEntity, contentEntity.getId());
                        pageProgress.afterPage(pagePath);
                        htmlCount++;
                    }

                    //                        long endTime = System.currentTimeMillis();
                    //                        System.out.println("生成页面" + localHostUrl + detailUrl +
                    // "共耗时" + (endTime - beginTime) + "毫秒");
                }
            }
            if (contentSizeByMenu < batchSize) break;
        }
        if (hasHashSmallKey) {
            // 删除需要全量发布的数据ID
            RedisUtils.deleteHashSmallKey(
                    WebConst.REDIS_FULL_PUBLISH_KEY, String.valueOf(oneshotEntity.getId()));
        }
        return htmlCount;
    }

    static List<String> orderedUniquePagePaths(
            I18nRenderContext renderContext, Collection<String> hrefs) {
        TreeMap<String, String> ordered = new TreeMap<>();
        for (String href : hrefs == null ? List.<String>of() : hrefs) {
            String publicPath = normalizePagePath(renderContext, href);
            if (publicPath != null)
                ordered.merge(
                        publicPath,
                        href,
                        (left, right) -> left.compareTo(right) <= 0 ? left : right);
        }
        return List.copyOf(ordered.keySet());
    }

    static List<PageLink> listPageLinks(
            I18nRenderContext context, Document document, OneshotEntity menu) {
        return orderedPageLinks(
                context,
                document.select("div.page a[href], [data-cms-pagination=true] a[href]"),
                pagePathPattern(
                        StringUtils.defaultIfBlank(menu.getListPageUrl(), "/"),
                        menu,
                        menu.getId()));
    }

    private static Pattern pagePathPattern(
            String configuredRoute, OneshotEntity menu, Integer recordId) {
        return StaticPaginationFiles.pagePattern(paginationRoute(configuredRoute, menu, recordId));
    }

    private static String paginationRoute(String configuredRoute, OneshotEntity menu, Integer recordId) {
        return configuredRoute
                        .replace("{classId}", menu.getId().toString())
                        .replace("{parentId}", Objects.toString(menu.getParentId(), ""))
                        .replace("{id}", recordId.toString())
                        .replace("${className}", Objects.toString(menu.getClassName(), ""));
    }

    private static List<PageLink> orderedPageLinks(
            I18nRenderContext renderContext, Elements elements, Pattern pathPattern) {
        TreeMap<String, String> ordered = new TreeMap<>();
        for (Element element : elements) {
            if (element.attr("href").startsWith("#")) continue;
            String href = element.attr("abs:href");
            URI candidate;
            try {
                candidate = URI.create(href);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            if (candidate.getRawPath() == null
                    || !pathPattern.matcher(candidate.getRawPath()).matches()) continue;
            // 无筛选值时，受控分页只带启用筛选协议的标记；它不改变该路径对应的数据。
            // 仅在已匹配本站栏目分页路径时忽略此单一标记，其余查询分页仍须拒绝。
            if ("__cms_filter=1".equals(candidate.getRawQuery()))
                href = href.substring(0, href.indexOf('?'));
            String publicPath = normalizePagePath(renderContext, href);
            if (publicPath != null)
                ordered.put(publicPath, renderContext.dynamicUrl() + publicPath);
        }
        return ordered.entrySet().stream()
                .map(entry -> new PageLink(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String normalizePagePath(I18nRenderContext renderContext, String href) {
        if (renderContext == null || StringUtils.isBlank(href) || href.startsWith("javascript:"))
            return null;
        URI candidate;
        try {
            candidate = URI.create(href.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        URI origin = URI.create(renderContext.dynamicUrl());
        if (candidate.getRawAuthority() != null
                && (!Objects.equals(candidate.getScheme(), origin.getScheme())
                        || !Objects.equals(candidate.getRawAuthority(), origin.getRawAuthority())))
            return null;
        if (candidate.isAbsolute() && candidate.getRawAuthority() == null) return null;
        String rawPath = candidate.getRawPath();
        if (StringUtils.isBlank(rawPath)) return null;
        if (candidate.getRawQuery() != null)
            throw new IllegalArgumentException("静态发布路径不能含查询参数，请配置固定路径分页：" + rawPath);
        String path = candidate.getPath();
        if (path.matches("(?s).*[<>:\"|?*\\\\\\p{Cntrl}].*")
                || Arrays.stream(path.split("/"))
                        .anyMatch(
                                part ->
                                        part.endsWith(".")
                                                || part.endsWith(" ")
                                                || part.matches(
                                                        "(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                || rawPath.matches("(?i).*(%2f|%5c).*"))
            throw new IllegalArgumentException("静态发布路径包含不支持的路径字符：" + rawPath);
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    static String staticFilePath(I18nRenderContext context, String path) {
        String decoded = URI.create(path).getPath();
        return context.filePath(decoded.endsWith("/") ? decoded + "index.html" : decoded);
    }

    static boolean publishDetailsOnly(OneshotEntity menu, boolean directPublish) {
        String detailUrl = menu.getDetailPageUrl();
        return directPublish
                && Integer.valueOf(1).equals(menu.getPageType())
                && !Objects.equals(menu.getId(), menu.getSiteRootId())
                && !Boolean.TRUE.equals(menu.getIsSubMenu())
                && StringUtils.isNotBlank(menu.getListPageUrl())
                && StringUtils.isBlank(menu.getListPageTemplate())
                && StringUtils.startsWith(detailUrl, "/")
                && !StringUtils.startsWith(detailUrl, "//")
                && StringUtils.endsWith(detailUrl, WebConst.HTML_SUFFIX)
                && StringUtils.isNotBlank(menu.getDetailPageTemplate());
    }

    void validatePublishMenu(OneshotEntity menu, boolean directPublish) {
        String label = menu.getClassName() + "（" + menu.getId() + "）";
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("待发布栏目未开放前台：" + label);
        boolean hasListRoute =
                StringUtils.isNotBlank(menu.getListPageUrl())
                        || Objects.equals(menu.getId(), menu.getSiteRootId());
        boolean hasDetailRoute = StringUtils.isNotBlank(menu.getDetailPageUrl());
        if (!hasListRoute && !hasDetailRoute)
            throw new IllegalArgumentException("待发布栏目没有正式路由：" + label);
        if (hasListRoute && StringUtils.isBlank(menu.getListPageTemplate())
                && !publishDetailsOnly(menu, directPublish))
            throw new IllegalArgumentException("待发布栏目缺少正式列表模板，请在栏目设置绑定模板：" + label);
        if (hasDetailRoute && StringUtils.isBlank(menu.getDetailPageTemplate())
                && (!hasListRoute || CollectionUtils.isNotEmpty(
                        contentQueries.getArticleByClassId(menu.getId(), 1, 0, 1))))
            throw new IllegalArgumentException("待发布栏目缺少正式详情模板，请在栏目设置绑定模板：" + label);
        // 受控首页的根栏目仅负责路由；正式渲染会核对数据栏目的发布状态和模板签名。
        if (Integer.valueOf(0).equals(menu.getPageType())
                && !Integer.valueOf(3).equals(menu.getStatus()) && !managedRoot(menu))
            throw new IllegalArgumentException("请先通过 CMS 发布单页：" + label);
    }

    private Optional<List<String>> nativeHomeAliases(OneshotEntity root) {
        if (!workbenchEnabled || StringUtils.startsWith(root.getListPageTemplate(), "cms-applied/"))
            return Optional.empty();
        var checker = nativeHomePublication.getIfAvailable();
        Long siteId = root.getSiteProjectId();
        return checker == null ? Optional.empty()
                : checker.nativeAliases(siteId, siteStoragePathResolver.requireSiteCode(siteId), root);
    }

    String renderStaticPage(
            I18nRenderContext context, String href, OneshotEntity menu, boolean detail) {
        String path = normalizePagePath(context, href);
        if (path == null) throw new RestClientException("静态发布源不属于当前站点");
        URI request = URI.create(context.toBuildHref(context.dynamicUrl() + path));
        var result = STATIC_RENDER_CLIENT.getForEntity(request, String.class);
        String template = detail ? menu.getDetailPageTemplate() : menu.getListPageTemplate();
        String proof =
                StaticRenderProof.of(menu.getSiteProjectId(), menu.getId(), detail, template);
        if (!result.getStatusCode().is2xxSuccessful()
                || StringUtils.isBlank(result.getBody())
                || !proof.equals(result.getHeaders().getFirst(StaticRenderProof.HEADER)))
            throw new RestClientException("静态发布源没有返回已核对的栏目模板，请检查正式模板与运行路由：" + path);
        return result.getBody();
    }

    record PageLink(String publicPath, String href) {}

    private static final class GeneratedPagePaths {
        private final Set<String> paths = new HashSet<>();

        private boolean claim(String publicPath) {
            return paths.add(publicPath);
        }
    }

    private void writeStaticFile(
            String filePath,
            String html,
            OperationExecutionContext context,
            Runnable databaseWrite,
            OneshotEntity menu, Integer detailId) {
        boolean detail = detailId != null;
        Runnable write =
                () -> {
                    if (detail) requireCurrentStaticDetail(menu, detailId);
                    else requireCurrentStaticList(menu);
                    Path target = Path.of(filePath).toAbsolutePath().normalize();
                    Path temporary =
                            target.resolveSibling(
                                    target.getFileName()
                                            + ".operation-"
                                            + UUID.randomUUID()
                                            + ".tmp");
                    try {
                        Files.createDirectories(target.getParent());
                        Files.writeString(temporary, html, StandardCharsets.UTF_8);
                        try {
                            Files.move(
                                    temporary,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException ex) {
                            throw new IllegalStateException("静态发布目录不支持原子 rename", ex);
                        }
                        if (databaseWrite != null) {
                            databaseWrite.run();
                        }
                        if (workbenchEnabled) {
                            String code = siteStoragePathResolver.requireSiteCode(menu.getSiteProjectId());
                            String template = detail ? menu.getDetailPageTemplate() : menu.getListPageTemplate();
                            workbenchContext.recordStaticArtifact(menu.getSiteProjectId(), code, target,
                                    StaticRenderProof.of(menu.getSiteProjectId(), menu.getId(), detail, template), html);
                        }
                    } catch (IOException ex) {
                        try {
                            Files.deleteIfExists(temporary);
                        } catch (IOException cleanup) {
                            ex.addSuppressed(cleanup);
                        }
                        throw new IllegalStateException("静态页写入失败", ex);
                    }
                };
        runStaticWrite(context, write);
    }

    private void runStaticWrite(OperationExecutionContext context, Runnable write) {
        if (context == null) staticWriteTransaction.executeWithoutResult(status -> write.run());
        else context.withFencedWrite(write);
    }

    private void requireCurrentStaticList(OneshotEntity menu) {
        var current = oneShotDao.lockFieldConfiguration(menu.getSiteProjectId(), menu.getId().longValue());
        if (current == null || !Boolean.TRUE.equals(current.getHasFrontChannel())
                || !Objects.equals(current.getListPageUrl(), menu.getListPageUrl())
                || !Objects.equals(current.getListPageTemplate(), menu.getListPageTemplate()))
            throw new IllegalStateException("栏目列表路由已变化，请重新生成");
        if (Integer.valueOf(0).equals(current.getPageType())
                && !Integer.valueOf(3).equals(current.getStatus())
                && !managedRoot(current))
            throw new IllegalStateException("单页已取消发布，静态页未写入");
    }

    private boolean managedRoot(OneshotEntity menu) {
        return Objects.equals(menu.getId(), menu.getSiteRootId())
                && (StringUtils.startsWith(menu.getListPageTemplate(), "cms-applied/")
                        || nativeHomeAliases(menu).isPresent());
    }

    @Override
    @Transactional
    public void withdrawSinglePage(Long siteId, Integer menuId) {
        var menu = oneShotDao.lockFieldConfiguration(siteId, menuId.longValue());
        if (menu == null) throw new IllegalStateException("单页不属于当前站点");
        if (managedRoot(menu))
            throw new IllegalStateException("请通过工作台撤回当前首页应用");

        oneShotDao.updateStatus(menuId, 0);
        String route = menu.getListPageUrl();
        boolean siteRoot = Objects.equals(menuId, menu.getSiteRootId());
        if (StringUtils.isBlank(route) && !siteRoot) return;
        route = paginationRoute(StringUtils.defaultIfBlank(route, "/"), menu, menuId);
        String code = siteStoragePathResolver.requireSiteCode(siteId);
        Path root = Path.of(siteStoragePathResolver.siteRootPath(
                cmsBuilderHtmlConfigProperties.getSaveRoot(), code)).toAbsolutePath().normalize();
        var roots = new LinkedHashSet<Path>();
        roots.add(root);
        siteLocaleService.list(siteId).stream()
                .map(SiteLocaleService.LocaleView::languageCode)
                .filter(language -> language != null && language.matches("[A-Za-z0-9_-]+"))
                .map(root::resolve)
                .forEach(roots::add);
        String expected = StaticRenderProof.of(siteId, menuId, false, menu.getListPageTemplate());
        try {
            for (Path localeRoot : roots) {
                Path base = StaticPaginationFiles.outputPath(localeRoot, route.replace("{pageIndex}", "1"));
                if (base == null) continue;
                var files = new LinkedHashSet<>(StaticPaginationFiles.existingFiles(localeRoot, route));
                var linked = StaticPaginationFiles.linkedFiles(localeRoot, base, List.copyOf(files),
                        "div.page a[href], [data-cms-pagination=true] a[href]");
                files.add(base);
                if (siteRoot) files.add(localeRoot.resolve("index.html"));
                for (Path file : files) {
                    if (!StaticPaginationFiles.isPublishedFile(root, file)) continue;
                    String proof = workbenchEnabled
                            ? workbenchContext.staticArtifactProof(siteId, code, file, Files.readString(file))
                            : null;
                    if (expected.equals(proof) || proof == null && linked.contains(file))
                        stageSinglePageWithdrawal(root, menu, file);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法撤下已发布的单页静态文件", failure);
        }
    }

    private void stageSinglePageWithdrawal(Path root, OneshotEntity menu, Path page) throws IOException {
        Path staged = root.toRealPath().resolveSibling(".withdraw-" + UUID.randomUUID());
        Files.move(page, staged, StandardCopyOption.ATOMIC_MOVE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) {
                        Files.deleteIfExists(staged);
                    } else if (status == STATUS_ROLLED_BACK) {
                        staticWithdrawalRecovery.executeWithoutResult(
                                ignored -> restoreSinglePage(menu, page, staged));
                    } else {
                        log.error("单页撤回事务结果未知，保留恢复文件 {}", staged);
                    }
                } catch (IOException | RuntimeException failure) {
                    log.error("无法完成单页撤回文件清理，恢复文件位于 {}", staged, failure);
                }
            }
        });
    }

    private void restoreSinglePage(OneshotEntity previous, Path page, Path staged) {
        var current = oneShotDao.lockFieldConfiguration(
                previous.getSiteProjectId(), previous.getId().longValue());
        try {
            if (current != null && Integer.valueOf(3).equals(current.getStatus())
                    && Objects.equals(current.getListPageUrl(), previous.getListPageUrl())
                    && Objects.equals(current.getListPageTemplate(), previous.getListPageTemplate())) {
                try {
                    Files.move(staged, page);
                    return;
                } catch (FileAlreadyExistsException regenerated) {
                    // 保留事务完成后重新生成的页面。
                }
            }
            Files.deleteIfExists(staged);
        } catch (IOException failure) {
            throw new IllegalStateException("无法恢复撤回前的单页", failure);
        }
    }

    private void requireCurrentStaticDetail(OneshotEntity menu, int detailId) {
        var site = menu.getSiteProjectId();
        var lockedMenu = oneShotDao.lockFieldConfiguration(site, menu.getId().longValue());
        if (lockedMenu == null || !Boolean.TRUE.equals(lockedMenu.getHasFrontChannel())
                || !Objects.equals(lockedMenu.getDetailPageUrl(), menu.getDetailPageUrl())
                || !Objects.equals(lockedMenu.getDetailPageTemplate(), menu.getDetailPageTemplate()))
            throw new IllegalStateException("栏目详情路由已变化，请重新生成");
        var record = contentRecords.lockActiveArticleInSiteMenu(site, menu.getId(), detailId);
        var now = LocalDateTime.now();
        if (record == null || !Objects.equals(record.getStatus(), 1)
                || record.getEntryIntoForceTime() == null || record.getEntryIntoForceTime().isAfter(now)
                || record.getExpirationTime() == null || record.getExpirationTime().isBefore(now))
            throw new IllegalStateException("记录已撤回或失效，静态详情未写入");
    }

    @Override
    public OperationTask.Type type() {
        return OperationTask.Type.STATIC_PUBLISH;
    }

    @Override
    public String normalize(String commandJson, long siteId, Long operatorId, String operatorName) {
        StaticPublishCommand supplied =
                OperationTaskExecutionAdapter.readCommand(commandJson, StaticPublishCommand.class);
        if (supplied.menuIds() == null || supplied.menuIds().isEmpty()) {
            throw new IllegalArgumentException("静态发布 command 缺少栏目清单");
        }
        boolean wrongSite =
                supplied.menuIds().stream()
                        .anyMatch(
                                menuId -> {
                                    OneshotEntity menu = oneShotDao.getModelById(menuId);
                                    return menu == null
                                            || menu.getSiteProjectId() == null
                                            || siteId != menu.getSiteProjectId();
                                });
        if (wrongSite) {
            throw new IllegalArgumentException("静态发布栏目不存在或不属于当前站点");
        }
        return OperationTaskExecutionAdapter.command(
                new StaticPublishCommand(
                        supplied.taskId(), supplied.language(), supplied.menuIds()));
    }

    @Override
    public void validate(String commandJson) {
        StaticPublishCommand command =
                OperationTaskExecutionAdapter.readCommand(commandJson, StaticPublishCommand.class);
        if (command.taskId() == null
                || command.taskId().isBlank()
                || command.menuIds() == null
                || command.menuIds().isEmpty()) {
            throw new IllegalArgumentException("静态发布 command 缺少 taskId 或栏目清单");
        }
    }

    @Override
    public OperationTaskDispatcher.DispatchResult execute(
            OperationTask.TaskSnapshot task, OperationExecutionContext context) {
        StaticPublishCommand command =
                OperationTaskExecutionAdapter.readCommand(
                        task.commandJson(), StaticPublishCommand.class);
        if (command.menuIds() == null || command.menuIds().isEmpty()) {
            throw new IllegalArgumentException("静态发布 command 缺少栏目清单");
        }
        boolean wrongSite =
                command.menuIds().stream()
                        .anyMatch(
                                menuId -> {
                                    OneshotEntity menu = oneShotDao.getModelById(menuId);
                                    return menu == null
                                            || menu.getSiteProjectId() == null
                                            || task.siteId() != menu.getSiteProjectId();
                                });
        if (wrongSite) {
            throw new IllegalArgumentException("静态发布 command 与任务站点不一致");
        }
        builderHtml(
                null,
                silentResponse(),
                command.taskId(),
                command.language(),
                context,
                command.menuIds(),
                null,
                OneShotService.PageBuildProgress.none());
        StaticPublishResult result = new StaticPublishResult(command.taskId(), command.language());
        return new OperationTaskDispatcher.DispatchResult(
                "静态页面发布完成", OperationTaskExecutionAdapter.command(result));
    }

    private static HttpServletResponse silentResponse() {
        PrintWriter writer = new PrintWriter(new StringWriter());
        return (HttpServletResponse)
                Proxy.newProxyInstance(
                        HttpServletResponse.class.getClassLoader(),
                        new Class<?>[] {HttpServletResponse.class},
                        (proxy, method, args) -> {
                            if ("getWriter".equals(method.getName())) return writer;
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            return null;
                        });
    }

    private record StaticPublishCommand(String taskId, String language, List<Integer> menuIds) {}

    private record StaticPublishResult(String taskId, String language) {}

    /**
     * 递归遍历判断子栏目中是否有存在前台页面的栏目,如果有则父栏目不禁用,如果没有则父栏目禁用
     *
     * @param oneshotEntities
     * @param nodeVo
     * @param oneshotEntity
     * @return
     */
    private Boolean getSetChkDisabled(
            List<OneshotEntity> oneshotEntities, NodeVo nodeVo, OneshotEntity oneshotEntity) {
        List<OneshotEntity> oneshotEntityChildList =
                oneshotEntities.stream()
                        .filter(item -> item.getParentId() == oneshotEntity.getId())
                        .collect(Collectors.toList());

        for (OneshotEntity entity : oneshotEntityChildList) {
            if (StringUtils.isNotBlank(entity.getListPageUrl())
                    || StringUtils.isNotBlank(entity.getDetailPageUrl())) {
                nodeVo.setChkDisabled(false);
                return true;
            } else {
                return getSetChkDisabled(oneshotEntities, nodeVo, entity);
            }
        }
        return false;
    }

    private String getModelListDisplayFieldTitle(
            String modelList,
            boolean hasDetail,
            boolean hasSeo,
            boolean hasSort,
            boolean hasStatusList,
            List<OneshotEntity> childMenu,
            int count,
            boolean hasEdit) {
        StringBuilder stringBuilderTitleHtml = new StringBuilder();
        // 显示的列的个数
        int fieldNum = 1;
        // 列表标题ID
        stringBuilderTitleHtml.append(
                "<th style=\"width: 50px;text-align:center;vertical-align: middle;\">\n"
                        + "                                    <input type=\"checkbox\"\n"
                        + "                                           name=\"checkAll\"\n"
                        + "                                           class=\"checkAll\">\n"
                        + "                                </th>");
        stringBuilderTitleHtml.append(
                "<th style=\"width: 80px;text-align:center;vertical-align: middle;\">ID</th>");
        // 动态字段
        List<ContentFieldDefinition> fieldSettingEntityList = ContentModelCodec.read(modelList);
        fieldSettingEntityList =
                fieldSettingEntityList.stream()
                        .sorted((a, b) -> Integer.compare(b.getSort(), a.getSort()))
                        .collect(Collectors.toList());

        for (ContentFieldDefinition fieldSettingEntity : fieldSettingEntityList) {
            stringBuilderTitleHtml.append(FieldHtmlUtils.getListTitleHtml(fieldSettingEntity));
            fieldNum++;
        }

        // 关联子栏目
        for (OneshotEntity menu : childMenu) {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 80px;text-align:center;vertical-align: middle;\">"
                            + menu.getClassName()
                            + "</th>\n");
            fieldNum++;
        }

        // 固定字段
        if (hasDetail) {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 80px;text-align:center;vertical-align:"
                            + " middle;\">浏览量</th>\n");
            fieldNum++;
        }
        if (hasSort) {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 80px;text-align:center;vertical-align:"
                            + " middle;\">排序</th>\n");
            fieldNum++;
        }
        if (hasStatusList) {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 80px;text-align:center;vertical-align:"
                            + " middle;\">状态</th>\n");
            fieldNum++;
        }
        if (hasSeo) {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 220px;text-align:center;vertical-align: middle;\">操作</th>");
            fieldNum++;
        } else {
            stringBuilderTitleHtml.append(
                    "<th style=\"width: 200px;text-align:center;vertical-align: middle;\">操作</th>");
            fieldNum++;
        }
        if (count == 0) {
            stringBuilderTitleHtml.append(
                    "<tr><td colspan=\""
                            + fieldNum
                            + "\" style=\"text-align:center;vertical-align:"
                            + " middle;\">暂无数据</td></tr>");
        }
        return stringBuilderTitleHtml.toString();
    }

    private SearchHtmlVo getSearchCondition(
            ContentCond contentCond, HttpServletRequest httpServletRequest) {
        SearchHtmlVo searchHtmlVo = new SearchHtmlVo();
        // map用于保存搜索关键词的值
        HashMap<String, Object> map = new HashMap<>();
        // 用于带条件的分页
        StringBuilder pageUrlCondition = new StringBuilder();
        map.put("classId", contentCond.getClassid());
        if (httpServletRequest.getParameter("title") != null) {
            contentCond.setTitle(httpServletRequest.getParameter("title"));
            map.put("title", httpServletRequest.getParameter("title"));
            pageUrlCondition.append("&title=" + httpServletRequest.getParameter("title"));
        }
        if (httpServletRequest.getParameter("tags") != null) {
            contentCond.setTags(httpServletRequest.getParameter("tags"));
            map.put("tags", httpServletRequest.getParameter("tags"));
            pageUrlCondition.append("&tags=" + httpServletRequest.getParameter("tags"));
        }
        // 下拉筛选 条件
        if (httpServletRequest.getParameter("status") != null) {
            contentCond.setStatus(
                    Integer.valueOf(httpServletRequest.getParameter("status")).intValue());
            pageUrlCondition.append(
                    "&status="
                            + Integer.valueOf(httpServletRequest.getParameter("status"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("indexTop") != null) {
            contentCond.setIndexTop(
                    Integer.valueOf(httpServletRequest.getParameter("indexTop")).intValue());
            pageUrlCondition.append(
                    "&indexTop="
                            + Integer.valueOf(httpServletRequest.getParameter("indexTop"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("indexHot") != null) {
            contentCond.setIndexHot(
                    Integer.valueOf(httpServletRequest.getParameter("indexHot")).intValue());
            pageUrlCondition.append(
                    "&indexHot="
                            + Integer.valueOf(httpServletRequest.getParameter("indexHot"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("indexElite") != null) {
            contentCond.setIndexElite(
                    Integer.valueOf(httpServletRequest.getParameter("indexElite")).intValue());
            pageUrlCondition.append(
                    "&indexElite="
                            + Integer.valueOf(httpServletRequest.getParameter("indexElite"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("channelTop") != null) {
            contentCond.setChannelTop(
                    Integer.valueOf(httpServletRequest.getParameter("channelTop")).intValue());
            pageUrlCondition.append(
                    "&channelTop="
                            + Integer.valueOf(httpServletRequest.getParameter("channelTop"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("channelHot") != null) {
            contentCond.setChannelHot(
                    Integer.valueOf(httpServletRequest.getParameter("channelHot")).intValue());
            pageUrlCondition.append(
                    "&channelHot="
                            + Integer.valueOf(httpServletRequest.getParameter("channelHot"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("channelElite") != null) {
            contentCond.setChannelElite(
                    Integer.valueOf(httpServletRequest.getParameter("channelElite")).intValue());
            pageUrlCondition.append(
                    "&channelElite="
                            + Integer.valueOf(httpServletRequest.getParameter("channelElite"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("menuTop") != null) {
            contentCond.setMenuTop(
                    Integer.valueOf(httpServletRequest.getParameter("menuTop")).intValue());
            pageUrlCondition.append(
                    "&menuTop="
                            + Integer.valueOf(httpServletRequest.getParameter("menuTop"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("menuHot") != null) {
            contentCond.setMenuHot(
                    Integer.valueOf(httpServletRequest.getParameter("menuHot")).intValue());
            pageUrlCondition.append(
                    "&menuHot="
                            + Integer.valueOf(httpServletRequest.getParameter("menuHot"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("menuElite") != null) {
            contentCond.setMenuElite(
                    Integer.valueOf(httpServletRequest.getParameter("menuElite")).intValue());
            pageUrlCondition.append(
                    "&menuElite="
                            + Integer.valueOf(httpServletRequest.getParameter("menuElite"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("navElite") != null) {
            contentCond.setNavElite(
                    Integer.valueOf(httpServletRequest.getParameter("navElite")).intValue());
            pageUrlCondition.append(
                    "&navElite="
                            + Integer.valueOf(httpServletRequest.getParameter("navElite"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("rightElite") != null) {
            contentCond.setRightElite(
                    Integer.valueOf(httpServletRequest.getParameter("rightElite")).intValue());
            pageUrlCondition.append(
                    "&rightElite="
                            + Integer.valueOf(httpServletRequest.getParameter("rightElite"))
                                    .intValue());
        }
        if (httpServletRequest.getParameter("leftElite") != null) {
            contentCond.setLeftElite(
                    Integer.valueOf(httpServletRequest.getParameter("leftElite")).intValue());
            pageUrlCondition.append(
                    "&leftElite="
                            + Integer.valueOf(httpServletRequest.getParameter("leftElite"))
                                    .intValue());
        }
        // 单行
        if (httpServletRequest.getParameter("singleLine1") != null) {
            contentCond.setSingleLine1(httpServletRequest.getParameter("singleLine1"));
            map.put("singleLine1", httpServletRequest.getParameter("singleLine1"));
            pageUrlCondition.append(
                    "&singleLine1=" + httpServletRequest.getParameter("singleLine1"));
        }
        if (httpServletRequest.getParameter("singleLine2") != null) {
            contentCond.setSingleLine2(httpServletRequest.getParameter("singleLine2"));
            map.put("singleLine2", httpServletRequest.getParameter("singleLine2"));
            pageUrlCondition.append(
                    "&singleLine2=" + httpServletRequest.getParameter("singleLine2"));
        }
        if (httpServletRequest.getParameter("singleLine3") != null) {
            contentCond.setSingleLine3(httpServletRequest.getParameter("singleLine3"));
            map.put("singleLine3", httpServletRequest.getParameter("singleLine3"));
            pageUrlCondition.append(
                    "&singleLine3=" + httpServletRequest.getParameter("singleLine3"));
        }
        if (httpServletRequest.getParameter("singleLine4") != null) {
            contentCond.setSingleLine4(httpServletRequest.getParameter("singleLine4"));
            map.put("singleLine4", httpServletRequest.getParameter("singleLine4"));
            pageUrlCondition.append(
                    "&singleLine4=" + httpServletRequest.getParameter("singleLine4"));
        }
        if (httpServletRequest.getParameter("singleLine5") != null) {
            contentCond.setSingleLine5(httpServletRequest.getParameter("singleLine5"));
            map.put("singleLine5", httpServletRequest.getParameter("singleLine5"));
            pageUrlCondition.append(
                    "&singleLine5=" + httpServletRequest.getParameter("singleLine5"));
        }
        if (httpServletRequest.getParameter("singleLine6") != null) {
            contentCond.setSingleLine6(httpServletRequest.getParameter("singleLine6"));
            map.put("singleLine6", httpServletRequest.getParameter("singleLine6"));
            pageUrlCondition.append(
                    "&singleLine6=" + httpServletRequest.getParameter("singleLine6"));
        }
        if (httpServletRequest.getParameter("singleLine7") != null) {
            contentCond.setSingleLine7(httpServletRequest.getParameter("singleLine7"));
            map.put("singleLine7", httpServletRequest.getParameter("singleLine7"));
            pageUrlCondition.append(
                    "&singleLine7=" + httpServletRequest.getParameter("singleLine7"));
        }
        if (httpServletRequest.getParameter("singleLine8") != null) {
            contentCond.setSingleLine8(httpServletRequest.getParameter("singleLine8"));
            map.put("singleLine8", httpServletRequest.getParameter("singleLine8"));
            pageUrlCondition.append(
                    "&singleLine8=" + httpServletRequest.getParameter("singleLine8"));
        }
        if (httpServletRequest.getParameter("singleLine9") != null) {
            contentCond.setSingleLine9(httpServletRequest.getParameter("singleLine9"));
            map.put("singleLine9", httpServletRequest.getParameter("singleLine9"));
            pageUrlCondition.append(
                    "&singleLine9=" + httpServletRequest.getParameter("singleLine9"));
        }
        if (httpServletRequest.getParameter("singleLine10") != null) {
            contentCond.setSingleLine10(httpServletRequest.getParameter("singleLine10"));
            map.put("singleLine10", httpServletRequest.getParameter("singleLine10"));
            pageUrlCondition.append(
                    "&singleLine10=" + httpServletRequest.getParameter("singleLine10"));
        }
        if (httpServletRequest.getParameter("singleLine11") != null) {
            contentCond.setSingleLine11(httpServletRequest.getParameter("singleLine11"));
            map.put("singleLine11", httpServletRequest.getParameter("singleLine11"));
            pageUrlCondition.append(
                    "&singleLine11=" + httpServletRequest.getParameter("singleLine11"));
        }
        if (httpServletRequest.getParameter("singleLine12") != null) {
            contentCond.setSingleLine12(httpServletRequest.getParameter("singleLine12"));
            map.put("singleLine12", httpServletRequest.getParameter("singleLine12"));
            pageUrlCondition.append(
                    "&singleLine12=" + httpServletRequest.getParameter("singleLine12"));
        }
        if (httpServletRequest.getParameter("singleLine13") != null) {
            contentCond.setSingleLine13(httpServletRequest.getParameter("singleLine13"));
            map.put("singleLine13", httpServletRequest.getParameter("singleLine13"));
            pageUrlCondition.append(
                    "&singleLine13=" + httpServletRequest.getParameter("singleLine13"));
        }
        if (httpServletRequest.getParameter("singleLine14") != null) {
            contentCond.setSingleLine14(httpServletRequest.getParameter("singleLine14"));
            map.put("singleLine14", httpServletRequest.getParameter("singleLine14"));
            pageUrlCondition.append(
                    "&singleLine14=" + httpServletRequest.getParameter("singleLine14"));
        }
        if (httpServletRequest.getParameter("singleLine15") != null) {
            contentCond.setSingleLine15(httpServletRequest.getParameter("singleLine15"));
            map.put("singleLine15", httpServletRequest.getParameter("singleLine15"));
            pageUrlCondition.append(
                    "&singleLine15=" + httpServletRequest.getParameter("singleLine15"));
        }
        if (httpServletRequest.getParameter("singleLine16") != null) {
            contentCond.setSingleLine16(httpServletRequest.getParameter("singleLine16"));
            map.put("singleLine16", httpServletRequest.getParameter("singleLine16"));
            pageUrlCondition.append(
                    "&singleLine16=" + httpServletRequest.getParameter("singleLine16"));
        }
        if (httpServletRequest.getParameter("singleLine17") != null) {
            contentCond.setSingleLine17(httpServletRequest.getParameter("singleLine17"));
            map.put("singleLine17", httpServletRequest.getParameter("singleLine17"));
            pageUrlCondition.append(
                    "&singleLine17=" + httpServletRequest.getParameter("singleLine17"));
        }
        if (httpServletRequest.getParameter("singleLine18") != null) {
            contentCond.setSingleLine18(httpServletRequest.getParameter("singleLine18"));
            map.put("singleLine18", httpServletRequest.getParameter("singleLine18"));
            pageUrlCondition.append(
                    "&singleLine18=" + httpServletRequest.getParameter("singleLine18"));
        }
        if (httpServletRequest.getParameter("singleLine19") != null) {
            contentCond.setSingleLine19(httpServletRequest.getParameter("singleLine19"));
            map.put("singleLine19", httpServletRequest.getParameter("singleLine19"));
            pageUrlCondition.append(
                    "&singleLine19=" + httpServletRequest.getParameter("singleLine19"));
        }
        if (httpServletRequest.getParameter("singleLine20") != null) {
            contentCond.setSingleLine20(httpServletRequest.getParameter("singleLine20"));
            map.put("singleLine20", httpServletRequest.getParameter("singleLine20"));
            pageUrlCondition.append(
                    "&singleLine20=" + httpServletRequest.getParameter("singleLine20"));
        }
        // 多行
        if (httpServletRequest.getParameter("note1") != null) {
            contentCond.setNote1(httpServletRequest.getParameter("note1"));
            map.put("note1", httpServletRequest.getParameter("note1"));
            pageUrlCondition.append("&note1=" + httpServletRequest.getParameter("note1"));
        }
        if (httpServletRequest.getParameter("note2") != null) {
            contentCond.setNote2(httpServletRequest.getParameter("note2"));
            map.put("note2", httpServletRequest.getParameter("note2"));
            pageUrlCondition.append("&note2=" + httpServletRequest.getParameter("note2"));
        }
        if (httpServletRequest.getParameter("note3") != null) {
            contentCond.setNote3(httpServletRequest.getParameter("note3"));
            map.put("note3", httpServletRequest.getParameter("note3"));
            pageUrlCondition.append("&note3=" + httpServletRequest.getParameter("note3"));
        }
        if (httpServletRequest.getParameter("note4") != null) {
            contentCond.setNote4(httpServletRequest.getParameter("note4"));
            map.put("note4", httpServletRequest.getParameter("note4"));
            pageUrlCondition.append("&note4=" + httpServletRequest.getParameter("note4"));
        }
        if (httpServletRequest.getParameter("note5") != null) {
            contentCond.setNote5(httpServletRequest.getParameter("note5"));
            map.put("note5", httpServletRequest.getParameter("note5"));
            pageUrlCondition.append("&note5=" + httpServletRequest.getParameter("note5"));
        }
        if (httpServletRequest.getParameter("note6") != null) {
            contentCond.setNote6(httpServletRequest.getParameter("note6"));
            map.put("note6", httpServletRequest.getParameter("note6"));
            pageUrlCondition.append("&note6=" + httpServletRequest.getParameter("note6"));
        }
        if (httpServletRequest.getParameter("note7") != null) {
            contentCond.setNote7(httpServletRequest.getParameter("note7"));
            map.put("note7", httpServletRequest.getParameter("note7"));
            pageUrlCondition.append("&note7=" + httpServletRequest.getParameter("note7"));
        }
        if (httpServletRequest.getParameter("note8") != null) {
            contentCond.setNote8(httpServletRequest.getParameter("note8"));
            map.put("note8", httpServletRequest.getParameter("note8"));
            pageUrlCondition.append("&note8=" + httpServletRequest.getParameter("note8"));
        }
        if (httpServletRequest.getParameter("note9") != null) {
            contentCond.setNote9(httpServletRequest.getParameter("note9"));
            map.put("note9", httpServletRequest.getParameter("note9"));
            pageUrlCondition.append("&note9=" + httpServletRequest.getParameter("note9"));
        }
        if (httpServletRequest.getParameter("note10") != null) {
            contentCond.setNote10(httpServletRequest.getParameter("note10"));
            map.put("note10", httpServletRequest.getParameter("note10"));
            pageUrlCondition.append("&note10=" + httpServletRequest.getParameter("note10"));
        }
        if (httpServletRequest.getParameter("note11") != null) {
            contentCond.setNote11(httpServletRequest.getParameter("note11"));
            map.put("note11", httpServletRequest.getParameter("note11"));
            pageUrlCondition.append("&note11=" + httpServletRequest.getParameter("note11"));
        }
        if (httpServletRequest.getParameter("note12") != null) {
            contentCond.setNote12(httpServletRequest.getParameter("note12"));
            map.put("note12", httpServletRequest.getParameter("note12"));
            pageUrlCondition.append("&note12=" + httpServletRequest.getParameter("note12"));
        }
        if (httpServletRequest.getParameter("note13") != null) {
            contentCond.setNote13(httpServletRequest.getParameter("note13"));
            map.put("note13", httpServletRequest.getParameter("note13"));
            pageUrlCondition.append(
                    "&singleLine2=" + httpServletRequest.getParameter("singleLine2"));
        }
        if (httpServletRequest.getParameter("note14") != null) {
            contentCond.setNote14(httpServletRequest.getParameter("note14"));
            map.put("note14", httpServletRequest.getParameter("note14"));
            pageUrlCondition.append("&note14=" + httpServletRequest.getParameter("note14"));
        }
        if (httpServletRequest.getParameter("note15") != null) {
            contentCond.setNote15(httpServletRequest.getParameter("note15"));
            map.put("note15", httpServletRequest.getParameter("note15"));
            pageUrlCondition.append("&note15=" + httpServletRequest.getParameter("note15"));
        }
        // 编辑器
        if (httpServletRequest.getParameter("content1") != null) {
            contentCond.setContent1(httpServletRequest.getParameter("content1"));
            map.put("content1", httpServletRequest.getParameter("content1"));
            pageUrlCondition.append("&content1=" + httpServletRequest.getParameter("content1"));
        }
        if (httpServletRequest.getParameter("content2") != null) {
            contentCond.setContent2(httpServletRequest.getParameter("content2"));
            map.put("content2", httpServletRequest.getParameter("content2"));
            pageUrlCondition.append("&content2=" + httpServletRequest.getParameter("content2"));
        }
        if (httpServletRequest.getParameter("content3") != null) {
            contentCond.setContent3(httpServletRequest.getParameter("content3"));
            map.put("content3", httpServletRequest.getParameter("content3"));
            pageUrlCondition.append("&content3=" + httpServletRequest.getParameter("content3"));
        }
        if (httpServletRequest.getParameter("content4") != null) {
            contentCond.setContent4(httpServletRequest.getParameter("content4"));
            map.put("content4", httpServletRequest.getParameter("content4"));
            pageUrlCondition.append("&content4=" + httpServletRequest.getParameter("content4"));
        }
        if (httpServletRequest.getParameter("content5") != null) {
            contentCond.setContent5(httpServletRequest.getParameter("content5"));
            map.put("content5", httpServletRequest.getParameter("content5"));
            pageUrlCondition.append("&content5=" + httpServletRequest.getParameter("content5"));
        }
        if (httpServletRequest.getParameter("content6") != null) {
            contentCond.setContent6(httpServletRequest.getParameter("content6"));
            map.put("content6", httpServletRequest.getParameter("content6"));
            pageUrlCondition.append("&content6=" + httpServletRequest.getParameter("content6"));
        }
        // 发布时间
        if (httpServletRequest.getParameter("startTime_createTime") != null) {
            contentCond.setStartTime_createTime(
                    httpServletRequest.getParameter("startTime_createTime"));
            map.put(
                    "startTime_createTime",
                    httpServletRequest.getParameter("startTime_createTime"));
            pageUrlCondition.append(
                    "&startTime_createTime="
                            + httpServletRequest.getParameter("startTime_createTime"));
        }
        if (httpServletRequest.getParameter("endTime_createTime") != null) {
            contentCond.setEndTime_createTime(
                    httpServletRequest.getParameter("endTime_createTime"));
            map.put("endTime_createTime", httpServletRequest.getParameter("endTime_createTime"));
            pageUrlCondition.append(
                    "&endTime_createTime=" + httpServletRequest.getParameter("endTime_createTime"));
        }
        searchHtmlVo.setContentCond(contentCond);
        searchHtmlVo.setMap(map);
        searchHtmlVo.setPageUrlCondition(pageUrlCondition.toString());
        return searchHtmlVo;
    }

    private Integer addMenuByModuleTemplate(
            MenuModuleEditDto menuModuleEditDto,
            HttpServletRequest httpServletRequest,
            List<Integer> newMenuIdList,
            boolean isCustomClassName,
            Integer parentId,
            ModuleTemplateEntity moduleTemplateEntity,
            SiteContext currentSite) {
        EditOneshotDto editOneshotDto;
        Integer insertMenuId;
        editOneshotDto = new EditOneshotDto();
        editOneshotDto.setParentId(parentId);
        if (isCustomClassName) {
            editOneshotDto.setClassName(menuModuleEditDto.getClassName());
            editOneshotDto.setClassEnName(menuModuleEditDto.getClassEnName());
        } else {
            editOneshotDto.setClassName(moduleTemplateEntity.getClassName());
            editOneshotDto.setClassEnName(moduleTemplateEntity.getClassEnName());
        }
        editOneshotDto.setShowPosition(menuModuleEditDto.getShowPosition());
        editOneshotDto.setHasManageChannel(moduleTemplateEntity.getHasManageChannel());
        editOneshotDto.setHasFrontChannel(moduleTemplateEntity.getHasFrontChannel());
        editOneshotDto.setPageType(moduleTemplateEntity.getPageType());
        editOneshotDto.setUrl(moduleTemplateEntity.getUrl());
        editOneshotDto.setSetting(moduleTemplateEntity.getSetting());
        editOneshotDto.setListPageUrl(moduleTemplateEntity.getListPageUrl());
        editOneshotDto.setDetailPageUrl(moduleTemplateEntity.getDetailPageUrl());
        editOneshotDto.setIdentifies(moduleTemplateEntity.getIdentifies());
        editOneshotDto.setCreateTime(new Date());
        editOneshotDto.setMenuField(moduleTemplateEntity.getMenuField());
        editOneshotDto.setModelField(moduleTemplateEntity.getModelField());
        editOneshotDto.setModelList(moduleTemplateEntity.getModelList());
        editOneshotDto.setModelSearch(moduleTemplateEntity.getModelSearch());
        editOneshotDto.setIsSubMenu(moduleTemplateEntity.getIsSubMenu());
        editOneshotDto.setIsCanDeleted(moduleTemplateEntity.getIsCanDeleted());
        editOneshotDto.setIsModuleMenu(false);
        editOneshotDto.setIsCanCascadeDeleted(moduleTemplateEntity.getIsCanCascadeDeleted());
        editOneshotDto.setIsCanAddSub(moduleTemplateEntity.getIsCanAddSub());
        editOneshotDto.setIsCanEdit(moduleTemplateEntity.getIsCanEdit());
        editOneshotDto.setIsCanSort(moduleTemplateEntity.getIsCanSort());
        editOneshotDto.setIsCanShowHidden(moduleTemplateEntity.getIsCanShowHidden());
        editOneshotDto.setIsShowCrumbs(moduleTemplateEntity.getIsShowCrumbs());
        editOneshotDto.setIsLoop(moduleTemplateEntity.getIsLoop());
        editOneshotDto.setHits(moduleTemplateEntity.getHits());
        editOneshotDto.setPublished(moduleTemplateEntity.getPublished());
        applyCurrentSiteScope(editOneshotDto, currentSite);
        oneShotDao.insertMenu(editOneshotDto);
        insertMenuId = editOneshotDto.getId();
        // 新增栏目默认 bizcode 取自身 id
        oneShotDao.assignBizcodeToOwnId(insertMenuId);
        newMenuIdList.add(insertMenuId);

        // 修改排序号
        oneShotDao.updateSort(new SortDto(insertMenuId, insertMenuId));

        addSetting(insertMenuId, httpServletRequest, moduleTemplateEntity.getSetting());
        return insertMenuId;
    }

    /**
     * 给当前登录用户添加指定栏目的权限
     *
     * @param insertMenuId
     * @param httpServletRequest
     * @param setting
     */
    private void addSetting(
            Integer insertMenuId, HttpServletRequest httpServletRequest, String setting) {
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        if (loginUser != null) {
            // 给当前用户组与所有父级用户组添加栏目权限
            addSettingForParentGroup(insertMenuId, setting, loginUser.getGroupId());
        }
    }

    /**
     * 递归父级用户组添加栏目权限
     *
     * @param insertMenuId
     * @param setting
     * @param groupId
     */
    private void addSettingForParentGroup(Integer insertMenuId, String setting, Integer groupId) {
        UserGroupEntity userGroupById = userGroupService.getUserGroupById(groupId);
        if (userGroupById != null) {
            userGroupById.setGroupSetting(userGroupById.getGroupSetting() + ',' + insertMenuId);
            userGroupById.setOtherSetting(userGroupById.getOtherSetting() + ';' + setting);
            userGroupService.updateUserGroup(userGroupById);
            addSettingForParentGroup(insertMenuId, setting, userGroupById.getParentId());
        }
    }

    /**
     * 左侧栏目递归 已废弃
     *
     * @param stringBuilder
     * @param allMenu
     * @param parentId
     * @param depth
     * @param httpServletRequest
     */
    @Deprecated
    private void recursiveForLeft(
            StringBuilder stringBuilder,
            List<OneshotEntity> allMenu,
            int parentId,
            int depth,
            HttpServletRequest httpServletRequest) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getParentId() == parentId)
                        .collect(Collectors.toList());
        OneshotEntity oneshotEntity;
        for (int i = 0; i < oneshotsByParentId.size(); i++) {
            oneshotEntity = oneshotsByParentId.get(i);
            // 若不是一级栏目需要增加子级html节点
            if (parentId != 0 && i == 0) {
                depth++;
                stringBuilder.append("<ul class=\"collapse\" data-depth=\"" + depth + "\">");
            }
            if (i != 0) {
                stringBuilder.append("</li>");
            }
            stringBuilder.append("<li data-depth=\"" + depth + "\">\n");
            stringBuilder.append(
                    "<a class=\"waves-effect dropdown-menu-left\" data-depth=\"" + depth + "\"");
            if (StringUtils.isNotBlank(oneshotEntity.getUrl())) {
                stringBuilder.append(
                        " href=\""
                                + (oneshotEntity
                                        .getUrl()
                                        .replace("{id}", String.valueOf(oneshotEntity.getId())))
                                + "\"");
                if (oneshotEntity.getUrl().contains("https://")
                        || oneshotEntity.getUrl().contains("http://")) {
                    stringBuilder.append(" target=\"_blank\" rel=\"nofollow\"");
                }
            } else {
                stringBuilder.append(" href=\"javascript:void(0);\"");
            }
            stringBuilder.append(
                    ">\n"
                            + (StringUtils.isNotBlank(oneshotEntity.getIconClass())
                                    ? "<i class=\""
                                            + oneshotEntity.getIconClass()
                                            + "\" aria-hidden=\"true\"></i>"
                                    : getIcon())
                            + "<span> "
                            + oneshotEntity.getClassName()
                            + " </span>");
            stringBuilder.append("</a>");
            recursiveForLeft(
                    stringBuilder, allMenu, oneshotEntity.getId(), depth, httpServletRequest);
            if (parentId != 0 && (i + 1) == oneshotsByParentId.size()) {
                stringBuilder.append("</li></ul>");
            }
        }
    }

    /** icon图标索引 */
    private int iconIndex = 0;

    private static final List<String> listIcon = new ArrayList<>();
    private static final List<String> iconClass = new ArrayList<>();

    /** 静态代码块 添加所有icon图标集合 */
    static {
        listIcon.add("<i class=\"glyphicon glyphicon-th-large\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-flag\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-list-alt\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-folder-open\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-globe\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-tint\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"glyphicon glyphicon-list\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-flask\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-flag-checkered\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-bolt\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-futbol-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-gavel\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-glass\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-handshake-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-hdd-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-id-card\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-map\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-newspaper-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-paper-plane\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-recycle\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-road\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-sliders\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-snowflake-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-space-shuttle\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-suitcase\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-sun-o\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-tree\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-trophy\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-umbrella\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-universal-access\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-rocket\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-anchor\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-archive\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-balance-scale\" aria-hidden=\"true\"></i>");
        listIcon.add("<i class=\"fa fa-beer\" aria-hidden=\"true\"></i>");

        iconClass.add("fa-flask");
        iconClass.add("fa-flag-checkered");
        iconClass.add("fa-bolt");
        iconClass.add("fa-futbol-o");
        iconClass.add("fa-gavel");
        iconClass.add("fa-glass");
        iconClass.add("fa-handshake-o");
        iconClass.add("fa-hdd-o");
        iconClass.add("fa-map");
        iconClass.add("fa-newspaper-o");
        iconClass.add("fa-paper-plane");
        iconClass.add("fa-recycle");
        iconClass.add("fa-road");
        iconClass.add("fa-sliders");
        iconClass.add("fa-snowflake-o");
        iconClass.add("fa-space-shuttle");
        iconClass.add("fa-suitcase");
        iconClass.add("fa-sun-o");
        iconClass.add("fa-tree");
        iconClass.add("fa-trophy");
        iconClass.add("fa-umbrella");
        iconClass.add("fa-universal-access");
        iconClass.add("fa-rocket");
        iconClass.add("fa-anchor");
        iconClass.add("fa-archive");
        iconClass.add("fa-balance-scale");
        iconClass.add("fa-beer");
    }

    /**
     * 遍历读取icon图片
     *
     * @return
     */
    private String getIcon() {
        iconIndex++;
        if (iconIndex > listIcon.size()) {
            iconIndex = 1;
        }
        return listIcon.get(iconIndex - 1);
    }

    /** 按深度优先顺序展开指定父栏目的后代，保留同级栏目顺序和原始名称。 */
    private List<OneshotEntity> flattenMenuDescendants(List<OneshotEntity> allMenu, int parentId) {
        Map<Integer, List<OneshotEntity>> childrenByParent = new HashMap<>();
        for (OneshotEntity menu : allMenu) {
            childrenByParent
                    .computeIfAbsent(menu.getParentId(), key -> new ArrayList<>(1))
                    .add(menu);
        }
        List<OneshotEntity> result = new ArrayList<>();
        List<OneshotEntity> roots = childrenByParent.remove(parentId);
        if (roots == null) {
            return result;
        }
        Deque<Iterator<OneshotEntity>> stack = new ArrayDeque<>();
        stack.push(roots.iterator());
        while (!stack.isEmpty()) {
            Iterator<OneshotEntity> siblings = stack.peek();
            if (!siblings.hasNext()) {
                stack.pop();
                continue;
            }
            OneshotEntity menu = siblings.next();
            result.add(menu);
            List<OneshotEntity> children = childrenByParent.remove(menu.getId());
            if (children != null) {
                stack.push(children.iterator());
            }
        }
        return result;
    }

    /**
     * 栏目管理 - 栏目递归
     *
     * @param oneshotEntities
     * @param allMenu
     * @param parentId
     * @param split
     */
    private void recursive(
            List<OneshotEntity> oneshotEntities,
            List<OneshotEntity> allMenu,
            int parentId,
            String split) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getParentId() == parentId)
                        .collect(Collectors.toList());
        for (OneshotEntity oneshotEntity : oneshotsByParentId) {
            // 此条件影响到了其他栏目，暂时注释
            // if (oneshotEntity.getHasManageChannel()){
            oneshotEntity.setClassName(
                    split
                            + "<span class=\"menu-title-span\" title=\"点击快速修改栏目名称\" data-id=\""
                            + oneshotEntity.getId()
                            + "\" data-parent-id=\""
                            + oneshotEntity.getParentId()
                            + "\">"
                            + oneshotEntity.getClassName()
                            + "</span><a name=\""
                            + oneshotEntity.getId()
                            + "\"></a>");
            oneshotEntities.add(oneshotEntity);
            recursive(
                    oneshotEntities,
                    allMenu,
                    oneshotEntity.getId(),
                    "<img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\""
                        + " width=\"18\" height=\"18\">"
                            + split);
            // }
        }
    }

    /**
     * 先添加当前父级 然后在递归栏目
     *
     * @param oneshotEntities
     * @param allMenu
     * @param parentId
     * @param split
     */
    private void recursiveFirstParent(
            List<OneshotEntity> oneshotEntities,
            List<OneshotEntity> allMenu,
            int parentId,
            String split) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getId() == parentId)
                        .collect(Collectors.toList());
        for (OneshotEntity oneshotEntity : oneshotsByParentId) {
            oneshotEntity.setClassName(
                    split
                            + "<span class=\"menu-title-span\" title=\"点击快速修改栏目名称\" data-id=\""
                            + oneshotEntity.getId()
                            + "\" data-parent-id=\""
                            + oneshotEntity.getParentId()
                            + "\">"
                            + oneshotEntity.getClassName()
                            + "</span><a name=\""
                            + oneshotEntity.getId()
                            + "\"></a>");
            oneshotEntities.add(oneshotEntity);
            recursive(
                    oneshotEntities,
                    allMenu,
                    oneshotEntity.getId(),
                    "<img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\""
                        + " width=\"18\" height=\"18\">"
                            + split);
        }
    }
}

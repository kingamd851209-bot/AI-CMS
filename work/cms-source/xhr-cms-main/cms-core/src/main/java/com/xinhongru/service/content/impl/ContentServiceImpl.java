package com.xinhongru.service.content.impl;

import com.alibaba.fastjson.JSON;
import com.xinhongru.cms.template.SiteWorkbenchContextService;
import com.xinhongru.common.page.PageResult;
import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.*;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.persistence.Pages;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.fieldDisplayDto.FieldDisplayDto;
import com.xinhongru.requestDto.model.OptionDto;
import com.xinhongru.requestDto.model.PageDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.model.ModelManageSeoVo;
import com.xinhongru.service.CheckRecordService;
import com.xinhongru.service.ContentHistoryService;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.service.logOperation.LogOperationService;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.locale.SiteLocaleService;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.site.storage.SiteStoragePathResolver;
import com.xinhongru.site.storage.StaticPaginationFiles;
import com.xinhongru.utils.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/** Created at 2021/8/9. */
@Slf4j
@Service
public class ContentServiceImpl implements ContentService {
    @Autowired private MemberCredentials memberCredentials;
    private ContentMapper contentDao;
    private OneShotMapper oneShotMapper;
    private LogOperationService logOperationService;
    private ExecutorService executorService;
    private ContentLinkService contentLinks;
    private CmsBuilderHtmlConfigProperties cmsBuilderHtmlConfigProperties;
    private SiteStoragePathResolver siteStoragePathResolver;
    private TransactionTemplate withdrawalRecovery;
    @Autowired private SiteLocaleService siteLocaleService;
    @Autowired private SiteWorkbenchContextService workbenchContext;

    @Autowired
    void setWithdrawalRecoveryTransactionManager(PlatformTransactionManager manager) {
        withdrawalRecovery = new TransactionTemplate(manager);
        withdrawalRecovery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired private ContentHistoryService contentHistoryService;

    @Autowired private CheckRecordService checkRecordService;

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
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Autowired
    public void setLogOperationService(LogOperationService logOperationService) {
        this.logOperationService = logOperationService;
    }

    @Autowired
    public void setContentDao(ContentMapper contentDao) {
        this.contentDao = contentDao;
    }

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    @Autowired
    public void setSiteStoragePathResolver(SiteStoragePathResolver siteStoragePathResolver) {
        this.siteStoragePathResolver = siteStoragePathResolver;
    }

    private AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Transactional
    @Override
    @CacheEvict(
            value = {"atricleCache", "atricleCaches"},
            allEntries = true,
            beforeInvocation = true)
    public ResultVO addArticle(ContentEntity contentEntity, HttpServletRequest httpServletRequest) {
        if (null == contentEntity) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        if (contentEntity.getExtensionFields() != null) {
            throw new IllegalArgumentException("扩展字段请通过原生内容保存接口按字段提交，旧接口不接受扩展JSON");
        }
        // 检测当前用户是否有添加数据的权限
        if (!hasContentPermission(
                httpServletRequest, contentEntity.getClassid(), WebConst.ADD_AUTHORITY_ID)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        // 获取栏目字段信息
        OneshotEntity modelById = lockContentModel(contentEntity.getClassid());
        if (modelById == null) return ResultVO.fail(ErrorCodeEnum.MENU_DATA_NOT_FOUND);
        List<ContentFieldDefinition> fieldSettingEntityList =
                ContentModelCodec.read(modelById.getModelField());
        ContentExtensionFields.validateRequired(null, fieldSettingEntityList);
        // 内容归属站点：取所属栏目的站点项目ID，便于多站点按 (biz_code, site_project_id) 定位
        if (modelById != null) {
            contentEntity.setSiteProjectId(modelById.getSiteProjectId());
        }

        Integer userGroupId = TaleUtils.getUserGroupId(httpServletRequest);
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        if (StringUtils.isNotBlank(modelById.getSetting())
                        && modelById.getSetting().contains(WebConst.CHECK_AUTHORITY_ID + "")
                        && AuthorityUtils.hasOtherSetting(
                                httpServletRequest,
                                contentEntity.getClassid(),
                                WebConst.CHECK_AUTHORITY_ID)
                || userGroupId <= 2
                || TaleUtils.isFullAdmin(httpServletRequest)) {
            contentEntity.setStatus(1);
        } else {
            contentEntity.setStatus(2); // 待审核
        }
        String memberPassword = memberPassword(contentEntity);
        contentEntity.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(
                        fieldSettingEntityList, contentEntity, null));
        contentDao.addArticle(contentEntity);
        if (StringUtils.isNotBlank(memberPassword)) {
            memberCredentials.store(contentEntity.getId(), memberPassword);
        }
        // 新增内容默认 content_code 取自身 id
        if (contentEntity.getId() != null) {
            contentDao.assignContentCodeToOwnId(contentEntity.getId());
        }

        // 比较结果
        Queue<LogTableDto> logTableDtoQueue =
                StringDeal.compareDifferences(null, contentEntity, fieldSettingEntityList);

        // 增加数据版本
        ContentHistoryEntity historyEntity =
                new ContentHistoryEntity()
                        .setContentId(contentEntity.getId())
                        .setMenuId(contentEntity.getClassid())
                        .setTitle(contentEntity.getTitle())
                        .setParams(JSON.toJSONString(contentEntity))
                        .setComparison(JSON.toJSONString(logTableDtoQueue))
                        .setUpdateDescription("新增数据")
                        .setUserId(loginUser.getId())
                        .setUserAccount(loginUser.getUsername())
                        .setUserName(loginUser.getScreenName());

        // 开启审核
        if (StringUtils.isNotBlank(modelById.getSetting())
                && modelById.getSetting().contains(WebConst.CHECK_AUTHORITY_ID + "")
                && !AuthorityUtils.hasOtherSetting(
                        httpServletRequest, contentEntity.getClassid(), WebConst.CHECK_AUTHORITY_ID)
                && userGroupId > 2) {
            // 待审核
            historyEntity.setCheckStatus(0);
            contentHistoryService.save(historyEntity);
            // 增加审核记录
            checkRecordService.save(
                    new CheckRecordEntity()
                            .setContentId(contentEntity.getId())
                            .setContentTitle(contentEntity.getTitle())
                            .setContentHistoryId(historyEntity.getId())
                            .setSubmitMenuId(contentEntity.getClassid())
                            .setSubmitMenuName(modelById.getClassName())
                            .setSubmitUserId(loginUser.getId())
                            .setSubmitUserName(loginUser.getScreenName())
                            .setSubmitDescription(contentEntity.getSubmitDescription())
                            .setType("add"));
        } else {
            // 直接通过
            historyEntity.setCheckStatus(1);
            historyEntity.setCheckDescription("当前账号无需审核");
            contentHistoryService.save(historyEntity);
        }

        // 操作日志明细
        LogOperationEntity logOperationEntity = new LogOperationEntity();
        logOperationEntity
                .setAction("新增数据")
                .setCreated(LocalDateTime.now())
                .setInfoId(contentEntity.getId())
                .setClassId(contentEntity.getClassid())
                .setRequestData(JSON.toJSONString(contentEntity))
                .setResponseData(JSON.toJSONString(contentEntity))
                .setUserName(loginUser.getUsername())
                .setUserId(loginUser.getId())
                .setIp(RequestOriginUtils.clientIp(httpServletRequest));
        logOperationService.add(logOperationEntity);

        return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addArticleImport(ContentEntity contentEntity) {
        if (contentEntity == null) throw new IllegalArgumentException("请提供导入内容");
        if (contentEntity.getExtensionFields() != null)
            throw new IllegalArgumentException("旧固定列导入不接受扩展JSON，请使用原生内容保存接口");
        OneshotEntity model = lockContentModel(contentEntity.getClassid());
        if (model == null) throw new IllegalArgumentException("导入栏目不存在或未绑定站点");
        var fields = ContentModelCodec.read(model.getModelField());
        ContentExtensionFields.validateRequired(null, fields);
        // 只从本函数实际持久化的固定导入列派生，不能索引请求里未写入的正文或其他值。
        ContentEntity row =
                new ContentEntity()
                        .setClassid(contentEntity.getClassid())
                        .setSiteProjectId(model.getSiteProjectId())
                        .setTitle(contentEntity.getTitle())
                        .setSort(contentEntity.getSort())
                        .setSingleLine1(contentEntity.getSingleLine1())
                        .setSingleLine2(contentEntity.getSingleLine2())
                        .setCreateTime(
                                contentEntity.getCreateTime() == null
                                        ? LocalDateTime.now()
                                        : contentEntity.getCreateTime());
        row.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(fields, row, null));
        int count = contentDao.addArticleImport(row);
        if (count > 0) {
            contentEntity.setId(row.getId());
            contentEntity.setSiteProjectId(row.getSiteProjectId());
            contentEntity.setPublicSearchText(row.getPublicSearchText());
            if (row.getId() != null) contentDao.assignContentCodeToOwnId(row.getId());
        }
        return count;
    }

    @Override
    @Transactional
    @CacheEvict(
            value = {"atricleCache", "atricleCaches"},
            allEntries = true,
            beforeInvocation = true)
    public void deleteArticleById(Integer cid) {
        if (null == cid) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        contentDao.deleteArticleById(cid);
    }

    @Override
    public void deleteArticleByIds(List<Integer> cids, Integer classid) {
        contentDao.deleteArticleByIds(cids, classid);
    }

    @Override
    @CacheEvict(
            value = {"atricleCache", "atricleCaches"},
            allEntries = true,
            beforeInvocation = true)
    //  后期可以考虑加入redis缓存
    //    @CacheEvict(value = "atricleCache", key =
    // "'classId:'+#contentEntity.getClassid()+'_id:'+#contentEntity.getId()",beforeInvocation =
    // true)
    @Transactional
    public ResultVO updateContentByCid(
            ContentEntity contentEntity, HttpServletRequest httpServletRequest) {
        if (contentEntity != null && contentEntity.getExtensionFields() != null) {
            throw new IllegalArgumentException("扩展字段请通过原生内容保存接口按字段提交，旧接口不接受扩展JSON");
        }
        if (null == contentEntity || null == contentEntity.getId()) {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
        // 检测当前用户是否有修改数据的权限
        if (!hasContentPermission(
                httpServletRequest, contentEntity.getClassid(), WebConst.UPDATE_AUTHORITY_ID)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        OneshotEntity modelById = lockContentModel(contentEntity.getClassid());
        if (modelById == null) return ResultVO.fail(ErrorCodeEnum.MENU_DATA_NOT_FOUND);
        ContentEntity articleById =
                contentDao.lockActiveArticleInSiteMenu(
                        modelById.getSiteProjectId(),
                        contentEntity.getClassid(),
                        contentEntity.getId());
        if (articleById == null
                || !Objects.equals(articleById.getClassid(), contentEntity.getClassid())
                || (articleById.getSiteProjectId() != null
                        && !Objects.equals(
                                articleById.getSiteProjectId(), modelById.getSiteProjectId())))
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        contentEntity.setSiteProjectId(modelById.getSiteProjectId());
        contentEntity.setExtensionFields(articleById.getExtensionFields());
        List<ContentFieldDefinition> fieldSettingEntityList =
                ContentModelCodec.read(modelById.getModelField());
        ContentExtensionFields.validateRequired(
                contentEntity.getExtensionFields(), fieldSettingEntityList);

        // 如果是网站管理员组级别的用户添加数据后直接发布，不需要审核;校验当前用户是否拥有审核权限,如果有则设置为已发布状态,如果无则设置为草稿状态
        Integer userGroupId = TaleUtils.getUserGroupId(httpServletRequest);
        if (userGroupId <= 2
                || TaleUtils.isFullAdmin(httpServletRequest)
                || AuthorityUtils.hasOtherSetting(
                        httpServletRequest,
                        contentEntity.getClassid(),
                        WebConst.APPROVAL_AUTHORITY_ID)) {
            contentEntity.setStatus(1);
        } else {
            contentEntity.setStatus(0);
        }
        String memberPassword = memberPassword(contentEntity);
        memberPassword(articleById);
        if (StringUtils.isNotBlank(memberPassword)) {
            if (StringUtils.isNotBlank(modelById.getSetting())
                    && modelById.getSetting().contains(WebConst.CHECK_AUTHORITY_ID + "")
                    && !AuthorityUtils.hasOtherSetting(
                            httpServletRequest, contentEntity.getClassid(), WebConst.CHECK_AUTHORITY_ID)
                    && userGroupId > 2) {
                return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
            }
            memberCredentials.store(contentEntity.getId(), memberPassword);
        }
        // 比较结果
        Queue<LogTableDto> logTableDtoQueue =
                StringDeal.compareDifferences(articleById, contentEntity, fieldSettingEntityList);

        // 存在字段更新则提交数据
        if (CollectionUtils.isNotEmpty(logTableDtoQueue)) {
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);

            // 增加数据版本
            ContentHistoryEntity historyEntity =
                    new ContentHistoryEntity()
                            .setContentId(contentEntity.getId())
                            .setMenuId(contentEntity.getClassid())
                            .setTitle(contentEntity.getTitle())
                            .setParams(JSON.toJSONString(contentEntity))
                            .setComparison(JSON.toJSONString(logTableDtoQueue))
                            .setUserId(loginUser.getId())
                            .setUserAccount(loginUser.getUsername())
                            .setUserName(loginUser.getScreenName());

            // 如当前栏目开启审核
            if (StringUtils.isNotBlank(modelById.getSetting())
                    && modelById.getSetting().contains(WebConst.CHECK_AUTHORITY_ID + "")
                    && !AuthorityUtils.hasOtherSetting(
                            httpServletRequest,
                            contentEntity.getClassid(),
                            WebConst.CHECK_AUTHORITY_ID)
                    && userGroupId > 2) {
                historyEntity.setCheckStatus(0); // 待审核
                long count = checkRecordService.countByContentId(contentEntity.getId());
                historyEntity.setUpdateDescription("「已发布」修改" + logTableDtoQueue.size() + "个字段");
                if (count > 0) {
                    // 查找此数据未完成的审批，设置为作废状态
                    checkRecordService.updateCheckStatus3(contentEntity.getId());
                    historyEntity.setUpdateDescription(
                            "「新增未发布」修改" + logTableDtoQueue.size() + "个字段");
                }
                contentHistoryService.save(historyEntity);

                // 增加审核记录
                checkRecordService.save(
                        new CheckRecordEntity()
                                .setContentId(contentEntity.getId())
                                .setContentTitle(contentEntity.getTitle())
                                .setContentHistoryId(historyEntity.getId())
                                .setSubmitMenuId(contentEntity.getClassid())
                                .setSubmitMenuName(
                                        oneShotMapper
                                                .getModelById(contentEntity.getClassid())
                                                .getClassName())
                                .setSubmitUserId(loginUser.getId())
                                .setSubmitUserName(loginUser.getScreenName())
                                .setSubmitDescription(contentEntity.getSubmitDescription())
                                .setType("update"));
            } else {
                historyEntity.setCheckStatus(1); // 直接通过
                historyEntity.setUpdateDescription("");
                contentHistoryService.save(historyEntity);
                contentEntity.setPublicSearchText(
                        com.xinhongru.cms.search.PublicSearchText.forContent(
                                fieldSettingEntityList, contentEntity, articleById));
                contentDao.updateArticleById(contentEntity); // 更新数据行
                if (Integer.valueOf(0).equals(contentEntity.getStatus())) {
                    removeStaticDetail(modelById, articleById);
                }
            }

            // 操作日志明细
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            String actionName = "修改数据，修改了" + logTableDtoQueue.size() + "个字段";
            logOperationEntity
                    .setAction(actionName)
                    .setCreated(LocalDateTime.now())
                    .setInfoId(contentEntity.getId())
                    .setClassId(contentEntity.getClassid())
                    .setRequestData(JSON.toJSONString(contentEntity))
                    .setResponseData(logOperationService.getUpdateTableHtml(logTableDtoQueue))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
        }

        return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
    }

    @Override
    //    @Cacheable(value = "atricleCache", key = "'atricleById_' + #p0")
    public ContentEntity getArticleById(Integer id) {
        if (null == id) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        ContentEntity content = contentDao.getArticleById(id);
        if (content != null) memberPassword(content);
        return content;
    }

    private String memberPassword(ContentEntity content) {
        if (!MemberCredentials.isDirectory(content.getClassid())) {
            return null;
        }
        String raw = content.getSingleLine5();
        content.setSingleLine5(null);
        return raw;
    }

    @Override
    // @Cacheable(value = "atricleCaches", key = "'articlesByCond_' + #p1 + 'type_' + #p0.type+
    // 'classid_'+#p0.classid+''")
    public PageResult<ContentEntity> getArticlesByCond(
            OneshotEntity oneshotEntity, ContentCond contentCond, int pageNum, int pageSize) {
        if (null == contentCond) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        if (null == oneshotEntity) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        List<String> fieldNameList = buildContentListFieldNames(oneshotEntity.getModelField());
        if (MemberCredentials.isDirectory(oneshotEntity.getId())) {
            fieldNameList.remove("single_line5");
            if (fieldNameList.isEmpty()) fieldNameList.add("title");
            contentCond.setSingleLine5(null);
        }
        return Pages.query(
                pageNum,
                pageSize,
                page -> contentDao.getArticlesByCond(page, fieldNameList, contentCond, null));
    }

    private static List<String> buildContentListFieldNames(String modelField) {
        List<FieldDisplayDto> fieldDisplayList = parseContentListFields(modelField);
        List<String> fieldNameList =
                fieldDisplayList.stream()
                        .filter(Objects::nonNull)
                        .map(FieldDisplayDto::getFieldName)
                        .map(ContentServiceImpl::resolveContentListColumn)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toCollection(ArrayList::new));
        if (fieldNameList.isEmpty()) {
            fieldNameList.add("title");
        }
        return fieldNameList;
    }

    private static List<FieldDisplayDto> parseContentListFields(String modelField) {
        if (StringUtils.isBlank(modelField)) {
            return Collections.emptyList();
        }
        try {
            List<FieldDisplayDto> fields = JSON.parseArray(modelField, FieldDisplayDto.class);
            return fields == null ? Collections.emptyList() : fields;
        } catch (RuntimeException ex) {
            log.warn("Failed to parse content model fields, falling back to title column.", ex);
            return Collections.emptyList();
        }
    }

    private static String resolveContentListColumn(String fieldName) {
        return ContentColumnWhitelist.resolve(fieldName);
    }

    @Override
    public int getArticlesCountByCond(ContentCond contentCond) {
        return contentDao.getArticlesCountByCond(contentCond, null);
    }

    @Override
    @Cacheable(value = "atricleCaches", key = "'recentlyArticle_' + #p0")
    public PageResult<ContentEntity> getRecentlyArticle(int pageNum, int pageSize) {
        return Pages.query(pageNum, pageSize, page -> contentDao.getRecentlyArticle(page));
    }

    @Override
    public PageResult<ContentEntity> searchArticle(String param, int pageNun, int pageSize) {
        return Pages.query(pageNun, pageSize, page -> contentDao.searchArticle(page, param));
    }

    @Override
    public int updateElite(List<Integer> ids, Integer classid, String optionType) {
        return contentDao.updateElite(ids, classid, optionType);
    }

    /**
     * 内容管理 - 修改排序号
     *
     * @param classid
     * @param sortDto
     * @param httpServletRequest
     * @return
     */
    @Override
    public ResultVO updateSort(
            Integer classid, SortDto sortDto, HttpServletRequest httpServletRequest) {
        // 检测当前用户是否有排序的权限
        if (!hasContentPermission(httpServletRequest, classid, WebConst.SORT_AUTHORITY_ID)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        ContentEntity modelById = contentDao.getModelById(sortDto.getId());
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        if (!modelById.getClassid().equals(classid)) {
            log.warn(
                    "将要排序的数据的classid入参与数据库自身的classid不一致，classid入参 - {}，数据库中classid - {}",
                    classid,
                    modelById.getClassid());
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
        Boolean updateSort = contentDao.updateSort(sortDto);
        if (updateSort) {
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("将排序号修改为：" + sortDto.getSort())
                    .setCreated(LocalDateTime.now())
                    .setInfoId(sortDto.getId())
                    .setClassId(classid)
                    .setRequestData(JSON.toJSONString(sortDto))
                    .setResponseData(
                            logOperationService.getSimpleUpdateTableHtml(
                                    WebConst.SORT_CHINESE, modelById.getSort(), sortDto.getSort()))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);

            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    public void updatePublished(ContentEntity contentEntity) {
        contentDao.updatePublished(contentEntity.getClassid(), contentEntity.getId());
    }

    @Override
    public PageResult<ContentEntity> getContentByCond(
            OneshotEntity oneshotEntity, PageDto pageDto) {
        if (null == oneshotEntity) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        if (StringUtils.isBlank(oneshotEntity.getModelList())) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        if (null == pageDto) {
            pageDto = new PageDto();
        }
        List<String> fieldNameList = buildContentListFieldNames(oneshotEntity.getModelList());
        return Pages.query(
                pageDto.getPage(),
                pageDto.getLimit(),
                page -> contentDao.getContentByCond(page, fieldNameList));
    }

    @Override
    public ContentEntity getSeoById(Integer classid, Integer id) {
        return contentDao.getSeoById(classid, id);
    }

    @Override
    public ResultVO updateSEO(
            ModelManageSeoVo modelManageSeoVo, HttpServletRequest httpServletRequest) {
        ContentEntity modelById = contentDao.getModelById(modelManageSeoVo.getId());
        if (null == modelById) {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
        if (!Objects.equals(modelById.getClassid(), modelManageSeoVo.getClassid())
                || !hasContentPermission(
                        httpServletRequest, modelById.getClassid(), WebConst.UPDATE_AUTHORITY_ID)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        Boolean updateSEO = contentDao.updateSEO(modelManageSeoVo);
        if (updateSEO) {
            // 操作日志明细
            Queue<LogTableDto> logTableDtoQueue = new LinkedList<>();
            String oldSeoTitle = modelById.getSeoTitle();
            String newSeoTitle = modelManageSeoVo.getSeoTitle();
            if (!Objects.equals(oldSeoTitle, newSeoTitle)) {
                logTableDtoQueue.add(
                        new LogTableDto()
                                .setFieldName(WebConst.SEO_TITLE)
                                .setUpdateBefore(oldSeoTitle)
                                .setUpdateAfter(newSeoTitle));
            }
            String oldSeoKeywords = modelById.getSeoKeywords();
            String newSeoKeywords = modelManageSeoVo.getSeoKeywords();
            if (!Objects.equals(oldSeoKeywords, newSeoKeywords)) {
                logTableDtoQueue.add(
                        new LogTableDto()
                                .setFieldName(WebConst.SEO_KEYWORD)
                                .setUpdateBefore(oldSeoKeywords)
                                .setUpdateAfter(newSeoKeywords));
            }
            String oldSeoDescription = modelById.getSeoDescription();
            String newSeoDescription = modelManageSeoVo.getSeoDescription();
            if (!Objects.equals(oldSeoDescription, newSeoDescription)) {
                logTableDtoQueue.add(
                        new LogTableDto()
                                .setFieldName(WebConst.SEO_DESCRIPTION)
                                .setUpdateBefore(oldSeoDescription)
                                .setUpdateAfter(newSeoDescription));
            }
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("修改SEO信息")
                    .setCreated(LocalDateTime.now())
                    .setInfoId(modelManageSeoVo.getId())
                    .setClassId(modelManageSeoVo.getClassid())
                    .setRequestData(JSON.toJSONString(modelManageSeoVo))
                    .setResponseData(logOperationService.getUpdateTableHtml(logTableDtoQueue))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);

            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    @Transactional
    public ResultVO deleteInfoById(
            Integer classid, IdDto idDto, HttpServletRequest httpServletRequest) {
        // 检测当前登录用户是否有删除数据的权限
        if (!hasContentPermission(httpServletRequest, classid, WebConst.DELETE_AUTHORITY_ID)) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        OneshotEntity menu = lockContentModel(classid);
        ContentEntity modelById = menu == null ? null
                : contentDao.lockActiveArticleInSiteMenu(menu.getSiteProjectId(), classid, idDto.getId());
        if (modelById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }

        // 检查是否存在关联数据
        Integer count = contentDao.getChildCount(idDto);
        if (count > 0) {
            return ResultVO.fail(ErrorCodeEnum.HAS_RELATION_DATA);
        }
        if (contentDao.updateElite(List.of(idDto.getId()), classid, "27") > 0) {
            removeStaticDetail(menu, modelById);
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            LogOperationEntity logOperationEntity = new LogOperationEntity();
            logOperationEntity
                    .setAction("删除数据")
                    .setCreated(LocalDateTime.now())
                    .setInfoId(idDto.getId())
                    .setClassId(classid)
                    .setRequestData(JSON.toJSONString(idDto))
                    .setResponseData(JSON.toJSONString(modelById))
                    .setUserName(loginUser.getUsername())
                    .setUserId(loginUser.getId())
                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
            logOperationService.add(logOperationEntity);
            return ResultVO.success(ErrorCodeEnum.DELETE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.DELETE_FAIL);
        }
    }

    /** 与字段配置保存共用栏目锁；所有正文写遵循栏目锁、内容锁的顺序。 */
    private OneshotEntity lockContentModel(Integer menuId) {
        if (menuId == null) return null;
        OneshotEntity located = oneShotMapper.getModelById(menuId);
        if (located == null || located.getSiteProjectId() == null) return null;
        OneshotEntity current =
                oneShotMapper.lockFieldConfiguration(
                        located.getSiteProjectId(), menuId.longValue());
        if (current == null
                || !Objects.equals(current.getSiteProjectId(), located.getSiteProjectId()))
            return null;
        return current;
    }

    private boolean hasContentPermission(
            HttpServletRequest request, Integer classId, Integer optionId) {
        if (permissionService == null || classId == null || optionId == null) {
            return true;
        }
        return permissionService.snapshot(request).canOperate(classId, optionId);
    }

    /** optionType(内容属性操作 1~27) → other_setting 操作 optionId(1~23)，用于权限校验 */
    private static Integer optionTypeToOptionId(Integer optionType) {
        if (optionType == null) {
            return null;
        }
        return switch (optionType) {
            case 1, 2 -> WebConst.INDEX_TOP_AUTHORITY_ID;
            case 3, 4 -> WebConst.INDEX_HOT_AUTHORITY_ID;
            case 5, 6 -> WebConst.INDEX_ELITE_AUTHORITY_ID;
            case 7, 8 -> WebConst.CHANNEL_TOP_AUTHORITY_ID;
            case 9, 10 -> WebConst.CHANNEL_HOT_AUTHORITY_ID;
            case 11, 12 -> WebConst.CHANNEL_ELITE_AUTHORITY_ID;
            case 13, 14 -> WebConst.MENU_TOP_AUTHORITY_ID;
            case 15, 16 -> WebConst.MENU_HOT_AUTHORITY_ID;
            case 17, 18 -> WebConst.MENU_ELITE_AUTHORITY_ID;
            case 19, 20 -> WebConst.NAV_ELITE_AUTHORITY_ID;
            case 21, 22 -> WebConst.RIGHT_ELITE_AUTHORITY_ID;
            case 23, 24 -> WebConst.LEFT_ELITE_AUTHORITY_ID;
            case 25, 26 -> WebConst.APPROVAL_AUTHORITY_ID;
            case 27 -> WebConst.DELETE_AUTHORITY_ID;
            default -> null;
        };
    }

    @Override
    @Transactional
    public ResultVO updateOption(
            OptionDto optionDto, Integer classid, HttpServletRequest httpServletRequest) {
        // 校验当前用户是否有当前操作的权限（optionType 映射到 other_setting 的 optionId）
        if (!hasContentPermission(
                httpServletRequest, classid, optionTypeToOptionId(optionDto.getOptionType()))) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        // 如果是批量删除 检查是否存在关联数据
        if (optionDto.getOptionType().equals(27)) {
            // 检查是否存在关联数据
            Integer countRelationData = contentDao.getBatchChildCount(optionDto.getIds());
            if (countRelationData > 0) {
                return ResultVO.fail(ErrorCodeEnum.HAS_RELATION_DATA);
            }
        }

        int optionType = optionDto.getOptionType();
        List<Integer> ids = optionDto.getIds();
        List<ContentEntity> removedDetails = List.of();
        OneshotEntity menu = null;
        if (optionType == 26 || optionType == 27) {
            menu = lockContentModel(classid);
            if (menu == null) return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
            var siteId = menu.getSiteProjectId();
            removedDetails = ids.stream().distinct().sorted()
                    .map(id -> contentDao.lockActiveArticleInSiteMenu(siteId, classid, id))
                    .filter(Objects::nonNull)
                    .filter(row -> optionType == 27 || row.getStatus() != null && row.getStatus() != 2)
                    .toList();
            ids = removedDetails.stream().map(ContentEntity::getId).toList();
            if (ids.isEmpty()) return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
        Integer count =
                contentDao.updateElite(
                        ids, classid, String.valueOf(optionType));
        if (count > 0) {
            if (!removedDetails.isEmpty()) {
                for (var row : removedDetails) removeStaticDetail(menu, row);
            }
            // 操作日志明细
            UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
            for (Integer infoId : ids) {
                executorService.execute(
                        () -> {
                            LogOperationEntity logOperationEntity = new LogOperationEntity();
                            logOperationEntity
                                    .setAction(
                                            "批量修改【"
                                                    + getOptionTypeValue(optionDto.getOptionType())
                                                    + "】属性")
                                    .setCreated(LocalDateTime.now())
                                    .setInfoId(infoId)
                                    .setClassId(classid)
                                    .setRequestData(JSON.toJSONString(optionDto))
                                    .setResponseData(
                                            logOperationService.getSimpleUpdateTableHtml(
                                                    getOptionTypeValue(optionDto.getOptionType()),
                                                    WebConst.FALSE_CHINESE,
                                                    WebConst.TRUE_CHINESE))
                                    .setUserName(loginUser.getUsername())
                                    .setUserId(loginUser.getId())
                                    .setIp(RequestOriginUtils.clientIp(httpServletRequest));
                            logOperationService.add(logOperationEntity);
                        });
            }

            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    private void removeStaticDetail(OneshotEntity menu, ContentEntity row) {
        String route = menu.getDetailPageUrl();
        if (route == null || !route.contains("{id}")) return;
        URI uri;
        try {
            uri = URI.create(contentLinks.getDetailUrl(row, menu));
        } catch (IllegalArgumentException invalid) {
            return;
        }
        String path = uri.getPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || path == null || !path.endsWith(WebConst.HTML_SUFFIX)
                || path.indexOf('\\') >= 0 || Arrays.asList(path.split("/")).contains("..")) return;
        var site = menu.getSiteProjectId();
        String code = siteStoragePathResolver.requireSiteCode(site);
        var root = Path.of(siteStoragePathResolver.siteRootPath(
                        cmsBuilderHtmlConfigProperties.getSaveRoot(), code))
                .toAbsolutePath().normalize();
        var roots = new ArrayList<Path>();
        roots.add(root);
        if (siteLocaleService != null) {
            siteLocaleService.list(menu.getSiteProjectId()).stream()
                    .map(SiteLocaleService.LocaleView::languageCode)
                    .filter(language -> language != null && language.matches("[A-Za-z0-9_-]+"))
                    .distinct()
                    .map(root::resolve)
                    .forEach(roots::add);
        }
        try {
            var expected = StaticRenderProof.of(site, menu.getId(), true, menu.getDetailPageTemplate());
            for (Path localeRoot : roots) {
                Path base = localeRoot.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
                List<Path> pages = List.of();
                if (route.contains("{pageIndex}")) {
                    String paginated = route.replace("{id}", row.getId().toString())
                            .replace("{classId}", menu.getId().toString())
                            .replace("{parentId}", Objects.toString(menu.getParentId(), "null"));
                    pages = StaticPaginationFiles.existingFiles(localeRoot, paginated);
                }
                var linked = pages.isEmpty() ? Set.of(base)
                        : StaticPaginationFiles.linkedFiles(localeRoot, base, pages, "div.page a[href]");
                var files = new LinkedHashSet<>(pages);
                files.add(base);
                for (Path file : files) {
                    if (!StaticPaginationFiles.isPublishedFile(root, file)) continue;
                    String proof = workbenchContext == null ? null :
                            workbenchContext.staticArtifactProof(site, code, file, Files.readString(file));
                    if (expected.equals(proof) || proof == null && linked.contains(file))
                        stageStaticDetailRemoval(root, menu, row.getId(), file);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法清理已发布的详情静态页", failure);
        }
    }

    private void stageStaticDetailRemoval(Path root, OneshotEntity menu, Integer recordId, Path file)
            throws IOException {
        var staged = root.toRealPath().resolveSibling(".withdraw-" + UUID.randomUUID());
        Files.move(file, staged, StandardCopyOption.ATOMIC_MOVE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                finishStaticDetailRemoval(status, menu, recordId, file, staged);
            }
        });
    }

    private void finishStaticDetailRemoval(
            int status, OneshotEntity menu, Integer recordId, Path file, Path staged) {
        try {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                Files.deleteIfExists(staged);
            } else if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                withdrawalRecovery.executeWithoutResult(ignored -> restoreStaticDetail(menu, recordId, file, staged));
            } else {
                log.error("撤回详情事务结果未知，保留恢复文件 {}", staged);
            }
        } catch (IOException | RuntimeException failure) {
            log.error("无法完成详情撤回文件清理，恢复文件位于 {}", staged, failure);
        }
    }

    private void restoreStaticDetail(OneshotEntity menu, Integer recordId, Path file, Path staged) {
        var site = menu.getSiteProjectId();
        var current = oneShotMapper.lockFieldConfiguration(site, menu.getId().longValue());
        var record = current == null ? null : contentDao.lockActiveArticleInSiteMenu(site, menu.getId(), recordId);
        try {
            if (record != null && Objects.equals(record.getStatus(), 1)
                    && Objects.equals(current.getDetailPageUrl(), menu.getDetailPageUrl())
                    && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(staged, file);
                    return;
                } catch (FileAlreadyExistsException regenerated) {
                    // 保留已经生成的新文件。
                }
            }
            Files.deleteIfExists(staged);
        } catch (IOException failure) {
            throw new IllegalStateException("无法恢复撤回前的详情文件", failure);
        }
    }

    /**
     * 根据操作ID获取操作名称
     *
     * @param optionType
     * @return
     */
    private String getOptionTypeValue(Integer optionType) {
        String[] settingTitleArr = {
            "首页置顶", "取消首页置顶", "首页热门", "取消首页热门", "首页推荐", "取消首页推荐", "频道置顶", "取消频道置顶", "频道热门",
            "取消频道热门", "频道推荐", "取消频道推荐", "栏目置顶", "取消栏目置顶", "栏目热门", "取消栏目热门", "栏目推荐", "取消栏目推荐",
            "导航推荐", "取消导航推荐", "右侧推荐", "取消右侧推荐", "左侧推荐", "取消左侧推荐", "存为发布", "存为草稿", "批量删除"
        };
        for (int i = 0; i < settingTitleArr.length; i++) {
            if (optionType.intValue() == (i + 1)) {
                return settingTitleArr[i];
            }
        }
        return "未知的属性值 - " + optionType;
    }

    @Override
    public List<ContentEntity> getArticleByClassId(Integer classId) {
        return contentDao.getArticleByClassId(classId);
    }

    @Override
    public List<ContentEntity> getArticleByClassIdForSelect(List<Integer> classIdList) {
        return contentDao.getArticleByClassIdForSelect(classIdList);
    }

    @Override
    public void outputExcel(
            Integer classid,
            ContentCond contentCond,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        OneshotEntity modelById = oneShotMapper.getModelById(classid);
        //        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        //        UserEntity userInfoById = userService.getUserInfoById(loginUser.getId(), false);
        //        if(!AuthorityUtils.hasOtherSetting(userInfoById,classid,20)){
        //            //无权限
        //        }
        List<ContentFieldDefinition> fieldSettingField =
                ContentModelCodec.read(modelById.getModelField());
        if (MemberCredentials.isDirectory(classid)) {
            contentCond.setSingleLine5(null);
            fieldSettingField = fieldSettingField.stream()
                    .filter(field -> field != null
                            && !MemberCredentials.isPasswordField(classid, field.getFieldName()))
                    .toList();
        }
        // 只从现有列白名单选择物理列，扩展字段统一读取JSON列。
        fieldSettingField =
                fieldSettingField.stream()
                        .filter(
                                field ->
                                        field != null
                                                && (com.xinhongru.content.model
                                                                .ContentExtensionFields.isName(
                                                                field.getFieldName())
                                                        || ContentColumnWhitelist.resolve(
                                                                        field.getFieldName())
                                                                != null))
                        .sorted(
                                Comparator.comparing(
                                                (ContentFieldDefinition field) ->
                                                        field.getSort() == null
                                                                ? 0
                                                                : field.getSort())
                                        .reversed())
                        .collect(Collectors.toList());
        List<String> fieldNameList =
                fieldSettingField.stream()
                        .map(
                                field ->
                                        com.xinhongru.content.model.ContentExtensionFields.isName(
                                                        field.getFieldName())
                                                ? "extension_fields"
                                                : ContentColumnWhitelist.resolve(
                                                        field.getFieldName()))
                        .distinct()
                        .collect(Collectors.toCollection(ArrayList::new));
        if (fieldNameList.isEmpty()) fieldNameList.add("title");
        contentCond.setClassid(classid);
        List<ContentEntity> contentEntities =
                contentDao.getArticlesByCond(fieldNameList, contentCond, null);
        // 图片、视频、附件字段 如果内容不为空增加当前请求域名
        if (CollectionUtils.isNotEmpty(contentEntities)) {
            String domain = StringDeal.getDomain(httpServletRequest);

            for (ContentEntity contentEntity : contentEntities) {
                if (StringUtils.isNotBlank(contentEntity.getPhoto1())) {
                    contentEntity.setPhoto1(domain + contentEntity.getPhoto1());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto2())) {
                    contentEntity.setPhoto2(domain + contentEntity.getPhoto2());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto3())) {
                    contentEntity.setPhoto3(domain + contentEntity.getPhoto3());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto4())) {
                    contentEntity.setPhoto4(domain + contentEntity.getPhoto4());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto5())) {
                    contentEntity.setPhoto5(domain + contentEntity.getPhoto5());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto6())) {
                    contentEntity.setPhoto6(domain + contentEntity.getPhoto6());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto7())) {
                    contentEntity.setPhoto7(domain + contentEntity.getPhoto7());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto8())) {
                    contentEntity.setPhoto8(domain + contentEntity.getPhoto8());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto9())) {
                    contentEntity.setPhoto9(domain + contentEntity.getPhoto9());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto10())) {
                    contentEntity.setPhoto10(domain + contentEntity.getPhoto10());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto11())) {
                    contentEntity.setPhoto11(domain + contentEntity.getPhoto11());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto12())) {
                    contentEntity.setPhoto12(domain + contentEntity.getPhoto12());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto13())) {
                    contentEntity.setPhoto13(domain + contentEntity.getPhoto13());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto14())) {
                    contentEntity.setPhoto14(domain + contentEntity.getPhoto14());
                }
                if (StringUtils.isNotBlank(contentEntity.getPhoto15())) {
                    contentEntity.setPhoto15(domain + contentEntity.getPhoto15());
                }
                // 视频
                if (StringUtils.isNotBlank(contentEntity.getMedia1())) {
                    contentEntity.setMedia1(domain + contentEntity.getMedia1());
                }
                if (StringUtils.isNotBlank(contentEntity.getMedia2())) {
                    contentEntity.setMedia2(domain + contentEntity.getMedia2());
                }
                if (StringUtils.isNotBlank(contentEntity.getMedia3())) {
                    contentEntity.setMedia3(domain + contentEntity.getMedia3());
                }
                if (StringUtils.isNotBlank(contentEntity.getMedia4())) {
                    contentEntity.setMedia4(domain + contentEntity.getMedia4());
                }
                // 文件
                if (StringUtils.isNotBlank(contentEntity.getFile1())) {
                    contentEntity.setFile1(domain + contentEntity.getFile1());
                }
                if (StringUtils.isNotBlank(contentEntity.getFile2())) {
                    contentEntity.setFile2(domain + contentEntity.getFile2());
                }
                if (StringUtils.isNotBlank(contentEntity.getFile3())) {
                    contentEntity.setFile3(domain + contentEntity.getFile3());
                }
                if (StringUtils.isNotBlank(contentEntity.getFile4())) {
                    contentEntity.setFile4(domain + contentEntity.getFile4());
                }
            }
        }
        ExcelUtils.writeExcel(httpServletResponse, contentEntities, fieldSettingField);
    }

    @Override
    public List<ContentEntity> getArticleByClassIdForSelect(Integer classId, List<Integer> idList) {
        List<ContentEntity> contentEntityListOrdered = new ArrayList<>();
        Optional<ContentEntity> entityOptional;
        List<ContentEntity> contentEntityList =
                getArticleByClassIdForSelect(Collections.singletonList(classId)).stream()
                        .filter(item -> idList.contains(item.getId()))
                        .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(contentEntityList)) {
            for (Integer id : idList) {
                entityOptional =
                        contentEntityList.stream()
                                .filter(item -> item.getId().equals(id))
                                .findFirst();
                if (entityOptional.isPresent()) {
                    contentEntityListOrdered.add(entityOptional.get());
                }
            }
        }
        return contentEntityListOrdered;
    }
}

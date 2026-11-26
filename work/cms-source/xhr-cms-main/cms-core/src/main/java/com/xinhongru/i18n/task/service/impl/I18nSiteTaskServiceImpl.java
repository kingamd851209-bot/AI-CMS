package com.xinhongru.i18n.task.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.cms.uipackage.UiPackageService;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.i18n.service.AiTranslationService;
import com.xinhongru.i18n.task.entity.I18nSiteTaskDetailEntity;
import com.xinhongru.i18n.task.entity.I18nSiteTaskEntity;
import com.xinhongru.i18n.task.mapper.I18nSiteTaskDetailMapper;
import com.xinhongru.i18n.task.mapper.I18nSiteTaskMapper;
import com.xinhongru.i18n.task.service.I18nSiteTaskService;
import com.xinhongru.i18n.task.view.I18nSiteTaskDetailView;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.template.TemplateFileAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 多语言站点生成任务：主任务/明细与逐栏目数据同步实现 */
@Service
public class I18nSiteTaskServiceImpl implements I18nSiteTaskService {
    private static final Logger LOG = LoggerFactory.getLogger(I18nSiteTaskServiceImpl.class);

    private static final List<String> ALL_DIMS =
            Arrays.asList(DIM_MODEL, DIM_TEMPLATE, DIM_CONTENT);

    private final I18nSiteTaskMapper taskMapper;
    private final I18nSiteTaskDetailMapper detailMapper;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;
    private final TemplateFileAccess templates;
    private final UiPackageService uiPackageService;
    private final OneShotService oneShotService;
    private final AiTranslationService translationService;
    private final ExecutorService executorService;

    private org.springframework.transaction.support.TransactionTemplate detailTransactions;
    private com.xinhongru.cms.search.PublicSearchProjectionService publicSearchProjectionService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.content.record.ContentReferenceValues referenceValues;

    private void validateReferenceModel(Long siteId, String config) {
        for (var field : com.xinhongru.content.model.ContentModelCodec.read(config)) {
            if (com.xinhongru.content.model.ContentReferences.applies(field)
                    && com.xinhongru.content.model.ContentFieldDefinition.enabled(
                            field.getIsOpen())) referenceValues.validateModel(siteId, field);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactionManager(
            org.springframework.transaction.PlatformTransactionManager manager) {
        detailTransactions =
                new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setPublicSearchProjectionService(
            com.xinhongru.cms.search.PublicSearchProjectionService service) {
        publicSearchProjectionService = service;
    }

    public I18nSiteTaskServiceImpl(
            I18nSiteTaskMapper taskMapper,
            I18nSiteTaskDetailMapper detailMapper,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            TemplateFileAccess templates,
            UiPackageService uiPackageService,
            OneShotService oneShotService,
            AiTranslationService translationService,
            @Qualifier("asyncServiceExecutor") ExecutorService executorService) {
        this.taskMapper = taskMapper;
        this.detailMapper = detailMapper;
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
        this.templates = templates;
        this.uiPackageService = uiPackageService;
        this.oneShotService = oneShotService;
        this.translationService = translationService;
        this.executorService = executorService;
    }

    @Override
    @Transactional
    public Long createTask(I18nSiteTaskEntity task, Map<Integer, Integer> sourceToTargetMenuId) {
        LocalDateTime now = LocalDateTime.now();
        if (task.getColumnStatus() == null) {
            task.setColumnStatus(I18nSiteTaskEntity.STATUS_SUCCESS);
        }
        if (task.getModelSyncStatus() == null) {
            task.setModelSyncStatus(I18nSiteTaskEntity.STATUS_PENDING);
        }
        if (task.getTemplateSyncStatus() == null) {
            task.setTemplateSyncStatus(I18nSiteTaskEntity.STATUS_PENDING);
        }
        if (task.getContentSyncStatus() == null) {
            task.setContentSyncStatus(I18nSiteTaskEntity.STATUS_PENDING);
        }
        if (task.getAssetSyncStatus() == null) {
            task.setAssetSyncStatus(I18nSiteTaskEntity.STATUS_PENDING);
        }
        task.setCreateTime(now).setDeleted(0);
        taskMapper.insert(task);

        if (sourceToTargetMenuId != null) {
            for (Map.Entry<Integer, Integer> entry : sourceToTargetMenuId.entrySet()) {
                detailMapper.insert(
                        buildDetail(task.getId(), entry.getKey(), entry.getValue(), now));
            }
        }
        return task.getId();
    }

    @Override
    public List<I18nSiteTaskEntity> listTasks(String keyword) {
        QueryWrapper<I18nSiteTaskEntity> qw = new QueryWrapper<>();
        qw.eq("deleted", 0);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("source_site_name", keyword).or().like("target_site_name", keyword));
        }
        qw.orderByDesc("id");
        return taskMapper.selectList(qw);
    }

    @Override
    public I18nSiteTaskEntity getTask(Long taskId) {
        return taskId == null ? null : taskMapper.selectById(taskId);
    }

    @Override
    public List<I18nSiteTaskDetailEntity> listDetails(Long taskId) {
        if (taskId == null) {
            return new ArrayList<>();
        }
        return detailMapper.selectList(
                new QueryWrapper<I18nSiteTaskDetailEntity>()
                        .eq("task_id", taskId)
                        .orderByAsc("id"));
    }

    @Override
    public List<I18nSiteTaskDetailView> listDetailViews(Long taskId) {
        List<I18nSiteTaskDetailEntity> details = listDetails(taskId);
        if (details.isEmpty()) {
            return new ArrayList<>();
        }
        I18nSiteTaskEntity task = taskMapper.selectById(taskId);
        Map<Integer, OneshotEntity> sourceMenus =
                menuIndex(task == null ? null : task.getSourceSiteId());
        Map<Integer, OneshotEntity> targetMenus =
                menuIndex(task == null ? null : task.getTargetSiteId());

        List<I18nSiteTaskDetailView> views = new ArrayList<>(details.size());
        // 与栏目导航取同一份顺序：导航用 getAllMenuForDropdownList 递归出的节点序列，
        // 这里按该序列的下标排序，保证树节点先后顺序与 /admin/v2/cms/menus 的栏目导航完全一致；
        // 未被该序列覆盖的栏目（如父级缺失）回退为 sort asc, 栏目id asc
        Map<Integer, Integer> navOrder =
                navOrderIndex(task == null ? null : task.getSourceSiteId());
        Map<Long, Integer> sourceSortById = new HashMap<>();
        for (I18nSiteTaskDetailEntity d : details) {
            OneshotEntity source = lookupMenu(sourceMenus, d.getSourceMenuId());
            OneshotEntity target = lookupMenu(targetMenus, d.getTargetMenuId());
            // 上级ID 直接取栏目自身的 parent_id：明细是快照，父栏目即使已删除（解析不到实体）
            // 也要给出父级ID，否则该行在前端会被当成顶层节点、脱离上级
            Integer sourceParentId = source == null ? null : source.getParentId();
            Integer targetParentId = target == null ? null : target.getParentId();
            OneshotEntity sourceParent =
                    sourceParentId == null ? null : lookupMenu(sourceMenus, sourceParentId);
            OneshotEntity targetParent =
                    targetParentId == null ? null : lookupMenu(targetMenus, targetParentId);
            sourceSortById.put(d.getId(), source == null ? Integer.MAX_VALUE : source.getSort());

            views.add(
                    new I18nSiteTaskDetailView(
                            d.getId(),
                            d.getTaskId(),
                            d.getSourceMenuId(),
                            bizCode(source),
                            nameOf(d.getSourceMenuName(), source),
                            typeOf(d.getSourceMenuType(), source),
                            sourceParentId,
                            sourceParent == null ? null : sourceParent.getClassName(),
                            d.getSourceModelJson(),
                            templateOf(source, true),
                            templateOf(source, false),
                            d.getSourceContentCount(),
                            d.getTargetMenuId(),
                            bizCode(target),
                            nameOf(d.getTargetMenuName(), target),
                            typeOf(d.getTargetMenuType(), target),
                            targetParentId,
                            targetParent == null ? null : targetParent.getClassName(),
                            d.getTargetModelJson(),
                            templateOf(target, true),
                            templateOf(target, false),
                            d.getTargetContentCount(),
                            d.getModelSynced(),
                            d.getTemplateSynced(),
                            d.getContentSynced(),
                            d.getSyncNote(),
                            d.getSyncTime()));
        }
        views.sort(
                (a, b) -> {
                    int byNav =
                            Integer.compare(
                                    navIndex(navOrder, a.sourceMenuId()),
                                    navIndex(navOrder, b.sourceMenuId()));
                    if (byNav != 0) {
                        return byNav;
                    }
                    int bySort =
                            Integer.compare(
                                    sourceSortById.getOrDefault(a.id(), Integer.MAX_VALUE),
                                    sourceSortById.getOrDefault(b.id(), Integer.MAX_VALUE));
                    if (bySort != 0) {
                        return bySort;
                    }
                    return Integer.compare(
                            menuIdOrMax(a.sourceMenuId()), menuIdOrMax(b.sourceMenuId()));
                });
        return views;
    }

    /** 栏目导航的节点顺序（对指定站点取 getAllMenuForDropdownList 的递归序列）下标 */
    private Map<Integer, Integer> navOrderIndex(Long siteId) {
        if (siteId == null) {
            return Map.of();
        }
        List<OneshotEntity> ordered =
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), siteId);
        if (ordered == null || ordered.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            index.put(ordered.get(i).getId(), i);
        }
        return index;
    }

    private int navIndex(Map<Integer, Integer> navOrder, Integer menuId) {
        if (menuId == null) {
            return Integer.MAX_VALUE;
        }
        Integer index = navOrder.get(menuId);
        return index == null ? Integer.MAX_VALUE : index;
    }

    private int menuIdOrMax(Integer menuId) {
        return menuId == null ? Integer.MAX_VALUE : menuId;
    }

    /** 站点栏目索引：一次性取全站栏目，避免逐条回表查上级 */
    private Map<Integer, OneshotEntity> menuIndex(Long siteId) {
        if (siteId == null) {
            return Map.of();
        }
        List<OneshotEntity> menus = oneShotMapper.selectBySiteProjectId(siteId);
        if (menus == null || menus.isEmpty()) {
            return Map.of();
        }
        Map<Integer, OneshotEntity> index = new HashMap<>();
        for (OneshotEntity menu : menus) {
            index.put(menu.getId(), menu);
        }
        return index;
    }

    /** parentId 为 0 表示站点根栏目，无上级 */
    private OneshotEntity lookupMenu(Map<Integer, OneshotEntity> index, Integer menuId) {
        if (menuId == null || menuId == 0) {
            return null;
        }
        OneshotEntity cached = index.get(menuId);
        return cached != null ? cached : oneShotMapper.getModelById(menuId);
    }

    private String bizCode(OneshotEntity menu) {
        return menu == null ? null : menu.getBizCode();
    }

    private String nameOf(String storedName, OneshotEntity menu) {
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        return menu == null ? null : menu.getClassName();
    }

    /** 栏目类型以栏目当前值为准（同步即按当前值复制），栏目已删除时退回明细快照 */
    private Integer typeOf(Integer storedType, OneshotEntity menu) {
        if (menu != null && menu.getPageType() != null) {
            return menu.getPageType();
        }
        return storedType;
    }

    private String templateOf(OneshotEntity menu, boolean list) {
        if (menu == null) {
            return null;
        }
        return list ? menu.getListPageTemplate() : menu.getDetailPageTemplate();
    }

    /** 进行中的同步（taskId -> 停止标记），用于协作式取消 */
    private final Map<Long, AtomicBoolean> runningSyncs = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public void syncDetails(
            Long taskId,
            Map<Long, Set<String>> detailDimensions,
            SyncOptions options,
            Long operatorId,
            String operatorName) {
        I18nSiteTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (detailDimensions == null || detailDimensions.isEmpty()) {
            throw new IllegalArgumentException("没有可同步的明细");
        }
        SyncOptions syncOptions = options == null ? SyncOptions.defaults() : options;

        Map<Long, Set<String>> targets = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<String>> entry : detailDimensions.entrySet()) {
            if (entry.getKey() != null) {
                targets.put(entry.getKey(), normalizeDims(entry.getValue()));
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("没有可同步的明细");
        }

        // 需要翻译时，同步前先确认 AI 服务可用（不可用则直接报错，不进入同步）
        AiTranslationService.Session translation = null;
        if (syncOptions.needsTranslation()) {
            String unavailable = translationService.ensureAvailable();
            if (unavailable != null) {
                throw new IllegalStateException(unavailable);
            }
            translation = translationService.openSession(task.getTargetLanguage());
        }
        final AiTranslationService.Session translationSession = translation;

        // 先落库把本次涉及的维度标记为进行中，便于前端轮询；实际同步在后台线程异步执行
        Set<String> allDims = new LinkedHashSet<>();
        targets.values().forEach(allDims::addAll);
        markRunning(task, allDims);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        runningSyncs.put(task.getId(), cancelled);
        executorService.submit(
                () -> {
                    try {
                        for (Map.Entry<Long, Set<String>> entry : targets.entrySet()) {
                            if (cancelled.get()) {
                                LOG.info("多语言站点同步已被停止: taskId={}", taskId);
                                break;
                            }
                            Long detailId = entry.getKey();
                            try {
                                I18nSiteTaskDetailEntity detail = detailMapper.selectById(detailId);
                                if (detail == null || !task.getId().equals(detail.getTaskId())) {
                                    continue;
                                }
                                detailTransactions.executeWithoutResult(
                                        status ->
                                                syncOne(
                                                        task,
                                                        detail,
                                                        entry.getValue(),
                                                        syncOptions,
                                                        translationSession,
                                                        cancelled));
                            } catch (Exception e) {
                                LOG.error(
                                        "多语言站点任务明细同步失败: taskId={}, detailId={}",
                                        taskId,
                                        detailId,
                                        e);
                            }
                        }
                        try {
                            recountStatuses(task);
                        } catch (Exception e) {
                            LOG.error("多语言站点任务状态回写失败: taskId={}", taskId, e);
                        }
                    } finally {
                        runningSyncs.remove(task.getId(), cancelled);
                        if (translationSession != null) {
                            translationSession.close();
                        }
                    }
                });
    }

    @Override
    public String ensureTranslationAvailable() {
        return translationService.ensureAvailable();
    }

    @Override
    public void syncAssets(Long taskId) {
        I18nSiteTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (task.getSourceSiteId() == null || task.getTargetSiteId() == null) {
            throw new IllegalStateException("任务缺少原/目标站点，无法同步静态资源");
        }
        // 先落库标记为进行中，实际复制在后台线程异步执行
        task.setAssetSyncStatus(I18nSiteTaskEntity.STATUS_RUNNING);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        Long sourceSiteId = task.getSourceSiteId();
        Long targetSiteId = task.getTargetSiteId();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        runningSyncs.put(task.getId(), cancelled);
        executorService.submit(
                () -> {
                    String result = I18nSiteTaskEntity.STATUS_SUCCESS;
                    try {
                        uiPackageService.copyFromSite(sourceSiteId, targetSiteId, cancelled::get);
                    } catch (Exception e) {
                        if (cancelled.get()) {
                            LOG.info("多语言站点静态资源同步已被停止: taskId={}", taskId);
                            result = I18nSiteTaskEntity.STATUS_PENDING;
                        } else {
                            LOG.error("多语言站点静态资源同步失败: taskId={}", taskId, e);
                            result = I18nSiteTaskEntity.STATUS_FAILED;
                        }
                    }
                    try {
                        I18nSiteTaskEntity latest = taskMapper.selectById(taskId);
                        if (latest != null) {
                            latest.setAssetSyncStatus(result);
                            latest.setUpdateTime(LocalDateTime.now());
                            taskMapper.updateById(latest);
                        }
                    } catch (Exception e) {
                        LOG.error("多语言站点静态资源状态回写失败: taskId={}", taskId, e);
                    } finally {
                        runningSyncs.remove(taskId, cancelled);
                    }
                });
    }

    @Override
    public boolean stopSync(Long taskId) {
        if (taskId == null) {
            return false;
        }
        AtomicBoolean flag = runningSyncs.get(taskId);
        if (flag != null) {
            flag.set(true);
            return true;
        }
        // 无在跑线程（例如服务重启后遗留的 RUNNING）：直接按明细回写状态，避免界面一直显示进行中
        I18nSiteTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        boolean stuck =
                I18nSiteTaskEntity.STATUS_RUNNING.equals(task.getModelSyncStatus())
                        || I18nSiteTaskEntity.STATUS_RUNNING.equals(task.getTemplateSyncStatus())
                        || I18nSiteTaskEntity.STATUS_RUNNING.equals(task.getContentSyncStatus())
                        || I18nSiteTaskEntity.STATUS_RUNNING.equals(task.getAssetSyncStatus());
        if (!stuck) {
            return false;
        }
        if (I18nSiteTaskEntity.STATUS_RUNNING.equals(task.getAssetSyncStatus())) {
            task.setAssetSyncStatus(I18nSiteTaskEntity.STATUS_PENDING);
        }
        recountStatuses(task);
        return true;
    }

    /** 将本次要同步的维度标记为进行中 */
    private void markRunning(I18nSiteTaskEntity task, Set<String> dims) {
        if (dims.contains(DIM_MODEL)) {
            task.setModelSyncStatus(I18nSiteTaskEntity.STATUS_RUNNING);
        }
        if (dims.contains(DIM_TEMPLATE)) {
            task.setTemplateSyncStatus(I18nSiteTaskEntity.STATUS_RUNNING);
        }
        if (dims.contains(DIM_CONTENT)) {
            task.setContentSyncStatus(I18nSiteTaskEntity.STATUS_RUNNING);
        }
        taskMapper.updateById(task);
    }

    // ---------------- 内部实现 ----------------

    private I18nSiteTaskDetailEntity buildDetail(
            Long taskId, Integer sourceMenuId, Integer targetMenuId, LocalDateTime now) {
        I18nSiteTaskDetailEntity detail =
                new I18nSiteTaskDetailEntity()
                        .setTaskId(taskId)
                        .setSourceMenuId(sourceMenuId)
                        .setTargetMenuId(targetMenuId)
                        .setModelSynced(false)
                        .setTemplateSynced(false)
                        .setContentSynced(false)
                        .setCreateTime(now);

        OneshotEntity source =
                sourceMenuId == null ? null : oneShotMapper.getModelById(sourceMenuId);
        if (source != null) {
            detail.setSourceMenuName(source.getClassName())
                    .setSourceMenuType(source.getPageType())
                    .setSourceModelJson(modelJson(source))
                    .setSourceTemplate(templateSummary(source))
                    .setSourceContentCount(contentCount(source));
        }
        OneshotEntity target =
                targetMenuId == null ? null : oneShotMapper.getModelById(targetMenuId);
        if (target != null) {
            detail.setTargetMenuName(target.getClassName())
                    .setTargetMenuType(target.getPageType())
                    .setTargetModelJson(modelJson(target))
                    .setTargetTemplate(templateSummary(target))
                    .setTargetContentCount(contentCount(target));
        }
        return detail;
    }

    /** 内容数量：单页栏目内容存于 t_oneshot 自身，固定为 1；列表栏目取 t_content 计数 */
    private int contentCount(OneshotEntity menu) {
        if (menu == null) {
            return 0;
        }
        if (isSinglePage(menu)) {
            return 1;
        }
        return contentMapper.countByClassId(menu.getId());
    }

    private boolean isSinglePage(OneshotEntity menu) {
        return menu != null && Integer.valueOf(0).equals(menu.getPageType());
    }

    private void syncOne(
            I18nSiteTaskEntity task,
            I18nSiteTaskDetailEntity detail,
            Set<String> dims,
            SyncOptions options,
            AiTranslationService.Session translation,
            AtomicBoolean cancelled) {
        Integer targetMenuId = detail.getTargetMenuId();
        if (targetMenuId == null) {
            throw new IllegalStateException("明细 " + detail.getId() + " 缺少目标栏目");
        }
        OneshotEntity source =
                detail.getSourceMenuId() == null
                        ? null
                        : oneShotMapper.getModelById(detail.getSourceMenuId());
        OneshotEntity target =
                oneShotMapper.lockFieldConfiguration(
                        task.getTargetSiteId(), targetMenuId.longValue());
        if (source == null || target == null) {
            throw new IllegalStateException("源或目标栏目不存在，无法同步");
        }

        if (!Objects.equals(source.getSiteProjectId(), task.getSourceSiteId())
                || !Objects.equals(target.getSiteProjectId(), task.getTargetSiteId()))
            throw new IllegalArgumentException("同步栏目不属于任务源或目标站点");

        if (dims.contains(DIM_MODEL)) {
            String menuField = source.getMenuField();
            String modelField = source.getModelField();
            String modelList = source.getModelList();
            String modelSearch = source.getModelSearch();
            if (options.translateModel() && translation != null) {
                menuField = translateFieldBusinessNames(menuField, translation);
                modelField = translateFieldBusinessNames(modelField, translation);
                modelList = translateFieldBusinessNames(modelList, translation);
                modelSearch = translateFieldBusinessNames(modelSearch, translation);
            }
            menuField =
                    com.xinhongru.content.model.ContentModelCodec.write(
                            com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                                    com.xinhongru.content.model.ContentModelCodec.read(
                                            target.getMenuField()),
                                    com.xinhongru.content.model.ContentModelCodec.read(menuField)));
            modelField =
                    com.xinhongru.content.model.ContentModelCodec.write(
                            com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                                    com.xinhongru.content.model.ContentModelCodec.read(
                                            target.getModelField()),
                                    com.xinhongru.content.model.ContentModelCodec.read(
                                            modelField)));
            validateReferenceModel(task.getTargetSiteId(), menuField);
            validateReferenceModel(task.getTargetSiteId(), modelField);
            oneShotMapper.updateFieldConfig(
                    targetMenuId, menuField, modelField, modelList, modelSearch);
            // 栏目类型（单页/列表）决定字段设置的取数口径，必须随数据模型一起同步
            EditOneshotDto typePatch = new EditOneshotDto();
            typePatch.setId(targetMenuId);
            typePatch.setPageType(source.getPageType());
            oneShotMapper.updateEdit(typePatch);
            detail.setTargetMenuType(source.getPageType());
            detail.setModelSynced(true);
            detail.setTargetModelJson(modelJson(source));
            publicSearchProjectionService.rebuildMenu(task.getTargetSiteId(), targetMenuId);
            target =
                    oneShotMapper.lockFieldConfiguration(
                            task.getTargetSiteId(), targetMenuId.longValue());
            if (target == null
                    || !Objects.equals(target.getSiteProjectId(), task.getTargetSiteId()))
                throw new IllegalArgumentException("同步目标栏目已变化");
        }
        if (dims.contains(DIM_TEMPLATE)) {
            detail.setTargetTemplate(copyTemplates(task, source, target));
            detail.setTemplateSynced(true);
        }
        if (dims.contains(DIM_CONTENT)) {
            if (isSinglePage(source)) {
                // 单页栏目内容存于 t_oneshot 自身，按字段配置复制（无多行内容，无需分页/取消）
                copySinglePageContent(source, target, options, translation);
                detail.setTargetContentCount(1);
            } else {
                copyContents(task, source, target, options, translation, cancelled);
                detail.setTargetContentCount(contentMapper.countByClassId(targetMenuId));
            }
            // 中途停止则内容并未整体覆盖，保持未同步状态，便于重新执行
            if (cancelled == null || !cancelled.get()) {
                detail.setContentSynced(true);
            }
        }

        detail.setSyncTime(LocalDateTime.now());
        detail.setSyncNote(buildSyncNote(detail));
        detailMapper.updateById(detail);
    }

    /** 模版同步：把源栏目模版内容复制到目标栏目按栏目树目录计算出的路径，并回写目标栏目模版字段 */
    private String copyTemplates(
            I18nSiteTaskEntity task, OneshotEntity source, OneshotEntity target) {
        String dir = templates.buildContentTemplateDir(target.getId().longValue());
        String targetListPath = joinPath(dir, "index.html");
        String targetDetailPath = joinPath(dir, "details.html");

        boolean copiedList =
                copyOneTemplate(
                        task.getSourceSiteId(),
                        task.getTargetSiteId(),
                        source.getListPageTemplate(),
                        targetListPath);
        boolean copiedDetail =
                copyOneTemplate(
                        task.getSourceSiteId(),
                        task.getTargetSiteId(),
                        source.getDetailPageTemplate(),
                        targetDetailPath);

        EditOneshotDto patch = new EditOneshotDto();
        patch.setId(target.getId());
        if (copiedList) {
            patch.setListPageTemplate(targetListPath);
        }
        if (copiedDetail) {
            patch.setDetailPageTemplate(targetDetailPath);
        }
        if (copiedList || copiedDetail) {
            oneShotMapper.updateEdit(patch);
        }

        return "list="
                + (copiedList ? targetListPath : nvl(target.getListPageTemplate()))
                + ", detail="
                + (copiedDetail ? targetDetailPath : nvl(target.getDetailPageTemplate()));
    }

    private boolean copyOneTemplate(
            Long sourceSiteId, Long targetSiteId, String sourcePath, String targetPath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return false;
        }
        Optional<String> content = templates.getTemplateContent(sourceSiteId, sourcePath);
        if (content.isEmpty()) {
            return false;
        }
        templates.saveTemplateContent(targetSiteId, targetPath, content.get());
        return true;
    }

    /**
     * 内容同步。
     *
     * <p>覆盖：先逻辑删除目标栏目旧内容，再整体复制。增量：按 (sourceSiteId, sourceContentId) 判定目标是否已存在该源内容，
     * 命中时按选项跳过或更新（更新写回原目标记录），未命中则新增。需要翻译时，逐条内容的文本字段先经 AI 翻译再写入。
     */
    private void copyContents(
            I18nSiteTaskEntity task,
            OneshotEntity source,
            OneshotEntity target,
            SyncOptions options,
            AiTranslationService.Session translation,
            AtomicBoolean cancelled) {
        Integer targetMenuId = target.getId();
        List<ContentEntity> sourceContents =
                new ArrayList<>(contentMapper.listByClassId(source.getId()));
        sourceContents.sort(java.util.Comparator.comparing(ContentEntity::getId));
        boolean increment = CONTENT_MODE_INCREMENT.equals(options.contentMode());
        boolean updateExisting = options.updateExisting();
        // 目标已存在内容按 (sourceSiteId, sourceContentId) 建索引，用于增量判定
        Map<String, ContentEntity> existingBySource = new HashMap<>();
        if (increment) {
            var existingRows = new ArrayList<>(contentMapper.listByClassId(targetMenuId));
            existingRows.sort(java.util.Comparator.comparing(ContentEntity::getId));
            for (ContentEntity candidate : existingRows) {
                ContentEntity existing =
                        contentMapper.lockActiveArticleInSiteMenu(
                                task.getTargetSiteId(), targetMenuId, candidate.getId());
                if (existing == null) throw new IllegalArgumentException("同步目标内容已变化");
                String key = sourceKey(existing.getSourceSiteId(), existing.getSourceContentId());
                if (key != null) {
                    existingBySource.put(key, existing);
                }
            }
        } else {
            contentMapper.softDeleteByClassId(targetMenuId);
        }

        int count = 0;
        for (ContentEntity item : sourceContents) {
            if (cancelled != null && cancelled.get()) {
                LOG.info("多语言站点内容同步已停止: targetMenuId={}, 已复制={}", targetMenuId, count);
                break;
            }
            String key = sourceKey(task.getSourceSiteId(), item.getId());
            ContentEntity existing = increment && key != null ? existingBySource.get(key) : null;
            if (existing != null && !updateExisting) {
                continue;
            }
            ContentEntity copy = new ContentEntity();
            BeanUtils.copyProperties(
                    item, copy, "id", "classid", "siteProjectId", "createTime", "publicSearchText");
            copy.setId(null);
            copy.setClassid(targetMenuId);
            copy.setSiteProjectId(task.getTargetSiteId());
            copy.setCreateTime(LocalDateTime.now());
            // 记录来源，供后续增量按 (sourceSiteId, sourceContentId) 判定
            copy.setSourceSiteId(task.getSourceSiteId());
            copy.setSourceContentId(item.getId());
            if (options.translateContent() && translation != null) {
                translateContentFields(copy, translation);
            }
            var referenceDefinitions =
                    com.xinhongru.content.model.ContentModelCodec.read(target.getModelField());
            if (referenceDefinitions.stream()
                    .anyMatch(com.xinhongru.content.model.ContentReferences::applies))
                copy.setExtensionFields(
                        referenceValues.validateStored(
                                task.getTargetSiteId(),
                                copy.getExtensionFields() != null
                                        ? copy.getExtensionFields()
                                        : existing == null ? null : existing.getExtensionFields(),
                                referenceDefinitions));
            com.xinhongru.content.model.CounterNumbers.validateStored(
                    copy.getExtensionFields() != null
                            ? copy.getExtensionFields()
                            : existing == null ? null : existing.getExtensionFields(),
                    com.xinhongru.content.model.ContentModelCodec.read(target.getModelField()));
            com.xinhongru.content.model.ContentDateTimes.validateStored(
                    copy.getExtensionFields() != null
                            ? copy.getExtensionFields()
                            : existing == null ? null : existing.getExtensionFields(),
                    referenceDefinitions);
            if (existing != null) {
                // 更新：写回原目标记录，保留其 id / 栏目 / 站点 / 创建时间
                copy.setId(existing.getId());
                copy.setClassid(existing.getClassid());
                copy.setSiteProjectId(task.getTargetSiteId());
                copy.setCreateTime(existing.getCreateTime());
                copy.setPublicSearchText(
                        com.xinhongru.cms.search.PublicSearchText.forContent(
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        target.getModelField()),
                                copy,
                                existing));
                contentMapper.updateArticleById(copy);
            } else {
                copy.setPublicSearchText(
                        com.xinhongru.cms.search.PublicSearchText.forContent(
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        target.getModelField()),
                                copy,
                                null));
                contentMapper.addArticle(copy);
                // 需求：新站点内容沿用原站点的业务编码；源内容没有编码时才退回取自身 id
                if (copy.getContentCode() == null || copy.getContentCode().isBlank()) {
                    if (copy.getId() != null) {
                        contentMapper.assignContentCodeToOwnId(copy.getId());
                    }
                }
            }
            count++;
        }
    }

    /** 跨站点内容匹配键：源站点ID + 源内容ID，缺一不可 */
    private String sourceKey(Long sourceSiteId, Integer sourceContentId) {
        if (sourceSiteId == null || sourceContentId == null) {
            return null;
        }
        return sourceSiteId + ":" + sourceContentId;
    }

    /** 单页内容字段配置项 */
    private record SinglePageField(String fieldName, String fieldType) {}

    /** 可翻译的字段类型（单行/多行/富文本） */
    private static final Set<String> TRANSLATABLE_FIELD_TYPES =
            Set.of("SingleLine", "MultiLine", "Text", "Textarea", "Editor", "RichText");

    /**
     * 单页内容同步：单页栏目（pageType=0）的内容直接存于 t_oneshot 自身， 按源栏目字段配置逐字段复制到目标栏目。
     *
     * <p>增量：目标栏目已从同源栏目同步过（sourceSiteId + sourceMenuId 命中）时，按选项跳过或更新；覆盖：始终按源字段整体盖写。 文本类字段按内容翻译设置经 AI
     * 翻译后写入。
     */
    private void copySinglePageContent(
            OneshotEntity source,
            OneshotEntity target,
            SyncOptions options,
            AiTranslationService.Session translation) {
        boolean increment = CONTENT_MODE_INCREMENT.equals(options.contentMode());
        boolean alreadySynced =
                target.getSourceMenuId() != null
                        && target.getSourceMenuId().equals(source.getId())
                        && Objects.equals(target.getSourceSiteId(), source.getSiteProjectId());
        if (increment && alreadySynced && !options.updateExisting()) {
            // 跳过：目标已存在同源单页内容，且选择不更新
            return;
        }
        List<SinglePageField> fields = parseSinglePageFields(source.getMenuField());
        if (fields.isEmpty()) {
            return;
        }
        Map<String, String> translatedByField = new HashMap<>();
        if (options.translateContent() && translation != null) {
            List<String> names = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (SinglePageField field : fields) {
                if (!isTranslatableField(field)) {
                    continue;
                }
                String value = readOneshotString(source, field.fieldName());
                if (value != null && !value.isBlank()) {
                    names.add(field.fieldName());
                    texts.add(value);
                }
            }
            if (!texts.isEmpty()) {
                List<String> translated = translation.translate(texts);
                for (int i = 0; i < names.size(); i++) {
                    translatedByField.put(names.get(i), translated.get(i));
                }
            }
        }
        OneshotEntity patch = new OneshotEntity();
        patch.setId(target.getId());
        // 复用 update 的动态更新只回写已置值的内容列；该语句在 createTime 为空时会写入 now()，故带上目标原值
        patch.setCreateTime(target.getCreateTime());
        boolean hasValue = false;
        for (SinglePageField field : fields) {
            String value = translatedByField.get(field.fieldName());
            if (value == null) {
                value = readOneshotString(source, field.fieldName());
            }
            if (writeOneshotString(patch, field.fieldName(), value)) {
                hasValue = true;
            }
        }
        if (hasValue) {
            patch.setPublicSearchText(
                    com.xinhongru.cms.search.PublicSearchText.forSinglePage(
                            com.xinhongru.content.model.ContentModelCodec.read(
                                    target.getMenuField()),
                            patch,
                            target));
            oneShotMapper.update(patch);
        }
        // 记录单页同步来源，供后续增量按 (sourceSiteId, sourceMenuId) 判定
        oneShotMapper.updateSourceMenu(target.getId(), source.getSiteProjectId(), source.getId());
    }

    /** 解析单页字段配置 JSON（数组），按出现顺序取去重后的 fieldName 与 fieldType */
    private List<SinglePageField> parseSinglePageFields(String json) {
        List<SinglePageField> fields = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return fields;
        }
        String trimmed = json.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) {
            return fields;
        }
        JSONArray array;
        try {
            array = JSON.parseArray(trimmed);
        } catch (Exception e) {
            LOG.warn("单页字段配置 JSON 解析失败，跳过单页内容同步: {}", e.getMessage());
            return fields;
        }
        if (array == null) {
            return fields;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String fieldName = item.getString("fieldName");
            if (fieldName != null && !fieldName.isBlank() && seen.add(fieldName)) {
                fields.add(new SinglePageField(fieldName, item.getString("fieldType")));
            }
        }
        return fields;
    }

    private boolean isTranslatableField(SinglePageField field) {
        String fieldType = field.fieldType();
        if (fieldType != null && !fieldType.isBlank()) {
            return TRANSLATABLE_FIELD_TYPES.contains(fieldType);
        }
        // 字段类型缺失时按字段名兜底：singleLine*/note*/content* 视为文本
        String name = field.fieldName().toLowerCase();
        return name.startsWith("singleline")
                || name.startsWith("note")
                || name.startsWith("content");
    }

    private String readOneshotString(OneshotEntity menu, String fieldName) {
        try {
            Field field = EntityFields.find(OneshotEntity.class, fieldName);
            field.setAccessible(true);
            Object value = field.get(menu);
            return value instanceof String text ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入单页内容字段，仅接受字符串列；返回是否写入成功 */
    private boolean writeOneshotString(OneshotEntity menu, String fieldName, String value) {
        if (value == null) {
            return false;
        }
        try {
            Field field = EntityFields.find(OneshotEntity.class, fieldName);
            if (!field.getType().equals(String.class)) {
                return false;
            }
            field.setAccessible(true);
            field.set(menu, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 内容里可翻译的文本字段：标题、单行文本、多行文本、富文本 */
    private static final List<String> TRANSLATABLE_CONTENT_FIELDS =
            buildTranslatableContentFields();

    private static List<String> buildTranslatableContentFields() {
        List<String> fields = new ArrayList<>();
        fields.add("title");
        for (int i = 1; i <= 20; i++) {
            fields.add("singleLine" + i);
        }
        for (int i = 1; i <= 15; i++) {
            fields.add("note" + i);
        }
        for (int i = 1; i <= 6; i++) {
            fields.add("content" + i);
        }
        return List.copyOf(fields);
    }

    /** 翻译内容实体的文本字段（原地修改） */
    private void translateContentFields(
            ContentEntity content, AiTranslationService.Session translation) {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (String name : TRANSLATABLE_CONTENT_FIELDS) {
            String value = readStringField(content, name);
            if (value != null && !value.isBlank()) {
                names.add(name);
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return;
        }
        List<String> translated = translation.translate(values);
        for (int i = 0; i < names.size(); i++) {
            writeStringField(content, names.get(i), translated.get(i));
        }
    }

    private String readStringField(ContentEntity content, String name) {
        try {
            Field field = EntityFields.find(ContentEntity.class, name);
            field.setAccessible(true);
            Object value = field.get(content);
            return value instanceof String text ? text : null;
        } catch (Exception e) {
            LOG.debug("读取内容字段失败（忽略）: {}", name);
            return null;
        }
    }

    private void writeStringField(ContentEntity content, String name, String value) {
        try {
            Field field = EntityFields.find(ContentEntity.class, name);
            field.setAccessible(true);
            field.set(content, value);
        } catch (Exception e) {
            LOG.warn("写入翻译后的内容字段失败: {}", name, e);
        }
    }

    /** 翻译字段配置 JSON（数组）里的业务名称 title，不改动字段标题 fromName */
    private String translateFieldBusinessNames(
            String json, AiTranslationService.Session translation) {
        if (json == null || json.isBlank()) {
            return json;
        }
        String trimmed = json.trim();
        if ("{}".equals(trimmed) || "[]".equals(trimmed)) {
            return json;
        }
        JSONArray array;
        try {
            array = JSON.parseArray(trimmed);
        } catch (Exception e) {
            LOG.warn("字段配置 JSON 解析失败，跳过业务名称翻译: {}", e.getMessage());
            return json;
        }
        if (array == null || array.isEmpty()) {
            return json;
        }
        List<String> texts = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = item.getString("title");
            if (title != null && !title.isBlank()) {
                texts.add(title);
                indexes.add(i);
            }
        }
        if (texts.isEmpty()) {
            return json;
        }
        List<String> translated = translation.translate(texts);
        for (int i = 0; i < indexes.size(); i++) {
            JSONObject item = array.getJSONObject(indexes.get(i));
            if (item != null) {
                item.put("title", translated.get(i));
            }
        }
        return JSON.toJSONString(array);
    }

    private void recountStatuses(I18nSiteTaskEntity task) {
        List<I18nSiteTaskDetailEntity> all = listDetails(task.getId());
        task.setModelSyncStatus(summarize(all, I18nSiteTaskDetailEntity::getModelSynced));
        task.setTemplateSyncStatus(summarize(all, I18nSiteTaskDetailEntity::getTemplateSynced));
        task.setContentSyncStatus(summarize(all, I18nSiteTaskDetailEntity::getContentSynced));
        taskMapper.updateById(task);
    }

    private String summarize(
            List<I18nSiteTaskDetailEntity> all,
            Function<I18nSiteTaskDetailEntity, Boolean> getter) {
        if (all.isEmpty()) {
            return I18nSiteTaskEntity.STATUS_PENDING;
        }
        long done = all.stream().filter(d -> Boolean.TRUE.equals(getter.apply(d))).count();
        if (done == 0) {
            return I18nSiteTaskEntity.STATUS_PENDING;
        }
        return done == all.size()
                ? I18nSiteTaskEntity.STATUS_SUCCESS
                : I18nSiteTaskEntity.STATUS_PARTIAL;
    }

    private Set<String> normalizeDims(Set<String> dimensions) {
        Set<String> dims = new LinkedHashSet<>();
        if (dimensions != null) {
            for (String dim : dimensions) {
                if (dim != null && !dim.isBlank()) {
                    dims.add(dim.trim());
                }
            }
        }
        if (dims.isEmpty()) {
            dims.addAll(ALL_DIMS);
        }
        return dims;
    }

    private String buildSyncNote(I18nSiteTaskDetailEntity detail) {
        List<String> synced = new ArrayList<>();
        if (Boolean.TRUE.equals(detail.getModelSynced())) {
            synced.add("数据模型");
        }
        if (Boolean.TRUE.equals(detail.getTemplateSynced())) {
            synced.add("模版");
        }
        if (Boolean.TRUE.equals(detail.getContentSynced())) {
            synced.add("内容");
        }
        boolean full = synced.size() == ALL_DIMS.size();
        return (full ? "全部同步" : "部分同步") + "（已同步：" + String.join("、", synced) + "）";
    }

    private String modelJson(OneshotEntity menu) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("menuField", menu.getMenuField());
        map.put("modelField", menu.getModelField());
        map.put("modelList", menu.getModelList());
        map.put("modelSearch", menu.getModelSearch());
        return JSON.toJSONString(map);
    }

    private String templateSummary(OneshotEntity menu) {
        return "list="
                + nvl(menu.getListPageTemplate())
                + ", detail="
                + nvl(menu.getDetailPageTemplate());
    }

    private String joinPath(String dir, String fileName) {
        return dir == null || dir.isBlank() ? fileName : dir + "/" + fileName;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}

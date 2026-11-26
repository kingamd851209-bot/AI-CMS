package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.PlatformContentEntity;
import com.xinhongru.entity.PlatformOneshotEntity;
import com.xinhongru.entity.PlatformPushConfigEntity;
import com.xinhongru.entity.PlatformPushRecordEntity;
import com.xinhongru.entity.TargetContentEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.FormContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.PlatformContentMapper;
import com.xinhongru.mapper.PlatformOneshotMapper;
import com.xinhongru.mapper.PlatformPushConfigMapper;
import com.xinhongru.mapper.PlatformPushRecordMapper;
import com.xinhongru.mapper.TargetContentMapper;
import com.xinhongru.service.cmsPlatform.PlatformDataType;
import com.xinhongru.service.cmsPlatform.PlatformPushService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PlatformPushServiceImpl implements PlatformPushService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper JSON_OUT = new ObjectMapper();

    @Resource private com.xinhongru.service.cmsPlatform.FormConfigService formConfigService;
    @Resource private PlatformPushConfigMapper pushConfigMapper;
    @Resource private PlatformPushRecordMapper pushRecordMapper;
    @Resource private PlatformContentMapper platformContentMapper;
    @Resource private PlatformOneshotMapper platformOneshotMapper;
    @Resource private OneShotMapper oneShotMapper;
    @Resource private FormContentMapper formContentMapper;
    @Resource private TargetContentMapper targetContentMapper;
    @Resource private SiteProjectMapper aiSiteProjectMapper;
    @Resource private com.xinhongru.mapper.ContentMapper contentMapper;
    @Resource private com.xinhongru.permission.AdminPermissionService permissionService;
    @Resource private com.xinhongru.site.context.SiteContextService siteContextService;
    @Resource private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Resource
    private com.xinhongru.cms.search.PublicSearchProjectionService publicSearchProjectionService;

    /** 关联推送子内容时按同名列复制的字段：singleLineN / noteN / photoN / mediaN / fileN / contentN */
    private static final Pattern CONTENT_FIELD_PATTERN =
            Pattern.compile("^(singleLine|note|photo|media|file|content)\\d+$");

    /** 可写入目标内容的字段名（camel）白名单 */
    private static final Set<String> WRITABLE = new HashSet<>();

    static {
        for (Field f : EntityFields.all(TargetContentEntity.class)) {
            if (!Set.of("id", "siteProjectId", "publicSearchText", "classid", "status")
                    .contains(f.getName())) WRITABLE.add(f.getName());
        }
    }

    @Override
    public int push(
            String configType,
            Long pushConfigId,
            List<Integer> contentIds,
            UserEntity user,
            jakarta.servlet.http.HttpServletRequest request) {
        if (contentIds == null || contentIds.isEmpty()) {
            return 0;
        }
        PlatformPushConfigEntity cfg = pushConfigMapper.selectById(pushConfigId);
        if (cfg == null) {
            throw new IllegalArgumentException("推送配置不存在");
        }
        if (cfg.getConfigType() != null
                && !java.util.Objects.equals(cfg.getConfigType(), configType))
            throw new IllegalArgumentException("来源类型与推送配置不一致");
        Map<Integer, Integer> formSources = null;
        if ("form".equals(configType)) {
            formSources = requireFormSources(request, contentIds);
            if (cfg.getCategoryId() != null
                    && formSources.values().stream()
                            .anyMatch(
                                    category ->
                                            !java.util.Objects.equals(
                                                    category, cfg.getCategoryId())))
                throw unavailableSource();
        }
        var selectedIds = new HashSet<Integer>(contentIds);
        if (selectedIds.stream().anyMatch(id -> id == null || id <= 0)) throw unavailableSource();
        List<Map<String, Object>> sources = selectSources(configType, contentIds);
        var loadedIds = new HashSet<Integer>();
        for (var source : sources) {
            Integer sourceId = toInt(source.get("id"));
            if (!selectedIds.contains(sourceId) || !loadedIds.add(sourceId))
                throw unavailableSource();
        }
        if (!loadedIds.equals(selectedIds)) throw unavailableSource();
        if (formSources != null) {
            var returned = new HashSet<Integer>();
            for (Map<String, Object> source : sources) {
                Integer id = toInt(source.get("id"));
                Integer category = toInt(source.get("classid"));
                if (id == null
                        || !formSources.containsKey(id)
                        || !java.util.Objects.equals(formSources.get(id), category))
                    throw unavailableSource();
                requireFormCategory(request, category);
                returned.add(id);
            }
            if (!returned.equals(formSources.keySet())) throw unavailableSource();
        }
        if (!"form".equals(configType)) {
            PlatformDataType type = dataTypeOf(configType);
            for (Map<String, Object> source : sources) {
                if (!java.util.Objects.equals(toInt(source.get("dataType")), type.code()))
                    throw new IllegalArgumentException("选中内容不属于推送来源类型");
                permissionService.requirePlatformOperate(
                        request,
                        type.permissionType(),
                        toInt(source.get("classid")),
                        com.xinhongru.permission.PlatformPermission.OP_PUSH);
            }
        }
        List<Map<String, String>> mappings = parseMapping(cfg.getFieldMapping());
        Integer targetStatus = cfg.getTargetStatus() == null ? 0 : cfg.getTargetStatus();

        int pushed = 0;
        for (Map<String, Object> source : sources) {
            PlatformPushRecordEntity record =
                    new PlatformPushRecordEntity()
                            .setSourceContentId(toInt(source.get("id")))
                            .setContentType(configType)
                            .setSiteId(cfg.getSiteId())
                            .setColumnId(cfg.getSiteColumnId())
                            .setPushConfigId(cfg.getId())
                            .setTargetStatus(targetStatus)
                            .setFieldMapping(cfg.getFieldMapping())
                            .setFieldContent(toJson(source))
                            .setPushTime(LocalDateTime.now())
                            .setPushUserId(
                                    user == null || user.getId() == null
                                            ? null
                                            : user.getId().longValue())
                            .setPushUser(user == null ? null : user.getScreenName())
                            .setDeleted(0);
            try {
                new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                                transaction -> {
                                    PushContext pushCtx = new PushContext(cfg, targetStatus);
                                    prepareTargets(pushCtx, configType, source, request);
                                    Integer existingTargetId =
                                            findExistingTargetId(
                                                    configType,
                                                    record.getSourceContentId(),
                                                    cfg.getSiteId(),
                                                    cfg.getSiteColumnId());
                                    boolean updating =
                                            existingTargetId != null
                                                    && targetContentMapper.selectById(
                                                                    existingTargetId)
                                                            != null;

                                    TargetContentEntity target = new TargetContentEntity();
                                    target.setClassid(cfg.getSiteColumnId());
                                    target.setStatus(targetStatus);
                                    Object title = source.get("title");
                                    if (title != null) {
                                        target.setTitle(String.valueOf(title));
                                    }
                                    // 关联子类别 / 子类别内容 / 枚举：先分发到目标站点，再回填目标侧取值
                                    Map<String, Object> relationTargets =
                                            pushRelations(pushCtx, configType, source, user);
                                    for (Map<String, String> m : mappings) {
                                        String productField = toCamel(m.get("productField"));
                                        String siteField = toCamel(m.get("siteField"));
                                        if (siteField == null || !WRITABLE.contains(siteField)) {
                                            continue;
                                        }
                                        Object val =
                                                productField == null
                                                        ? null
                                                        : source.get(productField);
                                        if (productField != null
                                                && relationTargets.containsKey(productField)) {
                                            val = relationTargets.get(productField);
                                        }
                                        if (val == null) {
                                            continue; // 源无值则目标保持 null
                                        }
                                        Object targetVal = val;
                                        try {
                                            new BeanWrapperImpl(target)
                                                    .setPropertyValue(siteField, targetVal);
                                        } catch (Exception ignored) {
                                            // 字段类型不兼容时跳过该映射
                                        }
                                    }
                                    if (updating) {
                                        target.setId(existingTargetId);
                                        saveTarget(
                                                pushCtx,
                                                target,
                                                cfg.getSiteColumnId(),
                                                existingTargetId);
                                    } else {
                                        target.setCreateTime(LocalDateTime.now());
                                        saveTarget(pushCtx, target, cfg.getSiteColumnId(), null);
                                    }
                                    record.setTargetContentId(target.getId())
                                            .setPushType(updating ? "UPDATE" : "INSERT")
                                            .setPushStatus("SUCCESS");
                                    pushRecordMapper.insert(record);
                                });
                pushed++;
                continue;
            } catch (
                    com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                            ex) {
                throw ex;
            } catch (RuntimeException ex) {
                record.setTargetContentId(null)
                        .setPushStatus("FAILED")
                        .setErrorMessage(truncate(ex.getMessage()));
            }
            pushRecordMapper.insert(record);
        }
        return pushed;
    }

    @Override
    public List<PlatformPushRecordEntity> listRecords(
            String configType,
            Integer sourceContentId,
            jakarta.servlet.http.HttpServletRequest request) {
        if (sourceContentId == null) {
            return List.of();
        }
        if ("form".equals(configType)) requireFormSources(request, List.of(sourceContentId));
        else requirePlatformSource(request, configType, sourceContentId);
        return pushRecordMapper.selectList(
                new QueryWrapper<PlatformPushRecordEntity>()
                        .eq("content_type", configType)
                        .eq("source_content_id", sourceContentId)
                        .eq("deleted", 0)
                        .orderByDesc("id"));
    }

    private RuntimeException unavailableSource() {
        return com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                .forbidden("PUSH_SOURCE_ACCESS_DENIED", "推送来源不存在或无权访问");
    }

    private Map<Integer, Integer> requireFormSources(
            jakarta.servlet.http.HttpServletRequest request, List<Integer> contentIds) {
        var ids = new java.util.LinkedHashSet<Integer>(contentIds);
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw unavailableSource();
        List<com.xinhongru.entity.FormContentEntity> headers =
                formContentMapper.selectList(
                        new QueryWrapper<com.xinhongru.entity.FormContentEntity>()
                                .select("id", "class_id")
                                .in("id", ids));
        var categories = new java.util.LinkedHashMap<Integer, Integer>();
        for (var row : headers) {
            if (row == null
                    || !ids.contains(row.getId())
                    || row.getClassid() == null
                    || row.getClassid() <= 0) throw unavailableSource();
            requireFormCategory(request, row.getClassid());
            categories.put(row.getId(), row.getClassid());
        }
        if (!categories.keySet().equals(ids)) throw unavailableSource();
        return categories;
    }

    private void requireFormCategory(
            jakarta.servlet.http.HttpServletRequest request, Integer categoryId) {
        var category = categoryId == null ? null : formConfigService.getById(categoryId);
        if (category == null
                || Boolean.TRUE.equals(category.getIsDeleted())
                || category.getSiteProjectId() == null
                || category.getSiteProjectId() <= 0) throw unavailableSource();
        requireSiteAccess(request, category.getSiteProjectId());
    }

    private void requirePlatformSource(
            jakarta.servlet.http.HttpServletRequest request, String configType, Integer contentId) {
        if (contentId == null || contentId <= 0) throw unavailableSource();
        var headers =
                platformContentMapper.selectList(
                        new QueryWrapper<PlatformContentEntity>()
                                .select("id", "class_id", "data_type")
                                .eq("id", contentId));
        if (headers.size() != 1 || !java.util.Objects.equals(headers.getFirst().getId(), contentId))
            throw unavailableSource();
        var row = headers.getFirst();
        var type = dataTypeOf(configType);
        if (!java.util.Objects.equals(row.getDataType(), type.code())) throw unavailableSource();
        permissionService.requirePlatformOperate(
                request,
                type.permissionType(),
                row.getClassid(),
                com.xinhongru.permission.PlatformPermission.OP_PUSH);
    }

    private void requireSiteAccess(jakarta.servlet.http.HttpServletRequest request, Long siteId) {
        try {
            siteContextService.assertCanAccessSite(request, siteId);
        } catch (com.xinhongru.site.context.SiteAccessDeniedException ex) {
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_SITE_ACCESS_DENIED", "无权访问推送来源或目标站点");
        }
    }

    private void prepareTargets(
            PushContext ctx,
            String configType,
            Map<String, Object> source,
            jakarta.servlet.http.HttpServletRequest request) {
        requireSiteAccess(request, ctx.siteId);
        var ids = new java.util.TreeSet<Integer>();
        if (ctx.anchorColumnId == null) throw new IllegalArgumentException("缺少目标栏目");
        ids.add(ctx.anchorColumnId);
        if (!"form".equals(configType)) {
            Integer categoryId = toInt(source.get("classid"));
            if (categoryId != null) {
                for (SourceFieldConfig field :
                        sourceFieldConfigs(ctx, dataTypeOf(configType), categoryId).values()) {
                    if (field.refDataType() == null) continue;
                    for (Integer value : toIds(source.get(field.fieldName()))) {
                        Integer targetCategory = null;
                        if (field.isCategory()) targetCategory = value;
                        else if (field.isContent()) {
                            PlatformContentEntity related = platformContentMapper.selectById(value);
                            if (related != null
                                    && java.util.Objects.equals(
                                            related.getDataType(), field.refDataType()))
                                targetCategory = related.getClassid();
                        }
                        if (targetCategory != null) {
                            List<PlatformOneshotEntity> chain =
                                    sourceCategoryChain(field.refDataType(), targetCategory);
                            if (chain.isEmpty()) continue;
                            PlatformDataType refType = PlatformDataType.ofCode(field.refDataType());
                            if (refType == null) throw new IllegalArgumentException("未知关联来源类型");
                            permissionService.requirePlatformOperate(
                                    request,
                                    refType.permissionType(),
                                    targetCategory,
                                    com.xinhongru.permission.PlatformPermission.OP_PUSH);
                            for (PlatformOneshotEntity node : chain) {
                                OneshotEntity target =
                                        oneShotMapper.selectBySiteAndPlatformRef(
                                                ctx.siteId,
                                                field.refDataType() + ":" + node.getId());
                                if (target != null && target.getId() != null)
                                    ids.add(target.getId());
                            }
                        }
                    }
                }
            }
        }
        for (Integer id : ids) {
            if (!permissionService.snapshot(request, ctx.siteId).hasMenuAccess(id))
                throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                        .forbidden("CMS_MENU_ACCESS_DENIED", "无权更改目标栏目 " + id);
        }
        for (Integer id : ids) {
            OneshotEntity target = oneShotMapper.lockFieldConfiguration(ctx.siteId, id.longValue());
            if (target == null
                    || !java.util.Objects.equals(target.getId(), id)
                    || !java.util.Objects.equals(target.getSiteProjectId(), ctx.siteId))
                throw new IllegalArgumentException("目标栏目不属于推送站点或结构已变化");
            ctx.locked.put(id, target);
        }
    }

    private void saveTarget(
            PushContext ctx, TargetContentEntity target, Integer menuId, Integer existingId) {
        OneshotEntity menu = ctx.locked.get(menuId);
        if (menu == null) throw new IllegalArgumentException("目标栏目结构已变化，请重新推送");
        com.xinhongru.entity.ContentEntity before =
                existingId == null
                        ? null
                        : contentMapper.lockActiveArticleInSiteMenu(ctx.siteId, menuId, existingId);
        if (existingId != null && before == null)
            throw new IllegalArgumentException("目标内容不属于推送站点栏目或已删除");
        target.setId(existingId);
        target.setClassid(menuId).setSiteProjectId(ctx.siteId).setStatus(ctx.targetStatus);
        var patch = new com.xinhongru.entity.ContentEntity();
        org.springframework.beans.BeanUtils.copyProperties(target, patch);
        target.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(
                        com.xinhongru.content.model.ContentModelCodec.read(menu.getModelField()),
                        patch,
                        before));
        int affected =
                existingId == null
                        ? targetContentMapper.insert(target)
                        : targetContentMapper.updateById(target);
        if (affected != 1 || target.getId() == null) throw new IllegalStateException("目标内容写入未成功");
    }

    private Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return JSON_OUT.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /** 查找该源内容此前分发到同一目标站点/栏目的成功记录，用于判断新增还是更新。 */
    private Integer findExistingTargetId(
            String configType, Integer sourceContentId, Long siteId, Integer columnId) {
        if (sourceContentId == null) {
            return null;
        }
        PlatformPushRecordEntity last =
                pushRecordMapper.selectOne(
                        new QueryWrapper<PlatformPushRecordEntity>()
                                .eq("content_type", configType)
                                .eq("source_content_id", sourceContentId)
                                .eq(siteId != null, "site_id", siteId)
                                .eq(columnId != null, "column_id", columnId)
                                .eq("push_status", "SUCCESS")
                                .eq("deleted", 0)
                                .isNotNull("target_content_id")
                                .orderByDesc("id")
                                .last("limit 1"));
        return last == null ? null : last.getTargetContentId();
    }

    private List<Map<String, Object>> selectSources(String configType, List<Integer> contentIds) {
        List<Map<String, Object>> result = new ArrayList<>();
        if ("form".equals(configType)) {
            formContentMapper.selectBatchIds(contentIds).forEach(e -> result.add(toFieldMap(e)));
        } else {
            // 产品/新闻/解决方案/案例：内容已统一到 t_platform_contents，按 id 直接取
            platformContentMapper
                    .selectBatchIds(contentIds)
                    .forEach(e -> result.add(toFieldMap(e)));
        }
        return result;
    }

    private Map<String, Object> toFieldMap(Object entity) {
        Map<String, Object> map = new HashMap<>();
        for (Field f : EntityFields.all(entity.getClass())) {
            try {
                f.setAccessible(true);
                Object v = f.get(entity);
                if (v != null) {
                    map.put(f.getName(), v);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return map;
    }

    private List<Map<String, String>> parseMapping(String fieldMapping) {
        if (fieldMapping == null || fieldMapping.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(fieldMapping, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================= 关联子类别 / 子类别内容 / 枚举 =================

    /** 单次推送上下文：缓存已建栏目与已解析的源字段配置，避免重复查询与重复新增 */
    private static final class PushContext {
        private final Long siteId;
        private final Integer anchorColumnId;
        private final Integer targetStatus;
        private final Long pushConfigId;

        /** dataType:categoryId -> 目标站点栏目 id */
        private final Map<String, Integer> columnMap = new HashMap<>();

        /** 源类别 modelField 解析结果：categoryId -> (fieldName -> 字段配置) */
        private final Map<Integer, Map<String, SourceFieldConfig>> fieldConfigCache =
                new HashMap<>();

        /** 已同步过枚举的目标栏目：columnId:categoryId */
        private final Set<String> enumSynced = new HashSet<>();

        private final Map<Integer, OneshotEntity> locked = new HashMap<>();

        private PushContext(PlatformPushConfigEntity cfg, Integer targetStatus) {
            this.siteId = cfg.getSiteId();
            this.anchorColumnId = cfg.getSiteColumnId();
            this.targetStatus = targetStatus;
            this.pushConfigId = cfg.getId();
        }
    }

    /** 源类别字段配置（只保留关联推送需要的部分） */
    private record SourceFieldConfig(
            String fieldName,
            String inputType,
            Integer refDataType,
            List<String> enumValues,
            boolean inputMultiple) {
        boolean isCategory() {
            return "categorySame".equals(inputType) || "categoryCross".equals(inputType);
        }

        boolean isContent() {
            return "contentSame".equals(inputType) || "contentCross".equals(inputType);
        }

        boolean isEnum() {
            return "enum".equals(inputType) && enumValues != null && !enumValues.isEmpty();
        }
    }

    /** 与改造前一致：未知类型按产品处理 */
    private PlatformDataType dataTypeOf(String configType) {
        for (PlatformDataType type : PlatformDataType.values()) {
            if (type.permissionType().equals(configType)) {
                return type;
            }
        }
        return PlatformDataType.PRODUCT;
    }

    /** 把内容关联的子类别、子类别内容与枚举一并分发到目标站点（无则新增、有则更新）， 返回字段名 → 目标侧取值（目标栏目 id / 目标内容 id）。 */
    private Map<String, Object> pushRelations(
            PushContext ctx, String configType, Map<String, Object> source, UserEntity user) {
        Map<String, Object> result = new HashMap<>();
        if (ctx.siteId == null || ctx.anchorColumnId == null || "form".equals(configType)) {
            return result;
        }
        PlatformDataType dataType = dataTypeOf(configType);
        Integer categoryId = toInt(source.get("classid"));
        if (categoryId == null) {
            return result;
        }
        Map<String, SourceFieldConfig> configs = sourceFieldConfigs(ctx, dataType, categoryId);
        if (configs.isEmpty()) {
            return result;
        }
        syncEnumFields(ctx, categoryId, configs);
        for (SourceFieldConfig field : configs.values()) {
            if (field.refDataType() == null) {
                continue;
            }
            Object rawValue = source.get(field.fieldName());
            List<Integer> values = toIds(rawValue);
            if (values.isEmpty()) {
                continue;
            }
            List<String> targetIds = new ArrayList<>();
            for (Integer value : values) {
                Integer targetId = null;
                if (field.isCategory()) {
                    targetId = resolveCategoryColumn(ctx, field.refDataType(), value);
                } else if (field.isContent()) {
                    targetId = resolveContentTarget(ctx, field.refDataType(), value, user);
                }
                if (targetId != null) {
                    targetIds.add(String.valueOf(targetId));
                }
            }
            if (targetIds.isEmpty()) {
                continue;
            }
            // 源为多选存储形式（,a,b,）时保持多选格式，否则写单值
            if (isMultiRaw(rawValue)) {
                result.put(field.fieldName(), "," + String.join(",", targetIds) + ",");
            } else {
                result.put(field.fieldName(), targetIds.get(0));
            }
        }
        return result;
    }

    /** 解析源字段值为 id 列表：兼容单值 id 与多选 ,a,b, 形式。 */
    private List<Integer> toIds(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Number number) {
            return List.of(number.intValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 非数字项忽略
            }
        }
        return result;
    }

    /** 源值是否为多选存储形式 ,a,b,。 */
    private boolean isMultiRaw(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.length() >= 2 && trimmed.startsWith(",") && trimmed.endsWith(",");
    }

    /** 读取源类别 modelField 中配置了类别/类别内容/枚举的字段 */
    private Map<String, SourceFieldConfig> sourceFieldConfigs(
            PushContext ctx, PlatformDataType dataType, Integer categoryId) {
        Map<String, SourceFieldConfig> cached = ctx.fieldConfigCache.get(categoryId);
        if (cached != null) {
            return cached;
        }
        Map<String, SourceFieldConfig> configs = new HashMap<>();
        PlatformOneshotEntity category = platformOneshotMapper.selectById(categoryId);
        if (category != null
                && category.getDataType() != null
                && category.getDataType() == dataType.code()) {
            configs = parseSourceFieldConfigs(category.getModelField());
        }
        ctx.fieldConfigCache.put(categoryId, configs);
        return configs;
    }

    private Map<String, SourceFieldConfig> parseSourceFieldConfigs(String modelFieldJson) {
        Map<String, SourceFieldConfig> configs = new HashMap<>();
        if (modelFieldJson == null || modelFieldJson.isBlank()) {
            return configs;
        }
        try {
            JsonNode root = JSON.readTree(modelFieldJson);
            if (!root.isArray()) {
                return configs;
            }
            for (JsonNode node : root) {
                String fieldName = textOrNull(node, "fieldName");
                String inputType = textOrNull(node, "inputType");
                if (fieldName == null || inputType == null || inputType.isBlank()) {
                    continue;
                }
                Integer refDataType = null;
                JsonNode ref = node.get("categoryRef");
                if (ref != null && ref.isObject() && ref.hasNonNull("dataType")) {
                    refDataType = ref.get("dataType").asInt();
                }
                List<String> enumValues = new ArrayList<>();
                JsonNode values = node.get("enumValues");
                if (values != null && values.isArray()) {
                    values.forEach(
                            v -> {
                                if (v != null && !v.isNull()) {
                                    enumValues.add(v.asText());
                                }
                            });
                }
                JsonNode multipleNode = node.get("inputMultiple");
                boolean inputMultiple = multipleNode != null && multipleNode.asBoolean(false);
                configs.put(
                        fieldName,
                        new SourceFieldConfig(
                                fieldName, inputType, refDataType, enumValues, inputMultiple));
            }
        } catch (Exception e) {
            log.warn("解析源类别字段配置失败", e);
        }
        return configs;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 把源类别的固定枚举写入目标栏目的字段配置（model_field / model_list） */
    private void syncEnumFields(
            PushContext ctx, Integer categoryId, Map<String, SourceFieldConfig> configs) {
        List<SourceFieldConfig> enumFields = new ArrayList<>();
        for (SourceFieldConfig cfg : configs.values()) {
            if (cfg.isEnum()) {
                enumFields.add(cfg);
            }
        }
        if (enumFields.isEmpty() || !ctx.enumSynced.add(ctx.anchorColumnId + ":" + categoryId)) {
            return;
        }
        OneshotEntity column = ctx.locked.get(ctx.anchorColumnId);
        if (column == null) throw new IllegalArgumentException("目标栏目结构已变化，请重新推送");
        String modelField = mergeEnumIntoConfig(column.getModelField(), enumFields);
        String modelList = mergeEnumIntoConfig(column.getModelList(), enumFields);
        if (java.util.Objects.equals(modelField, column.getModelField())
                && java.util.Objects.equals(modelList, column.getModelList())) return;
        com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                com.xinhongru.content.model.ContentModelCodec.read(column.getModelField()),
                com.xinhongru.content.model.ContentModelCodec.read(modelField));
        oneShotMapper.updateFieldConfig(
                ctx.anchorColumnId,
                column.getMenuField(),
                modelField,
                modelList,
                column.getModelSearch());
        column.setModelField(modelField).setModelList(modelList);
        publicSearchProjectionService.rebuildMenu(ctx.siteId, column.getId());
    }

    /** 合并枚举字段到目标栏目的字段配置 JSON：同名字段更新枚举，缺失字段追加 */
    private String mergeEnumIntoConfig(String json, List<SourceFieldConfig> enumFields) {
        ArrayNode array;
        try {
            JsonNode parsed = (json == null || json.isBlank()) ? null : JSON.readTree(json);
            if (parsed != null && !parsed.isArray())
                throw new IllegalArgumentException("目标字段配置必须是JSON数组");
            array = parsed == null ? JSON.createArrayNode() : (ArrayNode) parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("目标字段配置无效，不能覆盖原配置", e);
        }
        for (SourceFieldConfig field : enumFields) {
            ObjectNode matched = null;
            for (JsonNode node : array) {
                if (node.isObject() && field.fieldName().equals(textOrNull(node, "fieldName"))) {
                    matched = (ObjectNode) node;
                    break;
                }
            }
            if (matched == null) {
                matched = JSON.createObjectNode();
                matched.put("fieldName", field.fieldName());
                matched.put("fromName", field.fieldName());
                matched.put("fieldType", "text");
                matched.put("title", field.fieldName());
                matched.put("enabled", true);
                matched.put("isList", false);
                matched.put("isSearch", false);
                matched.put("isReadOnly", false);
                matched.put("sort", array.size());
                array.add(matched);
            }
            ArrayNode values = JSON.createArrayNode();
            field.enumValues().forEach(values::add);
            if (com.xinhongru.content.model.ContentExtensionFields.isName(field.fieldName())) {
                if (!matched.hasNonNull("maxLength"))
                    throw new IllegalArgumentException("新增字段缺少现有CMS定义，不能自动迁移");
                matched.put("defaultValue", String.join(",", field.enumValues()));
            }
            matched.put("inputType", "enum");
            matched.put("inputMultiple", field.inputMultiple());
            matched.set("enumValues", values);
        }
        return array.toString();
    }

    /** 关联子类别 → 目标站点栏目（保留源层级，顶层挂到推送配置的目标栏目下） */
    private Integer resolveCategoryColumn(PushContext ctx, int refDataType, int categoryId) {
        String ref = refDataType + ":" + categoryId;
        Integer cached = ctx.columnMap.get(ref);
        if (cached != null) {
            return cached;
        }
        List<PlatformOneshotEntity> chain = sourceCategoryChain(refDataType, categoryId);
        if (chain.isEmpty()) {
            log.warn("分发子类别失败：源类别不存在 {}", ref);
            return null;
        }
        Integer parentTargetId = ctx.anchorColumnId;
        Integer leafTargetId = null;
        for (PlatformOneshotEntity node : chain) {
            String nodeRef = refDataType + ":" + node.getId();
            Integer targetId = ctx.columnMap.get(nodeRef);
            OneshotEntity existing =
                    targetId == null
                            ? oneShotMapper.selectBySiteAndPlatformRef(ctx.siteId, nodeRef)
                            : null;
            if (existing != null && existing.getId() != null) {
                targetId = existing.getId();
                OneshotEntity current = ctx.locked.get(targetId);
                if (current == null) throw new IllegalArgumentException("目标栏目结构已变化，请重新推送");
                boolean metadataChanged =
                        !java.util.Objects.equals(current.getClassName(), node.getClassName())
                                || !java.util.Objects.equals(
                                        current.getParentId(),
                                        parentTargetId == null ? 0 : parentTargetId)
                                || !java.util.Objects.equals(current.getSort(), node.getSort())
                                || (node.getPageType() != null
                                        && !java.util.Objects.equals(
                                                current.getPageType(), node.getPageType()));
                if (metadataChanged) {
                    OneshotEntity update = new OneshotEntity();
                    update.setId(targetId);
                    update.setClassName(node.getClassName());
                    update.setParentId(parentTargetId == null ? 0 : parentTargetId);
                    update.setSort(node.getSort());
                    update.setPageType(node.getPageType());
                    oneShotMapper.updatePlatformColumn(update);
                    current.setClassName(node.getClassName())
                            .setParentId(update.getParentId())
                            .setSort(node.getSort());
                    if (node.getPageType() != null) current.setPageType(node.getPageType());
                    publicSearchProjectionService.rebuildMenu(ctx.siteId, targetId);
                }
            } else if (targetId == null) {
                OneshotEntity parent = ctx.locked.get(parentTargetId);
                if (parent == null || Boolean.FALSE.equals(parent.getIsCanAddSub()))
                    throw com.xinhongru.security.AdminSecurityExceptionHandler
                            .AdminSecurityException.forbidden(
                            "CMS_OPERATION_DENIED", "目标父栏目不允许新增子栏目");
                OneshotEntity insert = new OneshotEntity();
                insert.setSiteProjectId(ctx.siteId);
                insert.setParentId(parentTargetId == null ? 0 : parentTargetId);
                insert.setClassName(node.getClassName());
                insert.setSort(node.getSort());
                insert.setPageType(node.getPageType() == null ? 0 : node.getPageType());
                insert.setPlatformSourceRef(nodeRef);
                // 系统导入的分类节点继续承载同一已授权父下的分类链；与SQL持久值一致。
                insert.setIsCanAddSub(true);
                oneShotMapper.insertPlatformColumn(insert);
                targetId = insert.getId();
                if (targetId != null) {
                    oneShotMapper.assignBizcodeToOwnId(targetId);
                    ctx.locked.put(targetId, insert);
                    publicSearchProjectionService.rebuildMenu(ctx.siteId, targetId);
                }
            }
            if (targetId == null) {
                return null;
            }
            ctx.columnMap.put(nodeRef, targetId);
            parentTargetId = targetId;
            leafTargetId = targetId;
        }
        return leafTargetId;
    }

    /** 源类别祖先链：根 → 自身 */
    private List<PlatformOneshotEntity> sourceCategoryChain(int refDataType, int categoryId) {
        LinkedList<PlatformOneshotEntity> chain = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();
        Integer current = categoryId;
        while (current != null && current > 0 && visited.add(current)) {
            PlatformOneshotEntity node = platformOneshotMapper.selectById(current);
            if (node == null || node.getDataType() == null || node.getDataType() != refDataType) {
                break;
            }
            chain.addFirst(node);
            current = node.getParentId() > 0 ? node.getParentId() : null;
        }
        return chain;
    }

    /** 关联子类别内容 → 目标站点内容（写入子类别对应的目标栏目） */
    private Integer resolveContentTarget(
            PushContext ctx, int refDataType, int contentId, UserEntity user) {
        PlatformContentEntity src = platformContentMapper.selectById(contentId);
        if (src == null || src.getDataType() == null || src.getDataType() != refDataType) {
            log.warn("分发子类别内容失败：源内容不存在 {}:{}", refDataType, contentId);
            return null;
        }
        Integer sourceCategoryId = src.getClassid();
        if (sourceCategoryId == null) {
            return null;
        }
        Integer targetColumnId = resolveCategoryColumn(ctx, refDataType, sourceCategoryId);
        if (targetColumnId == null) {
            return null;
        }
        return upsertTargetContent(ctx, refDataType, src, targetColumnId, user);
    }

    /** 目标站点内容新增或更新，并写入推送记录以便下次判重与追溯 */
    private Integer upsertTargetContent(
            PushContext ctx,
            int refDataType,
            PlatformContentEntity src,
            Integer targetColumnId,
            UserEntity user) {
        PlatformDataType dataType = PlatformDataType.ofCode(refDataType);
        if (dataType == null) {
            return null;
        }
        String contentType = dataType.permissionType();
        Integer existingId =
                findExistingTargetId(contentType, src.getId(), ctx.siteId, targetColumnId);
        boolean updating = existingId != null && targetContentMapper.selectById(existingId) != null;

        TargetContentEntity target = new TargetContentEntity();
        target.setClassid(targetColumnId);
        target.setStatus(ctx.targetStatus);
        target.setTitle(src.getTitle());
        Map<String, Object> srcFields = toFieldMap(src);
        for (Entry<String, Object> entry : srcFields.entrySet()) {
            if (!CONTENT_FIELD_PATTERN.matcher(entry.getKey()).matches()
                    || !WRITABLE.contains(entry.getKey())) {
                continue;
            }
            try {
                new BeanWrapperImpl(target).setPropertyValue(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {
                // 字段类型不兼容时跳过
            }
        }
        Integer targetId;
        if (updating) {
            target.setId(existingId);
            saveTarget(ctx, target, targetColumnId, existingId);
            targetId = existingId;
        } else {
            target.setCreateTime(LocalDateTime.now());
            saveTarget(ctx, target, targetColumnId, null);
            targetId = target.getId();
        }
        PlatformPushRecordEntity record =
                new PlatformPushRecordEntity()
                        .setSourceContentId(src.getId())
                        .setContentType(contentType)
                        .setSiteId(ctx.siteId)
                        .setColumnId(targetColumnId)
                        .setPushConfigId(ctx.pushConfigId)
                        .setTargetStatus(ctx.targetStatus)
                        .setFieldContent(toJson(srcFields))
                        .setTargetContentId(targetId)
                        .setPushType(updating ? "UPDATE" : "INSERT")
                        .setPushStatus("SUCCESS")
                        .setPushTime(LocalDateTime.now())
                        .setPushUserId(
                                user == null || user.getId() == null
                                        ? null
                                        : user.getId().longValue())
                        .setPushUser(user == null ? null : user.getScreenName())
                        .setDeleted(0);
        pushRecordMapper.insert(record);
        return targetId;
    }

    private String toCamel(String name) {
        if (name == null || !name.contains("_")) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

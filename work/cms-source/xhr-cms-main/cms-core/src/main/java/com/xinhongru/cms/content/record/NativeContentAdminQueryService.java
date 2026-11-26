package com.xinhongru.cms.content.record;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.ContentStatus;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.dto.cond.ContentSearchCriterion;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.persistence.Pages;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.site.context.CmsResourceContextService;
import com.xinhongru.site.locale.SiteLocaleService;
import com.xinhongru.utils.StringDeal;

import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only assembly for the content administration endpoints (纯老系统 t_contents 读取)。 */
@Service
public class NativeContentAdminQueryService {
    private final CmsResourceContextService resources;
    private final SiteLocaleService locales;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;

    public NativeContentAdminQueryService(
            CmsResourceContextService resources,
            SiteLocaleService locales,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper) {
        this.resources = resources;
        this.locales = locales;
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
    }

    public NativeContentListResponse list(
            Long siteId, Long menuId, List<String> statuses, String keyword, int page, int limit) {
        return list(siteId, menuId, statuses, null, keyword, Map.of(), page, limit);
    }

    public NativeContentListResponse list(
            Long siteId,
            Long menuId,
            List<String> statuses,
            String variantState,
            String keyword,
            int page,
            int limit) {
        return list(siteId, menuId, statuses, variantState, keyword, Map.of(), page, limit);
    }

    /** 内容管理列表：基于 t_contents 宽表查询（数据库层过滤 + 分页），并按栏目的 modelSearch 配置返回动态搜索字段。 */
    public NativeContentListResponse list(
            Long siteId,
            Long menuId,
            List<String> statuses,
            String variantState,
            String keyword,
            Map<String, String> fieldFilters,
            int page,
            int limit) {
        return list(
                siteId,
                menuId,
                statuses,
                variantState,
                keyword,
                fieldFilters,
                null,
                null,
                null,
                page,
                limit);
    }

    public NativeContentListResponse list(
            Long siteId,
            Long menuId,
            List<String> statuses,
            String variantState,
            String keyword,
            Map<String, String> fieldFilters,
            String groupField,
            String groupValue,
            List<Integer> seedIds,
            int page,
            int limit) {
        resources.requireMenu(siteId, Math.toIntExact(menuId));

        OneshotEntity menu = oneShotMapper.getModelById(Math.toIntExact(menuId));
        if (menu == null || !java.util.Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        Map<String, ContentFieldDefinition> model = new LinkedHashMap<>();
        for (ContentFieldDefinition field : ContentModelCodec.read(menu.getModelField()))
            if (ContentExtensionFields.isName(field.getFieldName()))
                model.put(field.getFieldName(), field);
        List<ListFieldConfig> listFields =
                parseListFields(authorizedQueryConfig(menu.getId(), menu.getModelList(), model, false));
        List<SearchFieldConfig> searchFields =
                parseSearchFields(authorizedQueryConfig(menu.getId(), menu.getModelSearch(), model, true));

        ContentCond cond = new ContentCond();
        cond.setClassid(Math.toIntExact(menuId));
        cond.setSiteProjectId(siteId);
        if (keyword != null && !keyword.isBlank()) {
            cond.setTitle(keyword.trim());
        }
        List<Integer> statusList = contentStatuses(statuses);
        if (statusList != null && !statusList.isEmpty()) {
            cond.setStatusList(statusList);
        } else {
            Integer statusInt = contentStatus(statuses);
            if (statusInt != null) {
                cond.setStatus(statusInt);
            }
        }

        List<ContentSearchCriterion> criteria = buildModelCriteria(menu, fieldFilters, cond);
        if (groupField != null || groupValue != null) {
            if (groupValue == null || groupValue.length() > 512)
                throw new IllegalArgumentException("请选择有效的内容分组值");
            String normalizedGroup = ContentRecordService.normalizeGroup(groupValue);
            criteria.add(groupCriterion(menu, groupField, normalizedGroup));
            cond.setIncludeRowVersion(normalizedGroup.isEmpty());
        }
        if (seedIds != null) {
            if (!cond.isIncludeRowVersion()
                    || seedIds.isEmpty()
                    || seedIds.size() > 100
                    || seedIds.stream().anyMatch(id -> id == null || id <= 0))
                throw new IllegalArgumentException("草稿来源筛选仅支持未分组列表的有效内容 ID（最多 100 条）");
            cond.setSeedIds(seedIds);
        }

        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<String> fieldNameList = buildListFieldNames(listFields);

        var result =
                Pages.<ContentEntity>query(
                        safePage,
                        safeLimit,
                        pagination ->
                                contentMapper.getArticlesByCond(
                                        pagination, fieldNameList, cond, criteria));
        List<ContentEntity> contents = result.getList();
        long total = result.getTotal();

        List<NativeContentListItem> rows =
                contents.stream()
                        .map(
                                content ->
                                        toListItem(
                                                menuId,
                                                content,
                                                listFields,
                                                cond.isIncludeRowVersion()))
                        .toList();
        return new NativeContentListResponse(
                rows, (int) total, safePage, safeLimit, listFields, searchFields);
    }

    public List<ContentMapper.NativeContentGroup> contentGroups(
            Long siteId, Long menuId, String groupField) {
        resources.requireMenu(siteId, Math.toIntExact(menuId));
        var menu = oneShotMapper.getModelById(Math.toIntExact(menuId));
        if (menu == null || !java.util.Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        return contentMapper.selectNativeContentGroups(
                siteId, menu.getId(), groupCriterion(menu, groupField, ""));
    }

    static ContentSearchCriterion groupCriterion(
            OneshotEntity menu, String groupField, String groupValue) {
        ContentRecordService.requireGroupField(menu, groupField);
        if (ContentExtensionFields.isName(groupField))
            return new ContentSearchCriterion(null, "groupExact", groupValue, null, groupField);
        return new ContentSearchCriterion(
                ContentRecordService.nativeGroupColumn(groupField), "groupExact", groupValue);
    }

    /** 工作台只读候选复用同一有效配置；不把复制的搜索/列表开关当授权。 */
    public QueryFields queryFields(OneshotEntity menu) {
        Map<String, ContentFieldDefinition> model = new LinkedHashMap<>();
        for (var field : ContentModelCodec.read(menu.getModelField()))
            if (ContentExtensionFields.isName(field.getFieldName()))
                model.put(field.getFieldName(), field);
        return new QueryFields(
                parseSearchFields(authorizedQueryConfig(menu.getId(), menu.getModelSearch(), model, true)),
                parseListFields(authorizedQueryConfig(menu.getId(), menu.getModelList(), model, false)));
    }

    public record QueryFields(List<SearchFieldConfig> filters, List<ListFieldConfig> sorting) {}

    /** 调用方先验证站点、栏目及公开字段允许列表；仅复用模型条件，不查询后台记录。 */
    public List<ContentSearchCriterion> buildModelCriteria(
            OneshotEntity menu, Map<String, String> fieldFilters, ContentCond cond) {
        Map<String, ContentFieldDefinition> model = new LinkedHashMap<>();
        for (ContentFieldDefinition field : ContentModelCodec.read(menu.getModelField()))
            if (ContentExtensionFields.isName(field.getFieldName()))
                model.put(field.getFieldName(), field);
        List<ListFieldConfig> listFields =
                parseListFields(authorizedQueryConfig(menu.getId(), menu.getModelList(), model, false));
        List<SearchFieldConfig> searchFields =
                parseSearchFields(authorizedQueryConfig(menu.getId(), menu.getModelSearch(), model, true));
        // 父栏目跳转列带来的归属筛选：xgid 直接映射到 t_contents.xgid，不走 modelSearch 白名单
        String xgidParam = fieldFilters == null ? null : fieldFilters.get("xgid");
        if (xgidParam != null && !xgidParam.isBlank()) {
            try {
                cond.setXgid(Integer.valueOf(xgidParam.trim()));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("关联筛选必须是整数", error);
            }
        }

        validateQueryKeys(searchFields, fieldFilters);
        List<ContentSearchCriterion> criteria =
                new ArrayList<>(buildCriteria(searchFields, fieldFilters));
        criteria.addAll(extensionCriteria(searchFields, model, fieldFilters));
        if (criteria.size() > 20) throw new IllegalArgumentException("筛选条件过多");
        String sortKey = fieldFilters == null ? null : fieldFilters.get("sortField");
        String direction = fieldFilters == null ? null : fieldFilters.get("sortDirection");
        if (sortKey != null) {
            if (listFields.stream().noneMatch(field -> field.fieldName().equals(sortKey))
                    || !ContentExtensionFields.isName(sortKey))
                throw new IllegalArgumentException("排序字段未在当前列表中授权");
            if (direction != null && !Set.of("asc", "desc").contains(direction))
                throw new IllegalArgumentException("排序方向无效");
            cond.setExtensionSortKey(sortKey);
            cond.setExtensionSortNumeric(
                    ContentExtensionFields.supportsNumericQuery(model.get(sortKey)));
            cond.setExtensionSortDescending("desc".equals(direction));
        } else if (direction != null) throw new IllegalArgumentException("排序方向缺少字段");

        return criteria;
    }

    private NativeContentListItem toListItem(
            Long menuId,
            ContentEntity content,
            List<ListFieldConfig> listFields,
            boolean includeRowVersion) {
        Map<String, Object> payload = contentToPayload(content);
        Map<String, Object> fieldValues = extractFieldValues(payload, listFields);
        return new NativeContentListItem(
                content.getId().longValue(),
                null,
                menuId,
                "legacy",
                1L,
                workflowStatus(content.getStatus()),
                null,
                null,
                0,
                content.getTitle(),
                content.getCreateTime(),
                false,
                "MISSING",
                true,
                0,
                0,
                "DEFAULT",
                true,
                false,
                Map.of(),
                fieldValues,
                content.getSort() != null ? content.getSort() : 0,
                content.getHits() != null ? content.getHits() : 0,
                Boolean.TRUE.equals(content.getIndexTop()),
                Boolean.TRUE.equals(content.getIndexElite()),
                Boolean.TRUE.equals(content.getIndexHot()),
                Boolean.TRUE.equals(content.getMenuTop()),
                Boolean.TRUE.equals(content.getMenuElite()),
                Boolean.TRUE.equals(content.getMenuHot()),
                Boolean.TRUE.equals(content.getChannelTop()),
                Boolean.TRUE.equals(content.getChannelElite()),
                Boolean.TRUE.equals(content.getChannelHot()),
                Boolean.TRUE.equals(content.getNavElite()),
                Boolean.TRUE.equals(content.getRightElite()),
                Boolean.TRUE.equals(content.getLeftElite()),
                includeRowVersion
                                && java.util.Objects.equals(
                                        content.getStatus(), ContentStatus.DRAFT.code())
                        ? ContentRecordService.rowVersion(content)
                        : null);
    }

    /** 解析栏目的 model_search 配置为搜索字段列表（isSearch 开启且列名可解析）。 */
    private List<SearchFieldConfig> parseSearchFields(String modelSearch) {
        if (modelSearch == null || modelSearch.isBlank()) return List.of();
        List<ContentFieldDefinition> options;
        try {
            options = ContentModelCodec.read(modelSearch);
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (options == null) return List.of();
        return options.stream()
                .filter(opt -> isSearchEnabled(opt.getIsSearch()))
                .filter(
                        opt ->
                                ContentExtensionFields.isName(opt.getFieldName())
                                        || resolveSearchColumn(opt.getFieldName()) != null)
                .sorted(
                        (a, b) ->
                                Integer.compare(
                                        b.getSort() != null ? b.getSort() : 0,
                                        a.getSort() != null ? a.getSort() : 0))
                .map(
                        opt -> {
                            boolean select = isSelectField(opt.getDefaultValue());
                            return new SearchFieldConfig(
                                    opt.getFieldName(),
                                    opt.getTitle() != null && !opt.getTitle().isBlank()
                                            ? opt.getTitle()
                                            : opt.getFromName(),
                                    ContentExtensionFields.isName(opt.getFieldName())
                                                    && "SingleLine".equals(opt.getFieldType())
                                            ? (ContentExtensionFields.supportsNumericQuery(opt)
                                                    ? "number"
                                                    : "enum".equals(opt.getInputType())
                                                            ? "enum"
                                                            : "text")
                                            : editorFieldType(
                                                    opt.getFieldType(),
                                                    opt.getDefaultValue(),
                                                    opt.getFieldName()),
                                    select,
                                    select ? List.of() : parseEnumValues(opt.getDefaultValue()),
                                    select ? opt.getDefaultValue() : null,
                                    opt.getSort() != null ? opt.getSort() : 0,
                                    normalizeInputType(opt.getInputType()),
                                    Boolean.TRUE.equals(opt.getInputMultiple()),
                                    datePrecision(opt));
                        })
                .toList();
    }

    private String authorizedQueryConfig(
            Integer classId, String json, Map<String, ContentFieldDefinition> model, boolean search) {
        List<ContentFieldDefinition> result = new ArrayList<>();
        for (ContentFieldDefinition selected : ContentModelCodec.read(json)) {
            if (MemberCredentials.isPasswordField(classId, selected.getFieldName())) continue;
            if (!ContentExtensionFields.isName(selected.getFieldName())) {
                result.add(selected);
                continue;
            }
            ContentFieldDefinition actual = model.get(selected.getFieldName());
            if (actual == null
                    || !ContentExtensionFields.supportsQuery(actual)
                    || !ContentFieldDefinition.enabled(actual.getIsOpen())
                    || !ContentFieldDefinition.enabled(
                            search ? actual.getIsSearch() : actual.getIsList())) continue;
            ContentExtensionFields.validateDefinition(actual);
            result.add(actual);
        }
        return JSON.toJSONString(result);
    }

    private void validateQueryKeys(List<SearchFieldConfig> fields, Map<String, String> filters) {
        if (filters == null) return;
        if (filters.size() > 40) throw new IllegalArgumentException("筛选条件过多");
        Set<String> allowed = new LinkedHashSet<>(List.of("xgid", "sortField", "sortDirection"));
        for (SearchFieldConfig field : fields) {
            allowed.add(field.fieldName());
            if ("number".equals(field.fieldType())
                    && ContentExtensionFields.isName(field.fieldName())) {
                allowed.add("min_" + field.fieldName());
                allowed.add("max_" + field.fieldName());
            }
            if ("datetime".equals(field.fieldType())) {
                allowed.add("startTime_" + field.fieldName());
                allowed.add("endTime_" + field.fieldName());
            }
            if (ContentExtensionFields.isName(field.fieldName()))
                allowed.add("op_" + field.fieldName());
        }
        for (var entry : filters.entrySet())
            if (!allowed.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().length() > 1000)
                throw new IllegalArgumentException("筛选字段未授权或值超长");
    }

    private List<ContentSearchCriterion> extensionCriteria(
            List<SearchFieldConfig> fields,
            Map<String, ContentFieldDefinition> model,
            Map<String, String> filters) {
        List<ContentSearchCriterion> result = new ArrayList<>();
        if (filters == null) return result;
        for (SearchFieldConfig config : fields) {
            String key = config.fieldName();
            if (!ContentExtensionFields.isName(key)) continue;
            ContentFieldDefinition field = model.get(key);
            String value = filters.get(key), op = filters.get("op_" + key);
            if ("DateTime".equals(field.getFieldType())) {
                String start = filters.get("startTime_" + key), end = filters.get("endTime_" + key);
                if (start != null) {
                    validateQueryDate(field, start);
                    result.add(new ContentSearchCriterion(null, "ge", start, null, key));
                }
                if (end != null) {
                    validateQueryDate(field, end);
                    result.add(new ContentSearchCriterion(null, "le", end, null, key));
                }
                if (start != null && end != null && start.compareTo(end) > 0)
                    throw new IllegalArgumentException("时间范围倒置");
            }
            boolean numeric = ContentExtensionFields.supportsNumericQuery(field);
            if (numeric) {
                String min = filters.get("min_" + key), max = filters.get("max_" + key);
                if (min != null) {
                    min = ContentExtensionFields.numericQueryValue(min);
                    result.add(new ContentSearchCriterion(null, "ge", min, null, key, true));
                }
                if (max != null) {
                    max = ContentExtensionFields.numericQueryValue(max);
                    result.add(new ContentSearchCriterion(null, "le", max, null, key, true));
                }
                if (min != null
                        && max != null
                        && new java.math.BigInteger(min).compareTo(new java.math.BigInteger(max))
                                > 0) throw new IllegalArgumentException("数值范围倒置");
            }
            if (op == null && value == null) continue;
            if (op == null)
                op =
                        Boolean.TRUE.equals(field.getInputMultiple())
                                ? "any"
                                : "enum".equals(field.getInputType())
                                                || com.xinhongru.content.model.ContentReferences
                                                        .applies(field)
                                                || "DateTime".equals(field.getFieldType())
                                                || numeric
                                        ? "eq"
                                        : "contains";
            if (Set.of("empty", "notEmpty").contains(op)) {
                if (value != null) throw new IllegalArgumentException("空值操作不能携带字段值");
                result.add(new ContentSearchCriterion(null, op, null, null, key, numeric));
                continue;
            }
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("筛选值不能为空");
            if (numeric) {
                if (!Set.of("eq", "ge", "le", "gt", "lt").contains(op))
                    throw new IllegalArgumentException("数值筛选操作无效");
                value = ContentExtensionFields.numericQueryValue(value);
            } else if ("DateTime".equals(field.getFieldType())) {
                if (!Set.of("eq", "ge", "le").contains(op))
                    throw new IllegalArgumentException("时间筛选操作无效");
                validateQueryDate(field, value);
            } else if (com.xinhongru.content.model.ContentReferences.applies(field)) {
                List<String> selected =
                        com.xinhongru.content.model.ContentReferences.ids(field, value).stream()
                                .map(String::valueOf)
                                .toList();
                if (Boolean.TRUE.equals(field.getInputMultiple())) {
                    if (!Set.of("any", "all").contains(op) || selected.isEmpty())
                        throw new IllegalArgumentException("关联多选筛选操作无效");
                    result.add(new ContentSearchCriterion(null, op, null, selected, key));
                    continue;
                }
                if (!"eq".equals(op) || selected.size() != 1)
                    throw new IllegalArgumentException("关联单选筛选必须为一个完整内容ID");
                value = selected.getFirst();
            } else if ("enum".equals(field.getInputType())) {
                Set<String> allowed =
                        new LinkedHashSet<>(Arrays.asList(field.getDefaultValue().split(",", -1)));
                if (Boolean.TRUE.equals(field.getInputMultiple())) {
                    if (!Set.of("any", "all").contains(op))
                        throw new IllegalArgumentException("多选操作无效");
                    List<String> values =
                            value.length() >= 3 && value.startsWith(",") && value.endsWith(",")
                                    ? Arrays.asList(
                                            value.substring(1, value.length() - 1).split(",", -1))
                                    : List.of();
                    if (values.isEmpty()
                            || values.size() > 100
                            || new LinkedHashSet<>(values).size() != values.size()
                            || !allowed.containsAll(values))
                        throw new IllegalArgumentException("多选筛选包含非法成员");
                    result.add(new ContentSearchCriterion(null, op, null, values, key));
                    continue;
                }
                if (!"eq".equals(op) || !allowed.contains(value))
                    throw new IllegalArgumentException("枚举筛选无效");
            } else if (!Set.of("contains", "eq").contains(op))
                throw new IllegalArgumentException("文本筛选操作无效");
            result.add(new ContentSearchCriterion(null, op, value, null, key, numeric));
        }
        if (result.size() > 20) throw new IllegalArgumentException("筛选条件过多");
        return result;
    }

    private void validateQueryDate(ContentFieldDefinition field, String value) {
        ContentExtensionFields.put("{}", field, value);
    }

    private boolean isSearchEnabled(String isSearch) {
        return "on".equals(isSearch) || "1".equals(isSearch) || "true".equalsIgnoreCase(isSearch);
    }

    private boolean isSelectField(String defaultValue) {
        return defaultValue != null && defaultValue.startsWith("{$") && defaultValue.endsWith("$}");
    }

    /** 生成数据库层过滤条件（列名一律来自白名单，值走参数绑定）。 */
    private List<ContentSearchCriterion> buildCriteria(
            List<SearchFieldConfig> searchFields, Map<String, String> fieldFilters) {
        if (searchFields.isEmpty() || fieldFilters == null || fieldFilters.isEmpty())
            return List.of();
        List<ContentSearchCriterion> result = new ArrayList<>();
        for (SearchFieldConfig field : searchFields) {
            String column = resolveSearchColumn(field.fieldName());
            if (column == null) continue;
            if ("datetime".equals(field.fieldType())) {
                String start = fieldFilters.get("startTime_" + field.fieldName());
                if (start != null && !start.isBlank())
                    result.add(new ContentSearchCriterion(column, "ge", start));
                String end = fieldFilters.get("endTime_" + field.fieldName());
                if (end != null && !end.isBlank())
                    result.add(new ContentSearchCriterion(column, "le", end));
            } else {
                String value = fieldFilters.get(field.fieldName());
                if (value == null || value.isBlank()) continue;
                List<String> multiValues = parseMultiValue(value.trim());
                if (field.inputMultiple() && !multiValues.isEmpty()) {
                    // 多选字段：命中任一（存 ,a,b,，用 LIKE '%,v,%' 精确匹配单个值）
                    result.add(new ContentSearchCriterion(column, "multiLike", null, multiValues));
                    continue;
                }
                String op = field.select() || "eq".equals(field.fieldType()) ? "eq" : "like";
                result.add(new ContentSearchCriterion(column, op, value.trim()));
            }
        }
        return result;
    }

    /** 解析多选字段的 ,a,b, 形式取值；非该形式返回空列表。 */
    private List<String> parseMultiValue(String raw) {
        if (raw.length() < 3 || !raw.startsWith(",") || !raw.endsWith(",")) {
            return List.of();
        }
        String inner = raw.substring(1, raw.length() - 1);
        if (inner.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    /** 由列表字段配置解析出 SELECT 列名白名单后的列名列表；title/create_time 始终包含（用于标题与排序时间）。 */
    private List<String> buildListFieldNames(List<ListFieldConfig> listFields) {
        List<String> columns = new ArrayList<>();
        columns.add("title");
        columns.add("create_time");
        for (ListFieldConfig field : listFields) {
            String column =
                    ContentExtensionFields.isName(field.fieldName())
                            ? "extension_fields"
                            : resolveSearchColumn(field.fieldName());
            if (column != null
                    && !column.equals("title")
                    && !column.equals("create_time")
                    && !columns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    /** 列名白名单映射：驼峰/原始名 → t_contents 合法列，解析不出返回 null。 */
    private String resolveSearchColumn(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return null;
        String trimmed = fieldName.trim();
        String direct = T_CONTENTS_COLUMNS.get(trimmed);
        if (direct != null) return direct;
        return T_CONTENTS_COLUMNS.get(StringDeal.convertToUnderscore(trimmed));
    }

    private static final Map<String, String> T_CONTENTS_COLUMNS = buildTContentsColumns();

    private static Map<String, String> buildTContentsColumns() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", "title");
        map.put("createTime", "create_time");
        map.put("create_time", "create_time");
        map.put("beginTime", "begin_time");
        map.put("endTime", "end_time");
        map.put("entryIntoForceTime", "entry_into_force_time");
        map.put("expirationTime", "expiration_time");
        map.put("tags", "tags");
        addNumbered(map, "content", "content", 6);
        addNumbered(map, "singleLine", "single_line", 20);
        addNumbered(map, "single_line", "single_line", 20);
        addNumbered(map, "note", "note", 15);
        addNumbered(map, "photo", "photo", 15);
        addNumbered(map, "media", "media", 4);
        addNumbered(map, "file", "file", 4);
        return Collections.unmodifiableMap(map);
    }

    private static void addNumbered(
            Map<String, String> map, String prefix, String columnPrefix, int count) {
        for (int i = 1; i <= count; i++) {
            String prop = prefix + i;
            String col = columnPrefix + i;
            map.put(prop, col);
            map.put(col, col);
        }
    }

    /** workflowStatus -> legacy status（单值；多状态时取第一个可识别项，未识别返回 null）。 */
    private Integer contentStatus(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        for (String raw : statuses) {
            String status = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            switch (status) {
                case "DRAFT":
                    return 0;
                case "PUBLISHED":
                    return 1;
                case "PENDING_REVIEW":
                    return 2;
                case "REJECTED":
                    return 3;
                default:
                    break;
            }
        }
        return null;
    }

    /** 将多选状态映射为 legacy status 列表（IN 查询），无法识别的项忽略；入参为空时返回 null。 */
    private List<Integer> contentStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        Set<Integer> result = new LinkedHashSet<>();
        for (String raw : statuses) {
            String status = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            switch (status) {
                case "DRAFT":
                    result.add(0);
                    break;
                case "PUBLISHED":
                    result.add(1);
                    break;
                case "PENDING_REVIEW":
                    result.add(2);
                    break;
                case "REJECTED":
                    result.add(3);
                    break;
                case "ARCHIVED":
                    result.add(4);
                    break;
                default:
                    break;
            }
        }
        return result.isEmpty() ? null : new ArrayList<>(result);
    }

    public NativeContentEditorDetail editor(
            Long siteId,
            Long menuId,
            Long languageId,
            Long recordId,
            Long modelVersionId,
            String modelKey) {
        resources.requireMenu(siteId, Math.toIntExact(menuId));
        var locale = locales.requireEnabled(siteId, languageId);

        // 加载统一内容表 t_contents
        ContentEntity content =
                recordId == null
                        ? null
                        : contentMapper.selectActiveArticleInSiteMenu(
                                siteId, Math.toIntExact(menuId), Math.toIntExact(recordId));

        // 使用栏目的 modelField 配置解析字段
        OneshotEntity menu = oneShotMapper.getModelById(Math.toIntExact(menuId));
        String effectiveModelKey = (modelKey == null || modelKey.isBlank()) ? "legacy" : modelKey;
        List<NativeFieldDefinition> fieldDefs =
                parseModelFields(Math.toIntExact(menuId), menu != null ? menu.getModelField() : null);

        Map<String, Object> sharedPayload = Map.of();
        Map<String, Object> payload;
        Map<String, Object> relations = Map.of();
        Long effectiveRecordId;

        if (content != null) {
            effectiveRecordId = content.getId().longValue();
            payload = contentToPayload(content);
        } else {
            effectiveRecordId = null;
            payload = Map.of();
        }

        return new NativeContentEditorDetail(
                effectiveRecordId,
                content == null ? null : ContentRecordService.rowVersion(content),
                menuId,
                languageId,
                effectiveModelKey,
                content != null ? workflowStatus(content.getStatus()) : "DRAFT",
                fieldDefs,
                sharedPayload,
                payload,
                relations,
                new NativeLocaleState(
                        languageId,
                        true,
                        Integer.valueOf(1).equals(locale.getIsDefault()),
                        Integer.valueOf(1).equals(locale.getRequiredForPublish()),
                        content != null,
                        content != null ? workflowStatus(content.getStatus()) : null));
    }

    private List<NativeFieldDefinition> parseModelFields(Integer classId, String modelField) {
        if (modelField == null || modelField.isBlank()) return List.of();
        List<ContentFieldDefinition> options = ContentModelCodec.read(modelField);
        if (options == null) return List.of();
        return options.stream()
                .filter(opt -> isOpenEnabled(opt.getIsOpen()))
                .sorted(
                        (a, b) -> {
                            int sortA = a.getSort() != null ? a.getSort() : 0;
                            int sortB = b.getSort() != null ? b.getSort() : 0;
                            return Integer.compare(sortB, sortA); // 降序排列
                        })
                .map(
                        opt -> {
                            boolean password = MemberCredentials.isPasswordField(classId, opt.getFieldName());
                            String defaultValue = password ? "" : opt.getDefaultValue();
                            boolean isDynamicSelect =
                                    defaultValue != null
                                            && defaultValue.startsWith("{$")
                                            && defaultValue.endsWith("$}");
                            String selectQuery = isDynamicSelect ? defaultValue : null;
                            int sort = opt.getSort() != null ? opt.getSort() : 0;
                            return new NativeFieldDefinition(
                                    opt.getFieldName() != null ? opt.getFieldName() : "",
                                    opt.getTitle() != null && !opt.getTitle().isBlank()
                                            ? opt.getTitle()
                                            : opt.getFromName() != null
                                                    ? opt.getFromName()
                                                    : opt.getFieldName(),
                                    password ? "password" : editorFieldType(opt.getFieldType(), defaultValue),
                                    !password && ContentFieldDefinition.enabled(opt.getIsRequired()),
                                    true,
                                    parseEnumValues(defaultValue),
                                    password ? "留空保留原密码" : opt.getBak(),
                                    selectQuery,
                                    sort,
                                    password ? "" : normalizeInputType(opt.getInputType()),
                                    Boolean.TRUE.equals(opt.getInputMultiple()),
                                    Boolean.TRUE.equals(opt.getAllowEmpty()),
                                    opt.getCascadeParentField(),
                                    datePrecision(opt));
                        })
                .filter(def -> !def.fieldKey().isBlank())
                .toList();
    }

    private boolean isOpenEnabled(String isOpen) {
        return ContentFieldDefinition.enabled(isOpen);
    }

    private static String datePrecision(ContentFieldDefinition field) {
        return ContentExtensionFields.isName(field.getFieldName())
                        && "DateTime".equals(field.getFieldType())
                ? com.xinhongru.content.model.ContentDateTimes.precision(field)
                : null;
    }

    private List<String> parseEnumValues(String defaultValue) {
        if (defaultValue == null || defaultValue.isBlank()) return List.of();
        // 如果是动态关联下拉（{$...$}），不解析为静态枚举
        if (defaultValue.startsWith("{$") && defaultValue.endsWith("$}")) return List.of();
        // 普通逗号分隔的枚举值
        return Arrays.stream(defaultValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String editorFieldType(String legacyType, String defaultValue) {
        return editorFieldType(legacyType, defaultValue, null);
    }

    private String editorFieldType(String legacyType, String defaultValue, String fieldName) {
        if (legacyType == null || legacyType.isBlank()) {
            // 根据字段名判断时间类型
            if (fieldName != null) {
                String name = fieldName.toLowerCase(Locale.ROOT);
                if (name.endsWith("time") || name.endsWith("date")) {
                    return "datetime";
                }
            }
            return "text";
        }
        String type = legacyType.toLowerCase(Locale.ROOT);
        // SingleLine 类型如果有动态关联下拉（{$...$}），映射为 select
        if ("singleline".equals(type)
                && defaultValue != null
                && defaultValue.startsWith("{$")
                && defaultValue.endsWith("$}")) {
            return "select";
        }
        // SingleLine 类型如果有普通枚举值，映射为 enum
        if ("singleline".equals(type)
                && defaultValue != null
                && !defaultValue.isBlank()
                && !defaultValue.startsWith("{$")) {
            return "enum";
        }
        return switch (type) {
            case "editor", "richtext" -> "richtext";
            case "textarea", "multiline" -> "multiline";
            case "image", "photo" -> "image";
            case "file", "attachment" -> "file";
            case "video", "movie" -> "video";
            case "date", "datetime" -> "datetime";
            case "number", "int", "integer" -> "number";
            case "checkbox", "boolean" -> "boolean";
            case "select", "radio", "enum" -> "enum";
            default -> "text";
        };
    }

    private Map<String, Object> map(String json) {
        try {
            return json == null || json.isBlank()
                    ? Map.of()
                    : JSON.parseObject(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("CONTENT_PAYLOAD_INVALID", ex);
        }
    }

    private String selectQueryOf(String defaultValue) {
        if (defaultValue != null && defaultValue.startsWith("{$") && defaultValue.endsWith("$}")) {
            return defaultValue;
        }
        return null;
    }

    /** 规整输入方式：null/空 → ''（手动输入）；enum/content/menu 保留，其余归为手动。 */
    static String normalizeInputType(String inputType) {
        if (inputType == null || inputType.isBlank()) return "";
        String t = inputType.trim();
        return ("enum".equals(t) || "content".equals(t) || "menu".equals(t)) ? t : "";
    }

    private List<ListFieldConfig> parseListFields(String modelList) {
        if (modelList == null || modelList.isBlank()) return List.of();
        List<ContentFieldDefinition> options = ContentModelCodec.read(modelList);
        if (options == null) return List.of();
        return options.stream()
                .filter(opt -> isOpenEnabled(opt.getIsOpen()))
                .sorted(
                        (a, b) ->
                                Integer.compare(
                                        b.getSort() != null ? b.getSort() : 0,
                                        a.getSort() != null ? a.getSort() : 0))
                .map(
                        f ->
                                new ListFieldConfig(
                                        f.getFieldName(),
                                        f.getTitle() != null && !f.getTitle().isBlank()
                                                ? f.getTitle()
                                                : f.getFromName(),
                                        editorFieldType(
                                                f.getFieldType(),
                                                f.getDefaultValue(),
                                                f.getFieldName()),
                                        f.getSort() != null ? f.getSort() : 0,
                                        normalizeInputType(f.getInputType()),
                                        selectQueryOf(f.getDefaultValue()),
                                        Boolean.TRUE.equals(f.getInputMultiple())))
                .toList();
    }

    private Map<String, Object> extractFieldValues(
            Map<String, Object> payload, List<ListFieldConfig> fields) {
        if (payload == null || payload.isEmpty() || fields.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (ListFieldConfig field : fields) {
            Object value = payload.get(field.fieldName());
            if (value != null) {
                result.put(field.fieldName(), formatFieldValue(value, field.fieldType()));
            }
        }
        return result;
    }

    private Object formatFieldValue(Object value, String fieldType) {
        if (value == null) return "";
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        if (value instanceof LocalDate ld) {
            return ld.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (value instanceof Date d) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
        }
        return String.valueOf(value);
    }

    /** 将老CMS status 映射到 workflowStatus 老CMS status: 0=草稿, 1=已发布, 2=待审核, 3=未通过, 4=作废 */
    private String workflowStatus(Integer status) {
        return ContentStatus.fromCode(status).name();
    }

    /** 将老CMS内容实体转换为payload映射 */
    private Map<String, Object> contentToPayload(ContentEntity content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // 标题
        payload.putAll(content.getExtensionValues());
        if (content.getTitle() != null) payload.put("title", content.getTitle());
        // 时间字段
        if (content.getCreateTime() != null) payload.put("createTime", content.getCreateTime());
        if (content.getBeginTime() != null) payload.put("beginTime", content.getBeginTime());
        if (content.getEndTime() != null) payload.put("endTime", content.getEndTime());
        if (content.getEntryIntoForceTime() != null)
            payload.put("entryIntoForceTime", content.getEntryIntoForceTime());
        if (content.getExpirationTime() != null)
            payload.put("expirationTime", content.getExpirationTime());
        // 多行文本/编辑器 content1-6
        if (content.getContent1() != null) payload.put("content1", content.getContent1());
        if (content.getContent2() != null) payload.put("content2", content.getContent2());
        if (content.getContent3() != null) payload.put("content3", content.getContent3());
        if (content.getContent4() != null) payload.put("content4", content.getContent4());
        if (content.getContent5() != null) payload.put("content5", content.getContent5());
        if (content.getContent6() != null) payload.put("content6", content.getContent6());
        // 单行文本 singleLine1-20
        for (int i = 1; i <= 20; i++) {
            String field = "singleLine" + i;
            if (MemberCredentials.isPasswordField(content.getClassid(), field)) continue;
            Object value = readLegacyField(content, field);
            if (value != null) payload.put(field, value);
        }
        // 备用多行文本 note1-15
        for (int i = 1; i <= 15; i++) {
            Object value = readLegacyField(content, "note" + i);
            if (value != null) payload.put("note" + i, value);
        }
        // 图片 photo1-15
        for (int i = 1; i <= 15; i++) {
            Object value = readLegacyField(content, "photo" + i);
            if (value != null) payload.put("photo" + i, value);
        }
        // 文件 file1-4
        for (int i = 1; i <= 4; i++) {
            Object value = readLegacyField(content, "file" + i);
            if (value != null) payload.put("file" + i, value);
        }
        // 视频 media1-4
        for (int i = 1; i <= 4; i++) {
            Object value = readLegacyField(content, "media" + i);
            if (value != null) payload.put("media" + i, value);
        }
        return payload;
    }

    private Object readLegacyField(ContentEntity content, String fieldName) {
        return switch (fieldName) {
            case "content1" -> content.getContent1();
            case "content2" -> content.getContent2();
            case "content3" -> content.getContent3();
            case "content4" -> content.getContent4();
            case "content5" -> content.getContent5();
            case "content6" -> content.getContent6();
            case "singleLine1" -> content.getSingleLine1();
            case "singleLine2" -> content.getSingleLine2();
            case "singleLine3" -> content.getSingleLine3();
            case "singleLine4" -> content.getSingleLine4();
            case "singleLine5" -> content.getSingleLine5();
            case "singleLine6" -> content.getSingleLine6();
            case "singleLine7" -> content.getSingleLine7();
            case "singleLine8" -> content.getSingleLine8();
            case "singleLine9" -> content.getSingleLine9();
            case "singleLine10" -> content.getSingleLine10();
            case "singleLine11" -> content.getSingleLine11();
            case "singleLine12" -> content.getSingleLine12();
            case "singleLine13" -> content.getSingleLine13();
            case "singleLine14" -> content.getSingleLine14();
            case "singleLine15" -> content.getSingleLine15();
            case "singleLine16" -> content.getSingleLine16();
            case "singleLine17" -> content.getSingleLine17();
            case "singleLine18" -> content.getSingleLine18();
            case "singleLine19" -> content.getSingleLine19();
            case "singleLine20" -> content.getSingleLine20();
            case "note1" -> content.getNote1();
            case "note2" -> content.getNote2();
            case "note3" -> content.getNote3();
            case "note4" -> content.getNote4();
            case "note5" -> content.getNote5();
            case "note6" -> content.getNote6();
            case "note7" -> content.getNote7();
            case "note8" -> content.getNote8();
            case "note9" -> content.getNote9();
            case "note10" -> content.getNote10();
            case "note11" -> content.getNote11();
            case "note12" -> content.getNote12();
            case "note13" -> content.getNote13();
            case "note14" -> content.getNote14();
            case "note15" -> content.getNote15();
            case "photo1" -> content.getPhoto1();
            case "photo2" -> content.getPhoto2();
            case "photo3" -> content.getPhoto3();
            case "photo4" -> content.getPhoto4();
            case "photo5" -> content.getPhoto5();
            case "photo6" -> content.getPhoto6();
            case "photo7" -> content.getPhoto7();
            case "photo8" -> content.getPhoto8();
            case "photo9" -> content.getPhoto9();
            case "photo10" -> content.getPhoto10();
            case "photo11" -> content.getPhoto11();
            case "photo12" -> content.getPhoto12();
            case "photo13" -> content.getPhoto13();
            case "photo14" -> content.getPhoto14();
            case "photo15" -> content.getPhoto15();
            case "file1" -> content.getFile1();
            case "file2" -> content.getFile2();
            case "file3" -> content.getFile3();
            case "file4" -> content.getFile4();
            case "media1" -> content.getMedia1();
            case "media2" -> content.getMedia2();
            case "media3" -> content.getMedia3();
            case "media4" -> content.getMedia4();
            default -> null;
        };
    }

    public record NativeContentListResponse(
            List<NativeContentListItem> rows,
            int total,
            int page,
            int limit,
            List<ListFieldConfig> listFields,
            List<SearchFieldConfig> searchFields) {}

    public record ListFieldConfig(
            String fieldName,
            String title,
            String fieldType,
            int sort,
            String inputType,
            String selectQuery,
            boolean inputMultiple) {}

    public record SearchFieldConfig(
            String fieldName,
            String title,
            String fieldType,
            boolean select,
            List<String> options,
            String selectQuery,
            int sort,
            String inputType,
            boolean inputMultiple,
            String datePrecision) {
        public SearchFieldConfig(
                String fieldName,
                String title,
                String fieldType,
                boolean select,
                List<String> options,
                String selectQuery,
                int sort,
                String inputType,
                boolean inputMultiple) {
            this(
                    fieldName,
                    title,
                    fieldType,
                    select,
                    options,
                    selectQuery,
                    sort,
                    inputType,
                    inputMultiple,
                    null);
        }
    }

    public record NativeContentListItem(
            Long recordId,
            Long variantId,
            Long menuId,
            String modelKey,
            Long languageId,
            String workflowStatus,
            Long activeVersionId,
            Long publishedVersionId,
            Integer lockVersion,
            String title,
            LocalDateTime updatedAt,
            boolean variantExists,
            String variantState,
            boolean translationComplete,
            int completedRequiredFields,
            int requiredTranslatableFields,
            String languageState,
            boolean defaultLanguage,
            boolean requiredForPublish,
            Map<String, Object> sharedValues,
            Map<String, Object> fieldValues,
            int sort,
            int hits,
            boolean indexTop,
            boolean indexElite,
            boolean indexHot,
            boolean menuTop,
            boolean menuElite,
            boolean menuHot,
            boolean channelTop,
            boolean channelElite,
            boolean channelHot,
            boolean navElite,
            boolean rightElite,
            boolean leftElite,
            String rowVersion) {}

    public record NativeFieldDefinition(
            String fieldKey,
            String displayName,
            String fieldType,
            boolean required,
            boolean translatable,
            List<String> enumValues,
            String bak,
            String selectQuery,
            int sort,
            String inputType,
            boolean inputMultiple,
            boolean allowEmpty,
            String cascadeParentField,
            String datePrecision) {
        public NativeFieldDefinition(
                String fieldKey,
                String displayName,
                String fieldType,
                boolean required,
                boolean translatable,
                List<String> enumValues,
                String bak,
                String selectQuery,
                int sort,
                String inputType,
                boolean inputMultiple,
                boolean allowEmpty,
                String cascadeParentField) {
            this(
                    fieldKey,
                    displayName,
                    fieldType,
                    required,
                    translatable,
                    enumValues,
                    bak,
                    selectQuery,
                    sort,
                    inputType,
                    inputMultiple,
                    allowEmpty,
                    cascadeParentField,
                    null);
        }
    }

    public record NativeContentEditorDetail(
            Long recordId,
            String rowVersion,
            Long menuId,
            Long languageId,
            String modelKey,
            String workflowStatus,
            List<NativeFieldDefinition> fields,
            Map<String, Object> sharedPayload,
            Map<String, Object> payload,
            Map<String, Object> relations,
            NativeLocaleState localeState) {}

    public record NativeLocaleState(
            Long languageId,
            boolean enabled,
            boolean defaultLanguage,
            boolean requiredForPublish,
            boolean variantExists,
            String workflowStatus) {}
}

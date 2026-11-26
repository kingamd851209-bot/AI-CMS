package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** 只登记开发者圈定的静态控件证据，不把导航、轮播或JavaScript当作查询语义。 */
@Service
public class TemplateQueryBindingService {
    public static final int UNPAGED_LIST_LIMIT = 100;
    private final UiPackageStorageService packages;
    private final OneShotMapper menus;
    private final Path metadataRoot;

    public TemplateQueryBindingService(
            UiPackageStorageService packages,
            OneShotMapper menus,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String metadataRoot) {
        this.packages = packages;
        this.menus = menus;
        this.metadataRoot = Path.of(metadataRoot).toAbsolutePath().normalize();
    }

    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();
    private com.xinhongru.cms.content.record.NativeContentAdminQueryService queries;
    private TemplateFieldPlanService plans;
    private com.xinhongru.site.content.ContentLinkService links;
    private QueryListPresentation presentation;
    @org.springframework.beans.factory.annotation.Autowired private SiteModelNavigationService navigation;
    @org.springframework.beans.factory.annotation.Autowired private SiteModelPlanService siteModels;
    @org.springframework.beans.factory.annotation.Autowired private SiteModelEvidenceService siteEvidence;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPresentation(QueryListPresentation value) {
        presentation = value;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setQueries(com.xinhongru.cms.content.record.NativeContentAdminQueryService value) {
        queries = value;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setPlans(TemplateFieldPlanService value) {
        plans = value;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setLinks(com.xinhongru.site.content.ContentLinkService value) {
        links = value;
    }

    public record Mapping(
            String controlId,
            String role,
            String fieldName,
            String operator,
            Map<String, String> values,
            String label) {
        public Mapping(
                String controlId,
                String role,
                String fieldName,
                String operator,
                Map<String, String> values) {
            this(controlId, role, fieldName, operator, values, null);
        }
    }

    public record MappingRequest(
            String sourcePlanId,
            String sourcePath,
            String digest,
            Long menuId,
            String containerSelector,
            String fieldConfigDigest,
            String routeDigest,
            List<Mapping> mappings,
            boolean confirmed,
            QueryListPresentation.Request presentation,
            Integer pageSize) {
        public MappingRequest(
                String sourcePlanId,
                String sourcePath,
                String digest,
                Long menuId,
                String containerSelector,
                String fieldConfigDigest,
                String routeDigest,
                List<Mapping> mappings,
                boolean confirmed) {
            this(
                    sourcePlanId,
                    sourcePath,
                    digest,
                    menuId,
                    containerSelector,
                    fieldConfigDigest,
                    routeDigest,
                    mappings,
                    confirmed,
                    null,
                    null);
        }
    }

    public record SharedLayout(String sourceQueryId, String navigationPlanId) {}

    public record Snapshot(
            int schemaVersion,
            String id,
            Long siteId,
            String code,
            String packageVersion,
            String sourcePlanId,
            String sourcePath,
            String digest,
            Long menuId,
            String bizCode,
            String containerSelector,
            String fieldConfigDigest,
            String routeDigest,
            String firstUrl,
            List<Mapping> mappings,
            List<String> allowedFilterKeys,
            List<String> allowedSortKeys,
            QueryListPresentation.Plan presentation,
            Integer pageSize,
            SharedLayout sharedLayout) {
        public Snapshot(int schemaVersion, String id, Long siteId, String code, String packageVersion,
                String sourcePlanId, String sourcePath, String digest, Long menuId, String bizCode,
                String containerSelector, String fieldConfigDigest, String routeDigest, String firstUrl,
                List<Mapping> mappings, List<String> allowedFilterKeys, List<String> allowedSortKeys,
                QueryListPresentation.Plan presentation, Integer pageSize) {
            this(schemaVersion, id, siteId, code, packageVersion, sourcePlanId, sourcePath, digest,
                    menuId, bizCode, containerSelector, fieldConfigDigest, routeDigest, firstUrl,
                    mappings, allowedFilterKeys, allowedSortKeys, presentation, pageSize, null);
        }

        public boolean completeList() {
            if (presentation == null || presentation.request() == null) return false;
            var view = presentation.request();
            return view.pagination() != null || view.fixedList() || view.timeline() != null;
        }
        public Snapshot(
                int schemaVersion,
                String id,
                Long siteId,
                String code,
                String packageVersion,
                String sourcePlanId,
                String sourcePath,
                String digest,
                Long menuId,
                String bizCode,
                String containerSelector,
                String fieldConfigDigest,
                String routeDigest,
                String firstUrl,
                List<Mapping> mappings,
                List<String> allowedFilterKeys,
                List<String> allowedSortKeys) {
            this(
                    schemaVersion,
                    id,
                    siteId,
                    code,
                    packageVersion,
                    sourcePlanId,
                    sourcePath,
                    digest,
                    menuId,
                    bizCode,
                    containerSelector,
                    fieldConfigDigest,
                    routeDigest,
                    firstUrl,
                    mappings,
                    allowedFilterKeys,
                    allowedSortKeys,
                    null,
                    10);
        }
    }

    public com.xinhongru.cms.content.record.NativeContentAdminQueryService.QueryFields fields(
            Long siteId, Long menuId) {
        return queries.queryFields(menu(siteId, menuId));
    }

    @org.springframework.transaction.annotation.Transactional
    public synchronized Snapshot confirm(
            Long siteId, String code, String version, MappingRequest body) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive())
            throw new IllegalStateException("确认查询绑定必须通过CMS实际事务入口");
        if (body == null
                || !body.confirmed()
                || body.mappings() == null
                || body.mappings().size() > 20)
            throw new IllegalArgumentException("请明确确认控件映射，最多20项");
        boolean timeline = body.presentation() != null && body.presentation().timeline() != null;
        boolean pageOnly = body.mappings().isEmpty();
        boolean fixedList = body.presentation() != null && body.presentation().fixedList();
        if (timeline && (!pageOnly || body.pageSize() != null || !"body".equals(body.containerSelector())))
            throw new IllegalArgumentException("时间轴只支持无筛选、无分页的整页布局");
        if (pageOnly && !timeline && !fixedList && (body.presentation() == null || body.presentation().pagination() == null))
            throw new IllegalArgumentException("不接线筛选时须明确确认整组卡片与真实分页或无分页用途");
        var locked = menus.lockFieldConfiguration(siteId, body.menuId());
        var current =
                candidates(
                        siteId,
                        code,
                        version,
                        body.menuId(),
                        body.sourcePath(),
                        body.containerSelector());
        if (locked == null
                || !Objects.equals(locked.getSiteProjectId(), siteId)
                || !ContentFieldConfigDigest.of(locked).equals(current.fieldConfigDigest())
                || !Objects.equals(body.digest(), current.digest())
                || !Objects.equals(body.fieldConfigDigest(), current.fieldConfigDigest())
                || !Objects.equals(body.routeDigest(), current.routeDigest()))
            throw new IllegalArgumentException("来源、字段配置或路由已变化");
        if (!timeline) requireSourcePlan(
                siteId,
                code,
                version,
                body.sourcePlanId(),
                body.menuId(),
                body.sourcePath(),
                body.digest(),
                body.presentation() != null, false);
        if (locked.getBizCode() == null || locked.getBizCode().isBlank())
            throw new IllegalArgumentException("栏目缺少真实业务编码，不能猜测查询身份");
        if (fixedList) requireFixedList(code, version, body.sourcePath(), body.containerSelector(),
                body.presentation(), locked, pageOnly);
        var effective = queries.queryFields(locked);
        var allowedFilters = new LinkedHashSet<String>();
        var allowedSort = new LinkedHashSet<String>();
        var seen = new HashSet<String>();
        for (var mapping : body.mappings()) {
            if (mapping == null
                    || !seen.add(mapping.controlId())
                    || mapping.values() == null
                    || mapping.values().size() > 50)
                throw new IllegalArgumentException("映射重复或字面值范围无效");
            var control =
                    current.controls().stream()
                            .filter(row -> row.id().equals(mapping.controlId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("控件不在当前有界证据范围"));
            if (!"CANDIDATE".equals(control.status()))
                throw new IllegalArgumentException("控件语义或结构仍待处理：" + control.reason());
            if ("SUBMIT".equals(mapping.role())) {
                if (!"SUBMIT".equals(control.kind())
                        || mapping.fieldName() != null
                        || mapping.operator() != null
                        || !mapping.values().isEmpty())
                    throw new IllegalArgumentException("提交控件不能携带字段或值映射");
                continue;
            }
            if ("SORT".equals(mapping.role())) {
                if (!allowedSort.isEmpty()
                        || !"SELECT".equals(control.kind())
                        || mapping.fieldName() != null
                        || mapping.operator() != null)
                    throw new IllegalArgumentException("排序须是唯一明确的静态选项控件");
                var originalValues =
                        control.options().stream()
                                .map(Option::value)
                                .filter(v -> !v.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());
                if (!mapping.values().keySet().equals(originalValues) || originalValues.isEmpty())
                    throw new IllegalArgumentException("排序原选项必须逐一明确映射");
                for (String target : mapping.values().values()) {
                    String[] parts = sortParts(target);
                    if (effective.sorting().stream()
                                    .noneMatch(field -> field.fieldName().equals(parts[0]))
                            || !com.xinhongru.content.model.ContentExtensionFields.isName(parts[0]))
                        throw new IllegalArgumentException("排序字段未在当前列表授权");
                    queries.buildModelCriteria(
                            locked,
                            Map.of("sortField", parts[0], "sortDirection", parts[1]),
                            new com.xinhongru.dto.cond.ContentCond());
                    allowedSort.add(parts[0]);
                }
                continue;
            }
            if (!"FILTER".equals(mapping.role()))
                throw new IllegalArgumentException("此控件角色尚未支持，不能假称已接线");
            var field =
                    effective.filters().stream()
                            .filter(row -> row.fieldName().equals(mapping.fieldName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("字段未在当前模型授权查询"));
            if (field.select()
                    || field.inputMultiple()
                    || !Set.of("TEXT", "SELECT").contains(control.kind()))
                throw new IllegalArgumentException("动态选项、多选或复杂控件仍待处理");
            if (!allowedFilters.add(field.fieldName()))
                throw new IllegalArgumentException("同一字段重复映射，不能猜测AND或OR语义");
            String op = mapping.operator();
            if (op == null || !Set.of("eq", "contains", "ge", "le", "gt", "lt").contains(op))
                throw new IllegalArgumentException("筛选操作无效");
            if (!com.xinhongru.content.model.ContentExtensionFields.isName(field.fieldName())
                    && !mapping.values().isEmpty())
                throw new IllegalArgumentException("传统字段字面值改写尚未支持");
            if (control.kind().equals("SELECT")) {
                var originalValues =
                        control.options().stream()
                                .map(Option::value)
                                .filter(v -> !v.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());
                if (!mapping.values().keySet().equals(originalValues)
                        || mapping.values().values().stream()
                                .anyMatch(v -> v == null || v.length() > 1000))
                    throw new IllegalArgumentException("每个原选项都须明确映射，不能按数组猜值");
            } else if (!mapping.values().isEmpty())
                throw new IllegalArgumentException("自由文本不能携带固定选项映射");
            String sample =
                    field.options().isEmpty()
                            ? ("number".equals(field.fieldType())
                                    ? "0"
                                    : "datetime".equals(field.fieldType())
                                            ? ("DAY".equals(field.datePrecision())
                                                            || "date".equals(field.inputType())
                                                    ? "2000-01-01"
                                                    : "2000-01-01 00:00:00")
                                            : "query-check")
                            : field.options().getFirst();
            List<String> samples =
                    control.kind().equals("SELECT")
                            ? new ArrayList<>(mapping.values().values())
                            : List.of(sample);
            for (String value : samples) {
                var filter = new LinkedHashMap<String, String>();
                filter.put(field.fieldName(), value);
                if (com.xinhongru.content.model.ContentExtensionFields.isName(field.fieldName()))
                    filter.put("op_" + field.fieldName(), op);
                else if (!"contains".equals(op))
                    throw new IllegalArgumentException("传统文本字段当前仅沿原包含查询");
                queries.buildModelCriteria(
                        locked, filter, new com.xinhongru.dto.cond.ContentCond());
            }
        }
        if (!pageOnly && allowedFilters.isEmpty() && allowedSort.isEmpty())
            throw new IllegalArgumentException("至少明确一个筛选或排序字段");
        int pageSize = fixedList ? UNPAGED_LIST_LIMIT : body.pageSize() == null ? 10 : body.pageSize();
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("每页记录数须为1至100");
        QueryListPresentation.Plan rowPlan = null;
        if (timeline) {
            rowPlan = confirmTimeline(siteId, code, version, body.sourcePlanId(), body.sourcePath(), locked,
                    body.presentation());
        } else if (body.presentation() != null) {
            if (body.presentation().pairing() == null
                    || !Objects.equals(body.presentation().pairing().menuId(), body.menuId())
                    || !Objects.equals(
                            body.presentation().pairing().listSourcePath(), body.sourcePath()))
                throw new IllegalArgumentException("卡片显示与查询来源不一致");
            rowPlan =
                    presentation.confirm(siteId, code, version, body.sourcePlanId(), body.presentation(),
                            body.containerSelector(), false, pageOnly && "body".equals(body.containerSelector())
                                    && body.presentation().pagination() != null);
        }
        String id =
                UUID.nameUUIDFromBytes(
                                (code
                                                + "\n"
                                                + siteId
                                                + "\n"
                                                + version
                                                + "\n"
                                                + json.writeValueAsString(body))
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        var result =
                new Snapshot(
                        rowPlan == null ? 1 : 2,
                        id,
                        siteId,
                        code,
                        version,
                        body.sourcePlanId(),
                        body.sourcePath(),
                        current.digest(),
                        body.menuId(),
                        locked.getBizCode(),
                        body.containerSelector(),
                        current.fieldConfigDigest(),
                        current.routeDigest(),
                        timeline
                                ? links.getListUrl(locked, 1).split("\\?", 2)[0]
                                : links.getListUrl(locked, 1),
                        body.mappings().stream()
                                .map(
                                        m ->
                                                new Mapping(
                                                        m.controlId(),
                                                        m.role(),
                                                        m.fieldName(),
                                                        m.operator(),
                                                        Map.copyOf(m.values()),
                                                        m.role().equals("FILTER")
                                                                ? effective.filters().stream()
                                                                        .filter(
                                                                                field ->
                                                                                        field.fieldName()
                                                                                                .equals(
                                                                                                        m
                                                                                                                .fieldName()))
                                                                        .findFirst()
                                                                        .orElseThrow()
                                                                        .title()
                                                                : null))
                                .toList(),
                        List.copyOf(allowedFilters),
                        List.copyOf(allowedSort),
                        rowPlan,
                        pageSize);
        return save(result);
    }

    public Snapshot previewSharedLayout(Long site, String code, String version, Long menuId,
            String sourcePath, SharedLayout layout) {
        return sharedSnapshot(site, code, version, menuId, sourcePath, layout);
    }

    @org.springframework.transaction.annotation.Transactional
    public synchronized Snapshot confirmSharedLayout(Long site, String code, String version, Long menuId,
            String sourcePath, SharedLayout layout, String expectedId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || expectedId == null || menus.lockFieldConfiguration(site, menuId) == null)
            throw new IllegalArgumentException("请先预览并确认当前栏目使用的列表版式");
        var snapshot = sharedSnapshot(site, code, version, menuId, sourcePath, layout);
        if (!snapshot.id().equals(expectedId))
            throw new IllegalArgumentException("列表版式、栏目字段或路由已变化，请重新预览");
        return save(snapshot);
    }

    private Snapshot sharedSnapshot(Long site, String code, String version, Long menuId,
            String sourcePath, SharedLayout layout) {
        if (layout == null || layout.navigationPlanId() == null || layout.sourceQueryId() == null)
            throw new IllegalArgumentException("请选择已确认的原列表查询和已应用导航");
        var source = read(site, code, version, layout.sourceQueryId(), false);
        if (!source.completeList() || Objects.equals(source.menuId(), menuId)
                || !Objects.equals(source.sourcePath(), sourcePath))
            throw new IllegalArgumentException("原查询须来自同站、同包和同一原页的其他栏目");
        var view = source.presentation();
        var request = view.request();
        if (!source.mappings().isEmpty() || request.timeline() != null
                || request.categorySidebar() != null)
            throw new IllegalArgumentException("带独立筛选、分类侧栏或时间轴的列表须单独审核");
        var target = menu(site, menuId);
        if (!Boolean.TRUE.equals(target.getHasFrontChannel()) || target.getSiteRootId() == null
                || target.getBizCode() == null || target.getBizCode().isBlank())
            throw new IllegalArgumentException("目标栏目须配置独立业务编码并开放前台");
        var scope = new SiteModelNavigationService.Scope(site, code, version, target.getSiteRootId());
        var applied = navigation.get(scope, layout.navigationPlanId(), () -> {});
        var reviewed = siteModels.get(site, code, version, layout.navigationPlanId());
        requireCurrentSharedNavigation(reviewed, applied);
        navigation.appliedSnapshot(scope, layout.navigationPlanId());
        if (applied.nodes().stream().noneMatch(node -> !node.drift()
                        && node.kind() == SiteModelNavigationService.Kind.LIST
                        && Objects.equals(node.menuId(), target.getId())
                        && Objects.equals(node.sourcePath(), sourcePath)))
            throw new IllegalArgumentException("目标栏目须对应已应用导航中的同一原列表页");
        var fields = ContentModelCodec.read(target.getModelField());
        for (var field : view.fields()) {
            if (fields.stream().noneMatch(candidate -> QueryListPresentation.readable(candidate)
                            && Objects.equals(field.fieldName(), candidate.getFieldName())
                            && Objects.equals(field.fieldType(), candidate.getFieldType())
                            && Objects.equals(field.name(), candidate.getTitle())))
                throw new IllegalArgumentException("目标栏目的展示字段未就绪：" + field.name());
        }
        if (request.fixedList()) requireFixedList(code, version, sourcePath, source.containerSelector(),
                request, target, true);
        else if (target.getListPageUrl() == null || !target.getListPageUrl().contains("{pageIndex}"))
            throw new IllegalArgumentException("目标栏目须配置分页列表路由");
        var evidence = candidates(site, code, version, menuId, sourcePath, source.containerSelector());
        String id = UUID.nameUUIDFromBytes(json.writeValueAsString(List.of(site, code, version,
                menuId, layout.sourceQueryId(), layout.navigationPlanId(), applied.planRevision(), applied.revision(),
                evidence.fieldConfigDigest(), evidence.routeDigest(), target.getBizCode())).getBytes(StandardCharsets.UTF_8)).toString();
        return new Snapshot(3, id, site, code, version, source.sourcePlanId(), sourcePath,
                evidence.digest(), menuId, target.getBizCode(), source.containerSelector(),
                evidence.fieldConfigDigest(), evidence.routeDigest(), links.getListUrl(target, 1),
                List.of(), List.of(), List.of(), view, source.pageSize(), layout);
    }

    static void requireCurrentSharedNavigation(SiteModelPlanService.Plan reviewed,
            SiteModelNavigationService.Navigation applied) {
        if (!"CONFIRMED".equals(reviewed.status())
                || applied.status() != SiteModelNavigationService.Status.APPLIED
                || applied.planRevision() != reviewed.revision())
            throw new IllegalArgumentException("共享列表使用的导航接续已过期，请应用当前已确认结构并重新预览");
    }

    private Snapshot save(Snapshot result) {
        var path = file(result.code(), result.id());
        byte[] bytes = json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("查询接线元数据过大");
        try {
            if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return read(result.siteId(), result.code(), result.packageVersion(), result.id());
            checkDirectory(path.getParent());
            java.nio.file.Files.createDirectories(path.getParent());
            checkDirectory(path.getParent());
            try (var entries = java.nio.file.Files.list(path.getParent())) {
                if (entries.filter(p -> p.getFileName().toString().endsWith(".json"))
                                .limit(1001)
                                .count()
                        >= 1000) throw new IllegalArgumentException("项目查询计划已达1000份，请整理后再确认");
            }
            var temp = java.nio.file.Files.createTempFile(path.getParent(), "query-", ".tmp");
            try {
                java.nio.file.Files.write(temp, bytes);
                java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("查询绑定保存失败", error);
        }
        return result;
    }

    public static final class QueryInputException extends IllegalArgumentException {
        public QueryInputException(String message) {
            super(message);
        }

        public QueryInputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record Resolved(
            Snapshot snapshot, Map<String, Object> filters, Map<String, Object> form) {}

    /** 只接收服务器已绑定的列表上下文；不从JSON或请求站点参数创建身份。 */
    public Resolved resolveQuery(
            Long siteId,
            String code,
            String version,
            String id,
            com.xinhongru.web.resonseVo.IdEntity context) {
        var snapshot = read(siteId, code, version, id);
        if (context == null
                || !Objects.equals(context.getServerSiteProjectId(), siteId)
                || !Objects.equals(context.getSiteProjectId(), siteId)
                || context.getClassId() == null
                || !Objects.equals(context.getClassId().longValue(), snapshot.menuId()))
            throw new IllegalArgumentException("查询上下文必须来自当前站点实际列表路由");
        var received = new LinkedHashMap<String, String>(context.getQueryValues());
        var legacy = context.getConditionVo();
        if (legacy != null) {
            addCondition(received, "key", legacy.getKey());
            addCondition(received, "keywords", legacy.getKeywords());
            addCondition(received, "type", legacy.getType());
            addCondition(received, "area", legacy.getArea());
            addCondition(received, "year", legacy.getYear());
            addCondition(received, "month", legacy.getMonth());
            addCondition(received, "yearString", legacy.getYearString());
            addCondition(received, "classIds", legacy.getClassIds());
        }
        var acceptedKeys = new HashSet<String>(snapshot.allowedFilterKeys());
        for (String field : snapshot.allowedFilterKeys())
            if (com.xinhongru.content.model.ContentExtensionFields.isName(field))
                acceptedKeys.add("op_" + field);
        if (!snapshot.allowedSortKeys().isEmpty())
            acceptedKeys.addAll(List.of("cmsSort", "sortField", "sortDirection"));
        for (String key : received.keySet())
            if (!acceptedKeys.contains(key)) throw new QueryInputException("请求包含未确认的查询条件");
        var filters = new LinkedHashMap<String, Object>();
        var values = new LinkedHashMap<String, String>();
        var selected = new LinkedHashMap<String, Boolean>();
        var controls =
                candidates(
                                siteId,
                                code,
                                version,
                                snapshot.menuId(),
                                snapshot.sourcePath(),
                                snapshot.containerSelector())
                        .controls();
        for (var mapping : snapshot.mappings()) {
            if (!"FILTER".equals(mapping.role())) continue;
            String value = received.getOrDefault(mapping.fieldName(), "");
            if (!com.xinhongru.content.model.ContentExtensionFields.isName(mapping.fieldName()))
                value = value.trim();
            String requestedOp = received.get("op_" + mapping.fieldName());
            if (requestedOp != null && !requestedOp.equals(mapping.operator()))
                throw new QueryInputException("请求筛选操作未确认");
            var control =
                    controls.stream()
                            .filter(row -> row.id().equals(mapping.controlId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("控件证据已变化"));
            if (!value.isEmpty()
                    && control.kind().equals("SELECT")
                    && !mapping.values().containsValue(value))
                throw new QueryInputException("选项值未在此控件明确映射");
            values.put(mapping.fieldName(), value);
            if (!value.isEmpty()) {
                filters.put(mapping.fieldName(), value);
                if (com.xinhongru.content.model.ContentExtensionFields.isName(mapping.fieldName()))
                    filters.put("op_" + mapping.fieldName(), mapping.operator());
            }
            int index = 0;
            for (var option : control.options()) {
                String canonical =
                        option.value().isEmpty() ? "" : mapping.values().get(option.value());
                selected.put(
                        mapping.controlId() + ":" + (index++), Objects.equals(canonical, value));
            }
        }
        var sortMapping =
                snapshot.mappings().stream()
                        .filter(mapping -> "SORT".equals(mapping.role()))
                        .findFirst()
                        .orElse(null);
        if (sortMapping != null) {
            String canonical = null;
            if (received.containsKey("cmsSort")) {
                if (received.containsKey("sortField") || received.containsKey("sortDirection"))
                    throw new QueryInputException("排序参数混合或重复");
                String choice = received.get("cmsSort");
                if (!choice.isEmpty()) {
                    canonical = sortMapping.values().get(choice);
                    if (canonical == null) throw new QueryInputException("排序选项未明确映射");
                }
            } else if (received.containsKey("sortField")) {
                canonical =
                        received.get("sortField")
                                + "|"
                                + received.getOrDefault("sortDirection", "asc");
                if (!sortMapping.values().containsValue(canonical))
                    throw new QueryInputException("排序字段或方向未在控件确认");
            } else if (received.containsKey("sortDirection"))
                throw new QueryInputException("缺少排序字段");
            if (canonical != null) {
                String[] parts = sortParts(canonical);
                filters.put("sortField", parts[0]);
                filters.put("sortDirection", parts[1]);
            }
            var control =
                    controls.stream()
                            .filter(row -> row.id().equals(sortMapping.controlId()))
                            .findFirst()
                            .orElseThrow();
            int index = 0;
            for (var option : control.options()) {
                String target =
                        option.value().isEmpty() ? null : sortMapping.values().get(option.value());
                selected.put(
                        sortMapping.controlId() + ":" + (index++),
                        Objects.equals(target, canonical));
            }
        }
        var stringFilters = new LinkedHashMap<String, String>();
        filters.forEach((key, value) -> stringFilters.put(key, (String) value));
        var currentMenu = menu(siteId, snapshot.menuId());
        if (!ContentFieldConfigDigest.of(currentMenu).equals(snapshot.fieldConfigDigest()))
            throw new IllegalArgumentException("查询字段配置已变化");
        try {
            queries.buildModelCriteria(
                    currentMenu, stringFilters, new com.xinhongru.dto.cond.ContentCond());
        } catch (IllegalArgumentException error) {
            throw new QueryInputException(error.getMessage(), error);
        }
        return new Resolved(
                snapshot,
                Collections.unmodifiableMap(filters),
                Map.of(
                        "values",
                        Collections.unmodifiableMap(values),
                        "selected",
                        Collections.unmodifiableMap(selected)));
    }

    private static String[] sortParts(String target) {
        if (target == null || target.length() > 150) throw new IllegalArgumentException("排序映射无效");
        String[] parts = target.split("\\|", -1);
        if (parts.length != 2 || !Set.of("asc", "desc").contains(parts[1]))
            throw new IllegalArgumentException("排序方向须明确asc或desc");
        return parts;
    }

    private static void addCondition(Map<String, String> values, String key, Object value) {
        if (value != null) values.put(key, String.valueOf(value));
    }

    public Snapshot read(Long siteId, String code, String version, String id) {
        return read(siteId, code, version, id, true);
    }

    private Snapshot read(Long siteId, String code, String version, String id, boolean allowShared) {
        var path = file(code, id);
        try {
            checkDirectory(path.getParent());
            if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || java.nio.file.Files.isSymbolicLink(path)
                    || java.nio.file.Files.size(path) > 1024 * 1024)
                throw new IllegalArgumentException("查询绑定不存在或不安全");
            var saved = json.readValue(java.nio.file.Files.readString(path), Snapshot.class);
            if (!Objects.equals(saved.siteId(), siteId)
                    || !Objects.equals(saved.code(), code)
                    || !Objects.equals(saved.packageVersion(), version)
                    || !Objects.equals(saved.id(), id))
                throw new IllegalArgumentException("查询绑定不属于当前站点或包版本");
            if (saved.sharedLayout() != null) {
                if (!allowShared || !saved.equals(sharedSnapshot(siteId, code, version, saved.menuId(),
                        saved.sourcePath(), saved.sharedLayout())))
                    throw new IllegalArgumentException("共享版式来源、栏目字段或路由已变化");
                return saved;
            }
            var current =
                    candidates(
                            siteId,
                            code,
                            version,
                            saved.menuId(),
                            saved.sourcePath(),
                            saved.containerSelector());
            if (!Objects.equals(current.digest(), saved.digest())
                    || !Objects.equals(current.fieldConfigDigest(), saved.fieldConfigDigest())
                    || !Objects.equals(current.routeDigest(), saved.routeDigest()))
                throw new IllegalArgumentException("查询来源、字段配置或路由已变化，请重新确认");
            boolean timeline = saved.presentation() != null && saved.presentation().request().timeline() != null;
            boolean fixedList = saved.presentation() != null && saved.presentation().request().fixedList();
            if (fixedList) requireFixedList(code, version, saved.sourcePath(), saved.containerSelector(),
                    saved.presentation().request(), menu(siteId, saved.menuId()), saved.mappings().isEmpty());
            if (!timeline) requireSourcePlan(
                    siteId,
                    code,
                    version,
                    saved.sourcePlanId(),
                    saved.menuId(),
                    saved.sourcePath(),
                    saved.digest(),
                    saved.presentation() != null, true);
            if (saved.mappings().isEmpty()
                    && (saved.presentation() == null
                            || !timeline && !fixedList && saved.presentation().request().pagination() == null
                            || !saved.allowedFilterKeys().isEmpty()
                            || !saved.allowedSortKeys().isEmpty()))
                throw new IllegalArgumentException("无筛选查询必须保留完整卡片与分页");
            if (timeline && (!saved.mappings().isEmpty() || !"body".equals(saved.containerSelector())))
                throw new IllegalArgumentException("已保存的时间轴含不支持的筛选控件");
            if (saved.presentation() != null) {
                var currentRows =
                        timeline ? confirmTimeline(siteId, code, version, saved.sourcePlanId(), saved.sourcePath(),
                                menu(siteId, saved.menuId()), saved.presentation().request()) : presentation.confirm(
                                siteId,
                                code,
                                version,
                                saved.sourcePlanId(),
                                saved.presentation().request(),
                                saved.containerSelector(), true, saved.mappings().isEmpty()
                                        && "body".equals(saved.containerSelector())
                                        && saved.presentation().request().pagination() != null);
                if (!currentRows.equals(saved.presentation()))
                    throw new IllegalArgumentException("卡片显示来源、脚本依赖或字段配置已变化");
            }
            return saved;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("查询绑定无法读取", error);
        }
    }

    public QueryTimeline.Evidence timelineEvidence(
            Long siteId, String code, String version, Long menuId, String sourcePath, String planId) {
        var candidates = candidates(siteId, code, version, menuId, sourcePath, "body");
        var approved = siteModels.get(siteId, code, version, planId);
        if (!"CONFIRMED".equals(approved.status()))
            throw new IllegalArgumentException("请选择当前站点已确认的整站模型计划");
        var listModels = approved.proposal().models().stream()
                .filter(model -> "LIST".equals(model.kind()) && Objects.equals(model.menuId(), menuId))
                .map(SiteModelPlanService.Model::key).collect(java.util.stream.Collectors.toSet());
        var regionIds = approved.proposal().regions().stream()
                .filter(region -> "LIST".equals(region.mode()) && Objects.equals(sourcePath, region.sourcePath())
                        && listModels.contains(region.modelKey()))
                .map(SiteModelPlanService.RegionUse::regionId).collect(java.util.stream.Collectors.toSet());
        if (regionIds.isEmpty()) throw new IllegalArgumentException("该来源没有当前栏目的已确认列表区域");
        var page = packages.readStaticPage(code, version, sourcePath);
        var evidence = siteEvidence.page(code, version, sourcePath);
        if (!Objects.equals(page.digest(), candidates.digest()))
            throw new IllegalArgumentException("核对期间来源已变化");
        var document = Jsoup.parse(page.html());
        var targets = new java.util.HashMap<String, String>();
        for (var link : evidence.fragmentLinks()) targets.put(link.rawValue(), link.targetPath());
        var reader = packages.openStaticPageReader(code, version);
        var fragments = new java.util.HashMap<String, java.util.Optional<String>>();
        var layouts = new java.util.LinkedHashSet<QueryTimeline.Layout>();
        for (var region : evidence.regions()) {
            if (!regionIds.contains(region.id())) continue;
            layouts.addAll(QueryTimeline.layouts(document, region));
            layouts.addAll(SharedPanelTimeline.layouts(document, region, targets, path -> {
                if (fragments.size() >= 16 && !fragments.containsKey(path)) return null;
                return fragments.computeIfAbsent(path, key -> {
                    try {
                        var html = reader.read(key).html();
                        return html.length() <= 128 * 1024 ? java.util.Optional.of(html) : java.util.Optional.empty();
                    } catch (IllegalArgumentException ignored) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            }));
        }
        return new QueryTimeline.Evidence(candidates, List.copyOf(layouts), QueryTimeline.fields(menu(siteId, menuId)));
    }

    private QueryListPresentation.Plan confirmTimeline(Long site, String code, String version, String planId,
            String sourcePath, OneshotEntity menu, QueryListPresentation.Request request) {
        if (request.pairing() != null || request.cardId() != null || request.pagination() != null
                || request.categorySidebar() != null || request.deferredRegion() != null
                || request.actions() != null && !request.actions().isEmpty()
                || request.bindings() != null && !request.bindings().isEmpty()
                || request.sharedStatic() != null && !request.sharedStatic().isEmpty())
            throw new IllegalArgumentException("时间轴布局不能混用卡片、详情、筛选或分页");
        if (menu.getListPageUrl() == null || menu.getListPageUrl().isBlank()
                || menu.getListPageUrl().contains("{pageIndex}"))
            throw new IllegalArgumentException("请先为时间轴栏目配置单一列表路由");
        var timeline = request.timeline();
        var evidence =
                timelineEvidence(site, code, version, menu.getId().longValue(), sourcePath, planId);
        var matches =
                evidence.layouts().stream().filter(layout -> layout.matches(timeline)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("选择的时间轴结构在已确认来源中不唯一或已变化");
        var fields =
                evidence.fields().stream()
                        .filter(
                                field ->
                                        (Objects.equals(field.fieldName(), timeline.groupField())
                                                        && "SingleLine".equals(field.fieldType()))
                                                || ((Objects.equals(field.fieldName(), timeline.rowField())
                                                        || Objects.equals(field.fieldName(), timeline.secondaryField()))
                                                        && ("SingleLine".equals(field.fieldType())
                                                                || "MultiLine"
                                                                        .equals(field.fieldType())))
                                                || (Objects.equals(
                                                                field.fieldName(),
                                                                timeline.imageField())
                                                        && "Image".equals(field.fieldType())))
                        .toList();
        if (Objects.equals(timeline.groupField(), timeline.rowField())
                || fields.stream()
                        .noneMatch(
                                field -> Objects.equals(field.fieldName(), timeline.groupField()) && "SingleLine".equals(field.fieldType()))
                || fields.stream()
                        .noneMatch(field -> Objects.equals(field.fieldName(), timeline.rowField())
                                && ("SingleLine".equals(field.fieldType()) || "MultiLine".equals(field.fieldType()))))
            throw new IllegalArgumentException("请选择两个不同的启用字段，分组须为单行文本");
        if ((timeline.rowSecondarySelector() == null) != (timeline.secondaryField() == null)
                || timeline.secondaryField() != null && (timeline.secondaryField().equals(timeline.groupField())
                || timeline.secondaryField().equals(timeline.rowField())
                || fields.stream().noneMatch(field -> field.fieldName().equals(timeline.secondaryField())
                        && ("SingleLine".equals(field.fieldType()) || "MultiLine".equals(field.fieldType())))))
            throw new IllegalArgumentException("请为卡片的辅助文本选择独立且已启用的文本字段");
        if (timeline.imageField() != null && (timeline.rowImageSelector() == null
                || fields.stream().noneMatch(field -> field.fieldName().equals(timeline.imageField())
                        && "Image".equals(field.fieldType()))))
            throw new IllegalArgumentException("记录图片须对应已审核的行图片和启用的单图字段");
        return new QueryListPresentation.Plan(
                request,
                timeline.groupSelector(),
                timeline.rowSelector(),
                matches.getFirst().sourceRows(),
                List.of(),
                fields,
                List.of());
    }

    private void requireSourcePlan(
            Long siteId,
            String code,
            String version,
            String id,
            Long menuId,
            String sourcePath,
            String digest,
            boolean hasPresentation, boolean saved) {
        var plan = saved ? plans.readSavedDisplaySource(siteId, code, version, id) : plans.read(siteId, code, version, id);
        if (Objects.equals(plan.menuId(), menuId) && "CONFIRMED".equals(plan.status())) {
            if (plan.origins() != null
                    && plan.origins().stream()
                            .anyMatch(
                                    origin ->
                                            "LIST".equals(origin.role())
                                                    && sourcePath.equals(origin.sourcePath())
                                                    && digest.equals(origin.digest()))) return;
            // 独立详情只作为显示的来源证据；QueryListPresentation 会验证同卡真实链接。
            if (hasPresentation && QueryListPresentation.independentDetail(plan)) return;
        }
        throw new IllegalArgumentException("请选择同站同栏目已确认的双来源列表计划或独立详情计划");
    }

    private void requireFixedList(String code, String version, String source, String selector,
            QueryListPresentation.Request request, OneshotEntity menu, boolean pageOnly) {
        if (!pageOnly || !"body".equals(selector) || request.pagination() != null || request.timeline() != null
                || request.deferredRegion() != null || menu.getListPageUrl() == null
                || menu.getListPageUrl().contains("{pageIndex}") || menu.getListPageUrl().contains("{pageSize}"))
            throw new IllegalArgumentException("无分页列表需要固定路由且没有查询映射或分页配置");
        var document = org.jsoup.Jsoup.parse(packages.readStaticPage(code, version, source).html());
        if (!document.select("form,input,select,textarea,button,[data-cms-pagination],.pagination,.pager").isEmpty())
            throw new IllegalArgumentException("原页存在筛选或分页控件，须先核对并确认用途");
    }

    private Path file(String code, String id) {
        if (code == null
                || !code.matches("[A-Za-z0-9_-]{1,100}")
                || id == null
                || !id.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("项目或查询计划ID无效");
        return metadataRoot.resolve(code).resolve("query-bindings").resolve(id + ".json");
    }

    private void checkDirectory(Path directory) {
        for (Path current = directory;
                current != null && current.startsWith(metadataRoot);
                current = current.getParent())
            if (java.nio.file.Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (!java.nio.file.Files.isDirectory(
                                    current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            || java.nio.file.Files.isSymbolicLink(current)))
                throw new IllegalArgumentException("查询元数据目录不安全");
    }

    public record Option(String label, String value) {}

    public record Control(
            String id,
            String selector,
            String kind,
            String tag,
            String name,
            String value,
            String label,
            String href,
            List<Option> options,
            String status,
            String reason) {}

    public record Candidates(
            String sourcePath,
            String digest,
            Long menuId,
            String fieldConfigDigest,
            String routeDigest,
            String containerSelector,
            List<Control> controls,
            boolean truncated,
            List<String> warnings) {}

    public Candidates candidates(
            Long siteId,
            String code,
            String version,
            Long menuId,
            String sourcePath,
            String selector) {
        var menu = menu(siteId, menuId);
        if (sourcePath == null || sourcePath.length() > 1000)
            throw new IllegalArgumentException("来源路径无效");
        if (selector == null || !selector.matches("[a-zA-Z0-9_.# -]{1,200}") || selector.isBlank())
            throw new IllegalArgumentException("请选择简单的ID、class或标签容器，不支持伪类等复杂表达式");
        var source = packages.readStaticPage(code, version, sourcePath);
        var doc = Jsoup.parse(source.html());
        if (!doc.select("input[form],select[form],textarea[form],button[form]").isEmpty())
            throw new IllegalArgumentException("form关联控件仍待处理，不能静默提交未映射参数");
        var scopes = new ArrayList<Element>();
        var warnings = new ArrayList<>(source.warnings());
        boolean[] limited = {false};
        int[] count = {0};
        NodeTraversor.filter(
                new NodeFilter() {
                    public FilterResult head(Node node, int depth) {
                        if (++count[0] > 2000) {
                            limited[0] = true;
                            return FilterResult.STOP;
                        }
                        if (depth > 16) {
                            limited[0] = true;
                            return FilterResult.SKIP_CHILDREN;
                        }
                        if (node instanceof Element el && el.is(selector)) scopes.add(el);
                        return FilterResult.CONTINUE;
                    }

                    public FilterResult tail(Node node, int depth) {
                        return FilterResult.CONTINUE;
                    }
                },
                doc.body());
        if (scopes.size() != 1) throw new IllegalArgumentException("本次有界范围内控件容器不唯一或不存在，请明确容器");
        var controls = new ArrayList<Control>();
        count[0] = 0;
        NodeTraversor.filter(
                new NodeFilter() {
                    public FilterResult head(Node node, int depth) {
                        if (++count[0] > 2000) {
                            limited[0] = true;
                            return FilterResult.STOP;
                        }
                        if (depth > 16) {
                            limited[0] = true;
                            return FilterResult.SKIP_CHILDREN;
                        }
                        if (!(node instanceof Element el)) return FilterResult.CONTINUE;
                        if (Set.of("script", "style", "template", "noscript", "nav", "aside")
                                .contains(el.tagName())) return FilterResult.SKIP_CHILDREN;
                        if (Set.of("input", "select", "button", "a").contains(el.tagName())) {
                            if (controls.size() >= 100) {
                                limited[0] = true;
                                return FilterResult.STOP;
                            }
                            controls.add(control(el, sourcePath, source.digest()));
                        }
                        return FilterResult.CONTINUE;
                    }

                    public FilterResult tail(Node node, int depth) {
                        return FilterResult.CONTINUE;
                    }
                },
                scopes.getFirst());
        if (limited[0]) warnings.add("控件检查范围不足：最多2000节点、16层、100控件，其余未覆盖，不能据此认定没有控件");
        if ("body".equals(selector) && !QueryListPresentation.sharedSearchShell(doc).isEmpty())
            warnings.add(QueryListPresentation.SHARED_SEARCH_NOTICE);
        warnings.add("仅静态候选证据；尚未确认正式字段、字面值映射或执行查询，未知脚本行为仍待处理");
        return new Candidates(
                sourcePath,
                source.digest(),
                menuId,
                ContentFieldConfigDigest.of(menu),
                hash(
                        menu.getBizCode()
                                + "\n"
                                + menu.getListPageUrl()
                                + "\n"
                                + menu.getDetailPageUrl()),
                selector,
                List.copyOf(controls),
                limited[0],
                List.copyOf(warnings));
    }

    private Control control(Element el, String sourcePath, String digest) {
        String kind =
                switch (el.tagName()) {
                    case "select" -> "SELECT";
                    case "button" ->
                            el.attr("type").isBlank() || el.attr("type").equalsIgnoreCase("submit")
                                    ? "SUBMIT"
                                    : "UNSUPPORTED";
                    case "a" -> "LINK";
                    default ->
                            switch (el.attr("type").toLowerCase(Locale.ROOT)) {
                                case "submit" -> "SUBMIT";
                                case "hidden" -> "HIDDEN";
                                case "checkbox", "radio" -> "CHOICE";
                                case "", "text", "search", "number", "date", "datetime-local" ->
                                        "TEXT";
                                default -> "UNSUPPORTED";
                            };
                };
        var options = new ArrayList<Option>();
        String reason = "需开发者明确字段与原值映射，尚未接通查询";
        boolean eligible = true;
        for (var attribute : el.attributes())
            if (attribute.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                eligible = false;
                reason = "存在原事件处理，不能自动推断或运行JavaScript";
            }
        if (el.tagName().equals("select")) {
            for (var child : el.getAllElements())
                if (child.tagName().equals("option")) {
                    if (options.size() >= 50) {
                        eligible = false;
                        reason = "选项超过50项，其余待处理";
                        break;
                    }
                    options.add(
                            new Option(
                                    shortText(child.text()),
                                    child.hasAttr("value") ? child.attr("value") : child.text()));
                }
        }
        Element form = el.closest("form");
        if (kind.equals("LINK")) {
            eligible = false;
            reason = "链接不自动视作筛选、排序或分页；须明确角色及本地参数语义";
        } else if (form == null
                || !(form.attr("method").isBlank()
                        || form.attr("method").equalsIgnoreCase("get"))) {
            eligible = false;
            reason = "缺少原生GET表单；需要明确确认结构改造，不能执行原脚本";
        } else if (!localAction(sourcePath, form.attr("action"))) {
            eligible = false;
            reason = "表单action含外部、混合query或其他页面，仍待处理";
        } else if (!kind.equals("SUBMIT") && el.attr("name").isBlank()) {
            eligible = false;
            reason = "原控件没有参数名，需明确单字段含义，不能从placeholder猜多字段OR";
        }
        if (kind.equals("HIDDEN") || kind.equals("UNSUPPORTED")) {
            eligible = false;
            reason = "隐藏状态或不支持的控件不是自动筛选字段";
        }
        if (el.hasAttr("multiple")
                || el.hasAttr("formaction")
                || el.hasAttr("formmethod")
                || el.hasAttr("formtarget")) {
            eligible = false;
            reason = "多值或覆盖表单行为仍待处理";
        }
        if (el.hasAttr("disabled")) {
            eligible = false;
            reason = "原控件停用，不能自动启用";
        }
        if (el.tagName().equals("input")
                && Set.of("date", "datetime-local")
                        .contains(el.attr("type").toLowerCase(Locale.ROOT))) {
            eligible = false;
            reason = "原生日期控件的日期精度与提交格式尚未适配，仍待处理";
        }
        String selector = el.cssSelector();
        String label =
                el.hasAttr("aria-label")
                        ? el.attr("aria-label")
                        : el.hasAttr("placeholder") ? el.attr("placeholder") : el.ownText();
        return new Control(
                hash(digest + "\n" + selector).substring(0, 24),
                selector,
                kind,
                el.tagName(),
                el.attr("name"),
                el.attr("value"),
                shortText(label),
                el.attr("href"),
                List.copyOf(options),
                eligible ? "CANDIDATE" : "PENDING",
                reason);
    }

    private static boolean localAction(String source, String action) {
        if (action.isBlank()) return true;
        if (action.contains(":")
                || action.startsWith("//")
                || action.contains("?")
                || action.contains("#")
                || action.contains("\\")
                || action.contains("%")) return false;
        Path relative =
                action.startsWith("/")
                        ? Path.of(action.substring(1))
                        : Path.of(source).resolveSibling(action);
        return relative.normalize().toString().replace('\\', '/').equals(source);
    }

    private OneshotEntity menu(Long siteId, Long menuId) {
        if (siteId == null
                || siteId <= 0
                || menuId == null
                || menuId <= 0
                || menuId > Integer.MAX_VALUE) throw new IllegalArgumentException("站点或栏目无效");
        var menu = menus.selectActiveMenuModelBySite(siteId, menuId.intValue());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getId().longValue(), menuId)
                || !Integer.valueOf(1).equals(menu.getPageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel()))
            throw new IllegalArgumentException("请选择当前站点可管理的内容列表栏目");
        return menu;
    }

    private static String shortText(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

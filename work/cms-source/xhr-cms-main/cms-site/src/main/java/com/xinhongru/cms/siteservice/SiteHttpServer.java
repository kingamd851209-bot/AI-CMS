package com.xinhongru.cms.siteservice;

import com.xinhongru.cms.template.TemplatePreviewService;
import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.preview.PreviewAccessGuard;
import com.xinhongru.security.upload.UploadDelivery;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.utils.RequestOriginUtils;
import com.xinhongru.web.resonseVo.IdEntity;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个站点的 HTTP 服务
 *
 * <p>使用 Undertow 作为嵌入式服务器，支持： - 静态资源服务 (UI Package) - 模板渲染 (Thymeleaf) - URL 路由映射到栏目模板
 */
@Slf4j
public class SiteHttpServer {
    private PublicSearchRoutes publicSearchRoutes;
    private com.xinhongru.cms.template.PublicBindingRootRoutes publicRootRoutes;

    public void setPublicRootRoutes(com.xinhongru.cms.template.PublicBindingRootRoutes routes) {
        this.publicRootRoutes = routes;
    }

    public void setPublicSearchRoutes(PublicSearchRoutes routes) {
        this.publicSearchRoutes = routes;
    }

    private com.xinhongru.cms.integration.SiteExternalIntegrationOutput externalIntegrationOutput;

    public void setExternalIntegrationOutput(
            com.xinhongru.cms.integration.SiteExternalIntegrationOutput output) {
        if (running) throw new IllegalStateException("站点运行中不能替换输出接缝");
        this.externalIntegrationOutput = output;
    }

    /** ${className} 为栏目名称字面量占位符（非数字参数，解析为栏目名参与静态匹配）。 */
    private static final String CLASSNAME_PLACEHOLDER = "${className}";

    private static final Set<String> URL_PLACEHOLDERS =
            Set.of(
                    "{parentId}",
                    "{classId}",
                    "{pageIndex}",
                    "{pageSize}",
                    "{id}",
                    "{productId}",
                    CLASSNAME_PLACEHOLDER);

    /** 站点根栏目自动生成的 "/" 路由说明 */
    private static final String ROOT_ROUTE_DESCRIPTION = "站点根栏目默认首页，系统自动生成";

    private final Long siteId;
    private final String projectCode;
    private final int port;
    private final String siteDomain;
    private final String bindHost;
    private final boolean preferLocalhost;
    private final PreviewAccessGuard previewAccessGuard;
    private final UiPackageStorageService storageService;
    private final MissingIconLinks missingIconLinks;
    private final RetainedNavigationText retainedNavigationText;
    private final JsonNode backOfficeOnly;
    private final ReviewedHeaderSearch reviewedHeaderSearch;
    private final TemplatePreviewService previewService;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;
    private final SiteRequestLogger requestLogger;
    private final TemplateStorageService templateStorageService;

    /** 独立进程模式下由管理节点下发的内部控制令牌；统一进程模式为 null，不开放内部接口。 */
    private final String controlToken;

    private final SiteRouteRegistry routeRegistry;

    private Undertow server;
    private volatile boolean running = false;
    private SiteRouteContext routeContext;

    // 静态列表页路由缓存: url -> RoutePattern (无占位符，精确匹配)
    private final Map<String, RoutePattern> listUrlRouteMap = new HashMap<>();
    // 动态列表页路由模式缓存 (有占位符，正则匹配)
    private final List<RoutePattern> listRoutePatterns = new ArrayList<>();
    // 静态详情页路由缓存: url -> RoutePattern (无占位符，精确匹配)
    private final Map<String, RoutePattern> detailUrlRouteMap = new HashMap<>();
    // 动态详情页路由模式缓存 (有占位符，正则匹配)
    private final List<RoutePattern> detailRoutePatterns = new ArrayList<>();

    // 所有路由配置列表（用于显示，包括重复的共享路由）
    private final List<RoutePattern> allRoutePatterns = new ArrayList<>();

    // 路由冲突记录: pattern -> 冲突的栏目列表
    private final List<RouteConflict> routeConflicts = new ArrayList<>();

    /** URL 路由模式 */
    private static class RoutePattern {
        final Pattern pattern;
        final String originalUrl; // 原始 URL
        final OneshotEntity column;
        final List<String> paramNames; // 参数名列表，按捕获组顺序
        final String templatePath;
        final boolean isDetailPage;
        final String description; // 路由说明，仅系统自动生成的路由有值

        RoutePattern(
                Pattern pattern,
                String originalUrl,
                OneshotEntity column,
                List<String> paramNames,
                String templatePath,
                boolean isDetailPage,
                String description) {
            this.pattern = pattern;
            this.originalUrl = originalUrl;
            this.column = column;
            this.paramNames = paramNames;
            this.templatePath = templatePath;
            this.isDetailPage = isDetailPage;
            this.description = description;
        }
    }

    /** URL 匹配结果 */
    private static class UrlMatchResult {
        final RoutePattern routePattern;
        final Map<String, String> params;

        UrlMatchResult(RoutePattern routePattern, Map<String, String> params) {
            this.routePattern = routePattern;
            this.params = params;
        }
    }

    /** 请求处理结果：是否处理成功 + 分类 + 栏目ID */
    private static final class MatchOutcome {
        final boolean served;
        final String category;
        final Integer columnId;

        private MatchOutcome(boolean served, String category, Integer columnId) {
            this.served = served;
            this.category = category;
            this.columnId = columnId;
        }

        static MatchOutcome miss() {
            return new MatchOutcome(false, null, null);
        }

        static MatchOutcome served(String category, Integer columnId) {
            return new MatchOutcome(true, category, columnId);
        }
    }

    // 请求分类常量
    private static final String CATEGORY_ASSETS_STATIC = "assets_static";
    private static final String CATEGORY_UI_STATIC = "ui_static";
    private static final String CATEGORY_COLUMN_LIST = "column_list";
    private static final String CATEGORY_COLUMN_DETAIL = "column_detail";
    private static final String CATEGORY_UI_PACKAGE_INACTIVE = "ui_package_inactive";
    private static final String CATEGORY_NOT_FOUND = "not_found";
    private static final String CATEGORY_ERROR = "error";
    private static final String CATEGORY_CUSTOM = "custom_route";

    private static final String INTERNAL_ROUTES = "__internal/routes";
    private static final String INTERNAL_RELOAD_ROUTES = "__internal/reload-routes";
    private static final String INTERNAL_TOKEN_HEADER = "X-Site-Service-Token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SiteHttpServer(
            Long siteId,
            String projectCode,
            int port,
            String siteDomain,
            UiPackageStorageService storageService,
            TemplatePreviewService previewService,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            TemplateStorageService templateStorageService,
            PreviewAccessGuard previewAccessGuard) {
        this(
                siteId,
                projectCode,
                port,
                siteDomain,
                storageService,
                previewService,
                oneShotMapper,
                contentMapper,
                templateStorageService,
                null,
                null,
                false,
                previewAccessGuard,
                "0.0.0.0");
    }

    public SiteHttpServer(
            Long siteId,
            String projectCode,
            int port,
            String siteDomain,
            UiPackageStorageService storageService,
            TemplatePreviewService previewService,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            TemplateStorageService templateStorageService,
            String controlToken,
            SiteRouteRegistry routeRegistry,
            boolean preferLocalhost,
            PreviewAccessGuard previewAccessGuard,
            String bindHost) {
        this(siteId, projectCode, port, siteDomain, storageService, previewService, oneShotMapper,
                contentMapper, templateStorageService, controlToken, routeRegistry, preferLocalhost,
                previewAccessGuard, bindHost, null);
    }

    SiteHttpServer(
            Long siteId,
            String projectCode,
            int port,
            String siteDomain,
            UiPackageStorageService storageService,
            TemplatePreviewService previewService,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            TemplateStorageService templateStorageService,
            String controlToken,
            SiteRouteRegistry routeRegistry,
            boolean preferLocalhost,
            PreviewAccessGuard previewAccessGuard,
            String bindHost,
            Path publishedOutputPath) {
        this.previewAccessGuard = Objects.requireNonNull(previewAccessGuard);
        this.siteId = siteId;
        this.projectCode = projectCode;
        this.port = ServicePortRange.requireValid(port);
        this.siteDomain = siteDomain;
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException("站点监听地址不能为空");
        }
        this.bindHost = bindHost.trim();
        this.preferLocalhost = preferLocalhost;
        this.storageService = storageService;
        this.missingIconLinks = new MissingIconLinks(storageService.root(), projectCode);
        this.retainedNavigationText = new RetainedNavigationText(storageService.root(), projectCode, publishedOutputPath);
        Path visibility = storageService.root().resolve(".back-office-only-navigation.json");
        this.backOfficeOnly = Files.isRegularFile(visibility, LinkOption.NOFOLLOW_LINKS)
                ? new ObjectMapper().readTree(visibility.toFile()).path(projectCode)
                : MissingNode.getInstance();
        this.reviewedHeaderSearch = new ReviewedHeaderSearch(storageService.root(), projectCode);
        this.previewService = previewService;
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
        this.templateStorageService = templateStorageService;
        this.controlToken = controlToken;
        this.routeRegistry = routeRegistry;
        this.requestLogger =
                new SiteRequestLogger(storageService.root().resolve(projectCode).resolve("logs"));
    }

    /** 启动站点服务 */
    public synchronized void start() {
        if (running) {
            log.warn("站点服务已在运行: siteId={}, port={}", siteId, port);
            return;
        }

        // 加载 URL 路由
        loadUrlRoutes();

        // 初始化路由上下文（依赖 site 信息，在 loadUrlRoutes 之后）
        initRouteContext();

        server =
                Undertow.builder()
                        .addHttpListener(port, bindHost)
                        .setHandler(this::handleRequest)
                        .build();

        server.start();
        running = true;
        log.info("站点服务启动: siteId={}, projectCode={}, port={}", siteId, projectCode, port);
    }

    /** 停止站点服务 */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
        listUrlRouteMap.clear();
        listRoutePatterns.clear();
        detailUrlRouteMap.clear();
        detailRoutePatterns.clear();
        requestLogger.close();
        log.info("站点服务停止: siteId={}, port={}", siteId, port);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public String getAccessUrl() {
        if (!running) {
            return null;
        }
        String host =
                preferLocalhost
                        ? "127.0.0.1"
                        : (siteDomain != null && !siteDomain.isBlank()) ? siteDomain : "localhost";
        return "http://" + host + ":" + port + "/";
    }

    /** 重新加载路由表 */
    public void reloadRoutes() {
        loadUrlRoutes();
        log.info("路由表已重新加载: siteId={}", siteId);
    }

    /** 检索只认可当前已加载的匿名原生路由；未知自定义覆盖在分页前排除。 */
    public synchronized boolean hasPublicSearchRoute(String code, OneshotEntity menu) {
        if (!isRunning()
                || !Objects.equals(projectCode, code)
                || menu == null
                || !Objects.equals(siteId, menu.getSiteProjectId())) return false;
        boolean single = Integer.valueOf(0).equals(menu.getPageType());
        String url = single ? menu.getListPageUrl() : menu.getDetailPageUrl();
        String template = single ? menu.getListPageTemplate() : menu.getDetailPageTemplate();
        if (url == null || template == null) return false;
        for (RouteConflict conflict : routeConflicts)
            for (ConflictColumn column : conflict.columns())
                if (Objects.equals(column.columnId(), menu.getId())) return false;
        String prefix = searchRoutePrefix(url);
        for (RouteHandlerEntry custom : routeRegistry.getHandlers(projectCode)) {
            String other = searchRoutePrefix(custom.getUrlPattern());
            if (prefix.startsWith(other) || other.startsWith(prefix)) return false;
        }
        List<RoutePattern> active =
                new ArrayList<>(single ? listRoutePatterns : detailRoutePatterns);
        active.addAll((single ? listUrlRouteMap : detailUrlRouteMap).values());
        for (RoutePattern route : active) {
            if (route.isDetailPage == !single
                    && Objects.equals(route.column.getId(), menu.getId())
                    && Objects.equals(route.originalUrl, url)
                    && Objects.equals(route.templatePath, template)) return true;
        }
        return false;
    }

    private static String searchRoutePrefix(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        int placeholder = normalized.indexOf('{');
        return placeholder < 0 ? normalized : normalized.substring(0, placeholder);
    }

    /** 使用当前已加载的匿名路由优先级和详情归属判断核验链接。 */
    synchronized boolean hasPublicDetailRoute(
            String code, OneshotEntity menu, int recordId, String path) {
        if (!isRunning() || !Objects.equals(projectCode, code)
                || !Objects.equals(siteId, menu.getSiteProjectId())
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || path.startsWith("__cms/") || path.startsWith("assets/")
                || routeRegistry.getHandlers(code).stream().anyMatch(handler -> handler.matches(path))
                || listUrlRouteMap.containsKey(path)
                || listRoutePatterns.stream().anyMatch(route -> route.pattern.matcher(path).matches()))
            return false;
        var match = detailUrlRouteMap.containsKey(path) ? null : matchDetailRoute(path);
        return match != null && Objects.equals(match.routePattern.column.getId(), menu.getId())
                && Objects.equals(match.routePattern.originalUrl, menu.getDetailPageUrl())
                && Objects.equals(match.routePattern.templatePath, menu.getDetailPageTemplate())
                && Integer.toString(recordId).equals(match.params.get("id"));
    }

    /** 获取路由列表（包含所有栏目，未配置路由的栏目也会返回） 排序逻辑与栏目导航一致：树形结构，同层级按 sort 排序 */
    public List<RouteInfo> getRoutes() {
        // 获取站点下所有栏目
        List<OneshotEntity> allColumns = oneShotMapper.selectListBySiteProjectId(siteId);
        if (allColumns == null || allColumns.isEmpty()) {
            return List.of();
        }

        // 构建栏目ID到栏目的映射
        Map<Integer, OneshotEntity> columnMap = new HashMap<>();
        for (OneshotEntity col : allColumns) {
            columnMap.put(col.getId(), col);
        }

        // 构建父子关系映射
        Map<Integer, List<OneshotEntity>> childrenByParent = new HashMap<>();
        for (OneshotEntity col : allColumns) {
            Integer parentId = col.getParentId();
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(col);
        }

        // 对每个父节点的子节点按 sort 排序
        for (List<OneshotEntity> children : childrenByParent.values()) {
            children.sort(Comparator.comparingInt(OneshotEntity::getSort));
        }

        // 构建栏目到路由信息的映射（使用 allRoutePatterns，包含所有路由配置）
        Map<Integer, List<RouteInfo>> routeByColumn = new HashMap<>();
        for (RoutePattern pattern : allRoutePatterns) {
            OneshotEntity col = columnMap.get(pattern.column.getId());
            if (col == null) continue;

            String patternStr;
            if (pattern.paramNames.isEmpty()) {
                // 静态路由：标准化 URL，但保留根路径为 "/"
                String normalized = normalizeUrl(pattern.originalUrl);
                patternStr = normalized.isEmpty() ? "/" : normalized;
            } else {
                // 动态路由
                patternStr = pattern.pattern.pattern();
            }

            routeByColumn
                    .computeIfAbsent(col.getId(), k -> new ArrayList<>())
                    .add(
                            new RouteInfo(
                                    patternStr,
                                    pattern.originalUrl,
                                    pattern.isDetailPage ? "detail" : "list",
                                    !pattern.paramNames.isEmpty(),
                                    col.getId(),
                                    col.getParentId(),
                                    col.getClassName(),
                                    col.getDepth(),
                                    col.getSort(),
                                    pattern.templatePath,
                                    pattern.description));
        }

        // 按树形结构遍历，生成有序列表
        List<RouteInfo> routes = new ArrayList<>();

        // 找出根节点（parentId = 0 或 parentId 不在当前栏目列表中）
        List<OneshotEntity> roots = childrenByParent.getOrDefault(0, new ArrayList<>());
        // 按 sort 排序
        roots.sort(Comparator.comparingInt(OneshotEntity::getSort));

        // 先序遍历添加路由
        for (OneshotEntity root : roots) {
            traverseAndAddRoutes(root, childrenByParent, routeByColumn, routes);
        }

        log.info(
                "返回路由总数: {}, 已配置路由={}, 未配置路由栏目={}",
                routes.size(),
                routes.stream().filter(r -> r.pattern() != null).count(),
                routes.stream().filter(r -> r.pattern() == null).count());

        return routes;
    }

    /** 递归遍历栏目树，添加路由信息 */
    private void traverseAndAddRoutes(
            OneshotEntity column,
            Map<Integer, List<OneshotEntity>> childrenByParent,
            Map<Integer, List<RouteInfo>> routeByColumn,
            List<RouteInfo> routes) {
        // 先添加当前栏目的路由（如果有的话）
        List<RouteInfo> columnRoutes = routeByColumn.get(column.getId());
        if (columnRoutes != null && !columnRoutes.isEmpty()) {
            routes.addAll(columnRoutes);
        } else {
            // 没有配置路由，添加一个占位记录
            routes.add(
                    new RouteInfo(
                            null,
                            null,
                            null,
                            false,
                            column.getId(),
                            column.getParentId(),
                            column.getClassName(),
                            column.getDepth(),
                            column.getSort(),
                            null,
                            null));
        }

        // 递归处理子栏目
        List<OneshotEntity> children = childrenByParent.get(column.getId());
        if (children != null) {
            for (OneshotEntity child : children) {
                traverseAndAddRoutes(child, childrenByParent, routeByColumn, routes);
            }
        }
    }

    /** 路由信息 */
    public record RouteInfo(
            String pattern,
            String originalUrl,
            String type,
            boolean isDynamic,
            Integer columnId,
            Integer parentId,
            String columnName,
            int depth,
            int sort,
            String templatePath,
            String description) {}

    /** 路由冲突信息 */
    public record RouteConflict(String pattern, List<ConflictColumn> columns) {}

    /** 冲突栏目信息 */
    public record ConflictColumn(Integer columnId, String columnName) {}

    /** 加载 URL 路由表 */
    private synchronized void loadUrlRoutes() {
        // 先核验根别名元数据；失败的reload保留原路由，不能丢别名后静态回退。
        List<OneshotEntity> columns = oneShotMapper.selectListBySiteProjectId(siteId);
        Map<Integer, List<String>> rootAliases = new HashMap<>();
        if (publicRootRoutes != null && columns != null)
            for (var column : columns)
                if (column.getSiteRootId() != null && column.getId().equals(column.getSiteRootId()))
                    rootAliases.put(
                            column.getId(), publicRootRoutes.aliases(siteId, projectCode, column));
        listUrlRouteMap.clear();
        listRoutePatterns.clear();
        detailUrlRouteMap.clear();
        detailRoutePatterns.clear();
        routeConflicts.clear();
        allRoutePatterns.clear();

        if (columns == null || columns.isEmpty()) {
            log.warn("站点没有栏目: siteId={}", siteId);
            return;
        }

        // 用于检测冲突：记录每个 URL 对应的所有栏目
        Map<String, List<OneshotEntity>> listUrlColumns = new HashMap<>();
        Map<String, List<OneshotEntity>> detailUrlColumns = new HashMap<>();
        Map<String, List<OneshotEntity>> dynamicListUrlColumns = new HashMap<>();
        Map<String, List<OneshotEntity>> dynamicDetailUrlColumns = new HashMap<>();

        for (OneshotEntity column : columns) {
            // 处理列表页 URL
            String listUrl = column.getListPageUrl();
            if (listUrl != null && !listUrl.isEmpty()) {
                RoutePattern pattern =
                        createRoutePattern(listUrl, column, column.getListPageTemplate(), false);
                if (pattern != null) {
                    // 添加到全部路由列表（用于显示）
                    allRoutePatterns.add(pattern);

                    if (pattern.paramNames.isEmpty()) {
                        // 静态路由
                        String normalizedUrl =
                                normalizeUrl(resolveClassNamePlaceholder(listUrl, column));
                        listUrlColumns
                                .computeIfAbsent(normalizedUrl, k -> new ArrayList<>())
                                .add(column);
                        listUrlRouteMap.put(normalizedUrl, pattern);
                    } else {
                        // 动态路由
                        String patternStr = pattern.pattern.pattern();
                        dynamicListUrlColumns
                                .computeIfAbsent(patternStr, k -> new ArrayList<>())
                                .add(column);
                        listRoutePatterns.add(pattern);
                    }
                }
            }
            // 根栏目额外添加 "/" 路由映射
            if (column.getSiteRootId() != null
                    && column.getId().equals(column.getSiteRootId())
                    && (listUrl == null
                            || listUrl.isEmpty()
                            || !normalizeUrl(resolveClassNamePlaceholder(listUrl, column))
                                    .isEmpty())) {
                // listPageUrl本身就是“/”时不重复登记自身，避免把同一栏目误判为冲突。
                RoutePattern rootPattern =
                        createRoutePattern(
                                "/",
                                column,
                                column.getListPageTemplate(),
                                false,
                                ROOT_ROUTE_DESCRIPTION);
                if (rootPattern != null) {
                    // 添加到全部路由列表（用于显示）
                    allRoutePatterns.add(rootPattern);

                    listUrlColumns.computeIfAbsent("", k -> new ArrayList<>()).add(column);
                    listUrlRouteMap.put("", rootPattern);
                }
            }

            // 仅活动根应用明确确认的入口别名；原listPageUrl与自动“/”规则保留。
            for (String alias : rootAliases.getOrDefault(column.getId(), List.of())) {
                String normalizedAlias = normalizeUrl(alias);
                String original =
                        column.getListPageUrl() == null
                                ? ""
                                : normalizeUrl(
                                        resolveClassNamePlaceholder(
                                                column.getListPageUrl(), column));
                if (normalizedAlias.equals(original) || normalizedAlias.isEmpty()) continue;
                var aliasPattern =
                        createRoutePattern(
                                alias, column, column.getListPageTemplate(), false, "已确认的CMS首页别名");
                if (aliasPattern != null) {
                    allRoutePatterns.add(aliasPattern);
                    listUrlColumns
                            .computeIfAbsent(normalizedAlias, k -> new ArrayList<>())
                            .add(column);
                    listUrlRouteMap.put(normalizedAlias, aliasPattern);
                }
            }

            // 处理详情页 URL
            String detailUrl = column.getDetailPageUrl();
            if (detailUrl != null && !detailUrl.isEmpty()) {
                RoutePattern pattern =
                        createRoutePattern(detailUrl, column, column.getDetailPageTemplate(), true);
                if (pattern != null) {
                    // 添加到全部路由列表（用于显示）
                    allRoutePatterns.add(pattern);

                    if (pattern.paramNames.isEmpty()) {
                        // 静态路由
                        String normalizedUrl =
                                normalizeUrl(resolveClassNamePlaceholder(detailUrl, column));
                        detailUrlColumns
                                .computeIfAbsent(normalizedUrl, k -> new ArrayList<>())
                                .add(column);
                        detailUrlRouteMap.put(normalizedUrl, pattern);
                    } else {
                        // 动态路由
                        String patternStr = pattern.pattern.pattern();
                        dynamicDetailUrlColumns
                                .computeIfAbsent(patternStr, k -> new ArrayList<>())
                                .add(column);
                        detailRoutePatterns.add(pattern);
                    }
                }
            }
        }

        // 检测并记录冲突
        detectAndRecordConflicts(listUrlColumns, "list");
        detectAndRecordConflicts(detailUrlColumns, "detail");
        detectAndRecordConflicts(dynamicListUrlColumns, "list");
        detectAndRecordConflicts(dynamicDetailUrlColumns, "detail");

        log.info(
                "加载 URL 路由: siteId={}, 静态列表页={}, 动态列表页={}, 静态详情页={}, 动态详情页={}, 全部路由={}, 冲突={}",
                siteId,
                listUrlRouteMap.size(),
                listRoutePatterns.size(),
                detailUrlRouteMap.size(),
                detailRoutePatterns.size(),
                allRoutePatterns.size(),
                routeConflicts.size());
    }

    /** 检测并记录路由冲突 */
    private void detectAndRecordConflicts(
            Map<String, List<OneshotEntity>> urlColumns, String type) {
        for (Entry<String, List<OneshotEntity>> entry : urlColumns.entrySet()) {
            List<OneshotEntity> cols = entry.getValue();
            if (cols.size() > 1) {
                String pattern = entry.getKey();
                List<ConflictColumn> conflictColumns =
                        cols.stream()
                                .map(c -> new ConflictColumn(c.getId(), c.getClassName()))
                                .toList();
                routeConflicts.add(new RouteConflict(pattern, conflictColumns));
                log.warn("检测到路由冲突: pattern={}, 栏目数量={}", pattern, cols.size());
            }
        }
    }

    /** 获取路由冲突列表 */
    public List<RouteConflict> getRouteConflicts() {
        return new ArrayList<>(routeConflicts);
    }

    /** 创建路由正则模式 将占位符转换为正则捕获组 */
    private RoutePattern createRoutePattern(
            String url, OneshotEntity column, String templatePath, boolean isDetailPage) {
        return createRoutePattern(url, column, templatePath, isDetailPage, null);
    }

    /** 创建路由正则模式，可附带说明文字（用于系统自动生成的路由） */
    private RoutePattern createRoutePattern(
            String url,
            OneshotEntity column,
            String templatePath,
            boolean isDetailPage,
            String description) {
        List<String> paramNames = new ArrayList<>();
        // 路由匹配用的路径已去前导 "/"（normalizedPath），故正则也按同一形式构建。
        String patternStr = normalizeUrl(url);

        // ${className} 解析为栏目名称字面量（非数字参数，参与静态精确匹配）
        final String classNameMark = "___CLASSNAME___";
        boolean hasClassName = patternStr.contains(CLASSNAME_PLACEHOLDER);
        if (hasClassName) {
            patternStr = patternStr.replace(CLASSNAME_PLACEHOLDER, classNameMark);
        }

        // 参数名必须与 URL 中捕获组的出现顺序一致，不能依赖 Set 的遍历顺序。
        Matcher placeholderMatcher = Pattern.compile("\\{[^{}]+\\}").matcher(patternStr);
        StringBuilder parameterizedPattern = new StringBuilder();
        while (placeholderMatcher.find()) {
            String placeholder = placeholderMatcher.group();
            if (URL_PLACEHOLDERS.contains(placeholder)) {
                String paramName = placeholder.substring(1, placeholder.length() - 1);
                paramNames.add(paramName);
                placeholderMatcher.appendReplacement(
                        parameterizedPattern, "___PARAM_" + paramName + "___");
            }
        }
        placeholderMatcher.appendTail(parameterizedPattern);
        patternStr = parameterizedPattern.toString();

        // 转义所有正则特殊字符
        patternStr = escapeRegex(patternStr);

        // 恢复捕获组
        for (String paramName : paramNames) {
            Integer routeId =
                    switch (paramName) {
                        case "classId" -> column.getId();
                        case "parentId" -> column.getParentId();
                        default -> null;
                    };
            String capture = routeId == null ? "(\\d+)" : "(" + routeId + ")";
            patternStr = patternStr.replace("___PARAM_" + paramName + "___", capture);
        }

        // 恢复栏目名称字面量（按字面量匹配，转义为 \Q...\E）
        if (hasClassName) {
            patternStr = patternStr.replace(classNameMark, Pattern.quote(classNameOf(column)));
        }

        // 创建完整匹配模式
        patternStr = "^" + patternStr + "$";

        try {
            Pattern compiledPattern = Pattern.compile(patternStr);
            return new RoutePattern(
                    compiledPattern,
                    url,
                    column,
                    paramNames,
                    templatePath,
                    isDetailPage,
                    description);
        } catch (Exception e) {
            log.warn("创建路由模式失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /** 转义正则特殊字符 */
    private String escapeRegex(String str) {
        return str.replaceAll("([.^$+?{}\\[\\]\\\\|])", "\\\\$1");
    }

    /** 栏目名称字面量，空值安全。 */
    private String classNameOf(OneshotEntity column) {
        return column != null && column.getClassName() != null ? column.getClassName() : "";
    }

    /** 将 URL 中的 ${className} 解析为栏目名称（用于无占位符的静态路由键）。 */
    private String resolveClassNamePlaceholder(String url, OneshotEntity column) {
        if (url == null || !url.contains(CLASSNAME_PLACEHOLDER)) return url;
        return url.replace(CLASSNAME_PLACEHOLDER, classNameOf(column));
    }

    private String normalizeUrl(String url) {
        if (url.startsWith("/")) {
            url = url.substring(1);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 处理独立进程内部控制请求。
     *
     * <p>仅当通过控制令牌构造（独立进程模式）时开放；统一进程模式 controlToken 为 null，直接返回 false。
     */
    private boolean tryHandleInternal(HttpServerExchange exchange, String normalizedPath)
            throws Exception {
        if (controlToken == null || controlToken.isBlank()) {
            return false;
        }
        if (!INTERNAL_ROUTES.equals(normalizedPath)
                && !INTERNAL_RELOAD_ROUTES.equals(normalizedPath)) {
            return false;
        }

        String token = exchange.getRequestHeaders().getFirst(INTERNAL_TOKEN_HEADER);
        if (token == null) {
            var queryParam = exchange.getQueryParameters().get("token");
            if (queryParam != null && !queryParam.isEmpty()) {
                token = queryParam.getFirst();
            }
        }
        if (!controlToken.equals(token)) {
            sendPlain(exchange, 403, "forbidden");
            return true;
        }

        if (INTERNAL_ROUTES.equals(normalizedPath)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("routes", getRoutes());
            body.put("conflicts", getRouteConflicts());
            sendJson(exchange, MAPPER.writeValueAsString(body));
            return true;
        }
        if (INTERNAL_RELOAD_ROUTES.equals(normalizedPath)) {
            reloadRoutes();
            sendJson(exchange, "{\"success\":true}");
            return true;
        }

        sendPlain(exchange, 404, "not found");
        return true;
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        exchange.getResponseSender().send(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void sendPlain(HttpServerExchange exchange, int status, String text) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain;charset=UTF-8");
        exchange.getResponseSender().send(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 处理 HTTP 请求 */
    private void handleRequest(HttpServerExchange exchange) throws Exception {
        // 门禁读取数据库，必须与文件/模板处理一起在工作线程执行。
        if (exchange.isInIoThread()) {
            exchange.dispatch(this::handleRequest);
            return;
        }
        long startTime = System.currentTimeMillis();
        String path = exchange.getRequestPath();
        String method =
                exchange.getRequestMethod() == null ? "" : exchange.getRequestMethod().toString();
        String query = exchange.getQueryString();
        String clientIp = getClientIp(exchange);

        String category = null;
        Integer columnId = null;
        Throwable error = null;

        try {
            // 标准化路径
            String normalizedPath = normalizeUrl(path);

            // 独立进程内部控制接口（路由查询/重载），需携带控制令牌
            if (tryHandleInternal(exchange, normalizedPath)) {
                category = "internal";
                return;
            }

            if (previewAccessGuard.isLoginSubmission(method, exchange.getRequestURI())) {
                exchange.startBlocking();
                byte[] body =
                        exchange.getInputStream().readNBytes(PreviewAccessGuard.MAX_FORM_BYTES + 1);
                if (handlePreviewAccess(exchange, body)) {
                    category = "preview_access";
                    return;
                }
            }

            if (normalizedPath.equals("__cms/search")
                    || normalizedPath.equals("__cms/search.json")) {
                if (publicSearchRoutes == null || routeContext == null) {
                    sendError(exchange, 503, "搜索尚未准备好");
                } else {
                    publicSearchRoutes.handle(normalizedPath, exchange, routeContext);
                }
                category = "public_search";
                return;
            }

            if (normalizedPath.startsWith("assets/global/")
                    || normalizedPath.startsWith("assets/upload/")) {
                boolean global = normalizedPath.startsWith("assets/global/");
                Path root =
                        global
                                ? storageService.publicAssetsRoot()
                                : storageService.publicSiteAssetsRoot(projectCode);
                Path targetFile =
                        root.resolve(normalizedPath.substring("assets/global/".length()))
                                .normalize();
                if (!targetFile.startsWith(root) || !UploadDelivery.within(root, targetFile)) {
                    sendError(exchange, 404, "资源不存在");
                    category = CATEGORY_NOT_FOUND;
                    return;
                }
                serveStaticAsset(exchange, targetFile);
                category = CATEGORY_ASSETS_STATIC;
                return;
            }

            // 0. 自定义路由优先（在 UI Package 检查和默认路由之前执行）
            if (routeContext != null) {
                MatchOutcome customResult = tryServeCustomRoute(exchange, normalizedPath);
                if (customResult.served) {
                    category = customResult.category;
                    columnId = customResult.columnId;
                    return;
                }
            }

            // 获取站点根目录
            Optional<Path> activePath = storageService.getActivePath(projectCode);
            if (activePath.isEmpty()) {
                sendError(exchange, 503, "UI Package 未激活");
                category = CATEGORY_UI_PACKAGE_INACTIVE;
                return;
            }

            Path siteRoot = activePath.get();
            Path templateDir = templateStorageService.resolveTemplateDir(projectCode);

            // 对于根路径 "/"，优先尝试栏目列表页（根栏目路由）
            if (normalizedPath.isEmpty()) {
                MatchOutcome outcome = tryServeListPage(exchange, templateDir, normalizedPath);
                if (outcome.served) {
                    category = outcome.category;
                    columnId = outcome.columnId;
                    return;
                }
                // 根栏目未配置，回退到 index 静态文件
                normalizedPath = "index";
            }

            // 2. 尝试作为栏目列表页处理
            MatchOutcome listOutcome = tryServeListPage(exchange, templateDir, normalizedPath);
            if (listOutcome.served) {
                category = listOutcome.category;
                columnId = listOutcome.columnId;
                return;
            }

            // 3. 尝试作为内容详情页处理
            MatchOutcome detailOutcome = tryServeDetailPage(exchange, templateDir, normalizedPath);
            if (detailOutcome.served) {
                category = detailOutcome.category;
                columnId = detailOutcome.columnId;
                return;
            }

            // 1. 尝试作为静态文件处理
            MatchOutcome staticOutcome = tryServeStaticFile(exchange, siteRoot, normalizedPath);
            if (staticOutcome.served) {
                category = staticOutcome.category;
                return;
            }

            // 404
            sendError(exchange, 404, "页面不存在: " + path);
            category = CATEGORY_NOT_FOUND;
        } catch (Exception e) {
            log.error("处理请求失败: siteId={}, path={}", siteId, path, e);
            try {
                sendError(exchange, 500, "服务器内部错误: " + e.getMessage());
            } catch (Exception ignored) {
                // 忽略二次异常，保证日志能写入
            }
            category = CATEGORY_ERROR;
            error = e;
        } finally {
            try {
                int status = exchange.getStatusCode();
                if (status < 0) {
                    status = 200;
                }
                long durationMs = System.currentTimeMillis() - startTime;
                LocalDateTime now = LocalDateTime.now();
                Long colId = columnId == null ? null : columnId.longValue();
                String finalCategory = category == null ? "unknown" : category;
                if (error != null) {
                    requestLogger.logError(
                            now,
                            finalCategory,
                            colId,
                            method,
                            path,
                            query,
                            status,
                            durationMs,
                            clientIp,
                            error);
                } else {
                    requestLogger.logAccess(
                            now,
                            finalCategory,
                            colId,
                            method,
                            path,
                            query,
                            status,
                            durationMs,
                            clientIp);
                }
            } catch (Throwable t) {
                log.warn("站点请求日志写入失败: siteId={}, path={}", siteId, path, t);
            }
        }
    }

    /** HTML 页面和密码提交共用的响应适配；资源请求不调用此方法。 */
    private boolean handlePreviewAccess(HttpServerExchange exchange, byte[] body) {
        var policy = previewAccessGuard.forSiteId(siteId);
        if (policy.enabled()) {
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
            exchange.addResponseCommitListener(
                    response ->
                            response.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store"));
            String query = exchange.getQueryString();
            String method = exchange.getRequestMethod().toString();
            String target = exchange.getRequestURI() + (query.isEmpty() ? "" : "?" + query);
            var cookies = exchange.getRequestHeaders().get(Headers.COOKIE);
            var response =
                    previewAccessGuard.check(
                            policy,
                            PreviewAccessGuard.LOGIN_PATH,
                            method,
                            target,
                            cookies == null ? null : String.join("; ", cookies),
                            port,
                            exchange.isSecure(),
                            exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE),
                            body);
            if (response != null) {
                response.headers()
                        .forEach(
                                (name, value) ->
                                        exchange.getResponseHeaders()
                                                .put(new HttpString(name), value));
                exchange.setStatusCode(response.status());
                if ("HEAD".equals(method) || response.body().isEmpty()) {
                    exchange.endExchange();
                } else {
                    exchange.getResponseSender().send(response.body(), StandardCharsets.UTF_8);
                }
                return true;
            }
        }

        return false;
    }

    private String getClientIp(HttpServerExchange exchange) {
        try {
            if (exchange.getSourceAddress() == null
                    || exchange.getSourceAddress().getAddress() == null) {
                return "";
            }
            return RequestOriginUtils.clientIp(
                    exchange.getSourceAddress().getAddress().getHostAddress(),
                    exchange.getRequestHeaders().get("X-Real-IP"),
                    exchange.getRequestHeaders().get("X-Forwarded-For"));
        } catch (Exception e) {
            return "";
        }
    }

    /** 发送静态资源文件（用于统一公开资源库） */
    private void serveStaticAsset(HttpServerExchange exchange, Path file) throws IOException {
        var delivery =
                UploadDelivery.prepare(file, exchange.getRequestHeaders().getFirst(Headers.RANGE));
        delivery.headers()
                .forEach(
                        (name, value) ->
                                exchange.getResponseHeaders().put(new HttpString(name), value));
        exchange.setStatusCode(delivery.status());
        exchange.startBlocking();
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod().toString()))
            delivery.write(exchange.getOutputStream());
        exchange.endExchange();
    }

    /** 尝试作为静态文件服务 */
    private MatchOutcome tryServeStaticFile(HttpServerExchange exchange, Path siteRoot, String path)
            throws IOException {
        var delivery = storageService.deliveryAsset(projectCode, siteRoot, path);
        Path file = delivery.orElseGet(() -> siteRoot.resolve(path).normalize());
        if (delivery.isEmpty() && !file.startsWith(siteRoot)) return MatchOutcome.miss();

        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            // 尝试添加 index.html
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                file = file.resolve("index.html");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return MatchOutcome.miss();
                }
            } else {
                return MatchOutcome.miss();
            }
        }

        // 安全检查：确保文件在站点目录内
        if (delivery.isEmpty()) {
            Path activeRoot = siteRoot.toRealPath();
            Path original = file.toRealPath();
            if (!original.startsWith(activeRoot)) return MatchOutcome.miss();
            String relative = activeRoot.relativize(original).toString().replace('\\', '/');
            if (hiddenOriginalFallback(backOfficeOnly, activeRoot.getFileName().toString(), relative))
                return MatchOutcome.miss();
        }

        // 发送文件
        String contentType = getContentType(file.toString());
        if (contentType.startsWith("text/html") && handlePreviewAccess(exchange, null)) {
            return MatchOutcome.served("preview_access", null);
            // return MatchOutcome.miss();
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");

        byte[] content = Files.readAllBytes(file);
        if (contentType.startsWith("text/html") && externalIntegrationOutput != null) {
            String html = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            String decorated =
                    externalIntegrationOutput.decorate(siteId, html, exchange.getStatusCode());
            if (!html.equals(decorated)) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
                exchange.getResponseSender().send(decorated);
            } else exchange.getResponseSender().send(ByteBuffer.wrap(content));
        } else exchange.getResponseSender().send(ByteBuffer.wrap(content));

        return MatchOutcome.served(CATEGORY_UI_STATIC, null);
    }

    static boolean hiddenOriginalFallback(JsonNode siteRules, String version, String relative) {
        for (JsonNode decision : siteRules.path(version)) {
            if (decision.path("reason").asString().isBlank()) continue;
            for (JsonNode path : decision.path("visitorFallbackPaths"))
                if (path.isString() && relative.equalsIgnoreCase(path.asString())) return true;
        }
        return false;
    }

    /** 尝试作为栏目列表页服务 */
    private MatchOutcome tryServeListPage(
            HttpServerExchange exchange, Path templateDir, String path) {
        // 1. 先尝试精确匹配（静态路由，无占位符）
        RoutePattern matchedPattern = listUrlRouteMap.get(path);

        // 2. 尝试匹配根栏目
        if (matchedPattern == null && path.isEmpty()) {
            matchedPattern = listUrlRouteMap.get("");
        }

        // 3. 尝试动态路由匹配（有占位符的路由）
        UrlMatchResult dynamicMatch = null;
        for (RoutePattern pattern : listRoutePatterns) {
            Matcher matcher = pattern.pattern.matcher(path);
            if (matcher.matches()) {
                Map<String, String> params = extractParams(matcher, pattern.paramNames);
                dynamicMatch = new UrlMatchResult(pattern, params);
                break;
            }
        }

        // 4. 当 URL 中显式包含某动态路由栏目自身的 id 时，优先该动态路由：
        //    避免其它栏目写死的同名字面 URL（如一级栏目把 list_page_url 误设为某个二级栏目的具体 URL）抢占。
        final UrlMatchResult resolvedDynamic = dynamicMatch;
        boolean preferDynamicSelf =
                resolvedDynamic != null
                        && resolvedDynamic.params.values().stream()
                                .anyMatch(
                                        value ->
                                                value != null
                                                        && value.equals(
                                                                String.valueOf(
                                                                        resolvedDynamic.routePattern
                                                                                .column.getId())));
        UrlMatchResult matchResult = null;
        if (preferDynamicSelf) {
            matchedPattern = null;
            matchResult = dynamicMatch;
        } else if (matchedPattern == null) {
            matchResult = dynamicMatch;
        }

        if (matchedPattern == null && matchResult == null) {
            return MatchOutcome.miss();
        }

        if (handlePreviewAccess(exchange, null)) {
            return MatchOutcome.served("preview_access", null);
        }

        RoutePattern pattern = matchedPattern != null ? matchedPattern : matchResult.routePattern;
        Map<String, String> params = matchResult != null ? matchResult.params : new HashMap<>();
        Integer colId = pattern.column.getId();

        // 获取模板路径
        String templatePath = pattern.templatePath;
        if (Integer.valueOf(0).equals(pattern.column.getPageType())) {
            var current = oneShotMapper.selectActiveMenuModelBySite(siteId, colId);
            boolean managedRoot = Objects.equals(colId, pattern.column.getSiteRootId())
                    && (templatePath != null && templatePath.startsWith("cms-applied/")
                            || publicRootRoutes != null
                                    && publicRootRoutes.nativeProof(siteId, projectCode, pattern.column).isPresent());
            if (current == null || !Boolean.TRUE.equals(current.getHasFrontChannel())
                    || !managedRoot && !Integer.valueOf(3).equals(current.getStatus())) {
                sendError(exchange, 404, "页面不存在");
                return MatchOutcome.served(CATEGORY_NOT_FOUND, colId);
            }
        }
        if (templatePath == null || templatePath.isEmpty()) {
            log.warn("栏目列表模板路径未配置: columnId={}", colId);
            requestLogger.logError(
                    LocalDateTime.now(),
                    CATEGORY_COLUMN_LIST,
                    colId == null ? null : colId.longValue(),
                    exchange.getRequestMethod().toString(),
                    path,
                    exchange.getQueryString(),
                    500,
                    0,
                    getClientIp(exchange),
                    new IllegalStateException("栏目列表模板路径未配置: columnId=" + colId));
            sendHtml(
                    exchange,
                    buildTemplateNotFoundError(
                            "列表（主页）页", colId, pattern.column.getClassName(), "未配置"));
            return MatchOutcome.served(CATEGORY_COLUMN_LIST, colId);
        }

        Path templateFile = templateDir.resolve(templatePath);
        if (!Files.isRegularFile(templateFile, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("栏目列表模板不存在: columnId={}, template={}", colId, templatePath);
            requestLogger.logError(
                    LocalDateTime.now(),
                    CATEGORY_COLUMN_LIST,
                    colId == null ? null : colId.longValue(),
                    exchange.getRequestMethod().toString(),
                    path,
                    exchange.getQueryString(),
                    500,
                    0,
                    getClientIp(exchange),
                    new IllegalStateException(
                            "栏目列表模板不存在: columnId=" + colId + ", template=" + templatePath));
            sendHtml(
                    exchange,
                    buildTemplateNotFoundError(
                            "列表（主页）页", colId, pattern.column.getClassName(), templatePath));
            return MatchOutcome.served(CATEGORY_COLUMN_LIST, colId);
        }

        try {
            // 创建 IdEntity
            IdEntity idEntity;
            try {
                idEntity = createListIdEntity(pattern.column, params, exchange);
            } catch (IllegalArgumentException invalid) {
                exchange.setStatusCode(400);
                sendHtml(exchange, "列表分页或筛选参数无效");
                return MatchOutcome.served(CATEGORY_COLUMN_LIST, colId);
            }

            // 渲染列表页模板
            String prefill = com.xinhongru.cms.template.FormPrefillTemplateCompiler.requestValue(
                    templatePath, colId, exchange.getQueryParameters());
            String renderedHtml = prefill == null
                    ? previewService.renderTemplatePreviewWithIdEntity(siteId, templatePath, idEntity)
                    : previewService.renderTemplatePreviewWithIdEntity(siteId, templatePath, idEntity, prefill);

            markStaticRender(exchange, colId, false, templatePath);
            sendHtml(exchange, applyReviewedNavigationText(exchange, omitMissingIcons(renderedHtml)));
            return MatchOutcome.served(CATEGORY_COLUMN_LIST, colId);
        } catch (ResponseStatusException e) {
            int status = e.getStatusCode().value();
            exchange.setStatusCode(status);
            sendHtml(exchange, status == 404 ? "页面不存在" : "请求无效或页面不可用");
            return MatchOutcome.served(status == 404 ? CATEGORY_NOT_FOUND : CATEGORY_ERROR, colId);
        } catch (Exception e) {
            log.error("渲染栏目列表页失败: columnId={}", colId, e);
            requestLogger.logError(
                    LocalDateTime.now(),
                    CATEGORY_COLUMN_LIST,
                    colId == null ? null : colId.longValue(),
                    exchange.getRequestMethod().toString(),
                    path,
                    exchange.getQueryString(),
                    500,
                    0,
                    getClientIp(exchange),
                    e);
            sendError(exchange, 500, "站点页面暂时无法显示，请稍后重试");
            return MatchOutcome.served(CATEGORY_ERROR, colId);
        }
    }

    /** Shared detail URLs select the owning column, not the first configured column. */
    private UrlMatchResult matchDetailRoute(String path) {
        List<UrlMatchResult> matches = new ArrayList<>();
        for (RoutePattern pattern : detailRoutePatterns) {
            Matcher matcher = pattern.pattern.matcher(path);
            if (matcher.matches()) {
                matches.add(
                        new UrlMatchResult(pattern, extractParams(matcher, pattern.paramNames)));
            }
        }
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.getFirst();
        }
        Map<Integer, Integer> owners = new HashMap<>();
        for (UrlMatchResult candidate : matches) {
            String value = candidate.params.get("id");
            if (value == null) {
                continue;
            }
            int contentId;
            try {
                contentId = Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                // Oversized URL IDs cannot refer to an integer-keyed article.
                continue;
            }
            if (contentId <= 0) {
                continue;
            }
            if (!owners.containsKey(contentId)) {
                owners.put(
                        contentId, contentMapper.selectPublishedArticleClassId(siteId, contentId));
            }
            if (Objects.equals(owners.get(contentId), candidate.routePattern.column.getId())) {
                return candidate;
            }
        }
        // Preserve existing routing for shared patterns without a content ID.
        return matches.stream().allMatch(candidate -> !candidate.params.containsKey("id"))
                ? matches.getFirst()
                : null;
    }

    /** 尝试作为内容详情页服务 */
    private MatchOutcome tryServeDetailPage(
            HttpServerExchange exchange, Path templateDir, String path) {
        // 1. 先尝试精确匹配（静态路由，无占位符）
        RoutePattern matchedPattern = detailUrlRouteMap.get(path);

        // 2. 尝试动态路由匹配（有占位符的路由）
        UrlMatchResult matchResult = null;
        if (matchedPattern == null) {
            matchResult = matchDetailRoute(path);
        }

        if (matchedPattern == null && matchResult == null) {
            return MatchOutcome.miss();
        }

        if (handlePreviewAccess(exchange, null)) {
            return MatchOutcome.served("preview_access", null);
        }

        RoutePattern pattern = matchedPattern != null ? matchedPattern : matchResult.routePattern;
        Map<String, String> params = matchResult != null ? matchResult.params : new HashMap<>();
        Integer colId = pattern.column.getId();

        if (params.containsKey("id")) {
            int contentId;
            try {
                contentId = Integer.parseInt(params.get("id"));
            } catch (NumberFormatException invalid) {
                contentId = 0;
            }
            if (contentId <= 0
                    || !Objects.equals(colId, contentMapper.selectPublishedArticleClassId(siteId, contentId))) {
                sendError(exchange, 404, "内容不存在");
                return MatchOutcome.served(CATEGORY_NOT_FOUND, colId);
            }
        }

        // 获取详情页模板
        String templatePath = pattern.templatePath;
        if (templatePath == null || templatePath.isEmpty()) {
            log.warn("栏目详情模板路径未配置: columnId={}", colId);
            sendHtml(
                    exchange,
                    buildTemplateNotFoundError("详情页", colId, pattern.column.getClassName(), "未配置"));
            return MatchOutcome.served(CATEGORY_COLUMN_DETAIL, colId);
        }

        Path templateFile = templateDir.resolve(templatePath);
        if (!Files.isRegularFile(templateFile, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("栏目详情模板不存在: columnId={}, template={}", colId, templatePath);
            sendHtml(
                    exchange,
                    buildTemplateNotFoundError(
                            "详情页", colId, pattern.column.getClassName(), templatePath));
            return MatchOutcome.served(CATEGORY_COLUMN_DETAIL, colId);
        }

        try {
            // 创建 IdEntity
            IdEntity idEntity = createIdEntity(pattern.column, params);
            // 渲染详情页模板
            String renderedHtml =
                    previewService.renderTemplatePreviewWithIdEntity(
                            siteId, templatePath, idEntity);
            markStaticRender(exchange, colId, true, templatePath);
            sendHtml(exchange, applyReviewedNavigationText(exchange, omitMissingIcons(renderedHtml)));
            return MatchOutcome.served(CATEGORY_COLUMN_DETAIL, colId);
        } catch (ResponseStatusException e) {
            exchange.setStatusCode(e.getStatusCode().value());
            sendHtml(exchange, "内容不存在");
            return MatchOutcome.served(CATEGORY_NOT_FOUND, colId);
        } catch (Exception e) {
            log.error("渲染内容详情页失败: columnId={}", colId, e);
            sendError(exchange, 500, "站点页面暂时无法显示，请稍后重试");
            return MatchOutcome.served(CATEGORY_ERROR, colId);
        }
    }

    /** 从匹配器中提取参数 */
    private Map<String, String> extractParams(Matcher matcher, List<String> paramNames) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            String paramName = paramNames.get(i);
            String paramValue = matcher.group(i + 1); // 捕获组从 1 开始
            if (paramValue != null) {
                params.put(paramName, paramValue);
            }
        }
        return params;
    }

    /** 仅列表的显式筛选通路读取有界值；其他路由不绑定任意请求参数。 */
    private IdEntity createListIdEntity(
            OneshotEntity column, Map<String, String> pathParams, HttpServerExchange exchange) {
        var query = exchange.getQueryParameters();
        var params = new HashMap<>(pathParams);
        String requestedPage = singleListQuery(query, "pageIndex");
        if (requestedPage != null) {
            int value = positiveListPage(requestedPage);
            if (params.containsKey("pageIndex")
                    && positiveListPage(params.get("pageIndex")) != value)
                throw new IllegalArgumentException("路径与查询页码冲突");
            params.putIfAbsent("pageIndex", Integer.toString(value));
        }
        IdEntity id = createIdEntity(column, params);
        String enabled = singleListQuery(query, "__cms_filter");
        if (enabled == null) return id;
        if (!"1".equals(enabled)) throw new IllegalArgumentException("列表筛选标记无效");
        if (query.size() > 64) throw new IllegalArgumentException("列表筛选参数过多");
        Map<String, String> values = new LinkedHashMap<>();
        int bytes = 0;
        for (var entry : query.entrySet()) {
            String key = entry.getKey(), value = singleListQuery(query, key);
            if (key == null
                    || key.isBlank()
                    || key.length() > 128
                    || value == null
                    || value.length() > 1000) throw new IllegalArgumentException("列表筛选参数无效或超限");
            bytes +=
                    key.getBytes(StandardCharsets.UTF_8).length
                            + value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 16 * 1024) throw new IllegalArgumentException("列表筛选参数总量超过16KB");
            values.put(key, value);
        }
        values.remove("__cms_filter");
        values.remove("pageIndex");
        var condition = new com.xinhongru.web.resonseVo.ConditionVo();
        condition.setKey(values.remove("key"));
        condition.setKeywords(values.remove("keywords"));
        condition.setType(values.remove("type"));
        condition.setArea(values.remove("area"));
        condition.setYearString(values.remove("yearString"));
        condition.setClassIds(listConditionInteger(values.remove("classIds")));
        condition.setYear(listConditionInteger(values.remove("year")));
        condition.setMonth(listConditionInteger(values.remove("month")));
        condition.setPageIndex(id.getPageIndex());
        id.setConditionVo(condition);
        id.bindQueryValues(values);
        return id;
    }

    private String singleListQuery(Map<String, java.util.Deque<String>> query, String key) {
        var values = query.get(key);
        if (values == null) return null;
        if (values.size() != 1) throw new IllegalArgumentException("列表筛选不允许重复参数");
        return values.getFirst();
    }

    private int positiveListPage(String value) {
        if (value == null || !value.matches("[0-9]{1,10}"))
            throw new IllegalArgumentException("页码无效");
        int page = Integer.parseInt(value);
        if (page < 1) throw new IllegalArgumentException("页码无效");
        return page;
    }

    private Integer listConditionInteger(String value) {
        return value == null || value.isEmpty() ? null : Integer.valueOf(value);
    }

    /** 创建 IdEntity，从栏目和 URL 参数中填充 */
    private IdEntity createIdEntity(OneshotEntity column, Map<String, String> params) {
        IdEntity idEntity = new IdEntity();
        idEntity.bindServerSite(siteId);

        // 默认值从栏目获取
        idEntity.setClassId(column.getId() != null ? column.getId() : 0);
        idEntity.setParentId(column.getParentId());
        idEntity.setInfoId(0);
        idEntity.setPageIndex(1);
        idEntity.setPageSize(10);

        // 从 URL 参数覆盖
        if (params.containsKey("classId")) {
            idEntity.setClassId(Integer.parseInt(params.get("classId")));
        }
        if (params.containsKey("parentId")) {
            idEntity.setParentId(Integer.parseInt(params.get("parentId")));
        }
        if (params.containsKey("id")) {
            idEntity.setInfoId(Integer.parseInt(params.get("id")));
        }
        if (params.containsKey("productId")) {
            idEntity.setProductId(Integer.parseInt(params.get("productId")));
        }
        if (params.containsKey("pageIndex")) {
            idEntity.setPageIndex(Integer.parseInt(params.get("pageIndex")));
        }
        if (params.containsKey("pageSize")) {
            idEntity.setPageSize(Integer.parseInt(params.get("pageSize")));
        }

        return idEntity;
    }

    /**
     * 初始化站点路由上下文。 在站点启动、loadUrlRoutes 之后调用。 SiteRouteContext 只持有 siteId/projectCode/previewService，
     * 站点实体和 CommonUtils 在渲染时由 previewService 内部解析。
     */
    private void initRouteContext() {
        try {
            this.routeContext =
                    new SiteRouteContext(
                            siteId,
                            projectCode,
                            previewService,
                            exchange -> !handlePreviewAccess(exchange, null),
                            externalIntegrationOutput);
        } catch (Exception e) {
            log.warn("初始化路由上下文失败: siteId={}", siteId, e);
            this.routeContext = null;
        }
    }

    /** 尝试自定义路由处理。 遍历注册的自定义处理器，第一个匹配的处理器处理该请求。 */
    private MatchOutcome tryServeCustomRoute(HttpServerExchange exchange, String path) {
        List<RouteHandlerEntry> handlers = routeRegistry.getHandlers(projectCode);
        if (handlers.isEmpty()) {
            return MatchOutcome.miss();
        }

        for (RouteHandlerEntry entry : handlers) {
            if (!entry.matches(path)) {
                continue;
            }

            log.debug("自定义路由匹配: pattern={}, path={}", entry.getUrlPattern(), path);
            try {
                boolean handled = entry.getHandler().handle(path, exchange, routeContext);
                if (handled) {
                    return MatchOutcome.served(CATEGORY_CUSTOM, null);
                }
                // handled=false → 处理器主动跳过，继续尝试下一个
            } catch (Exception e) {
                log.error("自定义路由处理失败: pattern={}, path={}", entry.getUrlPattern(), path, e);
                sendError(exchange, 500, "服务器内部错误: " + e.getMessage());
                return MatchOutcome.served(CATEGORY_ERROR, null);
            }
        }

        return MatchOutcome.miss();
    }

    private void markStaticRender(
            HttpServerExchange exchange, Integer columnId, boolean detail, String templatePath) {
        var mode = exchange.getQueryParameters().get("buildStatus");
        if (mode != null && "true".equals(mode.peekFirst())) {
            exchange.getResponseHeaders().put(
                    new HttpString(StaticRenderProof.HEADER),
                    StaticRenderProof.of(siteId, columnId, detail, templatePath));
        }
    }

    private void sendHtml(HttpServerExchange exchange, String html) {
        if (externalIntegrationOutput != null)
            html = externalIntegrationOutput.decorate(siteId, html, exchange.getStatusCode());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(html);
    }

    private String omitMissingIcons(String html) throws IOException {
        if (!missingIconLinks.configured()) return html;
        var active = storageService.getActivePath(projectCode);
        if (active.isEmpty()) return html;
        String access = getAccessUrl();
        return missingIconLinks.apply(html, active.get(), List.of(
                access.substring(0, access.length() - 1),
                "http://localhost:" + port, "http://127.0.0.1:" + port));
    }

    private String applyReviewedNavigationText(HttpServerExchange exchange, String html) throws IOException {
        if (!retainedNavigationText.configured() && !reviewedHeaderSearch.configured()) return html;
        var active = storageService.getActivePath(projectCode);
        if (active.isEmpty()) return html;
        String route = exchange.getRequestPath();
        String linked = retainedNavigationText.apply(html, active.get(), route);
        return reviewedHeaderSearch.apply(linked, active.get(), route);
    }

    private void sendError(HttpServerExchange exchange, int code, String message) {
        exchange.setStatusCode(code);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");

        String errorHtml =
                "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>错误</title></head><body"
                        + " style=\"margin:0;padding:20px;background:#f5f5f5;\"><div"
                        + " style=\"padding:20px;background:#fff3f3;border:1px solid"
                        + " #ffcdd2;border-radius:8px;\"><h3 style=\"color:#c62828;margin:0 0"
                        + " 10px;\">错误 "
                        + code
                        + "</h3>"
                        + "<p style=\"color:#666;margin:0;\">"
                        + escapeHtml(message)
                        + "</p>"
                        + "</div></body></html>";

        exchange.getResponseSender().send(errorHtml);
    }

    /** 构建模板不存在的错误页面 */
    private String buildTemplateNotFoundError(
            String templateType, Integer columnId, String columnName, String templatePath) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>模板不存在</title></head><body"
                + " style=\"margin:0;padding:20px;background:#f5f5f5;\"><div"
                + " style=\"padding:20px;background:#fff3f3;border:1px solid"
                + " #ffcdd2;border-radius:8px;\"><h3 style=\"color:#c62828;margin:0 0"
                + " 10px;\">模板不存在</h3><p style=\"color:#666;margin:0 0"
                + " 8px;\"><strong>模板类型：</strong>"
                + escapeHtml(templateType)
                + "</p>"
                + "<p style=\"color:#666;margin:0 0 8px;\"><strong>栏目ID：</strong>"
                + columnId
                + "</p>"
                + "<p style=\"color:#666;margin:0 0 8px;\"><strong>栏目：</strong>"
                + escapeHtml(columnName)
                + "</p>"
                + "<p style=\"color:#666;margin:0;\"><strong>模板路径：</strong>"
                + escapeHtml(templatePath)
                + "</p>"
                + "</div></body></html>";
    }

    private String getContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".aspx")) {
            return "text/html; charset=UTF-8";
        } else if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=UTF-8";
        } else if (lower.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".avif")) {
            return "image/avif";
        } else if (lower.endsWith(".bmp")) {
            return "image/bmp";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lower.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lower.endsWith(".woff")) {
            return "font/woff";
        } else if (lower.endsWith(".woff2")) {
            return "font/woff2";
        } else if (lower.endsWith(".ttf")) {
            return "font/ttf";
        } else if (lower.endsWith(".otf")) {
            return "font/otf";
        } else if (lower.endsWith(".eot")) {
            return "application/vnd.ms-fontobject";
        } else if (lower.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        } else {
            String mediaType = UploadDelivery.contentType(filename);
            return mediaType.startsWith("video/") ? mediaType : "application/octet-stream";
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

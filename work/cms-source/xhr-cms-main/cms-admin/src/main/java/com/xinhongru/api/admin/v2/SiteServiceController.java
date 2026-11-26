package com.xinhongru.api.admin.v2;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.siteservice.SiteHttpServer;
import com.xinhongru.cms.siteservice.SiteHttpServerManager;
import com.xinhongru.cms.siteservice.SiteServiceModeConfig;
import com.xinhongru.cms.siteservice.SiteServiceSettingsService;
import com.xinhongru.cms.siteservice.SiteServiceStatus;
import com.xinhongru.cms.siteservice.StandaloneSiteProcessManager;
import com.xinhongru.cms.template.TemplatePreviewService;
import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageHttpServer;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.AdminPermissionSnapshot;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.IPKit;
import com.xinhongru.web.service.InfoService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 站点服务控制器 - 提供站点访问和模板渲染服务
 *
 * <p>用于访问站点并渲染 template/web 目录下的模板 根据站点根栏目导航找到对应的模板进行渲染
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/v2/site-service")
@RequiredArgsConstructor
public class SiteServiceController {
    private final SiteProjectMapper projectMapper;
    private final UiPackageStorageService uiPackageStorageService;
    private final UiPackageHttpServer uiPackageHttpServer;
    private final TemplatePreviewService previewService;
    private final TemplateStorageService templateStorageService;
    private final OneShotMapper oneShotMapper;
    private final InfoService infoService;
    private final SiteHttpServerManager siteHttpServerManager;
    private final StandaloneSiteProcessManager standaloneSiteProcessManager;
    private final SiteServiceModeConfig siteServiceModeConfig;
    private final SiteServiceSettingsService settingsService;
    private final SiteContextService siteContextService;

    private static final String DEFAULT_INDEX_TEMPLATE = "index.html";
    private static final int PORT_POOL_MIN = 8080;
    private static final int PORT_POOL_MAX = 9000;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 获取站点列表（按当前用户可见站点过滤） */
    @GetMapping
    public AdminApiResponse<List<SiteServiceView>> listSites(HttpServletRequest request) {
        AdminPermissionSnapshot snapshot = permissionService.snapshot(request);
        List<SiteProjectEntity> sites;
        if (snapshot.canAccessAllSites()) {
            sites = projectMapper.selectList(null);
        } else {
            Set<Long> accessible = snapshot.accessibleSiteIds();
            if (accessible == null || accessible.isEmpty()) {
                return AdminApiResponse.success(List.of());
            }
            sites =
                    projectMapper.selectList(
                            Wrappers.<SiteProjectEntity>lambdaQuery()
                                    .in(SiteProjectEntity::getId, accessible));
        }
        List<SiteServiceView> views = new ArrayList<>();
        boolean independent = siteServiceModeConfig.isIndependent();
        for (SiteProjectEntity site : sites) {
            // deleted 字段由 MyBatis-Plus @TableLogic 自动处理
            views.add(
                    SiteServiceView.from(
                            site,
                            uiPackageStorageService,
                            uiPackageHttpServer,
                            siteHttpServerManager,
                            standaloneSiteProcessManager,
                            independent,
                            templateStorageService));
        }
        return AdminApiResponse.success(views);
    }

    // ==================== 站点服务类型与启动参数 ====================

    /** 获取站点服务类型（统一进程/独立进程）与启动参数配置 */
    @GetMapping("/mode")
    public AdminApiResponse<SiteServiceModeView> getSiteServiceMode() {
        return AdminApiResponse.success(buildModeView());
    }

    /**
     * 设置站点服务模式。
     *
     * <p>切换时会先关闭当前运行中的站点服务，再按新模式重新启动（短暂中断）。
     */
    @PutMapping("/mode")
    public AdminApiResponse<SiteServiceModeView> updateSiteServiceMode(@RequestParam String mode) {
        String target =
                SiteServiceModeConfig.MODE_INDEPENDENT.equalsIgnoreCase(mode)
                        ? SiteServiceModeConfig.MODE_INDEPENDENT
                        : SiteServiceModeConfig.MODE_UNIFIED;
        String current = siteServiceModeConfig.getMode();
        if (current.equals(target)) {
            return AdminApiResponse.success(buildModeView());
        }

        // 记录切换前处于运行中的站点，切换后按新模式重启
        List<Long> runningSiteIds =
                projectMapper.selectList(null).stream()
                        .filter(
                                site ->
                                        SiteServiceStatus.STATUS_RUNNING.equals(
                                                site.getSiteServiceStatus()))
                        .map(SiteProjectEntity::getId)
                        .toList();

        stopSites(current, runningSiteIds);
        siteServiceModeConfig.setMode(target);

        boolean nowIndependent = SiteServiceModeConfig.MODE_INDEPENDENT.equals(target);
        int restarted = 0;
        int failed = 0;
        for (Long siteId : runningSiteIds) {
            try {
                SiteServiceStatus status =
                        nowIndependent
                                ? standaloneSiteProcessManager.start(requireSite(siteId))
                                : siteHttpServerManager.startSite(siteId);
                if (SiteServiceStatus.STATUS_RUNNING.equals(status.status())) {
                    restarted++;
                } else {
                    failed++;
                    log.warn(
                            "切换服务模式后重启站点服务失败: siteId={}, reason={}", siteId, status.errorMessage());
                }
            } catch (Exception e) {
                failed++;
                log.error("切换服务模式后重启站点服务异常: siteId={}", siteId, e);
            }
        }

        return AdminApiResponse.success(buildModeView(restarted, failed));
    }

    /** 保存全局启动参数（JVM 参数与程序参数） */
    @PutMapping("/launch-params")
    public AdminApiResponse<SiteServiceModeView> saveGlobalLaunchParams(
            @RequestBody Map<String, String> params) {
        siteServiceModeConfig.saveGlobalParams(
                new SiteServiceModeConfig.GlobalParams(
                        params.get("javaPath"),
                        params.get("jarPath"),
                        params.get("workDir"),
                        params.get("jvmArgs"),
                        params.get("programArgs")));
        return AdminApiResponse.success(buildModeView());
    }

    /** 获取站点启动参数（站点覆盖 + 全局 + 默认值），供独立进程参数弹窗回显 */
    @GetMapping("/{siteId}/site-service/launch-params")
    public AdminApiResponse<SiteLaunchParamsView> getSiteLaunchParams(@PathVariable Long siteId) {
        requireSite(siteId);
        return AdminApiResponse.success(buildSiteLaunchParamsView(siteId));
    }

    /** 保存站点级启动参数覆盖（留空字段表示沿用全局默认） */
    @PutMapping("/{siteId}/site-service/launch-params")
    public AdminApiResponse<SiteLaunchParamsView> saveSiteLaunchParams(
            @PathVariable Long siteId, @RequestBody Map<String, String> params) {
        requireSite(siteId);
        siteServiceModeConfig.saveSiteOverride(siteId, params);
        return AdminApiResponse.success(buildSiteLaunchParamsView(siteId));
    }

    private SiteLaunchParamsView buildSiteLaunchParamsView(Long siteId) {
        SiteServiceModeConfig.GlobalParams global = siteServiceModeConfig.getGlobalParams();
        return new SiteLaunchParamsView(
                siteServiceModeConfig.getSiteOverride(siteId),
                new GlobalParamsView(
                        global.javaPath(),
                        global.jarPath(),
                        global.workDir(),
                        global.jvmArgs(),
                        global.programArgs()),
                siteServiceModeConfig.effectiveDefaults());
    }

    private SiteServiceModeView buildModeView() {
        return buildModeView(null, null);
    }

    private SiteServiceModeView buildModeView(Integer restarted, Integer failed) {
        SiteServiceModeConfig.GlobalParams global = siteServiceModeConfig.getGlobalParams();
        return new SiteServiceModeView(
                siteServiceModeConfig.getMode(),
                new GlobalParamsView(
                        global.javaPath(),
                        global.jarPath(),
                        global.workDir(),
                        global.jvmArgs(),
                        global.programArgs()),
                siteServiceModeConfig.effectiveDefaults(),
                restarted,
                failed);
    }

    private void stopSites(String mode, List<Long> siteIds) {
        boolean independent = SiteServiceModeConfig.MODE_INDEPENDENT.equals(mode);
        for (Long siteId : siteIds) {
            try {
                if (independent) {
                    standaloneSiteProcessManager.stop(siteId);
                } else {
                    siteHttpServerManager.stopSite(siteId);
                }
            } catch (Exception e) {
                log.warn("切换服务模式时停止站点服务失败: siteId={}", siteId, e);
            }
        }
    }

    /** 启动站点的 UI 包服务 */
    @PostMapping("/{siteId}/ui-service/start")
    public AdminApiResponse<UiServiceStatus> startUiService(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        String projectCode = site.getProjectCode();

        // 检查 UI 包是否存在
        Optional<Path> activePath = uiPackageStorageService.getActivePath(projectCode);
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(new UiServiceStatus("offline", "UI 包未上传或未激活"));
        }

        // 确保 HTTP 服务器运行
        if (!uiPackageHttpServer.isRunning()) {
            try {
                uiPackageHttpServer.start();
            } catch (Exception e) {
                log.error("启动 UI 包 HTTP 服务器失败", e);
                return AdminApiResponse.success(
                        new UiServiceStatus("error", "启动服务失败: " + e.getMessage()));
            }
        }

        // 激活站点
        uiPackageHttpServer.activateSite(projectCode);
        String accessUrl = uiPackageHttpServer.getAccessUrl(projectCode, site.getSiteServiceIp());

        return AdminApiResponse.success(new UiServiceStatus("running", accessUrl));
    }

    /** 获取站点的 UI 服务访问地址 */
    @GetMapping("/{siteId}/ui-service/access-url")
    public AdminApiResponse<UiServiceAccessUrl> getUiServiceAccessUrl(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        String projectCode = site.getProjectCode();

        // 检查 UI 包是否存在
        Optional<Path> activePath = uiPackageStorageService.getActivePath(projectCode);
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(new UiServiceAccessUrl(null, null, "UI 包未上传"));
        }

        // 获取访问地址
        String accessUrl = uiPackageHttpServer.getAccessUrl(projectCode, site.getSiteServiceIp());
        if (accessUrl == null) {
            return AdminApiResponse.success(new UiServiceAccessUrl(null, null, "服务未启动"));
        }

        // 构建首页访问地址
        String indexUrl = accessUrl + "template/web/index.html";

        return AdminApiResponse.success(new UiServiceAccessUrl(accessUrl, indexUrl, null));
    }

    /** 停止站点的 UI 包服务 */
    @PostMapping("/{siteId}/ui-service/stop")
    public AdminApiResponse<UiServiceStatus> stopUiService(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        String projectCode = site.getProjectCode();

        uiPackageHttpServer.deactivateSite(projectCode);

        return AdminApiResponse.success(new UiServiceStatus("available", null));
    }

    /**
     * 获取 UI 服务当前生效端口
     *
     * <p>服务未运行时返回持久化配置或默认端口，用于前端弹窗回显原端口。
     */
    @GetMapping("/ui-service/port")
    public AdminApiResponse<Integer> getUiServicePort() {
        return AdminApiResponse.success(uiPackageHttpServer.getEffectivePort());
    }

    /** 获取本机所有有效网卡 IP，返回当前默认使用的 IP 作为 defaultIp。 */
    @GetMapping("/local-ips")
    public AdminApiResponse<LocalIpList> listLocalIps() {
        return AdminApiResponse.success(
                new LocalIpList(IPKit.listLocalIps(), IPKit.getPreferredLocalIp()));
    }

    // ==================== 站点服务管理 API ====================

    /**
     * 启动站点服务
     *
     * <p>启动独立的 HTTP 服务，用于生产环境
     */
    @PostMapping("/{siteId}/site-service/start")
    public AdminApiResponse<SiteServiceStatusView> startSiteService(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteServiceStatus status;
        if (siteServiceModeConfig.isIndependent()) {
            SiteProjectEntity site = requireSite(siteId);
            if (uiPackageStorageService.getActivePath(site.getProjectCode()).isEmpty()) {
                return AdminApiResponse.success(
                        new SiteServiceStatusView(
                                "STOPPED", site.getSiteServicePort(), null, "UI 包未上传或未激活"));
            }
            status = standaloneSiteProcessManager.start(site);
        } else {
            status = siteHttpServerManager.startSite(siteId);
        }
        return AdminApiResponse.success(toStatusView(status));
    }

    /** 停止站点服务 */
    @PostMapping("/{siteId}/site-service/stop")
    public AdminApiResponse<SiteServiceStatusView> stopSiteService(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteServiceStatus status =
                siteServiceModeConfig.isIndependent()
                        ? standaloneSiteProcessManager.stop(siteId)
                        : siteHttpServerManager.stopSite(siteId);
        return AdminApiResponse.success(toStatusView(status));
    }

    @GetMapping("/{siteId}/site-service/settings")
    public AdminApiResponse<SiteServiceSettingsService.Settings> getSiteServiceSettings(
            HttpServletRequest request, HttpServletResponse response, @PathVariable Long siteId) {
        response.setHeader("Cache-Control", "no-store");
        siteContextService.assertCanAccessSite(request, requireSite(siteId));
        return AdminApiResponse.success(settingsService.get(siteId));
    }

    @PutMapping("/{siteId}/site-service/settings")
    public AdminApiResponse<SiteServiceStatusView> updateSiteServiceSettings(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody SiteServiceSettingsService.Update settings) {
        siteContextService.assertCanAccessSite(request, requireSite(siteId));
        return AdminApiResponse.success(toStatusView(settingsService.save(siteId, settings)));
    }

    /** 设置站点服务端口 */
    @PutMapping("/{siteId}/site-service/port")
    public AdminApiResponse<SiteServiceStatusView> setSiteServicePort(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam int port) {
        SiteServiceStatus status =
                siteServiceModeConfig.isIndependent()
                        ? standaloneSiteProcessManager.setPort(siteId, port)
                        : siteHttpServerManager.setPort(siteId, port);
        return AdminApiResponse.success(toStatusView(status));
    }

    /**
     * 校验站点服务端口是否已被其它站点占用（依据 cms_ai_site_project.site_service_port 字段）。
     *
     * <p>供新增/编辑站点保存前预检查；被占用时在端口池范围内给出建议端口。
     */
    @GetMapping("/port-availability")
    public AdminApiResponse<PortAvailabilityView> checkPortAvailability(
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) Long excludeSiteId) {
        if (port == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "端口不能为空");
        }
        if (!ServicePortRange.isValid(port)) {
            return AdminApiResponse.success(
                    new PortAvailabilityView(false, null, ServicePortRange.MESSAGE));
        }

        Set<Integer> usedPorts = new HashSet<>();
        Long ownerSiteId = null;
        String ownerSiteName = null;
        for (SiteProjectEntity site : projectMapper.selectList(null)) {
            Integer sitePort = site.getSiteServicePort();
            if (sitePort == null) {
                continue;
            }
            usedPorts.add(sitePort);
            if (excludeSiteId != null && excludeSiteId.equals(site.getId())) {
                continue;
            }
            if (ownerSiteId == null && sitePort.equals(port)) {
                ownerSiteId = site.getId();
                ownerSiteName = site.getProjectName();
            }
        }

        if (ownerSiteId != null) {
            String owner =
                    ownerSiteName == null || ownerSiteName.isBlank()
                            ? String.valueOf(ownerSiteId)
                            : ownerSiteName;
            return AdminApiResponse.success(
                    new PortAvailabilityView(
                            false,
                            suggestPort(usedPorts),
                            "端口 " + port + " 已被站点「" + owner + "」占用"));
        }
        return AdminApiResponse.success(new PortAvailabilityView(true, port, null));
    }

    /** 在端口池范围（8080-9000）内取第一个未被任何站点占用的端口，找不到返回 null */
    private Integer suggestPort(Set<Integer> usedPorts) {
        for (int candidate = PORT_POOL_MIN; candidate <= PORT_POOL_MAX; candidate++) {
            if (!usedPorts.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 获取站点服务状态 */
    @GetMapping("/{siteId}/site-service/status")
    public AdminApiResponse<SiteServiceStatusView> getSiteServiceStatus(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        SiteServiceStatus status = resolveStatus(site);
        return AdminApiResponse.success(toStatusView(status));
    }

    private SiteServiceStatus resolveStatus(SiteProjectEntity site) {
        return siteServiceModeConfig.isIndependent()
                ? standaloneSiteProcessManager.getStatus(site)
                : siteHttpServerManager.getStatus(site);
    }

    private SiteServiceStatusView toStatusView(SiteServiceStatus status) {
        return new SiteServiceStatusView(
                status.status(), status.port(), status.accessUrl(), status.errorMessage());
    }

    /** 重新加载站点路由 */
    @PostMapping("/{siteId}/site-service/reload-routes")
    public AdminApiResponse<Boolean> reloadSiteRoutes(
            HttpServletRequest request, @PathVariable Long siteId) {
        if (siteServiceModeConfig.isIndependent()) {
            try {
                standaloneSiteProcessManager.callInternal(requireSite(siteId), true);
                return AdminApiResponse.success(true);
            } catch (Exception e) {
                log.warn("独立进程重载路由失败: siteId={}, error={}", siteId, e.getMessage());
                return AdminApiResponse.success(false);
            }
        }
        return AdminApiResponse.success(siteHttpServerManager.reloadRoutes(siteId));
    }

    /** 获取站点路由列表 */
    @GetMapping("/{siteId}/site-service/routes")
    public AdminApiResponse<RouteListView> getSiteRoutes(
            HttpServletRequest request, @PathVariable Long siteId) {
        if (siteServiceModeConfig.isIndependent()) {
            try {
                String body = standaloneSiteProcessManager.callInternal(requireSite(siteId), false);
                SiteRoutesSnapshot snapshot = JSON_MAPPER.readValue(body, SiteRoutesSnapshot.class);
                List<RouteInfoView> indepRoutes =
                        snapshot.routes().stream().map(this::withSampleUrl).toList();
                return AdminApiResponse.success(
                        new RouteListView(indepRoutes, snapshot.conflicts()));
            } catch (Exception e) {
                log.warn("获取独立进程路由失败: siteId={}, error={}", siteId, e.getMessage());
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "获取路由失败: " + e.getMessage());
            }
        }

        List<SiteHttpServer.RouteInfo> routes = siteHttpServerManager.getRoutes(siteId);
        List<SiteHttpServer.RouteConflict> conflicts =
                siteHttpServerManager.getRouteConflicts(siteId);

        List<RouteInfoView> routeViews =
                routes.stream()
                        .map(
                                r ->
                                        new RouteInfoView(
                                                r.pattern(),
                                                r.originalUrl(),
                                                r.type(),
                                                r.isDynamic(),
                                                r.columnId(),
                                                r.parentId(),
                                                r.columnName(),
                                                r.depth(),
                                                r.sort(),
                                                r.templatePath(),
                                                r.description(),
                                                resolveSampleUrl(
                                                        r.originalUrl(),
                                                        r.columnId(),
                                                        r.parentId(),
                                                        r.columnName())))
                        .toList();

        List<RouteConflictView> conflictViews =
                conflicts.stream()
                        .map(
                                c ->
                                        new RouteConflictView(
                                                c.pattern(),
                                                c.columns().stream()
                                                        .map(
                                                                col ->
                                                                        new ConflictColumnView(
                                                                                col.columnId(),
                                                                                col.columnName()))
                                                        .toList()))
                        .toList();

        return AdminApiResponse.success(new RouteListView(routeViews, conflictViews));
    }

    /** 用当前栏目信息把动态路由占位符替换成真实样例地址（{id} 取该栏目第一条内容）。 */
    private RouteInfoView withSampleUrl(RouteInfoView r) {
        return new RouteInfoView(
                r.pattern(),
                r.originalUrl(),
                r.type(),
                r.isDynamic(),
                r.columnId(),
                r.parentId(),
                r.columnName(),
                r.depth(),
                r.sort(),
                r.templatePath(),
                r.description(),
                resolveSampleUrl(r.originalUrl(), r.columnId(), r.parentId(), r.columnName()));
    }

    private String resolveSampleUrl(
            String originalUrl, Integer columnId, Integer parentId, String columnName) {
        if (originalUrl == null || originalUrl.isBlank()) return null;
        String url =
                originalUrl
                        .replace("{classId}", String.valueOf(columnId))
                        .replace("{parentId}", String.valueOf(parentId))
                        .replace("${className}", columnName == null ? "" : columnName)
                        .replace("{pageIndex}", "1")
                        .replace("{pageSize}", "10");
        if (url.contains("{id}")) {
            Integer sampleId = sampleContentId(columnId);
            if (sampleId == null) return null;
            url = url.replace("{id}", String.valueOf(sampleId));
        }
        return url;
    }

    private Integer sampleContentId(Integer columnId) {
        if (columnId == null) return null;
        try {
            var list = infoService.getArticleSummariesByClassId(columnId, 1);
            if (list == null || list.isEmpty()) return null;
            var first = list.get(0);
            return first == null ? null : first.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 访问站点 - 根据根栏目导航渲染对应模板
     *
     * <p>逻辑： 1. 获取站点的 rootMenuId 2. 获取根栏目配置 3. 根据栏目类型和模板配置找到对应模板 4. 使用预览服务渲染模板
     */
    @GetMapping("/{siteId}/visit")
    public AdminApiResponse<TemplateRenderResult> visitSite(
            HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);

        // 检查 UI 包
        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("UI 包未上传或未启动"),
                            "UI_PACKAGE_NOT_FOUND",
                            null,
                            null));
        }

        // 获取根栏目
        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId == null) {
            // 没有绑定根栏目，尝试渲染默认首页
            return renderDefaultIndex(site);
        }

        OneshotEntity rootMenu = oneShotMapper.getModelById(rootMenuId);
        if (rootMenu == null) {
            log.warn("站点 {} 的根栏目 {} 不存在", siteId, rootMenuId);
            return renderDefaultIndex(site);
        }

        // 确定要渲染的模板
        String templatePath =
                determineTemplatePath(
                        rootMenu, templateStorageService.resolveTemplateDir(site.getProjectCode()));

        // 检查模板是否存在
        Path templateFile =
                templateStorageService
                        .resolveTemplateDir(site.getProjectCode())
                        .resolve(templatePath);
        if (!Files.isRegularFile(templateFile)) {
            log.warn("模板文件不存在: {}, 尝试使用默认首页", templatePath);
            return renderDefaultIndex(site);
        }

        try {
            // 根据栏目类型选择渲染方式
            Integer pageType = rootMenu.getPageType();
            Long columnId = rootMenuId.longValue();

            String renderedHtml;
            if (pageType != null && pageType == 1) {
                // 列表栏目 - 使用列表模板渲染（带分页数据）
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            } else {
                // 单篇栏目或默认 - 使用列表模板渲染（传入 columnId 以提供上下文）
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            }

            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            renderedHtml, null, null, rootMenu.getClassName(), templatePath));
        } catch (Exception e) {
            log.error("渲染站点模板失败: siteId={}, template={}", siteId, templatePath, e);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("渲染失败: " + e.getMessage()),
                            "RENDER_ERROR",
                            null,
                            null));
        }
    }

    /**
     * 新窗口预览 - 返回纯 HTML 页面用于浏览器预览
     *
     * <p>直接返回渲染后的 HTML，便于在新窗口中查看完整页面效果
     */
    @GetMapping(value = "/{siteId}/preview", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String previewSiteHtml(HttpServletRequest request, @PathVariable Long siteId) {
        SiteProjectEntity site = requireSite(siteId);

        // 检查 UI 包
        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return buildErrorPage("UI 包未上传或未启动");
        }

        // 获取根栏目
        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId == null) {
            // 没有绑定根栏目，尝试渲染默认首页
            return renderDefaultIndexHtml(site);
        }

        OneshotEntity rootMenu = oneShotMapper.getModelById(rootMenuId);
        if (rootMenu == null) {
            log.warn("站点 {} 的根栏目 {} 不存在", siteId, rootMenuId);
            return renderDefaultIndexHtml(site);
        }

        // 确定要渲染的模板
        String templatePath =
                determineTemplatePath(
                        rootMenu, templateStorageService.resolveTemplateDir(site.getProjectCode()));

        // 检查模板是否存在
        Path templateFile =
                templateStorageService
                        .resolveTemplateDir(site.getProjectCode())
                        .resolve(templatePath);
        if (!Files.isRegularFile(templateFile)) {
            log.warn("模板文件不存在: {}, 尝试使用默认首页", templatePath);
            return renderDefaultIndexHtml(site);
        }

        try {
            // 根据栏目类型选择渲染方式
            Integer pageType = rootMenu.getPageType();
            Long columnId = rootMenuId.longValue();

            String renderedHtml;
            if (pageType != null && pageType == 1) {
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            } else {
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            }

            return renderedHtml;
        } catch (Exception e) {
            log.error("渲染站点模板失败: siteId={}, template={}", siteId, templatePath, e);
            return buildErrorPage("渲染失败: " + e.getMessage());
        }
    }

    /** 获取站点的模板文件列表 */
    @GetMapping("/{siteId}/templates")
    public AdminApiResponse<List<TemplateFileView>> listTemplates(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam(required = false) String type) {
        SiteProjectEntity site = requireSite(siteId);

        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(List.of());
        }

        Path templateDir = templateStorageService.resolveTemplateDir(site.getProjectCode());
        if (!Files.isDirectory(templateDir)) {
            return AdminApiResponse.success(List.of());
        }

        List<TemplateFileView> templates = new ArrayList<>();
        try {
            Files.walk(templateDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .forEach(
                            p -> {
                                String relativePath =
                                        templateDir.relativize(p).toString().replace("\\", "/");
                                String templateType = determineTemplateType(relativePath);
                                if (type == null || type.isEmpty() || type.equals(templateType)) {
                                    templates.add(
                                            new TemplateFileView(
                                                    relativePath,
                                                    p.getFileName().toString(),
                                                    templateType));
                                }
                            });
        } catch (Exception e) {
            log.error("列出模板文件失败", e);
        }

        return AdminApiResponse.success(templates);
    }

    /** 渲染指定模板 */
    @GetMapping("/{siteId}/render")
    public AdminApiResponse<TemplateRenderResult> renderTemplate(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam(required = false) String templatePath,
            @RequestParam(required = false) Long columnId) {
        SiteProjectEntity site = requireSite(siteId);

        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("UI 包未上传或未启动"),
                            "UI_PACKAGE_NOT_FOUND",
                            null,
                            null));
        }

        // 如果没有指定模板，根据栏目获取
        if (templatePath == null || templatePath.isEmpty()) {
            if (columnId != null) {
                OneshotEntity column = oneShotMapper.getModelById(columnId.intValue());
                if (column != null) {
                    templatePath =
                            determineTemplatePath(
                                    column,
                                    templateStorageService.resolveTemplateDir(
                                            site.getProjectCode()));
                }
            }
            if (templatePath == null || templatePath.isEmpty()) {
                templatePath = DEFAULT_INDEX_TEMPLATE;
            }
        }

        Path templateFile =
                templateStorageService
                        .resolveTemplateDir(site.getProjectCode())
                        .resolve(templatePath);
        if (!Files.isRegularFile(templateFile)) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("模板文件不存在: " + templatePath),
                            "TEMPLATE_NOT_FOUND",
                            null,
                            null));
        }

        try {
            String renderedHtml;
            if (columnId != null) {
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            } else {
                renderedHtml = previewService.renderCommonTemplatePreview(siteId, templatePath);
            }
            return AdminApiResponse.success(
                    new TemplateRenderResult(renderedHtml, null, null, null, templatePath));
        } catch (Exception e) {
            log.error("渲染模板失败: {}", templatePath, e);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("渲染失败: " + e.getMessage()),
                            "RENDER_ERROR",
                            null,
                            null));
        }
    }

    /** 渲染栏目列表页 */
    @GetMapping("/{siteId}/render-list/{columnId}")
    public AdminApiResponse<TemplateRenderResult> renderListTemplate(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable Long columnId,
            @RequestParam(required = false) String templatePath) {
        SiteProjectEntity site = requireSite(siteId);

        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("UI 包未上传或未启动"),
                            "UI_PACKAGE_NOT_FOUND",
                            null,
                            null));
        }

        // 获取栏目配置的模板
        OneshotEntity column = oneShotMapper.getModelById(columnId.intValue());
        String actualTemplatePath = templatePath;
        if (actualTemplatePath == null || actualTemplatePath.isEmpty()) {
            if (column != null
                    && column.getListPageTemplate() != null
                    && !column.getListPageTemplate().isEmpty()) {
                actualTemplatePath = column.getListPageTemplate();
            } else {
                actualTemplatePath = "news/index.html";
            }
        }

        try {
            String renderedHtml =
                    previewService.renderListTemplatePreview(siteId, actualTemplatePath, columnId);
            String columnName = column != null ? column.getClassName() : null;
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            renderedHtml, null, null, columnName, actualTemplatePath));
        } catch (Exception e) {
            log.error("渲染列表模板失败: siteId={}, columnId={}", siteId, columnId, e);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("渲染失败: " + e.getMessage()),
                            "RENDER_ERROR",
                            null,
                            null));
        }
    }

    /** 渲染内容详情页 */
    @GetMapping("/{siteId}/render-detail/{columnId}/{contentId}")
    public AdminApiResponse<TemplateRenderResult> renderDetailTemplate(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable Long columnId,
            @PathVariable Long contentId,
            @RequestParam(required = false) String templatePath) {
        SiteProjectEntity site = requireSite(siteId);

        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("UI 包未上传或未启动"),
                            "UI_PACKAGE_NOT_FOUND",
                            null,
                            null));
        }

        // 获取栏目配置的模板
        OneshotEntity column = oneShotMapper.getModelById(columnId.intValue());
        String actualTemplatePath = templatePath;
        if (actualTemplatePath == null || actualTemplatePath.isEmpty()) {
            if (column != null
                    && column.getDetailPageTemplate() != null
                    && !column.getDetailPageTemplate().isEmpty()) {
                actualTemplatePath = column.getDetailPageTemplate();
            } else {
                actualTemplatePath = "news/details.html";
            }
        }

        try {
            String renderedHtml =
                    previewService.renderDetailTemplatePreview(
                            siteId, actualTemplatePath, columnId, contentId);
            String columnName = column != null ? column.getClassName() : null;
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            renderedHtml, null, null, columnName, actualTemplatePath));
        } catch (Exception e) {
            log.error(
                    "渲染详情模板失败: siteId={}, columnId={}, contentId={}",
                    siteId,
                    columnId,
                    contentId,
                    e);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("渲染失败: " + e.getMessage()),
                            "RENDER_ERROR",
                            null,
                            null));
        }
    }

    /**
     * 获取栏目模板状态
     *
     * <p>从栏目的 listPageTemplate 或 detailPageTemplate 字段读取模板路径
     */
    @GetMapping("/{siteId}/columns/{columnId}/template-status")
    public AdminApiResponse<ColumnTemplateStatus> getColumnTemplateStatus(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @PathVariable Integer columnId,
            @RequestParam(required = false, defaultValue = "list") String templateType) {
        SiteProjectEntity site = requireSite(siteId);

        // 从栏目实体读取模板路径
        OneshotEntity column = oneShotMapper.getModelById(columnId);
        if (column == null) {
            return AdminApiResponse.success(new ColumnTemplateStatus(false, null, null, List.of()));
        }

        // 根据 templateType 选择对应的模板路径字段
        String templatePath = null;
        if ("detail".equals(templateType)) {
            templatePath = column.getDetailPageTemplate();
        } else {
            templatePath = column.getListPageTemplate();
        }

        boolean hasTemplate = templatePath != null && !templatePath.isBlank();

        return AdminApiResponse.success(
                new ColumnTemplateStatus(
                        hasTemplate,
                        hasTemplate ? templatePath : null,
                        null,
                        hasTemplate
                                ? List.of(
                                        new TemplateFileInfo(
                                                templatePath.substring(
                                                        templatePath.lastIndexOf('/') + 1),
                                                templatePath))
                                : List.of()));
    }

    // ==================== Helper Methods ====================

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站点 ID 不能为空");
        }
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "站点不存在");
        }
        return site;
    }

    /**
     * 确定要渲染的模板路径
     *
     * <p>优先级： 1. 栏目配置的模板 (listPageTemplate) 2. column-{id}/index.html 目录 3. classEnName/index.html
     * 或 classEnName.html 4. 默认 index.html
     */
    private String determineTemplatePath(OneshotEntity column, Path templateDir) {
        // 1. 优先使用栏目配置的模板
        String listTemplate = column.getListPageTemplate();
        if (listTemplate != null && !listTemplate.isEmpty()) {
            Path templateFile = templateDir.resolve(listTemplate);
            if (Files.isRegularFile(templateFile)) {
                return listTemplate;
            }
        }

        // 2. 尝试 column-{id}/index.html 格式
        Integer columnId = column.getId();
        if (columnId != null) {
            String columnDirPath = "column-" + columnId + "/index.html";
            if (Files.isRegularFile(templateDir.resolve(columnDirPath))) {
                return columnDirPath;
            }
        }

        // 3. 根据栏目英文名尝试查找模板
        String classEnName = column.getClassEnName();
        if (classEnName != null && !classEnName.isEmpty()) {
            // 尝试 classEnName/index.html
            String candidatePath = classEnName + "/index.html";
            if (Files.isRegularFile(templateDir.resolve(candidatePath))) {
                return candidatePath;
            }
            // 尝试 classEnName.html
            candidatePath = classEnName + ".html";
            if (Files.isRegularFile(templateDir.resolve(candidatePath))) {
                return candidatePath;
            }
        }

        // 4. 默认使用 index.html
        return DEFAULT_INDEX_TEMPLATE;
    }

    /** 渲染默认首页 */
    private AdminApiResponse<TemplateRenderResult> renderDefaultIndex(SiteProjectEntity site) {
        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("UI 包未上传或未启动"),
                            "UI_PACKAGE_NOT_FOUND",
                            null,
                            null));
        }

        Path templateFile =
                templateStorageService
                        .resolveTemplateDir(site.getProjectCode())
                        .resolve(DEFAULT_INDEX_TEMPLATE);
        if (!Files.isRegularFile(templateFile)) {
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("默认首页模板不存在: " + DEFAULT_INDEX_TEMPLATE),
                            "TEMPLATE_NOT_FOUND",
                            null,
                            null));
        }

        try {
            String renderedHtml =
                    previewService.renderCommonTemplatePreview(
                            site.getId(), DEFAULT_INDEX_TEMPLATE);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            renderedHtml,
                            null,
                            null,
                            site.getProjectName(),
                            DEFAULT_INDEX_TEMPLATE));
        } catch (Exception e) {
            log.error("渲染默认首页失败: siteId={}", site.getId(), e);
            return AdminApiResponse.success(
                    new TemplateRenderResult(
                            null,
                            buildErrorHtml("渲染失败: " + e.getMessage()),
                            "RENDER_ERROR",
                            null,
                            null));
        }
    }

    private String determineTemplateType(String relativePath) {
        if (relativePath.startsWith("inc/") || relativePath.contains("/inc/")) {
            return "common";
        }
        if (relativePath.contains("detail") || relativePath.contains("details")) {
            return "detail";
        }
        if (relativePath.equals("index.html") || relativePath.endsWith("/index.html")) {
            return "index";
        }
        return "list";
    }

    private String buildErrorHtml(String message) {
        return "<div class=\"render-error\" style=\"padding: 20px; background: #fff3f3; border: 1px"
                + " solid #ffcdd2; border-radius: 8px;\"><h3 style=\"color: #c62828; margin: 0 0"
                + " 10px;\">渲染错误</h3><pre style=\"background: #f5f5f5; padding: 10px;"
                + " border-radius: 4px; overflow: auto; font-size: 12px;\">"
                + escapeHtml(message)
                + "</pre></div>";
    }

    private String buildErrorPage(String message) {
        return "<!DOCTYPE html>"
                + "<html><head><meta charset=\"UTF-8\"><title>预览错误</title></head>"
                + "<body style=\"margin:0;padding:20px;background:#f5f5f5;\">"
                + buildErrorHtml(message)
                + "</body></html>";
    }

    private String renderDefaultIndexHtml(SiteProjectEntity site) {
        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return buildErrorPage("UI 包未上传或未启动");
        }

        Path templateFile =
                templateStorageService
                        .resolveTemplateDir(site.getProjectCode())
                        .resolve(DEFAULT_INDEX_TEMPLATE);
        if (!Files.isRegularFile(templateFile)) {
            return buildErrorPage("默认首页模板不存在: " + DEFAULT_INDEX_TEMPLATE);
        }

        try {
            return previewService.renderCommonTemplatePreview(site.getId(), DEFAULT_INDEX_TEMPLATE);
        } catch (Exception e) {
            log.error("渲染默认首页失败: siteId={}", site.getId(), e);
            return buildErrorPage("渲染失败: " + e.getMessage());
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

    // ==================== Response Types ====================

    public record SiteServiceView(
            Long siteId,
            String projectName,
            String projectCode,
            String siteDomain,
            String defaultLanguage,
            String status,
            boolean hasUiPackage,
            boolean hasTemplates,
            String uiServiceStatus,
            String uiServiceUrl,
            Integer siteServicePort,
            String siteServiceStatus,
            String siteServiceUrl,
            Long siteServicePid) {
        static SiteServiceView from(
                SiteProjectEntity site,
                UiPackageStorageService storageService,
                UiPackageHttpServer httpServer,
                SiteHttpServerManager siteHttpServerManager,
                StandaloneSiteProcessManager standaloneSiteProcessManager,
                boolean independent,
                TemplateStorageService templateStorageService) {
            Optional<Path> activePath = storageService.getActivePath(site.getProjectCode());
            boolean hasUiPackage = activePath.isPresent();

            boolean hasTemplates =
                    Files.isDirectory(
                            templateStorageService.resolveTemplateDir(site.getProjectCode()));

            // UI 服务状态：running/available/offline
            String uiServiceStatus = "offline";
            String uiServiceUrl = null;
            log.debug(
                    "站点 {} UI服务检查: isRunning={}, hasUiPackage={}",
                    site.getId(),
                    httpServer.isRunning(),
                    hasUiPackage);
            if (httpServer.isRunning() && hasUiPackage) {
                String accessUrl =
                        httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
                log.debug("站点 {} 首次获取 accessUrl={}", site.getId(), accessUrl);
                if (accessUrl != null) {
                    uiServiceStatus = "running";
                    uiServiceUrl = accessUrl;
                } else {
                    // HTTP 服务器运行，但站点未激活 - 自动激活
                    httpServer.activateSite(site.getProjectCode());
                    accessUrl =
                            httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
                    log.debug("站点 {} 激活后 accessUrl={}", site.getId(), accessUrl);
                    if (accessUrl != null) {
                        uiServiceStatus = "running";
                        uiServiceUrl = accessUrl;
                    } else {
                        uiServiceStatus = "available";
                    }
                }
            }
            log.debug(
                    "站点 {} 最终 uiServiceStatus={}, uiServiceUrl={}",
                    site.getId(),
                    uiServiceStatus,
                    uiServiceUrl);

            // 站点服务状态
            Integer siteServicePort = site.getSiteServicePort();
            String siteServiceStatus = site.getSiteServiceStatus();
            String siteServiceUrl = null;
            Long siteServicePid = null;

            if (independent) {
                // 独立进程：子进程句柄 + 端口探测
                SiteServiceStatus status = standaloneSiteProcessManager.getStatus(site);
                siteServiceStatus = status.status();
                siteServiceUrl = status.accessUrl();
                siteServicePid = standaloneSiteProcessManager.getPid(site.getId());
            } else if (siteHttpServerManager != null) {
                // 统一进程：内存中的嵌入式服务实例
                SiteServiceStatus status = siteHttpServerManager.getStatus(site);
                siteServiceStatus = status.status();
                siteServiceUrl = status.accessUrl();
            }

            return new SiteServiceView(
                    site.getId(),
                    site.getProjectName(),
                    site.getProjectCode(),
                    site.getSiteDomain(),
                    site.getDefaultLanguage(),
                    site.getStatus() != null ? site.getStatus() : "ACTIVE",
                    hasUiPackage,
                    hasTemplates,
                    uiServiceStatus,
                    uiServiceUrl,
                    siteServicePort,
                    siteServiceStatus != null ? siteServiceStatus : "STOPPED",
                    siteServiceUrl,
                    siteServicePid);
        }
    }

    public record TemplateFileView(String relativePath, String fileName, String type) {}

    public record TemplateRenderResult(
            String renderedHtml,
            String errorMessage,
            String errorCode,
            String columnName,
            String templatePath) {}

    public record UiServiceStatus(String status, String message) {}

    /** 站点服务状态视图 */
    public record SiteServiceStatusView(
            String status, Integer port, String accessUrl, String errorMessage) {}

    /** 服务模式与启动参数视图 */
    public record SiteServiceModeView(
            String mode,
            GlobalParamsView globalParams,
            Map<String, String> defaults,
            /** 切换服务模式后重启成功的站点数；非切换场景为 null */
            Integer restarted,
            /** 切换服务模式后重启失败的站点数；非切换场景为 null */
            Integer failed) {}

    /** 全局启动参数 */
    public record GlobalParamsView(
            String javaPath, String jarPath, String workDir, String jvmArgs, String programArgs) {}

    /** 站点启动参数视图：站点覆盖 + 全局默认 + 未配置时的默认值 */
    public record SiteLaunchParamsView(
            Map<String, String> override,
            GlobalParamsView globalParams,
            Map<String, String> defaults) {}

    /** 独立进程内部路由快照（用于代理子进程路由接口） */
    public record SiteRoutesSnapshot(
            List<RouteInfoView> routes, List<RouteConflictView> conflicts) {}

    public record UiServiceAccessUrl(String baseUrl, String indexUrl, String errorMessage) {}

    /** 栏目模板状态响应 */
    public record ColumnTemplateStatus(
            boolean hasTemplate,
            String templatePath,
            String templateDir,
            List<TemplateFileInfo> templateFiles) {}

    /** 模板文件信息 */
    public record TemplateFileInfo(String fileName, String relativePath) {}

    /** 路由信息视图 */
    public record RouteInfoView(
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
            String description,
            String sampleUrl) {}

    /** 路由列表视图（包含冲突信息） */
    public record RouteListView(List<RouteInfoView> routes, List<RouteConflictView> conflicts) {}

    /** 路由冲突视图 */
    public record RouteConflictView(String pattern, List<ConflictColumnView> columns) {}

    /** 冲突栏目视图 */
    public record ConflictColumnView(Integer columnId, String columnName) {}

    /** 本机网卡 IP 列表 */
    public record LocalIpList(List<String> ips, String defaultIp) {}

    /** 站点服务端口占用检查结果 */
    public record PortAvailabilityView(boolean available, Integer suggestedPort, String message) {}
}

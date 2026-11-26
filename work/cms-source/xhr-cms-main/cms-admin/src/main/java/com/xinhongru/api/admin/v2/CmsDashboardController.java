package com.xinhongru.api.admin.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.common.page.PageResult;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.dto.StatisticsDto;
import com.xinhongru.dto.TopHitRow;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.LogOperationEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.logOperation.LogOperationService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.service.site.SiteService;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** CMS 仪表盘控制器 - 提供统计数据和概览信息 */
@RestController
@RequestMapping("/api/admin/v2/cms")
public class CmsDashboardController {
    private final SiteService siteService;
    private final OneShotService oneShotService;
    private final LogService logService;
    private final SiteContextService siteContextService;
    private final LogOperationService logOperationService;

    @Autowired
    public CmsDashboardController(
            SiteService siteService,
            OneShotService oneShotService,
            LogService logService,
            SiteContextService siteContextService,
            LogOperationService logOperationService) {
        this.siteService = siteService;
        this.oneShotService = oneShotService;
        this.logService = logService;
        this.siteContextService = siteContextService;
        this.logOperationService = logOperationService;
    }

    @GetMapping("/dashboard")
    public AdminApiResponse<DashboardResponse> dashboard(HttpServletRequest request) {
        // 获取当前站点上下文
        SiteContext context = siteContextService.current(request);
        boolean isGlobalMode = context == null || context.isGlobalMode();
        Long siteId = isGlobalMode ? null : context.getSiteId();

        // 获取统计数据（按站点）
        StatisticsDto statistics = siteService.getStatisticsBySiteId(siteId);

        // 获取栏目数量（使用与栏目管理相同的过滤逻辑）
        long menuCount = 0;
        if (!isGlobalMode && context.getRootMenuId() != null) {
            // 使用 getAllContentMenu 获取内容管理栏目，与栏目管理页面保持一致
            List<OneshotEntity> contentMenus =
                    oneShotService.getAllContentMenu(new OneshotDto(0), request);
            menuCount = contentMenus != null ? contentMenus.size() : 0;
        }

        // 获取访问量 Top10（整合单篇栏目和列表内容）
        List<TopHitItem> topHits = getTop10Hits(context);

        // 获取热门文章（只包含列表内容的访问量排名）
        List<HotArticleItem> hotArticles = getHotArticles(context);

        // 获取最新系统日志（取前10条）
        PageResult<LogEntity> logs = logService.getLogs(1, 10, request);
        List<LogItem> logItems = new ArrayList<>();
        if (logs != null && logs.getList() != null) {
            for (LogEntity log : logs.getList()) {
                logItems.add(
                        new LogItem(
                                log.getId(),
                                log.getUserName(),
                                log.getAction(),
                                log.getCreateTime() != null ? log.getCreateTime().toString() : ""));
            }
        }

        // 获取最近操作日志（内容/栏目变更记录，取前10条）
        List<LogOperationEntity> operationLogs = logOperationService.getRecentLogs(10);
        List<LogItem> operationLogItems = new ArrayList<>();
        if (operationLogs != null) {
            for (LogOperationEntity log : operationLogs) {
                operationLogItems.add(
                        new LogItem(
                                log.getId(),
                                log.getUserName(),
                                log.getAction(),
                                log.getCreated() != null ? log.getCreated().toString() : ""));
            }
        }

        DashboardStatistics dashboardStats =
                new DashboardStatistics(
                        statistics.getArticles() != null ? statistics.getArticles() : 0,
                        menuCount,
                        statistics.getPendingReviewCount() != null
                                ? statistics.getPendingReviewCount()
                                : 0,
                        statistics.getDraftCount() != null ? statistics.getDraftCount() : 0);

        return AdminApiResponse.success(
                new DashboardResponse(
                        dashboardStats, topHits, hotArticles, logItems, operationLogItems));
    }

    /** 获取访问量 Top10（整合单篇栏目和列表内容） */
    /** 获取访问量 Top10（合并单篇栏目与列表内容，统一按访问量倒序，由 SQL 一次完成） */
    private List<TopHitItem> getTop10Hits(SiteContext context) {
        boolean isGlobalMode = context == null || context.isGlobalMode();
        Long siteId = isGlobalMode ? null : context.getSiteId();
        List<TopHitRow> rows = siteService.getTop10Hits(siteId);
        return rows.stream()
                .map(
                        row ->
                                new TopHitItem(
                                        row.getId(),
                                        row.getTitle(),
                                        row.getHits() == null ? 0 : row.getHits(),
                                        row.getMenuId(),
                                        row.getType(),
                                        row.getMenuName(),
                                        row.getDetailUrl()))
                .collect(Collectors.toList());
    }

    /** 获取热门文章（只包含列表内容的访问量排名，参考老 CMS 实现） */
    private List<HotArticleItem> getHotArticles(SiteContext context) {
        List<HotArticleItem> hotArticles = new ArrayList<>();
        boolean isGlobalMode = context == null || context.isGlobalMode();
        Long siteId = isGlobalMode ? null : context.getSiteId();

        // 获取列表内容的访问量（page_type=1 的栏目下的文章）
        List<ContentEntity> topArticles = siteService.getTop10BySiteId(siteId);
        if (topArticles != null) {
            for (ContentEntity content : topArticles) {
                if (content.getHits() != null && content.getHits() > 0) {
                    OneshotEntity menu = oneShotService.getModelById(content.getClassid());
                    String detailUrl = buildDetailUrl(content, menu);
                    hotArticles.add(
                            new HotArticleItem(
                                    content.getId(),
                                    content.getTitle(),
                                    content.getHits(),
                                    menu != null ? menu.getClassName() : "",
                                    detailUrl));
                }
            }
        }

        return hotArticles;
    }

    private String buildDetailUrl(ContentEntity content, OneshotEntity menu) {
        if (menu == null || menu.getDetailPageUrl() == null) {
            return "";
        }
        return menu.getDetailPageUrl()
                .replace("{id}", String.valueOf(content.getId()))
                .replace("{classId}", String.valueOf(menu.getId()));
    }

    public record DashboardResponse(
            DashboardStatistics statistics,
            List<TopHitItem> topHits,
            List<HotArticleItem> hotArticles,
            List<LogItem> recentLogs,
            List<LogItem> operationLogs) {}

    public record DashboardStatistics(
            @JsonProperty("articleCount") long articleCount,
            @JsonProperty("menuCount") long menuCount,
            @JsonProperty("pendingReviewCount") long pendingReviewCount,
            @JsonProperty("draftCount") long draftCount) {}

    public record TopHitItem(
            @JsonProperty("id") Integer id,
            @JsonProperty("title") String title,
            @JsonProperty("hits") int hits,
            @JsonProperty("menuId") Integer menuId,
            @JsonProperty("type") String type, // "menu" 或 "content"
            @JsonProperty("menuName") String menuName,
            @JsonProperty("detailUrl") String detailUrl) {}

    public record HotArticleItem(
            @JsonProperty("id") Integer id,
            @JsonProperty("title") String title,
            @JsonProperty("hits") int hits,
            @JsonProperty("menuName") String menuName,
            @JsonProperty("detailUrl") String detailUrl) {}

    public record LogItem(
            @JsonProperty("id") Integer id,
            @JsonProperty("userName") String userName,
            @JsonProperty("action") String action,
            @JsonProperty("createTime") String createTime) {}
}

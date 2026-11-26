package com.xinhongru.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Converts runtime failures into operator-facing actions without leaking task ids or internal
 * status codes.
 */
@Service
public class SiteHealthOverviewService {
    private final JdbcTemplate jdbc;

    public SiteHealthOverviewService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "健康总览数据库不能为空");
    }

    public List<HealthItem> overview(long siteId) {
        return jdbc.query(
                """
                SELECT task_type, business_route, MAX(updated_at) AS observed_at
                  FROM cms_operation_task
                 WHERE site_id=? AND status='FAILED'
                 GROUP BY task_type, business_route
                 ORDER BY observed_at DESC
                """,
                (rs, rowNum) ->
                        toHealthItem(
                                OperationTask.Type.valueOf(rs.getString("task_type")),
                                rs.getString("business_route"),
                                rs.getTimestamp("observed_at").toLocalDateTime()),
                siteId);
    }

    private static HealthItem toHealthItem(
            OperationTask.Type type, String route, LocalDateTime observedAt) {
        return switch (type) {
            case AI_RECOGNITION ->
                    item(
                            "AI 识别需要人工处理",
                            "AI 识别未完成，请检查样本和复核项后重新执行",
                            route,
                            "/admin/v2/ai-workbench",
                            observedAt);
            case DATA_CONVERSION ->
                    item(
                            "数据转换需要处理",
                            "数据转换未完成，请检查预检、批准和回退准备",
                            route,
                            "/admin/v2/data-conversion",
                            observedAt);
            case RESOURCE_PROCESSING ->
                    item(
                            "资源处理需要处理",
                            "资源处理未完成，请检查资源来源和存储可用性",
                            route,
                            "/admin/v2/resources",
                            observedAt);
            case STATIC_PUBLISH ->
                    item(
                            "站点发布需要处理",
                            "站点发布未完成，请检查发布准备后重新执行",
                            route,
                            "/admin/v2/site-publish",
                            observedAt);
            case IMPORT_EXPORT ->
                    item(
                            "导入导出需要处理",
                            "导入或导出未完成，请检查文件和站点数据后重新执行",
                            route,
                            "/admin/v2/ai-workbench",
                            observedAt);
        };
    }

    private static HealthItem item(
            String title,
            String message,
            String requestedRoute,
            String defaultRoute,
            LocalDateTime observedAt) {
        String route =
                requestedRoute == null || requestedRoute.isBlank() ? defaultRoute : requestedRoute;
        return new HealthItem("BLOCKING", title, message, route, observedAt);
    }

    public record HealthItem(
            String severity,
            String title,
            String message,
            String actionRoute,
            LocalDateTime observedAt) {}
}

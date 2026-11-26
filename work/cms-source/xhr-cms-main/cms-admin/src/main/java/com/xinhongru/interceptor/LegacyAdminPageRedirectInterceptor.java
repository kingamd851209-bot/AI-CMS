package com.xinhongru.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

@Component
public class LegacyAdminPageRedirectInterceptor implements HandlerInterceptor {
    public static final List<LegacyPageRoute> LEGACY_PAGE_ROUTES =
            List.of(
                    new LegacyPageRoute("/admin/menu/index", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/other", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/open", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/add/{parentId:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/edit/{id:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/seo/{id:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute(
                            "/admin/menu/open/add/{parentId:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu/open/edit/{id:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute("/admin/menu_manage/{id:[0-9]+}", "/admin/v2/cms/menus"),
                    new LegacyPageRoute(
                            "/admin/model_manage/{classid:[0-9]+}", "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/model_manage/{classid:[0-9]+}/add", "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/model_manage/{classid:[0-9]+}/edit/{id:[0-9]+}",
                            "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/model_manage/{classid:[0-9]+}/seo/{id:[0-9]+}",
                            "/admin/v2/cms/content"),
                    new LegacyPageRoute("/admin/check/index", "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/check/index/{classId:[0-9]+}", "/admin/v2/cms/content"),
                    new LegacyPageRoute("/admin/check/detail", "/admin/v2/cms/content"),
                    new LegacyPageRoute("/admin/check/check", "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/content/history/list/{classId:[0-9]+}/{infoId:[0-9]+}",
                            "/admin/v2/cms/content"),
                    new LegacyPageRoute(
                            "/admin/template_bind/index/{siteId:[0-9]+}", "/admin/v2/sites"),
                    new LegacyPageRoute("/admin/builder/index", "/admin/v2/sites"),
                    new LegacyPageRoute("/admin/user/index", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/user/add", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/user/edit/{uid:[0-9]+}", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/user_group/index", "/admin/v2/system/users"),
                    new LegacyPageRoute(
                            "/admin/user_group/add/{parentId:[0-9]+}", "/admin/v2/system/users"),
                    new LegacyPageRoute(
                            "/admin/user_group/edit/{id:[0-9]+}", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/profile/user", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/profile/password", "/admin/v2/system/users"),
                    new LegacyPageRoute("/admin/system/setting", "/admin/v2/system/settings"),
                    new LegacyPageRoute("/admin/site-management/index", "/admin/v2/sites"),
                    new LegacyPageRoute("/admin/site-preview/{siteId:[0-9]+}", "/admin/v2/sites"),
                    new LegacyPageRoute("/admin/ai/config/index", "/admin/v2/ai/config"),
                    new LegacyPageRoute("/admin/log/index", "/admin/v2/system/logs"),
                    new LegacyPageRoute("/admin/log/details/{id:[0-9]+}", "/admin/v2/system/logs"),
                    new LegacyPageRoute(
                            "/admin/log-operation/index/{classId:[0-9]+}/{infoId:[0-9]+}",
                            "/admin/v2/system/logs"));

    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();

    public static String[] pathPatterns() {
        return LEGACY_PAGE_ROUTES.stream().map(LegacyPageRoute::pattern).toArray(String[]::new);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String targetRoute = resolveTargetRoute(request.getRequestURI(), request.getContextPath());
        if (targetRoute == null) {
            return true;
        }

        response.sendRedirect(request.getContextPath() + targetRoute);
        return false;
    }

    String resolveTargetRoute(String requestUri, String contextPath) {
        String uri = normalizeRequestUri(requestUri, contextPath);
        for (LegacyPageRoute route : LEGACY_PAGE_ROUTES) {
            if (PATH_PATTERN_PARSER.parse(route.pattern()).matches(PathContainer.parsePath(uri))) {
                return route.targetRoute();
            }
        }
        return null;
    }

    private String normalizeRequestUri(String requestUri, String contextPath) {
        if (StringUtils.isBlank(requestUri)) {
            return "";
        }
        if (StringUtils.isNotBlank(contextPath) && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    public record LegacyPageRoute(String pattern, String targetRoute) {}
}

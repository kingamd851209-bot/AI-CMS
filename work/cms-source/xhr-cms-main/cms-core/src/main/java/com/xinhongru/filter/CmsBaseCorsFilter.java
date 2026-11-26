package com.xinhongru.filter;

import com.xinhongru.utils.RequestOriginUtils;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className CmsBaseCorsFilter
 * @date 2023/4/5 21:58
 * @since 1.0
 */
@Slf4j
@Component
public class CmsBaseCorsFilter implements Filter, Ordered {
    private final Environment environment;

    public CmsBaseCorsFilter() {
        this(null);
    }

    @Autowired
    public CmsBaseCorsFilter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
        log.info("CmsBaseCorsFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        String uri = httpServletRequest.getRequestURI();
        String origin = httpServletRequest.getHeader("Origin");
        boolean adminCorsRequest = isAdminCorsPath(uri);
        boolean allowedAdminOrigin =
                adminCorsRequest && isAllowedAdminOrigin(httpServletRequest, origin);
        if (allowedAdminOrigin) {
            httpServletResponse.setHeader("Access-Control-Allow-Origin", origin);
            httpServletResponse.setHeader("Access-Control-Allow-Credentials", "true");
            httpServletResponse.setHeader("Vary", "Origin");
        } else if (!adminCorsRequest && isAllowedPublicOrigin(origin)) {
            httpServletResponse.setHeader("Access-Control-Allow-Origin", origin);
            httpServletResponse.setHeader("Vary", "Origin");
        }
        String requestHeaders = httpServletRequest.getHeader("Access-Control-Request-Headers");
        httpServletResponse.setHeader(
                "Access-Control-Allow-Headers",
                requestHeaders == null || requestHeaders.isBlank() ? "*" : requestHeaders);
        httpServletResponse.setHeader(
                "Access-Control-Allow-Methods", "OPTIONS,GET,POST,PUT,PATCH,DELETE");
        httpServletResponse.setHeader("Access-Control-Max-Age", "3600");
        if ("OPTIONS".equalsIgnoreCase(httpServletRequest.getMethod())
                && (allowedAdminOrigin || (!adminCorsRequest && isAllowedPublicOrigin(origin)))) {
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return 10;
    }

    private boolean isAdminApiPath(String uri) {
        return uri != null && ("/api/admin".equals(uri) || uri.startsWith("/api/admin/"));
    }

    private boolean isAdminCorsPath(String uri) {
        return isAdminApiPath(uri)
                || "/admin/login".equals(uri)
                || (uri != null && uri.startsWith("/admin/login/"))
                || "/admin/logout".equals(uri)
                || (uri != null && uri.startsWith("/api/image-code/"))
                || "/image-code/get".equals(uri)
                || (uri != null && uri.startsWith("/image-code/"));
    }

    private boolean isAllowedPublicOrigin(String origin) {
        if (origin == null || origin.isBlank() || environment == null) {
            return false;
        }

        String allowedOrigins = environment.getProperty("cms.cors.allowed-origins", "");
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .anyMatch(origin::equals);
    }

    private boolean isAllowedAdminOrigin(HttpServletRequest request, String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        if (RequestOriginUtils.isSameOrigin(request, origin)) {
            return true;
        }
        return isAllowedConfiguredAdminOrigin(origin);
    }

    private boolean isAllowedConfiguredAdminOrigin(String origin) {
        if (origin == null || origin.isBlank() || environment == null) {
            return false;
        }
        String allowedOrigins = environment.getProperty("cms.cors.admin-allowed-origins", "");
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .anyMatch(origin::equals);
    }
}

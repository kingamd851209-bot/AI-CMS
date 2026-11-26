package com.xinhongru.security;

import com.xinhongru.utils.RequestOriginUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Service
public class SessionCsrfService {
    private static final Set<String> LEGACY_GET_MUTATION_PATHS =
            Set.of(
                    "/admin/logout",
                    "/member/logOut",
                    "/admin/builder/publish",
                    "/admin/model_manage/update-x-gid",
                    "/admin/model_manage/insert-images");

    private final Environment environment;

    public SessionCsrfService(Environment environment) {
        this.environment = environment;
    }

    public void requireValidWriteRequest(HttpServletRequest request) {
        if (!requiresSameOriginValidation(request)) {
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && isTrustedOrigin(request, origin)) {
            return;
        }
        String referer = request.getHeader("Referer");
        if ((origin == null || origin.isBlank())
                && referer != null
                && !referer.isBlank()
                && isTrustedOrigin(request, referer)) {
            return;
        }
        throw AdminSecurityExceptionHandler.AdminSecurityException.forbidden(
                "ADMIN_CSRF_VALIDATION_FAILED", "写请求缺少有效的同源校验信息");
    }

    public boolean requiresSameOriginValidation(HttpServletRequest request) {
        return isUnsafeMethod(request.getMethod()) || isLegacyGetMutationPath(request);
    }

    private boolean isUnsafeMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private boolean isLegacyGetMutationPath(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && LEGACY_GET_MUTATION_PATHS.contains(requestPath(request));
    }

    public static String requestPath(HttpServletRequest request) {
        return org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private boolean isTrustedOrigin(HttpServletRequest request, String candidate) {
        return RequestOriginUtils.isSameOrigin(request, candidate)
                || isAllowedConfiguredAdminOrigin(candidate);
    }

    private boolean isAllowedConfiguredAdminOrigin(String origin) {
        if (environment == null) {
            return false;
        }
        String normalizedOrigin = normalizeOrigin(origin);
        if (normalizedOrigin == null) {
            return false;
        }
        String allowedOrigins = environment.getProperty("cms.cors.admin-allowed-origins", "");
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::normalizeOrigin)
                .anyMatch(normalizedOrigin::equals);
    }

    private String normalizeOrigin(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(candidate.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            int port = uri.getPort();
            boolean useDefaultPort =
                    port < 0
                            || ("http".equalsIgnoreCase(scheme) && port == 80)
                            || ("https".equalsIgnoreCase(scheme) && port == 443);
            StringBuilder builder =
                    new StringBuilder()
                            .append(scheme.toLowerCase(Locale.ROOT))
                            .append("://")
                            .append(host.toLowerCase(Locale.ROOT));
            if (!useDefaultPort) {
                builder.append(':').append(port);
            }
            return builder.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}

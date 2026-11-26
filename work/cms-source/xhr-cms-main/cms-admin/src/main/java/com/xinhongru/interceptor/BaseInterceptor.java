package com.xinhongru.interceptor;

import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.AdminRoleAccessService;
import com.xinhongru.security.AdminSecurityExceptionHandler;
import com.xinhongru.security.SessionCsrfService;
import com.xinhongru.utils.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/** 自定义拦截器 */
@Slf4j
@Component
public class BaseInterceptor implements HandlerInterceptor {
    @Autowired private AdminRoleAccessService roleAccessService;

    private static final String USER_AGENT = "user-agent";

    private final AdminAuthenticationService authenticationService;
    private final SessionCsrfService csrfService;
    private final AdminSecurityExceptionHandler securityExceptionHandler;

    @Autowired
    public BaseInterceptor(
            AdminAuthenticationService authenticationService,
            SessionCsrfService csrfService,
            AdminSecurityExceptionHandler securityExceptionHandler) {
        this.authenticationService = authenticationService;
        this.csrfService = csrfService;
        this.securityExceptionHandler = securityExceptionHandler;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object o)
            throws Exception {
        String uri = SessionCsrfService.requestPath(request);

        // 如果是静态文件，则不拦截
        if (isStaticAsset(uri) && o instanceof ResourceHttpRequestHandler) {
            return true;
        }
        /*log.info("UserAgent: {}", request.getHeader(USER_AGENT));
        log.info("用户访问地址: {}, 来路地址: {}", uri, RequestOriginUtils.clientIp(request));*/

        // log.info("UserAgent: {}", request.getHeader(USER_AGENT));
        log.info(
                "用户访问地址: {}, 来路地址: {}, UserAgent: {}",
                uri,
                RequestOriginUtils.clientIp(request),
                request.getHeader(USER_AGENT));

        boolean authenticated = false;
        try {
            if (isPublicApiPath(uri)) {
                // 公开访问路径，无需认证
                return true;
            } else if (isAdminApiPath(uri) && !isPublicAdminApiRequest(request, uri)) {
                authenticationService.requireAuthenticated(request);
                authenticated = true;
                csrfService.requireValidWriteRequest(request);
            } else if (isAdminPagePath(uri) && !isPublicAdminPageRequest(uri)) {
                authenticated = authenticationService.authenticate(request).isPresent();
                if (authenticated) {
                    csrfService.requireValidWriteRequest(request);
                }
            }
            if (authenticated
                    && roleAccessService != null
                    && roleAccessService.pending(
                            authenticationService.requireLoginSessionUser(request))
                    && !"/api/admin/v2/auth/session".equals(uri)
                    && !"/api/admin/v2/auth/logout".equals(uri)
                    && !"/admin/logout".equals(uri)) {
                if (isAdminPagePath(uri)) {
                    response.sendRedirect(request.getContextPath() + "/admin/login/pending");
                    return false;
                }
                throw AdminSecurityExceptionHandler.AdminSecurityException.forbidden(
                        "ADMIN_ROLE_REQUIRED", "账号已登录，等待管理员核实身份并分配角色");
            }
        } catch (AdminSecurityExceptionHandler.AdminSecurityException exception) {
            if (isAuthenticationFailure(exception)) {
                authenticationService.clearAuthenticationState(request, response);
            }
            if (isAdminPagePath(uri) && isAuthenticationFailure(exception)) {
                response.sendRedirect(request.getContextPath() + "/admin/login");
                log.info("后台页面认证失效，跳转到登录页面: {}", exception.code());
                return false;
            }
            securityExceptionHandler.writeError(request, response, exception);
            log.info("拒绝后台请求: {}", exception.code());
            return false;
        }

        // 判断访问路径是/admin开头且不是/admin/login 且是非登录用户访问 非指定的目录的对象都跳转到登录页面
        if (isAdminPagePath(uri)
                && !uri.startsWith("/admin/login")
                && !authenticated
                && !uri.startsWith("/admin/css")
                && !uri.startsWith("/admin/images")
                && !uri.startsWith("/admin/js")
                && !uri.startsWith("/admin/plugins")
                && !uri.startsWith("/admin/ecj27zyqedit")
                && !uri.startsWith("/admin/plugins")
                && !uri.startsWith("/admin/editormd")) {
            authenticationService.clearAuthenticationState(request, response);
            response.sendRedirect(request.getContextPath() + "/admin/login");
            log.info("未登录用户访问后台，跳转到登录页面");
            return false;
        }

        log.info("BaseInterceptor拦截器放行");
        return true;
    }

    private boolean isAdminApiPath(String uri) {
        return uri != null && ("/api/admin".equals(uri) || uri.startsWith("/api/admin/"));
    }

    private boolean isPublicApiPath(String uri) {
        return uri != null && uri.startsWith("/api/public/");
    }

    private boolean isPublicAdminApiRequest(HttpServletRequest request, String uri) {
        return ("GET".equalsIgnoreCase(request.getMethod())
                        && ("/api/admin/v2/system/branding".equals(uri)
                                || "/api/admin/login/public-key".equals(uri)))
                || ("POST".equalsIgnoreCase(request.getMethod()) && "/api/admin/login".equals(uri));
    }

    private boolean isAdminPagePath(String uri) {
        return uri != null && uri.startsWith("/admin");
    }

    private boolean isPublicAdminPageRequest(String uri) {
        return uri != null && ("/admin/login".equals(uri) || uri.startsWith("/admin/login/"));
    }

    private boolean isAuthenticationFailure(
            AdminSecurityExceptionHandler.AdminSecurityException exception) {
        return "ADMIN_AUTHENTICATION_REQUIRED".equals(exception.code())
                || "ADMIN_ACCESS_DENIED".equals(exception.code());
    }

    private boolean isStaticAsset(String uri) {
        if (uri == null) {
            return false;
        }
        String normalizedUri = uri.toLowerCase();
        return normalizedUri.endsWith(".js")
                || normalizedUri.endsWith(".css")
                || normalizedUri.endsWith(".woff")
                || normalizedUri.endsWith(".woff2")
                || normalizedUri.endsWith(".ttf")
                || normalizedUri.endsWith(".ico")
                || normalizedUri.endsWith(".png")
                || normalizedUri.endsWith(".jpg")
                || normalizedUri.endsWith(".jpeg")
                || normalizedUri.endsWith(".gif")
                || normalizedUri.endsWith(".svg")
                || normalizedUri.endsWith(".webp")
                || normalizedUri.endsWith(".mp4")
                || normalizedUri.endsWith(".webm")
                || normalizedUri.endsWith(".mov");
    }
}

package com.xinhongru.interceptor;

import com.xinhongru.security.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class MemberSessionInterceptor implements HandlerInterceptor {
    private final MemberAuthenticationService authentication;
    private final SessionCsrfService csrf;
    private final AdminSecurityExceptionHandler errors;

    public MemberSessionInterceptor(
            MemberAuthenticationService authentication,
            SessionCsrfService csrf,
            AdminSecurityExceptionHandler errors) {
        this.authentication = authentication;
        this.csrf = csrf;
        this.errors = errors;
    }

    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        try {
            authentication.requireAuthenticated(request);
            csrf.requireValidWriteRequest(request);
            return true;
        } catch (AdminSecurityExceptionHandler.AdminSecurityException error) {
            errors.writeError(request, response, error);
            return false;
        }
    }
}

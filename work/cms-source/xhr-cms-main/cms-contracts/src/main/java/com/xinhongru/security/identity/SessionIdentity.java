package com.xinhongru.security.identity;

import com.xinhongru.entity.UserEntity;

import jakarta.servlet.http.HttpServletRequest;

/** Request-scoped identity established by the authentication interceptor. */
public final class SessionIdentity {
    public static final String ADMIN_USER = SessionIdentity.class.getName() + ".admin";
    public static final String MEMBER_USER = SessionIdentity.class.getName() + ".member";

    private SessionIdentity() {}

    public static UserEntity admin(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ADMIN_USER);
        return value instanceof UserEntity user ? user : null;
    }
}

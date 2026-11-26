package com.xinhongru.security;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.security.identity.SessionPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Common login/logout lifecycle for administrator and member sessions backed by Spring Session
 * Redis.
 */
@Service
public class SessionIdentityService {
    public void loginAdmin(HttpServletRequest request, UserEntity user) {
        HttpSession session = rotatedSession(request);
        session.setAttribute(
                WebConst.LOGIN_SESSION_KEY,
                new SessionPrincipal(user.getId(), SessionPrincipal.Realm.ADMIN));
        session.removeAttribute(WebConst.CURRENT_SESSION_SITE);
        request.setAttribute(SessionIdentity.ADMIN_USER, user);
    }

    public void loginMember(HttpServletRequest request, Integer id) {
        rotatedSession(request)
                .setAttribute(
                        WebConst.MEMBER_SESSION_KEY,
                        new SessionPrincipal(id, SessionPrincipal.Realm.MEMBER));
        request.removeAttribute(SessionIdentity.MEMBER_USER);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        request.removeAttribute(SessionIdentity.ADMIN_USER);
        request.removeAttribute(SessionIdentity.MEMBER_USER);
        // The session cookie is expired by Spring Session. Remove the obsolete remember-me cookie
        // as well.
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(WebConst.USER_IN_COOKIE, "")
                        .path("/")
                        .httpOnly(true)
                        .sameSite("Lax")
                        .maxAge(0)
                        .build()
                        .toString());
    }

    private HttpSession rotatedSession(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        return session;
    }
}

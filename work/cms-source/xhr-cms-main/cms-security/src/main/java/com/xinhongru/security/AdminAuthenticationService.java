package com.xinhongru.security;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.security.identity.AccountDirectory;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.security.identity.SessionPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class AdminAuthenticationService {
    public static final String AUTHENTICATED_ADMIN_ATTRIBUTE =
            AdminAuthenticationService.class.getName() + ".admin";
    private final AccountDirectory accounts;
    private final SessionIdentityService sessions;

    public AdminAuthenticationService(AccountDirectory accounts, SessionIdentityService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    public Optional<AuthenticatedAdmin> authenticate(HttpServletRequest request) {
        UserEntity cached = SessionIdentity.admin(request);
        if (cached != null) return Optional.of(summary(cached));
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(WebConst.LOGIN_SESSION_KEY);
        Integer id =
                value instanceof SessionPrincipal principal
                                && principal.realm() == SessionPrincipal.Realm.ADMIN
                        ? principal.id()
                        : value instanceof UserEntity old ? old.getId() : null;
        if (id == null) return Optional.empty();
        UserEntity user = accounts.admin(id);
        if (user == null) {
            session.removeAttribute(WebConst.LOGIN_SESSION_KEY);
            return Optional.empty();
        }
        if (Integer.valueOf(1).equals(user.getActivated()))
            reject(session, "ACCOUNT_DISABLED", "账号已停用，请重新登录");
        UserGroupEntity group = accounts.group(user.getGroupId());
        boolean superAdmin =
                Objects.equals(user.getId(), WebConst.SUPER_ADMIN_ID)
                        || WebConst.SUPER_ADMIN_NAME.equals(user.getUsername());
        if (!superAdmin
                && group != null
                && (Boolean.FALSE.equals(group.getEnabled())
                        || Boolean.TRUE.equals(group.getDeleted())))
            reject(session, "ADMIN_GROUP_DISABLED", "管理员组已停用，请重新登录");
        if (group != null) {
            user.setGroupSetting(group.getGroupSetting());
            user.setOtherSetting(group.getOtherSetting());
        }
        if (!(value instanceof SessionPrincipal))
            session.setAttribute(
                    WebConst.LOGIN_SESSION_KEY,
                    new SessionPrincipal(id, SessionPrincipal.Realm.ADMIN));
        request.setAttribute(SessionIdentity.ADMIN_USER, user);
        AuthenticatedAdmin admin = summary(user);
        request.setAttribute(AUTHENTICATED_ADMIN_ATTRIBUTE, admin);
        return Optional.of(admin);
    }

    public void requireAuthenticated(HttpServletRequest request) {
        if (authenticate(request).isEmpty())
            throw AdminSecurityExceptionHandler.AdminSecurityException.unauthorized(
                    "ADMIN_AUTHENTICATION_REQUIRED", "未登录或登录已过期");
    }

    public UserEntity requireLoginSessionUser(HttpServletRequest request) {
        return requireAuthenticatedUser(request);
    }

    public UserEntity requireAuthenticatedUser(HttpServletRequest request) {
        requireAuthenticated(request);
        return SessionIdentity.admin(request);
    }

    public void clearAuthenticationState(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
    }

    public void establish(HttpServletRequest request, UserEntity user) {
        sessions.loginAdmin(request, user);
    }

    private void reject(HttpSession session, String reason, String message) {
        session.removeAttribute(WebConst.LOGIN_SESSION_KEY);
        throw AdminSecurityExceptionHandler.AdminSecurityException.forbidden(
                "ADMIN_ACCESS_DENIED", message, Map.of("reason", reason));
    }

    private AuthenticatedAdmin summary(UserEntity user) {
        return new AuthenticatedAdmin(user.getId(), user.getUsername(), user.getGroupId());
    }

    public record AuthenticatedAdmin(Integer id, String username, Integer groupId) {}
}

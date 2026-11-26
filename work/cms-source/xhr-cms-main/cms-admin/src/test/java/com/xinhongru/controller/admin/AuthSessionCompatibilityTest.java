package com.xinhongru.controller.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.api.admin.v2.AuthV2ApiController;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.security.*;
import com.xinhongru.security.identity.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthSessionCompatibilityTest {
    AuthV2ApiController controller;
    SessionIdentityService sessions;
    AdminAuthenticationService authentication;
    AdminRoleAccessService roles;
    AccountDirectory accounts;
    UserEntity user;

    @BeforeEach
    void setup() {
        sessions = new SessionIdentityService();
        accounts = mock(AccountDirectory.class);
        authentication = new AdminAuthenticationService(accounts, sessions);
        roles = mock(AdminRoleAccessService.class);
        controller = new AuthV2ApiController(sessions, authentication, roles);
        user = new UserEntity().setId(30).setUsername("operator").setScreenName("编辑").setGroupId(0);
        when(accounts.admin(30)).thenReturn(user);
    }

    @Test
    void localSessionRotatesIdentityClearsSiteAndExposesPendingAuthorization() {
        var request = new MockHttpServletRequest();
        String oldId = request.getSession().getId();
        request.getSession().setAttribute(WebConst.CURRENT_SESSION_SITE, 4L);
        authentication.establish(request, user);
        when(roles.pending(user)).thenReturn(true);
        var response = new MockHttpServletResponse();
        var status = controller.session(request, response).data();
        assertThat(request.getSession().getId()).isNotEqualTo(oldId);
        assertThat(request.getSession().getAttribute(WebConst.LOGIN_SESSION_KEY))
                .isEqualTo(new SessionPrincipal(30, SessionPrincipal.Realm.ADMIN));
        assertThat(request.getSession().getAttribute(WebConst.CURRENT_SESSION_SITE)).isNull();
        assertThat(status.username()).isEqualTo("operator");
        assertThat(status.displayName()).isEqualTo("编辑");
        assertThat(status.pendingAuthorization()).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void anonymousAndMemberSessionsCannotReadAdminIdentity() {
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.session(request, new MockHttpServletResponse()))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        sessions.loginMember(request, 42);
        assertThatThrownBy(() -> controller.session(request, new MockHttpServletResponse()))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        verifyNoInteractions(accounts, roles);
    }

    @Test
    void logoutInvalidatesSessionAndRemovesRequestIdentity() {
        var request = new MockHttpServletRequest();
        authentication.establish(request, user);
        var old = (MockHttpSession) request.getSession();
        controller.logout(request, new MockHttpServletResponse());
        assertThat(old.isInvalid()).isTrue();
        assertThat(authentication.authenticate(request)).isEmpty();
    }

    @Test
    void disabledLocalAccountCannotRestoreAdminSession() {
        var request = new MockHttpServletRequest();
        request.getSession()
                .setAttribute(
                        WebConst.LOGIN_SESSION_KEY,
                        new SessionPrincipal(30, SessionPrincipal.Realm.ADMIN));
        user.setActivated(1);
        assertThatThrownBy(() -> controller.session(request, new MockHttpServletResponse()))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        assertThat(request.getSession().getAttribute(WebConst.LOGIN_SESSION_KEY)).isNull();
    }
}

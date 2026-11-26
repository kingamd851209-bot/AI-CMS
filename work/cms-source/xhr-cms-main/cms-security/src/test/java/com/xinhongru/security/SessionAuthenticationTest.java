package com.xinhongru.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.*;
import com.xinhongru.security.identity.*;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

import java.io.*;

class SessionAuthenticationTest {
    AccountDirectory accounts;
    SessionIdentityService sessions;
    AdminAuthenticationService admins;
    MemberAuthenticationService members;

    @BeforeEach
    void setup() {
        accounts = mock(AccountDirectory.class);
        sessions = new SessionIdentityService();
        admins = new AdminAuthenticationService(accounts, sessions);
        members = new MemberAuthenticationService(accounts);
        when(accounts.admin(20))
                .thenAnswer(
                        i ->
                                new UserEntity()
                                        .setId(20)
                                        .setUsername("operator")
                                        .setGroupId(3)
                                        .setActivated(0));
        when(accounts.group(3))
                .thenReturn(new UserGroupEntity().setId(3).setEnabled(true).setDeleted(false));
        when(accounts.member(42))
                .thenReturn(
                        new ContentEntity()
                                .setId(42)
                                .setClassid(183)
                                .setStatus(1)
                                .setTitle("13812345678"));
    }

    @Test
    void rotatesIdKeepsRealmsSeparateAndPersistsOnlySerializableIdentity() throws Exception {
        var request = new MockHttpServletRequest();
        String before = request.getSession().getId();
        sessions.loginAdmin(request, accounts.admin(20));
        assertThat(request.getSession().getId()).isNotEqualTo(before);
        Object principal = request.getSession().getAttribute(WebConst.LOGIN_SESSION_KEY);
        var bytes = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(bytes)) {
            stream.writeObject(principal);
        }
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(stream.readObject())
                    .isEqualTo(new SessionPrincipal(20, SessionPrincipal.Realm.ADMIN));
        }
        assertThatThrownBy(() -> members.requireAuthenticated(request))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        String adminId = request.getSession().getId();
        sessions.loginMember(request, 42);
        assertThat(request.getSession().getId()).isNotEqualTo(adminId);
        var next = new MockHttpServletRequest();
        next.setSession(request.getSession());
        assertThat(admins.requireAuthenticatedUser(next).getId()).isEqualTo(20);
        assertThat(members.requireAuthenticated(next).getId()).isEqualTo(42);
    }

    @Test
    void newRequestsObserveAccountAndGroupRevocation() {
        var request = new MockHttpServletRequest();
        sessions.loginAdmin(request, accounts.admin(20));
        var next = new MockHttpServletRequest();
        next.setSession(request.getSession());
        when(accounts.group(3)).thenReturn(new UserGroupEntity().setId(3).setEnabled(false));
        assertThatThrownBy(() -> admins.requireAuthenticated(next))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        assertThat(next.getSession().getAttribute(WebConst.LOGIN_SESSION_KEY)).isNull();
        sessions.loginMember(next, 42);
        when(accounts.member(42))
                .thenReturn(new ContentEntity().setId(42).setClassid(183).setStatus(0));
        assertThatThrownBy(() -> members.requireAuthenticated(next))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
    }

    @Test
    void logoutInvalidatesBothIdentitiesAndLegacyCookieCannotAuthenticate() {
        var request = new MockHttpServletRequest();
        sessions.loginAdmin(request, accounts.admin(20));
        sessions.loginMember(request, 42);
        var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        sessions.logout(request, response);
        assertThat(session.isInvalid()).isTrue();
        var next = new MockHttpServletRequest();
        next.setCookies(new Cookie(WebConst.USER_IN_COOKIE, "20"));
        assertThat(admins.authenticate(next)).isEmpty();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void csrfAllowsSameOriginAndConfiguredFrontendButRejectsMissingAndForeignOrigin() {
        var csrf =
                new SessionCsrfService(
                        new MockEnvironment()
                                .withProperty(
                                        "cms.cors.admin-allowed-origins",
                                        "https://admin.example.com"));
        var request = new MockHttpServletRequest("POST", "/member/logOut");
        request.setServerName("cms.example.com");
        request.setScheme("https");
        request.setServerPort(443);
        assertThatThrownBy(() -> csrf.requireValidWriteRequest(request))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        request.addHeader("Origin", "https://evil.example.com");
        assertThatThrownBy(() -> csrf.requireValidWriteRequest(request))
                .isInstanceOf(AdminSecurityExceptionHandler.AdminSecurityException.class);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://cms.example.com");
        csrf.requireValidWriteRequest(request);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://admin.example.com");
        csrf.requireValidWriteRequest(request);
    }
}

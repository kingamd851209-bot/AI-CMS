package com.xinhongru.security;

import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.member.MemberDto;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.security.identity.AccountDirectory;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.security.identity.SessionPrincipal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

@Service
public class MemberAuthenticationService {
    private final AccountDirectory accounts;

    public MemberAuthenticationService(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    public MemberDto requireAuthenticated(HttpServletRequest request) {
        Object cached = request.getAttribute(SessionIdentity.MEMBER_USER);
        if (cached instanceof MemberDto member) return member;
        var session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(WebConst.MEMBER_SESSION_KEY);
        if (!(value instanceof SessionPrincipal principal)
                || principal.realm() != SessionPrincipal.Realm.MEMBER) throw unauthorized();
        ContentEntity account = accounts.member(principal.id());
        if (account == null
                || !Integer.valueOf(183).equals(account.getClassid())
                || !Integer.valueOf(1).equals(account.getStatus())) {
            session.removeAttribute(WebConst.MEMBER_SESSION_KEY);
            throw unauthorized();
        }
        String name = account.getTitle();
        if (name != null && name.length() > 6)
            name = name.substring(0, 3) + "***" + name.substring(name.length() - 3);
        MemberDto member = new MemberDto(account.getId(), name);
        request.setAttribute(SessionIdentity.MEMBER_USER, member);
        return member;
    }

    private AdminSecurityExceptionHandler.AdminSecurityException unauthorized() {
        return AdminSecurityExceptionHandler.AdminSecurityException.unauthorized(
                "MEMBER_AUTHENTICATION_REQUIRED", "会员未登录或登录已过期");
    }
}

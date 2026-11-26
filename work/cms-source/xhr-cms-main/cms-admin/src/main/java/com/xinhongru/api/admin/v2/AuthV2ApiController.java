package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.AdminRoleAccessService;
import com.xinhongru.security.SessionIdentityService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v2/auth")
public class AuthV2ApiController {
    private final SessionIdentityService sessions;
    private final AdminAuthenticationService authentication;
    private final AdminRoleAccessService roles;

    public AuthV2ApiController(
            SessionIdentityService sessions,
            AdminAuthenticationService authentication,
            AdminRoleAccessService roles) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.roles = roles;
    }

    @GetMapping("/session")
    public AdminApiResponse<SessionStatus> session(
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        UserEntity user = authentication.requireAuthenticatedUser(request);
        return AdminApiResponse.success(
                new SessionStatus(user.getUsername(), user.getScreenName(), roles.pending(user)));
    }

    public record SessionStatus(
            String username, String displayName, boolean pendingAuthorization) {}

    @PostMapping("/logout")
    public AdminApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return AdminApiResponse.success();
    }
}

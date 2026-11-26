package com.xinhongru.security.preview;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 独立于管理员/会员身份的预发布密码门禁。 */
@Component
@EnableConfigurationProperties(PreviewAccessProperties.class)
public class PreviewAccessGuard {
    public static final String LOGIN_PATH = "/__preview/login";
    public static final int MAX_FORM_BYTES = 4096;
    private static final long ACCESS_SECONDS = 12 * 60 * 60;

    private final SitePreviewAccessProvider provider;
    private final boolean enabled;
    private final byte[] signingKey = new byte[32];

    public PreviewAccessGuard(
            PreviewAccessProperties properties, SitePreviewAccessProvider provider) {
        this.enabled = properties.isEnabled();
        this.provider = provider;
        new SecureRandom().nextBytes(signingKey);
    }

    public SitePreviewAccess forSiteId(Long siteId) {
        return enabled ? provider.forSiteId(siteId) : new SitePreviewAccess(siteId, false, null);
    }

    public SitePreviewAccess forSiteCode(String siteCode) {
        return enabled ? provider.forSiteCode(siteCode) : new SitePreviewAccess(null, false, null);
    }

    public boolean isLoginSubmission(String method, String requestTarget) {
        return isLoginSubmission(method, requestTarget, LOGIN_PATH);
    }

    public boolean isLoginSubmission(String method, String requestTarget, String loginPath) {
        return "POST".equals(method) && loginPath.equals(path(requestTarget));
    }

    /** 返回 null 表示允许进入业务处理；Cookie 仅用于预发布门禁，不建立账户身份。 */
    public PreviewAccessResponse check(
            SitePreviewAccess policy,
            String loginPath,
            String method,
            String requestTarget,
            String cookieHeader,
            int port,
            boolean secure,
            String contentType,
            byte[] formBody) {
        if (!enabled || !policy.enabled()) return null;
        if (isLoginSubmission(method, requestTarget, loginPath)) {
            if (formBody == null || formBody.length > MAX_FORM_BYTES) {
                return page(loginPath, 413, "/", "请求内容过长，请重新输入。");
            }
            if (contentType == null
                    || !"application/x-www-form-urlencoded"
                            .equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
                return page(loginPath, 415, "/", "请通过密码输入页面访问。");
            }
            Map<String, String> form;
            try {
                form = parseForm(formBody);
            } catch (IllegalArgumentException malformed) {
                return page(loginPath, 400, "/", "请求格式无效，请重新输入。");
            }
            String target = safeTarget(form.get("returnTo"));
            String supplied = form.get("password");
            if (supplied == null
                    || supplied.length() > 255
                    || policy.password() == null
                    || policy.password().isBlank()
                    || !MessageDigest.isEqual(digest(supplied), digest(policy.password()))) {
                return page(loginPath, 401, target, "密码不正确，请重试。");
            }
            String expiry = Long.toString(Instant.now().getEpochSecond() + ACCESS_SECONDS);
            String token = expiry + "." + signature(policy, port, expiry);
            String cookie =
                    cookieName(policy, port)
                            + "="
                            + token
                            + "; Path=/; Max-Age="
                            + ACCESS_SECONDS
                            + "; HttpOnly; SameSite=Lax"
                            + (secure ? "; Secure" : "");
            return new PreviewAccessResponse(
                    303,
                    Map.of("Location", target, "Set-Cookie", cookie, "Cache-Control", "no-store"),
                    "");
        }
        if (permits(policy, cookieHeader, port)) return null;
        String target =
                ("GET".equals(method) || "HEAD".equals(method)) ? safeTarget(requestTarget) : "/";
        return page(loginPath, 401, target, "");
    }

    private boolean permits(SitePreviewAccess policy, String cookieHeader, int port) {
        if (cookieHeader == null || cookieHeader.length() > 16384) return false;
        String token = null;
        for (String item : cookieHeader.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && cookieName(policy, port).equals(pair[0])) {
                if (token != null) return false;
                token = pair[1];
            }
        }
        if (token == null) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) return false;
        try {
            long expiry = Long.parseLong(parts[0]);
            long now = Instant.now().getEpochSecond();
            return expiry > now
                    && expiry <= now + ACCESS_SECONDS
                    && MessageDigest.isEqual(
                            signature(policy, port, parts[0]).getBytes(StandardCharsets.UTF_8),
                            parts[1].getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private String signature(SitePreviewAccess policy, int port, String expiry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    (policy.siteId()
                                                    + ":"
                                                    + policy.password()
                                                    + ":"
                                                    + port
                                                    + ":"
                                                    + expiry)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String cookieName(SitePreviewAccess policy, int port) {
        // Cookie 不区分端口；使用不同名称，防止独立进程互相覆盖通行凭据。
        return "CMS_PREVIEW_ACCESS_" + policy.siteId() + "_" + port;
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> form = new HashMap<>();
        for (String field : new String(body, StandardCharsets.UTF_8).split("&")) {
            String[] pair = field.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value =
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (form.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("Duplicate form field");
        }
        return form;
    }

    private static String path(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static String safeTarget(String target) {
        if (target == null || target.length() > 2048) return "/";
        try {
            URI uri = URI.create(target);
            String decoded = URLDecoder.decode(target, StandardCharsets.UTF_8);
            if (!target.startsWith("/")
                    || decoded.startsWith("//")
                    || uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || decoded.contains("\\")
                    || decoded.codePoints().anyMatch(Character::isISOControl)
                    || LOGIN_PATH.equals(path(target))) return "/";
            return target;
        } catch (IllegalArgumentException malformed) {
            return "/";
        }
    }

    private static PreviewAccessResponse page(
            String loginPath, int status, String target, String error) {
        String html =
                """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>网站访问验证</title>
                <style>
                *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f6f8;color:#222;font:16px system-ui,sans-serif;padding:24px}
                main{width:100%%;max-width:380px;background:#fff;padding:32px;border:1px solid #e5e7eb;border-radius:12px}
                h1{font-size:22px;margin:0 0 12px}p{color:#666;line-height:1.6}label{display:block;margin:24px 0 8px}
                input,button{width:100%%;padding:12px;font:inherit;border-radius:6px}input{border:1px solid #bbb}button{margin-top:16px;border:0;background:#222;color:white;cursor:pointer}.error{color:#b42318}
                </style></head><body><main><h1>网站访问验证</h1>
                <p>网站尚未正式上线，请输入访问密码。</p>
                <form method="post" action="%s">
                <input type="hidden" name="returnTo" value="%s">
                <label for="password">访问密码</label>
                <input id="password" name="password" type="password" autocomplete="current-password" required autofocus>
                <p class="error" role="alert">%s</p><button type="submit">进入网站</button>
                </form></main></body></html>
                """
                        .formatted(escape(loginPath), escape(target), escape(error));
        return new PreviewAccessResponse(
                status,
                Map.of(
                        "Cache-Control",
                        "no-store",
                        "Content-Type",
                        "text/html; charset=UTF-8",
                        "Referrer-Policy",
                        "no-referrer",
                        "X-Content-Type-Options",
                        "nosniff",
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self';"
                                + " base-uri 'none'"),
                html);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

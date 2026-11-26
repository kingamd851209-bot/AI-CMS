package com.xinhongru.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 请求来源工具：提供后台同源判断与访问 IP 提取。 */
public final class RequestOriginUtils {
    private static final String FORWARDED = "Forwarded";
    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String X_FORWARDED_HOST = "X-Forwarded-Host";
    private static final String X_FORWARDED_PORT = "X-Forwarded-Port";
    private static final String TRUSTED_PROXY_PROPERTY = "cms.trustedProxyAddresses";
    private static final String TRUSTED_PROXY_ENV = "CMS_TRUSTED_PROXY_ADDRESSES";

    private RequestOriginUtils() {}

    public static boolean isSameOrigin(HttpServletRequest request, String value) {
        if (request == null || value == null || value.isBlank()) {
            return false;
        }
        Origin requestOrigin = resolveRequestOrigin(request);
        Origin candidate = parseOrigin(value.trim());
        return requestOrigin != null && requestOrigin.matches(candidate);
    }

    private static Origin resolveRequestOrigin(HttpServletRequest request) {
        Origin forwardedOrigin = null;
        if (isTrustedProxyRequest(request)) {
            forwardedOrigin = parseForwardedHeader(request);
            if (forwardedOrigin == null) {
                forwardedOrigin = parseXForwardedHeaders(request);
            }
        }
        if (forwardedOrigin != null) {
            return forwardedOrigin;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        return Origin.of(scheme, host, port);
    }

    private static Origin parseOrigin(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            return Origin.of(scheme, host, uri.getPort());
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static Origin parseForwardedHeader(HttpServletRequest request) {
        HeaderValue header = singleHeaderValue(request, FORWARDED);
        if (!header.isUsable()) {
            return null;
        }
        String scheme = null;
        String host = null;
        for (String part : header.value().split(";")) {
            int index = part.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String name = part.substring(0, index).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(part.substring(index + 1).trim());
            if ("proto".equals(name)) {
                scheme = value;
            } else if ("host".equals(name)) {
                host = value;
            }
        }
        return originFromForwardedParts(scheme, host, null);
    }

    private static Origin parseXForwardedHeaders(HttpServletRequest request) {
        HeaderValue scheme = singleHeaderValue(request, X_FORWARDED_PROTO);
        HeaderValue host = singleHeaderValue(request, X_FORWARDED_HOST);
        HeaderValue port = singleHeaderValue(request, X_FORWARDED_PORT);
        if (!scheme.isUsable()
                || !host.isUsable()
                || port.isInvalid()
                || hasInvalidPortValue(port)) {
            return null;
        }
        return originFromForwardedParts(scheme.value(), host.value(), port.valueOrNull());
    }

    private static Origin originFromForwardedParts(
            String scheme, String hostValue, String portValue) {
        if (scheme == null || hostValue == null) {
            return null;
        }
        HostAndPort hostAndPort = parseHostAndPort(stripQuotes(hostValue.trim()));
        int port = parsePort(portValue, hostAndPort.port);
        return Origin.of(stripQuotes(scheme.trim()), hostAndPort.host, port);
    }

    private static HostAndPort parseHostAndPort(String value) {
        if (value == null || value.isBlank()) {
            return new HostAndPort(null, -1);
        }
        String host = value;
        int port = -1;
        if (value.startsWith("[") && value.contains("]")) {
            int end = value.indexOf(']');
            host = value.substring(1, end);
            if (value.length() > end + 2 && value.charAt(end + 1) == ':') {
                port = parsePort(value.substring(end + 2), -1);
            }
        } else {
            int separator = value.lastIndexOf(':');
            if (separator > 0 && value.indexOf(':') == separator) {
                host = value.substring(0, separator);
                port = parsePort(value.substring(separator + 1), -1);
            }
        }
        return new HostAndPort(host, port);
    }

    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(stripQuotes(value.trim()));
            return port > 0 ? port : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean hasInvalidPortValue(HeaderValue port) {
        if (!port.isUsable()) {
            return false;
        }
        try {
            return Integer.parseInt(stripQuotes(port.value().trim())) <= 0;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private static HeaderValue singleHeaderValue(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty()) {
            return HeaderValue.missing();
        }
        if (values.size() != 1) {
            return HeaderValue.invalid();
        }
        return singleHeaderValue(values.getFirst());
    }

    private static HeaderValue singleHeaderValue(String header) {
        if (header == null || header.isBlank()) {
            return HeaderValue.invalid();
        }
        String value = header.trim();
        if (value.indexOf(',') >= 0) {
            return HeaderValue.invalid();
        }
        return value.isEmpty() ? HeaderValue.invalid() : HeaderValue.of(value);
    }

    private static String stripQuotes(String value) {
        if (value != null
                && value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 访问日志使用 X-Real-IP，其次 X-Forwarded-For，缺失时回退到连接地址。 */
    public static String clientIp(HttpServletRequest request) {
        return clientIp(
                request.getRemoteAddr(),
                Collections.list(request.getHeaders("X-Real-IP")),
                Collections.list(request.getHeaders("X-Forwarded-For")));
    }

    /** 安全限流使用可信代理转发的地址，或直连地址。 */
    public static String trustedClientIp(HttpServletRequest request) {
        return isTrustedProxyRequest(request) ? clientIp(request) : request.getRemoteAddr();
    }

    /** 独立站点 HTTP 服务沿用同一规则；多跳转发头取第一个地址。 */
    public static String clientIp(String remote, List<String> realIp, List<String> forwardedFor) {
        String ip = firstClientIp(realIp);
        if (ip == null) {
            ip = firstClientIp(forwardedFor);
        }
        return ip == null ? remote : ip;
    }

    private static String firstClientIp(List<String> values) {
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String ip = value.split(",", 2)[0].trim();
                if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) return ip;
            }
        }
        return null;
    }

    private static boolean isTrustedProxyRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress()
                    || configuredTrustedProxyAddresses().contains(address.getHostAddress());
        } catch (Exception ex) {
            return false;
        }
    }

    private static Set<String> configuredTrustedProxyAddresses() {
        String configured = System.getProperty(TRUSTED_PROXY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(TRUSTED_PROXY_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(RequestOriginUtils::resolveAddress)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
    }

    private static String resolveAddress(String value) {
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception ex) {
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private record HostAndPort(String host, int port) {}

    private record HeaderValue(boolean present, boolean valid, String value) {
        static HeaderValue missing() {
            return new HeaderValue(false, true, null);
        }

        static HeaderValue invalid() {
            return new HeaderValue(true, false, null);
        }

        static HeaderValue of(String value) {
            return new HeaderValue(true, true, value);
        }

        boolean isUsable() {
            return present && valid;
        }

        boolean isInvalid() {
            return present && !valid;
        }

        String valueOrNull() {
            return isUsable() ? value : null;
        }
    }

    private record Origin(String scheme, String host, int port) {
        static Origin of(String scheme, String host, int port) {
            if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
                return null;
            }
            int normalizedPort = port > 0 ? port : defaultPort(scheme);
            return new Origin(
                    scheme.toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT), normalizedPort);
        }

        boolean matches(Origin other) {
            return other != null
                    && scheme.equals(other.scheme)
                    && host.equals(other.host)
                    && port == other.port;
        }
    }
}

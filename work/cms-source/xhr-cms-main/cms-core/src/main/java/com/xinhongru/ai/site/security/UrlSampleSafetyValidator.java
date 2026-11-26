package com.xinhongru.ai.site.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class UrlSampleSafetyValidator {
    public static final int DEFAULT_MAX_URL_LENGTH = 2048;

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern DECIMAL_LABEL = Pattern.compile("\\d+");
    private static final Pattern HEX_LABEL = Pattern.compile("0[xX][0-9a-fA-F]+");

    private final HostAddressResolver resolver;
    private final int maxUrlLength;

    public UrlSampleSafetyValidator() {
        this(HostAddressResolver.system(), DEFAULT_MAX_URL_LENGTH);
    }

    @Autowired
    public UrlSampleSafetyValidator(ObjectProvider<HostAddressResolver> resolverProvider) {
        this(resolverProvider.getIfAvailable(HostAddressResolver::system), DEFAULT_MAX_URL_LENGTH);
    }

    public UrlSampleSafetyValidator(HostAddressResolver resolver) {
        this(resolver, DEFAULT_MAX_URL_LENGTH);
    }

    public UrlSampleSafetyValidator(HostAddressResolver resolver, int maxUrlLength) {
        this.resolver = resolver;
        this.maxUrlLength = maxUrlLength;
    }

    public UrlSampleValidationResult validate(String url) {
        List<UrlSampleViolation> violations = new ArrayList<>();
        if (url == null || url.isBlank()) {
            violations.add(new UrlSampleViolation("INVALID_URL", null, "URL is empty"));
            return UrlSampleValidationResult.rejected(null, null, violations);
        }
        if (url.length() > maxUrlLength) {
            violations.add(
                    new UrlSampleViolation(
                            "URL_TOO_LONG", null, "URL exceeds " + maxUrlLength + " characters"));
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            violations.add(new UrlSampleViolation("INVALID_URL", url, "URL syntax is invalid"));
            return UrlSampleValidationResult.rejected(null, null, violations);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            violations.add(
                    new UrlSampleViolation(
                            "UNSUPPORTED_SCHEME", scheme, "Only http and https URLs are allowed"));
        }
        if (uri.getRawUserInfo() != null) {
            violations.add(
                    new UrlSampleViolation(
                            "USERINFO_NOT_ALLOWED",
                            uri.getRawUserInfo(),
                            "URL userinfo is not allowed"));
        }

        String host = uri.getHost();
        String hostCandidate = host == null ? hostFromRawAuthority(uri.getRawAuthority()) : host;
        if (hostCandidate != null && isAmbiguousIpv4NumericHost(hostCandidate)) {
            violations.add(
                    new UrlSampleViolation(
                            "AMBIGUOUS_IPV4_LITERAL",
                            hostCandidate,
                            "ambiguous IPv4 numeric host is not allowed"));
        }
        if (host == null || host.isBlank()) {
            violations.add(new UrlSampleViolation("MISSING_HOST", null, "URL host is required"));
            return UrlSampleValidationResult.rejected(null, null, violations);
        }

        String normalizedHost = normalizeHost(host);
        String normalizedUrl = normalizeUrl(uri, scheme, normalizedHost);
        if (isLocalhost(normalizedHost)) {
            violations.add(
                    new UrlSampleViolation(
                            "LOCALHOST_NOT_ALLOWED", normalizedHost, "localhost is not allowed"));
        }

        InetAddress literalAddress = literalAddress(normalizedHost);
        if (literalAddress != null) {
            if (isMetadataAddress(literalAddress)) {
                violations.add(
                        new UrlSampleViolation(
                                "METADATA_ADDRESS_NOT_ALLOWED",
                                literalAddress.getHostAddress(),
                                "cloud metadata address is not allowed"));
            } else if (isBlockedAddress(literalAddress)) {
                violations.add(
                        new UrlSampleViolation(
                                "BLOCKED_ADDRESS",
                                literalAddress.getHostAddress(),
                                "URL host address is not allowed"));
            }
        } else if (violations.isEmpty()) {
            validateResolvedAddresses(normalizedHost, violations);
        }

        if (violations.isEmpty()) {
            return UrlSampleValidationResult.accepted(normalizedUrl, normalizedHost);
        }
        return UrlSampleValidationResult.rejected(normalizedUrl, normalizedHost, violations);
    }

    private void validateResolvedAddresses(String host, List<UrlSampleViolation> violations) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            violations.add(
                    new UrlSampleViolation(
                            "DNS_RESOLUTION_FAILED", host, "URL host could not be resolved"));
            return;
        }
        if (addresses.isEmpty()) {
            violations.add(
                    new UrlSampleViolation(
                            "DNS_RESOLUTION_FAILED", host, "URL host resolved no addresses"));
            return;
        }
        for (InetAddress address : addresses) {
            if (isMetadataAddress(address)) {
                violations.add(
                        new UrlSampleViolation(
                                "METADATA_ADDRESS_NOT_ALLOWED",
                                address.getHostAddress(),
                                "URL host resolves to a cloud metadata address"));
            } else if (isBlockedAddress(address)) {
                violations.add(
                        new UrlSampleViolation(
                                "BLOCKED_RESOLVED_ADDRESS",
                                address.getHostAddress(),
                                "URL host resolves to a blocked address"));
            }
        }
    }

    private static String normalizeUrl(URI uri, String scheme, String normalizedHost) {
        if (scheme == null) {
            return uri.toString();
        }
        StringBuilder normalized =
                new StringBuilder()
                        .append(scheme.toLowerCase(Locale.ROOT))
                        .append("://")
                        .append(formatHostForAuthority(normalizedHost));
        if (uri.getPort() >= 0) {
            normalized.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            normalized.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            normalized.append('#').append(uri.getRawFragment());
        }
        return normalized.toString();
    }

    private static String formatHostForAuthority(String host) {
        if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
            return "[" + host + "]";
        }
        return host;
    }

    private static String normalizeHost(String host) {
        return stripTrailingDot(host).toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static String hostFromRawAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        int at = authority.lastIndexOf('@');
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostPort.startsWith("[")) {
            int closingBracket = hostPort.indexOf(']');
            return closingBracket > 0 ? hostPort.substring(1, closingBracket) : hostPort;
        }
        int colon = hostPort.indexOf(':');
        return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    }

    private static boolean isAmbiguousIpv4NumericHost(String host) {
        String candidate = stripTrailingDot(host).toLowerCase(Locale.ROOT);
        if (candidate.isBlank() || candidate.contains(":")) {
            return false;
        }
        String[] labels = candidate.split("\\.", -1);
        if (labels.length == 0 || labels.length > 4) {
            return false;
        }
        boolean allDecimal = true;
        boolean allNumericForms = true;
        for (String label : labels) {
            if (label.isBlank()) {
                return false;
            }
            boolean decimal = DECIMAL_LABEL.matcher(label).matches();
            boolean hex = HEX_LABEL.matcher(label).matches();
            allDecimal &= decimal;
            allNumericForms &= decimal || hex;
            if (hex) {
                return true;
            }
        }
        if (!allNumericForms) {
            return false;
        }
        if (labels.length != 4) {
            return true;
        }
        if (!allDecimal) {
            return true;
        }
        for (String label : labels) {
            if (label.length() > 1 && label.startsWith("0")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalhost(String host) {
        return "localhost".equals(host);
    }

    private static InetAddress literalAddress(String host) {
        if (!IPV4_LITERAL.matcher(host).matches() && !host.contains(":")) {
            return null;
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || isBlockedIpv4(address)
                || isBlockedIpv6(address);
    }

    private static boolean isMetadataAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        int fourth = Byte.toUnsignedInt(bytes[3]);
        return (first == 169 && second == 254 && third == 169 && fourth == 254)
                || (first == 100 && second == 100 && third == 100 && fourth == 200);
    }

    private static boolean isBlockedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 0
                || first == 10
                || (first == 100 && second >= 64 && second <= 127)
                || first == 127
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && Byte.toUnsignedInt(bytes[2]) == 0)
                || (first == 192 && second == 0 && Byte.toUnsignedInt(bytes[2]) == 2)
                || (first == 192 && second == 88 && Byte.toUnsignedInt(bytes[2]) == 99)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && Byte.toUnsignedInt(bytes[2]) == 100)
                || (first == 203 && second == 0 && Byte.toUnsignedInt(bytes[2]) == 113)
                || first >= 224;
    }

    private static boolean isBlockedIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return (first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80)
                || hasBlockedEmbeddedIpv4(bytes);
    }

    private static boolean hasBlockedEmbeddedIpv4(byte[] bytes) {
        if (!hasIpv4CompatibleOrMappedPrefix(bytes) && !hasNat64WellKnownPrefix(bytes)) {
            return false;
        }
        Inet4Address embeddedAddress = embeddedIpv4Address(bytes);
        return isMetadataAddress(embeddedAddress) || isBlockedIpv4(embeddedAddress);
    }

    private static boolean hasIpv4CompatibleOrMappedPrefix(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] == 0 && bytes[11] == 0)
                || (Byte.toUnsignedInt(bytes[10]) == 0xff && Byte.toUnsignedInt(bytes[11]) == 0xff);
    }

    private static boolean hasNat64WellKnownPrefix(byte[] bytes) {
        return Byte.toUnsignedInt(bytes[0]) == 0x00
                && Byte.toUnsignedInt(bytes[1]) == 0x64
                && Byte.toUnsignedInt(bytes[2]) == 0xff
                && Byte.toUnsignedInt(bytes[3]) == 0x9b
                && bytes[4] == 0
                && bytes[5] == 0
                && bytes[6] == 0
                && bytes[7] == 0
                && bytes[8] == 0
                && bytes[9] == 0
                && bytes[10] == 0
                && bytes[11] == 0;
    }

    private static Inet4Address embeddedIpv4Address(byte[] bytes) {
        byte[] embedded = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        try {
            return (Inet4Address) InetAddress.getByAddress(embedded);
        } catch (UnknownHostException ex) {
            throw new IllegalStateException("Unexpected embedded IPv4 address length", ex);
        }
    }
}

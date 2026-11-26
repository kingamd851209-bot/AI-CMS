package com.xinhongru.ai.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class AiHttpClientFactory {
    private AiHttpClientFactory() {}

    public static HttpClient newHttpClient() {
        return newBuilder(System.getenv()).build();
    }

    public static java.net.http.HttpResponse.BodyHandler<String> limitedUtf8Body(int maxBytes) {
        return info -> {
            var delegate =
                    java.net.http.HttpResponse.BodySubscribers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8);
            return new java.net.http.HttpResponse.BodySubscriber<String>() {
                private java.util.concurrent.Flow.Subscription subscription;
                private int size;

                public java.util.concurrent.CompletionStage<String> getBody() {
                    return delegate.getBody();
                }

                public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
                    subscription = value;
                    delegate.onSubscribe(value);
                }

                public void onNext(List<java.nio.ByteBuffer> buffers) {
                    for (var buffer : buffers) {
                        size += buffer.remaining();
                        if (size > maxBytes) {
                            subscription.cancel();
                            delegate.onError(new IOException("AI连接测试响应超限"));
                            return;
                        }
                    }
                    delegate.onNext(buffers);
                }

                public void onError(Throwable error) {
                    delegate.onError(error);
                }

                public void onComplete() {
                    delegate.onComplete();
                }
            };
        };
    }

    static HttpClient.Builder newBuilder(Map<String, String> environment) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(java.time.Duration.ofSeconds(15));
        environmentProxySelector(environment).ifPresent(builder::proxy);
        return builder;
    }

    static Optional<ProxySelector> environmentProxySelector(Map<String, String> environment) {
        ProxySelector selector =
                new EnvironmentProxySelector(environment == null ? Map.of() : environment);
        return ((EnvironmentProxySelector) selector).hasProxy()
                ? Optional.of(selector)
                : Optional.empty();
    }

    private static final class EnvironmentProxySelector extends ProxySelector {
        private final Proxy httpProxy;
        private final Proxy httpsProxy;
        private final Proxy allProxy;
        private final List<String> noProxyRules;

        private EnvironmentProxySelector(Map<String, String> environment) {
            this.httpProxy = parseProxy(firstNonBlank(environment, "HTTP_PROXY", "http_proxy"));
            this.httpsProxy = parseProxy(firstNonBlank(environment, "HTTPS_PROXY", "https_proxy"));
            this.allProxy = parseProxy(firstNonBlank(environment, "ALL_PROXY", "all_proxy"));
            this.noProxyRules = parseNoProxy(firstNonBlank(environment, "NO_PROXY", "no_proxy"));
        }

        private boolean hasProxy() {
            return httpProxy != null || httpsProxy != null || allProxy != null;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null || noProxy(uri.getHost())) {
                return List.of(Proxy.NO_PROXY);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            Proxy proxy =
                    "https".equals(scheme)
                            ? firstProxy(httpsProxy, httpProxy, allProxy)
                            : "http".equals(scheme) ? firstProxy(httpProxy, allProxy) : null;
            return proxy == null ? List.of(Proxy.NO_PROXY) : List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // The caller receives the original HTTP failure; no side channel is needed here.
        }

        private boolean noProxy(String host) {
            if (host == null || host.isBlank() || noProxyRules.isEmpty()) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            for (String rule : noProxyRules) {
                if ("*".equals(rule)) {
                    return true;
                }
                if (normalizedHost.equals(rule)
                        || normalizedHost.endsWith("." + stripLeadingDot(rule))) {
                    return true;
                }
            }
            return false;
        }

        private String stripLeadingDot(String value) {
            return value.startsWith(".") ? value.substring(1) : value;
        }

        private Proxy firstProxy(Proxy... proxies) {
            for (Proxy proxy : proxies) {
                if (proxy != null) {
                    return proxy;
                }
            }
            return null;
        }

        private Proxy parseProxy(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                URI uri = new URI(value.contains("://") ? value.trim() : "http://" + value.trim());
                String scheme =
                        uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return null;
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    return null;
                }
                int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
                return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
            } catch (Exception ex) {
                return null;
            }
        }

        private List<String> parseNoProxy(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.of(value.split(",")).stream()
                    .map(String::trim)
                    .filter(rule -> !rule.isBlank())
                    .map(rule -> rule.toLowerCase(Locale.ROOT))
                    .toList();
        }
    }

    private static String firstNonBlank(Map<String, String> environment, String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

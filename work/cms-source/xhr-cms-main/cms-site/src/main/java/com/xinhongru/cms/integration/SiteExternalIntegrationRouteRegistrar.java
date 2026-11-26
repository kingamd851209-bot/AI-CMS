package com.xinhongru.cms.integration;

import com.xinhongru.cms.siteservice.SiteRouteRegistry;

import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

@Component
public class SiteExternalIntegrationRouteRegistrar {
    private final SiteRouteRegistry routes;
    private final SiteExternalIntegrationOutput output;

    public SiteExternalIntegrationRouteRegistrar(
            SiteRouteRegistry routes, SiteExternalIntegrationOutput output) {
        this.routes = routes;
        this.output = output;
    }

    @PostConstruct
    public void register() {
        routes.registerGlobal(
                SiteExternalIntegrationOutput.RUNTIME_PATH,
                (path, exchange, ctx) -> {
                    if (!exchange.getRequestMethod().equals(Methods.GET)) {
                        exchange.setStatusCode(405);
                        exchange.endExchange();
                        return true;
                    }
                    exchange.getResponseHeaders()
                            .put(Headers.CONTENT_TYPE, "application/javascript;charset=UTF-8");
                    exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
                    exchange.getResponseHeaders()
                            .put(new HttpString("X-Content-Type-Options"), "nosniff");
                    exchange.getResponseSender().send(output.runtime(ctx.siteId()));
                    return true;
                });
    }
}

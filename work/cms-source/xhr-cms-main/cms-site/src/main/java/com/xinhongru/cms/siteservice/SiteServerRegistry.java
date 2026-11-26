package com.xinhongru.cms.siteservice;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared live server registry; it has no dependency on template rendering or lifecycle
 * orchestration.
 */
@Component
public class SiteServerRegistry {
    private final Map<Long, SiteHttpServer> servers = new ConcurrentHashMap<>();

    Map<Long, SiteHttpServer> servers() {
        return servers;
    }

    public boolean hasPublicDetailRoute(
            Long siteId, String code, com.xinhongru.entity.OneshotEntity menu, int recordId, String path) {
        var server = servers.get(siteId);
        return server != null && server.hasPublicDetailRoute(code, menu, recordId, path);
    }

    public SiteServiceStatus getStatus(Long siteId) {
        SiteHttpServer server = servers.get(siteId);
        return server != null && server.isRunning()
                ? new SiteServiceStatus(
                        SiteServiceStatus.STATUS_RUNNING,
                        server.getPort(),
                        server.getAccessUrl(),
                        null)
                : new SiteServiceStatus(SiteServiceStatus.STATUS_STOPPED, null, null, null);
    }
}

package com.xinhongru.cms.siteservice;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.search.PublicSearchRouteAccess;

import org.springframework.stereotype.Component;

/** 搜索专用的小端口，复用当前站点服务与实际路由配置。 */
@Component
public class SitePublicSearchRouteAccess implements PublicSearchRouteAccess {
    private final SiteServerRegistry registry;

    public SitePublicSearchRouteAccess(SiteServerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isPublicRoute(Long siteId, String code, OneshotEntity menu) {
        if (siteId == null) return false;
        SiteHttpServer server = registry.servers().get(siteId);
        return server != null && server.hasPublicSearchRoute(code, menu);
    }
}

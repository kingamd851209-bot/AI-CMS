package com.xinhongru.cms.siteservice;

import com.xinhongru.cms.template.TemplatePreviewService;
import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.preview.PreviewAccessGuard;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 独立进程（standalone）站点服务启动器。
 *
 * <p>由管理节点拉起独立子 JVM 时启用：不开放管理端口（--server.port=-1）， 只启动指定站点的站点服务端口，并开放带控制令牌的内部路由接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandaloneSiteRunner {
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSearchRoutes publicSearchRoutes;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.PublicBindingRootRoutes publicRootRoutes;

    private final SiteProjectMapper projectMapper;
    private final UiPackageStorageService storageService;
    private final TemplatePreviewService previewService;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;
    private final TemplateStorageService templateStorageService;
    private final SiteHttpServerManager siteHttpServerManager;
    private final SiteRouteRegistry routeRegistry;
    private final Environment environment;
    private final com.xinhongru.cms.integration.SiteExternalIntegrationOutput
            externalIntegrationOutput;
    private final PreviewAccessGuard previewAccessGuard;

    @Value("${cms.site-service.standalone.enabled:false}")
    private boolean enabled;

    @Value("${cms.site-service.standalone.site-id:0}")
    private long siteId;

    @Value("${cms.site-service.standalone.port:0}")
    private int port;

    @Value("${cms.site-service.standalone.control-token:}")
    private String controlToken;

    private SiteHttpServer server;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        if (siteId <= 0 || !ServicePortRange.isValid(port)) {
            log.error("独立进程参数无效: siteId={}, port={}", siteId, port);
            return;
        }

        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            log.error("独立进程站点不存在: siteId={}", siteId);
            return;
        }

        if (storageService.getActivePath(site.getProjectCode()).isEmpty()) {
            storageService.activate(site.getProjectCode(), site.getUiPackageVersion());
        }

        server =
                new SiteHttpServer(
                        siteId,
                        site.getProjectCode(),
                        port,
                        site.getSiteDomain(),
                        storageService,
                        previewService,
                        oneShotMapper,
                        contentMapper,
                        templateStorageService,
                        controlToken,
                        routeRegistry,
                        environment.acceptsProfiles(Profiles.of("dev")),
                        previewAccessGuard,
                        environment.getProperty("cms.site-service.bind-host", "0.0.0.0"),
                        siteHttpServerManager.publishedOutputPath(site.getProjectCode()));
        server.setExternalIntegrationOutput(externalIntegrationOutput);
        server.setPublicSearchRoutes(publicSearchRoutes);
        server.setPublicRootRoutes(publicRootRoutes);
        server.start();
        siteHttpServerManager.registerStandaloneServer(siteId, server);
        log.info("独立进程站点服务已启动: siteId={}, port={}", siteId, port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}

package com.xinhongru.cms.siteservice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinhongru.resource.ResourceAccessProvider;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.stereotype.Component;

@Component
public class SiteResourceAccessProvider implements ResourceAccessProvider {
    private final SiteServerRegistry servers;
    private final SiteProjectMapper projects;
    private final StandaloneSiteProcessManager processes;
    private final SiteServiceModeConfig mode;

    public SiteResourceAccessProvider(
            SiteServerRegistry servers,
            SiteProjectMapper projects,
            StandaloneSiteProcessManager processes,
            SiteServiceModeConfig mode) {
        this.servers = servers;
        this.projects = projects;
        this.processes = processes;
        this.mode = mode;
    }

    @Override
    public String accessUrl(String code) {
        var site =
                projects.selectOne(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getProjectCode, code));
        if (site == null) return null;
        String local = servers.getStatus(site.getId()).accessUrl();
        if (local != null) return local;
        return mode.isIndependent() ? processes.getStatus(site).accessUrl() : null;
    }
}

package com.xinhongru.site.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinhongru.security.preview.SitePreviewAccess;
import com.xinhongru.security.preview.SitePreviewAccessProvider;
import com.xinhongru.site.entity.SiteProjectEntity;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseSitePreviewAccessProvider implements SitePreviewAccessProvider {
    private final SiteProjectMapper mapper;

    public SitePreviewAccess forSiteId(Long siteId) {
        return policy(
                mapper.selectOne(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .select(
                                        SiteProjectEntity::getId,
                                        SiteProjectEntity::getPreviewAccessEnabled,
                                        SiteProjectEntity::getPreviewAccessPassword)
                                .eq(SiteProjectEntity::getId, siteId)));
    }

    public SitePreviewAccess forSiteCode(String siteCode) {
        return policy(
                mapper.selectOne(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .select(
                                        SiteProjectEntity::getId,
                                        SiteProjectEntity::getPreviewAccessEnabled,
                                        SiteProjectEntity::getPreviewAccessPassword)
                                .eq(SiteProjectEntity::getProjectCode, siteCode)));
    }

    private SitePreviewAccess policy(SiteProjectEntity site) {
        if (site == null) throw new IllegalStateException("站点不存在，无法读取访问认证设置");
        return new SitePreviewAccess(
                site.getId(),
                Boolean.TRUE.equals(site.getPreviewAccessEnabled()),
                site.getPreviewAccessPassword());
    }
}

package com.xinhongru.site.context;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CmsResourceContextService {
    private final SiteProjectMapper projectMapper;
    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;

    public CmsResourceContextService(
            SiteProjectMapper projectMapper,
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper) {
        this.projectMapper = projectMapper;
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
    }

    public CmsResourceContext requireMenu(Long siteId, Integer menuId) {
        requireSiteId(siteId);
        requireMenuId(menuId);
        OneshotEntity menu = oneShotMapper.selectActiveMenuBySite(siteId, menuId);
        if (menu == null) {
            throw new SiteAccessDeniedException("栏目不属于当前站点");
        }
        return new CmsResourceContext(siteId, menu, null, null);
    }

    public CmsResourceContext requireContent(Long siteId, Integer menuId, Integer contentId) {
        requireSiteId(siteId);
        requireMenuId(menuId);
        if (contentId == null || contentId <= 0) {
            throw new IllegalArgumentException("内容 ID 无效");
        }
        OneshotEntity menu = oneShotMapper.selectActiveMenuBySite(siteId, menuId);
        if (menu == null) {
            throw new SiteAccessDeniedException("栏目不属于当前站点");
        }
        ContentEntity content =
                contentMapper.selectActiveArticleInSiteMenu(siteId, menuId, contentId);
        if (content == null) {
            throw new SiteAccessDeniedException("内容不属于当前站点栏目");
        }
        return new CmsResourceContext(siteId, menu, content, null);
    }

    public SiteProjectEntity requireSiteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("站点域名不能为空");
        }
        SiteProjectEntity site =
                projectMapper.selectOne(
                        new QueryWrapper<SiteProjectEntity>()
                                .eq("active_site_domain", normalized)
                                .eq("deleted", 0)
                                .last("LIMIT 1"));
        if (site == null) {
            throw new SiteAccessDeniedException("域名未绑定站点");
        }
        return site;
    }

    private void requireSiteId(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new IllegalArgumentException("站点 ID 无效");
        }
    }

    private void requireMenuId(Integer menuId) {
        if (menuId == null || menuId <= 0) {
            throw new IllegalArgumentException("栏目 ID 无效");
        }
    }
}

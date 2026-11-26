package com.xinhongru.cms.search;

import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

@Service
public class PublicSearchService {
    public record Hit(
            String kind,
            Integer id,
            Integer menuId,
            String title,
            String url,
            List<PublicSearchText.Part> snippet,
            java.time.LocalDateTime createTime) {
        public Hit(
                String kind,
                Integer id,
                Integer menuId,
                String title,
                String url,
                List<PublicSearchText.Part> snippet) {
            this(kind, id, menuId, title, url, snippet, null);
        }
    }

    private final PublicSearchMapper mapper;
    private final SiteProjectMapper sites;
    private final TemplateStorageService templates;
    private final ContentLinkService links;
    private final com.xinhongru.site.search.PublicSearchRouteAccess routeAccess;

    public PublicSearchService(
            PublicSearchMapper mapper,
            SiteProjectMapper sites,
            TemplateStorageService templates,
            ContentLinkService links,
            com.xinhongru.site.search.PublicSearchRouteAccess routeAccess) {
        this.mapper = mapper;
        this.sites = sites;
        this.templates = templates;
        this.links = links;
        this.routeAccess = routeAccess;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public com.xinhongru.common.page.PageResult<Hit> search(
            PublicSearchQuery query, Integer menuId, boolean contentOnly) {
        Objects.requireNonNull(query, "搜索参数");
        var site = sites.selectById(query.siteId());
        if (site == null || Integer.valueOf(1).equals(site.getDeleted()))
            throw new IllegalArgumentException("搜索站点不存在");
        var root = templates.resolveTemplateDir(site.getProjectCode());
        var eligible = new LinkedHashMap<Integer, OneshotEntity>();
        for (var menu : mapper.menus(query.siteId())) {
            if (menuId != null && !Objects.equals(menuId, menu.getId())) continue;
            if (!Objects.equals(menu.getSiteProjectId(), query.siteId())
                    || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                    || Boolean.TRUE.equals(menu.getIsDeleted())) continue;
            boolean single = Integer.valueOf(0).equals(menu.getPageType());
            if (single && (contentOnly || !Integer.valueOf(3).equals(menu.getStatus()))) continue;
            if (!single && !Integer.valueOf(1).equals(menu.getPageType())) continue;
            String path = single ? menu.getListPageUrl() : menu.getDetailPageUrl();
            if (!safePath(path) || (!single && !path.contains("{id}"))) continue;
            if (!templateExists(
                    root, single ? menu.getListPageTemplate() : menu.getDetailPageTemplate()))
                continue;
            if (!routeAccess.isPublicRoute(query.siteId(), site.getProjectCode(), menu)) continue;
            eligible.put(menu.getId(), menu);
        }
        if (eligible.isEmpty())
            return new com.xinhongru.common.page.PageResult<>(
                    List.of(), 0, query.page(), query.size());
        var ids = List.copyOf(eligible.keySet());
        var pageResult =
                com.xinhongru.persistence.Pages.<PublicSearchRow>query(
                        query.page(),
                        query.size(),
                        page -> {
                            page.setSearchCount(false);
                            page.setTotal(mapper.count(query, ids, contentOnly));
                            return mapper.page(page, query, ids, contentOnly);
                        });
        var hits = new ArrayList<Hit>();
        for (var row : pageResult.list()) {
            var menu = eligible.get(row.getMenuId());
            if (menu == null) throw new IllegalStateException("搜索候选越出授权栏目");
            boolean single = "singlePage".equals(row.getKind());
            var content = new ContentEntity().setId(row.getRecordId()).setClassid(row.getMenuId());
            String url = single ? links.getListUrl(menu) : links.getDetailUrl(content, menu);
            if (!safePath(url)) throw new IllegalStateException("搜索链接不符合本站路由");
            if (!url.startsWith("/")) url = "/" + url;
            hits.add(
                    new Hit(
                            row.getKind(),
                            row.getRecordId(),
                            row.getMenuId(),
                            row.getTitle(),
                            url,
                            PublicSearchText.snippet(row.getSearchText(), query.keyword(), 240),
                            row.getCreateTime()));
        }
        return new com.xinhongru.common.page.PageResult<>(
                hits, pageResult.total(), query.page(), query.size());
    }

    private boolean templateExists(Path root, String name) {
        if (name == null || name.isBlank()) return false;
        try {
            var file = root.resolve(name).normalize();
            return file.startsWith(root)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.toRealPath().startsWith(root.toRealPath());
        } catch (Exception invalid) {
            return false;
        }
    }

    public static boolean safePath(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("//")
                || value.contains("\\")
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        String expanded = value.replaceAll("\\{(?:id|classId|parentId|pageIndex)\\}", "1");
        try {
            var uri = new URI(expanded);
            String path = uri.getPath();
            return uri.getScheme() == null
                    && uri.getRawAuthority() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && path != null
                    && !path.isBlank()
                    && !Arrays.asList(path.split("/")).contains("..");
        } catch (Exception invalid) {
            return false;
        }
    }
}

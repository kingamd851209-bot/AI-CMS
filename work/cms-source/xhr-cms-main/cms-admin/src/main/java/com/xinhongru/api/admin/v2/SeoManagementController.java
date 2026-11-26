package com.xinhongru.api.admin.v2;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.dto.OneshotDto;
import com.xinhongru.entity.*;
import com.xinhongru.mapper.*;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContext;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/v2/seo")
public class SeoManagementController {
    @Autowired private SeoRobotsMapper seoRobotsMapper;
    @Autowired private SeoSitemapMapper seoSitemapMapper;
    @Autowired private SeoHreflangMapper seoHreflangMapper;
    @Autowired private SeoGeoMapper seoGeoMapper;
    @Autowired private SeoMetaMapper seoMetaMapper;
    @Autowired private SiteContextService siteContextService;
    @Autowired private SiteProjectMapper aiSiteProjectMapper;
    @Autowired private OneShotService oneShotService;
    @Autowired private ContentMapper contentMapper;

    // ==================== 公共接口：栏目和内容 ====================
    @GetMapping("/menus")
    public AdminApiResponse<List<MenuOption>> listMenus(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<OneshotEntity> menus =
                oneShotService.getAllMenuForDropdownList(new OneshotDto(0), request);
        List<MenuOption> result = new ArrayList<>();
        if (menus != null) {
            for (OneshotEntity m : menus) {
                // 过滤掉系统栏目
                if (!Boolean.TRUE.equals(m.getIsSystem())) {
                    result.add(new MenuOption(m.getId(), m.getClassName(), m.getPageType()));
                }
            }
        }
        return AdminApiResponse.success(result);
    }

    @GetMapping("/contents")
    public AdminApiResponse<List<ContentOption>> listContents(
            HttpServletRequest request, @RequestParam Integer menuId) {
        List<ContentEntity> contents = contentMapper.selectActiveContentsByClassId(menuId);
        List<ContentOption> result = new ArrayList<>();
        if (contents != null) {
            for (ContentEntity c : contents) {
                result.add(new ContentOption(c.getId(), c.getTitle()));
            }
        }
        return AdminApiResponse.success(result);
    }

    // ==================== Robots 管理 ====================
    @GetMapping("/robots")
    public AdminApiResponse<List<RobotsListItem>> listRobots(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<SeoRobotsEntity> list;
        if (siteId != null) {
            list = seoRobotsMapper.selectBySiteId(siteId);
        } else {
            list =
                    seoRobotsMapper.selectList(
                            new QueryWrapper<SeoRobotsEntity>()
                                    .eq("deleted", 0)
                                    .orderByDesc("create_time"));
        }
        Map<Long, String> siteNames = getSiteNames();
        List<RobotsListItem> result = new ArrayList<>();
        for (SeoRobotsEntity e : list) {
            result.add(
                    new RobotsListItem(
                            e.getId(),
                            e.getContent(),
                            e.getSiteId(),
                            siteNames.getOrDefault(e.getSiteId(), ""),
                            e.getStatus(),
                            e.getCreateTime()));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping("/robots")
    public AdminApiResponse<SeoRobotsEntity> createRobots(@RequestBody SeoRobotsEntity entity) {
        if (entity.getSiteId() == null) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        seoRobotsMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/robots/{id}")
    public AdminApiResponse<SeoRobotsEntity> updateRobots(
            @PathVariable Long id, @RequestBody SeoRobotsEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        seoRobotsMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/robots/{id}")
    public AdminApiResponse<Void> deleteRobots(@PathVariable Long id) {
        SeoRobotsEntity entity = seoRobotsMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            seoRobotsMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    // ==================== Sitemap 管理 ====================
    @GetMapping("/sitemap")
    public AdminApiResponse<List<SitemapListItem>> listSitemap(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<SeoSitemapEntity> list;
        if (siteId != null) {
            list = seoSitemapMapper.selectBySiteId(siteId);
        } else {
            list =
                    seoSitemapMapper.selectList(
                            new QueryWrapper<SeoSitemapEntity>()
                                    .eq("deleted", 0)
                                    .orderByDesc("create_time"));
        }
        Map<Long, String> siteNames = getSiteNames();
        List<SitemapListItem> result = new ArrayList<>();
        for (SeoSitemapEntity e : list) {
            result.add(
                    new SitemapListItem(
                            e.getId(),
                            e.getContent(),
                            e.getSiteId(),
                            siteNames.getOrDefault(e.getSiteId(), ""),
                            e.getSitemapType(),
                            e.getStatus(),
                            e.getCreateTime()));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping("/sitemap")
    public AdminApiResponse<SeoSitemapEntity> createSitemap(@RequestBody SeoSitemapEntity entity) {
        if (entity.getSiteId() == null) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        seoSitemapMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/sitemap/{id}")
    public AdminApiResponse<SeoSitemapEntity> updateSitemap(
            @PathVariable Long id, @RequestBody SeoSitemapEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        seoSitemapMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/sitemap/{id}")
    public AdminApiResponse<Void> deleteSitemap(@PathVariable Long id) {
        SeoSitemapEntity entity = seoSitemapMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            seoSitemapMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    // ==================== Hreflang 管理 ====================
    @GetMapping("/hreflang")
    public AdminApiResponse<List<HreflangListItem>> listHreflang(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<SeoHreflangEntity> list;
        if (siteId != null) {
            list = seoHreflangMapper.selectBySiteId(siteId);
        } else {
            list =
                    seoHreflangMapper.selectList(
                            new QueryWrapper<SeoHreflangEntity>()
                                    .eq("deleted", 0)
                                    .orderByDesc("create_time"));
        }
        Map<Long, String> siteNames = getSiteNames();
        Map<Integer, String> menuNames = getMenuNames();
        Map<String, String> contentTitles = getContentTitles(list);
        List<HreflangListItem> result = new ArrayList<>();
        for (SeoHreflangEntity e : list) {
            String menuName =
                    e.getMenuId() != null ? menuNames.getOrDefault(e.getMenuId(), "") : "";
            String contentTitle =
                    e.getMenuId() != null && e.getContentId() != null
                            ? contentTitles.getOrDefault(e.getMenuId() + "_" + e.getContentId(), "")
                            : "";
            result.add(
                    new HreflangListItem(
                            e.getId(),
                            e.getHreflang(),
                            e.getHref(),
                            e.getSiteId(),
                            siteNames.getOrDefault(e.getSiteId(), ""),
                            e.getMenuId(),
                            menuName,
                            e.getContentId(),
                            contentTitle,
                            e.getStatus(),
                            e.getCreateTime()));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping("/hreflang")
    public AdminApiResponse<SeoHreflangEntity> createHreflang(
            @RequestBody SeoHreflangEntity entity) {
        if (entity.getSiteId() == null) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        seoHreflangMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/hreflang/{id}")
    public AdminApiResponse<SeoHreflangEntity> updateHreflang(
            @PathVariable Long id, @RequestBody SeoHreflangEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        seoHreflangMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/hreflang/{id}")
    public AdminApiResponse<Void> deleteHreflang(@PathVariable Long id) {
        SeoHreflangEntity entity = seoHreflangMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            seoHreflangMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    // ==================== GEO 管理 ====================
    @GetMapping("/geo")
    public AdminApiResponse<List<GeoListItem>> listGeo(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<SeoGeoEntity> list;
        if (siteId != null) {
            list = seoGeoMapper.selectBySiteId(siteId);
        } else {
            list =
                    seoGeoMapper.selectList(
                            new QueryWrapper<SeoGeoEntity>()
                                    .eq("deleted", 0)
                                    .orderByDesc("create_time"));
        }
        Map<Long, String> siteNames = getSiteNames();
        List<GeoListItem> result = new ArrayList<>();
        for (SeoGeoEntity e : list) {
            result.add(
                    new GeoListItem(
                            e.getId(),
                            e.getContent(),
                            e.getSiteId(),
                            siteNames.getOrDefault(e.getSiteId(), ""),
                            e.getMenuId(),
                            e.getContentId(),
                            e.getStatus(),
                            e.getCreateTime()));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping("/geo")
    public AdminApiResponse<SeoGeoEntity> createGeo(@RequestBody SeoGeoEntity entity) {
        if (entity.getSiteId() == null) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        seoGeoMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/geo/{id}")
    public AdminApiResponse<SeoGeoEntity> updateGeo(
            @PathVariable Long id, @RequestBody SeoGeoEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        seoGeoMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/geo/{id}")
    public AdminApiResponse<Void> deleteGeo(@PathVariable Long id) {
        SeoGeoEntity entity = seoGeoMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            seoGeoMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    // ==================== SEO Meta 管理 ====================
    @GetMapping("/meta")
    public AdminApiResponse<List<MetaListItem>> listMeta(HttpServletRequest request) {
        Long siteId = getOptionalSiteId(request);
        List<SeoMetaEntity> list;
        if (siteId != null) {
            list = seoMetaMapper.selectBySiteId(siteId);
        } else {
            list =
                    seoMetaMapper.selectList(
                            new QueryWrapper<SeoMetaEntity>()
                                    .eq("deleted", 0)
                                    .orderByDesc("create_time"));
        }
        Map<Long, String> siteNames = getSiteNames();
        List<MetaListItem> result = new ArrayList<>();
        for (SeoMetaEntity e : list) {
            result.add(
                    new MetaListItem(
                            e.getId(),
                            e.getSeoTitle(),
                            e.getSeoKeywords(),
                            e.getSeoDescription(),
                            e.getSiteId(),
                            siteNames.getOrDefault(e.getSiteId(), ""),
                            e.getMenuId(),
                            e.getContentId(),
                            e.getStatus(),
                            e.getCreateTime()));
        }
        return AdminApiResponse.success(result);
    }

    @PostMapping("/meta")
    public AdminApiResponse<SeoMetaEntity> createMeta(@RequestBody SeoMetaEntity entity) {
        if (entity.getSiteId() == null) {
            throw new IllegalArgumentException("请选择应用站点");
        }
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setStatus(entity.getStatus() != null ? entity.getStatus() : 1);
        seoMetaMapper.insert(entity);
        return AdminApiResponse.success(entity);
    }

    @PutMapping("/meta/{id}")
    public AdminApiResponse<SeoMetaEntity> updateMeta(
            @PathVariable Long id, @RequestBody SeoMetaEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(LocalDateTime.now());
        seoMetaMapper.updateById(entity);
        return AdminApiResponse.success(entity);
    }

    @DeleteMapping("/meta/{id}")
    public AdminApiResponse<Void> deleteMeta(@PathVariable Long id) {
        SeoMetaEntity entity = seoMetaMapper.selectById(id);
        if (entity != null) {
            entity.setDeleted(true);
            seoMetaMapper.updateById(entity);
        }
        return AdminApiResponse.success();
    }

    private Long getOptionalSiteId(HttpServletRequest request) {
        SiteContext context = siteContextService.current(request);
        if (context == null || context.isGlobalMode() || context.getSiteId() == null) {
            return null;
        }
        return context.getSiteId();
    }

    private Map<Long, String> getSiteNames() {
        List<SiteProjectEntity> sites =
                aiSiteProjectMapper.selectList(
                        new QueryWrapper<SiteProjectEntity>().eq("deleted", 0));
        return sites.stream()
                .collect(
                        Collectors.toMap(
                                SiteProjectEntity::getId,
                                s -> s.getProjectName() != null ? s.getProjectName() : "",
                                (a, b) -> a));
    }

    private Map<Integer, String> getMenuNames() {
        List<OneshotEntity> menus = oneShotService.getEditableMenu(new OneshotDto(0));
        return menus != null
                ? menus.stream()
                        .filter(m -> m.getId() != null)
                        .collect(
                                Collectors.toMap(
                                        OneshotEntity::getId,
                                        m -> m.getClassName() != null ? m.getClassName() : "",
                                        (a, b) -> a))
                : Map.of();
    }

    private Map<String, String> getContentTitles(List<SeoHreflangEntity> hreflangList) {
        Map<String, String> titles = new HashMap<>();
        if (hreflangList == null) return titles;
        for (SeoHreflangEntity e : hreflangList) {
            if (e.getMenuId() != null && e.getContentId() != null) {
                ContentEntity content = contentMapper.getModelById(e.getContentId());
                if (content != null && content.getTitle() != null) {
                    titles.put(e.getMenuId() + "_" + e.getContentId(), content.getTitle());
                }
            }
        }
        return titles;
    }

    // DTO records
    public record MenuOption(Integer id, String name, Integer pageType) {}

    public record ContentOption(Integer id, String title) {}

    public record RobotsListItem(
            Long id,
            String content,
            Long siteId,
            String siteName,
            Integer status,
            LocalDateTime createTime) {}

    public record SitemapListItem(
            Long id,
            String content,
            Long siteId,
            String siteName,
            String sitemapType,
            Integer status,
            LocalDateTime createTime) {}

    public record HreflangListItem(
            Long id,
            String hreflang,
            String href,
            Long siteId,
            String siteName,
            Integer menuId,
            String menuName,
            Integer contentId,
            String contentTitle,
            Integer status,
            LocalDateTime createTime) {}

    public record GeoListItem(
            Long id,
            String content,
            Long siteId,
            String siteName,
            Integer menuId,
            Integer contentId,
            Integer status,
            LocalDateTime createTime) {}

    public record MetaListItem(
            Long id,
            String seoTitle,
            String seoKeywords,
            String seoDescription,
            Long siteId,
            String siteName,
            Integer menuId,
            Integer contentId,
            Integer status,
            LocalDateTime createTime) {}
}

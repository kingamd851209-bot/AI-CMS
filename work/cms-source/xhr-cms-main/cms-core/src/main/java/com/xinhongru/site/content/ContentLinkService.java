package com.xinhongru.site.content;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.web.service.MenuService;

import org.springframework.stereotype.Service;

@Service
public class ContentLinkService {
    private static final String NO_LINK = "javascript:void(0);";
    private final MenuService menus;

    public ContentLinkService(MenuService menus) {
        this.menus = menus;
    }

    private com.xinhongru.mapper.OneShotMapper listRouteMenus;

    @org.springframework.beans.factory.annotation.Autowired
    public void setListRouteMenus(com.xinhongru.mapper.OneShotMapper mapper) {
        this.listRouteMenus = mapper;
    }

    /** 不使用MenuService已把pageIndex替成1的缓存URL，返回同站原始栏目配置。 */
    public OneshotEntity getStructuredListMenu(Long siteId, Integer menuId) {
        if (siteId == null
                || siteId <= 0
                || menuId == null
                || menuId <= 0
                || listRouteMenus == null) throw new IllegalArgumentException("当前站点栏目路由不可用");
        OneshotEntity menu = listRouteMenus.selectActiveMenuModelBySite(siteId, menuId);
        if (menu == null
                || !java.util.Objects.equals(menu.getSiteProjectId(), siteId)
                || !java.util.Objects.equals(menu.getId(), menuId)
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !Integer.valueOf(1).equals(menu.getPageType()))
            throw new IllegalArgumentException("当前站点栏目未开放前台列表");
        return menu;
    }

    /** 新结构化列表使用真实本地路由；旧无page重载的语义不变。 */
    public String getListUrl(OneshotEntity menu, int page) {
        if (menu == null
                || page < 1
                || menu.getListPageUrl() == null
                || menu.getListPageUrl().isBlank())
            throw new IllegalArgumentException("栏目未配置真实列表路由");
        String route = menu.getListPageUrl();
        boolean dynamic = route.contains("{pageIndex}");
        route =
                route.replace("{pageIndex}", Integer.toString(page))
                        .replace("{classId}", String.valueOf(menu.getId()))
                        .replace("{parentId}", String.valueOf(menu.getParentId()))
                        .replace("{id}", String.valueOf(menu.getId()))
                        .replace(
                                "${className}",
                                menu.getClassName() == null ? "" : menu.getClassName());
        String decoded =
                org.springframework.web.util.UriUtils.decode(
                        route, java.nio.charset.StandardCharsets.UTF_8);
        if (decoded.startsWith("//")
                || decoded.contains(":")
                || decoded.contains("\\")
                || decoded.contains("?")
                || decoded.contains("#")
                || decoded.contains("{")
                || decoded.contains("}")
                || java.util.Arrays.stream(decoded.split("/", -1))
                        .anyMatch(segment -> segment.equals(".") || segment.equals("..")))
            throw new IllegalArgumentException("栏目列表路由必须为当前站点本地路径");
        String local = route.startsWith("/") ? route : "/" + route;
        String url =
                org.springframework.web.util.UriComponentsBuilder.fromPath(local)
                        .build()
                        .encode()
                        .toUriString();
        return dynamic ? url : url + "?pageIndex=" + page;
    }

    public String getDetailUrl(ContentEntity content) {
        if (content == null) return NO_LINK;
        OneshotEntity menu = menus.getModelById(content.getClassid());
        return getDetailUrl(content, menu);
    }

    /** 使用调用者已验证的栏目快照生成规范链接，避免再次读取缓存路由。 */
    public String getDetailUrl(ContentEntity content, OneshotEntity menu) {
        if (content == null
                || menu == null
                || menu.getDetailPageUrl() == null
                || menu.getDetailPageUrl().isBlank()) return NO_LINK;
        return menu.getDetailPageUrl()
                .replace("{id}", String.valueOf(content.getId()))
                .replace("{classId}", String.valueOf(menu.getId()))
                .replace("{parentId}", String.valueOf(menu.getParentId()))
                .replace("{pageIndex}", "1");
    }

    public String getListUrl(OneshotEntity menu) {
        if (menu == null || menu.getListPageUrl() == null || menu.getListPageUrl().isBlank())
            return NO_LINK;
        return menu.getListPageUrl()
                .replace("{pageIndex}", "1")
                .replace("{classId}", String.valueOf(menu.getId()))
                .replace("{parentId}", String.valueOf(menu.getParentId()))
                .replace("{id}", String.valueOf(menu.getId()));
    }
}

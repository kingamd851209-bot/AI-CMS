package com.xinhongru.security.preview;

/** 跨模块查询端口，独立站点进程与 UI 服务读取同一数据库配置。 */
public interface SitePreviewAccessProvider {
    SitePreviewAccess forSiteId(Long siteId);

    SitePreviewAccess forSiteCode(String siteCode);
}

package com.xinhongru.security.preview;

/** 站点门禁内部配置；不直接序列化为 API 响应。 */
public record SitePreviewAccess(Long siteId, boolean enabled, String password) {
    @Override
    public String toString() {
        return "SitePreviewAccess[siteId=" + siteId + ", enabled=" + enabled + "]";
    }
}

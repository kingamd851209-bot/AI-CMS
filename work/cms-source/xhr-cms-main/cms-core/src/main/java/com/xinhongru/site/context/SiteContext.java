package com.xinhongru.site.context;

public class SiteContext {
    private final Long siteId;
    private final Integer rootMenuId;
    private final String siteName;
    private final String siteCode;
    private final boolean globalMode;

    public SiteContext(
            Long siteId, Integer rootMenuId, String siteName, String siteCode, boolean globalMode) {
        this.siteId = siteId;
        this.rootMenuId = rootMenuId;
        this.siteName = siteName;
        this.siteCode = siteCode;
        this.globalMode = globalMode;
    }

    public static SiteContext global() {
        return new SiteContext(null, null, null, null, true);
    }

    public Long getSiteId() {
        return siteId;
    }

    public Integer getRootMenuId() {
        return rootMenuId;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public boolean isGlobalMode() {
        return globalMode;
    }
}

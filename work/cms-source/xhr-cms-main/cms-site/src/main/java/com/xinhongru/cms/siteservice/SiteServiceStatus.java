package com.xinhongru.cms.siteservice;

/**
 * 站点服务运行状态。
 *
 * <p>统一进程与独立进程两种模式共用该结构描述单个站点的站点服务状态。
 */
public record SiteServiceStatus(
        String status, Integer port, String accessUrl, String errorMessage) {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STOPPED = "STOPPED";
}

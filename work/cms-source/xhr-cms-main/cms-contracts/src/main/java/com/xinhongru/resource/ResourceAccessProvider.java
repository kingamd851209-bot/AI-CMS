package com.xinhongru.resource;

/** 站点运行时地址端口，避免核心资源服务反向依赖站点模块。 */
public interface ResourceAccessProvider {
    String accessUrl(String siteCode);
}

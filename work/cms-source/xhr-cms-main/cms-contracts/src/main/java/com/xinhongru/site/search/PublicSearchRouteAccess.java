package com.xinhongru.site.search;

import com.xinhongru.entity.OneshotEntity;

/** 公开检索仅使用正在服务的、已确定匿名可访问的栏目路由。 */
public interface PublicSearchRouteAccess {
    boolean isPublicRoute(Long siteId, String projectCode, OneshotEntity menu);
}

package com.xinhongru.service.site;

/** Headless 站点接入认证配置管理：每站点一对 appKey/appSecret。 */
public interface SiteHeadlessAuthService {
    /** 读取站点认证配置（不存在时 hasAuth=false） */
    AuthView get(Long siteId);

    /** 生成或轮换 appKey/appSecret */
    AuthView rotate(Long siteId);

    /** 更新启用状态与备注 */
    AuthView update(Long siteId, Boolean enabled, String remark);

    /** 确保站点存在认证配置（新建 Headless 站点时调用）；已存在则原样返回 */
    AuthView ensure(Long siteId);

    /** 认证配置视图 */
    record AuthView(
            Long siteId,
            boolean hasAuth,
            String appKey,
            String appSecret,
            boolean enabled,
            String remark,
            String rotateTime) {}
}

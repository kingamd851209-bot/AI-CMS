package com.xinhongru.service.cmsPlatform;

import java.util.List;

/** 表单服务接口（站点服务暴露）的管理侧配置：站点 key 读写轮换 + 接口文档元数据。 */
public interface FormServiceConfigService {
    /** 读取站点 key（不存在时 hasKey=false） */
    KeyView getKey(Long siteId);

    /** 生成或轮换站点 key */
    KeyView rotateKey(Long siteId);

    /** 更新启用状态与备注 */
    KeyView updateKey(Long siteId, Boolean enabled, String remark);

    /** 动态文档元数据（base URL、分类列表、接口清单与示例） */
    DocView doc(Long siteId);

    /** 站点 key 视图 */
    record KeyView(
            Long siteId,
            boolean hasKey,
            String apiKey,
            boolean enabled,
            String remark,
            String rotateTime) {}

    /** 文档视图 */
    record DocView(
            String baseUrl,
            boolean hasKey,
            boolean keyEnabled,
            List<CategoryView> categories,
            List<EndpointView> endpoints) {}

    /** 表单分类视图 */
    record CategoryView(
            Integer id, String name, Integer pageType, boolean captchaRequired, int fieldCount) {}

    /** 接口视图 */
    record EndpointView(
            String name,
            String method,
            String path,
            List<ParamView> params,
            String sampleRequest,
            String sampleResponse) {}

    /** 接口参数视图 */
    record ParamView(String name, boolean required, String desc) {}
}

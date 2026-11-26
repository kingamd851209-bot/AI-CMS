package com.xinhongru.cms.formservice;

import java.util.Map;

/**
 * 表单服务接口业务（运行在站点服务内，直接读写本站点数据）。
 *
 * <p>认证接口要求站点级 key；公开留言接口不暴露查询 key，要求服务启用、前台分类及验证码。所有方法以 {@code siteId} 做站点隔离校验。校验/认证失败抛 {@link
 * FormApiException}，由路由处理器翻译为统一响应体。
 */
public interface FormSiteApiService {
    /** 无查询凭据的公开表单入口，仅开放已启用且强制验证码的本站分类。 */
    Map<String, Object> publicDefinition(Long siteId, Integer categoryId);

    Map<String, Object> publicCaptcha(Long siteId, Integer categoryId);

    Map<String, Object> publicSubmit(Long siteId, SubmitRequest request, String clientIp);

    /** 表单定义查询（含下拉框/固定枚举选项）。 */
    Map<String, Object> definition(Long siteId, Integer categoryId, String apiKey);

    /** 表单显示：定义 + 回填值（{@code contentId} 为空时回填字段默认值）。 */
    Map<String, Object> display(Long siteId, Integer categoryId, Integer contentId, String apiKey);

    /** 表单提交：校验后写入 t_from_contents，返回记录 id 与回查凭证。 */
    Map<String, Object> submit(Long siteId, SubmitRequest request, String clientIp);

    /** 外部按条件查询已提交记录（分页）。 */
    Map<String, Object> queryList(Long siteId, QueryListRequest request);

    /** 提交人回查自己那条：id + 回查 token，或 id + 电话/邮箱。 */
    Map<String, Object> queryOne(
            Long siteId,
            Integer categoryId,
            Integer contentId,
            String queryToken,
            String mobile,
            String email,
            String apiKey);

    /** 认证连通性校验，返回站点信息。 */
    Map<String, Object> authCheck(Long siteId, String apiKey);

    /** 图形验证码（仅当分类开启 form_captcha 时可用）。 */
    Map<String, Object> captcha(Long siteId, Integer categoryId, String apiKey);

    /** 提交请求 */
    record SubmitRequest(
            Integer categoryId,
            Map<String, Object> fields,
            String captchaKey,
            String captchaCode,
            String apiKey) {}

    /** 条件查询请求 */
    record QueryListRequest(
            Integer categoryId,
            int page,
            int size,
            String keyword,
            String beginTime,
            String endTime,
            Integer status,
            String apiKey) {}
}

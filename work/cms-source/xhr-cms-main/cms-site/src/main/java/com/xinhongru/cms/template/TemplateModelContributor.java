package com.xinhongru.cms.template;

import org.thymeleaf.context.Context;

/** 为数据库路由、文件预览和编辑器预览共用的渲染入口提供页面模型。 */
public interface TemplateModelContributor {
    boolean supports(String projectCode, String templatePath, String templateContent);

    void contribute(Long siteId, Context context);

    /** 本次渲染元数据由服务器调用链传入；旧贡献者保持原接口语义。 */
    default void contribute(
            Long siteId,
            String projectCode,
            String templatePath,
            String templateContent,
            Context context,
            boolean publicRequest) {
        contribute(siteId, context);
    }
}

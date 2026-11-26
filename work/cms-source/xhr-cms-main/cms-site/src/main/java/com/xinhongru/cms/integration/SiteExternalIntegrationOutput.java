package com.xinhongru.cms.integration;

import com.alibaba.fastjson.JSON;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 仅由公开站点输出边界调用，不参与共享模板渲染或管理预览。 */
@Component
public class SiteExternalIntegrationOutput {
    public static final String RUNTIME_PATH = "/api/integrations/runtime.js";
    private final SiteExternalIntegrationService service;

    public SiteExternalIntegrationOutput(SiteExternalIntegrationService service) {
        this.service = service;
    }

    public String decorate(Long siteId, String html, int status) {
        if (status != 200 || html == null || service.effective(siteId).isEmpty()) return html;
        String lower = html.toLowerCase(Locale.ROOT);
        if (!lower.contains("<html") && !lower.contains("<!doctype html")) return html;
        if (lower.contains("data-cms-integrations-runtime")) return html;
        String script =
                "<script defer src=\""
                        + RUNTIME_PATH
                        + "\" data-cms-integrations-runtime></script>";
        int body = lower.lastIndexOf("</body>");
        return body >= 0 ? html.substring(0, body) + script + html.substring(body) : html + script;
    }

    public String runtime(Long siteId) throws java.io.IOException {
        // 每次按服务端站点和环境重新筛选；源DOM、query参数不能提供URL或改变mode。
        String entries =
                JSON.toJSONString(
                        service.effective(siteId).stream()
                                .map(
                                        entry ->
                                                java.util.Map.of(
                                                        "kind",
                                                        entry.kind(),
                                                        "scriptUrl",
                                                        entry.scriptUrl(),
                                                        "parameters",
                                                        entry.parameters(),
                                                        "loopbackTest",
                                                        entry.loopbackTest()))
                                .toList());
        try (var input = getClass().getResourceAsStream("/integrations/runtime.js")) {
            if (input == null) throw new IllegalStateException("接入加载器未安装");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("/*__CMS_PUBLIC_ENTRIES__*/[]", entries);
        }
    }
}

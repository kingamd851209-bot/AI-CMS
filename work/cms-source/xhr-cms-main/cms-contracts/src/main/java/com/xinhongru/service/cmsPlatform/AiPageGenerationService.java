package com.xinhongru.service.cmsPlatform;

import java.util.List;

/** AI 页面生成：参考 URL/图片 + prompt → 生成落地页 HTML，并支持保存到静态资源 html 目录 */
public interface AiPageGenerationService {
    record AiRequest(
            String referenceUrl, String referenceHtml, String referenceImage, String prompt) {}

    record AiResult(String prompt, String html, List<String> logs) {}

    AiResult generate(AiRequest request);

    /**
     * 保存 html 到静态资源 html 目录
     *
     * @param filename 文件名（不含扩展名，可空）
     * @param html 内容
     * @return 相对路径（html/xxx.html）
     */
    String saveHtml(String filename, String html);
}

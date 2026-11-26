package com.xinhongru.service.cmsPlatform.impl;

import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.properties.StaticAssetProperties;
import com.xinhongru.service.cmsPlatform.AiPageGenerationService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiPageGenerationServiceImpl implements AiPageGenerationService {
    private static final String USAGE_TYPE = "AI_PAGE_GENERATION";
    private static final String DEFAULT_PROMPT = "依据上述参考内容，并补充内容说明，生成落地页面html。";

    @Resource private AiTextGenerator generator;
    @Resource private StaticAssetProperties staticAssetProperties;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public AiResult generate(AiRequest request) {
        List<String> logs = new ArrayList<>();
        logs.add("开始 AI 页面生成");

        String referenceText = null;
        if (request.referenceHtml() != null && !request.referenceHtml().isBlank()) {
            referenceText = sanitizeHtml(request.referenceHtml());
            logs.add("已使用上传的目标页面内容（" + referenceText.length() + " 字符）");
        } else if (request.referenceUrl() != null && !request.referenceUrl().isBlank()) {
            logs.add("正在抓取参考页面：" + request.referenceUrl());
            referenceText = fetchPageText(request.referenceUrl().trim());
            if (referenceText == null) {
                logs.add("参考页面抓取失败，将仅依据提示词生成");
            } else {
                logs.add("参考页面内容提取完成（" + referenceText.length() + " 字符）");
            }
        }

        boolean hasImage = request.referenceImage() != null && !request.referenceImage().isBlank();
        if (hasImage) {
            throw new IllegalArgumentException("当前生成接口仅支持 HTML 或页面 URL，请将参考图片转换为页面内容后提交");
        }

        String userPrompt =
                (request.prompt() == null || request.prompt().isBlank())
                        ? DEFAULT_PROMPT
                        : request.prompt();
        String finalPrompt = buildPrompt(userPrompt, referenceText, hasImage);
        logs.add("提示词构造完成");

        logs.add("正在调用 AI 模型...");
        String html = generator.generate(AiUsage.AI_PAGE_GENERATION, finalPrompt);
        html = stripMarkdownFence(html == null ? "" : html);
        if (html.isBlank() || !html.contains("<"))
            throw new IllegalStateException("AI 未返回有效 HTML 页面");
        logs.add("AI 生成完成，共 " + html.length() + " 字符");

        return new AiResult(finalPrompt, html, logs);
    }

    @Override
    public String saveHtml(String filename, String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("HTML 内容为空");
        }
        Path root = Path.of(staticAssetProperties.getRoot()).toAbsolutePath().normalize();
        Path dir = root.resolve("html");
        try {
            Files.createDirectories(dir);
            String safeName = (filename == null || filename.isBlank()) ? "ai-page" : filename;
            safeName = safeName.replaceAll("[^\\w\\-]", "_");
            String name = safeName + "-" + System.currentTimeMillis() % 100000 + ".html";
            Files.writeString(dir.resolve(name), html, StandardCharsets.UTF_8);
            return "html/" + name;
        } catch (Exception ex) {
            throw new IllegalArgumentException("保存失败：" + ex.getMessage(), ex);
        }
    }

    private String buildPrompt(String userPrompt, String referenceText, boolean hasImage) {
        StringBuilder sb = new StringBuilder();
        if (referenceText != null && !referenceText.isBlank()) {
            sb.append("参考页面内容（仅作样式与文案参考，不要照抄敏感信息）：\n").append(referenceText).append("\n\n");
        }
        if (hasImage) {
            sb.append("已提供参考页面图片，请按其视觉风格生成。\n");
        }
        sb.append("用户要求：").append(userPrompt).append("\n");
        sb.append("请直接输出完整的单文件 HTML 代码（含内联 CSS/JS），不要输出解释文字。");
        return sb.toString();
    }

    private String fetchPageText(String url) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "Mozilla/5.0 Hongru-CMS")
                            .GET()
                            .build();
            HttpResponse<String> resp =
                    httpClient.send(
                            req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body();
            return sanitizeHtml(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private String sanitizeHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text =
                html.replaceAll("(?s)<script.*?</script>", " ")
                        .replaceAll("(?s)<style.*?</style>", " ")
                        .replaceAll("(?s)<[^>]+>", " ")
                        .replaceAll("&nbsp;", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String stripMarkdownFence(String content) {
        String c = content.trim();
        if (c.startsWith("```")) {
            int firstNl = c.indexOf('\n');
            if (firstNl >= 0) {
                c = c.substring(firstNl + 1);
            } else {
                c = c.replaceFirst("^```[^\\n]*", "");
            }
            if (c.endsWith("```")) {
                c = c.substring(0, c.lastIndexOf("```"));
            }
        }
        return c.trim();
    }
}

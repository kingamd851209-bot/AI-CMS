package com.xinhongru.cms.column;

import com.xinhongru.ai.generation.AiOutputValidator;
import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.ai.generation.HtmlRecognitionContext;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class ModelRecognitionService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelRecognitionService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_HTML_LENGTH = 50000;

    private final UiPackageStorageService storageService;
    private final SiteProjectMapper projectMapper;

    private final AiTextGenerator generator;

    public ModelRecognitionService(
            UiPackageStorageService storageService,
            SiteProjectMapper projectMapper,
            AiTextGenerator generator) {
        this.storageService = storageService;
        this.projectMapper = projectMapper;
        this.generator = generator;
    }

    public interface RecognitionProgressCallback {
        void onProgress(String stage, String message);

        void onPrompt(String prompt);

        void onAiResponseChunk(String chunk);
    }

    public RecognitionResult recognizeColumns(
            Long siteId,
            String filePath,
            String additionalInstructions,
            RecognitionProgressCallback progressCallback) {
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            return RecognitionResult.failed("SITE_NOT_FOUND", "站点不存在");
        }
        if (site.getUiPackageVersion() == null) {
            return RecognitionResult.failed("UIPACKAGE_NOT_UPLOADED", "UIPackage 未上传");
        }

        if (progressCallback != null) {
            progressCallback.onProgress("reading_file", "正在读取文件...");
        }

        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            return RecognitionResult.failed("FILE_READ_ERROR", "无法读取文件内容");
        }

        String truncatedHtml = truncateHtml(htmlContent);
        String prompt = buildPrompt(truncatedHtml, filePath, additionalInstructions);

        // 发送 prompt
        if (progressCallback != null) {
            progressCallback.onPrompt(prompt);
            progressCallback.onProgress("calling_model", "正在调用 AI 识别...");
        }

        try {
            String rawResponse = runModel(prompt, progressCallback);
            if (rawResponse == null || rawResponse.isBlank()) {
                return RecognitionResult.failed("EMPTY_RESPONSE", "AI 返回空响应");
            }

            if (progressCallback != null) {
                progressCallback.onProgress("parsing_result", "正在解析结果...");
            }

            String content = AiOutputValidator.validate(AiUsage.COLUMN_RECOGNITION, rawResponse);
            List<RecognizedColumn> columns = parseColumns(content);

            return RecognitionResult.success(columns, rawResponse, prompt);
        } catch (Exception e) {
            LOG.error("Model recognition failed", e);
            return RecognitionResult.failed("RECOGNITION_ERROR", "识别失败，请检查模型配置或输出格式");
        }
    }

    /** 使用用户编辑后的 prompt 直接进行识别 */
    public RecognitionResult recognizeColumnsWithPrompt(
            Long siteId, String prompt, RecognitionProgressCallback progressCallback) {
        if (prompt == null || prompt.isBlank()) {
            return RecognitionResult.failed("EMPTY_PROMPT", "Prompt 不能为空");
        }

        if (progressCallback != null) {
            progressCallback.onProgress("calling_model", "正在调用 AI 识别...");
        }

        try {
            String rawResponse = runModel(prompt, progressCallback);
            if (rawResponse == null || rawResponse.isBlank()) {
                return RecognitionResult.failed("EMPTY_RESPONSE", "AI 返回空响应");
            }

            if (progressCallback != null) {
                progressCallback.onProgress("parsing_result", "正在解析结果...");
            }

            String content = AiOutputValidator.validate(AiUsage.COLUMN_RECOGNITION, rawResponse);
            List<RecognizedColumn> columns = parseColumns(content);

            return RecognitionResult.success(columns, rawResponse, prompt);
        } catch (Exception e) {
            LOG.error("Model recognition failed", e);
            return RecognitionResult.failed("RECOGNITION_ERROR", "识别失败，请检查模型配置或输出格式");
        }
    }

    private String runModel(String prompt, RecognitionProgressCallback callback) {
        return generator.stream(AiUsage.COLUMN_RECOGNITION, prompt)
                .doOnNext(
                        chunk -> {
                            if (callback != null) callback.onAiResponseChunk(chunk);
                        })
                .collectList()
                .map(parts -> String.join("", parts))
                .block();
    }

    public RecognitionResult generateTemplate(
            Long siteId, String prompt, RecognitionProgressCallback callback) {
        try {
            String content =
                    generator.stream(AiUsage.TEMPLATE_GENERATION, prompt)
                            .doOnNext(
                                    chunk -> {
                                        if (callback != null) callback.onAiResponseChunk(chunk);
                                    })
                            .collectList()
                            .map(parts -> String.join("", parts))
                            .block();
            if (content == null || content.isBlank())
                return RecognitionResult.failed("EMPTY_RESPONSE", "AI 返回空响应");
            AiOutputValidator.validate(AiUsage.TEMPLATE_GENERATION, content);
            return RecognitionResult.success(List.of(), content, prompt);
        } catch (RuntimeException error) {
            return RecognitionResult.failed("GENERATION_FAILED", "模板生成失败，请检查模型配置后重试");
        }
    }

    private String buildPrompt(String htmlContent, String filePath, String additionalInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下 HTML 源代码，识别网站的栏目结构（导航菜单/频道结构）。\n\n");
        sb.append("文件路径：").append(filePath).append("\n\n");
        sb.append("【重要规则】\n");
        sb.append("1. 排除 <div class=\"main\"> 区域内的内容，该区域不是栏目数据\n");
        sb.append("2. 保持原文语言，不要翻译：如果页面是中文，栏目名称就用中文；如果页面是英文，栏目名称就用英文\n");
        sb.append("3. enName 字段仅当页面本身有英文版本时才填写，否则留空\n\n");
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            sb.append("用户补充说明：").append(additionalInstructions).append("\n\n");
        }
        sb.append("HTML 源代码：\n```\n").append(htmlContent).append("\n```\n\n");
        sb.append("返回 JSON 格式，结构如下：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"columns\": [\n");
        sb.append("    {\n");
        sb.append("      \"name\": \"栏目名称（保持原文语言）\",\n");
        sb.append("      \"enName\": \"仅当页面有英文版本时填写，否则为 null\",\n");
        sb.append("      \"path\": \"/path/to/page\",\n");
        sb.append("      \"pageType\": 1,\n");
        sb.append("      \"sort\": 1,\n");
        sb.append("      \"parentName\": null,\n");
        sb.append("      \"children\": [...]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("pageType 说明：0=单篇页面，1=列表页面。只返回 JSON，不要包含其他文字。");
        return sb.toString();
    }

    private List<RecognizedColumn> parseColumns(String content) {
        String jsonStr = stripMarkdownFence(content);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonStr);
            JsonNode columnsNode = root.has("columns") ? root.get("columns") : root.get("menus");
            if (columnsNode == null || !columnsNode.isArray()) {
                throw new IllegalArgumentException("栏目结果必须包含 columns 数组");
            }
            List<RecognizedColumn> result = new ArrayList<>();
            int sort = 1;
            for (JsonNode node : columnsNode) {
                RecognizedColumn column = parseColumn(node, null, sort++);
                if (column != null) {
                    result.add(column);
                    if (node.has("children") && node.get("children").isArray()) {
                        int childSort = 1;
                        for (JsonNode child : node.get("children")) {
                            RecognizedColumn childColumn =
                                    parseColumn(child, column.name(), childSort++);
                            if (childColumn != null) {
                                result.add(childColumn);
                            }
                        }
                    }
                }
            }
            return result;
        } catch (Exception ex) {
            LOG.error("Failed to parse columns JSON", ex);
            throw new IllegalArgumentException("AI 栏目结果格式无效", ex);
        }
    }

    private RecognizedColumn parseColumn(JsonNode node, String parentName, int sort) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("栏目必须为对象");
        String name = node.has("name") ? node.get("name").asText() : null;
        if (name == null || name.isBlank()) throw new IllegalArgumentException("栏目名称不能为空");
        if (node.has("pageType")
                && (!node.get("pageType").isIntegralNumber()
                        || node.get("pageType").asInt() < 0
                        || node.get("pageType").asInt() > 1))
            throw new IllegalArgumentException("栏目类型必须为 0 或 1");
        return new RecognizedColumn(
                name,
                node.has("enName") ? node.get("enName").asText() : null,
                node.has("path") ? node.get("path").asText() : null,
                node.has("pageType") ? node.get("pageType").asInt() : 1,
                sort,
                parentName,
                null,
                null,
                null,
                null);
    }

    private String stripMarkdownFence(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("```")) return value;
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd >= 0) {
            value = value.substring(firstLineEnd + 1).trim();
        } else {
            value = value.substring(3).trim();
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3).trim();
        }
        return value;
    }

    private String truncateHtml(String html) {
        return HtmlRecognitionContext.navigation(html, MAX_HTML_LENGTH);
    }

    public record RecognitionResult(
            String status,
            String errorCode,
            String errorMessage,
            List<RecognizedColumn> columns,
            String rawResponse,
            String prompt) {
        public static RecognitionResult success(
                List<RecognizedColumn> columns, String rawResponse, String prompt) {
            return new RecognitionResult("SUCCESS", null, null, columns, rawResponse, prompt);
        }

        public static RecognitionResult failed(String errorCode, String errorMessage) {
            return new RecognitionResult("FAILED", errorCode, errorMessage, List.of(), null, null);
        }
    }

    public record RecognizedColumn(
            String name,
            String enName,
            String path,
            Integer pageType,
            Integer sort,
            String parentName,
            String listPageUrl,
            String listPageTemplate,
            String detailPageUrl,
            String detailPageTemplate) {}
}

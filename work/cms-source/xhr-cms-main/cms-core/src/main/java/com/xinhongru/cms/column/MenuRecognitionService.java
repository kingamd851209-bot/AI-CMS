package com.xinhongru.cms.column;

import com.xinhongru.ai.config.AiHttpClientFactory;
import com.xinhongru.ai.config.AiProviderConfigService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MenuRecognitionService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_MS = 60000;
    private static final int MAX_HTML_LENGTH = 50000;

    private final AiProviderConfigService providerConfigService;
    private final UiPackageStorageService storageService;
    private final SiteProjectMapper projectMapper;
    private OneShotMapper oneShotMapper;

    public MenuRecognitionService(
            AiProviderConfigService providerConfigService,
            UiPackageStorageService storageService,
            SiteProjectMapper projectMapper) {
        this.providerConfigService = providerConfigService;
        this.storageService = storageService;
        this.projectMapper = projectMapper;
    }

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    public RecognitionResult recognizeMenus(
            Long siteId, String filePath, String additionalInstructions) {
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在");
        }
        if (site.getUiPackageVersion() == null) {
            throw new IllegalStateException("UIPackage 未上传");
        }

        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            throw new IllegalStateException("无法读取文件内容");
        }

        String truncatedHtml = truncateHtml(htmlContent);
        AiProviderConfigService.RuntimeConfig config =
                providerConfigService.findRuntimeConfig("SITE_RECOGNITION");
        if (config == null) {
            return RecognitionResult.failed(
                    "PROVIDER_NOT_CONFIGURED",
                    "AI Provider 未配置，请先在系统设置中配置 SITE_RECOGNITION 类型的 Provider");
        }

        try {
            String requestBody =
                    buildRequestBody(
                            config.modelName(), truncatedHtml, filePath, additionalInstructions);
            HttpRequest request =
                    HttpRequest.newBuilder(chatCompletionsUri(config.baseUrl()))
                            .timeout(Duration.ofMillis(timeoutMs(config.timeoutMs())))
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

            HttpResponse<String> response =
                    AiHttpClientFactory.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return RecognitionResult.failed(
                        "HTTP_ERROR",
                        "HTTP " + response.statusCode() + ": " + limit(response.body(), 500));
            }

            String content = extractContent(response.body());
            List<RecognizedMenu> menus = parseMenus(content);
            return RecognitionResult.success(menus, content);
        } catch (IOException e) {
            return RecognitionResult.failed("IO_ERROR", "网络请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RecognitionResult.failed("INTERRUPTED", "请求被中断");
        } catch (RuntimeException e) {
            return RecognitionResult.failed("CLIENT_ERROR", e.getMessage());
        }
    }

    public ExistingMenusView getExistingMenus(Long siteId) {
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在");
        }
        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId == null) {
            return new ExistingMenusView(List.of(), List.of());
        }

        List<OneshotEntity> allMenus = oneShotMapper.selectListBySiteProjectId(siteId);
        if (allMenus == null || allMenus.isEmpty()) {
            return new ExistingMenusView(List.of(), List.of());
        }

        List<MenuNode> tree = buildMenuTree(allMenus, rootMenuId);
        List<MenuNode> flat = flattenTree(tree);
        return new ExistingMenusView(tree, flat);
    }

    @Transactional
    public SaveResult saveRecognizedMenus(Long siteId, List<RecognizedMenu> menus) {
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在");
        }

        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId == null) {
            throw new IllegalStateException("站点未绑定根栏目");
        }

        List<OneshotEntity> existingMenus = oneShotMapper.selectListBySiteProjectId(siteId);
        Set<String> existingNames = new HashSet<>();
        Map<String, OneshotEntity> existingByName = new HashMap<>();
        for (OneshotEntity menu :
                existingMenus != null ? existingMenus : List.<OneshotEntity>of()) {
            if (menu.getClassName() != null) {
                existingNames.add(menu.getClassName().trim());
                existingByName.put(menu.getClassName().trim(), menu);
            }
        }

        int newCount = 0;
        int matchedCount = 0;

        for (RecognizedMenu menu : menus) {
            String name = menu.name() != null ? menu.name().trim() : "";
            if (name.isEmpty()) continue;

            if (existingNames.contains(name)) {
                matchedCount++;
            } else {
                EditOneshotDto dto = new EditOneshotDto();
                dto.setSiteProjectId(siteId);
                dto.setSiteRootId(rootMenuId);
                // 优先使用前端指定的父节点ID
                if (menu.parentIdOverride() != null) {
                    dto.setParentId(menu.parentIdOverride());
                } else {
                    dto.setParentId(findParentId(menus, menu, rootMenuId, existingByName));
                }
                dto.setClassName(name);
                dto.setClassEnName(menu.enName());
                dto.setSort(menu.sort() != null ? menu.sort() : 0);
                dto.setPageType(menu.pageType() != null ? menu.pageType() : 1);
                // 优先使用 listPageUrl，为空时使用 path
                dto.setListPageUrl(
                        menu.listPageUrl() != null && !menu.listPageUrl().isBlank()
                                ? menu.listPageUrl()
                                : menu.path());
                dto.setListPageTemplate(menu.listPageTemplate());
                dto.setDetailPageUrl(menu.detailPageUrl());
                dto.setDetailPageTemplate(menu.detailPageTemplate());
                dto.setHasManageChannel(true);
                dto.setHasFrontChannel(true);
                // 设置栏目权限：可删除、可编辑、可排序
                dto.setIsCanDeleted(true);
                dto.setIsCanEdit(true);
                dto.setIsCanSort(true);
                dto.setCreateTime(new Date());
                oneShotMapper.insertMenu(dto);
                // 新增栏目默认 biz_code 取自身 id，多站点下按 (biz_code, site_project_id) 定位
                oneShotMapper.assignBizcodeToOwnId(dto.getId());
                newCount++;
                // 将新创建的栏目加入 map，以便后续子栏目可以找到父栏目
                OneshotEntity newEntity = new OneshotEntity();
                newEntity.setId(dto.getId());
                newEntity.setClassName(name);
                existingByName.put(name, newEntity);
            }
        }

        return new SaveResult(newCount, matchedCount, newCount + matchedCount);
    }

    private Integer findParentId(
            List<RecognizedMenu> menus,
            RecognizedMenu menu,
            Integer rootMenuId,
            Map<String, OneshotEntity> existingByName) {
        if (menu.parentName() == null || menu.parentName().isBlank()) {
            return rootMenuId;
        }
        OneshotEntity parent = existingByName.get(menu.parentName().trim());
        if (parent != null && parent.getId() != null) {
            return parent.getId();
        }
        return rootMenuId;
    }

    private String buildRequestBody(
            String modelName, String htmlContent, String filePath, String additionalInstructions) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        messages.add(message("system", systemPrompt()));
        messages.add(message("user", userPrompt(htmlContent, filePath, additionalInstructions)));
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JacksonException ex) {
            throw new IllegalStateException("请求序列化失败", ex);
        }
    }

    private String systemPrompt() {
        return """
        你是一个 CMS 网站栏目识别助手。你的任务是从 HTML 源代码中识别网站的栏目结构（导航菜单/频道结构）。

        请仔细分析 HTML，识别出：
        1. 主导航栏目
        2. 子栏目（多级导航）
        3. 栏目名称
        4. 栏目链接/路径
        5. 栏目类型（单篇/列表）

        返回 JSON 格式，结构如下：
        ```json
        {
          "columns": [
            {
              "name": "栏目名称",
              "enName": "English Name",
              "path": "/path/to/page",
              "pageType": 1,
              "sort": 10,
              "parentName": null,
              "children": [
                {
                  "name": "子栏目名称",
                  "enName": "Sub Menu",
                  "path": "/path/to/subpage",
                  "pageType": 1,
                  "sort": 10,
                  "parentName": "父栏目名称"
                }
              ]
            }
          ]
        }
        ```

        pageType 说明：
        - 0: 单篇页面（如"关于我们"、"联系方式"等）
        - 1: 列表页面（如"新闻中心"、"产品展示"等）

        sort 规则：
        - sort 值表示栏目的显示顺序，必须与 HTML 中出现的顺序一致
        - 每个 sort 值之间相差 10（如 10, 20, 30...）
        - 同一级别的栏目按从上到下、从左到右的顺序递增 sort 值
        - 子栏目的 sort 在其父栏目下独立计算

        注意事项：
        - 只返回 JSON，不要包含其他文字或 Markdown 标记
        - 忽略登录、注册、搜索等功能性链接
        - 对于下拉菜单或多级导航，正确设置父子关系
        """;
    }

    private String userPrompt(String htmlContent, String filePath, String additionalInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下 HTML 源代码，识别网站的栏目结构。\n\n");
        sb.append("文件路径：").append(filePath).append("\n\n");
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            sb.append("用户补充说明：").append(additionalInstructions).append("\n\n");
        }
        sb.append("HTML 源代码：\n```\n").append(htmlContent).append("\n```\n\n");
        sb.append("请返回 JSON 格式的栏目结构。");
        return sb.toString();
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = OBJECT_MAPPER.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return validateUri(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return validateUri(normalized + "/chat/completions");
        }
        return validateUri(normalized + "/v1/chat/completions");
    }

    private URI validateUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme =
                    uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalStateException("URL 必须使用 http/https 协议");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("URL host 不能为空");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("URL 格式无效", ex);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("响应中没有 choices");
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            JsonNode content = message == null ? firstChoice.get("text") : message.get("content");
            if (content == null || content.asText().isBlank()) {
                throw new IllegalStateException("响应中没有 content");
            }
            return content.asText().trim();
        } catch (JacksonException ex) {
            throw new IllegalStateException("响应 JSON 解析失败", ex);
        }
    }

    private List<RecognizedMenu> parseMenus(String content) {
        String jsonStr = stripMarkdownFence(content);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonStr);
            JsonNode columnsNode = root.get("columns");
            if (columnsNode == null || !columnsNode.isArray()) {
                return List.of();
            }
            List<RecognizedMenu> result = new ArrayList<>();
            int index = 1;
            for (JsonNode node : columnsNode) {
                RecognizedMenu menu = parseMenu(node, null, index++);
                if (menu != null) {
                    result.add(menu);
                    if (node.has("children") && node.get("children").isArray()) {
                        int childIndex = 1;
                        for (JsonNode child : node.get("children")) {
                            RecognizedMenu childMenu = parseMenu(child, menu.name(), childIndex++);
                            if (childMenu != null) {
                                result.add(childMenu);
                            }
                        }
                    }
                }
            }
            return result;
        } catch (JacksonException ex) {
            throw new IllegalStateException("栏目 JSON 解析失败: " + ex.getMessage(), ex);
        }
    }

    private RecognizedMenu parseMenu(JsonNode node, String parentName, int index) {
        if (node == null || !node.isObject()) return null;
        String name = node.has("name") ? node.get("name").asText() : null;
        if (name == null || name.isBlank()) return null;
        // 强制使用 index * 10 作为 sort，确保顺序正确且间隔为 10
        int sort = index * 10;
        // 处理 path：.aspx 转换为 .html
        String path = node.has("path") ? node.get("path").asText() : null;
        if (path != null && path.toLowerCase().endsWith(".aspx")) {
            path = path.substring(0, path.length() - 5) + ".html";
        }
        return new RecognizedMenu(
                name,
                node.has("enName") ? node.get("enName").asText() : null,
                path,
                node.has("pageType") ? node.get("pageType").asInt() : 1,
                sort,
                parentName,
                null,
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
        if (html == null || html.length() <= MAX_HTML_LENGTH) {
            return html;
        }
        return html.substring(0, MAX_HTML_LENGTH) + "\n... [内容已截断]";
    }

    private int timeoutMs(Integer value) {
        return value == null || value <= 0 ? DEFAULT_TIMEOUT_MS : value;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<MenuNode> buildMenuTree(List<OneshotEntity> menus, Integer rootMenuId) {
        Map<Integer, List<OneshotEntity>> byParent = new HashMap<>();
        for (OneshotEntity menu : menus) {
            int parentId = menu.getParentId();
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(menu);
        }
        return buildTreeNodes(rootMenuId, byParent, 0);
    }

    private List<MenuNode> buildTreeNodes(
            Integer parentId, Map<Integer, List<OneshotEntity>> byParent, int depth) {
        List<OneshotEntity> children = byParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<MenuNode> result = new ArrayList<>();
        for (OneshotEntity entity : children) {
            MenuNode node =
                    new MenuNode(
                            entity.getId(),
                            entity.getClassName(),
                            entity.getClassEnName(),
                            entity.getPageType(),
                            entity.getSort(),
                            depth,
                            parentId, // 添加父栏目ID
                            buildTreeNodes(entity.getId(), byParent, depth + 1),
                            false,
                            entity.getListPageUrl(),
                            entity.getListPageTemplate(),
                            entity.getDetailPageUrl(),
                            entity.getDetailPageTemplate(),
                            entity.getHasManageChannel(),
                            entity.getHasFrontChannel());
            result.add(node);
        }
        return result;
    }

    private List<MenuNode> flattenTree(List<MenuNode> tree) {
        List<MenuNode> result = new ArrayList<>();
        flattenTreeRecursive(tree, result);
        return result;
    }

    private void flattenTreeRecursive(List<MenuNode> nodes, List<MenuNode> result) {
        for (MenuNode node : nodes) {
            result.add(node);
            if (node.children() != null && !node.children().isEmpty()) {
                flattenTreeRecursive(node.children(), result);
            }
        }
    }

    public record RecognitionResult(
            String status,
            String errorCode,
            String errorMessage,
            List<RecognizedMenu> menus,
            String rawResponse) {
        public static RecognitionResult success(List<RecognizedMenu> menus, String rawResponse) {
            return new RecognitionResult("SUCCESS", null, null, menus, rawResponse);
        }

        public static RecognitionResult failed(String errorCode, String errorMessage) {
            return new RecognitionResult("FAILED", errorCode, errorMessage, List.of(), null);
        }
    }

    public record RecognizedMenu(
            String name,
            String enName,
            String path,
            Integer pageType,
            Integer sort,
            String parentName,
            String listPageUrl,
            String listPageTemplate,
            String detailPageUrl,
            String detailPageTemplate,
            Integer parentIdOverride) {}

    public record ExistingMenusView(List<MenuNode> tree, List<MenuNode> flat) {}

    public record MenuNode(
            Integer id,
            String name,
            String enName,
            Integer pageType,
            Integer sort,
            int depth,
            Integer parentId,
            List<MenuNode> children,
            boolean isNew,
            String listPageUrl,
            String listPageTemplate,
            String detailPageUrl,
            String detailPageTemplate,
            Boolean hasManageChannel,
            Boolean hasFrontChannel) {}

    public record SaveResult(int newCount, int matchedCount, int totalCount) {}
}

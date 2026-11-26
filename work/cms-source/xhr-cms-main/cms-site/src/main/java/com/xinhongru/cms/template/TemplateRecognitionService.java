package com.xinhongru.cms.template;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xinhongru.cms.column.ModelRecognitionService;
import com.xinhongru.cms.column.ModelRecognitionService.RecognitionProgressCallback;
import com.xinhongru.cms.column.ModelRecognitionService.RecognitionResult;
import com.xinhongru.cms.uipackage.UiPackageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.site.template.TemplateFileAccess;
import com.xinhongru.web.utils.CommonUtils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.Character.UnicodeBlock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TemplateRecognitionService implements TemplateFileAccess {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateRecognitionService.class);

    private final UiPackageStorageService storageService;
    private final TemplateStorageService templateStorageService;
    private final SiteProjectMapper projectMapper;
    private final ModelRecognitionService recognitionService;
    private final CommonUtils commonUtils;
    private final UiPackageService uiPackageService;

    public TemplateRecognitionService(
            UiPackageStorageService storageService,
            TemplateStorageService templateStorageService,
            SiteProjectMapper projectMapper,
            ModelRecognitionService recognitionService,
            CommonUtils commonUtils,
            UiPackageService uiPackageService) {
        this.storageService = storageService;
        this.templateStorageService = templateStorageService;
        this.projectMapper = projectMapper;
        this.recognitionService = recognitionService;
        this.commonUtils = commonUtils;
        this.uiPackageService = uiPackageService;
    }

    private OneShotMapper oneShotMapper;

    @Autowired(required = false)
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    /** 获取已存在的模板文件列表 */
    public List<TemplateFileInfo> getTemplateFiles(Long siteId, String templateType) {
        SiteProjectEntity site = requireSite(siteId);
        Path templateDir = getTemplateDir(site.getProjectCode());

        if (!Files.isDirectory(templateDir)) {
            return List.of();
        }

        List<TemplateFileInfo> files = new ArrayList<>();
        try (var stream = Files.walk(templateDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(templateDir.resolve(".history")))
                    .filter(p -> isHtmlLike(p))
                    .forEach(
                            p -> {
                                String relativePath =
                                        templateDir.relativize(p).toString().replace('\\', '/');
                                files.add(
                                        new TemplateFileInfo(
                                                relativePath, p.getFileName().toString()));
                            });
        } catch (IOException e) {
            LOG.error("获取模板文件列表失败", e);
        }

        return files;
    }

    /** 获取公共元素模板文件列表 */
    public List<TemplateFileInfo> getCommonTemplateFiles(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        Path incDir = getTemplateDir(site.getProjectCode()).resolve("inc");

        if (!Files.isDirectory(incDir)) {
            return List.of();
        }

        List<TemplateFileInfo> files = new ArrayList<>();
        try (var stream = Files.list(incDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isHtmlLike(p))
                    .forEach(
                            p -> {
                                String relativePath = "inc/" + p.getFileName().toString();
                                files.add(
                                        new TemplateFileInfo(
                                                relativePath, p.getFileName().toString()));
                            });
        } catch (IOException e) {
            LOG.error("获取公共模板文件列表失败", e);
        }

        return files;
    }

    /** 识别公共元素模板 */
    public CommonTemplateResult recognizeCommonTemplate(
            Long siteId,
            String filePath,
            String templateName,
            RecognitionProgressCallback callback) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取原始文件内容
        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            return CommonTemplateResult.failed("FILE_READ_ERROR", "无法读取文件内容");
        }

        // 构建 prompt
        String prompt =
                buildCommonTemplatePrompt(siteId, htmlContent, filePath, templateName, null);

        // 发送 prompt 给前端显示
        if (callback != null) {
            callback.onPrompt(prompt);
        }

        // 调用 AI 识别
        RecognitionResult result = recognitionService.generateTemplate(siteId, prompt, callback);

        if (!"SUCCESS".equals(result.status())) {
            return CommonTemplateResult.failed(result.errorCode(), result.errorMessage());
        }

        // 提取模板代码
        String templateContent = extractTemplateContent(result.rawResponse());

        // 保存模板文件
        String savePath =
                saveTemplate(
                        site.getProjectCode(), "inc/" + templateName + ".html", templateContent);

        return CommonTemplateResult.success(templateContent, savePath, result.rawResponse());
    }

    /** 识别内容模板 */
    public ContentTemplateResult recognizeContentTemplate(
            Long siteId,
            String filePath,
            Long columnId,
            String templateType,
            String outputPath,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            RecognitionProgressCallback callback) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取原始文件内容
        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            return ContentTemplateResult.failed("FILE_READ_ERROR", "无法读取文件内容");
        }

        // 获取栏目信息
        OneshotEntity column = findColumn(columnId);
        String columnName = column != null ? column.getClassName() : "栏目" + columnId;
        String columnEnName = columnEnglishName(column);
        if (columnEnName.isBlank()) {
            columnEnName = toPinyinAbbreviation(columnName);
        }

        // 构建保存路径：按栏目树结构（英文名逐级）生成子目录
        String dirName = buildContentTemplateDir(columnId);

        // 构建 prompt，包含栏目名称信息、字段配置和公共模板选择状态
        String prompt =
                buildContentTemplatePrompt(
                        siteId,
                        htmlContent,
                        filePath,
                        column,
                        columnEnName,
                        templateType,
                        includeKey,
                        includeHeader,
                        includeFooter);

        // 发送 prompt 给前端显示
        if (callback != null) {
            callback.onPrompt(prompt);
        }

        // 调用 AI 识别
        RecognitionResult result = recognitionService.generateTemplate(siteId, prompt, callback);

        if (!"SUCCESS".equals(result.status())) {
            return ContentTemplateResult.failed(result.errorCode(), result.errorMessage());
        }

        // 提取模板代码
        String templateContent = extractTemplateContent(result.rawResponse());

        // 确定保存路径（根栏目 dirName 为空，直接保存在 template/web/ 下）
        String fileName = "list".equals(templateType) ? "index.html" : "details.html";
        String relativePath = dirName.isEmpty() ? fileName : dirName + "/" + fileName;
        String savePath = saveTemplate(site.getProjectCode(), relativePath, templateContent);

        return ContentTemplateResult.success(templateContent, savePath, result.rawResponse());
    }

    /**
     * 计算内容模板的保存子目录：结合栏目树状结构逐级拼接，每级取栏目的英文名。
     *
     * <p>每级目录名取值规则：栏目名(className)（中文转拼音全拼、英文原样），为空时回退 classEnName， 统一做 ASCII 化（仅保留字母/数字/-）；仍为空时回退
     * column-栏目id。 多级栏目生成多级目录（不含站点根栏目），例如 about/news/xinwenzhongxin。
     *
     * <p>去重：同一站点内若多个栏目得到相同的名称路径，则给这些栏目的叶子目录段追加 -栏目id， 避免不同栏目共用同一目录导致模板文件互相覆盖。
     *
     * @param columnId 栏目ID
     * @return 相对子目录（不含文件名），无法解析栏目时返回 column-栏目id
     */
    public String buildContentTemplateDir(Long columnId) {
        if (columnId == null) {
            return "column-null";
        }
        OneshotEntity column = findColumn(columnId);
        if (column == null) {
            return "column-" + columnId;
        }
        Map<Integer, OneshotEntity> columnsById = new HashMap<>();
        columnsById.put(column.getId(), column);
        String base = namePath(column, columnsById);
        if (base.isEmpty()) {
            // 站点根栏目：不加子目录
            return "";
        }
        if (hasPathCollision(column, base, columnsById)) {
            return appendLeafSuffix(base, "-" + column.getId());
        }
        return base;
    }

    /**
     * 按 id 读取栏目实体，供管理端生成目录使用。
     *
     * <p>不经过前台可见性过滤（has_front_channel / is_system），仅排除已删除栏目， 避免管理端栏目因不满足前台可见条件而被解析为 null，导致目录回退为
     * column-id。
     */
    private OneshotEntity findColumn(Long columnId) {
        if (columnId == null || oneShotMapper == null) {
            return null;
        }
        try {
            return oneShotMapper.getModelById(columnId.intValue());
        } catch (Exception e) {
            LOG.warn("读取栏目失败: columnId={}", columnId, e);
            return null;
        }
    }

    /**
     * 名称路径：沿栏目树逐级取英文名拼接（不含去重后缀）。
     *
     * <p>目录不包含站点根栏目：站点根栏目（其 parentId 为 0/空，或 id 等于 siteRootId）不参与拼接。
     */
    private String namePath(OneshotEntity column, Map<Integer, OneshotEntity> columnsById) {
        List<String> segments = new ArrayList<>();
        Integer rootId = column.getSiteRootId();
        OneshotEntity current = column;
        int depth = 0;
        while (current != null && depth++ < 20) {
            Integer parentId = current.getParentId();
            boolean isSiteRoot =
                    (rootId != null && rootId.equals(current.getId()))
                            || parentId == null
                            || parentId == 0;
            if (!isSiteRoot) {
                String segment = columnDirSegment(current);
                if (!segment.isBlank()) {
                    segments.add(0, segment);
                }
            }
            if (parentId == null || parentId == 0 || parentId.equals(current.getId())) {
                break;
            }
            if (!columnsById.containsKey(parentId)) {
                columnsById.put(parentId, findColumn(Long.valueOf(parentId)));
            }
            OneshotEntity parent = columnsById.get(parentId);
            if (parent == null
                    || parent.getId() == null
                    || parent.getId().equals(current.getId())) {
                break;
            }
            current = parent;
        }
        if (segments.isEmpty()) {
            // 站点根栏目：目录不含任何层级
            return "";
        }
        return String.join("/", segments);
    }

    /** 判断同一站点内是否存在其它栏目的名称路径与本栏目相同（用于目录去重）。 */
    private boolean hasPathCollision(
            OneshotEntity column, String base, Map<Integer, OneshotEntity> columnsById) {
        Long siteId = column.getSiteProjectId();
        if (siteId == null || oneShotMapper == null) {
            return false;
        }
        List<OneshotEntity> siteMenus;
        try {
            siteMenus = oneShotMapper.selectBySiteProjectId(siteId);
        } catch (Exception e) {
            LOG.warn("目录去重查询站点栏目失败: siteId={}", siteId, e);
            return false;
        }
        if (siteMenus == null) {
            return false;
        }
        // 复用本次查询的全站栏目，避免去重时对每条祖先路径重复访问数据库。
        for (OneshotEntity menu : siteMenus) {
            if (menu != null && menu.getId() != null) {
                columnsById.putIfAbsent(menu.getId(), menu);
            }
        }
        for (OneshotEntity other : siteMenus) {
            if (other == null || other.getId() == null || other.getId().equals(column.getId())) {
                continue;
            }
            if (base.equals(namePath(other, columnsById))) {
                return true;
            }
        }
        return false;
    }

    /** 给路径的最后一段追加后缀（如 -1001）。 */
    private String appendLeafSuffix(String path, String suffix) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            return path + suffix;
        }
        return path.substring(0, idx + 1) + path.substring(idx + 1) + suffix;
    }

    /** 单个栏目的目录段：取栏目名(className)（中文转拼音、英文原样），ASCII 化；为空时回退 column-栏目id。 */
    private String columnDirSegment(OneshotEntity column) {
        if (column == null) {
            return "";
        }
        String sanitized = sanitizeDirSegment(columnEnglishName(column));
        if (sanitized.isBlank()) {
            return "column-" + column.getId();
        }
        // 避免与公共模板目录 inc 冲突
        if ("inc".equals(sanitized)) {
            return sanitized + "-" + column.getId();
        }
        return sanitized;
    }

    /** 栏目名转英文：栏目名(className)优先（中文转拼音全拼、英文原样），为空时回退 classEnName。 */
    private String columnEnglishName(OneshotEntity column) {
        if (column == null) {
            return "";
        }
        String name = column.getClassName();
        if (name != null && !name.isBlank()) {
            return toEnglishName(name);
        }
        String en = column.getClassEnName();
        return en == null ? "" : en;
    }

    /** 名称转英文：含中文取拼音全拼；其他语言（含英文）原样返回。 */
    private String toEnglishName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        for (char c : name.toCharArray()) {
            if (isChineseCharacter(c)) {
                String full = toPinyinFull(name);
                return full.isBlank() ? toPinyinAbbreviation(name) : full;
            }
        }
        return name;
    }

    /** 中文转拼音全拼（小写、无声调），保留其中的字母数字；例："新闻中心" -> "xinwenzhongxin"。 */
    private String toPinyinFull(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return "";
        }
        try {
            HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
            format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
            format.setVCharType(HanyuPinyinVCharType.WITH_V);

            StringBuilder sb = new StringBuilder();
            for (char c : chinese.toCharArray()) {
                if (Character.isWhitespace(c)) {
                    continue;
                }
                if (isChineseCharacter(c)) {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        sb.append(pinyinArray[0]);
                    }
                } else if (Character.isLetterOrDigit(c)) {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("拼音转换失败: {}", chinese, e);
            return "";
        }
    }

    /** 目录段 ASCII 化：仅保留字母/数字，其余替换为 -，并去掉首尾 -。 */
    private String sanitizeDirSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** 构建模板识别 Prompt（公开方法，供前端获取显示） */
    public String buildPrompt(
            Long siteId,
            String filePath,
            Long columnId,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter) {
        return buildPrompt(
                siteId,
                filePath,
                columnId,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                null,
                null);
    }

    /** 构建模板识别 Prompt（带选中栏目列表） */
    public String buildPrompt(
            Long siteId,
            String filePath,
            Long columnId,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns) {
        return buildPrompt(
                siteId,
                filePath,
                columnId,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                selectedColumns,
                null);
    }

    /** 构建模板识别 Prompt（带选中栏目列表和布局） */
    public String buildPrompt(
            Long siteId,
            String filePath,
            Long columnId,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns,
            String layoutId) {
        return buildPrompt(
                siteId,
                filePath,
                columnId,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                selectedColumns,
                layoutId,
                null);
    }

    /** 构建模板识别 Prompt（带选中栏目列表、布局和预览URL） */
    public String buildPrompt(
            Long siteId,
            String filePath,
            Long columnId,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns,
            String layoutId,
            String previewUrl) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取原始文件内容
        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            throw new IllegalArgumentException("无法读取文件内容");
        }

        // 获取栏目信息
        OneshotEntity column = findColumn(columnId);
        String columnName = column != null ? column.getClassName() : "栏目" + columnId;
        String columnEnName = columnEnglishName(column);
        if (columnEnName.isBlank()) {
            columnEnName = toPinyinAbbreviation(columnName);
        }

        return buildContentTemplatePrompt(
                siteId,
                htmlContent,
                filePath,
                column,
                columnEnName,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                selectedColumns,
                layoutId,
                previewUrl);
    }

    /** 构建公共模板识别 Prompt（公开方法，供前端获取显示） */
    public String buildCommonTemplatePromptPublic(
            Long siteId, String filePath, String templateName) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取原始文件内容
        String htmlContent =
                storageService.getFileContent(
                        site.getProjectCode(), site.getUiPackageVersion(), filePath);
        if (htmlContent == null || htmlContent.isBlank()) {
            throw new IllegalArgumentException("无法读取文件内容");
        }

        return buildCommonTemplatePrompt(siteId, htmlContent, filePath, templateName, null);
    }

    /** 构建公共模板识别 Prompt（使用前端传递的源代码内容） */
    public String buildCommonTemplatePromptWithContent(
            Long siteId,
            String htmlContent,
            String filePath,
            String templateName,
            List<Map<String, Object>> selectedColumns) {
        return buildCommonTemplatePrompt(
                siteId, htmlContent, filePath, templateName, selectedColumns);
    }

    /** 保存模板内容 */
    public String saveTemplateContent(Long siteId, String templatePath, String content) {
        SiteProjectEntity site = requireSite(siteId);
        return saveTemplate(site.getProjectCode(), templatePath, content);
    }

    /** 获取模板内容 */
    public Optional<String> getTemplateContent(Long siteId, String templatePath) {
        SiteProjectEntity site = requireSite(siteId);
        Path root = getTemplateDir(site.getProjectCode()).toAbsolutePath().normalize();
        Path templateFile = root.resolve(templatePath).normalize();
        if (!templateFile.startsWith(root)) throw new IllegalArgumentException("模板路径超出站点目录");
        try {
            if (Files.exists(templateFile)
                    && !templateFile.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("模板路径超出站点目录");
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法校验模板路径", error);
        }

        if (!Files.isRegularFile(templateFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readString(templateFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.error("读取模板文件失败: {}", templatePath, e);
            return Optional.empty();
        }
    }

    private String buildCommonTemplatePrompt(
            Long siteId,
            String htmlContent,
            String filePath,
            String templateName,
            List<Map<String, Object>> selectedColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析参考页面，将其转换为 Thymeleaf 模板。\n\n");
        sb.append("模板类型: ").append(templateName).append("\n");
        sb.append("文件路径: ").append(filePath).append("\n\n");

        // 添加选中的栏目信息（包含上下级关系）
        if (selectedColumns != null && !selectedColumns.isEmpty()) {
            sb.append("【应用栏目】\n");
            sb.append("此模板将应用于以下栏目：\n");
            // 一次性批量取回所有选中栏目及其祖先，避免逐列/逐级查询造成 N+1（远程 Redis/DB 往返）
            List<Integer> selectedColumnIds = new ArrayList<>();
            for (Map<String, Object> col : selectedColumns) {
                if (col.get("columnId") != null) {
                    selectedColumnIds.add(Long.valueOf(col.get("columnId").toString()).intValue());
                }
            }
            Map<Integer, OneshotEntity> columnEntityMap =
                    loadColumnEntitiesWithAncestors(selectedColumnIds);
            for (Map<String, Object> col : selectedColumns) {
                Long colId = Long.valueOf(col.get("columnId").toString());
                String colName = (String) col.get("columnName");
                // 层级路径与 bizcode 均来自上方批量查询结果
                String hierarchyPath = buildHierarchyPathFromMap(colId.intValue(), columnEntityMap);
                OneshotEntity colEntity = columnEntityMap.get(colId.intValue());
                String bizcode = colEntity != null ? colEntity.getBizCode() : null;
                sb.append("- 栏目ID: ").append(colId).append(", 名称: ").append(colName);
                if (bizcode != null && !bizcode.isBlank()) {
                    sb.append(", bizcode: ").append(bizcode);
                }
                if (hierarchyPath != null && !hierarchyPath.isEmpty()) {
                    sb.append(", 层级路径: ").append(hierarchyPath);
                }
                sb.append("\n");
                // 栏目字段配置说明（单篇/列表由实体 pageType 推导）
                if (colEntity != null) {
                    int colPageType =
                            colEntity.getPageType() != null && colEntity.getPageType() == 0 ? 0 : 1;
                    String enabledFieldsLine = buildEnabledFieldsLine(colEntity, colPageType);
                    if (enabledFieldsLine != null) {
                        sb.append(enabledFieldsLine).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        sb.append("【核心原则 - 必须遵守】\n");
        sb.append("1. ❌ 禁止写死静态资源：图片路径、视频地址、关键文本、链接等必须从数据中读取\n");
        sb.append("2. ✅ 使用 CommonUtils 方法动态获取数据\n");
        sb.append("3. ✅ 保持原文语言，不要翻译中英文\n");
        sb.append("4. ✅ 模板必须能结合实际 CMS 数据正常渲染\n");
        sb.append("5. ✅ 模板必须结合栏目定义与栏目启用字段生成模版内容\n");
        sb.append(
                "6. ✅ 获取栏目对象必须优先使用 CommonUtils.getOneshotEntityByBizcode(bizcode, siteId)（bizcode"
                        + " 见下方【应用栏目】），多站点下稳定；仅当该栏目无 bizcode 时才退回 getOneshotEntityById(id)\n\n");

        sb.append("【可用变量】\n");
        sb.append("- siteId: 站点ID（自动可用）\n");
        sb.append("- siteConfig: 站点配置对象，字段包括 projectName, projectCode 等\n");
        sb.append("- site: 站点配置对象（与 siteConfig 相同）\n\n");

        sb.append("【静态资源路径转换规则 - ⚠️ 必须遵守】\n");
        sb.append("所有静态资源引用必须转换为站点 UI 服务的绝对路径：\n");
        sb.append(
                "- <script src=\"xxx\"> → <script th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <link href=\"xxx\"> → <link th:href=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <img src=\"xxx\"> → <img th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <video src=\"xxx\"> → <video th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append("⚠️ 重要：路径 xxx 必须保持原样不变，只改变标签写法！\n");
        sb.append("示例:\n");
        sb.append(
                "  原: <link href=\"css/style.css\"> → 转换: <link"
                        + " th:href=\"${CommonUtils.resolveUiUrl('css/style.css', siteId)}\">\n");
        sb.append(
                "  原: <img src=\"images/logo.png\"> → 转换: <img"
                        + " th:src=\"${CommonUtils.resolveUiUrl('images/logo.png', siteId)}\">\n");
        sb.append(
                "  原: <script src=\"js/common.js\"> → 转换: <script"
                        + " th:src=\"${CommonUtils.resolveUiUrl('js/common.js',"
                        + " siteId)}\"></script>\n");
        sb.append(
                "  原: style=\"background-image: url('images/bg.jpg');\" → 转换:"
                        + " th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n");
        sb.append("  ⚠️ 内联样式中的路径：th:style 的值是字符串拼接，必须用 ${} 包裹方法调用！\n");
        sb.append(
                "  ❌ 错误: th:style=\"'background-image: url(' +"
                        + " CommonUtils.resolveUiUrl('images/bg.jpg', siteId) + ');'\"\n");
        sb.append(
                "  ✅ 正确: th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n\n");

        sb.append("【常用方法 - ⚠️ 重要】\n");
        appendBizcodeMethodsDoc(sb);
        sb.append("1. 栏目获取方法：\n");
        sb.append(
                "   - CommonUtils.getOneshotEntityByBizcode(bizcode, siteId) - 【优先使用】根据 bizcode"
                        + " 与当前站点ID获取栏目实体\n");
        sb.append("     用途：多站点下稳定获取当前站点对应栏目，生成导航链接、栏目名称等\n");
        sb.append(
                "     示例：th:with=\"column=${CommonUtils.getOneshotEntityByBizcode('1001',"
                        + " siteId)}\"\n\n");
        sb.append(
                "   - CommonUtils.getOneshotEntityById(id) - 根据ID获取单个栏目实体（仅在本站点上下文且无 bizcode"
                        + " 时的兜底）\n");
        sb.append("     用途：获取指定栏目信息，用于生成导航链接、栏目名称等\n");
        sb.append("     示例：th:with=\"column=${CommonUtils.getOneshotEntityById(1)}\"\n\n");
        sb.append("   - CommonUtils.getOneshotListByParentId(parentId) - 获取指定父栏目下的子栏目列表\n");
        sb.append("     用途：生成多级导航菜单、侧边栏栏目列表\n");
        sb.append(
                "     示例：th:with=\"subColumns=${CommonUtils.getOneshotListByParentId(parentId)}\"\n\n");
        sb.append("   ⚠️ 栏目实体常用属性：\n");
        sb.append("   - entity.className - 获取栏目名称（如\"关于我们\"、\"产品中心\"）\n");
        sb.append("   - entity.id - 获取栏目ID\n");
        sb.append("   - 示例：${nav1.className} 获得栏目名称\n\n");
        sb.append("2. 链接获取方法：\n");
        sb.append("   - CommonUtils.getListUrl(entity) - 获取栏目一级页面链接（列表页/主页）\n");
        sb.append("     用途：栏目导航链接、面包屑导航、更多链接\n");
        sb.append("     示例：th:href=\"${CommonUtils.getListUrl(column)}\"\n\n");
        sb.append("   - CommonUtils.getDetailUrl(entity) - 获取栏目二级页面链接（详情页）\n");
        sb.append("     用途：内容详情链接、文章链接\n");
        sb.append("     示例：th:href=\"${CommonUtils.getDetailUrl(content)}\"\n\n");
        sb.append("3. 内容获取方法：\n");
        sb.append("   - CommonUtils.getContentListByClassId(classId, limit) - 获取指定栏目的内容列表\n");
        sb.append("     用途：文章列表、新闻列表、图片列表\n");
        sb.append(
                "     示例：th:with=\"list=${CommonUtils.getContentListByClassId(columnId,"
                        + " 10)}\"\n\n");
        sb.append(
                "   - CommonUtils.getContentListByParentId(parentId, limit, setting) -"
                        + " 按推荐位筛选指定父栏目下的内容列表\n");
        sb.append("     ⚠️ 第三个参数 setting 为内容推荐位编号（决定只取哪些被标记为置顶/热门/推荐的内容）：\n");
        sb.append("        8 首页置顶、9 首页热门、10 首页推荐、11 频道置顶、12 频道热门、13 频道推荐、\n");
        sb.append("        14 栏目置顶、15 栏目热门、16 栏目推荐、17 导航推荐、18 右侧推荐、19 左侧推荐\n");
        sb.append("        传 0 表示不按推荐位过滤，返回全部内容\n");
        sb.append(
                "     示例：th:with=\"topList=${CommonUtils.getContentListByParentId(parentId, 5,"
                        + " 8)}\"（取该父栏目下【首页置顶】前 5 条）\n\n");
        sb.append("4. 资源路径方法：\n");
        sb.append("   - CommonUtils.resolveUiUrl(path, siteId) - 转换静态资源路径\n");
        sb.append("     用途：图片、视频、CSS、JS等静态资源路径转换\n\n");

        sb.append("【图片/视频字段转换】\n");
        sb.append("- 图片字段(photo1~photo15)必须用 resolveUiUrl 转换:\n");
        sb.append("  <img th:src=\"${CommonUtils.resolveUiUrl(item.photo1, siteId)}\"/>\n");
        sb.append("- 视频字段(media1~media4)必须用 resolveUiUrl 转换:\n");
        sb.append("  <video th:src=\"${CommonUtils.resolveUiUrl(item.media1, siteId)}\"/>\n\n");

        sb.append("【示例转换】\n");
        sb.append("原: <a href=\"/about.html\">关于我们</a>\n");
        sb.append(
                "转换: <a th:with=\"item=${CommonUtils.getOneshotEntityByBizcode('1001', siteId)}\""
                        + " ");
        sb.append(
                "th:href=\"${CommonUtils.getListUrl(item)}\""
                        + " th:text=\"${item.className}\"></a>\n\n");

        // 构建参考页面 URL
        String accessUrl = uiPackageService.getAccessUrl(siteId);
        String referenceUrl = null;
        if (accessUrl != null && !accessUrl.isBlank()) {
            String baseUrl =
                    accessUrl.endsWith("/")
                            ? accessUrl.substring(0, accessUrl.length() - 1)
                            : accessUrl;
            // 将 localhost 替换为 127.0.0.1
            baseUrl = baseUrl.replace("localhost", "127.0.0.1");
            referenceUrl = "view-source:" + baseUrl + "/" + filePath;
        }

        if (referenceUrl != null && !referenceUrl.isEmpty()) {
            sb.append("【参考页面】\n").append(referenceUrl).append("\n\n");
            sb.append("【结构要求 - ⚠️ 极其重要】\n");
            sb.append("1. 生成的模板必须与参考页面的 div 结构完全一致\n");
            sb.append("2. 所有 classname 必须与参考页面保持一致，不能修改或省略\n");
            sb.append("3. 所有样式细节（CSS class、内联样式）必须完全复制，不要新增样式,如 th:style\n");
            sb.append("4. 不要简化或合并任何 div 结构，保持原有层级嵌套\n");
            sb.append("5. 只将静态内容替换为动态变量，结构框架不变\n\n");
        }
        {
            // 暂时不把 HTML 源码放进 prompt；需要恢复时取消注释。
            // sb.append("【HTML 源代码】\n```\n").append(htmlContent).append("\n```\n\n");
        }

        sb.append("【输出要求 - 必须严格遵守】\n");
        sb.append("⚠️ 重要：仅仅只返回识别生成的模板代码，不要返回任何其他说明文字或解释！\n\n");
        sb.append("1. 响应中只能包含 HTML 模板代码，不要添加任何说明文字\n");
        sb.append("2. 模板代码必须用 ```html 代码块包裹\n");
        sb.append("3. 不要只返回保存路径或摘要信息\n");
        sb.append("4. 直接输出完整的模板代码，让用户可以预览和编辑\n");
        sb.append("5. ⚠️ 禁止使用省略写法如 \"...and N more lines\" 或 \"entire template omitted\"\n");
        sb.append("6. ⚠️ 必须逐行输出完整代码，无论模板有多长\n\n");

        sb.append("请返回转换后的完整 Thymeleaf 模板代码（完整输出，禁止省略，禁止添加说明文字）。");
        return sb.toString();
    }

    private String buildContentTemplatePrompt(
            Long siteId,
            String htmlContent,
            String filePath,
            OneshotEntity column,
            String columnEnName,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter) {
        return buildContentTemplatePrompt(
                siteId,
                htmlContent,
                filePath,
                column,
                columnEnName,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                null,
                null,
                null);
    }

    private String buildContentTemplatePrompt(
            Long siteId,
            String htmlContent,
            String filePath,
            OneshotEntity column,
            String columnEnName,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns) {
        return buildContentTemplatePrompt(
                siteId,
                htmlContent,
                filePath,
                column,
                columnEnName,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                selectedColumns,
                null,
                null);
    }

    private String buildContentTemplatePrompt(
            Long siteId,
            String htmlContent,
            String filePath,
            OneshotEntity column,
            String columnEnName,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns,
            String layoutId) {
        return buildContentTemplatePrompt(
                siteId,
                htmlContent,
                filePath,
                column,
                columnEnName,
                templateType,
                includeKey,
                includeHeader,
                includeFooter,
                selectedColumns,
                layoutId,
                null);
    }

    private String buildContentTemplatePrompt(
            Long siteId,
            String htmlContent,
            String filePath,
            OneshotEntity column,
            String columnEnName,
            String templateType,
            boolean includeKey,
            boolean includeHeader,
            boolean includeFooter,
            List<Map<String, Object>> selectedColumns,
            String layoutId,
            String previewUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的 CMS 模板开发工程师。请分析参考页面，将其转换为 Thymeleaf 动态模板。\n\n");

        Long columnId = column != null ? column.getId().longValue() : 0L;
        String templateTypeLabel = getTemplateTypeLabel(templateType);

        sb.append("【基本信息】\n");
        sb.append("- 模板类型: ").append(templateTypeLabel).append("\n");
        sb.append("- 源文件: ").append(filePath).append("\n");
        sb.append("- 栏目 ID: ").append(columnId).append("\n");
        sb.append("- 栏目名称: ").append(column != null ? column.getClassName() : "未知栏目").append("\n");
        if (column != null && column.getBizCode() != null && !column.getBizCode().isBlank()) {
            sb.append("- 栏目 bizcode: ")
                    .append(column.getBizCode())
                    .append("（获取本栏目对象请优先用 CommonUtils.getOneshotEntityByBizcode('")
                    .append(column.getBizCode())
                    .append("', siteId)）\n");
        }
        // sb.append("- 输出目录:
        // template/web/").append(columnEnName).append("-").append(columnId).append("/\n\n");

        // 添加栏目层级结构
        appendColumnHierarchy(sb, column);

        // 添加选中的栏目列表
        if (selectedColumns != null && !selectedColumns.isEmpty()) {
            appendSelectedColumnsConfig(sb, selectedColumns, layoutId);
        }

        sb.append("【核心原则 - 必须遵守】\n");
        sb.append("1. ❌ 禁止写死静态资源：图片路径、视频地址、关键文本、链接等必须从内容数据中读取\n");
        sb.append("2. ✅ 使用 CommonUtils 方法动态获取数据\n");
        sb.append("3. ✅ 保持原文语言，不要翻译中英文\n");
        sb.append("4. ✅ 模板必须结合实际 CMS 数据正常渲染\n");
        sb.append("5. ✅ 模板必须结合栏目定义与栏目启用字段生成模版内容\n");
        sb.append(
                "6. ✅ 获取栏目对象必须优先使用 CommonUtils.getOneshotEntityByBizcode(bizcode, siteId)，bizcode"
                        + " 见【基本信息】与【栏目与区域设置】；仅当该栏目无 bizcode 时才退回"
                        + " CommonUtils.getOneshotEntityById(id)。多站点下直接写死 id 会取到其他站点的栏目\n\n");

        sb.append("【图片/视频字段必须使用 resolveUiUrl 转换】\n");
        sb.append("photo1~photo15、media1~media4 字段存储的是 ui: 格式的路径，必须转换：\n");
        sb.append("❌ 错误写法: <img th:src=\"${itemEach.photo1}\"/>\n");
        sb.append(
                "✅ 正确写法: <img th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n");
        sb.append("❌ 错误写法: <video th:src=\"${itemEach.media1}\"/>\n");
        sb.append(
                "✅ 正确写法: <video th:src=\"${CommonUtils.resolveUiUrl(itemEach.media1,"
                        + " siteId)}\"/>\n");
        sb.append("注意：siteId 变量在模板上下文中自动可用\n\n");

        sb.append("【CommonUtils 完整方法列表】\n");
        sb.append("⚠️ 重要：只能使用以下列出的方法，禁止使用其他任何未列出的方法！\n");
        sb.append("禁止猜测或发明不存在的方法，如 getxxx、findxxx 等。\n\n");
        sb.append("⚠️ 正确的使用方式：使用 ${CommonUtils.方法名(...)} 而不是 @CommonUtils\n");
        sb.append("❌ 错误写法: th:src=\"${@CommonUtils.resolveUiUrl(...)}\"\n");
        sb.append("✅ 正确写法: th:src=\"${CommonUtils.resolveUiUrl(...)}\"\n\n");
        sb.append("⚠️ SpEL 表达式规则：在 ${} 内部，不要再使用 ${} 嵌套调用方法\n");
        sb.append(
                "❌ 错误写法: th:text=\"${item.title} + ${CommonUtils.getDateString(item.createTime,"
                        + " 'yyyy-MM-dd')}\"\n");
        sb.append(
                "✅ 正确写法: th:text=\"${item.title + ' ' + CommonUtils.getDateString(item.createTime,"
                        + " 'yyyy-MM-dd')}\"\n");
        sb.append("❌ 错误写法: th:src=\"${${CommonUtils.resolveUiUrl(item.photo1, siteId)}}\"\n");
        sb.append("✅ 正确写法: th:src=\"${CommonUtils.resolveUiUrl(item.photo1, siteId)}\"\n\n");
        appendBizcodeMethodsDoc(sb);
        sb.append("一、栏目/菜单数据读取\n");
        sb.append(
                "- CommonUtils.getOneshotEntityByBizcode(bizcode, siteId) → 【优先】根据 bizcode"
                        + " 与当前站点ID获取栏目对象(OneshotEntity)，多站点下稳定\n");
        sb.append(
                "- CommonUtils.getOneshotEntityById(id) →"
                        + " 根据ID获取单个栏目对象(OneshotEntity)（仅本站点上下文有效，多站点场景请优先用上面的 bizcode 方法）\n");
        sb.append("- CommonUtils.getOneshotListByParentId(parentId) → 获取子栏目列表\n");
        sb.append("- CommonUtils.getOneshotListByParentId(parentId, limit) → 获取子栏目列表(限制数量)\n");
        sb.append(
                "- CommonUtils.getOneshotEntityByParentIdAndIdentifies(parentId, identifies) →"
                        + " 根据父栏目ID和唯一标识获取栏目\n\n");

        sb.append("二、内容数据读取\n");
        sb.append("- CommonUtils.getContentEntityById(id) → 根据ID获取单篇内容(ContentEntity)\n");
        sb.append("- CommonUtils.getContentEntityById(classId, id) → 根据栏目ID和内容ID获取内容\n");
        sb.append("- CommonUtils.getContentListByClassId(classId, limit) → 根据栏目ID获取内容列表\n");
        sb.append(
                "- CommonUtils.getContentListByClassId(classId, limit, setting) → 同上，第三参数 setting"
                        + " 为推荐位编号（语义见下方 getContentListByParentId）\n");
        sb.append(
                "- CommonUtils.getContentListByParentId(parentId, limit) → 根据父栏目ID获取内容列表（不限推荐位）\n");
        sb.append(
                "- CommonUtils.getContentListByParentId(parentId, limit, setting) →"
                        + " 按推荐位筛选指定父栏目下的内容列表\n");
        sb.append(
                "  ⚠️ 第三个参数 setting = 内容推荐位编号：8 首页置顶、9 首页热门、10 首页推荐、11 频道置顶、12 频道热门、13 频道推荐、14"
                        + " 栏目置顶、15 栏目热门、16 栏目推荐、17 导航推荐、18 右侧推荐、19 左侧推荐；传 0 表示不按推荐位过滤（返回全部）\n");
        sb.append(
                "  示例：th:with=\"topList=${CommonUtils.getContentListByParentId(parentId, 5,"
                        + " 8)}\"（取该父栏目下【首页置顶】前 5 条）\n");
        sb.append("- CommonUtils.getContentHotListByClassId(classId, limit) → 获取热门内容列表\n");
        sb.append("- CommonUtils.getContentPagerByClassId(view.idEntity) → 获取分页数据对象(Pager)\n\n");

        sb.append("三、URL生成\n");
        sb.append("- CommonUtils.getListUrl(oneshotEntity) → 获取栏目列表页URL\n");
        sb.append("- CommonUtils.getDetailUrl(contentEntity) → 获取内容详情页URL\n");
        sb.append("- CommonUtils.getBreadcrumb(classId) → 获取面包屑导航HTML\n\n");

        sb.append("四、工具方法\n");
        sb.append("- CommonUtils.getDateString(date, 'yyyy-MM-dd') → 格式化日期\n");
        sb.append("- CommonUtils.lineToBr(text) → 换行符转<br/>\n");
        sb.append("- CommonUtils.getBannerEntity(bannerId) → 获取Banner对象\n\n");

        sb.append("五、资源路径转换（⚠️ 必须用于图片/视频/静态资源）\n");
        sb.append("- CommonUtils.resolveUiUrl(path, siteId) → 转换 ui: 前缀路径为可访问URL\n");
        sb.append("  示例: ${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\n");
        sb.append("  用于: photo1~photo15 图片字段、media1~media4 视频字段\n\n");
        sb.append("【静态资源路径转换规则】\n");
        sb.append("所有静态资源引用必须转换为站点 UI 服务的绝对路径：\n");
        sb.append(
                "- <script src=\"xxx\"> → <script th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <link href=\"xxx\"> → <link th:href=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <img src=\"xxx\"> → <img th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <video src=\"xxx\"> → <video th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append("⚠️ 重要：路径 xxx 必须保持原样不变，只改变标签写法！\n");
        sb.append("示例:\n");
        sb.append(
                "  原: <link href=\"css/style.css\"> → 转换: <link"
                        + " th:href=\"${CommonUtils.resolveUiUrl('css/style.css', siteId)}\">\n");
        sb.append(
                "  原: <img src=\"images/logo.png\"> → 转换: <img"
                        + " th:src=\"${CommonUtils.resolveUiUrl('images/logo.png', siteId)}\">\n");
        sb.append(
                "  原: <script src=\"js/common.js\"> → 转换: <script"
                        + " th:src=\"${CommonUtils.resolveUiUrl('js/common.js',"
                        + " siteId)}\"></script>\n");
        sb.append(
                "  原: style=\"background-image: url('images/bg.jpg');\" → 转换:"
                        + " th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n");
        sb.append("  ⚠️ 内联样式中的路径：th:style 的值是字符串拼接，必须用 ${} 包裹方法调用！\n");
        sb.append(
                "  ❌ 错误: th:style=\"'background-image: url(' +"
                        + " CommonUtils.resolveUiUrl('images/bg.jpg', siteId) + ');'\"\n");
        sb.append(
                "  ✅ 正确: th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n\n");

        sb.append("六、SEO 变量（通过 view.seoEntity 访问）\n");
        sb.append("- ${view.seoEntity.title} → SEO标题\n");
        sb.append("- ${view.seoEntity.keywords} → SEO关键词\n");
        sb.append("- ${view.seoEntity.description} → SEO描述\n");
        sb.append("- ${view.seoEntity.siteName} → 站点名称\n");
        sb.append("  示例: <meta name=\"keywords\" content=\"${view.seoEntity.keywords}\"/>\n");
        sb.append("  示例: <title th:text=\"${view.seoEntity.title}\"></title>\n");
        sb.append("⚠️ 重要：必须使用 seoEntity，不是 seoInfo！\n");
        sb.append("  ❌ 错误: ${view.seoInfo.keywords}\n");
        sb.append("  ✅ 正确: ${view.seoEntity.keywords}\n\n");

        sb.append("【OneshotEntity 栏目对象字段】\n");
        sb.append("- className: 栏目名称\n");
        sb.append("- photo1~photo15: 图片字段(存储图片路径)\n");
        sb.append("- singleLine1~singleLine30: 单行文本字段\n");
        sb.append("- note1~note15: 多行文本字段\n");
        sb.append("- media1~media4: 视频字段(存储视频路径如mp4)\n\n");

        sb.append("【栏目类型与数据获取方法】\n");
        sb.append("根据栏目类型选择正确的数据获取方法：\n\n");
        sb.append("一、单篇栏目（pageType=0）\n");
        sb.append("  - 获取栏目内容（singleLine、photo、note等字段）:\n");
        sb.append(
                "    CommonUtils.getOneshotEntityByBizcode(栏目bizcode, siteId)  // 优先使用，bizcode"
                        + " 见【基本信息】/【栏目与区域设置】\n");
        sb.append(
                "  - 示例: th:with=\"column=${CommonUtils.getOneshotEntityByBizcode(栏目bizcode,"
                        + " siteId)}\"\n");
        sb.append("  - 使用: ${column.singleLine1}、${column.photo1}、${column.note1} 等\n\n");
        sb.append("二、列表栏目（pageType=1）\n");
        sb.append("  - 获取内容列表（每条内容的 singleLine、photo、note等字段）:\n");
        sb.append("    CommonUtils.getContentListByClassId(栏目ID, 数量)\n");
        sb.append("  - 示例: th:with=\"list=${CommonUtils.getContentListByClassId(栏目ID, 10)}\"\n");
        sb.append(
                "  - 使用: th:each=\"itemEach:${list}\" 然后 ${itemEach.title}、${itemEach.photo1} 等\n");
        sb.append("  - 分页: CommonUtils.getContentPagerByClassId(view.idEntity) 返回 Pager 对象\n\n");
        sb.append("三、获取栏目基本信息（栏目名称、栏目ID）\n");
        sb.append("  - 无论单篇还是列表栏目，都通过以下方法获取栏目对象:\n");
        sb.append(
                "    CommonUtils.getOneshotEntityByBizcode(栏目bizcode, siteId)  // 优先使用，bizcode"
                        + " 见【基本信息】/【栏目与区域设置】\n");
        sb.append("  - 示例: ${column.className} 获取栏目名称, ${column.id} 获取栏目ID\n\n");

        sb.append("【ContentEntity 内容对象字段】\n");
        sb.append("- title: 标题\n");
        sb.append("- createTime: 创建时间\n");
        sb.append("- hits: 点击量\n");
        sb.append("- content1~content6: 正文内容(富文本)，用于填充 Div 标签的内容\n");
        sb.append("  ⚠️ 重要：content 字段是富文本HTML，必须使用 th:utext 渲染到标签内部\n");
        sb.append("  ✅ <div class=\"content\" th:utext=\"${itemContent.content1}\"></div>\n");
        sb.append("  ❌ 错误: th:text=\"${itemContent.content1}\" (会转义HTML标签)\n");
        sb.append("  ❌ 错误: <p th:each=\"p:${itemContent.content1}\"> (content不是数组)\n");
        sb.append("  ❌ 禁止使用 #strings.split 或其他分割函数处理 content 字段！\n");
        sb.append("     content 是完整的 HTML 片段，直接用 th:utext 输出到 div 内部即可。\n\n");
        sb.append("- note1~note15: 摘要/简介等多行文本\n");
        sb.append("- singleLine1~singleLine20: 单行文本(如来源、作者、链接等)\n");
        sb.append("- photo1~photo15: 图片字段(存储图片路径)\n");
        sb.append("- media1~media4: 视频字段(存储视频路径)\n\n");

        sb.append("【字段类型与转换规则 - ⚠️ 重要】\n");
        sb.append("不同类型字段需要不同的处理方式：\n\n");
        sb.append("一、photo1~photo15、media1~media4 字段（存储 ui:/ 或 assets:/ 引用）\n");
        sb.append("  必须通过 CommonUtils.resolveUiUrl 转换：\n");
        sb.append("  ✅ <img th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n");
        sb.append("  ✅ <a th:href=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n\n");
        sb.append("二、singleLine1~singleLine20、note1~note15 字段（存储普通文本）\n");
        sb.append("  直接显示，不需要 resolveUiUrl 转换：\n");
        sb.append("  ✅ <span th:text=\"${itemEach.singleLine1}\"/> (链接地址、来源、作者等)\n");
        sb.append("  ✅ <a th:href=\"${itemEach.singleLine2}\">链接</a> (链接字段)\n");
        sb.append("  ❌ 错误：对 singleLine/note 字段使用 resolveUiUrl 转换\n\n");

        sb.append("【三目运算符与空值判断 - ⚠️ 重要】\n");
        sb.append("photo、singleLine、media、note 等字段都是字符串类型，不是布尔类型！\n");
        sb.append("在 Thymeleaf 三目运算符中判断这些字段时，必须进行空值比较：\n\n");
        sb.append("❌ 错误写法（直接当作布尔值）:\n");
        sb.append(
                "  ${itemEach.photo7 ? CommonUtils.resolveUiUrl(itemEach.photo7, siteId) :"
                        + " null}\n");
        sb.append("  ${itemEach.singleLine1 ? itemEach.singleLine1 : '默认值'}\n\n");
        sb.append("✅ 正确写法（判断是否为空）:\n");
        sb.append(
                "  ${#strings.isEmpty(itemEach.photo7) ? null :"
                        + " CommonUtils.resolveUiUrl(itemEach.photo7, siteId)}\n");
        sb.append(
                "  ${itemEach.photo7 != null && !itemEach.photo7.isEmpty() ?"
                        + " CommonUtils.resolveUiUrl(itemEach.photo7, siteId) : null}\n");
        sb.append("  ${#strings.isEmpty(itemEach.singleLine1) ? '默认值' : itemEach.singleLine1}\n\n");
        sb.append("推荐使用 Thymeleaf 内置的 #strings.isEmpty() 方法判断字符串是否为空或空白。\n\n");

        sb.append("【公共模板引入】\n");
        if (includeKey) {
            sb.append("- 必须在 <head> 中引入: <th:block th:include=\"${'inc/key'}\"></th:block>\n");
        } else {
            sb.append("- 不引入 inc/key (用户未勾选)\n");
        }
        if (includeHeader) {
            sb.append("- 必须在 <body> 开头引入: <th:block th:include=\"${'inc/header'}\"></th:block>\n");
        } else {
            sb.append("- 不引入 inc/header (用户未勾选)\n");
        }
        if (includeFooter) {
            sb.append("- 必须在 </body> 前引入: <th:block th:include=\"${'inc/footer'}\"></th:block>\n");
        } else {
            sb.append("- 不引入 inc/footer (用户未勾选)\n");
        }
        sb.append("\n");

        if ("index".equals(templateType)) {
            // 首页模板示例
            sb.append("【首页模板完整示例】\n\n");
            sb.append("```html\n");
            sb.append("<!DOCTYPE html>\n");
            sb.append("<html xmlns:th=\"http://www.thymeleaf.org\">\n");
            sb.append("<head>\n");
            if (includeKey) {
                sb.append("    <th:block th:include=\"${'inc/key'}\"></th:block>\n");
            }
            sb.append("    <title th:text=\"${view.seoEntity.title}\"></title>\n");
            sb.append("</head>\n");
            sb.append("<body>\n");
            if (includeHeader) {
                sb.append("<th:block th:include=\"${'inc/header'}\"></th:block>\n");
            }
            sb.append("<div class=\"main\">\n");
            sb.append("    <!-- Banner 轮播 -->\n");
            sb.append(
                    "    <th:block"
                        + " th:replace=\"inc/banner::bannerFragment(${bannerClassId})\"></th:block>\n");
            sb.append("\n");
            sb.append("    <!-- 首页特色区域 - 使用栏目数据 -->\n");
            sb.append(
                    "    <section class=\"featured\""
                            + " th:with=\"column=${CommonUtils.getOneshotEntityByBizcode('1001',"
                            + " siteId)}\">\n");
            sb.append("        <h2 th:text=\"${column.className}\">栏目名称</h2>\n");
            sb.append("        <p th:utext=\"${CommonUtils.lineToBr(column.note1)}\">栏目简介</p>\n");
            sb.append("        <!-- 栏目图片 - 必须用 resolveUiUrl 转换 -->\n");
            sb.append(
                    "        <img th:if=\"${column.photo1}\""
                            + " th:src=\"${CommonUtils.resolveUiUrl(column.photo1, siteId)}\""
                            + " alt=\"\"/>\n");
            sb.append("    </section>\n");
            sb.append("\n");
            sb.append("    <!-- 内容列表 - 多个栏目内容 -->\n");
            sb.append(
                    "    <section class=\"news-list\""
                        + " th:with=\"pager=${CommonUtils.getContentPagerByClassId(view.idEntity)}\">\n");
            sb.append("        <ul>\n");
            sb.append("            <li th:each=\"itemEach:${pager.rows}\">\n");
            sb.append("                <!-- 图片必须用 resolveUiUrl 转换 -->\n");
            sb.append(
                    "                <img th:if=\"${itemEach.photo1}\""
                            + " th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\""
                            + " alt=\"\"/>\n");
            sb.append(
                    "                <h3><a th:href=\"${CommonUtils.getDetailUrl(itemEach)}\""
                            + " th:text=\"${itemEach.title}\"></a></h3>\n");
            sb.append(
                    "                <span class=\"date\""
                        + " th:text=\"${CommonUtils.getDateString(itemEach.createTime,'yyyy.MM.dd')}\"></span>\n");
            sb.append(
                    "                <p"
                            + " th:utext=\"${CommonUtils.lineToBr(itemEach.note1)}\"></p>\n");
            sb.append("            </li>\n");
            sb.append("        </ul>\n");
            sb.append("    </section>\n");
            sb.append("\n");
            sb.append("    <!-- 其他栏目内容 - 通过栏目ID获取 -->\n");
            sb.append(
                    "    <section class=\"other-news\""
                            + " th:with=\"otherList=${CommonUtils.getContentListByClassId(栏目ID,"
                            + " 5)}\">\n");
            sb.append("        <ul>\n");
            sb.append("            <li th:each=\"item:${otherList}\">\n");
            sb.append(
                    "                <a th:href=\"${CommonUtils.getDetailUrl(item)}\""
                            + " th:text=\"${item.title}\"></a>\n");
            sb.append("            </li>\n");
            sb.append("        </ul>\n");
            sb.append("    </section>\n");
            sb.append("</div>\n");
            if (includeFooter) {
                sb.append("<th:block th:include=\"${'inc/footer'}\"></th:block>\n");
            }
            sb.append("</body>\n");
            sb.append("</html>\n");
            sb.append("```\n\n");

            sb.append("【重要提醒 - 首页】\n");
            sb.append(
                    "- 首页通常展示多个栏目的内容，使用 CommonUtils.getContentListByClassId(栏目ID, 数量)"
                            + " 获取栏目对应的内容列表\n");
            sb.append("- CommonUtils.getOneshotListByParentId(parentId) 获取子栏目列表\n");
            sb.append(
                    "- 所有图片必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemEach.photo1,"
                            + " siteId)}\n");
            sb.append(
                    "- 所有视频必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemEach.media1,"
                            + " siteId)}\n");
            sb.append("- 请结合栏目定义与栏目启用字段生成模版内容\n\n");
        } else if ("list".equals(templateType)) {
            sb.append("【列表页模板完整示例】\n\n");
            sb.append("```html\n");
            sb.append("<!DOCTYPE html>\n");
            sb.append("<html xmlns:th=\"http://www.thymeleaf.org\">\n");
            sb.append("<head>\n");
            if (includeKey) {
                sb.append("    <th:block th:include=\"${'inc/key'}\"></th:block>\n");
            }
            sb.append("    <title th:text=\"${view.seoEntity.title}\"></title>\n");
            sb.append("</head>\n");
            sb.append("<body>\n");
            if (includeHeader) {
                sb.append("<th:block th:include=\"${'inc/header'}\"></th:block>\n");
            }
            sb.append("<div class=\"main\">\n");
            sb.append("    <!-- Banner -->\n");
            sb.append(
                    "    <th:block"
                        + " th:replace=\"inc/banner::bannerFragment(${bannerClassId})\"></th:block>\n");
            sb.append("\n");
            sb.append("    <!-- 面包屑导航 -->\n");
            sb.append(
                    "    <div class=\"breadcrumb\""
                        + " th:utext=\"${CommonUtils.getBreadcrumb(view.idEntity.classId)}\"></div>\n");
            sb.append("\n");
            sb.append("    <!-- 子栏目导航 -->\n");
            sb.append("    <div class=\"sub-nav\">\n");
            sb.append("        <ul>\n");
            sb.append(
                    "            <li"
                        + " th:each=\"item,status:${CommonUtils.getOneshotListByParentId(view.idEntity.parentId)}\"\n");
            sb.append(
                    "               "
                            + " th:class=\"${view.idEntity.classId==item.id?'active':''}\">\n");
            sb.append(
                    "                <a th:href=\"${CommonUtils.getListUrl(item)}\""
                            + " th:text=\"${item.className}\"></a>\n");
            sb.append("            </li>\n");
            sb.append("        </ul>\n");
            sb.append("    </div>\n");
            sb.append("\n");
            sb.append("    <!-- 内容列表 - 必须使用 pager -->\n");
            sb.append(
                    "    <div class=\"content-list\""
                        + " th:with=\"pager=${CommonUtils.getContentPagerByClassId(view.idEntity)}\">\n");
            sb.append("        <ul>\n");
            sb.append("            <li th:each=\"itemEach:${pager.rows}\">\n");
            sb.append("                <!-- 图片必须用 resolveUiUrl 转换 -->\n");
            sb.append(
                    "                <img th:if=\"${itemEach.photo1}\""
                            + " th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\""
                            + " alt=\"\"/>\n");
            sb.append("\n");
            sb.append("                <!-- 视频必须用 resolveUiUrl 转换 -->\n");
            sb.append(
                    "                <video th:if=\"${itemEach.media1}\""
                            + " th:src=\"${CommonUtils.resolveUiUrl(itemEach.media1,"
                            + " siteId)}\"></video>\n");
            sb.append("\n");
            sb.append(
                    "                <h3><a th:href=\"${CommonUtils.getDetailUrl(itemEach)}\""
                            + " th:text=\"${itemEach.title}\"></a></h3>\n");
            sb.append("\n");
            sb.append("                <!-- 时间 -->\n");
            sb.append(
                    "                <span class=\"date\""
                        + " th:text=\"${CommonUtils.getDateString(itemEach.createTime,'yyyy.MM.dd')}\"></span>\n");
            sb.append("\n");
            sb.append("                <!-- 摘要 -->\n");
            sb.append(
                    "                <p"
                            + " th:utext=\"${CommonUtils.lineToBr(itemEach.note1)}\"></p>\n");
            sb.append("\n");
            sb.append("                <!-- 其他文本字段 -->\n");
            sb.append(
                    "                <span th:if=\"${itemEach.singleLine3}\""
                            + " th:text=\"${itemEach.singleLine3}\"></span>\n");
            sb.append("            </li>\n");
            sb.append("            <li th:if=\"${pager.total==0}\">暂无内容</li>\n");
            sb.append("        </ul>\n");
            sb.append("\n");
            sb.append("        <!-- 分页 -->\n");
            sb.append("        <th:block th:utext=\"${pager.pageHtml}\"></th:block>\n");
            sb.append("    </div>\n");
            sb.append("</div>\n");
            if (includeFooter) {
                sb.append("<th:block th:include=\"${'inc/footer'}\"></th:block>\n");
            }
            sb.append("</body>\n");
            sb.append("</html>\n");
            sb.append("```\n\n");

            sb.append("【重要提醒】\n");
            sb.append(
                    "- 所有图片必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemEach.photo1,"
                            + " siteId)}\n");
            sb.append(
                    "- 所有视频必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemEach.media1,"
                            + " siteId)}\n");
            sb.append(
                    "- 所有文本使用 ${itemEach.singleLine1}~${itemEach.singleLine20} 或"
                            + " ${itemEach.note1}~${itemEach.note15}\n");
            sb.append("- 请结合栏目定义与栏目启用字段生成模版内容\n\n");
        } else {
            sb.append("【详情页模板完整示例】\n\n");
            sb.append("```html\n");
            sb.append("<!DOCTYPE html>\n");
            sb.append("<html xmlns:th=\"http://www.thymeleaf.org\">\n");
            sb.append("<head>\n");
            if (includeKey) {
                sb.append("    <th:block th:include=\"${'inc/key'}\"></th:block>\n");
            }
            sb.append("    <title th:text=\"${itemContent.title}\"></title>\n");
            sb.append("</head>\n");
            sb.append("<body>\n");
            if (includeHeader) {
                sb.append("<th:block th:include=\"${'inc/header'}\"></th:block>\n");
            }
            sb.append(
                    "<div class=\"main\""
                        + " th:with=\"itemContent=${CommonUtils.getContentEntityById(view.idEntity.classId,"
                        + " view.idEntity.infoId)}\">\n");
            sb.append("    <!-- 面包屑 -->\n");
            sb.append(
                    "    <div class=\"breadcrumb\""
                            + " th:utext=\"${CommonUtils.getBreadcrumb(view.idEntity.classId,"
                            + " view.idEntity.infoId)}\"></div>\n");
            sb.append("\n");
            sb.append("    <article class=\"detail\">\n");
            sb.append("        <!-- 标题 -->\n");
            sb.append("        <h1 th:text=\"${itemContent.title}\"></h1>\n");
            sb.append("\n");
            sb.append("        <!-- 元信息 -->\n");
            sb.append("        <div class=\"meta\">\n");
            sb.append(
                    "            <span class=\"date\""
                        + " th:text=\"${CommonUtils.getDateString(itemContent.createTime,'yyyy-MM-dd')}\"></span>\n");
            sb.append(
                    "            <span class=\"hits\">点击: <em"
                            + " th:text=\"${itemContent.hits}\"></em></span>\n");
            sb.append("            <!-- 来源字段 -->\n");
            sb.append(
                    "            <span th:if=\"${!#strings.isEmpty(itemContent.singleLine3)}\""
                            + " th:text=\"${itemContent.singleLine3}\"></span>\n");
            sb.append("        </div>\n");
            sb.append("\n");
            sb.append("        <!-- 封面图 - 必须用 resolveUiUrl 转换 -->\n");
            sb.append("        <div class=\"cover\" th:if=\"${itemContent.photo1}\">\n");
            sb.append(
                    "            <img th:src=\"${CommonUtils.resolveUiUrl(itemContent.photo1,"
                            + " siteId)}\" alt=\"\"/>\n");
            sb.append("        </div>\n");
            sb.append("\n");
            sb.append("        <!-- 视频 - 必须用 resolveUiUrl 转换 -->\n");
            sb.append("        <div class=\"video\" th:if=\"${itemContent.media1}\">\n");
            sb.append(
                    "            <video controls"
                            + " th:src=\"${CommonUtils.resolveUiUrl(itemContent.media1,"
                            + " siteId)}\"></video>\n");
            sb.append("        </div>\n");
            sb.append("\n");
            sb.append("        <!-- 正文内容 -->\n");
            sb.append(
                    "        <div class=\"content\" th:utext=\"${itemContent.content1}\"></div>\n");
            sb.append("\n");
            sb.append("        <!-- 摘要/简介 -->\n");
            sb.append("        <div class=\"summary\" th:if=\"${itemContent.note1}\">\n");
            sb.append(
                    "            <p"
                            + " th:utext=\"${CommonUtils.lineToBr(itemContent.note1)}\"></p>\n");
            sb.append("        </div>\n");
            sb.append("    </article>\n");
            sb.append("\n");
            sb.append("    <!-- 上下篇 -->\n");
            sb.append(
                    "    <div class=\"nav-pages\""
                        + " th:with=\"prevNext=${CommonUtils.getPrevAndNextByIdEntity(view.idEntity)}\">\n");
            sb.append("        <p th:if=\"${!prevNext.prevUrl.equals('javascript:void(0);')}\">\n");
            sb.append(
                    "            上一篇: <a th:href=\"${prevNext.prevUrl}\""
                            + " th:text=\"${prevNext.prevTitle}\"></a>\n");
            sb.append("        </p>\n");
            sb.append("        <p th:if=\"${!prevNext.nextUrl.equals('javascript:void(0);')}\">\n");
            sb.append(
                    "            下一篇: <a th:href=\"${prevNext.nextUrl}\""
                            + " th:text=\"${prevNext.nextTitle}\"></a>\n");
            sb.append("        </p>\n");
            sb.append("    </div>\n");
            sb.append("</div>\n");
            if (includeFooter) {
                sb.append("<th:block th:include=\"${'inc/footer'}\"></th:block>\n");
            }
            sb.append("</body>\n");
            sb.append("</html>\n");
            sb.append("```\n\n");

            sb.append("【重要提醒】\n");
            sb.append(
                    "- 所有图片必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemContent.photo1,"
                            + " siteId)}\n");
            sb.append(
                    "- 所有视频必须用 resolveUiUrl 转换: ${CommonUtils.resolveUiUrl(itemContent.media1,"
                            + " siteId)}\n");
            sb.append(
                    "- 所有文本使用 ${itemContent.singleLine1}~${itemContent.singleLine20} 或"
                            + " ${itemContent.note1}~${itemContent.note15}\n");
            sb.append("- 正文使用 ${itemContent.content1}~${itemContent.content6} (富文本需用 th:utext)\n");
            sb.append("- 请结合栏目定义与栏目启用字段生成模版内容\n\n");
        }

        // 构建参考页面 URL
        String referenceUrl = null;
        if (previewUrl != null && !previewUrl.isBlank()) {
            // 使用前端传递的预览URL
            previewUrl = previewUrl.replace("localhost", "127.0.0.1");
            referenceUrl = "view-source:" + previewUrl;
        } else {
            // 降级：使用默认值
            referenceUrl = null;
        }

        if (referenceUrl != null && !referenceUrl.isEmpty()) {
            sb.append("【参考页面】\n").append(referenceUrl).append("\n\n");
            sb.append("【结构要求 - ⚠️ 极其重要】\n");
            sb.append("1. 生成的模板必须与参考页面的 div 结构完全一致\n");
            sb.append("2. 所有 classname 必须与参考页面保持一致，不能修改或省略\n");
            sb.append("3. 所有样式细节（CSS class、内联样式）必须完全复制\n");
            sb.append("4. 不要简化或合并任何 div 结构，保持原有层级嵌套\n");
            sb.append("5. 只将静态内容替换为动态变量，结构框架不变\n\n");
        }
        {
            // The text model receives source directly; it cannot browse URLs.
            // 暂时不把 HTML 源码放进 prompt；需要恢复时取消注释。
            // sb.append("【HTML 源代码】\n```\n").append(htmlContent).append("\n```\n\n");
        }

        sb.append("【输出要求 - 必须严格遵守】\n");
        sb.append("1. 必须在响应中返回完整的 HTML 模板代码\n");
        sb.append("2. 模板代码必须用 ```html 代码块包裹，格式如下：\n");
        sb.append("```html\n");
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html xmlns:th=\"http://www.thymeleaf.org\">\n");
        sb.append("...\n");
        sb.append("</html>\n");
        sb.append("```\n");
        sb.append("3. 不要只返回保存路径或摘要信息\n");
        sb.append("4. 不要使用 'template created at' 或类似的表达\n");
        sb.append("5. 直接输出完整的模板代码，让用户可以预览和编辑\n");
        sb.append("6. ⚠️ 禁止使用省略写法如 \"...and N more lines\" 或 \"entire template omitted\"\n");
        sb.append("7. ⚠️ 必须逐行输出完整代码，无论模板有多长\n\n");

        sb.append("请结合栏目定义与栏目启用字段生成模版内容，现在返回完整的 Thymeleaf 模板代码（完整输出，禁止省略）。");
        return sb.toString();
    }

    private String extractTemplateContent(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "";
        }

        // 尝试从 markdown 代码块中提取
        int startIdx = rawResponse.indexOf("```html");
        if (startIdx == -1) {
            startIdx = rawResponse.indexOf("```HTML");
        }
        if (startIdx == -1) {
            startIdx = rawResponse.indexOf("```");
        }
        if (startIdx != -1) {
            startIdx = rawResponse.indexOf('\n', startIdx) + 1;
            int endIdx = rawResponse.indexOf("```", startIdx);
            if (endIdx != -1) {
                String content = rawResponse.substring(startIdx, endIdx).trim();
                // 验证提取的内容是否是有效的 HTML
                if (content.contains("<!DOCTYPE")
                        || content.contains("<html")
                        || content.contains("<body")) {
                    return content;
                }
            }
            // 如果没有找到结尾的 ```，尝试获取从开始到末尾的内容
            String content = rawResponse.substring(startIdx).trim();
            if (content.contains("<!DOCTYPE") || content.contains("<html")) {
                return content;
            }
        }

        // 尝试直接提取 HTML（如果响应本身就是 HTML）
        int doctypeIdx = rawResponse.indexOf("<!DOCTYPE");
        if (doctypeIdx != -1) {
            int htmlEndIdx = rawResponse.lastIndexOf("</html>");
            if (htmlEndIdx != -1) {
                return rawResponse.substring(doctypeIdx, htmlEndIdx + 7).trim();
            }
            // 没有找到 </html>，尝试返回从 <!DOCTYPE 开始的所有内容
            return rawResponse.substring(doctypeIdx).trim();
        }

        // 尝试从 <html 标签开始提取
        int htmlStartIdx = rawResponse.indexOf("<html");
        if (htmlStartIdx != -1) {
            int htmlEndIdx = rawResponse.lastIndexOf("</html>");
            if (htmlEndIdx != -1) {
                return rawResponse.substring(htmlStartIdx, htmlEndIdx + 7).trim();
            }
            return rawResponse.substring(htmlStartIdx).trim();
        }

        // 尝试从 JSON text 字段提取
        try {
            ObjectMapper mapper = new ObjectMapper();
            String[] lines = rawResponse.split("\n");
            StringBuilder content = new StringBuilder();
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    var event = mapper.readTree(line);
                    String type = event.has("type") ? event.get("type").asText() : "";
                    if ("text".equals(type)) {
                        var part = event.get("part");
                        if (part != null && part.has("text")) {
                            content.append(part.get("text").asText());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            String result = content.toString().trim();
            if (!result.isEmpty()) {
                // 再次尝试从代码块提取
                return extractTemplateContent(result);
            }
        } catch (Exception e) {
            LOG.error("解析响应失败", e);
        }

        throw new IllegalArgumentException("AI 响应中没有完整的 HTML 模板");
    }

    private String saveTemplate(String projectCode, String relativePath, String content) {
        Path templateDir = getTemplateDir(projectCode);
        Path targetFile = templateDir.resolve(relativePath);

        try {
            TemplateFileWriter.write(templateDir, relativePath, content);
            LOG.info("模板已保存: {}", targetFile);
            return relativePath;
        } catch (IOException e) {
            LOG.error("保存模板失败: {}", relativePath, e);
            throw new IllegalStateException("保存模板失败: " + e.getMessage(), e);
        }
    }

    private Path getTemplateDir(String projectCode) {
        return templateStorageService.resolveTemplateDir(projectCode);
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点 ID 不能为空");
        }
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null) {
            throw new IllegalArgumentException("站点不存在");
        }
        return site;
    }

    private boolean isHtmlLike(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".html")
                || name.endsWith(".htm")
                || name.endsWith(".aspx")
                || name.endsWith(".asp");
    }

    /** 批量取回一组栏目及其所有祖先，构建 id→实体 映射，避免逐列/逐级查询（N+1）。 */
    private Map<Integer, OneshotEntity> loadColumnEntitiesWithAncestors(List<Integer> columnIds) {
        Map<Integer, OneshotEntity> map = new HashMap<>();
        if (columnIds == null || columnIds.isEmpty()) {
            return map;
        }
        // 逐层向上批量补齐祖先：每一轮一次 IN 查询，层数即站点栏目树深度（通常 2~4 层）
        LinkedHashSet<Integer> pending = new LinkedHashSet<>(columnIds);
        while (!pending.isEmpty()) {
            List<Integer> batch = new ArrayList<>(pending);
            pending.clear();
            for (OneshotEntity entity : commonUtils.getOneshotListByIdsRaw(batch)) {
                if (entity == null || entity.getId() == null || map.containsKey(entity.getId())) {
                    continue;
                }
                map.put(entity.getId(), entity);
                Integer parentId = entity.getParentId();
                if (parentId != null && parentId != 0 && !map.containsKey(parentId)) {
                    pending.add(parentId);
                }
            }
        }
        return map;
    }

    /** 基于预取的 id→实体 映射构建栏目层级路径（从根到当前栏目），不产生额外查询。 */
    private String buildHierarchyPathFromMap(int columnId, Map<Integer, OneshotEntity> map) {
        List<String> pathNames = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Integer currentId = columnId;
        while (currentId != null && currentId != 0 && seen.add(currentId)) {
            OneshotEntity current = map.get(currentId);
            if (current == null) {
                break;
            }
            pathNames.add(0, current.getClassName());
            Integer parentId = current.getParentId();
            if (parentId == null || parentId == 0) {
                break;
            }
            currentId = parentId;
        }
        return pathNames.isEmpty() ? null : String.join(" > ", pathNames);
    }

    /** 构建「启用字段: fieldName(title), ...」说明行（仅列 isOpen 开启的字段）；无启用字段时返回 null。 */
    private String buildEnabledFieldsLine(OneshotEntity colEntity, int pageType) {
        if (colEntity == null) {
            return null;
        }
        String fieldConfigJson =
                pageType == 0 ? colEntity.getMenuField() : colEntity.getModelField();
        List<JSONObject> fields = parseFieldOptions(fieldConfigJson);
        List<String> enabledFields = new ArrayList<>();
        for (JSONObject field : fields) {
            String isOpen = field.getString("isOpen");
            if ("on".equals(isOpen) || "1".equals(isOpen) || "true".equalsIgnoreCase(isOpen)) {
                String fieldName = field.getString("fieldName");
                String title = field.getString("title");
                enabledFields.add(fieldName + (title != null ? "(" + title + ")" : ""));
            }
        }
        return enabledFields.isEmpty() ? null : "启用字段: " + String.join(", ", enabledFields);
    }

    /** 将中文字符串转换为拼音缩写 例如: "新闻中心" -> "xwzx" */
    private String toPinyinAbbreviation(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return "column";
        }

        try {
            HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
            format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
            format.setVCharType(HanyuPinyinVCharType.WITH_V);

            StringBuilder sb = new StringBuilder();
            for (char c : chinese.toCharArray()) {
                // 跳过空格和特殊字符
                if (Character.isWhitespace(c) || !isChineseCharacter(c)) {
                    continue;
                }
                // 获取拼音首字母
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);
                if (pinyinArray != null && pinyinArray.length > 0) {
                    sb.append(pinyinArray[0].charAt(0));
                }
            }

            String result = sb.toString();
            // 如果转换结果为空，使用默认名称
            return result.isEmpty() ? "column" : result;
        } catch (Exception e) {
            LOG.warn("拼音转换失败: {}", chinese, e);
            return "column";
        }
    }

    /** 判断字符是否为中文字符 */
    private boolean isChineseCharacter(char c) {
        UnicodeBlock block = UnicodeBlock.of(c);
        return block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }

    /** 获取模板类型标签 */
    private String getTemplateTypeLabel(String templateType) {
        if ("index".equals(templateType)) {
            return "首页模板";
        } else if ("list".equals(templateType)) {
            return "列表页模板";
        } else if ("detail".equals(templateType)) {
            return "详情页模板";
        }
        return "列表页模板";
    }

    /** 添加栏目层级结构信息 */
    private void appendColumnHierarchy(StringBuilder sb, OneshotEntity currentColumn) {
        if (currentColumn == null) {
            return;
        }

        sb.append("【栏目层级结构】\n");
        sb.append("以下是站点的栏目树形结构，帮助理解栏目的父子关系：\n\n");

        // 获取站点根栏目ID
        Integer siteRootId = currentColumn.getSiteRootId();
        if (siteRootId == null || siteRootId == 0) {
            sb.append("- 无法获取站点根栏目\n\n");
            return;
        }

        // 先添加根节点本身
        OneshotEntity rootColumn = commonUtils.getOneshotEntityById(siteRootId);
        if (rootColumn != null) {
            boolean isCurrent =
                    currentColumn.getId() != null
                            && currentColumn.getId().equals(rootColumn.getId());
            String marker = isCurrent ? " ⭐ 当前栏目" : "";
            sb.append("├─ ")
                    .append(rootColumn.getClassName())
                    .append(" (ID: ")
                    .append(rootColumn.getId())
                    .append(")")
                    .append(marker)
                    .append("\n");

            // 然后递归构建根节点的子栏目
            buildColumnTree(sb, rootColumn.getId(), 1, currentColumn.getId());
        } else {
            // 如果无法获取根节点，直接从 siteRootId 开始构建
            buildColumnTree(sb, siteRootId, 0, currentColumn.getId());
        }

        sb.append("\n提示：\n");
        sb.append("- 获取子栏目: CommonUtils.getOneshotListByParentId(父栏目ID)\n");
        sb.append("- 获取栏目内容: CommonUtils.getContentListByClassId(栏目ID, 数量)\n");
        sb.append("- 当前识别栏目 ID: ").append(currentColumn.getId()).append("\n");
        if (currentColumn.getBizCode() != null && !currentColumn.getBizCode().isBlank()) {
            sb.append("- 当前识别栏目 bizcode: ")
                    .append(currentColumn.getBizCode())
                    .append("（获取栏目对象优先用 CommonUtils.getOneshotEntityByBizcode('")
                    .append(currentColumn.getBizCode())
                    .append("', siteId)）\n");
        }
        sb.append("\n");
    }

    /** 递归构建栏目树 */
    private void buildColumnTree(
            StringBuilder sb, int parentId, int level, Integer currentColumnId) {
        List<OneshotEntity> children = commonUtils.getOneshotListByParentId(parentId);
        if (children == null || children.isEmpty()) {
            return;
        }

        String indent = "  ".repeat(level);
        for (OneshotEntity child : children) {
            boolean isCurrent = currentColumnId != null && currentColumnId.equals(child.getId());
            String marker = isCurrent ? " ⭐ 当前栏目" : "";
            sb.append(indent)
                    .append("├─ ")
                    .append(child.getClassName())
                    .append(" (ID: ")
                    .append(child.getId())
                    .append(")")
                    .append(marker)
                    .append("\n");

            // 递归处理子栏目
            buildColumnTree(sb, child.getId(), level + 1, currentColumnId);
        }
    }

    /** 添加选中栏目的配置信息 */
    private void appendSelectedColumnsConfig(
            StringBuilder sb, List<Map<String, Object>> selectedColumns, String layoutId) {
        sb.append("【栏目与区域设置 - 参考】\n");
        sb.append("以下栏目已选择并指定了页面位置，生成模板时请根据位置放置对应内容：\n\n");

        // 添加布局信息
        if (layoutId != null && !layoutId.isEmpty()) {
            sb.append("选择的页面布局: ").append(getLayoutName(layoutId)).append("\n");
            sb.append("布局说明: ").append(getLayoutDescription(layoutId)).append("\n\n");
        }

        // 批量取回所有栏目实体，避免逐列调用 getOneshotEntityById 造成的 N+1 远程查询
        List<Integer> selectedColumnIds = new ArrayList<>();
        for (Map<String, Object> col : selectedColumns) {
            if (col.get("columnId") != null) {
                selectedColumnIds.add(Long.valueOf(col.get("columnId").toString()).intValue());
            }
        }
        Map<Integer, OneshotEntity> columnEntityMap = new HashMap<>();
        for (OneshotEntity entity : commonUtils.getOneshotListByIdsRaw(selectedColumnIds)) {
            if (entity != null && entity.getId() != null) {
                columnEntityMap.put(entity.getId(), entity);
            }
        }

        for (Map<String, Object> col : selectedColumns) {
            Long colId = Long.valueOf(col.get("columnId").toString());
            String colName = (String) col.get("columnName");
            Integer pageType =
                    col.get("pageType") != null
                            ? Integer.valueOf(col.get("pageType").toString())
                            : 1;
            String region = (String) col.get("region");

            // 栏目详情来自上方批量查询结果
            OneshotEntity colEntity = columnEntityMap.get(colId.intValue());
            String colEnName = columnEnglishName(colEntity);
            if (colEnName.isBlank()) {
                colEnName = toPinyinAbbreviation(colName);
            }

            // 解析区域位置
            String regionLabel = translateRegion(region);

            sb.append("--- ").append(colName).append(" (ID: ").append(colId).append(") ---\n");
            sb.append("栏目名称: ").append(colName).append("\n");
            sb.append("栏目类型: ").append(pageType == 0 ? "单篇栏目" : "列表栏目").append("\n");
            if (regionLabel != null && !regionLabel.isEmpty()) {
                sb.append("页面位置: ").append(regionLabel).append("\n");
            }
            String colBizcode = colEntity != null ? colEntity.getBizCode() : null;
            if (colBizcode != null && !colBizcode.isBlank()) {
                sb.append("栏目 bizcode: ").append(colBizcode).append("\n");
                sb.append("获取栏目数据: CommonUtils.getOneshotEntityByBizcode('")
                        .append(colBizcode)
                        .append("', siteId)\n");
            } else {
                // 存量栏目未回填 bizcode，退回按 ID 查询
                sb.append("获取栏目数据: CommonUtils.getOneshotEntityById(").append(colId).append(")\n");
            }

            if (pageType == 1) {
                // 列表栏目
                sb.append("获取内容列表: CommonUtils.getContentListByClassId(")
                        .append(colId)
                        .append(", 10)\n");
                sb.append("获取分页数据: CommonUtils.getContentPagerByClassId(view.idEntity)\n");
            }

            // 添加该栏目的字段配置
            if (colEntity != null) {
                String enabledFieldsLine = buildEnabledFieldsLine(colEntity, pageType);
                if (enabledFieldsLine != null) {
                    sb.append(enabledFieldsLine).append("\n");
                }
            }

            sb.append("\n");
        }
        sb.append("⚠\uFE0F 请根据上述栏目的页面位置信息，在模板对应区域生成相应内容。\n\n\n");
    }

    /** 将区域代码转换为中文描述 */
    private String translateRegion(String region) {
        if (region == null || region.isEmpty()) {
            return null;
        }
        return switch (region) {
            case "top-left" -> "左上";
            case "top-center" -> "上中";
            case "top-right" -> "右上";
            case "middle-left" -> "左中";
            case "middle-center" -> "正中";
            case "middle-right" -> "右中";
            case "bottom-left" -> "左下";
            case "bottom-center" -> "下中";
            case "bottom-right" -> "右下";
            // 新布局区域
            case "r1" -> "区域1";
            case "r2" -> "区域2";
            case "r3" -> "区域3";
            case "r4" -> "区域4";
            case "r5" -> "区域5";
            case "r6" -> "区域6";
            case "r7" -> "区域7";
            case "r8" -> "区域8";
            case "r9" -> "区域9";
            case "header" -> "头部";
            case "sidebar" -> "侧边栏";
            case "main" -> "主内容区";
            case "footer" -> "底部";
            case "title" -> "标题区";
            case "info" -> "信息区";
            case "content" -> "内容区";
            case "related" -> "相关推荐";
            case "contact" -> "联系方式";
            case "nav" -> "导航区";
            case "banner" -> "轮播区";
            case "block1" -> "内容块1";
            case "block2" -> "内容块2";
            case "block3" -> "内容块3";
            case "left" -> "左侧";
            case "center" -> "中间";
            case "right" -> "右侧";
            case "top" -> "上部";
            case "middle" -> "中部";
            case "bottom" -> "下部";
            case "second" -> "第二栏";
            case "third" -> "第三栏";
            default -> region;
        };
    }

    /** 获取布局名称 */
    private String getLayoutName(String layoutId) {
        return switch (layoutId) {
            case "grid-9" -> "九宫格布局";
            case "news-list" -> "新闻列表布局";
            case "detail" -> "详情页布局";
            case "company" -> "公司简介布局";
            case "homepage" -> "首页布局";
            case "two-column" -> "两栏布局";
            case "three-column" -> "三栏布局";
            case "two-row" -> "上下两栏布局";
            case "three-row" -> "上中下三栏布局";
            case "four-row" -> "上下四栏布局";
            default -> layoutId;
        };
    }

    /** 获取布局描述 */
    private String getLayoutDescription(String layoutId) {
        return switch (layoutId) {
            case "grid-9" -> "标准九宫格布局，9个等分区域";
            case "news-list" -> "顶部通栏+侧边栏+主内容区+底部";
            case "detail" -> "标题区+正文区+侧边栏+相关推荐";
            case "company" -> "头部介绍+内容区+联系方式";
            case "homepage" -> "导航区+轮播区+内容块+底部";
            case "two-column" -> "左右两栏布局";
            case "three-column" -> "左中右三栏布局";
            case "two-row" -> "上下两栏布局";
            case "three-row" -> "上中下三栏布局";
            case "four-row" -> "上下四栏布局";
            default -> "";
        };
    }

    /** 解析字段配置 JSON */
    private List<JSONObject> parseFieldOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, JSONObject.class);
        } catch (Exception e) {
            LOG.warn("解析字段配置失败: {}", json, e);
            return List.of();
        }
    }

    /**
     * 构建AI修复模板的Prompt
     *
     * @param siteId 站点ID
     * @param templatePath 模板相对路径
     * @param scenario 场景类型: "error"(异常修复) 或 "inconsistent"(一致性修复)
     * @param errorMessage 异常信息（用于修复上下文）
     * @param errorInfoUrl 模版渲染异常信息URL（预览模版界面的url地址）
     * @param templateContentUrl 模板内容URL（模版内容标签页代码编辑器请求的url）
     * @param referenceUrl 参考界面URL（右边页面最终效果的url地址）
     */
    public String buildRepairPrompt(
            Long siteId,
            String templatePath,
            String scenario,
            String errorMessage,
            String errorInfoUrl,
            String templateContentUrl,
            String referenceUrl) {
        String current =
                getTemplateContent(siteId, templatePath)
                        .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        StringBuilder sb = new StringBuilder("请修复以下 CMS Thymeleaf 模板，保持现有结构和样式。\n");
        sb.append("【当前模板源码】\n```html\n").append(current).append("\n```\n");
        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append("【渲染错误】\n").append(errorMessage).append("\n");
        }
        sb.append("【修复场景】").append(scenario).append("\n");
        sb.append("根据提供的源码与错误信息修复，不要修改已发布模板；修复结果由用户预览后应用。\n");
        sb.append(getTemplateSyntaxReference());
        sb.append("返回修复后的完整 HTML，使用 html 代码块，不要省略代码。\n");
        return sb.toString();
    }

    /**
     * 追加“多站点定位方法（bizcode + siteId）”清单，供各识别 Prompt 复用
     *
     * <p>多站点场景下栏目在不同站点的 t_oneshot.id 不同、但 bizcode 相同， 因此凡按“某个栏目”取数据都应优先使用以下方法（以 bizcode + siteId
     * 定位）， 不要在模板中写死栏目 id 再调用对应的 ...ById(id) 方法。
     */
    private void appendBizcodeMethodsDoc(StringBuilder sb) {
        sb.append("【⚠️ 多站点定位方法（bizcode + siteId，优先使用）】\n");
        sb.append("多站点下栏目在不同站点的 t_oneshot.id 不同、但 bizcode 相同。凡要按“某个栏目”取数据，\n");
        sb.append("一律优先使用下列方法（bizcode 见本 Prompt 中给出的栏目信息），不要在模板里写死栏目 id；\n");
        sb.append("下列方法均为对应 ...ById(id) 方法的多站点版本，第二个参数固定为站点ID siteId。\n");
        sb.append("- CommonUtils.getOneshotEntityByBizcode(bizcode, siteId) → 取当前站点的栏目对象\n");
        sb.append(
                "- CommonUtils.getOneshotEntityByParentBizcodeAndIdentifies(bizcode, siteId,"
                        + " identifies) → 按父栏目+唯一标识取栏目\n");
        sb.append(
                "- CommonUtils.getOneshotListByParentBizcodeAndIdentifies(bizcode, siteId,"
                        + " identifies...) → 按父栏目+唯一标识取栏目列表\n");
        sb.append(
                "- CommonUtils.getOneshotListByParentBizcodeAndShowPosition(bizcode, siteId,"
                        + " showPosition) → 父栏目下专题栏目\n");
        sb.append(
                "- CommonUtils.getOneshotListByParentBizcode(bizcode, siteId) / (bizcode, siteId,"
                        + " limit) / (bizcode, siteId, outBizcodes) → 父栏目的子栏目列表\n");
        sb.append(
                "- CommonUtils.getOneshotListByBizcodes(bizcodes, siteId) → 按多个栏目"
                        + " bizcode（逗号分隔）取栏目列表\n");
        sb.append(
                "- CommonUtils.getFirstOneshotListByParentBizcode(bizcode, siteId) → 父栏目第一个子栏目"
                        + " id\n");
        sb.append("- CommonUtils.getBannerEntityByBizcode(bizcode, siteId) → 栏目 Banner 对象\n");
        sb.append(
                "- CommonUtils.getBreadcrumbByBizcode(bizcode, siteId) / (bizcode, siteId,"
                        + " contentId) → 面包屑导航\n");
        sb.append(
                "- CommonUtils.getContentEntityByClassBizcode(bizcode, siteId, contentId) →"
                        + " 取单条内容\n");
        sb.append(
                "- CommonUtils.getContentByParentBizcodeAndIdentifies(bizcode, siteId, identifies)"
                        + " / (..., size) → 按父栏目+唯一标识取内容\n");
        sb.append(
                "- CommonUtils.getContentListByClassBizcode(bizcode, siteId) / (..., limit) / (...,"
                        + " limit, setting) / (..., limit, setting, pageIndex) → 栏目内容列表\n");
        sb.append(
                "- CommonUtils.getContentListByParentBizcode(bizcode, siteId) / (..., limit) /"
                    + " (..., limit, setting) / (..., limit, setting, outBizcodes) → 父栏目内容列表\n");
        sb.append(
                "- CommonUtils.getContentListByParentBizcodeAndShowPosition(bizcode, siteId,"
                        + " showPosition, limit) → 按显示位置取内容\n");
        sb.append(
                "- CommonUtils.getContentHotListByClassBizcode(bizcode, siteId, limit) → 栏目热门内容\n");
        sb.append(
                "- CommonUtils.getContentHotListByParentBizcode(bizcode, siteId, limit) →"
                        + " 父栏目热门内容\n");
        sb.append(
                "- CommonUtils.getContentListNoPublishedByClassBizcode(bizcode, siteId) → 未发布内容\n");
        sb.append(
                "- CommonUtils.getContentListByClassBizcodeAndRelationId(bizcode, siteId,"
                        + " relationId) → 关联内容\n");
        sb.append(
                "- CommonUtils.getContentFieldByClassBizcodeGroupBy(bizcode, siteId, fieldName,"
                        + " limit, isGroup, sortType) → 字段分组取值\n");
        sb.append(
                "- CommonUtils.getContentPagerByClassBizcode(bizcode, siteId, view.idEntity) →"
                        + " 栏目分页\n");
        sb.append(
                "- CommonUtils.getContentPagerByClassBizcodes(bizcodes, siteId, view.idEntity) →"
                        + " 多栏目分页\n");
        sb.append(
                "- CommonUtils.getContentPagerByParentBizcode(bizcode, siteId, view.idEntity) →"
                        + " 父栏目分页\n");
        sb.append(
                "- CommonUtils.getGroupByTitleByClassBizcode(bizcode, siteId, view.idEntity,"
                        + " addAll) → 按字段分组\n");
        sb.append(
                "- CommonUtils.getPrevAndNextByClassBizcode(bizcode, siteId, view.idEntity) →"
                        + " 上一篇/下一篇\n");
        sb.append("【⚠️ 内容 contentCode 方法（t_contents.content_code，与上面的栏目 biz_code 是两套标识，勿混用）】\n");
        sb.append(
                "- CommonUtils.getContentEntityByContentCode(contentCode, siteId) → 按内容 contentCode"
                        + " 取内容\n");
        sb.append(
                "- CommonUtils.getTitleByContentCode(contentCode, siteId) → 按内容 contentCode 取标题\n");
        sb.append(
                "- CommonUtils.getTypeTitleByContentCode(contentCode, siteId) → 按内容 contentCode"
                        + " 取标题（类型标题场景）\n");
        sb.append(
                "- CommonUtils.getContentListByContentCodes(contentCodes, siteId) → 按多个内容"
                        + " contentCode（逗号分隔）取内容列表\n");
        sb.append(
                "- CommonUtils.getBusinessBannerEntityByContentCode(contentCode, siteId) → 按内容"
                        + " contentCode 取业务板块 Banner\n");
        sb.append(
                "- CommonUtils.getContentListByRelationContentCode(contentCode, siteId) → 按关联内容"
                        + " contentCode 取关联数据(xgid)\n");
        sb.append(
                "⚠️ 区分：栏目用 biz_code（t_oneshot.biz_code），内容用"
                        + " content_code（t_contents.content_code），两者的查询方法不可混用。\n");
        sb.append("⚠️ 上述 setting 参数为推荐位编号：8首页置顶、9首页热门、10首页推荐、11频道置顶、12频道热门、13频道推荐、\n");
        sb.append("   14栏目置顶、15栏目热门、16栏目推荐、17导航推荐、18右侧推荐、19左侧推荐；0 表示不按推荐位过滤。\n\n");
    }

    /** 获取模板语法参考文本 */
    private String getTemplateSyntaxReference() {
        StringBuilder sb = new StringBuilder();
        sb.append("【图片/视频字段必须使用 resolveUiUrl 转换】\n");
        sb.append("photo1~photo15、media1~media4 字段存储的是 ui: 格式的路径，必须转换：\n");
        sb.append("❌ 错误写法: <img th:src=\"${itemEach.photo1}\"/>\n");
        sb.append(
                "✅ 正确写法: <img th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n");
        sb.append("❌ 错误写法: <video th:src=\"${itemEach.media1}\"/>\n");
        sb.append(
                "✅ 正确写法: <video th:src=\"${CommonUtils.resolveUiUrl(itemEach.media1,"
                        + " siteId)}\"/>\n");
        sb.append("注意：siteId 变量在模板上下文中自动可用\n\n");

        sb.append("【CommonUtils 完整方法列表】\n");
        sb.append("⚠️ 重要：只能使用以下列出的方法，禁止使用其他任何未列出的方法！\n");
        sb.append("禁止猜测或发明不存在的方法，如 getxxx、findxxx 等。\n\n");

        sb.append("⚠️ 正确的使用方式：使用 ${CommonUtils.方法名(...)} 而不是 @CommonUtils\n");
        sb.append("❌ 错误写法: th:src=\"${@CommonUtils.resolveUiUrl(...)}\"\n");
        sb.append("✅ 正确写法: th:src=\"${CommonUtils.resolveUiUrl(...)}\"\n\n");

        sb.append("⚠️ SpEL 表达式规则：在 ${} 内部，不要再使用 ${} 嵌套调用方法\n");
        sb.append(
                "❌ 错误写法: th:text=\"${item.title} + ${CommonUtils.getDateString(item.createTime,"
                        + " 'yyyy-MM-dd')}\"\n");
        sb.append(
                "✅ 正确写法: th:text=\"${item.title + ' ' + CommonUtils.getDateString(item.createTime,"
                        + " 'yyyy-MM-dd')}\"\n");
        sb.append("❌ 错误写法: th:src=\"${${CommonUtils.resolveUiUrl(item.photo1, siteId)}}\"\n");
        sb.append("✅ 正确写法: th:src=\"${CommonUtils.resolveUiUrl(item.photo1, siteId)}\"\n\n");

        appendBizcodeMethodsDoc(sb);
        sb.append("一、栏目/菜单数据读取\n");
        sb.append(
                "- CommonUtils.getOneshotEntityByBizcode(bizcode, siteId) → 【优先】根据 bizcode"
                        + " 与当前站点ID获取栏目对象(OneshotEntity)，多站点下稳定\n");
        sb.append(
                "- CommonUtils.getOneshotEntityById(id) →"
                        + " 根据ID获取单个栏目对象(OneshotEntity)（仅本站点上下文有效，多站点场景请优先用上面的 bizcode 方法）\n");
        sb.append("- CommonUtils.getOneshotListByParentId(parentId) → 获取子栏目列表\n");
        sb.append("- CommonUtils.getOneshotListByParentId(parentId, limit) → 获取子栏目列表(限制数量)\n");
        sb.append(
                "- CommonUtils.getOneshotEntityByParentIdAndIdentifies(parentId, identifies) →"
                        + " 根据父栏目ID和唯一标识获取栏目\n\n");

        sb.append("二、内容数据读取\n");
        sb.append("- CommonUtils.getContentEntityById(id) → 根据ID获取单篇内容(ContentEntity)\n");
        sb.append("- CommonUtils.getContentEntityById(classId, id) → 根据栏目ID和内容ID获取内容\n");
        sb.append("- CommonUtils.getContentListByClassId(classId, limit) → 根据栏目ID获取内容列表\n");
        sb.append(
                "- CommonUtils.getContentListByClassId(classId, limit, setting) → 同上，第三参数 setting"
                        + " 为推荐位编号（语义见下方 getContentListByParentId）\n");
        sb.append(
                "- CommonUtils.getContentListByParentId(parentId, limit) → 根据父栏目ID获取内容列表（不限推荐位）\n");
        sb.append(
                "- CommonUtils.getContentListByParentId(parentId, limit, setting) →"
                        + " 按推荐位筛选指定父栏目下的内容列表\n");
        sb.append(
                "  ⚠️ 第三个参数 setting = 内容推荐位编号：8 首页置顶、9 首页热门、10 首页推荐、11 频道置顶、12 频道热门、13 频道推荐、14"
                        + " 栏目置顶、15 栏目热门、16 栏目推荐、17 导航推荐、18 右侧推荐、19 左侧推荐；传 0 表示不按推荐位过滤（返回全部）\n");
        sb.append(
                "  示例：th:with=\"topList=${CommonUtils.getContentListByParentId(parentId, 5,"
                        + " 8)}\"（取该父栏目下【首页置顶】前 5 条）\n");
        sb.append("- CommonUtils.getContentHotListByClassId(classId, limit) → 获取热门内容列表\n");
        sb.append("- CommonUtils.getContentPagerByClassId(view.idEntity) → 获取分页数据对象(Pager)\n\n");

        sb.append("三、URL生成\n");
        sb.append("- CommonUtils.getListUrl(oneshotEntity) → 获取栏目列表页URL\n");
        sb.append("- CommonUtils.getDetailUrl(contentEntity) → 获取内容详情页URL\n");
        sb.append("- CommonUtils.getBreadcrumb(classId) → 获取面包屑导航HTML\n\n");

        sb.append("四、工具方法\n");
        sb.append("- CommonUtils.getDateString(date, 'yyyy-MM-dd') → 格式化日期\n");
        sb.append("- CommonUtils.lineToBr(text) → 换行符转<br/>\n");
        sb.append("- CommonUtils.getBannerEntity(bannerId) → 获取Banner对象\n\n");

        sb.append("五、资源路径转换（⚠️ 必须用于图片/视频/静态资源）\n");
        sb.append("- CommonUtils.resolveUiUrl(path, siteId) → 转换 ui: 前缀路径为可访问URL\n");
        sb.append("  示例: ${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\n");
        sb.append("  用于: photo1~photo15 图片字段、media1~media4 视频字段\n\n");

        sb.append("【静态资源路径转换规则】\n");
        sb.append("所有静态资源引用必须转换为站点 UI 服务的绝对路径：\n");
        sb.append(
                "- <script src=\"xxx\"> → <script th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <link href=\"xxx\"> → <link th:href=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <img src=\"xxx\"> → <img th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append(
                "- <video src=\"xxx\"> → <video th:src=\"${CommonUtils.resolveUiUrl('xxx',"
                        + " siteId)}\">\n");
        sb.append("⚠️ 重要：路径 xxx 必须保持原样不变，只改变标签写法！\n");
        sb.append("示例:\n");
        sb.append(
                "  原: <link href=\"css/style.css\"> → 转换: <link"
                        + " th:href=\"${CommonUtils.resolveUiUrl('css/style.css', siteId)}\">\n");
        sb.append(
                "  原: <img src=\"images/logo.png\"> → 转换: <img"
                        + " th:src=\"${CommonUtils.resolveUiUrl('images/logo.png', siteId)}\">\n");
        sb.append(
                "  原: <script src=\"js/common.js\"> → 转换: <script"
                        + " th:src=\"${CommonUtils.resolveUiUrl('js/common.js',"
                        + " siteId)}\"></script>\n");
        sb.append(
                "  原: style=\"background-image: url('images/bg.jpg');\" → 转换:"
                        + " th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n");
        sb.append("  ⚠️ 内联样式中的路径：th:style 的值是字符串拼接，必须用 ${} 包裹方法调用！\n");
        sb.append(
                "  ❌ 错误: th:style=\"'background-image: url(' +"
                        + " CommonUtils.resolveUiUrl('images/bg.jpg', siteId) + ');'\"\n");
        sb.append(
                "  ✅ 正确: th:style=\"'background-image: url(' +"
                        + " ${CommonUtils.resolveUiUrl('images/bg.jpg', siteId)} + ');'\"\n\n");

        sb.append("六、SEO 变量（通过 view.seoEntity 访问）\n");
        sb.append("- ${view.seoEntity.title} → SEO标题\n");
        sb.append("- ${view.seoEntity.keywords} → SEO关键词\n");
        sb.append("- ${view.seoEntity.description} → SEO描述\n");
        sb.append("- ${view.seoEntity.siteName} → 站点名称\n");
        sb.append("  示例: <meta name=\"keywords\" content=\"${view.seoEntity.keywords}\"/>\n");
        sb.append("  示例: <title th:text=\"${view.seoEntity.title}\"></title>\n");
        sb.append("⚠️ 重要：必须使用 seoEntity，不是 seoInfo！\n");
        sb.append("  ❌ 错误: ${view.seoInfo.keywords}\n");
        sb.append("  ✅ 正确: ${view.seoEntity.keywords}\n\n");

        sb.append("【OneshotEntity 栏目对象字段】\n");
        sb.append("- className: 栏目名称\n");
        sb.append("- photo1~photo15: 图片字段(存储图片路径)\n");
        sb.append("- singleLine1~singleLine30: 单行文本字段\n");
        sb.append("- note1~note15: 多行文本字段\n");
        sb.append("- media1~media4: 视频字段(存储视频路径如mp4)\n\n");

        sb.append("【栏目类型与数据获取方法】\n");
        sb.append("根据栏目类型选择正确的数据获取方法：\n\n");

        sb.append("一、单篇栏目（pageType=0）\n");
        sb.append("  - 获取栏目内容（singleLine、photo、note等字段）:\n");
        sb.append(
                "    CommonUtils.getOneshotEntityByBizcode(栏目bizcode, siteId)  // 优先使用，bizcode"
                        + " 见【基本信息】/【栏目与区域设置】\n");
        sb.append(
                "  - 示例: th:with=\"column=${CommonUtils.getOneshotEntityByBizcode(栏目bizcode,"
                        + " siteId)}\"\n");
        sb.append("  - 使用: ${column.singleLine1}、${column.photo1}、${column.note1} 等\n\n");

        sb.append("二、列表栏目（pageType=1）\n");
        sb.append("  - 获取内容列表（每条内容的 singleLine、photo、note等字段）:\n");
        sb.append("    CommonUtils.getContentListByClassId(栏目ID, 数量)\n");
        sb.append("  - 示例: th:with=\"list=${CommonUtils.getContentListByClassId(栏目ID, 10)}\"\n");
        sb.append(
                "  - 使用: th:each=\"itemEach:${list}\" 然后 ${itemEach.title}、${itemEach.photo1} 等\n");
        sb.append("  - 分页: CommonUtils.getContentPagerByClassId(view.idEntity) 返回 Pager 对象\n\n");

        sb.append("三、获取栏目基本信息（栏目名称、栏目ID）\n");
        sb.append("  - 无论单篇还是列表栏目，都通过以下方法获取栏目对象:\n");
        sb.append(
                "    CommonUtils.getOneshotEntityByBizcode(栏目bizcode, siteId)  // 优先使用，bizcode"
                        + " 见【基本信息】/【栏目与区域设置】\n");
        sb.append("  - 示例: ${column.className} 获取栏目名称, ${column.id} 获取栏目ID\n\n");

        sb.append("【ContentEntity 内容对象字段】\n");
        sb.append("- title: 标题\n");
        sb.append("- createTime: 创建时间\n");
        sb.append("- hits: 点击量\n");
        sb.append("- content1~content6: 正文内容(富文本)，用于填充 Div 标签的内容\n");
        sb.append("  ⚠️ 重要：content 字段是富文本HTML，必须使用 th:utext 渲染到标签内部\n");
        sb.append("  ✅ <div class=\"content\" th:utext=\"${itemContent.content1}\"></div>\n");
        sb.append("  ❌ 错误: th:text=\"${itemContent.content1}\" (会转义HTML标签)\n");
        sb.append("  ❌ 错误: <p th:each=\"p:${itemContent.content1}\"> (content不是数组)\n");
        sb.append("  ❌ 禁止使用 #strings.split 或其他分割函数处理 content 字段！\n");
        sb.append("     content 是完整的 HTML 片段，直接用 th:utext 输出到 div 内部即可。\n\n");

        sb.append("- note1~note15: 摘要/简介等多行文本\n");
        sb.append("- singleLine1~singleLine20: 单行文本(如来源、作者、链接等)\n");
        sb.append("- photo1~photo15: 图片字段(存储图片路径)\n");
        sb.append("- media1~media4: 视频字段(存储视频路径)\n\n");

        sb.append("【字段类型与转换规则 - ⚠️ 重要】\n");
        sb.append("不同类型字段需要不同的处理方式：\n\n");

        sb.append("一、photo1~photo15、media1~media4 字段（存储 ui:/ 或 assets:/ 引用）\n");
        sb.append("  必须通过 CommonUtils.resolveUiUrl 转换：\n");
        sb.append("  ✅ <img th:src=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n");
        sb.append("  ✅ <a th:href=\"${CommonUtils.resolveUiUrl(itemEach.photo1, siteId)}\"/>\n\n");

        sb.append("二、singleLine1~singleLine20、note1~note15 字段（存储普通文本）\n");
        sb.append("  直接显示，不需要 resolveUiUrl 转换：\n");
        sb.append("  ✅ <span th:text=\"${itemEach.singleLine1}\"/> (链接地址、来源、作者等)\n");
        sb.append("  ✅ <a th:href=\"${itemEach.singleLine2}\">链接</a> (链接字段)\n");
        sb.append("  ❌ 错误：对 singleLine/note 字段使用 resolveUiUrl 转换\n\n");

        sb.append("【三目运算符与空值判断 - ⚠️ 重要】\n");
        sb.append("photo、singleLine、media、note 等字段都是字符串类型，不是布尔类型！\n");
        sb.append("在 Thymeleaf 三目运算符中判断这些字段时，必须进行空值比较：\n\n");
        sb.append("❌ 错误写法（直接当作布尔值）:\n");
        sb.append(
                "  ${itemEach.photo7 ? CommonUtils.resolveUiUrl(itemEach.photo7, siteId) :"
                        + " null}\n");
        sb.append("  ${itemEach.singleLine1 ? itemEach.singleLine1 : '默认值'}\n\n");
        sb.append("✅ 正确写法（判断是否为空）:\n");
        sb.append(
                "  ${#strings.isEmpty(itemEach.photo7) ? null :"
                        + " CommonUtils.resolveUiUrl(itemEach.photo7, siteId)}\n");
        sb.append(
                "  ${itemEach.photo7 != null && !itemEach.photo7.isEmpty() ?"
                        + " CommonUtils.resolveUiUrl(itemEach.photo7, siteId) : null}\n");
        sb.append("  ${#strings.isEmpty(itemEach.singleLine1) ? '默认值' : itemEach.singleLine1}\n\n");
        sb.append("推荐使用 Thymeleaf 内置的 #strings.isEmpty() 方法判断字符串是否为空或空白。\n\n");

        return sb.toString();
    }

    public record TemplateFileInfo(String relativePath, String fileName) {}

    public record CommonTemplateResult(
            String status,
            String errorCode,
            String errorMessage,
            String templateContent,
            String savePath,
            String rawResponse) {
        public static CommonTemplateResult success(
                String templateContent, String savePath, String rawResponse) {
            return new CommonTemplateResult(
                    "SUCCESS", null, null, templateContent, savePath, rawResponse);
        }

        public static CommonTemplateResult failed(String errorCode, String errorMessage) {
            return new CommonTemplateResult("FAILED", errorCode, errorMessage, null, null, null);
        }
    }

    public record ContentTemplateResult(
            String status,
            String errorCode,
            String errorMessage,
            String templateContent,
            String savePath,
            String rawResponse) {
        public static ContentTemplateResult success(
                String templateContent, String savePath, String rawResponse) {
            return new ContentTemplateResult(
                    "SUCCESS", null, null, templateContent, savePath, rawResponse);
        }

        public static ContentTemplateResult failed(String errorCode, String errorMessage) {
            return new ContentTemplateResult("FAILED", errorCode, errorMessage, null, null, null);
        }
    }
}

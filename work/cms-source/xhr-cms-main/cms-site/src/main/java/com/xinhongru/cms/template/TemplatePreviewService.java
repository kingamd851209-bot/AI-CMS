package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.upload.ResourceHtml;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.web.resonseVo.FooterEntity;
import com.xinhongru.web.resonseVo.HeaderEntity;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.resonseVo.SeoEntity;
import com.xinhongru.web.utils.CommonUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.AlwaysValidCacheEntryValidity;
import org.thymeleaf.cache.ICacheEntryValidity;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.AbstractTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** 模板预览服务 - 渲染 Thymeleaf 模板用于预览对比 */
@Service
public class TemplatePreviewService {
    @Autowired private ResourceHtml resourceHtml;
    @Autowired private OneShotMapper menus;
    @Autowired private com.xinhongru.cms.content.record.ContentExtensionResources mediaResources;
    private static final Logger LOG = LoggerFactory.getLogger(TemplatePreviewService.class);
    private static final String TEMPLATE_DIR = "template/web";

    private final UiPackageStorageService uiPackageStorageService;
    private final UiPackageService uiPackageService;
    private final SiteProjectMapper projectMapper;
    private final CommonUtils commonUtils;
    private final ApplicationContext applicationContext;
    private final TemplateStorageService templateStorageService;
    private final List<TemplateModelContributor> modelContributors;

    public TemplatePreviewService(
            UiPackageStorageService uiPackageStorageService,
            UiPackageService uiPackageService,
            SiteProjectMapper projectMapper,
            CommonUtils commonUtils,
            ApplicationContext applicationContext,
            TemplateStorageService templateStorageService,
            List<TemplateModelContributor> modelContributors) {
        this.uiPackageStorageService = uiPackageStorageService;
        this.uiPackageService = uiPackageService;
        this.projectMapper = projectMapper;
        this.commonUtils = commonUtils;
        this.applicationContext = applicationContext;
        this.templateStorageService = templateStorageService;
        this.modelContributors = List.copyOf(modelContributors);
    }

    private void contributePageModel(
            SiteProjectEntity site, String templatePath, String templateContent, Context context) {
        contributePageModel(site, templatePath, templateContent, context, false);
    }

    private void contributePageModel(
            SiteProjectEntity site,
            String templatePath,
            String templateContent,
            Context context,
            boolean publicRequest) {
        for (TemplateModelContributor contributor : modelContributors) {
            if (contributor.supports(site.getProjectCode(), templatePath, templateContent)) {
                contributor.contribute(
                        site.getId(),
                        site.getProjectCode(),
                        templatePath,
                        templateContent,
                        context,
                        publicRequest);
            }
        }
    }

    /**
     * 渲染模板预览
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径（如 inc/header.html）
     * @param columnId 栏目 ID（可选，用于内容模板）
     * @param contentId 内容 ID（可选，用于详情页模板）
     * @return 渲染后的 HTML
     */
    public String renderTemplatePreview(
            Long siteId, String templatePath, Long columnId, Long contentId) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取模板内容
        String templateContent = readTemplateContent(site.getProjectCode(), templatePath);
        if (templateContent == null || templateContent.isBlank()) {
            throw new IllegalArgumentException("模板文件不存在或内容为空: " + templatePath);
        }

        // 创建上下文
        Context context = createPreviewContext(siteId, columnId, contentId);

        // 创建预览用的模板引擎（支持字符串模板和 @bean 语法）
        try {
            TemplateEngine previewEngine = createPreviewTemplateEngine(site.getProjectCode());

            // 设置主模板内容到 ThreadLocal
            mainTemplateContent.set(templateContent);

            // 使用特殊模板名，让自定义解析器处理
            contributePageModel(site, templatePath, templateContent, context);
            return resourceHtml.render(
                    previewEngine.process(MAIN_TEMPLATE_NAME, context), site.getProjectCode());
        } catch (TemplateProcessingException e) {
            LOG.error("Thymeleaf 模板解析错误: {}", templatePath, e);
            // 返回详细的错误信息，包括完整堆栈
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("模板: ").append(templatePath).append("\n");
            errorMsg.append(e.getMessage());
            return buildDetailedErrorHtml("Thymeleaf 解析错误", errorMsg.toString(), e);
        } catch (Exception e) {
            LOG.error("渲染模板失败: {}", templatePath, e);
            return buildDetailedErrorHtml("渲染失败", e.getMessage(), e);
        } finally {
            // 清理 ThreadLocal
            mainTemplateContent.remove();
        }
    }

    /** 渲染公共元素模板预览（简化版） */
    public String renderCommonTemplatePreview(Long siteId, String templatePath) {
        return renderTemplatePreview(siteId, templatePath, null, null);
    }

    /** 渲染列表页模板预览 */
    public String renderListTemplatePreview(Long siteId, String templatePath, Long columnId) {
        return renderTemplatePreview(siteId, templatePath, columnId, null);
    }

    /** 渲染详情页模板预览 */
    public String renderDetailTemplatePreview(
            Long siteId, String templatePath, Long columnId, Long contentId) {
        return renderTemplatePreview(siteId, templatePath, columnId, contentId);
    }

    /** 渲染模板预览（使用 IdEntity） 用于站点服务，支持从 URL 提取的参数 */
    public String renderTemplatePreviewWithIdEntity(
            Long siteId, String templatePath, IdEntity idEntity) {
        return renderTemplatePreviewWithIdEntity(siteId, templatePath, idEntity, null);
    }

    public String renderTemplatePreviewWithIdEntity(
            Long siteId, String templatePath, IdEntity idEntity, String prefillValue) {
        SiteProjectEntity site = requireSite(siteId);

        // 读取模板内容
        String templateContent = readTemplateContent(site.getProjectCode(), templatePath);
        if (templateContent == null || templateContent.isBlank()) {
            throw new IllegalArgumentException("模板文件不存在或内容为空: " + templatePath);
        }

        // 创建上下文（使用 IdEntity）
        Context context = createPreviewContextWithIdEntity(siteId, idEntity);
        if (prefillValue != null) context.setVariable("cmsFormPrefillValue", prefillValue);

        // 创建预览用的模板引擎
        try {
            TemplateEngine previewEngine = createPreviewTemplateEngine(site.getProjectCode());

            // 设置主模板内容到 ThreadLocal
            mainTemplateContent.set(templateContent);

            // 使用特殊模板名，让自定义解析器处理
            contributePageModel(site, templatePath, templateContent, context, true);
            String rendered = previewEngine.process(MAIN_TEMPLATE_NAME, context);
            // cms-applied路径必须先经Contributor校验receipt/来源/绑定；旧人工模板保持原协议校验。
            return PublicBindingApplication.managed(templatePath) || prefillValue != null
                    || templatePath.startsWith("cms-form-native/")
                    ? resourceHtml.renderTemplate(rendered, site.getProjectCode())
                    : resourceHtml.render(rendered, site.getProjectCode());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("站点页面渲染失败: {}", templatePath, e);
            throw new IllegalStateException("站点页面渲染失败", e);
        } finally {
            mainTemplateContent.remove();
        }
    }

    /**
     * 渲染模板内容预览（接收模板字符串）
     *
     * <p>用于编辑器实时预览，不需要保存文件
     *
     * @param siteId 站点 ID
     * @param templateContent 模板内容字符串
     * @param columnId 栏目 ID（可选）
     * @param contentId 内容 ID（可选）
     * @return 渲染后的 HTML
     */
    public String renderTemplateContentPreview(
            Long siteId, String templateContent, Long columnId, Long contentId) {
        SiteProjectEntity site = requireSite(siteId);

        // 创建上下文
        Context context = createPreviewContext(siteId, columnId, contentId);

        // 创建支持文件解析的模板引擎（用于 th:replace/th:include）
        try {
            TemplateEngine previewEngine = createPreviewTemplateEngine(site.getProjectCode());

            // 设置主模板内容到 ThreadLocal
            mainTemplateContent.set(templateContent);

            // 使用特殊模板名，让自定义解析器处理
            contributePageModel(site, null, templateContent, context);
            return resourceHtml.render(
                    previewEngine.process(MAIN_TEMPLATE_NAME, context), site.getProjectCode());
        } catch (TemplateProcessingException e) {
            LOG.error("Thymeleaf 模板解析错误", e);
            // 返回详细的错误信息
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("模板解析错误:\n");
            errorMsg.append(e.getMessage()).append("\n");
            return buildDetailedErrorHtml("Thymeleaf 解析错误", errorMsg.toString(), e);
        } catch (Exception e) {
            LOG.error("渲染模板内容失败", e);
            return buildDetailedErrorHtml("渲染失败", e.getMessage(), e);
        } finally {
            // 清理 ThreadLocal
            mainTemplateContent.remove();
        }
    }

    /**
     * 使用自定义 Context 构建器渲染模板 —— 自定义路由扩展的核心方法。
     *
     * <p>与 {@link #renderTemplatePreviewWithIdEntity} 的区别在于： Context 的构建完全由调用方通过 {@code
     * contextBuilder} 控制。 这允许自定义路由在 Java 代码中完成复杂的多表查询和数据组装， 然后注入到 Context 中供模板使用。
     *
     * <h3>自动注入的基础变量</h3>
     *
     * <p>Context 在传给 {@code contextBuilder} 之前已预填以下变量：
     *
     * <ul>
     *   <li>{@code siteId} — Long，站点 ID
     *   <li>{@code site} / {@code siteConfig} — SiteProjectEntity，站点配置
     *   <li>{@code commonUtils} / {@code CommonUtils} — CommonUtils 实例
     *   <li>{@code projectCode} — String，站点编码
     *   <li>{@code ThymeleafEvaluationContext} — 支持 {@code @beanName} 语法
     * </ul>
     *
     * <h3>使用示例</h3>
     *
     * <pre>{@code
     * previewService.renderWithCustomContext(siteId, "list_news", context -> {
     *     context.setVariable("newsList", newsMapper.getLatest(10));
     *     context.setVariable("readCounts", statsMapper.batchGetCounts(ids));
     *     context.setVariable("hotTags", tagMapper.getHotTags(100, 20));
     *     context.setVariable("title", "自定义页面标题");
     *     context.setVariable("keywords", "关键词1,关键词2");
     * });
     * }</pre>
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径（如 "column-3/index"、"list_news"），相对于站点模板目录
     * @param contextBuilder 自定义 Context 构建器，在基础变量之上添加业务变量
     * @return 渲染后的 HTML
     */
    public String renderWithCustomContext(
            Long siteId, String templatePath, Consumer<Context> contextBuilder) {
        SiteProjectEntity site = requireSite(siteId);
        String projectCode = site.getProjectCode();

        // 读取模板内容
        String templateContent = readTemplateContent(projectCode, templatePath);
        if (templateContent == null || templateContent.isBlank()) {
            throw new IllegalArgumentException("模板文件不存在或内容为空: " + templatePath);
        }

        // 创建 Context，自动注入站点基础变量
        Context context = new Context();
        Map<String, Object> baseVariables = new HashMap<>();
        baseVariables.put("siteId", siteId);
        baseVariables.put("site", site);
        baseVariables.put("siteConfig", site);
        baseVariables.put("commonUtils", commonUtils);
        baseVariables.put("CommonUtils", commonUtils);
        baseVariables.put("projectCode", projectCode);

        // 支持 @bean 语法（如 @CommonUtils.getContentListByClassId(...)）
        baseVariables.put(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));

        context.setVariables(baseVariables);

        // ★ 调用方自定义变量
        contextBuilder.accept(context);

        // 渲染
        try {
            TemplateEngine previewEngine = createPreviewTemplateEngine(projectCode);
            mainTemplateContent.set(templateContent);
            contributePageModel(site, templatePath, templateContent, context);
            return resourceHtml.render(
                    previewEngine.process(MAIN_TEMPLATE_NAME, context), site.getProjectCode());
        } catch (TemplateProcessingException e) {
            LOG.error("Thymeleaf 模板解析错误: {}", templatePath, e);
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("模板: ").append(templatePath).append("\n");
            errorMsg.append(e.getMessage());
            return buildDetailedErrorHtml("Thymeleaf 解析错误", errorMsg.toString(), e);
        } catch (Exception e) {
            LOG.error("渲染模板失败: {}", templatePath, e);
            return buildDetailedErrorHtml("渲染失败", e.getMessage(), e);
        } finally {
            mainTemplateContent.remove();
        }
    }

    /** 仅供已确认绑定的授权草稿链调用；直接使用未发布实体，不调用公开查询、Bean或贡献器。 */
    public String renderControlledDraft(
            String projectCode, TextBindingCompiler.Compilation compilation, OneshotEntity menu) {
        if (menu == null) throw new IllegalArgumentException("草稿栏目不存在");
        return renderControlled(
                projectCode, compilation, 0, Map.of("currentMenu", menu, "itemMenu", menu));
    }

    String renderControlledPageListDraft(String projectCode,
            TextBindingCompiler.Compilation compilation, OneshotEntity menu,
            DetailCollections collections, java.util.List<DetailCollections.Binding> bindings) {
        var variables = Map.<String, Object>of("currentMenu", menu, "itemMenu", menu);
        var context = new Context();
        collections.contribute(menu.getSiteProjectId(), projectCode, menu, null, bindings, context);
        var prepared = DetailCollections.compile(compilation.html(), bindings);
        return renderControlled(projectCode, compilation, 0, variables, context, prepared);
    }

    public String renderControlledDetail(
            String projectCode,
            TextBindingCompiler.Compilation compilation,
            OneshotEntity menu,
            ContentEntity content) {
        if (menu == null || content == null) throw new IllegalArgumentException("详情草稿或栏目不存在");
        return renderControlled(
                projectCode,
                compilation,
                1,
                Map.of("currentMenu", menu, "itemMenu", menu, "content", content, "info", content));
    }

    private String renderControlled(
            String projectCode,
            TextBindingCompiler.Compilation compilation,
            int pageType,
            Map<String, Object> variables) {
        return renderControlled(projectCode, compilation, pageType, variables, new Context(), compilation.html());
    }

    private String renderControlled(
            String projectCode, TextBindingCompiler.Compilation compilation, int pageType,
            Map<String, Object> variables, Context context, String html) {
        new TextBindingCompiler().validateControlled(compilation, pageType);
        context.setVariables(variables);
        var htmlValues = new java.util.LinkedHashMap<String, String>();
        Object owner = variables.get(pageType == 0 ? "currentMenu" : "content");
        for (var binding : compilation.bound())
            if ("Editor".equals(binding.fieldType())) {
                Object value = controlledFieldValue(owner, binding.fieldName());
                if (value != null && !(value instanceof String))
                    throw new IllegalArgumentException("草稿正文字段类型不合法");
                htmlValues.put(
                        binding.fieldName(),
                        com.xinhongru.cms.content.record.ContentExtensionHtml.clean(
                                (String) value));
            }
        context.setVariable("cmsDraftHtml", java.util.Map.copyOf(htmlValues));
        var mediaValues = new java.util.LinkedHashMap<String, String>();
        var menu = (OneshotEntity) variables.get("currentMenu");
        context.setVariable("cmsDraftMenuNames",
                MenuNameBinding.names(menus, menu, compilation.bound(), true));
        var definitions =
                com.xinhongru.content.model.ContentModelCodec.read(
                        pageType == 0 ? menu.getMenuField() : menu.getModelField());
        for (var binding : compilation.bound())
            if (java.util.Set.of("Image", "Video").contains(binding.fieldType())) {
                var field =
                        definitions.stream()
                                .filter(
                                        item ->
                                                item.getFieldName().equals(binding.fieldName())
                                                        && item.getFieldType()
                                                                .equals(binding.fieldType()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("媒体字段配置已变化"));
                Object value = controlledFieldValue(owner, binding.fieldName());
                mediaValues.put(
                        binding.fieldName(),
                        LinkedImageBackground.validateReference(
                                compilation.html(),
                                "cmsDraftMedia",
                                binding.fieldName(),
                                mediaResources.prepareTemplateMedia(
                                        field, value, menu.getSiteProjectId())));
            }
        var mediaDocument = org.jsoup.Jsoup.parse(compilation.html());
        for (var source : mediaDocument.select("source[type]")) {
            for (var entry : mediaValues.entrySet())
                if (source.attr("th:src").equals("${cmsDraftMedia['" + entry.getKey() + "']}")) {
                    String value =
                            entry.getValue().split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
                    String mime =
                            source.attr("type")
                                    .split(";", 2)[0]
                                    .trim()
                                    .toLowerCase(java.util.Locale.ROOT);
                    if (!value.isEmpty()
                            && !(mime.equals("video/mp4") && value.endsWith(".mp4")
                                    || mime.equals("video/webm") && value.endsWith(".webm")
                                    || mime.equals("video/ogg") && value.endsWith(".ogv")))
                        throw new IllegalArgumentException(
                                "当前资源与source固定MIME不一致或未支持；保留原type，需人工处理");
                }
        }
        context.setVariable("cmsDraftMedia", java.util.Map.copyOf(mediaValues));
        var dateValues = new java.util.LinkedHashMap<String, String>();
        for (var binding : compilation.bound())
            if ("DateTime".equals(binding.fieldType())) {
                var field =
                        definitions.stream()
                                .filter(
                                        f ->
                                                f.getFieldName().equals(binding.fieldName())
                                                        && com.xinhongru.content.model
                                                                .ContentFieldDefinition.enabled(
                                                                f.getIsOpen()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("日期字段配置已变化"));
                Object value =
                        owner instanceof OneshotEntity single
                                ? single.getExtensionValues().get(binding.fieldName())
                                : ((ContentEntity) owner)
                                        .getExtensionValues()
                                        .get(binding.fieldName());
                if (value != null && !(value instanceof String))
                    throw new IllegalArgumentException("日期字段值类型无效");
                dateValues.put(
                        binding.fieldName(),
                        TemplateDateValues.display(binding, field, (String) value));
            }
        context.setVariable("cmsDraftDates", java.util.Map.copyOf(dateValues));

        try {
            TemplateEngine engine = createPreviewTemplateEngine(projectCode, false);
            mainTemplateContent.set(html);
            return engine.process(MAIN_TEMPLATE_NAME, context);
        } catch (RuntimeException error) {
            throw new IllegalStateException("草稿模板渲染失败，未生成成功预览", error);
        } finally {
            mainTemplateContent.remove();
        }
    }

    // 用于存储主模板内容的 ThreadLocal
    private static final ThreadLocal<String> mainTemplateContent = new ThreadLocal<>();
    // 主模板的固定模板名
    private static final String MAIN_TEMPLATE_NAME = "template:main:content";

    /** 创建预览用的模板引擎（支持解析引用的模板文件） */
    private static Object controlledFieldValue(Object owner, String name) {
        if (com.xinhongru.content.model.ContentExtensionFields.isName(name))
            return owner instanceof OneshotEntity menu
                    ? menu.getExtensionValues().get(name)
                    : ((ContentEntity) owner).getExtensionValues().get(name);
        try {
            var property = com.xinhongru.common.reflect.EntityFields.find(owner.getClass(), name);
            property.setAccessible(true);
            return property.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("草稿绑定字段读取失败", error);
        }
    }

    private TemplateEngine createPreviewTemplateEngine(String projectCode) {
        return createPreviewTemplateEngine(projectCode, true);
    }

    private TemplateEngine createPreviewTemplateEngine(
            String projectCode, boolean allowFileTemplates) {
        // 使用 SpringTemplateEngine 以支持 @bean 语法
        SpringTemplateEngine engine = new SpringTemplateEngine();
        // 禁用 SpringEL 编译器以支持运行时变量解析
        engine.setEnableSpringELCompiler(false);

        // 文件模板解析器（用于 th:replace/th:include 引用）
        // prefix 设置为 template/web/ 目录，模板引用路径如 inc/key
        // 实际模板文件位于 template/web/inc/key.html
        FileTemplateResolver fileResolver = new FileTemplateResolver();
        fileResolver.setTemplateMode("HTML");
        fileResolver.setPrefix(getTemplateDir(projectCode).toString() + "/");
        fileResolver.setSuffix(".html");
        fileResolver.setOrder(1);
        fileResolver.setCheckExistence(true);

        // 自定义模板解析器（只处理主模板 template:main:content）
        // 使用特殊的模板名前缀，确保不会与文件模板冲突
        AbstractTemplateResolver mainTemplateResolver =
                new AbstractTemplateResolver() {
                    @Override
                    protected ITemplateResource computeTemplateResource(
                            IEngineConfiguration configuration,
                            String ownerTemplate,
                            String template,
                            Map<String, Object> templateResolutionAttributes) {
                        // 只处理主模板（模板名为 template:main:content）
                        if (MAIN_TEMPLATE_NAME.equals(template)) {
                            String content = mainTemplateContent.get();
                            if (content != null) {
                                return new StringTemplateResource(content);
                            }
                        }
                        // 其他模板名返回 null，让 FileTemplateResolver 处理
                        return null;
                    }

                    @Override
                    protected TemplateMode computeTemplateMode(
                            IEngineConfiguration configuration,
                            String ownerTemplate,
                            String template,
                            Map<String, Object> templateResolutionAttributes) {
                        return TemplateMode.HTML;
                    }

                    @Override
                    protected ICacheEntryValidity computeValidity(
                            IEngineConfiguration configuration,
                            String ownerTemplate,
                            String template,
                            Map<String, Object> templateResolutionAttributes) {
                        // 主模板始终有效
                        return AlwaysValidCacheEntryValidity.INSTANCE;
                    }

                    @Override
                    protected boolean computeResolvable(
                            IEngineConfiguration configuration,
                            String ownerTemplate,
                            String template,
                            Map<String, Object> templateResolutionAttributes) {
                        // 只处理主模板
                        return MAIN_TEMPLATE_NAME.equals(template);
                    }
                };

        engine.setTemplateResolvers(
                allowFileTemplates
                        ? Set.of(fileResolver, mainTemplateResolver)
                        : Set.of(mainTemplateResolver));

        return engine;
    }

    /** 构建详细的错误 HTML（包含完整堆栈信息，包括嵌套的 cause） */
    private String buildDetailedErrorHtml(String title, String message, Exception e) {
        // 构建完整堆栈信息（包括所有嵌套的 cause）
        StringBuilder stackTrace = new StringBuilder();
        Throwable current = e;
        int causeIndex = 0;
        while (current != null) {
            if (causeIndex > 0) {
                stackTrace.append("\nCaused by: ");
            }
            stackTrace
                    .append(current.getClass().getName())
                    .append(": ")
                    .append(current.getMessage())
                    .append("\n");
            for (StackTraceElement element : current.getStackTrace()) {
                stackTrace.append("    at ").append(element.toString()).append("\n");
                // 限制堆栈长度
                if (stackTrace.length() > 18000) {
                    stackTrace.append("    ...(更多堆栈信息已省略)\n");
                    break;
                }
            }
            current = current.getCause();
            causeIndex++;
            if (causeIndex > 10) break; // 防止无限循环
        }

        StringBuilder html = new StringBuilder();
        html.append(
                "<div class=\"preview-error\" style=\"padding: 20px; background: #fff3f3; border:"
                        + " 1px solid #ffcdd2; border-radius: 8px;\">");
        html.append("<h3 style=\"color: #c62828; margin: 0 0 10px;\">")
                .append(escapeHtml(title))
                .append("</h3>");
        html.append("<p style=\"color: #666; margin: 0 0 10px;\">")
                .append(escapeHtml(message))
                .append("</p>");
        html.append("<details style=\"margin-top: 10px;\">");
        html.append(
                "<summary style=\"cursor: pointer; color: #1976d2; font-size:"
                        + " 13px;\">点击查看详细堆栈信息</summary>");
        html.append(
                "<pre style=\"background: #f5f5f5; padding: 10px; border-radius: 4px; overflow:"
                        + " auto; font-size: 11px; max-height: 400px; white-space: pre-wrap;"
                        + " word-break: break-word; margin-top: 10px;\">");
        html.append(escapeHtml(stackTrace.toString()));
        html.append("</pre></details>");

        // 添加提示信息
        if (message != null && message.contains("th:replace")) {
            html.append("<p style=\"color: #666; margin-top: 10px; font-size: 13px;\">");
            html.append("<strong>提示:</strong> 模板中使用了 th:replace 或 th:include 引用其他模板片段，");
            html.append("请确保引用的模板存在，或先保存模板后再预览。");
            html.append("</p>");
        }

        html.append("</div>");
        return html.toString();
    }

    /** 获取模板文件路径对应的静态文件预览 URL */
    public String getStaticPreviewUrl(Long siteId, String templatePath) {
        SiteProjectEntity site = requireSite(siteId);

        // 获取 UI Package 访问 URL
        Optional<Path> activePath = uiPackageStorageService.getActivePath(site.getProjectCode());
        if (activePath.isEmpty()) {
            return null;
        }

        // 将模板路径转换为静态文件路径
        // template/web/inc/header.html -> inc/header.html
        String staticPath = templatePath.replace(TEMPLATE_DIR + "/", "");
        // 如果是内容模板，尝试找到对应的原始文件

        return "/" + staticPath;
    }

    private String readTemplateContent(String projectCode, String templatePath) {
        Path templateDir = getTemplateDir(projectCode);
        Path templateFile = templateDir.resolve(templatePath);

        if (!Files.isRegularFile(templateFile)) {
            LOG.warn("模板文件不存在: {}", templateFile);
            return null;
        }

        try {
            return Files.readString(templateFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("读取模板文件失败: {}", templatePath, e);
            return null;
        }
    }

    private Context createPreviewContext(Long siteId, Long columnId, Long contentId) {
        Context context = new Context();
        Map<String, Object> variables = new HashMap<>();

        // 添加站点信息
        variables.put("siteId", siteId);

        // 获取站点实体
        SiteProjectEntity siteEntity = requireSite(siteId);
        variables.put("site", siteEntity);
        variables.put("siteConfig", siteEntity); // 站点配置（兼容不同变量名）

        // 添加 CommonUtils 到上下文（支持 ${commonUtils.method()} 调用）
        // 注意：老 CMS 使用小写的 commonUtils，需要兼容
        variables.put("commonUtils", commonUtils);
        variables.put("CommonUtils", commonUtils); // 兼容 @CommonUtils 写法

        // SEO 信息
        SeoEntity seoEntity = new SeoEntity();
        seoEntity.setTitle("预览标题");
        seoEntity.setKeywords("预览关键词");
        seoEntity.setDescription("预览描述");
        seoEntity.setSiteName("预览站点");

        // Header 和 Footer（预览时使用空对象）
        HeaderEntity headerEntity = new HeaderEntity();
        FooterEntity footerEntity = new FooterEntity();

        // 创建 idEntity
        IdEntity idEntity;
        if (columnId != null) {
            idEntity = new IdEntity();
            idEntity.setClassId(columnId.intValue());
            idEntity.setInfoId(contentId != null ? contentId.intValue() : 0);
            idEntity.setPageIndex(1);
            idEntity.setPageSize(10);

            // 添加 bannerClassId（常用变量）
            variables.put("bannerClassId", columnId);

            // 添加栏目信息
            try {
                var menu = commonUtils.getOneshotEntityById(columnId.intValue());
                if (menu != null) {
                    variables.put("itemMenu", menu);
                    variables.put("currentMenu", menu);
                    idEntity.setBizCode(menu.getBizCode());
                    idEntity.setSiteProjectId(menu.getSiteProjectId());
                }
            } catch (Exception e) {
                LOG.warn("获取栏目信息失败: columnId={}", columnId, e);
            }

            // 如果是详情页，添加内容信息
            if (contentId != null) {
                try {
                    var content =
                            commonUtils.getContentEntityById(
                                    columnId.intValue(), contentId.intValue());
                    if (content != null) {
                        variables.put("info", content);
                        variables.put("content", content);
                    }
                } catch (Exception e) {
                    LOG.warn("获取内容信息失败: contentId={}", contentId, e);
                }
            }
        } else {
            // 没有 columnId 时创建默认 idEntity
            idEntity = new IdEntity();
            idEntity.setClassId(1);
            idEntity.setInfoId(0);
            idEntity.setPageIndex(1);
            idEntity.setPageSize(10);
        }

        // 创建 view 对象，使用匿名类包装以支持 pageTitle 字段
        // 模板中使用 ${view.pageTitle}，需要 view 对象有此属性
        final SeoEntity finalSeoEntity = seoEntity;
        final HeaderEntity finalHeaderEntity = headerEntity;
        final FooterEntity finalFooterEntity = footerEntity;
        final IdEntity finalIdEntity = idEntity;

        Object view =
                new Object() {
                    public SeoEntity getSeoEntity() {
                        return finalSeoEntity;
                    }

                    public HeaderEntity getHeaderEntity() {
                        return finalHeaderEntity;
                    }

                    public FooterEntity getFooterEntity() {
                        return finalFooterEntity;
                    }

                    public IdEntity getIdEntity() {
                        return finalIdEntity;
                    }

                    // pageTitle 字段 - 模板中使用 ${view.pageTitle}
                    public String getPageTitle() {
                        return finalSeoEntity.getTitle();
                    }

                    // pageKeyword 字段 - 模板中使用 ${view.pageKeyword}
                    public String getPageKeyword() {
                        return finalSeoEntity.getKeywords();
                    }

                    // SEO 字段 - 模板中使用 ${view.keywords}, ${view.description} 等
                    public String getKeywords() {
                        return finalSeoEntity.getKeywords();
                    }

                    public String getDescription() {
                        return finalSeoEntity.getDescription();
                    }

                    public String getTitle() {
                        return finalSeoEntity.getTitle();
                    }

                    public String getSiteName() {
                        return finalSeoEntity.getSiteName();
                    }
                };

        // 添加 view 对象（重要：这是模板中 ${view.xxx} 的来源）
        variables.put("view", view);

        // 兼容：也添加顶级变量
        variables.put("title", seoEntity.getTitle());
        variables.put("keywords", seoEntity.getKeywords());
        variables.put("description", seoEntity.getDescription());
        // variables.put("pageTitle", seoEntity.getTitle());

        // 添加 ThymeleafEvaluationContext 以支持 @bean 语法
        // 这是让 SpringEL 能解析 @CommonUtils 的关键
        variables.put(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));

        context.setVariables(variables);
        return context;
    }

    /** 创建预览上下文（使用传入的 IdEntity） 用于站点服务，支持从 URL 提取的参数 */
    private Context createPreviewContextWithIdEntity(Long siteId, IdEntity idEntity) {
        Context context = new Context();
        Map<String, Object> variables = new HashMap<>();

        // 添加站点信息
        variables.put("siteId", siteId);

        // 获取站点实体
        SiteProjectEntity siteEntity = requireSite(siteId);
        variables.put("site", siteEntity);
        variables.put("siteConfig", siteEntity);

        // 添加 CommonUtils 到上下文
        variables.put("commonUtils", commonUtils);
        variables.put("CommonUtils", commonUtils);

        // SEO 信息
        SeoEntity seoEntity = new SeoEntity();
        seoEntity.setTitle(siteEntity.getProjectName());
        seoEntity.setKeywords("预览关键词");
        seoEntity.setDescription("预览描述");
        seoEntity.setSiteName("预览站点");

        // Header 和 Footer
        HeaderEntity headerEntity = new HeaderEntity();
        FooterEntity footerEntity = new FooterEntity();

        // 使用传入的 idEntity
        Long columnId = idEntity.getClassId() != null ? idEntity.getClassId().longValue() : null;
        Long contentId =
                idEntity.getInfoId() != null && idEntity.getInfoId() > 0
                        ? idEntity.getInfoId().longValue()
                        : null;

        // {productId} 占位符捕获的产品内容 id（产品对比等按产品出内容的列表页使用）
        variables.put("productId", idEntity.getProductId());
        variables.put("idEntity", idEntity);

        // 添加 bannerClassId
        OneshotEntity menu = null;
        if (columnId != null) {
            variables.put("bannerClassId", columnId);

            // 添加栏目信息
            try {
                menu = commonUtils.getOneshotEntityById(columnId.intValue());
                if (menu != null) {
                    variables.put("itemMenu", menu);
                    variables.put("currentMenu", menu);
                    // SEO 信息
                    seoEntity.setTitle(menu.getClassName() + "-" + seoEntity.getTitle());
                    seoEntity.setKeywords(menu.getSeoKeywords());
                    seoEntity.setDescription(menu.getSeoDescription());
                }
                seoEntity.setSiteName(siteEntity.getProjectName());
            } catch (Exception e) {
                LOG.warn("获取栏目信息失败: columnId={}", columnId, e);
            }

            // 如果是详情页，添加内容信息
            if (contentId != null) {
                try {
                    var content =
                            commonUtils.getContentEntityById(
                                    columnId.intValue(), contentId.intValue());
                    if (content != null) {
                        variables.put("info", content);
                        variables.put("content", content);
                        seoEntity.setTitle(content.getTitle() + "-" + seoEntity.getTitle());
                        // 优先使用内容的 SEO 信息
                        if (content.getSeoTitle() != null && !content.getSeoTitle().isBlank()) {
                            seoEntity.setTitle(content.getSeoTitle());
                        }
                        if (content.getSeoKeywords() != null
                                && !content.getSeoKeywords().isBlank()) {
                            seoEntity.setKeywords(content.getSeoKeywords());
                        }
                        if (content.getSeoDescription() != null
                                && !content.getSeoDescription().isBlank()) {
                            seoEntity.setDescription(content.getSeoDescription());
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("获取内容信息失败: contentId={}", contentId, e);
                }
            }
        }

        // 如果没有内容的 SEO 信息，则使用栏目的 SEO 信息
        if (contentId == null && menu != null) {
            if (menu.getSeoTitle() != null && !menu.getSeoTitle().isBlank()) {
                seoEntity.setTitle(menu.getSeoTitle());
            }
            if (menu.getSeoKeywords() != null && !menu.getSeoKeywords().isBlank()) {
                seoEntity.setKeywords(menu.getSeoKeywords());
            }
            if (menu.getSeoDescription() != null && !menu.getSeoDescription().isBlank()) {
                seoEntity.setDescription(menu.getSeoDescription());
            }
        }

        // 创建 view 对象
        final SeoEntity finalSeoEntity = seoEntity;
        final HeaderEntity finalHeaderEntity = headerEntity;
        final FooterEntity finalFooterEntity = footerEntity;
        final IdEntity finalIdEntity = idEntity;

        Object view =
                new Object() {
                    public SeoEntity getSeoEntity() {
                        return finalSeoEntity;
                    }

                    public HeaderEntity getHeaderEntity() {
                        return finalHeaderEntity;
                    }

                    public FooterEntity getFooterEntity() {
                        return finalFooterEntity;
                    }

                    public IdEntity getIdEntity() {
                        return finalIdEntity;
                    }

                    public String getPageTitle() {
                        return finalSeoEntity.getTitle();
                    }

                    public String getPageKeyword() {
                        return finalSeoEntity.getKeywords();
                    }

                    public String getKeywords() {
                        return finalSeoEntity.getKeywords();
                    }

                    public String getDescription() {
                        return finalSeoEntity.getDescription();
                    }

                    public String getTitle() {
                        return finalSeoEntity.getTitle();
                    }

                    public String getSiteName() {
                        return finalSeoEntity.getSiteName();
                    }
                };

        variables.put("view", view);

        // 兼容：也添加顶级变量
        variables.put("title", seoEntity.getTitle());
        variables.put("keywords", seoEntity.getKeywords());
        variables.put("description", seoEntity.getDescription());

        // 添加 ThymeleafEvaluationContext
        variables.put(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));

        context.setVariables(variables);
        return context;
    }

    private Path getTemplateDir(String projectCode) {
        return templateStorageService.resolveTemplateDir(projectCode);
    }

    /**
     * 获取指定站点的模板目录（公开方法，供自定义路由使用）。 目录路径：runtime/template/{projectCode}/
     *
     * @param projectCode 站点编码
     * @return 模板目录的绝对路径
     */
    public Path getPublicTemplateDir(String projectCode) {
        return getTemplateDir(projectCode);
    }

    /**
     * 获取模板文件的绝对路径
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径（如 inc/header.html）
     * @return 模板文件的绝对路径
     */
    public String getTemplateAbsolutePath(Long siteId, String templatePath) {
        SiteProjectEntity site = requireSite(siteId);
        Path templateDir = getTemplateDir(site.getProjectCode());
        Path templateFile = templateDir.resolve(templatePath);
        return templateFile.toAbsolutePath().toString();
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

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

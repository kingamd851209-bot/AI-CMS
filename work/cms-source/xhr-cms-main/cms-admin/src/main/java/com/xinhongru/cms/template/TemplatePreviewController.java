package com.xinhongru.cms.template;

import com.xinhongru.api.admin.AdminApiResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

/**
 * 模板预览控制器 - 提供模板渲染预览服务
 *
 * <p>用于第三步预览对比，渲染生成的 Thymeleaf 模板并返回 HTML
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/v2/sites/{siteId}/template-preview")
@RequiredArgsConstructor
public class TemplatePreviewController {
    private final TemplatePreviewService previewService;

    /**
     * 预览公共元素模板（key/header/footer）
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径，如 inc/header.html
     * @return 渲染后的 HTML 和静态文件预览 URL
     */
    @GetMapping("/common")
    public AdminApiResponse<TemplatePreviewResult> previewCommonTemplate(
            @PathVariable Long siteId, @RequestParam String templatePath) {
        log.info("预览公共元素模板: siteId={}, templatePath={}", siteId, templatePath);

        try {
            String renderedHtml = previewService.renderCommonTemplatePreview(siteId, templatePath);
            String staticUrl = previewService.getStaticPreviewUrl(siteId, templatePath);
            return AdminApiResponse.success(new TemplatePreviewResult(renderedHtml, staticUrl));
        } catch (Exception e) {
            log.error("预览公共元素模板失败", e);
            return AdminApiResponse.success(new TemplatePreviewResult(buildErrorHtml(e), null));
        }
    }

    /**
     * 预览列表页模板
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径，如 news/index.html
     * @param columnId 栏目 ID（用于获取模拟数据）
     * @return 渲染后的 HTML 和静态文件预览 URL
     */
    @GetMapping("/list")
    public AdminApiResponse<TemplatePreviewResult> previewListTemplate(
            @PathVariable Long siteId,
            @RequestParam String templatePath,
            @RequestParam Long columnId) {
        log.info(
                "预览列表页模板: siteId={}, templatePath={}, columnId={}", siteId, templatePath, columnId);

        try {
            String renderedHtml =
                    previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            String staticUrl = previewService.getStaticPreviewUrl(siteId, templatePath);
            return AdminApiResponse.success(new TemplatePreviewResult(renderedHtml, staticUrl));
        } catch (Exception e) {
            log.error("预览列表页模板失败", e);
            return AdminApiResponse.success(new TemplatePreviewResult(buildErrorHtml(e), null));
        }
    }

    /**
     * 预览详情页模板
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径，如 news/details.html
     * @param columnId 栏目 ID
     * @param contentId 内容 ID（可选，用于获取具体内容数据）
     * @return 渲染后的 HTML 和静态文件预览 URL
     */
    @GetMapping("/detail")
    public AdminApiResponse<TemplatePreviewResult> previewDetailTemplate(
            @PathVariable Long siteId,
            @RequestParam String templatePath,
            @RequestParam Long columnId,
            @RequestParam(required = false) Long contentId) {
        log.info(
                "预览详情页模板: siteId={}, templatePath={}, columnId={}, contentId={}",
                siteId,
                templatePath,
                columnId,
                contentId);

        try {
            String renderedHtml =
                    previewService.renderDetailTemplatePreview(
                            siteId, templatePath, columnId, contentId != null ? contentId : 1L);
            String staticUrl = previewService.getStaticPreviewUrl(siteId, templatePath);
            return AdminApiResponse.success(new TemplatePreviewResult(renderedHtml, staticUrl));
        } catch (Exception e) {
            log.error("预览详情页模板失败", e);
            return AdminApiResponse.success(new TemplatePreviewResult(buildErrorHtml(e), null));
        }
    }

    /**
     * 通用模板预览（自动判断模板类型）
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径
     * @param columnId 栏目 ID（可选）
     * @param contentId 内容 ID（可选）
     * @return 渲染后的 HTML 和静态文件预览 URL
     */
    @GetMapping("/auto")
    public AdminApiResponse<TemplatePreviewResult> previewTemplate(
            @PathVariable Long siteId,
            @RequestParam String templatePath,
            @RequestParam(required = false) Long columnId,
            @RequestParam(required = false) Long contentId) {
        log.info(
                "自动预览模板: siteId={}, templatePath={}, columnId={}, contentId={}",
                siteId,
                templatePath,
                columnId,
                contentId);

        try {
            // 根据路径判断模板类型
            boolean isCommonTemplate = templatePath.startsWith("inc/");
            boolean isDetailTemplate =
                    templatePath.contains("detail") || templatePath.contains("details");

            String renderedHtml;
            if (isCommonTemplate) {
                renderedHtml = previewService.renderCommonTemplatePreview(siteId, templatePath);
            } else if (isDetailTemplate && columnId != null) {
                renderedHtml =
                        previewService.renderDetailTemplatePreview(
                                siteId, templatePath, columnId, contentId != null ? contentId : 1L);
            } else if (columnId != null) {
                renderedHtml =
                        previewService.renderListTemplatePreview(siteId, templatePath, columnId);
            } else {
                // 默认使用公共模板预览
                renderedHtml = previewService.renderCommonTemplatePreview(siteId, templatePath);
            }

            String staticUrl = previewService.getStaticPreviewUrl(siteId, templatePath);
            return AdminApiResponse.success(new TemplatePreviewResult(renderedHtml, staticUrl));
        } catch (Exception e) {
            log.error("预览模板失败", e);
            return AdminApiResponse.success(new TemplatePreviewResult(buildErrorHtml(e), null));
        }
    }

    private String buildErrorHtml(Exception e) {
        // 获取异常堆栈信息
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            stackTrace.append("    at ").append(element.toString()).append("\n");
            // 只显示前20行堆栈
            if (stackTrace.length() > 1000000) {
                stackTrace.append("    ...(更多堆栈信息已省略)\n");
                break;
            }
        }

        return "<div class=\"preview-error\" style=\"padding: 20px; background: #fff3f3; border:"
                + " 1px solid #ffcdd2; border-radius: 8px;\"><h3 style=\"color: #c62828; margin:"
                + " 0 0 10px;\">预览失败</h3><p style=\"color: #666; margin: 0 0 10px;\">"
                + escapeHtml(e.getMessage())
                + "</p><details style=\"margin-top: 10px;\"><summary style=\"cursor: pointer;"
                + " color: #1976d2; font-size: 13px;\">点击查看详细堆栈信息</summary><pre style=\"background:"
                + " #f5f5f5; padding: 10px; border-radius: 4px; overflow: auto; font-size: 11px;"
                + " max-height: 300px; white-space: pre-wrap; word-break: break-word; margin-top:"
                + " 10px;\">"
                + escapeHtml(stackTrace.toString())
                + "</pre></details></div>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 获取模板文件的绝对路径
     *
     * <p>用于 AI 辅助修复功能，让 AI 能够直接修改模板文件
     *
     * @param siteId 站点 ID
     * @param templatePath 模板路径，如 inc/header.html 或 news/index.html
     * @return 模板文件的绝对路径
     */
    @GetMapping("/template-absolute-path")
    public AdminApiResponse<TemplateAbsolutePathResult> getTemplateAbsolutePath(
            @PathVariable Long siteId, @RequestParam String templatePath) {
        log.info("获取模板绝对路径: siteId={}, templatePath={}", siteId, templatePath);

        try {
            String absolutePath = previewService.getTemplateAbsolutePath(siteId, templatePath);
            return AdminApiResponse.success(
                    new TemplateAbsolutePathResult(absolutePath, templatePath));
        } catch (Exception e) {
            log.error("获取模板绝对路径失败", e);
            return AdminApiResponse.success(new TemplateAbsolutePathResult(null, templatePath));
        }
    }

    /**
     * 预览模板内容（接收模板字符串而非文件路径）
     *
     * <p>用于编辑器中实时预览，无需保存模板文件
     *
     * @param siteId 站点 ID
     * @param request 包含 templateContent 和可选的 columnId、contentId
     * @return 渲染后的 HTML
     */
    @PostMapping("/content")
    public AdminApiResponse<TemplatePreviewResult> previewTemplateContent(
            @PathVariable Long siteId, @RequestBody TemplateContentPreviewRequest request) {
        log.info(
                "预览模板内容: siteId={}, columnId={}, contentId={}",
                siteId,
                request.columnId(),
                request.contentId());

        try {
            if (request.templateContent() == null || request.templateContent().isBlank()) {
                return AdminApiResponse.success(
                        new TemplatePreviewResult(
                                "<div class=\"preview-error\"><h3>模板内容为空</h3></div>", null));
            }

            String renderedHtml =
                    previewService.renderTemplateContentPreview(
                            siteId,
                            request.templateContent(),
                            request.columnId(),
                            request.contentId());
            return AdminApiResponse.success(new TemplatePreviewResult(renderedHtml, null));
        } catch (Exception e) {
            log.error("预览模板内容失败", e);
            return AdminApiResponse.success(new TemplatePreviewResult(buildErrorHtml(e), null));
        }
    }

    /** 模板内容预览请求 */
    public record TemplateContentPreviewRequest(
            /** 模板内容字符串 */
            String templateContent,
            /** 栏目 ID（可选） */
            Long columnId,
            /** 内容 ID（可选） */
            Long contentId) {}

    /** 模板预览结果 */
    public record TemplatePreviewResult(
            /** 渲染后的 HTML */
            String renderedHtml,
            /** 对应的静态文件预览 URL */
            String staticPreviewUrl) {}

    /** 模板绝对路径结果 */
    public record TemplateAbsolutePathResult(
            /** 模板文件绝对路径 */
            String absolutePath,
            /** 模板相对路径 */
            String templatePath) {}
}

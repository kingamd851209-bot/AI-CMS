package com.xinhongru.ai.context;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.service.oneshot.OneShotService;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class AiTemplateContextService {
    private static final int LEGACY_TYPE_SINGLE_PAGE = 1;
    private static final int LEGACY_TYPE_LIST_DISPLAY = 2;
    private static final int LEGACY_TYPE_LIST_SEARCH = 3;

    private final OneShotService oneShotService;
    private final Path templateRoot;

    public AiTemplateContextService(
            OneShotService oneShotService,
            @Value("${cms.template.root:cms-boot/src/main/resources/templates}")
                    String templateRoot) {
        this.oneShotService = oneShotService;
        this.templateRoot = Path.of(templateRoot).toAbsolutePath().normalize();
    }

    public AiTemplateContext load(Integer menuId, Integer legacyFieldSettingType) {
        if (menuId == null || menuId <= 0) {
            throw new IllegalArgumentException("menuId is required");
        }
        int legacyType = legacyFieldSettingType == null ? 0 : legacyFieldSettingType;
        OneshotEntity menu = oneShotService.getModelById(menuId);
        if (menu == null) {
            throw new IllegalArgumentException("menu does not exist: " + menuId);
        }

        String templatePath = StringUtils.defaultString(selectTemplatePath(menu));
        TemplateReadResult template = readTemplate(templatePath);
        return new AiTemplateContext(
                menu.getId(),
                menu.getClassName(),
                legacyType,
                templatePath,
                template.html(),
                selectExistingFieldJson(menu, legacyType),
                template.found(),
                template.message());
    }

    private String selectTemplatePath(OneshotEntity menu) {
        if (StringUtils.isNotBlank(menu.getListPageTemplate())) {
            return menu.getListPageTemplate();
        }
        return StringUtils.defaultString(menu.getDetailPageTemplate());
    }

    private String selectExistingFieldJson(OneshotEntity menu, int legacyType) {
        return switch (legacyType) {
            case LEGACY_TYPE_SINGLE_PAGE -> StringUtils.defaultString(menu.getMenuField());
            case LEGACY_TYPE_LIST_DISPLAY -> StringUtils.defaultString(menu.getModelList());
            case LEGACY_TYPE_LIST_SEARCH -> StringUtils.defaultString(menu.getModelSearch());
            default -> StringUtils.defaultString(menu.getModelField());
        };
    }

    private TemplateReadResult readTemplate(String templatePath) {
        if (StringUtils.isBlank(templatePath)) {
            return TemplateReadResult.notFound("", "当前栏目未配置模板文件，可手动粘贴 HTML");
        }

        Path rootRealPath;
        Path targetRealPath;
        try {
            rootRealPath = templateRoot.toRealPath();
            targetRealPath =
                    templateRoot.resolve(templatePath).toAbsolutePath().normalize().toRealPath();
        } catch (IOException ignored) {
            return TemplateReadResult.notFound("", "未找到模板文件，可手动粘贴 HTML");
        }

        if (!targetRealPath.startsWith(rootRealPath)) {
            return TemplateReadResult.notFound("", "模板路径不在允许的模板根目录内，可手动粘贴 HTML");
        }
        if (!Files.isRegularFile(targetRealPath)) {
            return TemplateReadResult.notFound("", "未找到模板文件，可手动粘贴 HTML");
        }
        try {
            return new TemplateReadResult(Files.readString(targetRealPath), true, "已自动加载模板上下文");
        } catch (IOException ignored) {
            return TemplateReadResult.notFound("", "模板文件读取失败，可手动粘贴 HTML");
        }
    }

    private record TemplateReadResult(String html, boolean found, String message) {
        private static TemplateReadResult notFound(String html, String message) {
            return new TemplateReadResult(html, false, message);
        }
    }
}

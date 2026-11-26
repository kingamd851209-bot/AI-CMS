package com.xinhongru.cms.template;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 站点模板文件存储：模板文件（内容模板、基础模板）统一保存在 {@code {cms.ui-package.templateRoot}/{projectCode}/} 目录下。 */
@Service
public class TemplateStorageService {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final Path templateRoot;

    public TemplateStorageService(
            @Value("${cms.ui-package.templateRoot:${user.dir}/runtime/template}") String root) {
        this.templateRoot = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.templateRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("模板根目录创建失败", ex);
        }
    }

    public Path root() {
        return templateRoot;
    }

    /** 站点模板目录：{templateRoot}/{projectCode} */
    public Path resolveTemplateDir(String projectCode) {
        if (projectCode == null || !SAFE_NAME.matcher(projectCode).matches()) {
            throw new IllegalArgumentException("站点编码格式无效");
        }
        return templateRoot.resolve(projectCode);
    }
}

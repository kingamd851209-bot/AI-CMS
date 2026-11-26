package com.xinhongru.config;

import com.xinhongru.properties.BusinessStorageProperties;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 删除DAM后保留生产共享存储校验，不再创建任何私有目录。 */
@Component
public class BusinessStorageValidator implements InitializingBean {
    private final BusinessStorageProperties properties;
    private final PublicStorageRoots publicRoots;
    private final Environment environment;

    public BusinessStorageValidator(
            BusinessStorageProperties properties,
            PublicStorageRoots publicRoots,
            Environment environment) {
        this.properties = properties;
        this.publicRoots = publicRoots;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        publicRoots.resolve("");
        if (!properties.isSharedRequired()) return;
        String value = properties.getSharedRoot();
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute())
            throw new IllegalStateException("cms.storage.shared-root 必须指定共享存储绝对路径");
        Path shared = Path.of(value).toRealPath();
        requireRoot(shared, publicRoots.configuredRoot());
        for (String key :
                new String[] {
                    "cms.ui-package.root",
                    "cms.ui-package.templateRoot",
                    "cms.html.save-root",
                    "cms.ai.site.workbench.resource-root",
                    "cms.template.traditional-root"
                }) {
            String root = environment.getProperty(key);
            if (root == null || root.isBlank())
                throw new IllegalStateException("共享存储模式必须配置 " + key);
            requireRoot(shared, Path.of(root));
        }
    }

    private void requireRoot(Path shared, Path root) throws Exception {
        if (!root.isAbsolute()
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(root)) throw new IllegalStateException("共享业务目录必须已存在且可写");
        if (!root.toRealPath().startsWith(shared))
            throw new IllegalStateException("业务目录必须位于共享存储根目录下");
    }
}

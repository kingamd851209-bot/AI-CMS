package com.xinhongru.site.template;

import java.util.Optional;

/** Site template operations used by content/language synchronization. */
public interface TemplateFileAccess {
    String buildContentTemplateDir(Long columnId);

    Optional<String> getTemplateContent(Long siteId, String templatePath);

    String saveTemplateContent(Long siteId, String templatePath, String content);
}

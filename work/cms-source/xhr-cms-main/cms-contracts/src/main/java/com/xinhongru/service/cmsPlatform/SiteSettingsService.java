package com.xinhongru.service.cmsPlatform;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 站点设置：栏目上线配置（列表页/详情页 的 301/302/多语言回退/跨站映射）+ Excel 导入导出 */
public interface SiteSettingsService {
    record SettingDto(
            String pageUrl,
            String redirect301,
            String redirect302,
            String fallbackLang,
            String crossMappingJson) {}

    record ColumnConfigDto(
            Integer columnId, String columnName, SettingDto list, SettingDto detail) {}

    List<ColumnConfigDto> childrenConfigs(Long siteId, Integer parentColumnId);

    SettingDto get(Long siteId, Integer columnId, String kind);

    boolean save(
            Long siteId,
            Integer columnId,
            String columnName,
            Integer parentId,
            String kind,
            String pageUrl,
            String redirect301,
            String redirect302,
            String fallbackLang,
            String crossMappingJson);

    void writeTemplateXlsx(HttpServletResponse response, Long siteId, Integer parentColumnId)
            throws Exception;

    void writeExportXlsx(HttpServletResponse response, Long siteId, Integer parentColumnId)
            throws Exception;

    int importXlsx(MultipartFile file, Long siteId);
}

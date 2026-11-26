package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentRecordService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 人工明确映射后导入一条草稿；所有值重新从服务器页面提取。 */
@Service
public class TemplateSeedImportService {
    private final TemplateSeedService seeds;
    private final OneShotMapper menus;
    private final ContentMapper contents;
    private final ContentRecordService records;

    public TemplateSeedImportService(TemplateSeedService seeds, OneShotMapper menus,
                                     ContentMapper contents, ContentRecordService records) {
        this.seeds = seeds;
        this.menus = menus;
        this.contents = contents;
        this.records = records;
    }

    public record ImportRequest(Long menuId, String sourcePath, String digest, Map<String, String> mapping) {}
    public record ImportResult(Long recordId, boolean alreadyImported) {}
    public record Field(String fieldName, String title, String fieldType) {}

    private List<ContentFieldDefinition> model(Long siteId, Long menuId) {
        if (menuId == null || menuId <= 0) throw new IllegalArgumentException("请选择目标栏目");
        var menu = menus.getModelById(Math.toIntExact(menuId));
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId)) {
            throw new IllegalArgumentException("栏目不属于当前站点");
        }
        if (Boolean.FALSE.equals(menu.getHasManageChannel())
                || (!Integer.valueOf(0).equals(menu.getPageType()) && !Integer.valueOf(1).equals(menu.getPageType()))) {
            throw new IllegalArgumentException("仅支持已开启后台管理的列表/详情内容栏目");
        }
        return ContentModelCodec.read(menu.getModelField());
    }

    private boolean supported(ContentFieldDefinition field) {
        return field != null && field.getFieldName() != null
                && field.getFieldName().matches("title|subtitle|singleLine[0-9]+|note[0-9]+|content[0-9]+|photo[0-9]+|media[0-9]+|file[0-9]+")
                && ContentFieldDefinition.enabled(field.getIsOpen())
                && !ContentFieldDefinition.enabled(field.getIsReadOnly())
                && (field.getInputType() == null || field.getInputType().isBlank());
    }

    public List<Field> fields(Long siteId, Long menuId) {
        return model(siteId, menuId).stream().filter(this::supported)
                .map(f -> new Field(f.getFieldName(), f.getTitle(), f.getFieldType())).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importDraft(Long siteId, String code, String version, Long operatorId, ImportRequest request) {
        if (operatorId == null || request == null || request.mapping() == null
                || request.mapping().isEmpty() || request.mapping().size() > 100
                || !request.mapping().containsKey("title")) {
            throw new IllegalArgumentException("必须明确映射标题及需要导入的字段");
        }
        if (request.menuId() == null || request.menuId() <= 0) throw new IllegalArgumentException("请选择目标栏目");
        if (menus.lockSeedMenu(siteId, request.menuId()) == null) {
            throw new IllegalArgumentException("栏目不属于当前站点");
        }
        var definitions = model(siteId, request.menuId());
        var preview = seeds.preview(code, version, request.sourcePath());
        if (request.digest() == null || !request.digest().equals(preview.digest())) {
            throw new IllegalArgumentException("页面已变化，请重新提取并确认映射");
        }
        Map<String, TemplateSeedService.Item> items = new LinkedHashMap<>();
        preview.items().forEach(item -> items.put(item.id(), item));
        Map<String, Object> values = new LinkedHashMap<>();
        for (var mapping : request.mapping().entrySet()) {
            var definition = definitions.stream().filter(this::supported)
                    .filter(f -> f.getFieldName().equals(mapping.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("字段未开启或不支持静态导入: " + mapping.getKey()));
            var item = items.get(mapping.getValue());
            if (item == null) throw new IllegalArgumentException("提取项不存在，请重新预览");
            String field = definition.getFieldName();
            boolean resource = field.matches("(photo|media|file)[0-9]+");
            if (resource && !Set.of("IMAGE", "LINK").contains(item.kind())) {
                throw new IllegalArgumentException("资源字段必须映射图片或链接");
            }
            String value = resource ? item.value() : item.text();
            if (resource) value = resourceReference(value, request.sourcePath());
            // 富文本字段只导入转义后的静态文字，避免把页面内容解释为 HTML。
            if (field.startsWith("content")) value = org.jsoup.nodes.Entities.escape(value);
            values.put(field, value);
        }
        for (var field : definitions) {
            if (ContentFieldDefinition.enabled(field.getIsOpen())
                    && ContentFieldDefinition.enabled(field.getIsRequired())
                    && !ContentFieldDefinition.enabled(field.getIsReadOnly())
                    && (!values.containsKey(field.getFieldName())
                    || String.valueOf(values.get(field.getFieldName())).isBlank())) {
                throw new IllegalArgumentException("请映射必填字段: " + field.getFieldName());
            }
        }
        String title = String.valueOf(values.get("title"));
        if (title.isBlank()) throw new IllegalArgumentException("标题不能为空");
        if (contents.countSeedTitle(siteId, request.menuId(), title) > 0) {
            throw new IllegalArgumentException("当前栏目已存在同标题内容，请到内容管理中核对，禁止重复导入");
        }
        var result = records.saveDraft(new ContentRecordService.SaveDraftCommand(siteId,
                request.menuId(), null, Map.of(), values, operatorId));
        return new ImportResult(result.recordId(), false);
    }

    static String resourceReference(String raw, String sourcePath) {
        String value = raw.trim();
        if (value.isBlank() || value.contains("${") || value.contains("@{")
                || value.contains("\\") || value.contains("%") || value.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("资源地址格式无效");
        }
        try {
            var uri = new java.net.URI(value);
            if (uri.isAbsolute()) {
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("资源仅支持 HTTP(S) 或站内路径");
                }
                return value;
            }
            if (value.startsWith("//")) throw new IllegalArgumentException("不支持协议相对地址");
            var base = new java.net.URI("https://seed.invalid/" + sourcePath);
            var resolved = base.resolve(uri).normalize();
            String path = resolved.getPath();
            if (path == null || path.equals("/") || path.contains("/../")) {
                throw new IllegalArgumentException("资源路径无效");
            }
            return "ui:/" + path.substring(1)
                    + (resolved.getRawQuery() == null ? "" : "?" + resolved.getRawQuery())
                    + (resolved.getRawFragment() == null ? "" : "#" + resolved.getRawFragment());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("资源地址格式无效", e);
        }
    }
}

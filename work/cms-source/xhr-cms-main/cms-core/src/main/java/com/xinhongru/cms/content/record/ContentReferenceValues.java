package com.xinhongru.cms.content.record;

import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentReferences;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;

import org.springframework.stereotype.Service;

import java.util.*;

/** 关联只保存本站明确来源栏目的原生内容ID；公开读取只展开一层，不递归追踪引用。 */
@Service
public class ContentReferenceValues {
    private final OneShotMapper menus;
    private final ContentMapper contents;

    public ContentReferenceValues(OneShotMapper menus, ContentMapper contents) {
        this.menus = menus;
        this.contents = contents;
    }

    public void validateModel(Long siteId, ContentFieldDefinition field) {
        requireSite(siteId);
        ContentExtensionFields.validateDefinition(field);
        for (Integer menuId : ContentReferences.menuIds(field)) {
            var menu = menus.selectActiveMenuBySite(siteId, menuId);
            if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
                throw new IllegalArgumentException("关联来源栏目不存在或不属于当前站点");
        }
    }

    public String prepare(Long siteId, ContentFieldDefinition field, Object value) {
        requireSite(siteId);
        String text = ContentReferences.normalize(field, value);
        ContentExtensionFields.put(null, field, text);
        List<Integer> ids = ContentReferences.ids(field, text);
        if (!ids.isEmpty()) {
            validateModel(siteId, field);
            var rows =
                    contents.selectReferenceArticles(
                            siteId, ContentReferences.menuIds(field), ids, false);
            Set<Integer> returned = new HashSet<>();
            for (var row : rows) {
                if (!ids.contains(row.getId()) || !returned.add(row.getId()))
                    throw new IllegalArgumentException("关联内容已变化，请重新选择");
            }
            if (returned.size() != ids.size())
                throw new IllegalArgumentException("关联内容不存在、已删除或不属于当前站点的来源栏目");
        }
        return text;
    }

    /** 站点复制/同步不能把原站点ID直接写入新站点。 */
    public String validateStored(Long siteId, String json, List<ContentFieldDefinition> fields) {
        if (fields == null || fields.stream().noneMatch(ContentReferences::applies)) return json;
        var values = ContentExtensionFields.read(json);
        for (var field : fields) {
            if (ContentReferences.applies(field)
                    && ContentFieldDefinition.enabled(field.getIsOpen()))
                values.put(
                        field.getFieldName(),
                        prepare(siteId, field, values.get(field.getFieldName())));
        }
        return com.alibaba.fastjson.JSON.toJSONString(values);
    }

    /** 不公开草稿、已删/停用栏目或矛盾归属内容；失效引用按缺失项跳过，保留有效项人工顺序。 */
    public List<ContentEntity> published(Long siteId, ContentFieldDefinition field, Object value) {
        requireSite(siteId);
        if (!ContentFieldDefinition.enabled(field.getIsOpen())) return List.of();
        List<Integer> ids = ContentReferences.ids(field, value);
        if (ids.isEmpty()) return List.of();
        var byId = new LinkedHashMap<Integer, ContentEntity>();
        for (var row :
                contents.selectReferenceArticles(
                        siteId, ContentReferences.menuIds(field), ids, true))
            byId.put(row.getId(), row);
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static void requireSite(Long siteId) {
        if (siteId == null || siteId <= 0) throw new IllegalArgumentException("关联内容需要明确站点");
    }
}

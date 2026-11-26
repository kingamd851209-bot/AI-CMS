package com.xinhongru.cms.search;

import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.mapper.OneShotMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 仅在源写事务中重建同表派生文本，当前栏目锁与所有写入共用。 */
@Service
public class PublicSearchProjectionService {
    private static final int BATCH_SIZE = 20;
    private final PublicSearchMapper mapper;
    private final OneShotMapper menus;

    public PublicSearchProjectionService(PublicSearchMapper mapper, OneShotMapper menus) {
        this.mapper = mapper;
        this.menus = menus;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void rebuildMenu(Long siteId, Integer menuId) {
        if (siteId == null || menuId == null) throw new IllegalArgumentException("投影重建缺少站点/栏目");
        var menu = menus.lockFieldConfiguration(siteId, menuId.longValue());
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("投影栏目不属于当前站点");
        if (Integer.valueOf(0).equals(menu.getPageType())) {
            String text =
                    PublicSearchText.forSinglePage(
                            ContentModelCodec.read(menu.getMenuField()), menu, null);
            mapper.writeSingleProjection(siteId, menuId, text);
            return;
        }
        var fields = ContentModelCodec.read(menu.getModelField());
        int after = 0;
        while (true) {
            var rows = mapper.projectionBatch(siteId, menuId, after, BATCH_SIZE);
            if (rows.isEmpty()) break;
            var updates = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                if (row.getId() == null || row.getId() <= after)
                    throw new IllegalStateException("投影重建游标未前进");
                updates.add(
                        Map.of(
                                "id",
                                row.getId(),
                                "text",
                                PublicSearchText.forContent(fields, row, null)));
                after = row.getId();
            }
            mapper.writeContentProjections(siteId, menuId, updates);
        }
    }
}

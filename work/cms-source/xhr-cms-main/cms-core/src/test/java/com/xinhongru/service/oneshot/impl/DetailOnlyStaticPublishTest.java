package com.xinhongru.service.oneshot.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinhongru.entity.OneshotEntity;

import org.junit.jupiter.api.Test;

class DetailOnlyStaticPublishTest {
    @Test
    void directPublishingAcceptsBoundDetailsWhileStagingStillRequiresTheListTemplate() {
        var service = new OneShotServiceImpl();
        var menu = new OneshotEntity();
        menu.setId(42);
        menu.setSiteRootId(1);
        menu.setClassName("研究与资讯");
        menu.setPageType(1);
        menu.setHasFrontChannel(true);
        menu.setListPageUrl("/news/list/{pageIndex}.html");
        menu.setDetailPageUrl("/news/detail/{id}.html");
        menu.setDetailPageTemplate("cms-applied/shared-detail.html");

        assertDoesNotThrow(() -> service.validatePublishMenu(menu, true));
        assertTrue(OneShotServiceImpl.publishDetailsOnly(menu, true));
        assertFalse(OneShotServiceImpl.publishDetailsOnly(menu, false));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.validatePublishMenu(menu, false)).getMessage().contains("绑定模板"));

        menu.setDetailPageUrl("//outside.example/news/detail/{id}.html");
        assertFalse(OneShotServiceImpl.publishDetailsOnly(menu, true));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublishMenu(menu, true));
        menu.setDetailPageUrl("/news/detail/{id}.html");
        menu.setDetailPageTemplate(null);
        assertFalse(OneShotServiceImpl.publishDetailsOnly(menu, true));
        assertThrows(IllegalArgumentException.class, () -> service.validatePublishMenu(menu, true));

        menu.setListPageTemplate("cms-applied/list.html");
        menu.setDetailPageTemplate("cms-applied/shared-detail.html");
        assertFalse(OneShotServiceImpl.publishDetailsOnly(menu, true));
        assertDoesNotThrow(() -> service.validatePublishMenu(menu, false));
    }
}

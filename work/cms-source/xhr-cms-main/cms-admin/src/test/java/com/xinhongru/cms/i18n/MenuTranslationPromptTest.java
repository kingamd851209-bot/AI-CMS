package com.xinhongru.cms.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xinhongru.entity.OneshotEntity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MenuTranslationPromptTest {
    private static OneshotEntity menu(int id, String name) {
        OneshotEntity entity = new OneshotEntity();
        entity.setId(id);
        entity.setClassName(name);
        return entity;
    }

    @Test
    void parsesIdPipeNameLines() {
        List<OneshotEntity> menus = List.of(menu(1, "新闻"), menu(2, "产品"));
        Map<Integer, String> result = MenuTranslationPrompt.parse("1|News\n2|Products\n", menus);
        assertEquals("News", result.get(1));
        assertEquals("Products", result.get(2));
    }

    @Test
    void stripsAlreadySuffix() {
        List<OneshotEntity> menus = List.of(menu(1, "新闻"));
        Map<Integer, String> result =
                MenuTranslationPrompt.parse("1|News - already English\n", menus);
        assertEquals("News", result.get(1));
    }

    @Test
    void throwsWhenAnyMenuMissing() {
        List<OneshotEntity> menus = List.of(menu(1, "新闻"), menu(2, "产品"));
        assertThrows(
                IllegalStateException.class, () -> MenuTranslationPrompt.parse("1|News\n", menus));
    }
}

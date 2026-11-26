package com.xinhongru.cms.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ContentTranslationServiceTest {
    @Test
    void toItemsPreservesSourceOrder() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("title", "Hello");
        source.put("note", "World");
        source.put("body", "Text");
        Map<String, String> translated = new LinkedHashMap<>();
        translated.put("body", "正文");
        translated.put("title", "你好");
        translated.put("note", "世界");

        List<ContentTranslationItem> items = ContentTranslationService.toItems(source, translated);

        assertEquals(3, items.size());
        assertEquals("title", items.get(0).fieldKey());
        assertEquals("note", items.get(1).fieldKey());
        assertEquals("body", items.get(2).fieldKey());
    }

    @Test
    void toItemsUsesEmptyTranslatedTextForMissingKey() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("title", "Hello");
        source.put("subtitle", "Sub");
        Map<String, String> translated = new LinkedHashMap<>();
        translated.put("title", "你好");

        List<ContentTranslationItem> items = ContentTranslationService.toItems(source, translated);

        assertEquals("", items.get(1).translatedText());
    }

    @Test
    void toItemsEchoesSourceAndUsesTranslatedValueWhenPresent() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("title", "Hello");
        Map<String, String> translated = new LinkedHashMap<>();
        translated.put("title", "你好");

        List<ContentTranslationItem> items = ContentTranslationService.toItems(source, translated);

        assertEquals(1, items.size());
        assertEquals("Hello", items.get(0).sourceText());
        assertEquals("你好", items.get(0).translatedText());
    }
}

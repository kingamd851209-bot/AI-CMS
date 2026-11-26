package com.xinhongru.cms.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class ContentTranslationPromptTest {
    @Test
    void buildListsFieldKeyAndFlattensLineBreaks() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", "Hello");
        values.put("note1", "Line one\nLine two");

        String prompt = ContentTranslationPrompt.build(values, "en-US");

        assertTrue(prompt.contains("title|Hello"));
        assertTrue(prompt.contains("note1|Line one{{BR}}Line two"));
        assertFalse(prompt.contains("Line one\nLine two"));
    }

    @Test
    void parseRestoresLineBreaks() {
        Map<String, String> parsed = ContentTranslationPrompt.parse("title|News\nnote1|A{{BR}}B\n");
        assertEquals("News", parsed.get("title"));
        assertEquals("A\nB", parsed.get("note1"));
    }

    @Test
    void parseKeepsFirstPipeAsSeparator() {
        Map<String, String> parsed = ContentTranslationPrompt.parse("note1|a|b\n");
        assertEquals("a|b", parsed.get("note1"));
    }

    @Test
    void parseIgnoresLinesWithoutPipe() {
        Map<String, String> parsed = ContentTranslationPrompt.parse("garbage\n\nx|y\n");
        assertFalse(parsed.containsKey("garbage"));
        assertEquals("y", parsed.get("x"));
    }
}

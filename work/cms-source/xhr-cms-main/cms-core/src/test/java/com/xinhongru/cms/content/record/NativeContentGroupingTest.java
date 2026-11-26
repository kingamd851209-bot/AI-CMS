package com.xinhongru.cms.content.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xinhongru.entity.OneshotEntity;

import org.junit.jupiter.api.Test;

class NativeContentGroupingTest {
    private final OneshotEntity menu = new OneshotEntity().setModelField("""
            [
              {"fieldName":"singleLine2","fieldType":"SingleLine","isOpen":"1"},
              {"fieldName":"singleLine3","fieldType":"SingleLine","isOpen":"0"},
              {"fieldName":"note1","fieldType":"MultiLine","isOpen":"1"},
              {"fieldName":"ext_a1234567890123456789012345678901","fieldType":"SingleLine","isOpen":"1"},
              {"fieldName":"sort","fieldType":"SingleLine","isOpen":"1"}
            ]
            """);

    @Test
    void exactGroupingUsesOnlyConfiguredLegacyColumnsAndBoundValues() {
        var condition = NativeContentAdminQueryService.groupCriterion(menu, "singleLine2", "2025%_");
        assertEquals("single_line2", condition.column());
        assertEquals("groupExact", condition.op());
        assertEquals("2025%_", condition.value());
        assertEquals("", NativeContentAdminQueryService.groupCriterion(menu, "singleLine2", "").value());
    }

    @Test
    void enabledExtensionUsesOnlyItsBoundKey() {
        String name = "ext_a1234567890123456789012345678901";
        var condition = NativeContentAdminQueryService.groupCriterion(menu, name, "2025");
        assertNull(condition.column());
        assertEquals(name, condition.extensionKey());
    }

    @Test
    void rejectsDisabledNonTextMissingAndSystemFields() {
        for (String field : new String[]{null, "singleLine3", "note1", "singleLine8", "sort"})
            assertThrows(IllegalArgumentException.class,
                    () -> NativeContentAdminQueryService.groupCriterion(menu, field, "2025"));
    }
}

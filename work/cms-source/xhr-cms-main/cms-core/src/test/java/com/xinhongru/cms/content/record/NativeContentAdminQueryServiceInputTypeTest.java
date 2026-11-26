package com.xinhongru.cms.content.record;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeContentAdminQueryServiceInputTypeTest {
    @Test
    void keepsEnumContentAndMenu() {
        assertThat(NativeContentAdminQueryService.normalizeInputType("enum")).isEqualTo("enum");
        assertThat(NativeContentAdminQueryService.normalizeInputType("content"))
                .isEqualTo("content");
        assertThat(NativeContentAdminQueryService.normalizeInputType("menu")).isEqualTo("menu");
    }

    @Test
    void fallsBackToManualForOthers() {
        assertThat(NativeContentAdminQueryService.normalizeInputType(null)).isEqualTo("");
        assertThat(NativeContentAdminQueryService.normalizeInputType("  ")).isEqualTo("");
        assertThat(NativeContentAdminQueryService.normalizeInputType("unknown")).isEqualTo("");
    }
}

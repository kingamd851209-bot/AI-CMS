package com.xinhongru.web.select;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class SelectQuerySyntaxTest {
    @Test
    void parsesSingleParentChildrenQuery() {
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$109_children$}"))
                .isEqualTo(List.of(109));
    }

    @Test
    void parsesMultipleParentChildrenQuery() {
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$101,102_children$}"))
                .isEqualTo(List.of(101, 102));
    }

    @Test
    void dedupsRepeatedParentIds() {
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$5,5_children$}"))
                .isEqualTo(List.of(5));
    }

    @Test
    void returnsEmptyForNonChildrenQueries() {
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$101,102_title_id$}")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$101_child_HjQs1ZcVaF9K27bh_5$}"))
                .isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("新闻,产品")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds(null)).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$abc_children$}")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$}")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$109_children_$}")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$_children$}")).isEmpty();
        assertThat(SelectQuerySyntax.parseChildrenParentIds("{$1_2_children$}")).isEmpty();
    }

    @Test
    void detectsDynamicQuery() {
        assertThat(SelectQuerySyntax.isDynamic("{$1$}")).isTrue();
        assertThat(SelectQuerySyntax.isDynamic("1,2,3")).isFalse();
        assertThat(SelectQuerySyntax.isDynamic(null)).isFalse();
    }
}

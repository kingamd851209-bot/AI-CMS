package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class QueryPagerDraftTest {
    private static final String ORIGINAL = """
            <div class="pageout fnt_20">
              <div class="page">
                <a href="javascript:void(0);" title="上一页" class="prev disabled"><i class="icon-ll1"></i></a>
                <a href="javascript:void(0);" title="1" class="num on">1</a>
                <a href="##" title="2" class="num">2</a>
                <a href="##" title="3" class="num">3</a>
                <a href="##" title="···" class="dian">···</a>
                <a href="##" title="4" class="num">4</a>
                <a href="##" title="5" class="num">5</a>
                <a href="##" title="下一页" class="next"><i class="icon-rr1"></i></a>
              </div>
            </div>
            """;

    @Test
    void suggestsTheObservedControlsAndValidatesWithTheExistingPagerRules() {
        var document = Jsoup.parse(ORIGINAL);
        var draft = QueryPagerControls.suggest(document, List.of());
        assertNotNull(draft);
        assertEquals("div.page", draft.containerSelector());
        assertEquals("a.num", draft.numberSelector());
        assertEquals("on", draft.activeClass());
        assertEquals("disabled", draft.disabledClass());
        assertEquals(Map.of("prev", "a.prev", "next", "a.next"), draft.links());
        assertTrue(draft.ignoredControls().get("a.dian").contains("省略号"));
        assertEquals(document.selectFirst("div.page"), QueryPagerControls.validate(document, draft));
    }

    @Test
    void leavesAmbiguousOrUnsupportedControlsForManualReview() {
        assertNull(QueryPagerControls.suggest(Jsoup.parse(ORIGINAL + ORIGINAL), List.of()));
        for (String uncertain : List.of(
                ORIGINAL.replace("class=\"num on\"", "class=\"num\""),
                ORIGINAL.replace("class=\"prev disabled\"", "class=\"prev\""),
                ORIGINAL.replace("title=\"下一页\"", "title=\"下一页\" aria-label=\"上一页\""),
                ORIGINAL.replace("title=\"5\"", "title=\"5\" onclick=\"jump()\""),
                ORIGINAL.replace("title=\"2\"", "title=\"2\" aria-label=\"下一页\""),
                ORIGINAL.replace("class=\"dian\"", "class=\"dian\" data-jump=\"5\"")
                        .replace("href=\"##\" title=\"···\"", "href=\"?page=6\" title=\"···\""),
                ORIGINAL.replace("</div>", "<input aria-label=\"跳页\"></div>"))) {
            assertNull(QueryPagerControls.suggest(Jsoup.parse(uncertain), List.of()));
        }
        var card = Jsoup.parse("<article>" + ORIGINAL + "</article>");
        assertNull(QueryPagerControls.suggest(card, card.select("article")));
    }
}

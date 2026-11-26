package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PublicNavigationLinkRegionTest {
    @Test
    void recognizesLegacyMainContentWithoutOpeningControlsForBinding() {
        var document = Jsoup.parse("""
                <div class="main">
                  <a href="team.aspx">团队</a>
                  <nav><a href="menu.aspx">菜单控件</a></nav>
                  <a href="action.aspx" onclick="load()">脚本按钮</a>
                  <a href="filter.aspx?category=1">筛选</a>
                </div>
                """);

        assertEquals("BODY", PublicNavigationLinkCompiler.region(document.selectFirst("a[href=team.aspx]")));
        assertNull(PublicNavigationLinkCompiler.region(document.selectFirst("nav a")));
        assertNull(PublicNavigationLinkCompiler.region(document.selectFirst("a[onclick]")));
        assertNull(PublicNavigationLinkCompiler.region(document.selectFirst("a[href^=filter]")));
    }
}

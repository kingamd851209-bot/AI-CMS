package com.xinhongru.cms.template;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SharedPanelTimelineTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "cms.test.honorPackageRoot", matches = ".+")
    void verifiesTheRealPackageUsingTheReviewedRegionAndLocalFragmentReader() {
        var storage = new com.xinhongru.cms.uipackage.UiPackageStorageService(
                java.nio.file.Path.of(System.getProperty("cms.test.honorPackageRoot")));
        String code = "STAGE127JINYU", version = "v1795334331437-52cfc1337650", path = "about/honor.aspx";
        var evidence = new SiteModelEvidenceService(storage).page(code, version, path);
        var reader = storage.openStaticPageReader(code, version);
        var document = Jsoup.parse(reader.read(path).html());
        var targets = new java.util.HashMap<String, String>();
        for (var link : evidence.fragmentLinks()) targets.put(link.rawValue(), link.targetPath());
        var result = evidence.regions().stream().flatMap(r -> SharedPanelTimeline.layouts(document, r,
                targets, target -> reader.read(target).html()).stream()).distinct().toList();
        assertEquals(1, result.size());
        assertEquals(3, result.getFirst().sourceRows());
        assertEquals(6, result.getFirst().labels().size());
        assertEquals("dl > dd > span", result.getFirst().rowSecondarySelector());
    }
    private static String row(String title) {
        return "<li><figure><img src='cover.jpg'></figure><div><time>五月</time><h3>" + title + "</h3></div></li>";
    }
    private static final String CARDS = "<ul>" + row("社区服务") + row("职业教育") + "</ul>";
    private static final String PAGE = "<main><section><aside><ol><li data-src='a.htm'><p>东区</p></li>"
            + "<li data-src='b.htm'><p>西区</p></li><li data-src='a.htm'><p>南区</p></li></ol></aside>"
            + "<div class='records'>" + CARDS + "</div></section></main>";
    private static final SiteModelEvidenceService.Region REGION = new SiteModelEvidenceService.Region(
            "items","LIST",".records > ul", java.util.stream.IntStream.rangeClosed(1, 2)
                    .mapToObj(i -> new SiteModelEvidenceService.SourceRecord("r" + i, i,
                            ".records > ul > li:nth-child(" + i + ")", List.of(), List.of())).toList(), List.of());
    private static List<QueryTimeline.Layout> layouts(String page, String first, String second) {
        return SharedPanelTimeline.layouts(Jsoup.parse(page), REGION, Map.of("a.htm","a.htm","b.htm","b.htm"),
                path -> path.equals("a.htm") ? first : second);
    }
    @Test void recognizesSharedFragmentsWithoutAssigningTheRepeatedSamplesToEveryLabel() {
        var layout = layouts(PAGE, "\uFEFF" + CARDS, "<ul>" + row("其他项目") + "</ul>").getFirst();
        assertEquals(List.of("东区", "西区", "南区"), layout.labels());
        assertEquals(2, layout.sourceRows());
        assertEquals("div > time", layout.rowSecondarySelector());
        assertEquals("div > h3", layout.rowTextSelector());
        assertEquals("figure > img", layout.rowImageSelector());
    }
    @Test void requiresCorroboratedUnambiguousLocalListFragments() {
        assertTrue(layouts(PAGE, null, CARDS).isEmpty());
        assertTrue(layouts(PAGE, CARDS, CARDS.replace("time>", "p>")).isEmpty());
        assertTrue(layouts(PAGE, CARDS, CARDS.replace("<li>", "<li onclick='switchPanel()'>")).isEmpty());
        assertTrue(layouts(PAGE, CARDS, CARDS + "<script>selectTab()</script>").isEmpty());
        String unrelated = "<ul>" + row("另一个栏目") + "</ul>";
        assertTrue(layouts(PAGE, unrelated, unrelated).isEmpty());
        assertTrue(layouts(PAGE.replace("data-src='b.htm'", "data-src='https://example.test/b.htm'"), CARDS, CARDS).isEmpty());
    }
}

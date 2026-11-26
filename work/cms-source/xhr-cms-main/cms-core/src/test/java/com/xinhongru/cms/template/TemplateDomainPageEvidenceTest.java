package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateDomainPageEvidenceTest {
    @Test
    void sharedCarouselArtworkDoesNotMaskDistinctLinkEvidence() {
        String cards = """
                <ul class="swiper-wrapper"><li class="swiper-slide"><a href="first.html">标题</a><img src="same.jpg"></li>
                <li class="swiper-slide"><a href="%s">标题</a><img src="same.jpg"></li></ul>
                """;
        var same = SiteModelEvidenceService.repeatedRegionReview(Jsoup.parse(cards.formatted("first.html")),
                new ArrayList<>()).getFirst();
        var different = SiteModelEvidenceService.repeatedRegionReview(Jsoup.parse(cards.formatted("second.html")),
                new ArrayList<>()).getFirst();
        assertEquals("CAROUSEL_PLACEHOLDER", same.layout());
        assertEquals("CAROUSEL", different.layout());
        assertEquals(2, different.linkPatterns());
    }

    @TempDir Path root;

    @Test
    void loadsBundledAgentRulesWithinTheRuntimeBudget() {
        assertDoesNotThrow(() -> Class.forName(SiteModelPlanService.class.getName()));
        assertDoesNotThrow(() -> Class.forName(TemplateDomainAgentService.class.getName()));
    }

    @Test
    void separatesShellExpansionFromBodyFragmentReview() throws Exception {
        var folder = Files.createDirectories(root.resolve("demo/v1"));
        Files.writeString(folder.resolve("shell.js"), "/* candidate; never executed */");
        Files.writeString(folder.resolve("fragment.html"), "<p>正文片段</p>");
        Files.writeString(folder.resolve("services.html"), "<main><section id='section'>分区</section></main>");
        var result = inspect("""
                <head><script src="shell.js"></script><script src="missing.js"></script></head>
                <header>
                  <button id="menu-trigger" aria-controls="menu">菜单</button>
                  <nav id="menu"><ul>
                    <li id="nested"><a id="landing" href="fragment.html">父栏目</a>
                      <div id="submenu"><a href="fragment.html">子栏目</a></div>
                    </li>
                    <li id="ordinary"><a href="fragment.html">普通入口</a></li>
                    <li data-load="fragment.html">导航里的片段线索</li>
                  </ul></nav>
                </header>
                <main>
                  <button aria-controls="content-panel">正文控件</button><div id="content-panel"></div>
                  <nav aria-label="正文分类">
                    <button data-ajax="fragment.html">分类一</button>
                    <button data-ajax="fragment.html">分类二</button>
                  </nav>
                  <a id="section-link" href="services.html#section">去另一页的分区</a>
                  <a id="same-page" href="#content-panel">本页面板</a>
                  <iframe src="fragment.html" title="正文嵌入"></iframe>
                </main>
                """);
        var nav = result.suggestions().stream()
                .filter(row -> row.kind().equals("NAV_EXPANSION_REVIEW")).toList();
        assertEquals(List.of("#menu-trigger", "#nested"), nav.stream()
                .map(TemplateDomainPageEvidence.Suggestion::selector).toList());
        assertEquals("#menu", nav.get(0).facts().get("panelSelector"));
        assertEquals("#landing", nav.get(1).facts().get("parentLinkSelector"));
        assertEquals(List.of("shell.js"), nav.get(0).facts().get("localScriptCandidates"));
        assertTrue(nav.stream().allMatch(row -> row.targetPath() == null));
        var body = result.suggestions().stream()
                .filter(row -> row.kind().equals("BODY_FRAGMENT_REVIEW")).toList();
        assertEquals(2, body.size());
        assertNotEquals(body.get(0).selector(), body.get(1).selector());
        assertTrue(body.stream().allMatch(row -> row.targetPath().equals("fragment.html")));

        var storage = new UiPackageStorageService(root);
        var page = new SiteModelEvidenceService(storage).page("demo", "v1", "index.html");
        var link = page.links().stream().filter(row -> row.rawValue().equals("services.html#section"))
                .findFirst().orElseThrow();
        var document = Jsoup.parse(storage.readStaticPage("demo", "v1", "index.html").html());
        var trigger = document.select(link.selector());
        assertEquals(1, trigger.size());
        assertEquals("section-link", trigger.first().id());
        assertEquals("services.html#section", trigger.first().attr("href"));
        assertEquals("services.html", link.targetPath());
        assertTrue(page.links().stream().noneMatch(row -> row.rawValue().equals("#content-panel")));
        assertTrue(result.suggestions().stream()
                .noneMatch(row -> row.isInteractionReview() && row.selector().equals("#section-link")));
        assertTrue(result.excludedSourceIds().isEmpty());
    }

    @Test
    void preservesCompleteListsWhenHeadingsAreTruncated() throws Exception {
        var result = inspect("<main><section>" + "<h2>标题</h2>".repeat(25)
                + "</section><ul class='item-list'><li>第一项内容</li><li>第二项内容</li></ul></main>");

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("业务标题超过扫描上限")));
        assertEquals(List.of(2), listCounts(result));
    }

    @Test
    void keepsOnlyScannedListsWhenRepeatRegionsAreTruncated() throws Exception {
        String lists = IntStream.range(0, 25)
                .mapToObj(i -> "<ul class='item-list'><li>第一项" + i + "</li><li>第二项" + i + "</li></ul>")
                .collect(Collectors.joining());
        var result = inspect("<main>" + lists + "</main>");

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("重复区域超过扫描上限")));
        assertEquals(24, listCounts(result).size());
    }

    @Test
    void interactionCandidatesDoNotDisplaceExistingStructureReview() throws Exception {
        var folder = Files.createDirectories(root.resolve("demo/v1"));
        Files.writeString(folder.resolve("fragment.html"), "<p>正文片段</p>");
        String menu = "<header><nav><ul>"
                + "<li><a href='fragment.html'>父栏目</a><div><a href='fragment.html'>子栏目</a></div></li>".repeat(12)
                + "</ul></nav></header>";
        String lists = IntStream.range(0, 24)
                .mapToObj(i -> "<ul class='item-list'><li>第一项" + i + "</li><li>第二项" + i + "</li></ul>")
                .collect(Collectors.joining());
        String fragmentLinks = "<button data-ajax='fragment.html'>分类</button>".repeat(10);
        var result = inspect(menu + "<main>" + lists + fragmentLinks + "</main>");
        assertEquals(24, listCounts(result).size());
        assertEquals(4, result.suggestions().stream()
                .filter(row -> row.kind().equals("NAV_EXPANSION_REVIEW")).count());
        assertEquals(4, result.suggestions().stream()
                .filter(row -> row.kind().equals("BODY_FRAGMENT_REVIEW")).count());
        assertTrue(result.suggestions().stream().filter(row -> !row.isInteractionReview()).count() <= 32);
    }

    @Test
    void leavesStructureForReviewWhenDomTraversalIsIncomplete() throws Exception {
        var result = inspect("<main><ul class='item-list'><li>第一项内容</li><li>第二项内容</li></ul>"
                + "<div>".repeat(35) + "内容" + "</div>".repeat(35) + "</main>");

        assertTrue(result.suggestions().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("未完整扫描")));
        assertTrue(result.excludedSourceIds().isEmpty());
    }

    @Test
    void skipsTruncatedRepeatRecordsAndRetainsIndependentLists() throws Exception {
        var result = inspect("<main><ul class='swiper-wrapper'>"
                + "<li class='swiper-slide'><p>轮播文案</p></li>".repeat(101)
                + "</ul><ul class='item-list'><li>第一项内容</li><li>第二项内容</li></ul></main>");

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("重复区域记录或字段超过取证上限")));
        assertEquals(List.of(2), listCounts(result));
        assertTrue(result.suggestions().stream().noneMatch(suggestion -> "CAROUSEL_COUNTER".equals(suggestion.kind())));
    }

    @Test
    void offersNativeListOrderReviewForACompleteTwoDigitSequence() throws Exception {
        var result = inspect(numberedCards("01", "02", "03"));
        var review = result.suggestions().stream()
                .filter(suggestion -> suggestion.facts().containsKey("displayOrdinal"))
                .findFirst().orElseThrow();
        var ordinal = (SiteModelEvidenceService.DisplayOrdinal) review.facts().get("displayOrdinal");
        assertEquals("01", ordinal.firstSample());
        assertEquals("03", ordinal.lastSample());
        assertEquals(3, review.facts().get("rowCount"));
        assertTrue(review.reason().contains("直属列表子栏目"));
        assertTrue(result.excludedSourceIds().isEmpty());
    }

    @Test
    void leavesPartialAmbiguousAndBusinessNumbersForReview() throws Exception {
        String complete = numberedCards("01", "02", "03");
        for (String html : List.of(
                numberedCards("01", "03", "04"),
                "<main><ul>" + numberedCard("01", "第一项") + "</ul></main>",
                complete.replace("<dt>02</dt>", "<dt hidden>02</dt>"),
                complete.replace("<dd><h3>", "<dd><p>").replace("</h3>", "</p>"),
                complete.replace("<dt>", "<dt class='price'>"),
                "<main><ul>" + ambiguousCard("01") + ambiguousCard("02") + ambiguousCard("03") + "</ul></main>")) {
            assertTrue(inspect(html).suggestions().stream()
                    .noneMatch(suggestion -> suggestion.facts().containsKey("displayOrdinal")), html);
        }
    }

    private static String ambiguousCard(String number) {
        return "<li><dl><dt>" + number + "</dt><dd><h3>第一项 " + number + "</h3></dd></dl>"
                + "<dl><dt>" + number + "</dt><dd><h3>第二项 " + number + "</h3></dd></dl></li>";
    }

    private static String numberedCards(String first, String second, String third) {
        return "<main><ul>" + numberedCard(first, "第一项") + numberedCard(second, "第二项")
                + numberedCard(third, "第三项") + "</ul></main>";
    }

    private static String numberedCard(String number, String title) {
        return "<li><dl><dt>" + number + "</dt><dd><h3>" + title + "</h3></dd></dl></li>";
    }

    private TemplateDomainPageEvidence.Evidence inspect(String html) throws Exception {
        var file = root.resolve("demo/v1/index.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, html);
        var storage = new UiPackageStorageService(root);
        var evidence = new TemplateDomainPageEvidence(
                storage, new SiteModelEvidenceService(storage), new SiteModelNavigationEvidence(storage));
        return evidence.inspect("demo", "v1", "index.html",
                storage.readStaticPage("demo", "v1", "index.html").digest(), List.of());
    }

    private static List<Object> listCounts(TemplateDomainPageEvidence.Evidence result) {
        return result.suggestions().stream().filter(suggestion -> "LIST_ONLY".equals(suggestion.kind()))
                .map(suggestion -> suggestion.facts().get("recordCount")).toList();
    }
}

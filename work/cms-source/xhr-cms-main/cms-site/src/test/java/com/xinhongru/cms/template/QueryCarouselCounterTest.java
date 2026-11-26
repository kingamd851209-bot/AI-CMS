package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinhongru.cms.template.QueryListPresentation.DisplayBinding;
import com.xinhongru.cms.template.QueryListPresentation.Field;
import com.xinhongru.cms.template.QueryListPresentation.Plan;
import com.xinhongru.cms.template.QueryListPresentation.Request;
import com.xinhongru.cms.template.TemplateContentUnitsService.Source;
import com.xinhongru.cms.template.TemplateQueryBindingService.Snapshot;
import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.file.Path;
import java.util.*;

class QueryCarouselCounterTest {
    private static final String CARD_GROUP =
            """
            <div id="cards" class="swiper-wrapper">
              <article class="swiper-slide"><p class="count"><span class="number">01</span> /
                <span class="total">02</span></p><time>2025/04</time><h2>First</h2>
                <a href="first.html">Details</a></article>
              <article class="swiper-slide"><p class="count"><span class="number">02</span> /
                <span class="total">02</span></p><time>2025/04</time><h2>Second</h2>
                <a href="second.html">Details</a></article>
            </div>
            """;
    private final QueryTemplateCompiler compiler = new QueryTemplateCompiler(null, null);

    @Test
    void findsRealExpandedSourceAndIdentifiesAllCounterText() {
        String samplePath = System.getProperty("stage130.samples");
        assumeTrue(samplePath != null, "仅显式指定原包目录时运行验收");
        var base = Path.of(samplePath).resolve("J_jinyujituan");
        var path = "news/index.aspx";
        var page = StaticPageSource.read(base, path);
        var doc = Jsoup.parse(page.html());
        var first = doc.selectFirst(".newsTrends_01_Slide_num").closest(".swiper-slide");
        var cards = List.copyOf(first.parent().children());
        var storage = mock(UiPackageStorageService.class);
        when(storage.readStaticPage("fixture", "v1", path)).thenReturn(page);
        var sources =
                new TemplateSeedService(storage)
                        .preview("fixture", "v1", path).items().stream()
                                .map(
                                        item ->
                                                source(
                                                        item.id(),
                                                        item.kind(),
                                                        item.value(),
                                                        item.selector()))
                                .toList();

        var evidence = QueryCarouselCounter.inspect(cards, cards.get(1), sources);
        assertNotNull(evidence);
        assertEquals("02", evidence.currentSample());
        assertEquals("02", evidence.totalSample());
        assertEquals(2, evidence.currentWidth());
        assertEquals(2, evidence.totalWidth());
        assertTrue(evidence.currentSelector().contains("newsTrends_01_Slide_num_one"));
        assertTrue(evidence.totalSelector().contains("newsTrends_01_Slide_num_all"));
        var covered =
                sources.stream()
                        .filter(source -> evidence.sourceIds().contains(source.id()))
                        .toList();
        assertEquals(4, covered.size(), covered.toString());
        assertTrue(covered.stream().noneMatch(source -> source.rawValue().contains("2025")));
        assertThrows(IllegalArgumentException.class, () -> evidence.expressions(cards.getFirst()));
        var firstEvidence = QueryCarouselCounter.inspect(cards, cards.getFirst(), sources);
        assertNotNull(firstEvidence);
        System.out.println("金隅首卡原序号来源 " + firstEvidence.sourceIds());

        for (String sample :
                List.of(
                        "B_blw/index.aspx",
                        "c_cgxc/about/history.aspx",
                        "c_cgxc/about/news.aspx")) {
            int slash = sample.indexOf('/');
            var otherBase = Path.of(samplePath).resolve(sample.substring(0, slash));
            var otherPage = StaticPageSource.read(otherBase, sample.substring(slash + 1));
            var otherDocument = Jsoup.parse(otherPage.html());
            var groups =
                    otherDocument.select(".swiper-wrapper").stream()
                            .filter(group -> group.childrenSize() >= 2)
                            .toList();
            assertFalse(groups.isEmpty(), sample);
            for (var group : groups)
                assertNull(
                        QueryCarouselCounter.inspect(
                                List.copyOf(group.children()),
                                group.child(0),
                                textEvidence(group.child(0))),
                        sample);
        }
    }

    @Test
    void rendersBatchIndexAndSizeEvenWhenDatabaseTotalDiffersAndSampleWasSecond() throws Exception {
        var approved = snapshot(CARD_GROUP, true);
        assertEquals(
                approved.presentation(),
                new ObjectMapper()
                        .readValue(
                                new ObjectMapper().writeValueAsBytes(approved.presentation()),
                                Plan.class));
        var compiled = compiler.compileHtml(CARD_GROUP, approved, Map.of());
        var empty = render(compiled.html(), 0);
        assertTrue(empty.select(".swiper-slide").isEmpty());
        var one = render(compiled.html(), 1);
        assertEquals(List.of("01"), one.select(".number").eachText());
        assertEquals(List.of("01"), one.select(".total").eachText());
        var three = render(compiled.html(), 3);
        assertEquals(List.of("01", "02", "03"), three.select(".number").eachText());
        assertEquals(List.of("03", "03", "03"), three.select(".total").eachText());
        assertEquals(List.of("2025/04", "2025/04", "2025/04"), three.select("time").eachText());
        assertEquals("01 / 03", three.selectFirst(".count").text());
        assertEquals("/story/3", three.select(".swiper-slide a").last().attr("href"));

        var tampered = Jsoup.parse(compiled.html());
        tampered.selectFirst("time")
                .attr("th:text", tampered.selectFirst(".number").attr("th:text"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        compiler.validateControlled(
                                new QueryTemplateCompiler.Compiled(
                                        tampered.outerHtml(), approved, List.of(), true)));

        var legacy = snapshot(CARD_GROUP, false);
        byte[] legacyJson = new ObjectMapper().writeValueAsBytes(legacy.presentation());
        assertFalse(
                new String(legacyJson, java.nio.charset.StandardCharsets.UTF_8)
                        .contains("carouselCounter"));
        assertEquals(legacy.presentation(), new ObjectMapper().readValue(legacyJson, Plan.class));
        var oldHtml = compiler.compileHtml(CARD_GROUP, legacy, Map.of());
        assertEquals(List.of("02", "02"), render(oldHtml.html(), 2).select(".number").eachText());
    }

    @Test
    void supportsInlineSeparatorAndUnpaddedSample() {
        var html =
                CARD_GROUP
                        .replace(
                                "<span class=\"number\">01</span> /\n"
                                        + "    <span class=\"total\">02</span>",
                                "1 ／ 2")
                        .replace(
                                "<span class=\"number\">02</span> /\n"
                                        + "    <span class=\"total\">02</span>",
                                "2 ／ 2");
        var compiled = compiler.compileHtml(html, snapshot(html, true), Map.of());
        assertEquals(
                List.of("1 ／ 2", "2 ／ 2"), render(compiled.html(), 2).select(".count").eachText());
    }

    @Test
    void rejectsOrdinaryNumericTextAmbiguousPairsAndIncompleteSequences() {
        var cards = cards(CARD_GROUP);
        assertNull(QueryCarouselCounter.inspect(cards, cards.get(1), List.of()));
        for (var html :
                List.of(
                        CARD_GROUP.replace("swiper-", "plain-"),
                        CARD_GROUP.replace("02</span></p>", "20</span></p>"),
                        CARD_GROUP.replace("class=\"number\">02", "class=\"number\">01"),
                        CARD_GROUP.replace("</p><time>2025/04</time>", "</p><p>2025/04</p>"),
                        CARD_GROUP
                                .replace("<time>2025/04</time>", "<time>99/100</time>")
                                .replace("class=\"number\">02", "class=\"number\">01"),
                        CARD_GROUP.replace(
                                "class=\"count\"", "class=\"count\" itemprop=\"ratingValue\""),
                        CARD_GROUP.replace("class=\"count\"", "class=\"count\" aria-label=\"评分\""),
                        CARD_GROUP.replace("class=\"number\"", "class=\"number price\""),
                        CARD_GROUP.replace(
                                "class=\"count\"", "class=\"count\" onclick=\"test()\""))) {
            if (html.contains("<p>2025/04</p>")) assertNotNull(evidence(html));
            else assertNull(evidence(html), html);
        }
        assertNull(
                evidence(
                        CARD_GROUP
                                .replace(
                                        "</p><time>2025/04</time><h2>First",
                                        "</p><p class=counter>01 / 02</p><h2>First")
                                .replace(
                                        "</p><time>2025/04</time><h2>Second",
                                        "</p><p class=counter>02 / 02</p><h2>Second")));
    }

    private static QueryCarouselCounter evidence(String html) {
        var cards = cards(html);
        return QueryCarouselCounter.inspect(cards, cards.get(1), textEvidence(cards.get(1)));
    }

    private static List<Source> textEvidence(Element card) {
        return card.getAllElements().stream()
                .filter(node -> !node.text().isBlank())
                .map(node -> source(node.cssSelector(), "TEXT", node.text(), node.cssSelector()))
                .toList();
    }

    private static List<Element> cards(String html) {
        return List.copyOf(Jsoup.parse(html).selectFirst("#cards").children());
    }

    private static Snapshot snapshot(String html, boolean confirm) {
        var cards = cards(html);
        var card = cards.get(1);
        var title = source("title", "TEXT", "Second", card.selectFirst("h2").cssSelector());
        var detail = source("detail", "LINK", "second.html", card.selectFirst("a").cssSelector());
        var request =
                new Request(
                        null,
                        "sample",
                        List.of(
                                new DisplayBinding("title", "title"),
                                new DisplayBinding("detail", "$detail")),
                        Map.of(),
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        confirm ? true : null);
        var counter =
                confirm ? QueryCarouselCounter.inspect(cards, card, textEvidence(card)) : null;
        if (confirm) assertNotNull(counter);
        var plan =
                new Plan(
                        request,
                        "#cards",
                        card.cssSelector(),
                        2,
                        List.of(title, detail),
                        List.of(new Field("title", "标题", "SingleLine")),
                        cards.stream().map(Element::cssSelector).toList(),
                        null,
                        null,
                        null,
                        counter);
        return new Snapshot(
                2,
                "fixture",
                4L,
                "fixture",
                "v1",
                "source",
                "index.html",
                "digest",
                7L,
                "7",
                "body",
                "fields",
                "route",
                "/news/1",
                List.of(),
                List.of(),
                List.of(),
                plan,
                8);
    }

    private static Source source(String id, String kind, String value, String selector) {
        return new Source(id, kind, value, selector, "", null, null, null, null, null, null);
    }

    private static org.jsoup.nodes.Document render(String html, int records) {
        var context = new Context(Locale.CHINA);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= records; i++)
            rows.add(Map.of("detailUrl", "/story/" + i, "text", Map.of("title", "Record " + i)));
        context.setVariable("cmsBoundRows", rows);
        context.setVariable("cmsBoundPage", Map.of("total", 58));
        return Jsoup.parse(new SpringTemplateEngine().process(html, context));
    }
}

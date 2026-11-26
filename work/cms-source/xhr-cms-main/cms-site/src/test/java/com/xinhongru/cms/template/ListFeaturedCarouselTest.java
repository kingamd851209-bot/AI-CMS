package com.xinhongru.cms.template;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ListFeaturedCarouselTest {
    @Test
    void rendersOnlyRealRecordsAndRejectsEditedScriptOrOverlappingGroups() {
        String sample = """
                <li class="swiper-slide"><div>
                <a href="detail.aspx"><img src="old.jpg" alt=""></a>
                <h2><a href="detail.aspx">样件</a></h2><p>原包摘要</p>
                </div></li>
                """;
        var document = Jsoup.parse("<main><div class='featured'><div class='swiper-container'>"
                + "<ul class='swiper-wrapper'>" + sample.repeat(3) + "</ul></div>"
                + "<div class='swiper-pagination'></div></div><ul class='list'></ul><nav id='pages'></nav></main>");
        var option = new QueryListPresentation.FeaturedCarousel(".featured", "photo1", "title", null);
        var pagination = new QueryListPresentation.Pagination("#pages", "a", "on", "off", Map.of(), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> QueryFeaturedCarousel.source(document, option, "main", "body", pagination));
        var group = QueryFeaturedCarousel.source(document, option, ".list", "body", pagination);
        ListFeaturedCarousel.bind(document.body(), group, option);
        assertEquals(1, document.select(".swiper-slide").size());
        assertFalse(document.outerHtml().contains("原包摘要"));
        assertFalse(document.outerHtml().contains("detail.aspx"));
        ListFeaturedCarousel.validateAndRemove(document.clone(), option);
        var edited = document.clone();
        edited.selectFirst("script").attr("src", "unknown.js");
        assertThrows(IllegalArgumentException.class, () -> ListFeaturedCarousel.validateAndRemove(edited, option));

        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        var rows = IntStream.rangeClosed(1, 6).mapToObj(i -> Map.of(
                "text", Map.of("title", "正式稿件 " + i), "media", Map.of("photo1", "/img/" + i + ".jpg"),
                "detailUrl", "/detail/" + i + ".html")).toList();
        var context = new Context();
        context.setVariable("cmsBoundRows", rows);
        context.setVariable("cmsBoundPage", Map.of("page", 1));
        var output = Jsoup.parse(engine.process(document.outerHtml(), context));
        assertEquals(List.of("正式稿件 1", "正式稿件 2", "正式稿件 3", "正式稿件 4", "正式稿件 5", "正式稿件 6"),
                output.select(".swiper-slide h2 a").eachText());
        assertEquals("/detail/6.html", output.select(".swiper-slide h2 a").last().attr("href"));
        assertEquals("/img/6.jpg", output.select(".swiper-slide img").last().attr("src"));
        context.setVariable("cmsBoundPage", Map.of("page", 2));
        assertTrue(Jsoup.parse(engine.process(document.outerHtml(), context)).select(".featured").isEmpty());
    }
}

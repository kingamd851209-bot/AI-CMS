package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.config.UploadConfig;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.properties.StaticAssetProperties;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.web.resonseVo.IdEntity;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class QueryTimelineTest {
    private static final String SOURCE =
            """
            <!DOCTYPE html>
            <html><head><script src="old-slider.js"></script></head><body><main>
              <aside><div class="sections">
                <div class="label on">2025</div><div class="label">2024</div>
              </div></aside>
              <section class="entries" data-year="2025">
                <article class="section on"><h3>2025</h3><ol>
                  <li>源样文一</li><li>源样文二</li>
                </ol></article>
                <article class="section"><h3>2024</h3><ol>
                  <li>源样文一</li><li>源样文二</li>
                </ol></article>
              </section>
            </main></body></html>
            """;

    @Test
    void projectsNewGroupsAndRowsWithoutDetailOrPagination() {
        var layout = layouts(SOURCE).getFirst();
        assertEquals(List.of("2025", "2024"), layout.labels());
        assertEquals(4, layout.sourceRows());
        var compiled = compile(SOURCE, layout);
        assertTrue(compiled.completeList());
        assertFalse(compiled.html().contains("old-slider.js"));
        assertFalse(compiled.html().contains("源样文"));
        assertFalse(compiled.html().contains("pagination"));
        assertFalse(compiled.html().contains("detailUrl"));
        var rendered =
                Jsoup.parse(
                        render(
                                compiled.html(),
                                List.of(
                                        Map.of(
                                                "label",
                                                "2031",
                                                "rows",
                                                List.of("<b>事件一</b>", "事件二")),
                                        Map.of("label", "2029", "rows", List.of("事件三")))));
        assertEquals(List.of("2031", "2029"), rendered.select("[role=tab]").eachText());
        var active = rendered.selectFirst("[role=tab][aria-selected=true]");
        assertNotNull(active);
        assertEquals("cms-timeline-panel-0", active.attr("aria-controls"));
        assertEquals("2031", rendered.selectFirst("[data-cms-timeline=groups]").attr("data-year"));
        assertEquals(1, rendered.select("[role=tabpanel][hidden]").size());
        assertEquals(
                List.of("<b>事件一</b>", "事件二", "事件三"),
                rendered.select("[data-cms-timeline=row]").eachText());
        assertTrue(rendered.select("[data-cms-timeline=row] b").isEmpty());
    }

    @Test
    void leavesAnEmptyStateWhenThereAreNoPublishedGroups() {
        var rendered =
                Jsoup.parse(render(compile(SOURCE, layouts(SOURCE).getFirst()).html(), List.of()));
        assertTrue(rendered.select("[role=tab],[role=tabpanel],[data-cms-timeline=row]").isEmpty());
        assertEquals("暂无可展示的内容", rendered.selectFirst("[data-cms-timeline=empty]").text());
    }

    @Test
    void findsAChangedLayoutAndRejectsStaleOrUnauthorizedStructure() {
        var original = layouts(SOURCE).getFirst();
        String changed =
                SOURCE.replace("sections", "year-selector").replace("entries", "year-panels");
        var fresh = layouts(changed).getFirst();
        assertFalse(original.navigationSelector().equals(fresh.navigationSelector()));
        assertThrows(IllegalArgumentException.class, () -> compile(changed, original));
        assertTrue(compile(changed, fresh).completeList());
        assertTrue(layouts(SOURCE.replace("<h3>2024</h3>", "<h3>2025</h3>")).isEmpty());
        String html = compile(SOURCE, original).html();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TimelineTemplateCompiler.validate(
                                html.replace("<main>", "<main th:text='${otherModel}'>")));
    }

    @Test
    void rendersIllustratedHistoryWithSafeOptionalImages(@TempDir Path storage) throws Exception {
        String source =
                """
                <!doctype html><html><head><title>发展历程</title></head><body><main>
                  <aside><ul><li>2015</li><li>2010</li></ul></aside>
                  <section>
                    <article><h2>2015</h2><div class="events">
                      <div class="event"><p>开设服务站</p><figure><img src="original.jpg"></figure></div>
                      <div class="event"><p>增设产品线</p><figure></figure></div>
                    </div></article>
                    <article><h2>2010</h2><div class="events">
                      <div class="event"><p>初创</p><figure><img src="another-original.jpg"></figure></div>
                    </div></article>
                  </section>
                </main></body></html>
                """;
        var document = Jsoup.parse(source);
        var approvedRows =
                document.select("article:first-child .event").stream()
                        .map(
                                element ->
                                        new SiteModelEvidenceService.SourceRecord(
                                                element.cssSelector(),
                                                0,
                                                element.cssSelector(),
                                                List.of(),
                                                List.of()))
                        .toList();
        var region =
                new SiteModelEvidenceService.Region(
                        "reviewed", "LIST", "body", approvedRows, List.of());
        var layout = QueryTimeline.layouts(document, region).getFirst();
        assertEquals("h2", layout.groupTitleSelector());
        assertEquals(3, layout.sourceRows());
        var selection =
                new QueryTimeline.Selection(
                        layout.navigationSelector(),
                        layout.navigationItemSelector(),
                        layout.groupSelector(),
                        layout.groupItemSelector(),
                        layout.groupTitleSelector(),
                        layout.rowSelector(),
                        "singleLine2",
                        "title",
                        false,
                        layout.rowTextSelector(),
                        layout.rowImageSelector(),
                        "photo1");

        var menu = new OneshotEntity();
        menu.setId(7);
        menu.setSiteProjectId(4L);
        menu.setPageType(1);
        menu.setHasFrontChannel(true);
        menu.setModelField(
                """
                [{"fieldName":"title","fieldType":"SingleLine","isOpen":1},
                 {"fieldName":"singleLine2","fieldType":"SingleLine","isOpen":1},
                 {"fieldName":"photo1","fieldType":"Image","isOpen":1,"title":"记录图片"}]
                """);
        var compiled = compile(source, layout, selection, ContentFieldConfigDigest.of(menu));
        assertFalse(compiled.html().contains("original.jpg"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TimelineTemplateCompiler.validate(
                                compiled.html().replace("${event.image}", "${other.image}"),
                                selection));

        var context = new IdEntity().setClassId(7).setSiteProjectId(4L);
        context.bindServerSite(4L);
        var contents = mock(ContentMapper.class);
        when(contents.selectPublicTimelineRows(4L, 7))
                .thenReturn(
                        List.of(
                                record(1, "<b>开设服务站</b>", "assets:/upload/current.jpg"),
                                record(2, "增设产品线", ""),
                                record(3, "整理档案", "https://external.invalid/private.jpg"),
                                record(
                                        4,
                                        "对外交流",
                                        "assets:/upload/../../../other/assets/upload/private.jpg")));
        var projection =
                new PublicListModelProjection(null, contents, resources(storage), null, null);
        var rendered =
                Jsoup.parse(
                        render(
                                compiled.html(),
                                projection.timeline(compiled.snapshot(), context, menu)));
        assertEquals(List.of("2015"), rendered.select("[data-cms-timeline=title]").eachText());
        assertEquals(
                List.of("<b>开设服务站</b>", "增设产品线", "整理档案", "对外交流"),
                rendered.select("[data-cms-timeline=row-text]").eachText());
        assertEquals(
                List.of("assets:/upload/current.jpg"),
                rendered.select("[data-cms-timeline=row] img").eachAttr("src"));
        assertTrue(rendered.select("[data-cms-timeline=row] b").isEmpty());
    }

    @Test
    void rendersDirectRowsFromOnePanelAndKeepsTheNestedTabLabel() {
        String source = """
                <!doctype html><html><head><script src="legacy.js"></script></head><body><main>
                  <aside class="wow fadeInUp">
                    <div class="swiper-container"><ul class="swiper-wrapper">
                      <li class="swiper-slide" data-src="old-one.html"><p class="label">2020</p></li>
                      <li class="swiper-slide" data-src="old-two.html"><p class="label">2021</p></li>
                    </ul></div>
                    <div class="controls"><div class="swiper-button-prev disabled"></div>
                      <div class="swiper-button-next swiper-button-disabled"></div></div>
                  </aside>
                  <div class="cards wow fadeInUp"><ul>
                    <li class="wow fadeInUp"><dl><dt><img src="source-one.jpg"></dt>
                      <dd><span>1月</span><h3>旧标题一</h3></dd></dl></li>
                    <li><dl><dt><img src="source-two.jpg"></dt>
                      <dd><span>2月</span><h3>旧标题二</h3></dd></dl></li>
                  </ul></div>
                </main></body></html>
                """;
        var selection = new QueryTimeline.Selection(".swiper-wrapper", "li.swiper-slide", ".cards",
                "ul", null, "li", "singleLine2", "title", false,
                "dd > h3", "dt > img", "photo1", "dd > span", "singleLine1");
        var compiled = compile(source, layouts(SOURCE).getFirst(), selection, "fields");
        assertFalse(compiled.html().contains("old-one.html"));
        assertFalse(compiled.html().contains("source-one.jpg"));
        assertFalse(compiled.html().contains("旧标题"));
        assertFalse(compiled.html().contains("legacy.js"));
        var rendered = Jsoup.parse(render(compiled.html(), List.of(
                Map.of("label", "2031", "rows", List.of(Map.of(
                        "text", "<b>记录甲</b>", "secondary", "<b>12月</b>", "image", "/current.jpg"))),
                Map.of("label", "2029", "rows", List.of(Map.of(
                        "text", "记录乙", "secondary", "8月", "image", ""))))));
        assertEquals(List.of("2031", "2029"), rendered.select("li[role=tab] > p.label").eachText());
        assertEquals(1, rendered.select("ul[role=tabpanel][hidden]").size());
        assertEquals(List.of("<b>记录甲</b>", "记录乙"), rendered.select("[data-cms-timeline=row-text]").eachText());
        assertEquals(List.of("<b>12月</b>", "8月"), rendered.select("[data-cms-timeline=row-secondary]").eachText());
        assertEquals(List.of("/current.jpg"), rendered.select("ul[role=tabpanel] > li img").eachAttr("src"));
        assertTrue(rendered.select(".wow,.disabled,.swiper-button-disabled,[data-src],li b").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TimelineTemplateCompiler.validate(
                compiled.html().replace("${event.secondary}", "${other.secondary}"), selection));
        assertThrows(IllegalArgumentException.class, () -> TimelineTemplateCompiler.validate(
                compiled.html().replace("role=\"tab\"", "role=\"tab\" data-src=\"old.html\""), selection));

        String plainTabs = source.replace("<p class=\"label\">2020</p>", "2020")
                .replace("<p class=\"label\">2021</p>", "2021");
        var plainRendered = Jsoup.parse(render(compile(plainTabs, layouts(SOURCE).getFirst(), selection, "fields").html(),
                List.of(Map.of("label", "2031", "rows", List.of()))));
        assertEquals(List.of("2031"), plainRendered.select("li[role=tab]").eachText());
        assertTrue(plainRendered.select("li[role=tab] > *").isEmpty());
    }

    private static ContentEntity record(int id, String title, String photo) {
        var row = new ContentEntity();
        row.setId(id);
        row.setClassid(7);
        row.setSiteProjectId(4L);
        row.setStatus(1);
        row.setTitle(title);
        row.setSingleLine2("2015");
        row.setPhoto1(photo);
        return row;
    }

    private static ContentExtensionResources resources(Path storage) throws Exception {
        var roots = new PublicStorageRoots(new StaticAssetProperties(), storage.toString());
        Files.createDirectories(roots.siteRoot("site"));
        Files.createFile(roots.siteRoot("site").resolve("current.jpg"));
        Files.createDirectories(roots.siteRoot("other"));
        Files.createFile(roots.siteRoot("other").resolve("private.jpg"));
        var formats = new UploadConfig();
        formats.setFormat(Map.of("image", "jpg"));
        var resolver = mock(ResourceReferenceResolver.class);
        when(resolver.context("site"))
                .thenReturn(
                        new ResourceReferenceResolver.Context(
                                "site",
                                "https://site.invalid/",
                                "https://site.invalid/assets/upload/",
                                "/assets/global/"));
        var projects = mock(SiteProjectMapper.class);
        var site = new SiteProjectEntity();
        site.setProjectCode("site");
        when(projects.selectById(4L)).thenReturn(site);
        return new ContentExtensionResources(roots, resolver, formats, null, projects);
    }

    private static List<QueryTimeline.Layout> layouts(String html) {
        var document = Jsoup.parse(html);
        var reviewedRows =
                document.select("article").first().select("li").stream()
                        .map(
                                element ->
                                        new SiteModelEvidenceService.SourceRecord(
                                                element.cssSelector(),
                                                0,
                                                element.cssSelector(),
                                                List.of(),
                                                List.of()))
                        .toList();
        return QueryTimeline.layouts(
                document,
                new SiteModelEvidenceService.Region(
                        "reviewed", "LIST", "article ol", reviewedRows, List.of()));
    }

    private static QueryTemplateCompiler.Compiled compile(
            String html, QueryTimeline.Layout layout) {
        var selection =
                new QueryTimeline.Selection(
                        layout.navigationSelector(),
                        layout.navigationItemSelector(),
                        layout.groupSelector(),
                        layout.groupItemSelector(),
                        layout.groupTitleSelector(),
                        layout.rowSelector(),
                        "singleLine2",
                        "singleLine1",
                        false);
        return compile(html, layout, selection, "fields");
    }

    private static QueryTemplateCompiler.Compiled compile(
            String html,
            QueryTimeline.Layout layout,
            QueryTimeline.Selection selection,
            String fieldDigest) {
        var request =
                new QueryListPresentation.Request(
                        null, null, List.of(), Map.of(), true, null, List.of(), null, null,
                        selection);
        var plan =
                new QueryListPresentation.Plan(
                        request,
                        selection.groupSelector(),
                        selection.rowSelector(),
                        layout.sourceRows(),
                        List.of(),
                        List.of(),
                        List.of());
        var snapshot =
                new TemplateQueryBindingService.Snapshot(
                        2,
                        "query",
                        4L,
                        "site",
                        "v1",
                        "plan",
                        "about/timeline.html",
                        "digest",
                        7L,
                        "history",
                        "body",
                        fieldDigest,
                        "route",
                        "/about/timeline.html",
                        List.of(),
                        List.of(),
                        List.of(),
                        plan,
                        10);
        return new QueryTemplateCompiler(null, null).compileHtml(html, snapshot, Map.of());
    }

    private static String render(String html, List<Map<String, Object>> groups) {
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        var context = new Context();
        context.setVariable("cmsBoundTimeline", groups);
        return engine.process(html, context);
    }
}

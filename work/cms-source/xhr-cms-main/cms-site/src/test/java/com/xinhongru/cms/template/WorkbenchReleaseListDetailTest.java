package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.FormContentMapper;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.entity.SiteProjectEntity;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class WorkbenchReleaseListDetailTest {
    @TempDir Path temp;

    @Test
    void completeReviewedCardsStayListOnly() throws Exception {
        assertEquals(List.of(), review("", 35L, false, false, false));
    }

    @Test
    void linkedInteractiveIncompleteAndMismatchedCardsRemainPending() throws Exception {
        for (String marker : List.of("link", "onclick", "data-url")) {
            assertEquals(List.of("请先核对栏目是否需要独立详情"), review(marker, 35L, false, false, false));
        }
        assertEquals(List.of("请先核对栏目是否需要独立详情"), review("", 99L, false, false, false));
        assertEquals(List.of("请先核对栏目是否需要独立详情"), review("", 35L, true, false, false));
    }

    @Test
    void reviewedOrConfiguredDetailsKeepThePublishedRecordCheck() throws Exception {
        String missing = "栏目没有可用于验收详情页的有效已发布记录";
        assertEquals(List.of(missing), review("", 35L, false, true, false));
        assertEquals(List.of(missing), review("", 35L, false, false, true));
    }

    @Test
    void sharedFragmentNeedsVerifiedDestinationAndUniqueBodyAnchor() throws Exception {
        var target = new PublicNavigationLinkCompiler.Target("entry", 35, "产品", "/products.html#spec",
                PublicNavigationLinkCompiler.TargetKind.FRAGMENT);
        var page = new WorkbenchReleaseReviewService.Page(35, "产品", "products.aspx", "LIST",
                null, "/products.html", "products.html", "READY", "verified");
        var scan = new WorkbenchStaticLinkCheckService.Inspection(
                new WorkbenchStaticLinkCheckService.Result("SCANNED", 1, 0, false, List.of()),
                temp, null, Map.of());
        var file = temp.resolve("products.html");
        Files.writeString(file, "<main><h2 id='spec'>参数</h2></main>");
        assertEquals(page, WorkbenchReleaseReviewService.verifiedEntryDestination(target, List.of(page), scan));
        assertNull(WorkbenchReleaseReviewService.verifiedEntryDestination(target, List.of(), scan));
        Files.writeString(file, "<header id='spec'>菜单</header>");
        assertNull(WorkbenchReleaseReviewService.verifiedEntryDestination(target, List.of(page), scan));
        Files.writeString(file, "<main><h2 id='spec'>参数</h2><p id='spec'>重复</p></main>");
        assertNull(WorkbenchReleaseReviewService.verifiedEntryDestination(target, List.of(page), scan));
    }

    @Test
    void nativeRowsNeedCurrentMembershipOrderAndExactDetailRoute() {
        var menu = new OneshotEntity();
        var first = new ContentEntity();
        first.setId(41);
        first.setTitle("第一篇");
        var second = new ContentEntity();
        second.setId(42);
        second.setTitle("第二篇");
        var routes = mock(ContentLinkService.class);
        when(routes.getDetailUrl(first, menu)).thenReturn("/detail/41.html");
        when(routes.getDetailUrl(second, menu)).thenReturn("/detail/42.html");
        var service = new WorkbenchReleaseReviewService(null, null, null, null, null,
                null, null, null, routes, null, null, null, null);
        var marker = Jsoup.parse("""
                <ul><li data-cms-projection-record="41"><a href="/detail/41.html">第一篇</a></li>
                <li data-cms-projection-record="42"><a href="/detail/42.html">第二篇</a></li></ul>
                """).selectFirst("ul");
        var expected = List.of(first, second);
        String attribute = "data-cms-projection-record";
        assertTrue(service.matchesNativeRows(menu, 2, expected, expected, marker, attribute));
        assertFalse(service.matchesNativeRows(menu, 2, expected, List.of(second, first), marker, attribute));
        assertFalse(service.matchesNativeRows(menu, 2, List.of(first), expected, marker, attribute));
        marker.select("a").last().attr("href", "/other/42.html");
        assertFalse(service.matchesNativeRows(menu, 2, expected, expected, marker, attribute));
    }

    @Test
    void nativeVideoVerifiesPublishedSourceAndPosterOnTheSamePlayer() {
        var site = new SiteProjectEntity();
        site.setProjectCode("test");
        var model = Map.of("title", new ContentFieldDefinition().setFieldName("title").setFieldType("SingleLine"),
                "photo1", new ContentFieldDefinition().setFieldName("photo1").setFieldType("Image"),
                "media1", new ContentFieldDefinition().setFieldName("media1").setFieldType("Video"));
        var record = new ContentEntity();
        record.setTitle("展览视频");
        record.setPhoto1("ui:/images/exhibit.jpg");
        record.setMedia1("ui:/flash/exhibit.mp4");
        var service = new WorkbenchReleaseReviewService(null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        var resources = mock(ResourceReferenceResolver.class);
        when(resources.context("test")).thenReturn(new ResourceReferenceResolver.Context(
                "test", "https://cms.example/", "https://cms.example/assets/upload/", "https://cms.example/assets/global/"));
        service.setNativeProjectionEvidence(null, null, resources);
        String trusted = """
                <section data-cms-native-row="42"><h3 data-cms-native-field="title">展览视频</h3>
                  <video data-cms-native-field="photo1" poster="https://cms.example/images/exhibit.jpg">
                    <source data-cms-native-field="media1" src="https://cms.example/flash/exhibit.mp4" type="video/mp4">
                  </video>
                </section>
                """;
        java.util.function.Predicate<String> verify = html -> service.matchesDirectFields(site, model, record,
                Jsoup.parse(html).selectFirst("section"), true, java.util.Set.of());
        assertTrue(verify.test(trusted));
        assertFalse(verify.test(trusted.replace("展览视频", "其他视频")));
        assertFalse(verify.test(trusted.replace("/flash/exhibit.mp4", "/flash/changed.mp4")));
        assertFalse(verify.test(trusted.replace("/images/exhibit.jpg", "/images/changed.jpg")));
        assertFalse(verify.test(trusted.replace("<video", "<video src='/flash/other.mp4'")));
        assertFalse(verify.test(trusted.replace("</video>", "<source src='/flash/other.mp4'></video>")));
        assertFalse(verify.test(trusted.replace("<video", "<audio").replace("</video>", "</audio>")));
        assertFalse(verify.test(trusted.replace("data-cms-native-field=\"media1\"", "")));
        assertFalse(verify.test(trusted.replace("</video>", "</video><img data-cms-native-field='photo1' src='/other.jpg'>")));

        record.setMedia1("/flash/exhibit.mp4");
        record.setPhoto1("/images/exhibit.jpg");
        String local = trusted.replace("https://cms.example", "");
        assertTrue(verify.test(local));
        record.setMedia1("https://another.example/flash/exhibit.mp4");
        assertFalse(verify.test(local.replace("/flash/exhibit.mp4", record.getMedia1())));
        record.setMedia1("/flash/exhibit.mp4");
        for (String unsafe : List.of("//another.example/images/exhibit.jpg", "data:image/png;base64,xyz",
                "/images/../exhibit.jpg", "/images/%2e%2e/exhibit.jpg", "https://cms.example:444/images/exhibit.jpg")) {
            record.setPhoto1(unsafe);
            assertFalse(verify.test(local.replace("/images/exhibit.jpg", unsafe)), unsafe);
        }
    }

    @Test
    void nativeImageStillRequiresMatchingImgSrcAndRecordTitle() {
        var site = new SiteProjectEntity();
        site.setProjectCode("test");
        var model = Map.of("title", new ContentFieldDefinition().setFieldName("title").setFieldType("SingleLine"),
                "photo1", new ContentFieldDefinition().setFieldName("photo1").setFieldType("Image"));
        var record = new ContentEntity();
        record.setTitle("展览图片");
        record.setPhoto1("ui:/images/exhibit.jpg");
        var service = new WorkbenchReleaseReviewService(null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        var resources = mock(ResourceReferenceResolver.class);
        when(resources.resolve(record.getPhoto1(), "test")).thenReturn("/images/exhibit.jpg");
        service.setNativeProjectionEvidence(null, null, resources);
        var marker = Jsoup.parse("<article data-cms-native-row='42'><h3 data-cms-native-field='title'>展览图片</h3>"
                + "<img data-cms-native-field='photo1' src='/images/exhibit.jpg'></article>").selectFirst("article");
        assertTrue(service.matchesDirectFields(site, model, record, marker, true, java.util.Set.of()));
        marker.selectFirst("img").attr("src", "/images/other.jpg");
        assertFalse(service.matchesDirectFields(site, model, record, marker, true, java.util.Set.of()));
        marker.selectFirst("img").remove();
        assertFalse(service.matchesDirectFields(site, model, record, marker, true, java.util.Set.of()));
    }

    @Test
    @EnabledIfSystemProperty(named = "cms.test.honorPackageRoot", matches = ".+")
    void actualConfirmedHonorRegionMatchesOriginalArchive() throws Exception {
        var root = Path.of(System.getProperty("cms.test.honorPackageRoot"));
        String code = "STAGE127JINYU", version = "v1795334331437-52cfc1337650";
        var planPath =
                root.resolve(".workbench-site-model")
                        .resolve(code)
                        .resolve("58")
                        .resolve(version)
                        .resolve("1f21bab7-a140-3e96-b93c-a7c4a3f8da5a-2.json");
        var plan =
                new tools.jackson.databind.ObjectMapper()
                        .readValue(Files.readString(planPath), SiteModelPlanService.Plan.class);
        var region =
                plan.proposal().regions().stream()
                        .filter(row -> "group_honors".equals(row.modelKey()))
                        .findFirst()
                        .orElseThrow();
        var evidence = new SiteModelEvidenceService(new UiPackageStorageService(root));
        assertTrue(
                evidence.confirmsDisplayOnlyList(code, version, region),
                () -> evidence.page(code, version, region.sourcePath()).warnings().toString());
    }

    private List<String> review(
            String marker, Long owner, boolean partial, boolean detailRegion, boolean detailUrl)
            throws Exception {
        String code = "fixture", version = "v-test", source = "about/honors.aspx";
        String markup =
                "<html><body><main><ul>"
                        + "<li><img src='a.jpg'><h3>荣誉一</h3><p>一月</p></li>"
                        + "<li><img src='b.jpg'><h3>荣誉二</h3><p>二月</p></li>"
                        + "<li><img src='c.jpg'><h3>荣誉三</h3><p>三月</p></li>"
                        + "</ul></main></body></html>";
        var packageRoot = Files.createTempDirectory(temp, "archives");
        var sourceRoot =
                Files.createDirectories(
                        packageRoot.resolve(code).resolve(version).resolve("about"));
        var sourceFile = sourceRoot.resolve("honors.aspx");
        Files.writeString(sourceFile, markup);
        Files.writeString(sourceRoot.resolve("detail.aspx"), "<h1>荣誉二</h1>");
        var evidence = new SiteModelEvidenceService(new UiPackageStorageService(packageRoot));
        var sourceRegion =
                evidence.page(code, version, source).regions().stream()
                        .filter(region -> region.kind().equals("REPEATED"))
                        .findFirst()
                        .orElseThrow();
        var records =
                sourceRegion.records().stream()
                        .limit(partial ? 2 : 3)
                        .map(
                                row ->
                                        new SiteModelPlanService.RecordUse(
                                                row.id(),
                                                Map.of(
                                                        "title",
                                                        row.values().stream()
                                                                .filter(
                                                                        value ->
                                                                                value.rawValue()
                                                                                        .startsWith(
                                                                                                "荣誉"))
                                                                .findFirst()
                                                                .orElseThrow()
                                                                .id())))
                        .toList();
        String currentMarkup =
                switch (marker) {
                    case "link" ->
                            markup.replace(
                                    "<h3>荣誉二</h3>", "<h3><a href='detail.aspx'>荣誉二</a></h3>");
                    case "onclick" -> markup.replace("<h3>荣誉二", "<h3 onclick='openAward()'>荣誉二");
                    case "data-url" -> markup.replace("<h3>荣誉二", "<h3 data-url='detail.aspx'>荣誉二");
                    default -> markup;
                };
        Files.writeString(sourceFile, currentMarkup);
        var regions = new ArrayList<SiteModelPlanService.RegionUse>();
        regions.add(
                new SiteModelPlanService.RegionUse(
                        source, sourceRegion.id(), "honors", "LIST", records));
        if (detailRegion)
            regions.add(
                    new SiteModelPlanService.RegionUse(
                            "about/detail.aspx", "detail-region", "honors", "DETAIL", records));
        var proposal =
                new SiteModelPlanService.Proposal(
                        "",
                        List.of(
                                new SiteModelPlanService.Model(
                                        "honors", "荣誉", "LIST", owner, List.of())),
                        regions,
                        List.of(),
                        List.of());
        var plan =
                new SiteModelPlanService.Plan(
                        1,
                        "plan",
                        1L,
                        code,
                        version,
                        "",
                        2,
                        "CONFIRMED",
                        proposal,
                        List.of(),
                        0,
                        0,
                        "",
                        null);
        var node =
                new SiteModelNavigationService.Node(
                        "honors",
                        null,
                        "荣誉",
                        1,
                        source,
                        "index.aspx",
                        null,
                        source,
                        SiteModelNavigationService.Kind.LIST,
                        "honors",
                        35,
                        35,
                        "/about/honors.aspx",
                        SiteModelNavigationService.ContentStatus.PENDING_MATCH,
                        "荣誉",
                        false,
                        true,
                        List.of(),
                        false);
        var site = new SiteProjectEntity();
        site.setId(1L);
        site.setProjectCode(code);
        site.setUiPackageVersion(version);
        site.setRootMenuId(5);
        var workspace = mock(SiteWorkbenchContextService.class);
        when(workspace.staticReleaseReview(eq(1L), eq(code), anyString()))
                .thenReturn(new SiteWorkbenchContextService.Document(false, null, ""));
        var links = mock(WorkbenchStaticLinkCheckService.class);
        when(links.inspect(eq(site), anyList()))
                .thenReturn(
                        new WorkbenchStaticLinkCheckService.Inspection(
                                new WorkbenchStaticLinkCheckService.Result(
                                        "SCANNED", 0, 0, false, List.of()),
                                temp,
                                null,
                                Map.of()));
        var plans = mock(SiteModelPlanService.class);
        when(plans.get(1L, code, version, "plan")).thenReturn(plan);
        var navigation = mock(SiteModelNavigationService.class);
        when(navigation.get(any(), eq("plan"), any()))
                .thenReturn(
                        new SiteModelNavigationService.Navigation(
                                4, 2, SiteModelNavigationService.Status.APPLIED, List.of(node)));
        var menus = mock(OneShotMapper.class);
        var menu = new OneshotEntity();
        menu.setId(35);
        menu.setSiteProjectId(1L);
        menu.setHasFrontChannel(false);
        if (detailUrl) menu.setDetailPageUrl("/detail/{id}.html");
        when(menus.selectListBySiteProjectId(1L)).thenReturn(List.of(menu));
        var routes = mock(ContentLinkService.class);
        when(routes.getListUrl(menu)).thenReturn("/about/honors.html");
        var service =
                new WorkbenchReleaseReviewService(
                        links,
                        mock(SiteWorkbenchHandoffService.class),
                        workspace,
                        mock(TemplateStorageService.class),
                        plans,
                        navigation,
                        menus,
                        mock(ContentMapper.class),
                        routes,
                        mock(FormOneshotMapper.class),
                        mock(FormContentMapper.class),
                        mock(PublicNavigationLinkCompiler.class),
                        evidence);
        var review =
                service.read(
                        site,
                        "plan",
                        new WorkbenchReleaseReviewService.Scope(version, 2, 4, "CMS"),
                        () -> {});
        assertTrue(
                review.pages().stream()
                        .anyMatch(
                                row ->
                                        "LIST".equals(row.slot())
                                                && Integer.valueOf(35).equals(row.menuId())));
        return review.pages().stream()
                .filter(row -> "DETAIL".equals(row.slot()))
                .map(WorkbenchReleaseReviewService.Page::reason)
                .toList();
    }
}

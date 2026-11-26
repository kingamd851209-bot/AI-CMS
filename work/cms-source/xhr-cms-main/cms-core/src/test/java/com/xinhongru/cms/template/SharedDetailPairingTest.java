package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SharedDetailPairingTest {
    @TempDir Path temp;

    @Test
    void cardDiscoverySeparatesSiteChromeAndPaginationFromBodyCards() {
        var doc = org.jsoup.Jsoup.parse("""
                <header><div class='menu'>global</div></header>
                <main><article><header>inside card</header></article>
                <div class='pagination'><a href='1'>1</a><a href='2'>2</a></div>
                <div class='page'><h2>Projects</h2><a href='a'>1</a><a href='b'>2</a></div>
                </main><footer><p>global</p></footer>""");
        org.junit.jupiter.api.Assertions.assertTrue(TemplateListPairingService.skipCardSubtree(doc.selectFirst(".menu")));
        org.junit.jupiter.api.Assertions.assertTrue(TemplateListPairingService.skipCardSubtree(doc.selectFirst("footer")));
        org.junit.jupiter.api.Assertions.assertTrue(TemplateListPairingService.skipCardSubtree(doc.selectFirst(".pagination")));
        org.junit.jupiter.api.Assertions.assertFalse(TemplateListPairingService.skipCardSubtree(doc.selectFirst("article header")));
        org.junit.jupiter.api.Assertions.assertFalse(TemplateListPairingService.skipCardSubtree(doc.selectFirst(".page")));
    }

    @Test
    void reviewsDifferentCardTitlesAndRejectsPlaceholderAndDuplicateTitles() throws Exception {
        var root = temp.resolve("packages");
        var folder = Files.createDirectories(root.resolve("demo/v1"));
        Files.writeString(folder.resolve("list.html"), """
                <main><ul><li><a href="detail.html"><h2>标题甲</h2></a></li>
                <li><a href="detail.html"><h2>标题乙</h2></a></li>
                <li><a href="detail.html"><h2>重复标题</h2></a></li>
                <li><a href="detail.html"><h2>重复标题</h2></a></li>
                <li><a href="#"><h2>占位标题</h2></a></li></ul></main>
                """);
        Files.writeString(folder.resolve("detail.html"), """
                <main><article><h1>原详情标题</h1><div><p>住房服务的原始详情正文是一份可以在不同列表记录中重复使用的完整素材。</p>
                <p>每张列表卡片保留各自标题，原详情页面的元数据由独立流程审核。</p></div></article></main>
                """);
        var fixture = fixture(root);
        var request = request(fixture, "demo", "v1", "list.html", null);
        var review = fixture.pairing().inspectForReview(1L, "demo", "v1", request, null, null);
        assertEquals(List.of("SHARED_DETAIL", "SHARED_DETAIL", "PENDING", "PENDING", "PENDING"),
                review.items().stream().map(TemplateListPairingService.Item::status).toList());
        for (var row : review.items().subList(2, 5)) {
            assertThrows(IllegalArgumentException.class, () -> approve(fixture, "demo", "v1", request, review,
                    List.of(row)));
        }
        var first = review.items().getFirst();
        assertEquals("SHARED_DETAIL", fixture.pairing().inspectForImport(1L, "demo", "v1", request, first.id(), null)
                .items().getFirst().status());
        approve(fixture, "demo", "v1", request, review, review.items().subList(0, 2));
        assertEquals("MATCHED", fixture.pairing().inspectForImport(1L, "demo", "v1", request, first.id(), null)
                .items().getFirst().status());
    }

    @Test
    void mapsTwoCardsToIndependentApplicationsWithOneOriginalBody() throws Exception {
        var root = temp.resolve("packages");
        var folder = Files.createDirectories(root.resolve("demo/v1"));
        Files.writeString(folder.resolve("list.html"), """
                <main><ul><li><a href="detail.html"><h2>标题甲</h2></a><img src="a.png"><span>分类甲</span></li>
                <li><a href="detail.html"><h2>标题乙</h2></a><img src="b.png"><span>分类乙</span></li></ul></main>
                """);
        Files.writeString(folder.resolve("detail.html"), """
                <main><article><h1>原详情标题</h1><div class="editor"><p>住房服务的原始详情正文是一份可以在不同列表记录中重复使用的完整素材。</p>
                <p>每张列表卡片保留各自标题，原详情页面的元数据由独立流程审核。</p></div></article></main>
                """);
        assertEquals(2, verifyIndependentPlans(root, "demo", "v1", "list.html", null));
    }

    int verifyIndependentPlans(Path root, String code, String version, String path, String selector) throws Exception {
        var fixture = fixture(root);
        var request = request(fixture, code, version, path, selector);
        var result = fixture.pairing().inspectForReview(1L, code, version, request, null, null);
        var eligible = result.items().stream().filter(row -> "SHARED_DETAIL".equals(row.status())).toList();
        assertTrue(eligible.size() >= 2, result.items().toString());
        var rows = eligible.subList(0, 2);
        assertEquals(rows.getFirst().detailBodyHash(), rows.getLast().detailBodyHash());
        var detailPath = rows.getFirst().detail().sourcePath();
        var units = fixture.units();
        var listWorkspace = units.load(code, version, path);
        var detailWorkspace = units.load(code, version, detailPath);
        var titles = rows.stream().map(row -> row.comparisons().getFirst().list().value()).toList();
        var titleUnits = titles.stream().map(title -> listWorkspace.sources().stream()
                        .filter(source -> "TEXT".equals(source.kind())
                                && title.equals(ListPairingComparison.clean(source.rawValue())))
                        .max(java.util.Comparator.comparingInt(source -> source.selector().length())).orElseThrow())
                .map(source -> unit(source, "SingleLine")).toList();
        var review = approve(fixture, code, version, request, result, rows);
        assertEquals(1, review.revision());
        var body = detailWorkspace.sources().stream().filter(source -> "HTML_REGION".equals(source.kind()))
                .max(java.util.Comparator.comparingInt(source -> source.rawValue().length())).orElseThrow();
        var listUnits = new ArrayList<>(titleUnits);
        var fields = new LinkedHashMap<String, String>();
        titleUnits.forEach(unit -> fields.put(unit.id(), "title"));
        for (var row : rows) {
            var images = row.list().evidence().stream().filter(evidence -> "IMAGE".equals(evidence.kind())).toList();
            if (images.isEmpty()) continue;
            var image = listWorkspace.sources().stream().filter(source -> source.id().equals(images.getFirst().sourceId()))
                    .findFirst().orElseThrow();
            listUnits.add(unit(image, "Image"));
            fields.put(image.id(), "photo1");
            row.list().evidence().stream().filter(evidence -> "TEXT".equals(evidence.kind())
                            && ListPairingComparison.clean(evidence.preview()).startsWith("分类"))
                    .findFirst().ifPresent(category -> {
                        var source = listWorkspace.sources().stream()
                                .filter(candidate -> candidate.id().equals(category.sourceId())).findFirst().orElseThrow();
                        listUnits.add(unit(source, "SingleLine"));
                        fields.put(source.id(), "singleLine1");
                    });
        }
        var list = units.save(code, version, new TemplateContentUnitsService.SaveRequest(path,
                listWorkspace.digest(), listWorkspace.revision(), listUnits));
        var detail = units.save(code, version, new TemplateContentUnitsService.SaveRequest(detailPath,
                detailWorkspace.digest(), detailWorkspace.revision(), List.of(unit(body, "Editor"))));
        assertEquals(2, titleUnits.size());
        var first = preview(fixture, code, version, request, rows.getFirst(), list, detail, fields);
        var second = preview(fixture, code, version, request, rows.getLast(), list, detail, fields);
        assertNotEquals(first.id(), second.id());
        assertNotEquals(TemplateFieldPlanService.applicationId(first), TemplateFieldPlanService.applicationId(second));
        assertEquals(first.id(), preview(fixture, code, version, request, rows.getFirst(), list, detail, fields).id());
        assertNotEquals(value(first, "title"), value(second, "title"));
        assertNotEquals(value(first, "photo1"), value(second, "photo1"));
        if (fields.containsValue("singleLine1")) assertNotEquals(value(first, "singleLine1"), value(second, "singleLine1"));
        assertEquals(value(first, "content1"), value(second, "content1"));
        return eligible.size();
    }

    private static String value(TemplateFieldPlanService.Plan plan, String field) {
        return plan.bindings().stream().filter(binding -> field.equals(binding.fieldName()))
                .map(TemplateFieldPlanService.Binding::valuePreview).findFirst().orElseThrow();
    }

    private static TemplateFieldPlanService.Plan preview(Fixture fixture, String code, String version,
            TemplateListPairingService.Request request, TemplateListPairingService.Item row,
            TemplateContentUnitsService.Workspace list, TemplateContentUnitsService.Workspace detail,
            Map<String, String> fields) {
        var options = fixture.plans().pairedOptions(1L, code, version,
                new TemplateFieldPlanService.PairedOptionsRequest(request, row.id()));
        assertTrue(options.list().units().size() >= 2);
        assertEquals(1, options.detail().units().size());
        var listFields = new LinkedHashMap<String, String>();
        for (var unit : options.list().units()) listFields.put(unit.id(), fields.get(unit.id()));
        var body = new TemplateFieldPlanService.PairedRequest(request, row.id(), detail.digest(),
                list.revision(), detail.revision(), options.fieldConfigDigest(),
                listFields,
                Map.of(options.detail().units().getFirst().id(), "content1"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var plan = fixture.plans().previewPaired(1L, code, version, body);
            assertTrue(TemplateFieldPlanService.usesSharedDetail(plan));
            return plan;
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static TemplateContentUnitsService.Unit unit(TemplateContentUnitsService.Source source, String type) {
        return new TemplateContentUnitsService.Unit(source.id(), type, type,
                List.of(new TemplateContentUnitsService.Reference(source.id(), 0, source.rawValue().length())),
                false, null);
    }

    private static TemplateListPairingService.Review approve(Fixture fixture, String code, String version,
            TemplateListPairingService.Request request, TemplateListPairingService.Inspection review,
            List<TemplateListPairingService.Item> rows) {
        var decisions = rows.stream().map(row -> new TemplateListPairingService.Decision(row.id(),
                TemplateListPairingService.Ownership.SHARED_DETAIL, null, null)).toList();
        return fixture.pairing().saveReview(1L, code, version, new TemplateListPairingService.ReviewRequest(request,
                review.review().revision(), review.review().evidenceDigest(), decisions), null);
    }

    private static TemplateListPairingService.Request request(Fixture fixture, String code, String version,
            String path, String selector) {
        var candidates = fixture.pairing().candidates(1L, code, version, 2L, path);
        var group = candidates.containers().stream().filter(row -> selector == null || row.selector().contains(selector))
                .findFirst().orElseThrow();
        return new TemplateListPairingService.Request(2L, path, candidates.digest(), group.id());
    }

    private Fixture fixture(Path root) {
        var storage = new UiPackageStorageService(root);
        var seeds = new TemplateSeedService(storage);
        var menus = mock(OneShotMapper.class);
        var menu = new OneshotEntity();
        menu.setId(2);
        menu.setSiteProjectId(1L);
        menu.setPageType(1);
        menu.setHasManageChannel(true);
        menu.setModelField("""
                [{"fieldName":"title","fieldType":"SingleLine","isOpen":"1"},
                 {"fieldName":"photo1","fieldType":"Image","isOpen":"1"},
                 {"fieldName":"singleLine1","fieldType":"SingleLine","isOpen":"1"},
                 {"fieldName":"content1","fieldType":"Editor","isOpen":"1"}]
                """);
        when(menus.selectActiveMenuModelBySite(1L, 2)).thenReturn(menu);
        when(menus.lockFieldConfiguration(1L, 2L)).thenReturn(menu);
        var units = new TemplateContentUnitsService(seeds, temp.resolve("units").toString());
        var pairing = new TemplateListPairingService(storage, seeds, menus, null, temp.resolve("review").toString());
        var plans = new TemplateFieldPlanService(units, menus, temp.resolve("units").toString());
        plans.setListPairingService(pairing);
        return new Fixture(units, pairing, plans);
    }

    private record Fixture(TemplateContentUnitsService units, TemplateListPairingService pairing,
            TemplateFieldPlanService plans) {}
}

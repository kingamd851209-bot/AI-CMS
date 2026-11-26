package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class PlanSourceOverlapTest {
    @TempDir Path metadata;
    final TemplateContentUnitsService units = mock(TemplateContentUnitsService.class);
    final OneShotMapper menus = mock(OneShotMapper.class);
    final UiPackageStorageService packages = mock(UiPackageStorageService.class);

    @Test
    void preflightBeforeFieldSaveAndConfirmFindBothOverlaps() {
        var first = source("item-118", "营销与获客", "#first");
        var second = source("item-154", "物业管理", "#second");
        var menu = menu();
        var plans = planService("<p id=first>营销与获客</p><p id=second>物业管理</p>",
                List.of(first, second), List.of(unit("a", "营销标题甲", first, 0, 5),
                        unit("b", "营销标题乙", first, 0, 5), unit("c", "物业标题甲", second, 0, 4),
                        unit("d", "物业标题乙", second, 0, 4)));
        var plan = preview(plans);
        assertNotNull(plan.fieldUpdate());
        assertNull(menu.getMenuField());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> plans.preflight(46L, "site", "active", plan.id()));
        for (String expected : List.of("item-118", "item-154", "营销与获客", "物业管理",
                "营销标题甲", "营销标题乙", "物业标题甲", "物业标题乙"))
            assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertNull(menu.getMenuField());
        applyFieldConfig(menu, plan);
        assertEquals(failure.getMessage(), assertThrows(IllegalArgumentException.class,
                () -> confirm(plans, plan.id())).getMessage());
        assertEquals("DRAFT", plans.read(46L, "site", "active", plan.id()).status());
    }

    @Test
    void differentSourceIdsPointingAtSameTextNodeConflict() {
        menu();
        var first = source("parent", "业务：物业管理", "#heading");
        var second = source("child", "物业管理", "#heading > b");
        var plans = planService("<h2 id=heading>业务：<b>物业管理</b></h2>",
                List.of(first, second), List.of(unit("a", "父标题", first, 3, 7),
                        unit("b", "子标题", second, 0, 4)));
        var plan = preview(plans);
        var message = assertThrows(IllegalArgumentException.class,
                () -> plans.preflight(46L, "site", "active", plan.id())).getMessage();
        assertTrue(message.contains("父标题") && message.contains("子标题")
                && message.contains("物业管理"), message);
    }

    @Test
    void adjacentRangesAndEqualTextInDifferentNodesConfirm() {
        var menu = menu();
        var first = source("first", "物业物业", "#first");
        var second = source("second", "物业", "#second");
        var plans = planService("<p id=first>物业物业</p><p id=second>物业</p>",
                List.of(first, second), List.of(unit("a", "首项", first, 0, 2),
                        unit("b", "第二项", first, 2, 4), unit("c", "第三项", second, 0, 2)));
        var plan = preview(plans);
        assertTrue(plans.preflight(46L, "site", "active", plan.id()).requiresFieldUpdate());
        applyFieldConfig(menu, plan);
        assertEquals("CONFIRMED", confirm(plans, plan.id()).status());
    }

    private static void applyFieldConfig(OneshotEntity menu, TemplateFieldPlanService.Plan plan) {
        menu.setMenuField(ContentModelCodec.write(plan.fieldUpdate().getFieldOptions()));
        menu.setModelList(ContentModelCodec.write(plan.fieldUpdate().getFieldListDisplay()));
        menu.setModelSearch(ContentModelCodec.write(plan.fieldUpdate().getFieldSearch()));
    }

    private OneshotEntity menu() {
        var menu = new OneshotEntity();
        menu.setId(255);
        menu.setSiteProjectId(46L);
        menu.setPageType(0);
        when(menus.getModelById(255)).thenReturn(menu);
        return menu;
    }

    private TemplateFieldPlanService planService(String html,
            List<TemplateContentUnitsService.Source> sources,
            List<TemplateContentUnitsService.Unit> workspaceUnits) {
        when(packages.readStaticPage("site", "active", "page.html")).thenReturn(
                new StaticPageSource.Page(html, "digest", List.of(), List.of()));
        when(units.requireSaved("site", "active", "page.html")).thenReturn(
                new TemplateContentUnitsService.Workspace("page.html", "digest", 1L,
                        sources, workspaceUnits, List.of()));
        var service = new TemplateFieldPlanService(units, menus, metadata.toString());
        ReflectionTestUtils.setField(service, "sourceValidator", new TextBindingPlanSourceValidator(packages));
        return service;
    }

    private static TemplateFieldPlanService.Plan preview(TemplateFieldPlanService plans) {
        return plans.preview(46L, "site", "active",
                new TemplateFieldPlanService.Request("page.html", "digest", 1L, 255L, Map.of()));
    }

    private static TemplateFieldPlanService.Plan confirm(TemplateFieldPlanService plans, String id) {
        return plans.confirm(46L, "site", "active", new TemplateFieldPlanService.Confirmation(id, true, true));
    }

    private static TemplateContentUnitsService.Source source(String id, String raw, String selector) {
        return new TemplateContentUnitsService.Source(id, "TEXT", raw, selector, "", null, null);
    }

    private static TemplateContentUnitsService.Unit unit(String id, String name,
            TemplateContentUnitsService.Source source, int start, int end) {
        return new TemplateContentUnitsService.Unit(id, name, "SingleLine",
                List.of(new TemplateContentUnitsService.Reference(source.id(), start, end)), false, null);
    }
}

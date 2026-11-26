package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class TemplateFieldPlanMenuNameTest {
    private static final String CODE = "sample";
    private static final String VERSION = "version";
    private static final String PAGE = "services.html";
    private static final String DIGEST = "example";
    private static final Long SITE_ID = 47L;
    private static final Long PARENT_ID = 255L;
    private static final Long CHILD_ID = 256L;

    @TempDir Path metadata;

    private final TemplateContentUnitsService units = mock(TemplateContentUnitsService.class);
    private final OneShotMapper menus = mock(OneShotMapper.class);
    private final OneshotEntity child = new OneshotEntity();
    private TemplateFieldPlanService plans;

    @BeforeEach
    void setUp() {
        var parent = new OneshotEntity();
        parent.setId(PARENT_ID.intValue());
        parent.setSiteProjectId(SITE_ID);
        parent.setPageType(0);
        when(menus.getModelById(PARENT_ID.intValue())).thenReturn(parent);
        child.setId(CHILD_ID.intValue());
        child.setParentId(PARENT_ID.intValue());
        child.setSiteProjectId(SITE_ID);
        child.setClassName("康养服务");
        child.setHasFrontChannel(false);
        when(menus.selectActiveMenuModelBySite(SITE_ID, CHILD_ID.intValue())).thenReturn(child);
        plans = new TemplateFieldPlanService(units, menus, metadata.toString());
        workspace("SingleLine", "TEXT");
    }

    @Test
    void confirmsWithoutWritingFieldConfigurationAndKeepsBindingAfterRename() {
        var plan = preview();
        assertNull(plan.fieldUpdate());
        var row = plan.bindings().getFirst();
        assertEquals("MENU_NAME", row.action());
        assertEquals(CHILD_ID, row.menuId());
        assertNull(row.fieldName());
        assertFalse(plans.preflight(SITE_ID, CODE, VERSION, plan.id()).requiresFieldUpdate());
        assertThrows(IllegalArgumentException.class, () -> confirm(plan.id(), false));
        child.setClassName("养老服务");
        var confirmed = confirm(plan.id(), true);
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(CHILD_ID, confirmed.bindings().getFirst().menuId());
    }

    @Test
    void rejectsStaleOrUnrelatedChildren() {
        var plan = preview();
        child.setParentId(254);
        assertThrows(IllegalArgumentException.class, () -> confirm(plan.id(), true));
        child.setParentId(PARENT_ID.intValue());
        child.setSiteProjectId(46L);
        assertThrows(IllegalArgumentException.class, this::preview);
    }

    @Test
    void rejectsNonTextAndNonSingleLineUnits() {
        workspace("SingleLine", "IMAGE");
        assertThrows(IllegalArgumentException.class, this::preview);
        workspace("MultiLine", "TEXT");
        assertThrows(IllegalArgumentException.class, this::preview);
    }

    private void workspace(String type, String kind) {
        var source = new TemplateContentUnitsService.Source("label", kind, "原页面名称", "h2", "", null, null);
        var reference = new TemplateContentUnitsService.Reference(source.id(), 0, source.rawValue().length());
        var unit = new TemplateContentUnitsService.Unit("heading", "标题", type, List.of(reference), false, null);
        when(units.requireSaved(CODE, VERSION, PAGE)).thenReturn(
                new TemplateContentUnitsService.Workspace(PAGE, DIGEST, 1L, List.of(source), List.of(unit), List.of()));
    }

    private TemplateFieldPlanService.Plan preview() {
        var request = new TemplateFieldPlanService.Request(
                PAGE, DIGEST, 1L, PARENT_ID, Map.of("heading", "MENU_NAME:" + CHILD_ID));
        return plans.preview(SITE_ID, CODE, VERSION, request);
    }

    private TemplateFieldPlanService.Plan confirm(String planId, boolean acknowledgeReuse) {
        return plans.confirm(SITE_ID, CODE, VERSION,
                new TemplateFieldPlanService.Confirmation(planId, acknowledgeReuse, false));
    }
}

package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.cms.template.PackageDraftAnalysisService.ImportPage;
import com.xinhongru.cms.template.PackageDraftAnalysisService.Scope;
import com.xinhongru.cms.template.TemplateContentUnitsService.Reference;
import com.xinhongru.cms.template.TemplateContentUnitsService.Source;
import com.xinhongru.cms.template.TemplateContentUnitsService.Unit;
import com.xinhongru.cms.template.TemplateContentUnitsService.Workspace;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class PackageDraftWorkspaceReviewTest {
    @TempDir Path root;

    @Test
    void confirmsOnlyTheExpectedSavedWorkspaceAndKeepsOrdinaryReviewStrict() {
        var seeds = mock(TemplateSeedService.class);
        var plans = mock(SiteModelPlanService.class);
        var agent = mock(TemplateDomainAgentService.class);
        var units = mock(TemplateContentUnitsService.class);
        var menus = mock(OneShotMapper.class);
        var scope = new Scope(12, "site", "v1");
        var path = "index.html";
        var digest = "a".repeat(64);
        var otherDigest = "b".repeat(64);
        Runnable guard = () -> {};
        var source = new Source("s1", "TEXT", "来源", "p", "", null);
        var suggestion = new Unit(
                "agent-1", "标题", "SingleLine", List.of(new Reference("s1", 0, 2)), false, "");
        var result = new TemplateDomainAgentService.Result(
                path, "v1", digest, 0, null, "SINGLE", "建议", List.of(suggestion),
                List.of(new TemplateDomainAgentService.Mapping("agent-1", null, "PENDING", "待审核")),
                List.of(), List.of(), 1, 1, null);
        var initial = new Workspace(path, digest, 0, List.of(source), List.of(), List.of());
        var saved = new Workspace(path, digest, 2, List.of(source), List.of(suggestion), List.of());
        when(seeds.pages("site", "v1")).thenReturn(List.of(new TemplateSeedService.Page(path, path)));
        when(units.load("site", "v1", path)).thenReturn(initial);
        when(menus.selectActiveMenuModelBySite(12L, 31)).thenReturn(new OneshotEntity());
        var service = new PackageDraftAnalysisService(seeds, plans, agent, units, menus, root.toString());
        try {
            var state = service.importPages(
                    scope, 0, null, null, List.of(new ImportPage(path, List.of(result), null, null)), guard);
            long revision = state.revision();
            when(units.load("site", "v1", path)).thenReturn(saved);
            assertThrows(ResponseStatusException.class,
                    () -> service.review(scope, revision, path, "CONFIRMED", 31L, null, guard));
            assertThrows(ResponseStatusException.class,
                    () -> service.review(scope, revision, path, "CONFIRMED", 31L, null, 1L, digest, guard));
            assertThrows(ResponseStatusException.class,
                    () -> service.review(scope, revision, path, "CONFIRMED", 31L, null, 2L, otherDigest, guard));
            assertThrows(IllegalArgumentException.class,
                    () -> service.review(scope, revision, path, "CONFIRMED", 31L, null, 2L, null, guard));
            assertThrows(IllegalArgumentException.class,
                    () -> service.review(scope, revision, path, "NEEDS_REVIEW", 31L, null, 2L, digest, guard));
            assertEquals(revision, service.get(scope).revision());
            when(units.load("site", "v1", path)).thenReturn(
                    new Workspace(path, otherDigest, 2, List.of(source), List.of(suggestion), List.of()));
            assertThrows(ResponseStatusException.class,
                    () -> service.review(scope, revision, path, "CONFIRMED", 31L, null, 2L, otherDigest, guard));
            when(units.load("site", "v1", path)).thenReturn(saved);
            var confirmed = service.review(scope, revision, path, "CONFIRMED", 31L, null, 2L, digest, guard);
            assertEquals(revision + 1, confirmed.revision());
            assertEquals("CONFIRMED", confirmed.pages().getFirst().review());
            assertEquals(31L, confirmed.pages().getFirst().reviewMenuId());
            assertEquals(0, service.page(scope, path).results().getFirst().revision());
            verify(units, never()).save(anyString(), anyString(), any());
        } finally {
            service.stop();
        }
    }
}

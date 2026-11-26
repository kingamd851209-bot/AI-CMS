package com.xinhongru.cms.template;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import java.nio.file.Path;
import java.util.List;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class MenuNameRenderingTest {
    private final TextBindingCompiler compiler = new TextBindingCompiler();
    private final OneShotMapper menus = mock(OneShotMapper.class);
    private final OneshotEntity parent = menu(10, 9, 8L, "父栏目");
    private final TemplateFieldPlanService.Binding binding =
            new TemplateFieldPlanService.Binding("unit-1", "区块标题", "SingleLine",
                    List.of(new TemplateContentUnitsService.Reference("source-1", 0, 4)),
                    "MENU_NAME", null, 0, 4, "版式示例", "", false, null, 11L);
    private final TemplateContentUnitsService.Source source =
            new TemplateContentUnitsService.Source("source-1", "TEXT", "版式示例",
                    "#heading", "版式示例", null);
    private final String html = "<h2 id=heading class=section>版式示例</h2>";

    @Test
    void privateDraftUsesCurrentNameAndEscapesMarkupWithoutRecompiling() {
        var child = menu(11, 10, 8L, "技术文档");
        child.setHasFrontChannel(false);
        when(menus.selectActiveMenuBySite(8L, 11)).thenReturn(child);
        var compiled = compiler.compile(html, List.of(binding), List.of(source), 0);
        assertThat(compiled.bound()).containsExactly(binding);
        assertThat(compiled.pending()).isEmpty();

        var storage = mock(TemplateStorageService.class);
        when(storage.resolveTemplateDir("site")).thenReturn(Path.of("site"));
        var preview = new TemplatePreviewService(null, null, null, null, null, storage, List.of());
        ReflectionTestUtils.setField(preview, "menus", menus);
        assertThat(Jsoup.parse(preview.renderControlledDraft("site", compiled, parent))
                        .selectFirst("#heading.section").text())
                .isEqualTo("技术文档");
        child.setClassName("<img src=x onerror=alert(1)>");
        var rendered = Jsoup.parse(preview.renderControlledDraft("site", compiled, parent));
        assertThat(rendered.selectFirst("#heading.section").text())
                .isEqualTo("<img src=x onerror=alert(1)>");
        assertThat(rendered.select("img")).isEmpty();
    }

    @Test
    void publicTemplateAllowsOnlyTheReviewedChildNameAndReadsLiveProjection() {
        var child = menu(11, 10, 8L, "产品手册");
        when(menus.selectActiveMenuBySite(8L, 11)).thenReturn(child);
        var compiled = compiler.compilePublic(html, List.of(binding), List.of(source), 0);
        compiler.validatePublicControlled(compiled, 0);
        assertThat(compiled.html()).contains("cmsBoundMenuNames").doesNotContain("cmsDraftMenuNames");
        assertThatThrownBy(() -> compiler.validatePublicControlled(
                new TextBindingCompiler.Compilation(compiled.html().replace("['11']", "['12']"),
                        compiled.bound(), List.of()), 0))
                .hasMessageContaining("非受控");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        var context = new Context();
        child.setClassName("安装指南");
        context.setVariable("cmsBoundMenuNames", MenuNameBinding.names(menus, parent, compiled.bound(), false));
        assertThat(Jsoup.parse(engine.process(compiled.html(), context)).selectFirst("#heading").text())
                .isEqualTo("安装指南");
    }

    @Test
    void staleOrForeignChildCannotRevealItsName() {
        var invalid = List.of(menu(11, 20, 8L, "错误父栏目"),
                menu(11, 10, 99L, "其他站点"), menu(12, 10, 8L, "错误栏目ID"));
        for (var child : invalid) {
            when(menus.selectActiveMenuBySite(8L, 11)).thenReturn(child);
            assertThat(MenuNameBinding.names(menus, parent, List.of(binding), false)).containsEntry("11", "");
            assertThatThrownBy(() -> MenuNameBinding.names(menus, parent, List.of(binding), true))
                    .hasMessageContaining("引用的子栏目已不可用");
        }
    }

    private static OneshotEntity menu(int id, int parentId, Long siteId, String name) {
        var menu = new OneshotEntity();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setSiteProjectId(siteId);
        menu.setClassName(name);
        menu.setIsDeleted(false);
        return menu;
    }
}


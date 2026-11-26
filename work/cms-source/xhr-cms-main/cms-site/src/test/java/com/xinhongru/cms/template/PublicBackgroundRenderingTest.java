package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class PublicBackgroundRenderingTest {
    private static final String FIELD = "ext_44711368b9994a0fbc2afb1da228ba85";
    private static final String SOURCE_PATH = "business/index.aspx";
    private static final String ORIGINAL = "../images/bus_01.jpg";
    private static final String REBASED = "ui:/images/bus_01.jpg";
    private final TextBindingCompiler compiler = new TextBindingCompiler();

    @Test
    void validatesCompilerGeneratedRebasedBackgroundWithPrivateValidationUnchanged() {
        var binding = binding();
        var source = source();
        var privateTemplate = compiler.compile(html(), List.of(binding), List.of(source), 0);
        assertDoesNotThrow(() -> compiler.validateControlled(privateTemplate, 0));
        var rebased = rebasePublic();
        assertTrue(rebased.html().contains(REBASED));
        assertTrue(rebased.html().contains("cmsBoundMedia["));
        assertDoesNotThrow(() -> compiler.validatePublicControlled(rebased, 0));

        var rebasedPrivate = replaceHtml(privateTemplate,
                PublicBindingResources.rebase(privateTemplate.html(), SOURCE_PATH));
        assertThrows(IllegalArgumentException.class, () -> compiler.validateControlled(rebasedPrivate, 0));
    }

    @Test
    void rejectsUncontrolledPublicStyleExpressionsAndUnsafeFallbacks() {
        var rebased = rebasePublic();
        var altered = List.of(
                rebased.html().replace("cmsBoundMedia[", "arbitraryModel["),
                rebased.html().replace(" th:style=", " data-th-style="),
                rebased.html().replace(REBASED, "https://example.invalid/image.jpg"),
                rebased.html().replace(REBASED, "ui:/../image.jpg"),
                rebased.html().replace(REBASED, "ui:/images/../image.jpg"),
                rebased.html().replace(REBASED, "ui://other/image.jpg"),
                rebased.html().replace(REBASED, "ui:/image.jpg?unexpected"),
                rebased.html().replace("</div>", "<img src='" + REBASED + "'></div>"),
                rebased.html().replace("');", "'); color: red;"));
        for (String html : altered) {
            assertThrows(IllegalArgumentException.class,
                    () -> compiler.validatePublicControlled(replaceHtml(rebased, html), 0), html);
        }
    }

    private TextBindingCompiler.Compilation rebasePublic() {
        var result = compiler.compilePublic(html(), List.of(binding()), List.of(source()), 0);
        return replaceHtml(result, PublicBindingResources.rebase(result.html(), SOURCE_PATH));
    }

    private static TextBindingCompiler.Compilation replaceHtml(
            TextBindingCompiler.Compilation original, String html) {
        return new TextBindingCompiler.Compilation(html, original.bound(), original.pending());
    }

    private static String html() {
        return "<div id='box' style='background-image: url(" + ORIGINAL + ");'></div>";
    }

    private static TemplateContentUnitsService.Source source() {
        return new TemplateContentUnitsService.Source(
                "background-1", "BACKGROUND_IMAGE", ORIGINAL, "#box", "", null, null);
    }

    private static TemplateFieldPlanService.Binding binding() {
        return new TemplateFieldPlanService.Binding(
                "hero", "横幅背景", "Image",
                List.of(new TemplateContentUnitsService.Reference("background-1", 0, ORIGINAL.length())),
                "CREATE", FIELD, 2048, ORIGINAL.length(), ORIGINAL, null, false);
    }
}

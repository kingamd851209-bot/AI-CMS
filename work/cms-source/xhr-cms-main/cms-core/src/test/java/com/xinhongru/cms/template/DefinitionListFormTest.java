package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class DefinitionListFormTest {
    private static final String CONTACT = """
            <section class="contact"><dl><dt><i>*</i>电话</dt><dd><input type="text"></dd></dl>
            <dl><dt>留言内容</dt><dd><textarea></textarea></dd></dl>
            <div><li><dl><dt>验证码</dt><dd><input type="text"></dd></dl></li>
            <li><img src="/sample.png"></li></div><div><button>提交留言</button></div></section>
            """;

    @Test void inspectionAndCompilationUseTheSameFieldIdentities() {
        var inspected = Jsoup.parse(CONTACT);
        DefinitionListForm.prepare(inspected, null);
        var region = inspected.selectFirst("section[role=form]");
        assertNotNull(region);
        assertEquals(2, region.select("[aria-label][id]").size());
        assertEquals(1, region.select("input[type=tel][required]").size());
        assertTrue(region.select("img,button").isEmpty());
        var compiled = Jsoup.parse(CONTACT);
        DefinitionListForm.prepare(compiled, region.cssSelector());
        assertEquals(region.outerHtml(), compiled.selectFirst("section").outerHtml());
    }

    @Test void ambiguousOrUnselectedControlsRetainOriginalSemantics() {
        var ambiguous = Jsoup.parse(CONTACT.replace("<dt>留言内容</dt>", "<dt>甲</dt><dt>乙</dt>"));
        var original = ambiguous.outerHtml();
        DefinitionListForm.prepare(ambiguous, null);
        assertEquals(original, ambiguous.outerHtml());
        var unselected = Jsoup.parse(CONTACT);
        original = unselected.outerHtml();
        DefinitionListForm.prepare(unselected, ".another-form");
        assertEquals(original, unselected.outerHtml());
    }
}

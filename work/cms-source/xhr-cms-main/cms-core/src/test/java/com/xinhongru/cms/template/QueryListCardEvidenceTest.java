package com.xinhongru.cms.template;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryListCardEvidenceTest {
    @Test
    void keepTheLinkedTitleAndIndependentCopyButNotAnAggregateHeading() {
        var doc = Jsoup.parse("<article><h2><a>公告</a></h2><p>摘要 <b>公告</b></p></article>");
        var sources = new ArrayList<TemplateContentUnitsService.Source>();
        for (var node : doc.select("h2,a,p,b")) {
            sources.add(new TemplateContentUnitsService.Source(node.tagName(), "TEXT",
                    node.text(), node.cssSelector(), "", null));
        }
        QueryListPresentation.removeWrappedTextDuplicates(doc.selectFirst("article"), sources, java.util.Set.of("h2"));
        assertEquals(4, sources.size());
        QueryListPresentation.removeWrappedTextDuplicates(doc.selectFirst("article"), sources, java.util.Set.of());
        assertEquals(List.of("a","p","b"), sources.stream().map(TemplateContentUnitsService.Source::id).toList());
    }
}

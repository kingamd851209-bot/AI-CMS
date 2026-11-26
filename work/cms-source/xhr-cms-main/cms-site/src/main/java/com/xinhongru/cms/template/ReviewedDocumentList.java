package com.xinhongru.cms.template;

import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** 按同版样本证据更新公司制度区，保留站点字段与导航。 */
record ReviewedDocumentList(String originalLink, List<String> originalTitles,
        String archive, List<Document> documents, Chart chart) {
    private static final String MARK = "data-governance-official";

    record Document(String title, String url, String source) {}
    record Chart(String selector, String original, String asset, String digest, String alt) {}

    static ReviewedDocumentList read(Path workspace, TemplateFieldPlanService.Plan plan) {
        var rule = ReviewedReportCards.readRule(workspace, plan, "reviewed-document-lists", ReviewedDocumentList.class);
        if (rule == null) return null;
        if (rule.originalLink == null || !rule.originalLink.startsWith("../")
                || rule.originalTitles == null || rule.originalTitles.isEmpty() || rule.originalTitles.size() > 30
                || !ReviewedReportCards.publishedUrl(rule.archive) || rule.documents == null || rule.documents.isEmpty()
                || rule.documents.size() > 30 || rule.documents.stream().anyMatch(row -> row.title == null
                || row.title.isBlank() || row.source == null || row.source.isBlank()
                || !ReviewedReportCards.publishedUrl(row.url))
                || rule.documents.stream().map(Document::url).distinct().count() != rule.documents.size())
            throw new IllegalArgumentException("制度清单缺少审定原页或正式文件");
        if (rule.chart != null)
            ReviewedReportCards.verifyAsset(ReviewedReportCards.uploadRoot(workspace, plan),
                    rule.chart.asset, rule.chart.digest, "images", "png|jpe?g|webp");
        return rule;
    }

    String apply(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var main = only(document, "body > .main");
        var section = only(main, ".governance_02 .system_list");
        if (section.hasAttr(MARK)) return html;
        var list = only(section, ".system_list > ul");
        if (!list.children().stream().map(row -> only(row, "dd a").text()).toList().equals(originalTitles)
                || list.select("a[href]").size() != originalTitles.size() * 2
                || list.select("a[href]").stream().anyMatch(anchor -> !originalLink.equals(anchor.attr("href"))))
            throw new IllegalArgumentException("公司治理原样本已发生变化");
        var prototype = list.child(0).clone();
        list.empty();
        for (var entry : documents) {
            var card = prototype.clone();
            only(card, "dd a").attr("href", entry.url).removeAttr("target").removeAttr("download").text(entry.title);
            only(card, "dt a").attr("href", entry.url).removeAttr("target").removeAttr("download")
                    .attr("aria-label", "打开" + entry.title + "PDF原件").text("PDF 原件");
            only(card, "dd").appendElement("span").addClass("fnt_16 governance-document-source").text(entry.source);
            list.appendChild(card);
        }
        section.attr(MARK, "documents").appendElement("p").addClass("fnt_16")
                .appendElement("a").attr("href", archive).text("金隅集团官网公司治理");
        section.appendElement("style").appendText("[data-governance-official] dl{gap:12px}"
                + "[data-governance-official] dd{flex:1;min-width:0}"
                + "[data-governance-official] dt .pub_btn{display:inline-flex;align-items:center;"
                + "white-space:nowrap;min-height:34px;padding:4px 10px}"
                + ".governance-document-source{display:block;margin-top:6px}");
        if (chart != null) {
            var image = only(main, chart.selector);
            String originalPath = chart.original.replaceFirst("^\\.\\.", "");
            String src = image.attr("src");
            if (!image.is("img") || !List.of(chart.original, originalPath, "ui:" + originalPath).contains(src))
                throw new IllegalArgumentException("公司治理结构图已变化");
            image.attr("src", chart.asset).attr("alt", chart.alt);
        }
        return document.outerHtml();
    }

    private static Element only(Element parent, String selector) {
        var matches = parent.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("公司治理制度定位不唯一：" + selector);
        return matches.first();
    }
}

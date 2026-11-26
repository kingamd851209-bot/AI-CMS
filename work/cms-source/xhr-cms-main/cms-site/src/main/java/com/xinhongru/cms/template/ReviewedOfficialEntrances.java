package com.xinhongru.cms.template;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** 将具有明确来源的占位招聘信息和行情替换为官方页面入口。 */
record ReviewedOfficialEntrances(Recruitment recruitment, Quotes quotes) {
    private static final String MARK = "data-reviewed-official-entrances";
    private static final Set<String> INACTIVE = Set.of("", "#", "##", "javascript:void(0);", "avascript:void(0);");

    record Recruitment(String society, String campus, String historyUrl, String historyTitle,
                       String published, String deadline, String sampleDate, List<String> sampleTitles) {}

    record Quotes(String sourceUrl, List<Market> markets, int charts, int samplePdfLinks) {}

    record Market(String code, String name, String originalPrice) {}

    static ReviewedOfficialEntrances read(Path workspace, TemplateFieldPlanService.Plan plan) {
        var review = ReviewedReportCards.readRule(
                workspace, plan, "reviewed-official-entrances", ReviewedOfficialEntrances.class);
        if (review == null) return null;
        if ((review.recruitment == null) == (review.quotes == null))
            throw new IllegalArgumentException("请选择一种官方入口审核类型");
        if (review.recruitment != null) {
            var job = review.recruitment;
            for (String url : List.of(job.society, job.campus, job.historyUrl)) requireOfficial(url);
            if (job.sampleTitles == null || job.sampleTitles.size() != 8)
                throw new IllegalArgumentException("招聘入口缺少原样板行程清单");
        } else {
            requireOfficial(review.quotes.sourceUrl);
            if (review.quotes.markets == null || review.quotes.markets.size() != 2
                    || !Set.of(0, 2).contains(review.quotes.charts)
                    || !Set.of(0, 8).contains(review.quotes.samplePdfLinks))
                throw new IllegalArgumentException("行情审核清单缺少两个证券市场或旧样板边界");
        }
        return review;
    }

    private static void requireOfficial(String url) {
        if (!ReviewedReportCards.publishedUrl(url))
            throw new IllegalArgumentException("公开入口必须使用已核验的 HTTPS 地址");
    }

    String apply(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var main = one(document, "body > .main");
        if (main.hasAttr(MARK)) return html;
        if (recruitment != null) updateRecruitment(main);
        else updateQuotes(main);
        main.attr(MARK, recruitment != null ? "recruitment" : "quotes");
        return document.outerHtml();
    }

    private void updateRecruitment(Element main) {
        var archive = one(main, ".applyForJob_01 .applyForJob_01_list");
        var items = one(archive, "ul");
        if (!items.children().stream().map(row -> one(row, ".applyForJob_01_cnt").text()).toList()
                .equals(recruitment.sampleTitles)
                || !items.children().stream()
                        .allMatch(row -> one(row, ".applyForJob_01_date").text().equals(recruitment.sampleDate)))
            throw new IllegalArgumentException("招聘动态已不再是审核的重复样例");
        requireInactive(items);
        var pagination = one(archive, ".page-wrap");
        requireInactive(pagination);
        pagination.remove();
        var sample = items.child(0).clone();
        items.empty().appendChild(sample);
        var link = one(sample, "a[href]");
        link.attr("href", recruitment.historyUrl).removeAttr("target");
        one(sample, ".applyForJob_01_date").text(recruitment.published);
        one(sample, ".applyForJob_01_cnt").text(recruitment.historyTitle);
        link.appendElement("p").addClass("fnt_16").text("报名已结束（" + recruitment.deadline + "）");

        var events = one(main, ".applyForJob_02");
        if (events.select(".applyForJob_select").size() != 1 || events.selectFirst("form") != null)
            throw new IllegalArgumentException("校招行程已不是原样板");
        requireInactive(events);
        events.remove();

        var cards = one(main, ".applyForJob_03 > ul");
        if (cards.childrenSize() != 2)
            throw new IllegalArgumentException("正式招聘两类入口与审核范围不符");
        updatePortal(cards.child(0), "社会招聘", recruitment.society);
        updatePortal(cards.child(1), "校园招聘", recruitment.campus);
        cards.parent().appendElement("p").addClass("fnt_16")
                .text("请在金隅集团招聘官网查看职位并投递。");
    }

    private static void updatePortal(Element card, String label, String url) {
        if (!label.equals(one(card, ".applyForJob_03_tit").text()) || card.select("a[href]").size() != 2)
            throw new IllegalArgumentException("招聘入口与审核用途不符");
        requireInactive(card);
        for (var link : card.select("a[href]"))
            link.attr("href", url).attr("aria-label", "查看" + label).removeAttr("target");
        one(card, ".applyForJob_03_btn").text("查看" + label);
    }

    private void updateQuotes(Element main) {
        var blocks = main.select(".investor_01 .invest-left, .investor_01 .invest-right");
        if (blocks.size() != quotes.markets.size()) throw new IllegalArgumentException("行情区数量已变化");
        for (int index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index);
            var market = quotes.markets.get(index);
            var title = one(block, ".stock_tit");
            var rawPrice = one(block, ".stock_num h2").ownText();
            if (!title.text().contains(market.code) || !rawPrice.equals(market.originalPrice))
                throw new IllegalArgumentException("证券代码或旧报价证据已变化");
            one(title, "span").text(market.name + " " + market.code);
            one(block, ".kline-div").remove();
            one(block, ".stock_p").remove();
            block.appendElement("p").addClass("fnt_16").text("行情来源：金隅集团官方网站");
            var prior = block.children().stream().filter(el -> el.is("a[href].pub_btn")
                    && el.text().equals("更多")).findFirst().orElse(null);
            if (prior == null) prior = block.appendElement("a").addClass("pub_btn fnt_16")
                    .text("查看" + market.name + "官网行情");
            prior.attr("href", quotes.sourceUrl).attr("aria-label", "查看" + market.name + "官网行情");
        }
        var charts = main.select(".kline_02");
        if (charts.size() != quotes.charts || charts.stream().anyMatch(chart ->
                chart.select("img[src]").size() != 1 || !chart.select("script,iframe,form").isEmpty()))
            throw new IllegalArgumentException("原行情图位置已变化");
        charts.remove();
        if (quotes.samplePdfLinks > 0) {
            var summary = one(main, ".investor_02 .notice_list");
            var links = summary.select("a[href]");
            if (links.size() != quotes.samplePdfLinks
                    || links.stream().anyMatch(link -> !link.attr("href").equals("../upload/cs.pdf")))
                throw new IllegalArgumentException("公告摘要中包含已调整的正式内容");
            summary.remove();
        }
    }

    private static void requireInactive(Element parent) {
        if (parent.select("a[href]").stream().anyMatch(link -> !INACTIVE.contains(link.attr("href"))))
            throw new IllegalArgumentException("样板区已有实际链接，请先复核");
    }

    private static Element one(Element parent, String selector) {
        var matches = parent.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("官方入口定位不唯一：" + selector);
        return matches.first();
    }
}

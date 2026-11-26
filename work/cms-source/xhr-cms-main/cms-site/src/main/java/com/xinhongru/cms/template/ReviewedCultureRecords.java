package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.web.service.InfoService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** 在已有受控单页中投影直属文化产品列表的实际发布记录。 */
record ReviewedCultureRecords(long targetMenuId, String originalPdf, int originalLinks,
                              List<String> bookTypes, String newspaperType) {
    private static final String MARK = "data-culture-records";
    private static final Map<String, Set<String>> INSTRUCTIONS = Map.of(
            "th:each", Set.of("row : ${cmsBoundCulture.newspapers}", "row : ${cmsBoundCulture.covers}"),
            "th:text", Set.of("${row.title}"), "th:alt", Set.of("${row.title}"),
            "th:href", Set.of("${row.href}", "${row.pdf}", "${cmsBoundCulture.catalog}"),
            "th:src", Set.of("${row.cover}"),
            "th:if", Set.of("${!#strings.isEmpty(row.pdf)}", "${#strings.isEmpty(row.pdf)}",
                    "${!#strings.isEmpty(row.cover)}", "${#lists.isEmpty(cmsBoundCulture.newspapers)}"));

    static ReviewedCultureRecords read(Path workspace, TemplateFieldPlanService.Plan plan) {
        var rule = ReviewedReportCards.readRule(workspace, plan, "reviewed-culture-records", ReviewedCultureRecords.class);
        if (rule == null) return null;
        if (rule.targetMenuId <= 0 || rule.originalLinks < 1 || rule.originalLinks > 100
                || rule.originalPdf == null || !rule.originalPdf.startsWith("../")
                || rule.bookTypes == null || rule.bookTypes.isEmpty() || rule.bookTypes.size() > 10
                || rule.bookTypes.stream().anyMatch(String::isBlank)
                || rule.bookTypes.stream().distinct().count() != rule.bookTypes.size()
                || rule.newspaperType == null || rule.newspaperType.isBlank())
            throw new IllegalArgumentException("文化产品缺少审核栏目和原样板证据");
        return rule;
    }

    String apply(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var area = only(document, "body > .main > .party_culture");
        if (area.hasAttr(MARK)) return html;
        var links = area.select("a[href]").stream().filter(anchor -> originalPdf.equals(anchor.attr("href"))).toList();
        var books = area.select(".party_c01 ul > li");
        if (links.size() != originalLinks || books.size() != bookTypes.size())
            throw new IllegalArgumentException("文化产品含不同的原清单或附件");
        for (int index = 0; index < books.size(); index++) {
            var book = books.get(index);
            var link = only(book, "a[href]");
            if (!originalPdf.equals(link.attr("href"))) throw new IllegalArgumentException("文化出版物原目标已变化");
            String bookKey = Integer.toString(index + 1);
            book.attr("data-culture-book", bookKey).attr("data-culture-type", bookTypes.get(index));
            book.attr("th:if", lookup(bookKey, true));
            link.removeAttr("href").removeAttr("target").attr("th:with", "row=" + lookup(bookKey, false))
                    .attr("th:href", "${row.href}");
            image(only(link, "img"));
        }
        var newspaper = only(area, ".party_c02");
        var slides = only(newspaper, ".party_slider .swiper-wrapper");
        var item = slides.empty().appendElement("li").addClass("swiper-slide")
                .attr("th:each", "row : ${cmsBoundCulture.covers}");
        var frame = item.appendElement("a").attr("th:href", "${row.href}").appendElement("dl");
        image(frame.appendElement("dt").appendElement("img").addClass("ratio-img").attr("data-ratio", "1.375"));
        frame.appendElement("dd").appendElement("h3").addClass("fnt_20 bold").attr("th:text", "${row.title}");

        var wrap = only(newspaper, ".party_c02 > .wrap");
        for (var element : new ArrayList<>(wrap.children()))
            if (!element.is("h2,.party_slider")) element.remove();
        var entry = wrap.appendElement("ul").addClass("culture-records")
                .appendElement("li").attr("th:each", "row : ${cmsBoundCulture.newspapers}");
        entry.appendElement("a").attr("th:href", "${row.href}").attr("th:text", "${row.title}");
        entry.appendElement("a").attr("th:href", "${row.pdf}").attr("th:if", "${!#strings.isEmpty(row.pdf)}").text("阅读原刊 PDF");
        entry.appendElement("span").attr("th:if", "${#strings.isEmpty(row.pdf)}").text("样刊资料");
        wrap.appendElement("p").attr("th:if", "${#lists.isEmpty(cmsBoundCulture.newspapers)}").text("暂无已发布的北京金隅报记录。");
        wrap.appendElement("a").addClass("pub_more").attr("th:href", "${cmsBoundCulture.catalog}").text("全部文化产品");
        area.attr(MARK, Long.toString(targetMenuId)).attr("data-culture-newspaper", newspaperType);
        area.appendElement("style").appendText(".culture-records{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));"
                + "gap:14px 32px;margin:30px 0}.culture-records li{display:flex;justify-content:space-between;align-items:center;"
                + "gap:12px;padding:12px 0;border-bottom:1px solid #ddd}.culture-records a{text-decoration:underline;"
                + "text-underline-offset:.2em}.culture-records span{color:#575757}");
        if (area.select("a[href]").stream().anyMatch(anchor -> originalPdf.equals(anchor.attr("href"))))
            throw new IllegalArgumentException("文化产品仍含原样本下载");
        return document.outerHtml();
    }

    private static void image(Element image) {
        image.removeAttr("src").removeAttr("onerror").attr("th:src", "${row.cover}").attr("th:alt", "${row.title}")
                .attr("th:if", "${!#strings.isEmpty(row.cover)}").attr("loading", "lazy");
    }

    private static String lookup(String key, boolean condition) {
        return "${cmsBoundCulture.books['" + key + "']" + (condition ? " != null}" : "}");
    }

    record Rows(Map<String, Map<String, String>> books, List<Map<String, String>> newspapers,
                List<Map<String, String>> covers, String catalog) {
        Map<String, Object> model() {
            return Map.of("books", books, "newspapers", newspapers, "covers", covers, "catalog", catalog);
        }
    }

    Rows publishedRows(Long siteId, OneshotEntity owner, OneShotMapper menus, InfoService info, ContentLinkService links) {
        var target = menus.selectPublicBindingMenu(siteId, targetMenuId);
        if (target == null || !Objects.equals(target.getSiteProjectId(), siteId)
                || !Objects.equals(target.getParentId(), owner.getId())
                || !Objects.equals(target.getSiteRootId(), owner.getSiteRootId()) || !Integer.valueOf(1).equals(target.getPageType())
                || !Boolean.TRUE.equals(target.getHasFrontChannel()) || Boolean.TRUE.equals(target.getIsDeleted()))
            throw new IllegalArgumentException("文化产品记录须来自当前单页直属的已公开原生列表");
        String catalog = links.getListUrl(target);
        if (!cmsPage(catalog)) throw new IllegalArgumentException("文化产品列表尚无正式本机地址");
        var books = new LinkedHashMap<String, Map<String, String>>();
        var newspapers = new ArrayList<Map<String, String>>();
        for (int page = 1; ; page++) {
            var batch = info.getEventSummaryPage(siteId, target.getId(), page, 100).list();
            for (var record : batch) {
                String title = Objects.toString(record.getTitle(), "");
                String type = Objects.toString(record.getSingleLine1(), "");
                String href = links.getDetailUrl(record, target);
                if (title.isBlank() || !cmsPage(href)) continue;
                String cover = cultureCover(record.getPhoto1());
                String pdf = ReviewedDisclosureRows.resource(record.getFile1(), "files", "pdf");
                var row = Map.of("title", title, "href", href, "cover", cover, "pdf", pdf);
                int index = bookTypes.indexOf(type);
                if (index >= 0) books.putIfAbsent(Integer.toString(index + 1), row);
                if (newspaperType.equals(type)) newspapers.add(row);
            }
            if (batch.size() < 100) break;
        }
        return new Rows(Map.copyOf(books), List.copyOf(newspapers),
                newspapers.stream().filter(row -> !row.get("cover").isBlank()).limit(24).toList(), catalog);
    }

    private static boolean cmsPage(String href) {
        return href != null && href.matches("/(?:[a-zA-Z0-9_-]+/)+[a-zA-Z0-9_-]+\\.html");
    }

    private static String cultureCover(String photo) {
        if (photo != null && photo.matches("ui:/images/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:png|jpe?g|webp)"))
            return photo.substring("ui:".length());
        return ReviewedDisclosureRows.resource(photo, "images", "png|jpe?g|webp");
    }

    static boolean supports(String html) { return html.contains(MARK); }

    static Rows publishedRows(String html, Long siteId, OneshotEntity owner, OneShotMapper menus,
            InfoService info, ContentLinkService links) {
        var area = only(Jsoup.parse(html), "[" + MARK + "]");
        return new ReviewedCultureRecords(Long.parseLong(area.attr(MARK)), "", 0,
                area.select("[data-culture-book]").eachAttr("data-culture-type"), area.attr("data-culture-newspaper"))
                .publishedRows(siteId, owner, menus, info, links);
    }

    static String withoutInstructions(String html) {
        if (!supports(html)) return html;
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var area = only(document, "[" + MARK + "]");
        for (var book : area.select("[data-culture-book]")) {
            String key = book.attr("data-culture-book");
            if (key.matches("[1-9][0-9]?")) {
                if (book.attr("th:if").equals(lookup(key, true))) book.removeAttr("th:if");
                for (var link : book.select("a[th:with]"))
                    if (link.attr("th:with").equals("row=" + lookup(key, false))) link.removeAttr("th:with");
            }
        }
        for (var node : area.getAllElements()) INSTRUCTIONS.forEach((attribute, allowed) -> {
            if (allowed.contains(node.attr(attribute))) node.removeAttr(attribute);
        });
        return document.outerHtml();
    }

    String preview(String html, Rows rows) {
        var document = Jsoup.parse(apply(html));
        document.outputSettings().prettyPrint(false);
        var area = only(document, "[" + MARK + "]");
        for (var book : area.select("[data-culture-book]")) {
            var row = rows.books.get(book.attr("data-culture-book"));
            if (row == null) book.remove();
            else fill(book, row);
        }
        repeat(only(area, ".swiper-wrapper"), rows.covers);
        repeat(only(area, ".culture-records"), rows.newspapers);
        var empty = only(area, "p[th:if]");
        if (!rows.newspapers.isEmpty()) empty.remove();
        only(area, "a.pub_more").attr("href", rows.catalog);
        return withoutInstructions(document.outerHtml());
    }

    private static void repeat(Element list, List<Map<String, String>> rows) {
        var prototype = list.child(0).clone();
        list.empty();
        for (var row : rows) {
            var item = prototype.clone();
            fill(item, row);
            list.appendChild(item);
        }
    }

    private static void fill(Element item, Map<String, String> row) {
        for (var node : item.getAllElements()) {
            switch (node.attr("th:href")) {
                case "${row.href}" -> node.attr("href", row.get("href"));
                case "${row.pdf}" -> {
                    if (row.get("pdf").isBlank()) node.remove();
                    else node.attr("href", row.get("pdf"));
                }
            }
            if (node.attr("th:text").equals("${row.title}")) node.text(row.get("title"));
            if (node.attr("th:src").equals("${row.cover}")) {
                if (row.get("cover").isBlank()) node.remove();
                else node.attr("src", row.get("cover")).attr("alt", row.get("title"));
            }
            if (node.attr("th:if").equals("${#strings.isEmpty(row.pdf)}") && !row.get("pdf").isBlank()) node.remove();
        }
    }

    private static Element only(Element parent, String selector) {
        var matches = parent.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("文化产品定位不唯一：" + selector);
        return matches.first();
    }
}

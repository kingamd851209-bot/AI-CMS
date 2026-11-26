package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.web.service.InfoService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** 绑定已审核的样板位置。披露值来自本站后台子栏目。 */
record ReviewedDisclosureRows(
        String containerSelector,
        String listSelector,
        String titleSelector,
        String dateSelector,
        String originalPdf,
        int originalLinks,
        String archive,
        long targetMenuId,
        boolean cover) {
    private static final String MARK = "data-reviewed-disclosures";
    private static final String SCRIPT = "/js/reviewed-disclosure-filters.js";
    private static final Map<String, Set<String>> INSTRUCTIONS = Map.of(
            "th:each", Set.of("row : ${cmsBoundDisclosures}"),
            "th:attr", Set.of("data-disclosure-year=${row.year},data-disclosure-type=${row.type}",
                    "download=${row.filename}"),
            "th:text", Set.of("${row.title}", "${row.date}"),
            "th:href", Set.of("${row.pdf}"),
            "th:src", Set.of("${row.cover}"),
            "th:alt", Set.of("${row.title}"));

    static ReviewedDisclosureRows read(Path workspace, TemplateFieldPlanService.Plan plan) {
        var review = ReviewedReportCards.readRule(
                workspace, plan, "reviewed-disclosure-rows", ReviewedDisclosureRows.class);
        if (review != null && (!ReviewedReportCards.publishedUrl(review.archive) || review.targetMenuId <= 0))
            throw new IllegalArgumentException("披露列表缺少审核的本站栏目或外部档案来源");
        return review;
    }

    String apply(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var section = unique(document, containerSelector);
        if (section.closest("header,nav,footer,form") != null)
            throw new IllegalArgumentException("披露列表必须位于独立正文内");
        if (section.hasAttr(MARK)) return html;
        var list = unique(section, listSelector);
        var links = list.select("a[href]");
        if (!list.is("ul,ol") || list.childrenSize() == 0 || links.size() != originalLinks
                || links.stream().anyMatch(link -> !originalPdf.equals(link.attr("href"))))
            throw new IllegalArgumentException("披露列表包含审核范围以外的旧链接");
        var filters = unique(section, ".notice_top");
        var pagination = unique(section, ".page-wrap");
        if (filters.select("dl.pub_select").isEmpty()
                || pagination.select("a[href]").stream().anyMatch(anchor ->
                        !Set.of("", "#", "##", "javascript:void(0);", "avascript:void(0);")
                                .contains(anchor.attr("href"))))
            throw new IllegalArgumentException("披露列表的原筛选或分页已变化");

        var card = list.child(0).clone();
        list.empty().appendChild(card);
        card.attr("th:each", "row : ${cmsBoundDisclosures}")
                .attr("th:attr", "data-disclosure-year=${row.year},data-disclosure-type=${row.type}");
        unique(card, titleSelector).empty().attr("th:text", "${row.title}");
        unique(card, dateSelector).empty().attr("th:text", "${row.date}");
        for (var link : card.select("a[href]"))
            link.removeAttr("href").removeAttr("target").removeAttr("download")
                    .attr("th:href", "${row.pdf}").attr("th:attr", "download=${row.filename}");
        if (cover) unique(card, "img[src]").removeAttr("src")
                .attr("th:src", "${row.cover}").attr("th:alt", "${row.title}");
        var icon = card.selectFirst("a > .icon-xiazai");
        if (icon != null) icon.parent().previousElementSibling().remove();

        section.attr(MARK, Long.toString(targetMenuId));
        filters.empty();
        addSelect(filters, "年份", "year");
        addSelect(filters, "类别", "type");
        filters.appendElement("a").addClass("fnt_16").attr("href", archive).text("查看官网完整档案");
        pagination.remove();
        list.after(new Element("p").attr("data-disclosure-empty", "").attr("hidden", "").text("没有匹配的记录"));
        list.after(new Element("div").attr("data-disclosure-pages", "").attr("aria-label", "列表分页"));
        document.body().appendElement("script").attr("src", SCRIPT).attr("defer", "");
        return document.outerHtml();
    }

    String preview(String html, List<Map<String, String>> rows) {
        var document = Jsoup.parse(apply(html));
        document.outputSettings().prettyPrint(false);
        var list = unique(unique(document, containerSelector), listSelector);
        var prototype = list.child(0).clone();
        list.empty();
        for (var row : rows) {
            var card = prototype.clone();
            unique(card, titleSelector).text(row.get("title"));
            unique(card, dateSelector).text(row.get("date"));
            for (var link : card.select("[th:href]"))
                link.attr("href", row.get("pdf")).attr("download", row.get("filename"));
            if (cover) unique(card, "img").attr("src", row.get("cover")).attr("alt", row.get("title"));
            list.appendChild(card);
        }
        return withoutInstructions(document.outerHtml());
    }

    static String withoutInstructions(String html) {
        if (!html.contains(MARK)) return html;
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        for (var section : document.select("[" + MARK + "]"))
            for (var element : section.getAllElements())
                INSTRUCTIONS.forEach((attribute, allowed) -> {
                    if (allowed.contains(element.attr(attribute))) element.removeAttr(attribute);
                });
        return document.outerHtml();
    }

    static boolean supports(String html) {
        return html.contains(MARK);
    }

    static List<Map<String, String>> publishedRows(
            String html, Long siteId, OneshotEntity owner, OneShotMapper menus, InfoService info) {
        var marker = unique(Jsoup.parse(html), "[" + MARK + "]");
        return publishedRows(Long.parseLong(marker.attr(MARK)), siteId, owner, menus, info);
    }

    static List<Map<String, String>> publishedRows(
            long targetMenuId, Long siteId, OneshotEntity owner, OneShotMapper menus, InfoService info) {
        var target = menus.selectPublicBindingMenu(siteId, targetMenuId);
        if (target == null || !Objects.equals(target.getSiteProjectId(), siteId)
                || !Objects.equals(target.getParentId(), owner.getId())
                || !Objects.equals(target.getSiteRootId(), owner.getSiteRootId())
                || !Integer.valueOf(1).equals(target.getPageType())
                || Boolean.TRUE.equals(target.getHasFrontChannel()) || Boolean.TRUE.equals(target.getIsDeleted()))
            throw new IllegalArgumentException("披露记录须来自当前单页直属的后台列表");
        var rows = new ArrayList<Map<String, String>>();
        for (int page = 1; ; page++) {
            var batch = info.getEventSummaryPage(siteId, target.getId(), page, 100).list();
            for (var record : batch) {
                String date = Objects.toString(record.getSingleLine1(), "");
                String type = Objects.toString(record.getSingleLine2(), "");
                String title = Objects.toString(record.getTitle(), "");
                String pdf = resource(record.getFile1(), "files", "pdf");
                if (title.isBlank() || type.isBlank() || pdf.isBlank()
                        || !date.matches("[0-9]{4}-[0-9]{2}(?:-[0-9]{2})?")) continue;
                rows.add(Map.of("title", title, "date", date, "year", date.substring(0, 4), "type", type,
                        "pdf", pdf, "filename", title + ".pdf",
                        "cover", resource(record.getPhoto1(), "images", "png|jpe?g|webp")));
            }
            if (batch.size() < 100) break;
        }
        return rows.stream().sorted(Comparator.comparing((Map<String, String> row) -> row.get("date"))
                .reversed()).collect(Collectors.toUnmodifiableList());
    }

    static String resource(String value, String directory, String extension) {
        if (value == null) return "";
        String path = value.startsWith("assets:") ? "/assets" + value.substring("assets:".length()) : value;
        return path.matches("/assets/upload/" + directory + "/[A-Za-z0-9_-]+\\.(?:" + extension + ")") ? path : "";
    }

    private static void addSelect(Element parent, String label, String key) {
        var select = parent.appendElement("label").addClass("seleItem").text(label + " ")
                .appendElement("select").addClass("fnt_16").attr("data-disclosure-filter", key);
        select.appendElement("option").attr("value", "").text("全部");
    }

    private static Element unique(Element parent, String selector) {
        var matches = parent.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("披露列表定位不唯一：" + selector);
        return matches.first();
    }
}

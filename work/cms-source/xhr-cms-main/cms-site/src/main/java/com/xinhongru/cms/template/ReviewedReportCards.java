package com.xinhongru.cms.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import tools.jackson.databind.ObjectMapper;

/** 按同版原页和素材摘要审核重复报告卡，只允许更正 PDF、封面及该组的占位分页。 */
record ReviewedReportCards(
        String listSelector,
        String paginationSelector,
        int duplicateCount,
        String originalPdf,
        String originalImage,
        String pdf,
        String pdfDigest,
        String cover,
        String coverDigest,
        String coverDescription,
        String disclosureSource) {
    static ReviewedReportCards read(Path workspace, TemplateFieldPlanService.Plan plan) {
        var review = readRule(workspace, plan, "reviewed-report-cards", ReviewedReportCards.class);
        if (review == null) return null;
        if (review.duplicateCount < 2 || review.duplicateCount > 30
                || !publishedUrl(review.originalPdf) || !publishedUrl(review.originalImage)
                || !publishedUrl(review.disclosureSource)
                || review.coverDescription == null || review.coverDescription.isBlank())
            throw new IllegalArgumentException("报告卡更正缺少可核对的原地址或披露来源");
        Path uploads = uploadRoot(workspace, plan);
        verifyAsset(uploads, review.pdf, review.pdfDigest, "files", "pdf");
        verifyAsset(uploads, review.cover, review.coverDigest, "images", "png|jpe?g|webp");
        return review;
    }

    static Path uploadRoot(Path workspace, TemplateFieldPlanService.Plan plan) {
        return workspace.getParent().resolve(plan.code()).resolve("assets/upload");
    }

    static <T> T readRule(Path workspace, TemplateFieldPlanService.Plan plan, String directory, Class<T> type) {
        if (plan.pageType() != 0) return null;
        Path site = workspace.resolve(plan.code());
        Path file = site.resolve(directory)
                .resolve(plan.packageVersion()).resolve(plan.digest() + ".json");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768
                    || !file.toRealPath().startsWith(site.toRealPath()))
                throw new IllegalArgumentException("报告卡更正审核记录无效");
            return new ObjectMapper().readValue(Files.readString(file), type);
        } catch (IOException error) {
            throw new IllegalStateException("报告卡的审核记录无法读取", error);
        }
    }

    static boolean publishedUrl(String url) {
        return url != null && url.matches("https://[^\\s]+");
    }

    static void verifyAsset(Path uploads, String url, String expected, String directory,
            String extension) {
        String prefix = "/assets/upload/";
        if (url == null || !url.matches(prefix + directory + "/[A-Za-z0-9_-]+\\.(?:" + extension + ")")
                || expected == null || !expected.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("报告卡仅支持经审核的本站素材");
        Path file = uploads.resolve(url.substring(prefix.length()));
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(uploads.toRealPath()))
                throw new IllegalArgumentException("报告卡缺少已审核的本站素材");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!expected.equals(HexFormat.of().formatHex(digest.digest())))
                throw new IllegalArgumentException("报告卡素材与审核摘要不符");
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("报告卡审核素材无法读取", error);
        }
    }

    String apply(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var lists = document.select(listSelector);
        if (lists.size() != 1 || !lists.first().is("ul,ol")
                || lists.first().closest("header,nav,footer,form") != null)
            throw new IllegalArgumentException("报告卡列表与审核的正文范围不符");
        var list = lists.first();
        var cards = list.children();
        var pagination = document.select(paginationSelector);
        if (cards.size() == 1 && pagination.isEmpty()
                && pdf.equals(cards.first().select("a[href]").attr("href"))
                && cover.equals(cards.first().select("img[src]").attr("src"))) return html;
        if (cards.size() != duplicateCount || !cards.first().is("li")
                || cards.first().select("a[href]").size() != 1 || cards.first().select("img[src]").size() != 1
                || cards.stream().anyMatch(card -> !cards.first().outerHtml().equals(card.outerHtml())))
            throw new IllegalArgumentException("报告卡内容与审核的重复组不符");
        var link = cards.first().selectFirst("a[href]");
        var image = link.selectFirst("img[src]");
        if (image == null || !originalPdf.equals(link.attr("href")) || !originalImage.equals(image.attr("src")))
            throw new IllegalArgumentException("报告卡原素材与审核地址不符");
        if (pagination.size() != 1 || pagination.first().closest("header,nav,footer,form") != null
                || pagination.first().parent() != list.parent().parent().parent()
                || !pagination.first().select("script,form,img,iframe,video").isEmpty()
                || pagination.first().select("a[href]").stream().anyMatch(ReviewedReportCards::hasActiveRoute))
            throw new IllegalArgumentException("报告卡分页范围或链接已有变化，请重新核对");
        link.attr("href", pdf).attr("download", "");
        image.attr("src", cover).attr("alt", coverDescription);
        cards.stream().skip(1).forEach(Element::remove);
        pagination.first().remove();
        return document.outerHtml();
    }

    private static boolean hasActiveRoute(Element link) {
        return !java.util.Set.of("", "#", "##", "javascript:void(0);", "avascript:void(0);")
                .contains(link.attr("href"));
    }
}

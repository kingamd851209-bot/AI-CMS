package com.xinhongru.cms.template;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 从可见原页取证，缺失和无法等同的值交给人工审核。 */
final class ListPairingComparison {
    private static final String CHROME = "header,footer,nav,aside,script,style,[hidden]";
    private static final String HEADLINES = "[itemprop=headline],h1,h2,h3,h4";
    private static final String SUMMARIES = "[itemprop=description],.summary,.abstract,.description";
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})\\s*[./年-]\\s*(\\d{1,2})\\s*[./月-]\\s*(\\d{1,2})(?!\\d)");
    private ListPairingComparison() {}

    enum Result { MATCH, DIFFERENT, PRESENT, UNAVAILABLE }

    record Value(String value, String selector) {}

    record Fact(String name, Value list, Value detail, Result result) {}

    record Evidence(List<Fact> facts, String bodyHash, List<String> bodyParagraphs, String detailTitle) {
        boolean conflicting() {
            return facts.stream().anyMatch(fact -> fact.result() == Result.DIFFERENT);
        }

        boolean titleMatched() {
            return facts.stream().anyMatch(fact -> "标题".equals(fact.name()) && fact.result() == Result.MATCH);
        }

    }

    static Evidence compare(Element card, Document detail) {
        var headings = ListDetailRoleEvidence.detailHeadings(detail);
        var match = card.select("a[href]").stream()
                .map(anchor -> ListDetailRoleEvidence.matchingTitle(anchor, headings))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        var cardHeading = card.selectFirst(HEADLINES);
        var detailHeading = detail.select(HEADLINES).stream().filter(ListPairingComparison::visible)
                .filter(el -> match != null ? clean(el.text()).contains(match.value())
                        : !headings.isEmpty() && clean(el.text()).equals(clean(headings.getFirst().heading())))
                .findFirst().orElse(null);
        String matchedTitle = match == null ? null : match.value();
        var cardTitle = matchedTitle != null
                ? new Value(matchedTitle, cardHeading == null ? card.cssSelector() : cardHeading.cssSelector())
                : value(cardHeading);
        var detailTitle = matchedTitle != null && detailHeading != null
                ? new Value(matchedTitle, detailHeading.cssSelector()) : value(detailHeading);
        var article = detailHeading == null ? null : detailHeading.closest("article,main,[role=main]");
        if (article == null) article = detail.body();

        var bodyText = article.select("p").stream().filter(ListPairingComparison::visible)
                .filter(el -> el.closest("a") == null).filter(el -> clean(el.text()).length() >= 28)
                .limit(40).map(ListPairingComparison::value).toList();
        var paragraphs = bodyText.stream().map(Value::value).toList();
        var summary = explicitSummary(card);
        if (summary == null)
            summary = card.select("p").stream().filter(ListPairingComparison::visible)
                    .filter(el -> clean(el.text()).length() >= 28)
                    .findFirst().map(ListPairingComparison::value).orElse(null);
        var body = bodyText.isEmpty() ? null : bodyText.getFirst();
        var detailSummary = explicitSummary(article);
        return new Evidence(List.of(fact("标题", cardTitle, detailTitle, false),
                fact("日期", date(card), date(article), false),
                summaryFact(summary, detailSummary, bodyText),
                new Fact("正文", null, abbreviated(body), body == null ? Result.UNAVAILABLE : Result.PRESENT)),
                paragraphs.isEmpty() ? null : TemplateListPairingService.hash(String.join("\n", paragraphs)), paragraphs,
                headings.isEmpty() ? null : headings.getFirst().heading());
    }

    private static Value explicitSummary(Element area) {
        return area.select(SUMMARIES).stream().filter(ListPairingComparison::visible)
                .map(ListPairingComparison::value).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static Fact summaryFact(Value list, Value detail, List<Value> body) {
        if (list == null) return fact("摘要", null, detail, true);
        var excerpt = body.stream().filter(paragraph -> list.value().length() >= 28
                        && paragraph.value().contains(list.value()))
                .findFirst().orElse(null);
        if (excerpt != null) return fact("摘要", list, excerpt, true);
        if (detail != null) return fact("摘要", list, detail, true);
        return new Fact("摘要", abbreviated(list), null, Result.UNAVAILABLE);
    }

    private static Fact fact(String name, Value left, Value right, boolean excerpt) {
        if (left == null || right == null) return new Fact(name, abbreviated(left), abbreviated(right), Result.UNAVAILABLE);
        boolean same = left.value().equals(right.value())
                || excerpt && (left.value().contains(right.value()) || right.value().contains(left.value()));
        return new Fact(name, abbreviated(left), abbreviated(right), same ? Result.MATCH : Result.DIFFERENT);
    }

    private static Value date(Element area) {
        for (var el : area.select("time,[itemprop=datePublished],.date,.time,span,p,div")) {
            if (!visible(el) || el.closest("a") != null && area.tagName().equals("body")) continue;
            String raw = el.hasAttr("datetime") ? el.attr("datetime") : clean(el.ownText());
            if (raw.length() > 55) continue;
            var date = DATE.matcher(raw);
            if (!date.find()) continue;
            try {
                return new Value(LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)),
                        Integer.parseInt(date.group(3))).toString(), el.cssSelector());
            } catch (DateTimeException invalid) {
                // 无效的日历值保留待核。
            }
        }
        return null;
    }

    private static boolean visible(Element el) {
        return el.closest(CHROME) == null;
    }

    private static Value value(Element el) {
        return el == null || clean(el.text()).isEmpty() ? null : new Value(clean(el.text()), el.cssSelector());
    }

    private static Value abbreviated(Value value) {
        return value == null || value.value().length() <= 220 ? value
                : new Value(value.value().substring(0, 220) + "…", value.selector());
    }

    static String clean(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip();
    }
}

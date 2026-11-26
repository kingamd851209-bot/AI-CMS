package com.xinhongru.cms.template;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 以当前包内的标题身份区分可维护记录、同组目录与装饰控件。 */
final class MaintainableCardEvidence {
    private static final String GROUP = "ul, ol, [role=list], .swiper-wrapper,"
            + " div:has(> article), section:has(> article), div:has(> .card)";
    private static final String HEADING = "h1, h2, h3, h4, h5, h6, [itemprop=name]";
    private static final String EXCLUDED = "header, footer, nav, [role=navigation], form, template,"
            + " [hidden], [aria-hidden=true], .swiper-slide-duplicate, .swiper-pagination,"
            + " .pagination, .slick-dots, .breadcrumb, [role=tablist]";
    private static final int MAX_GROUPS = 24;
    private static final int MAX_ROWS = 24;
    private static final int MAX_LOCAL_DEPTH = 7;

    record Row(Element row, Element heading, String title, List<String> otherTitles) {}
    record Directory(Element container, List<Row> rows) {}
    record Group(Element container, List<Row> cards, Directory directory, String state) {}

    private MaintainableCardEvidence() {}

    static List<Group> read(Document document) {
        var directories = new ArrayList<Directory>();
        var candidates = new LinkedHashMap<Element, List<Row>>();
        for (var container : document.body().select(GROUP)) {
            if (container.closest(EXCLUDED) != null) continue;
            var direct = container.children().stream().filter(row -> !row.is(EXCLUDED)).toList();
            if (direct.size() < 2 || direct.size() > MAX_ROWS) continue;
            if (direct.stream().map(Element::normalName).distinct().count() != 1) continue;
            var cards = direct.stream().map(MaintainableCardEvidence::card).toList();
            if (cards.stream().allMatch(Objects::nonNull) && uniqueTitles(cards)) {
                candidates.put(container, cards);
                continue;
            }
            if (!container.is("ol, ul")) continue;
            var labels = direct.stream().map(MaintainableCardEvidence::label).toList();
            if (labels.stream().allMatch(Objects::nonNull) && uniqueTitles(labels)) {
                directories.add(new Directory(container, labels));
            }
        }
        var groups = new ArrayList<Group>();
        for (var candidate : candidates.entrySet()) {
            if (groups.size() == MAX_GROUPS) break;
            Element container = candidate.getKey();
            List<Row> cards = candidate.getValue();
            var directory = directory(container, directories, candidates);
            String state = "CARDS";
            if (directory != null) {
                var cardTitles = cards.stream().map(row -> identity(row.title())).toList();
                var labels = directory.rows().stream().map(row -> identity(row.title())).toList();
                state = cardTitles.equals(labels) ? "MATCHING"
                        : cardTitles.size() == labels.size() && cardTitles.containsAll(labels)
                                ? "ORDER_DIFFERS" : "IDENTITY_REVIEW";
            }
            groups.add(new Group(container, cards, directory, state));
        }
        return List.copyOf(groups);
    }

    private static Directory directory(Element cards, List<Directory> directories,
            Map<Element, List<Row>> candidateGroups) {
        var scope = cards.parent();
        for (int level = 0; scope != null && !scope.is("body, main") && level < MAX_LOCAL_DEPTH;
                level++, scope = scope.parent()) {
            Element local = scope;
            if (candidateGroups.keySet().stream()
                    .filter(group -> within(group, local)).limit(2).count() > 1) return null;
            var contained = directories.stream()
                    .filter(directory -> within(directory.container(), local)
                            && !within(directory.container(), cards) && !within(cards, directory.container()))
                    .toList();
            if (contained.size() == 1) return contained.getFirst();
            if (contained.size() > 1) return null;
        }
        return null;
    }

    private static boolean within(Element node, Element ancestor) {
        return node == ancestor || node.parents().contains(ancestor);
    }

    private static Row card(Element row) {
        if (row.selectFirst(".swiper-wrapper, [role=list], ul > li > h3, ol > li > h3") != null) return null;
        if (row.select("a[href]").stream().anyMatch(MaintainableCardEvidence::navigates)) return null;
        var headings = row.select(HEADING).stream().filter(node -> node.closest(EXCLUDED) == null)
                .filter(node -> usable(node.text())).toList();
        if (headings.isEmpty()) return null;
        Element head = headings.getFirst();
        boolean body = row.select("p, [itemprop=description]").stream().anyMatch(node ->
                node.closest(EXCLUDED) == null && node.text().length() >= 8
                        && !identity(head.text()).equals(identity(node.text())));
        boolean media = row.select("img[src], img[data-src], video[src], video source[src]").stream()
                .filter(node -> node.closest(EXCLUDED) == null && !node.attr("role").equals("presentation"))
                .anyMatch(node -> {
                    String source = node.hasAttr("data-src") ? node.attr("data-src") : node.attr("src");
                    return !source.isBlank() && !source.startsWith("data:") && !source.startsWith("#");
                });
        if (!body && !media) return null;
        var otherTitles = headings.stream().skip(1).filter(node -> pairedHeading(head, node)).map(Element::text)
                .filter(value -> !identity(value).equals(identity(head.text()))).distinct().limit(3).toList();
        return new Row(row, head, head.text(), otherTitles);
    }

    private static boolean pairedHeading(Element first, Element second) {
        var front = first.closest("dt");
        var back = second.closest("dd");
        return front != null && back != null && front.parent() == back.parent()
                && first.normalName().equals(second.normalName())
                && first.classNames().equals(second.classNames());
    }

    private static Row label(Element row) {
        if (!row.is("li") || row.selectFirst("a[href], button, img, video, ul, ol, " + HEADING) != null
                || row.childrenSize() > 1) return null;
        var node = row.childrenSize() == 1 ? row.child(0) : row;
        if (!node.is("li, p, span, strong") || node.childrenSize() != 0 || !usable(node.text())) return null;
        return new Row(row, node, node.text(), List.of());
    }

    private static boolean navigates(Element link) {
        String href = link.attr("href").strip();
        return !href.isBlank() && !href.startsWith("#")
                && !href.toLowerCase(Locale.ROOT).startsWith("javascript:");
    }

    private static boolean uniqueTitles(List<Row> rows) {
        return rows.stream().map(row -> identity(row.title())).distinct().count() == rows.size();
    }

    private static boolean usable(String value) {
        return value.length() >= 2 && value.length() <= 120 && value.codePoints().anyMatch(Character::isLetter);
    }

    static String identity(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\s]+", "");
    }
}

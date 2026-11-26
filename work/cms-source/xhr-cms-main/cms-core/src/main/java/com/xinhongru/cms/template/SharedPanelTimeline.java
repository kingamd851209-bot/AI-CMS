package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** 用包内片段核实多个标签共同更新一个记录列表。标签文字只作为审核证据。 */
final class SharedPanelTimeline {
    private SharedPanelTimeline() {}

    static List<QueryTimeline.Layout> layouts(Document page, SiteModelEvidenceService.Region region,
            Map<String, String> targets, Function<String, String> read) {
        if (targets.isEmpty() || region.records().size() < 2 || page.body().getAllElements().size() > 5000)
            return List.of();
        var rows = new ArrayList<Element>();
        for (var record : region.records()) {
            var matches = page.select(record.selector());
            if (matches.size() != 1) return List.of();
            rows.add(matches.first());
        }
        var panel = rows.getFirst().parent();
        if (panel == null || !panel.is("ol,ul") || !rows.equals(panel.children())) return List.of();
        var groups = panel.parent();
        if (groups == null || groups.childrenSize() != 1 || !groups.ownText().isBlank()
                || groups.parent() == null || groups.parent().is("body,html")
                || groups.closest("nav,header,footer,.header,.footer,[role=banner],[role=contentinfo]") != null)
            return List.of();
        var shape = Shape.of(rows.getFirst());
        if (shape == null || rows.stream().anyMatch(row -> !shape.equals(Shape.of(row)))) return List.of();
        var result = new ArrayList<QueryTimeline.Layout>();
        for (var nav : groups.parent().select("ul,ol")) {
            if (nav == panel || nav.parents().contains(groups) || groups.parents().contains(nav)
                    || nav.childrenSize() < 2 || nav.childrenSize() > 100 || !nav.ownText().isBlank()) continue;
            var entries = nav.children();
            var labels = new ArrayList<String>();
            var sources = new LinkedHashSet<String>();
            for (var entry : entries) {
                if (!entry.is("li[data-src]") || entry.hasAttr("id")
                        || entry.childrenSize() > 1 || entry.childrenSize() == 1
                        && (!entry.ownText().isBlank() || !entry.child(0).is("p,span,strong,em")
                        || entry.child(0).childrenSize() != 0)) break;
                String path = targets.get(entry.attr("data-src"));
                if (path == null || entry.text().isBlank() || labels.contains(entry.text())) break;
                labels.add(entry.text());
                sources.add(path);
            }
            if (labels.size() != entries.size() || sources.size() > 16) continue;
            var examples = rows.stream().map(shape::sample).collect(java.util.stream.Collectors.toSet());
            boolean matchesPage = false;
            boolean valid = true;
            for (String path : sources) {
                String html = read.apply(path);
                if (html == null) { valid = false; break; }
                var fragment = fragmentDocument(html);
                var body = fragment.body();
                if (body.childrenSize() != 1 || !body.ownText().isBlank()
                        || !fragment.select("script,style,link,base,form").isEmpty()) { valid = false; break; }
                var list = body.child(0);
                if (!list.tagName().equals(panel.tagName()) || list.childrenSize() == 0
                        || list.childrenSize() > 100 || !list.ownText().isBlank()
                        || list.children().stream().anyMatch(row -> !shape.equals(Shape.of(row)))) {
                    valid = false; break;
                }
                matchesPage |= list.children().stream().map(shape::sample).anyMatch(examples::contains);
            }
            if (valid && matchesPage) result.add(new QueryTimeline.Layout(nav.cssSelector(), "li",
                    groups.cssSelector(), panel.tagName(), null, "li", List.copyOf(labels), rows.size(),
                    shape.title, shape.image, shape.secondary));
        }
        return result.size() == 1 ? List.copyOf(result) : List.of();
    }

    record RecordComparison(int checkedFragments, Integer matchingRecords, Boolean orderOnlyChange) {}

    static RecordComparison compareRecords(
            Document page, String pagePath, QueryTimeline.Layout layout, List<String> paths,
            Map<String, StaticPageSource.Page> fragments, Set<String> packageFiles) {
        var groups = page.select(layout.groupSelector());
        if (groups.size() != 1 || groups.first().childrenSize() != 1) {
            return new RecordComparison(0, null, null);
        }
        var defaultCards = cardIdentities(groups.first().child(0), pagePath, layout, packageFiles);
        int checked = 0;
        boolean complete = defaultCards != null;
        boolean changedOrder = false;
        var baseline = defaultCards == null ? Map.<CardIdentity, Integer>of() : cardCounts(defaultCards);
        for (String path : paths) {
            var source = fragments.get(path);
            if (source == null) {
                complete = false;
                continue;
            }
            var body = fragmentDocument(source.html()).body();
            var cards = body.childrenSize() == 1
                    ? cardIdentities(body.child(0), path, layout, packageFiles)
                    : null;
            if (cards == null) {
                complete = false;
                continue;
            }
            checked++;
            complete &= baseline.equals(cardCounts(cards));
            changedOrder |= !cards.equals(defaultCards);
        }
        return complete && checked > 0
                ? new RecordComparison(checked, defaultCards.size(), changedOrder)
                : new RecordComparison(checked, null, null);
    }

    private static Document fragmentDocument(String html) {
        return Jsoup.parseBodyFragment(html.startsWith("\uFEFF") ? html.substring(1) : html);
    }

    private record CardIdentity(String title, String additionalText, String image) {}

    private static List<CardIdentity> cardIdentities(
            Element panel, String owner, QueryTimeline.Layout layout, Set<String> packageFiles) {
        var cards = new ArrayList<CardIdentity>();
        for (var card : panel.children()) {
            var title = card.select(layout.rowTextSelector());
            var image = card.select(layout.rowImageSelector());
            var additional = layout.rowSecondarySelector() == null
                    ? null : card.select(layout.rowSecondarySelector());
            if (title.size() != 1 || title.first().text().isBlank() || image.size() != 1
                    || additional != null && (additional.size() != 1 || additional.first().text().isBlank())) {
                return null;
            }
            String source = image.first().attr("src").strip();
            String normalizedPath = SiteModelEvidenceService.resolveLocal(owner, source, packageFiles);
            if (normalizedPath == null || StaticPageSource.supports(normalizedPath)) return null;
            var uri = URI.create(source);
            String imageIdentity = normalizedPath
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            cards.add(new CardIdentity(title.first().text(), additional == null ? "" : additional.first().text(),
                    imageIdentity));
        }
        return cards.isEmpty() ? null : List.copyOf(cards);
    }

    private static Map<CardIdentity, Integer> cardCounts(List<CardIdentity> cards) {
        var counts = new HashMap<CardIdentity, Integer>();
        for (var card : cards) counts.merge(card, 1, Integer::sum);
        return counts;
    }

    private record Shape(String title, String image, String secondary) {
        static Shape of(Element row) {
            if (!row.is("li") || !row.ownText().isBlank() || row.getAllElements().size() > 40) return null;
            Element heading = null, image = null, extra = null;
            for (var el : row.getAllElements()) {
                if (el.hasAttr("id") || !el.is("li,dl,dd,dt,div,figure,figcaption,p,span,strong,em,time,img,h1,h2,h3,h4,h5,h6")
                        || el.attributes().asList().stream().anyMatch(a -> a.getKey().startsWith("on"))) return null;
                if (el.childrenSize() > 0) {
                    if (!el.ownText().isBlank()) return null;
                } else if (el.is("img[src]") && !el.attr("src").isBlank() && image == null) image = el;
                else if (el.text().isBlank()) return null;
                else if (el.is("h1,h2,h3,h4,h5,h6") && heading == null) heading = el;
                else if (extra == null && el.is("span,p,time,em,strong")) extra = el;
                else return null;
            }
            if (heading == null || image == null) return null;
            var titlePath = path(row, heading);
            var imagePath = path(row, image);
            var extraPath = extra == null ? null : path(row, extra);
            if (titlePath == null || imagePath == null || extra != null && extraPath == null) return null;
            return new Shape(titlePath, imagePath, extraPath);
        }

        private List<String> sample(Element row) {
            return List.of(row.selectFirst(title).text(), row.selectFirst(image).attr("src"),
                    secondary == null ? "" : row.selectFirst(secondary).text());
        }

        private static String path(Element row, Element leaf) {
            var steps = new ArrayDeque<String>();
            for (var el = leaf; el != row; el = el.parent()) steps.addFirst(el.tagName());
            String path = String.join(" > ", steps);
            return row.select(path).size() == 1 ? path : null;
        }
    }
}

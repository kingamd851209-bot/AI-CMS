package com.xinhongru.cms.template;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 分页、重复记录及包内详情标题的交叉证据，仅用于栏目类型建议。 */
final class ListDetailRoleEvidence {
    private static final String OUTSIDE = "header,footer,nav,aside,.header,.footer,[role=navigation],"
            + "[hidden],[aria-hidden=true],form,.breadcrumb,.breadcrumbs";
    private static final Pattern SPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern LABELLED_CODE = Pattern.compile("[^:]{1,24}:\\s*((?=[\\w./-]*[A-Za-z])(?=[\\w./-]*[0-9])[A-Za-z0-9][A-Za-z0-9./_-]{2,47})");
    record Title(String heading, String value) {}
    private record Hit(String path, Title title) {}
    record Match(String listSelector, int cardCount, String detailPath, String detailHeading, int matchingCards) {}

    static Match find(String path, Map<String,Document> documents, Set<String> navigationPages) {
        var document = documents.get(path);
        if (document == null || document.selectFirst("base[href]") != null || !hasPager(document)) return null;
        Map<String,List<Title>> titles = new HashMap<>();
        for (var group : document.body().select("ul,ol,section,div,main")) {
            if (group.childrenSize() < 2 || group.childrenSize() > 100 || group.closest(OUTSIDE) != null) continue;
            var first = group.child(0);
            if (!first.is("li,article") && !(first.is("div") && !first.classNames().isEmpty())) continue;
            if (group.children().stream().anyMatch(card -> !card.normalName().equals(first.normalName())
                    || first.is("div") && java.util.Collections.disjoint(first.classNames(),card.classNames()))) continue;
            var hits = new LinkedHashMap<Hit,Integer>();
            int linkedCards = 0;
            for (var card : group.children()) {
                boolean linksToRecord = false;
                var matched = new java.util.LinkedHashSet<Hit>();
                for (var anchor : card.select("a[href]")) {
                    if (anchor.closest(OUTSIDE) != null) continue;
                    String target = SiteModelEvidenceService.resolveLocal(path,anchor.attr("href"),documents.keySet());
                    if (target == null || navigationPages.contains(target) || target.equals(path)) continue;
                    var headings = titles.computeIfAbsent(target, key -> detailHeadings(documents.get(key)));
                    if (headings.isEmpty()) continue;
                    linksToRecord = true;
                    var title = matchingTitle(anchor, headings);
                    if (title != null) matched.add(new Hit(target, title));
                }
                if (linksToRecord) linkedCards++;
                matched.forEach(target -> hits.merge(target,1,Integer::sum));
            }
            if (linkedCards == group.childrenSize() && !hits.isEmpty()) {
                // 一份原样稿足以验证列表职责。其余卡片的内容身份由配对审核单独确认。
                var exemplar = hits.entrySet().iterator().next();
                return new Match(group.cssSelector(),linkedCards,exemplar.getKey().path(),
                        exemplar.getKey().title().heading(),exemplar.getValue());
            }
        }
        return null;
    }

    private static boolean hasPager(Document doc) {
        for (var pager : doc.select(".page,.pagination,.pager,[aria-label*=分页],[aria-label*=pagination]")) {
            if (pager.closest("header,footer,aside,.header,.footer,[hidden],[aria-hidden=true]") != null) continue;
            var numbers = new HashSet<String>();
            for (var link : pager.select("a,button,[aria-current=page]")) {
                String text = link.text().strip();
                if (text.matches("[0-9]{1,6}")) numbers.add(text);
            }
            if (numbers.size() >= 2) return true;
        }
        return false;
    }

    static List<Title> detailHeadings(Document detail) {
        boolean hasBody = detail.select("p,article").stream().anyMatch(el ->
                el.closest(OUTSIDE + ",a") == null && el.text().length() >= 20);
        if (!hasBody) return List.of();
        for (String selector : new String[]{"[itemprop=headline]","h1","h2"}) {
            for (var heading : detail.select(selector)) {
                if (heading.closest(OUTSIDE + ",a,li,button") != null) continue;
                String title = heading.text().strip();
                if (title.length() < 2 || title.length() > 240) continue;
                var result = new ArrayList<Title>();
                result.add(new Title(title, comparisonText(title)));
                // 标题区的次级标题可能显示型号或编号。只接受带标签的完整字母数字编码。
                for (Element sibling : heading.siblingElements()) {
                    if (!sibling.is("h1,h2,h3,h4") || sibling.closest(OUTSIDE) != null) continue;
                    var value = LABELLED_CODE.matcher(comparisonText(sibling.text()));
                    if (value.matches()) result.add(new Title(sibling.text(), value.group(1)));
                }
                return result;
            }
        }
        return List.of();
    }

    static Title matchingTitle(Element anchor, List<Title> headings) {
        for (var title : headings) {
            if (title.value().equals(comparisonText(anchor.text())) || anchor.getAllElements().stream()
                    .anyMatch(el -> title.value().equals(comparisonText(el.ownText())))) return title;
        }
        return null;
    }

    private static String comparisonText(String text) {
        return SPACE.matcher(Normalizer.normalize(text,Normalizer.Form.NFKC)).replaceAll(" ").strip();
    }
}

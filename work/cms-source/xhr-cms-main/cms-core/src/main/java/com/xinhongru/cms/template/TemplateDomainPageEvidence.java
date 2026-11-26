package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 同包原稿取证，输出供人工审核的绑定建议。 */
@Component
public final class TemplateDomainPageEvidence {
    private static final int MAX_SUGGESTIONS = 32;
    private static final int MAX_LOCAL_EVIDENCE = 16;
    private static final int MAX_LAYOUT_REGIONS = 8;
    private static final int MAX_FRAGMENTS = 16;
    private static final int MAX_INTERACTION_CANDIDATES = 4;
    private static final int MAX_FRAGMENT_CHARS = 128 * 1024;
    private static final int MAX_LOCATOR_LENGTH = 1024;
    private static final String SHELL = "header, footer, [role=banner], [role=contentinfo]";
    private static final String NAVIGATION = "nav, [role=navigation]";

    private final UiPackageStorageService storage;
    private final SiteModelEvidenceService pages;
    private final SiteModelNavigationEvidence navigation;

    TemplateDomainPageEvidence(
            UiPackageStorageService storage, SiteModelEvidenceService pages,
            SiteModelNavigationEvidence navigation) {
        this.storage = storage;
        this.pages = pages;
        this.navigation = navigation;
    }

    public record Evidence(
            List<Suggestion> suggestions, List<String> warnings, List<String> excludedSourceIds) {
        boolean hasIndependentLinkBase() {
            return warnings.contains(INDEPENDENT_LINK_BASE);
        }
    }

    private static final String INDEPENDENT_LINK_BASE = "页面声明了独立链接基址，请人工审核。";

    public record Suggestion(
            String kind, String path, String selector, String targetPath,
            Map<String, Object> facts, String reason) {
        boolean isInteractionReview() {
            return kind.equals("NAV_EXPANSION_REVIEW") || kind.equals("BODY_FRAGMENT_REVIEW");
        }
    }

    public Evidence inspect(String code, String version, String sourcePath, String digest) {
        return inspect(code, version, sourcePath, digest, List.of(), true);
    }

    public Evidence inspect(
            String code, String version, String sourcePath, String digest,
            List<TemplateContentUnitsService.Source> sources) {
        return inspect(code, version, sourcePath, digest, sources, false);
    }

    private Evidence inspect(String code, String version, String sourcePath, String digest,
            List<TemplateContentUnitsService.Source> sources, boolean includeSiteNavigation) {
        var page = storage.readStaticPage(code, version, sourcePath);
        checkDigest(digest, page.digest());
        var local = pages.page(code, version, sourcePath);
        checkDigest(digest, local.digest());

        var result = new ArrayList<Suggestion>();
        var warnings = new ArrayList<String>();
        var excluded = new LinkedHashSet<String>();
        var document = Jsoup.parse(page.html());
        var body = document.body();
        if (body.getAllElements().size() > 5_000) {
            return new Evidence(List.of(), List.of("页面节点超过结构取证上限，请人工审核。"), List.of());
        }
        if (document.selectFirst("base[href]") != null) {
            return new Evidence(List.of(), List.of(INDEPENDENT_LINK_BASE), List.of());
        }
        if (SiteModelEvidenceService.incompleteStructureScan(local.warnings())) {
            return new Evidence(List.of(), List.of("页面证据未完整扫描，请人工审核。"), List.of());
        }
        warnings.addAll(SiteModelEvidenceService.localStructureLimits(local.warnings()));

        SiteModelNavigationEvidence.Sources snapshot = null;
        List<SiteModelNavigationEvidence.Entry> entries = null;
        try {
            snapshot = navigation.readSources(code, version);
            var current = snapshot.pages().get(sourcePath);
            checkDigest(digest, current == null ? null : current.digest());
            entries = navigation.read(snapshot);
        } catch (IllegalArgumentException unavailable) {
            warnings.add("同包页面未能完成交叉核对，相关结构请人工审核。");
        }

        if (snapshot != null && entries != null) {
            listRelations(sourcePath, document, local, snapshot, entries, result);
            neighbors(sourcePath, document, snapshot.pages(), sources, result, excluded);
        }
        maintainableCards(sourcePath, document, local, result);
        var fragmentTargets = new LinkedHashMap<String, String>();
        for (var link : local.fragmentLinks()) {
            fragmentTargets.put(link.rawValue(), link.targetPath());
        }
        var fragmentReads = new LinkedHashMap<String, StaticPageSource.Page>();
        var reader = storage.openStaticPageReader(code, version);
        Function<String, String> readFragment = path -> {
            if (!fragmentReads.containsKey(path) && fragmentReads.size() < MAX_FRAGMENTS) {
                StaticPageSource.Page fragment = null;
                try {
                    var source = reader.read(path);
                    if (source.html().length() <= MAX_FRAGMENT_CHARS) {
                        fragment = source;
                    }
                } catch (IllegalArgumentException unavailable) {
                    warnings.add("存在无法读取的分组片段，相关分组请人工审核。");
                }
                fragmentReads.put(path, fragment);
            }
            var fragment = fragmentReads.get(path);
            return fragment == null ? null : fragment.html();
        };

        var layouts = new LinkedHashMap<QueryTimeline.Layout, Boolean>();
        int layoutRegions = 0;
        for (var region : local.regions()) {
            if (!"REPEATED".equals(region.kind()) && !"BANNER".equals(region.kind())) {
                continue;
            }
            if (SiteModelEvidenceService.truncatedRegion(region)) {
                warnings.add("部分重复区域记录或字段超过取证上限，相关区域请人工审核。");
                continue;
            }
            var cards = cards(document, region);
            if (cards.isEmpty()) continue;
            if (SiteModelEvidenceService.displayOnlyList(document, region)) {
                add(result, "LIST_ONLY", sourcePath, region.selector(), null,
                        Map.of("recordCount", cards.size()), "完整重复行没有卡片入口，可审核为独立纯列表。");
            }
            carousel(sourcePath, region, cards, sources, result, excluded);
            if (!"REPEATED".equals(region.kind()) || layoutRegions++ >= MAX_LAYOUT_REGIONS) continue;
            for (var layout : QueryTimeline.layouts(document, region)) layouts.putIfAbsent(layout, false);
            for (var layout : SharedPanelTimeline.layouts(document, region, fragmentTargets, readFragment)) {
                layouts.put(layout, true);
            }
        }
        var packageFiles = snapshot == null ? Set.<String>of() : snapshot.filePaths();
        for (var layout : layouts.entrySet().stream().limit(MAX_LOCAL_EVIDENCE).toList()) {
            grouped(sourcePath, document, layout.getKey(), layout.getValue(),
                    fragmentTargets, fragmentReads, packageFiles, result);
        }
        for (var review : SiteModelEvidenceService.repeatedRegionReview(document, warnings)) {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("context", review.context());
            facts.put("layout", review.layout());
            facts.put("rowCount", review.rowCount());
            facts.put("samples", review.samples());
            facts.put("parentSelector", review.parentSelector());
            facts.put("containsNestedGroups", review.containsNestedGroups());
            String reason = "重复区仅供审核，样例相同可能为轮播占位，嵌套区域不等于同一组记录。";
            if (review.linkPatterns() != null) facts.put("sampleLinkPatterns", review.linkPatterns());
            if ("CAROUSEL_PLACEHOLDER".equals(review.layout()))
                reason = "轮播样卡文本、图片与链接位置重复，需核对是否为占位并明确选择正式记录范围及字段。";
            if (review.displayOrdinal() != null) {
                facts.put("displayOrdinal", review.displayOrdinal());
                reason = "完整卡片组的同一位置依 DOM 顺序显示两位序号。若确认本页直属列表子栏目负责维护，可用原生排序与模板序号复现。";
            }
            if (!add(result, "REPEATED_REGION", sourcePath, review.selector(), null,
                    facts, reason)) {
                warnings.add("部分重复区样例超过输出上限，请人工审核其余候选。");
                break;
            }
        }
        if (includeSiteNavigation && snapshot != null && entries != null) {
            sharedFragments(sourcePath, snapshot, result);
            sharedNavigation(sourcePath, document, entries, snapshot.pages(), result);
        }
        if (result.size() >= MAX_SUGGESTIONS || layoutRegions > MAX_LAYOUT_REGIONS) {
            warnings.add("部分结构建议超过输出或核对上限，请人工审核其余候选。");
        }
        if (snapshot != null) {
            var interactions = new ArrayList<Suggestion>();
            reviewInteractions(sourcePath, document, local, packageFiles, interactions, warnings);
            result.addAll(interactions);
        }
        checkDigest(digest, storage.readStaticPage(code, version, sourcePath).digest());
        return new Evidence(
                List.copyOf(result), warnings.stream().distinct().toList(),
                sources.isEmpty() ? List.of() : List.copyOf(excluded));
    }

    private static void maintainableCards(String path, Document document,
            SiteModelEvidenceService.PageEvidence page, List<Suggestion> result) {
        for (var group : MaintainableCardEvidence.read(document).stream().limit(MAX_LOCAL_EVIDENCE).toList()) {
            var facts = new LinkedHashMap<String, Object>();
            var region = page.regions().stream()
                    .filter(candidate -> document.selectFirst(candidate.selector()) == group.container())
                    .findFirst().orElse(null);
            if (region != null && region.records().size() == group.cards().size()
                    && !SiteModelEvidenceService.truncatedRegion(region)) facts.put("regionId", region.id());
            facts.put("layout", group.container().hasClass("swiper-wrapper") ? "CAROUSEL" : "CARDS");
            facts.put("sourceRows", group.cards().size());
            facts.put("cardTitles", group.cards().stream().map(MaintainableCardEvidence.Row::title).toList());
            facts.put("directoryIdentity", group.state());
            var conflicts = new LinkedHashMap<String, List<String>>();
            group.cards().stream().filter(row -> !row.otherTitles().isEmpty())
                    .forEach(row -> conflicts.put(row.title(), row.otherTitles()));
            if (!conflicts.isEmpty()) facts.put("titleVariants", Map.copyOf(conflicts));
            if (group.directory() != null) {
                facts.put("directorySelector", group.directory().container().cssSelector());
                var labels = group.directory().rows().stream().map(MaintainableCardEvidence.Row::title).toList();
                facts.put("labels", labels);
                var positions = new LinkedHashMap<String, Integer>();
                for (int i = 0; i < group.cards().size(); i++) {
                    positions.put(MaintainableCardEvidence.identity(group.cards().get(i).title()), i + 1);
                }
                facts.put("directoryToCard", labels.stream()
                        .map(title -> positions.getOrDefault(MaintainableCardEvidence.identity(title), 0)).toList());
            }
            String reason = switch (group.state()) {
                case "MATCHING" -> "相邻目录与同组卡片按唯一标题逐项一致，维护一份原生记录，目录与卡片分别投影。";
                case "ORDER_DIFFERS" -> "相邻目录与卡片有相同唯一标题但次序不同，先核对两处顺序，按标题定位记录。";
                case "IDENTITY_REVIEW" -> "相邻目录与卡片未逐项匹配。分别保留原文，不能以顺序或数量代替记录身份。";
                default -> "完整同组卡片有各自标题和正文或素材，未发现独立详情入口，可审核为原生列表。";
            };
            add(result, "MAINTAINABLE_CARD_LIST", path, group.container().cssSelector(), null, facts, reason);
        }
    }

    private static void reviewInteractions(
            String path,
            Document document,
            SiteModelEvidenceService.PageEvidence page,
            Set<String> files,
            List<Suggestion> result,
            List<String> warnings) {
        var scripts =
                document.select("script[src]").stream()
                        .map(script -> SiteModelEvidenceService.resolveLocal(path, script.attr("src"), files))
                        .filter(Objects::nonNull)
                        .filter(script -> script.length() <= 160)
                        .distinct()
                        .limit(MAX_INTERACTION_CANDIDATES)
                        .toList();
        int navigationCandidates = 0;
        for (Element toggle : document.select("button[aria-controls], [role=button][aria-controls]")) {
            String targetId = toggle.attr("aria-controls").strip();
            Element target = document.getElementById(targetId);
            if (target == null || target.closest(NAVIGATION) == null
                    || !inPageShell(toggle) || !inPageShell(target)) continue;
            if (navigationCandidates++ >= MAX_INTERACTION_CANDIDATES
                    || !add(result, "NAV_EXPANSION_REVIEW", path, toggle.cssSelector(), null,
                            Map.of("panelSelector", target.cssSelector(), "localScriptCandidates", scripts),
                            "控件指向导航面板。核对展开、收起及其依赖，保留正式链接身份。")) break;
        }
        for (Element item : document.select("nav li, [role=navigation] li")) {
            if (!inPageShell(item)) continue;
            Element parentLink = item.selectFirst("> a[href]");
            Element nested = item.selectFirst("> :not(a):has(a[href])");
            if (parentLink == null || nested == null) continue;
            if (navigationCandidates++ >= MAX_INTERACTION_CANDIDATES
                    || !add(result, "NAV_EXPANSION_REVIEW", path, item.cssSelector(), null,
                            Map.of("panelSelector", nested.cssSelector(),
                                    "parentLinkSelector", parentLink.cssSelector(),
                                    "localScriptCandidates", scripts),
                            "嵌套导航可能依赖悬停或独立控件；父链接和子链接仍分别审核。脚本引用仅供核对。")) break;
        }
        if (navigationCandidates > MAX_INTERACTION_CANDIDATES) {
            warnings.add("部分导航展开候选超过取证上限，请人工检查其余控件。");
        }
        int bodyCandidates = 0;
        for (var fragment : page.fragmentLinks()) {
            Element trigger = document.selectFirst(fragment.selector());
            if (trigger == null || trigger.is("iframe") || inPageShell(trigger)) continue;
            if (bodyCandidates++ >= MAX_INTERACTION_CANDIDATES) {
                warnings.add("部分正文片段候选超过取证上限，请人工核对其余入口。");
                break;
            }
            if (!add(result, "BODY_FRAGMENT_REVIEW", path, fragment.selector(), fragment.targetPath(),
                    Map.of("originalReference", fragment.rawValue()),
                    "正文有包内片段地址。触发动作、请求、写入区域及与 CMS 原生列表的重叠仍待核对。")) break;
        }
    }

    private static boolean inPageShell(Element element) {
        return element.closest(SHELL + ", " + NAVIGATION) != null
                && element.closest("main, [role=main], article") == null;
    }

    private static void listRelations(
            String path, Document document, SiteModelEvidenceService.PageEvidence local,
            SiteModelNavigationEvidence.Sources snapshot, List<SiteModelNavigationEvidence.Entry> entries,
            List<Suggestion> result) {
        var sources = snapshot.pages();
        var matches = new LinkedHashMap<String, ListDetailRoleEvidence.Match>();
        for (var entry : entries) {
            var match = entry.listDetailEvidence();
            if (match != null && (path.equals(entry.sourcePath()) || path.equals(match.detailPath()))) {
                matches.put(entry.sourcePath(), match);
            }
        }
        if (!matches.containsKey(path)) {
            var documents = new LinkedHashMap<String, Document>();
            documents.put(path, document);
            for (var link : local.links().stream().map(SiteModelEvidenceService.PageLink::targetPath)
                    .distinct().limit(MAX_LOCAL_EVIDENCE).toList()) {
                var target = sources.get(link);
                if (target != null && !path.equals(link)) {
                    documents.put(link, Jsoup.parse(target.html()));
                }
            }
            var navigationPages = entries.stream().map(SiteModelNavigationEvidence.Entry::sourcePath)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            var match = ListDetailRoleEvidence.find(path, documents, navigationPages);
            if (match != null) matches.put(path, match);
        }
        matches.forEach((listPath, match) -> {
            var list = sources.get(listPath);
            var detail = sources.get(match.detailPath());
            if (list == null || detail == null) return;
            add(result, "LIST_DETAIL", listPath, match.listSelector(), match.detailPath(),
                    Map.of("cards", match.cardCount(), "titleMatches", match.matchingCards(),
                            "detailHeading", sample(match.detailHeading()),
                            "listDigest", list.digest(), "targetDigest", detail.digest()),
                    "分页、重复卡片和包内详情标题相互印证，记录归属和配对仍需人工确认。");
        });
    }

    private static List<Element> cards(Document document, SiteModelEvidenceService.Region region) {
        var rows = new ArrayList<Element>();
        for (var record : region.records()) {
            var matches = document.select(record.selector());
            if (matches.size() != 1) return List.of();
            rows.add(matches.first());
        }
        return rows;
    }

    private static void carousel(
            String path, SiteModelEvidenceService.Region region, List<Element> cards,
            List<TemplateContentUnitsService.Source> sources, List<Suggestion> result, Set<String> excluded) {
        if (cards.size() < 2 || cards.size() > 100 || cards.getFirst().parent() == null
                || !cards.equals(cards.getFirst().parent().children())) return;
        var first = QueryCarouselCounter.inspectStructure(cards);
        if (first == null) return;
        var ids = new LinkedHashSet<String>();
        for (var card : cards) {
            var scope = QueryCarouselCounter.locate(card, first.scopePath());
            if (scope == null) return;
            for (var node : scope.getAllElements()) {
                var selector = node.cssSelector();
                if (node.ownerDocument().select(selector).size() == 1) {
                    exactTextIds(sources, node, selector, ids);
                }
            }
        }
        if (add(result, "CAROUSEL_COUNTER", path, region.selector(), null,
                Map.of("recordCount", cards.size(), "currentSelector", first.currentSelector(),
                        "totalSelector", first.totalSelector(), "currentSample", first.currentSample(),
                        "totalSample", first.totalSample(), "currentWidth", first.currentWidth(),
                        "totalWidth", first.totalWidth()), "完整轮播组符合连续序号及总数，建议绑定查询的循环状态。")) {
            excluded.addAll(ids);
        }
    }

    private static void grouped(
            String path, Document document, QueryTimeline.Layout layout, boolean sharedPanel,
            Map<String, String> targets, Map<String, StaticPageSource.Page> fragmentReads, Set<String> packageFiles,
            List<Suggestion> result) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("navigationSelector", layout.navigationSelector());
        facts.put("sourceRows", layout.sourceRows());
        facts.put("labels", layout.labels().stream().limit(8).map(TemplateDomainPageEvidence::sample).toList());
        String reason = "标签和分组记录的结构匹配，可审核分组查询与原布局。";
        if (sharedPanel) {
            var navigation = document.selectFirst(layout.navigationSelector());
            var counts = new LinkedHashMap<String, Integer>();
            for (var label : navigation.children()) {
                counts.merge(targets.get(label.attr("data-src")), 1, Integer::sum);
            }
            var paths = List.copyOf(counts.keySet());
            facts.put("fragmentPaths", paths);
            facts.put("fragmentDigests", paths.stream().map(key -> fragmentReads.get(key).digest()).toList());
            facts.put("reusedFragmentTargetCount", counts.values().stream().filter(count -> count > 1).count());
            facts.put("labelsReusingTargets", counts.values().stream().filter(count -> count > 1)
                    .mapToInt(Integer::intValue).sum());
            var comparison = SharedPanelTimeline.compareRecords(document, path, layout, paths, fragmentReads, packageFiles);
            facts.put("checkedFragmentCount", comparison.checkedFragments());
            if (comparison.matchingRecords() == null) {
                facts.put("recordComparison", "REVIEW");
                reason = "分组片段结构匹配，记录、图片或来源证据需人工核对。";
            } else {
                facts.put("recordComparison", "MATCHING");
                facts.put("matchingRecordCount", comparison.matchingRecords());
                facts.put("orderOnlyChange", comparison.orderOnlyChange());
                reason = "各片段与默认面板的记录项一致，标签与记录归属需人工核对。";
            }
        }
        add(result, "GROUPED_LIST", path, layout.groupSelector(), null, facts, reason);
        if (layout.rowSecondarySelector() != null) {
            add(result, "ADDITIONAL_FIELD", path, layout.groupSelector(), null,
                    Map.of("rowSelector", layout.rowSelector(),
                            "relativeFieldSelector", layout.rowSecondarySelector(), "fieldType", "SingleLine"),
                    "同型记录在相同相对位置有附加文本，可审核独立字段及其业务语义。");
        }
    }

    private static void neighbors(
            String path, Document page, Map<String, StaticPageSource.Page> packagePages,
            List<TemplateContentUnitsService.Source> sources, List<Suggestion> result, Set<String> excluded) {
        if (ListDetailRoleEvidence.detailHeadings(page).isEmpty()) return;
        int found = 0;
        for (var group : page.select("main,article,section,div,nav,ul,ol")) {
            if (!DetailNeighborMarkup.candidateGroup(group)) continue;
            var previous = DetailNeighborMarkup.neighborEdge(group.child(0), true, false);
            var next = DetailNeighborMarkup.neighborEdge(group.child(1), false, false);
            if (previous == null || next == null || page.select(group.cssSelector()).size() != 1) continue;
            var facts = new LinkedHashMap<String, Object>();
            if (!neighborTarget("previous", path, previous, packagePages, facts)
                    || !neighborTarget("next", path, next, packagePages, facts)) continue;
            if (add(result, "DETAIL_NEIGHBORS", path, group.cssSelector(), null, facts,
                    "前后篇的独立控件和方向明确，运行时目标须按当前栏目及已发布记录审核。")) {
                for (var edge : List.of(previous, next)) {
                    var anchor = edge.anchor();
                    String selector = anchor.cssSelector();
                    if (page.select(selector).size() == 1) {
                        exactTextIds(sources, anchor, selector, excluded);
                    }
                }
            }
            if (++found == MAX_LOCAL_EVIDENCE) return;
        }
    }

    private static void exactTextIds(
            List<TemplateContentUnitsService.Source> sources, Element node, String selector, Set<String> ids) {
        String ownText = node.ownText().strip();
        String fullText = node.wholeText().strip();
        for (var source : sources) {
            if (!"TEXT".equals(source.kind()) || !selector.equals(source.selector()) || source.rawValue() == null)
                continue;
            String value = source.rawValue().strip();
            if (value.equals(ownText) || value.equals(fullText)) ids.add(source.id());
        }
    }

    private static boolean neighborTarget(
            String direction, String path, DetailNeighborMarkup.Edge edge,
            Map<String, StaticPageSource.Page> packagePages, Map<String, Object> facts) {
        String href = edge.anchor().attr("href").strip();
        facts.put(direction + "Selector", edge.anchor().cssSelector());
        if (href.isEmpty() || href.matches("#+")) {
            facts.put(direction + "Placeholder", true);
            return true;
        }
        String target = SiteModelEvidenceService.resolveLocal(path, href, packagePages.keySet());
        if (target == null || path.equals(target)) return false;
        var source = packagePages.get(target);
        if (ListDetailRoleEvidence.detailHeadings(Jsoup.parse(source.html())).isEmpty()) {
            return false;
        }
        facts.put(direction + "Path", target);
        facts.put(direction + "Digest", source.digest());
        return true;
    }

    private static void sharedNavigation(
            String path, Document document, List<SiteModelNavigationEvidence.Entry> entries,
            Map<String, StaticPageSource.Page> sources, List<Suggestion> result) {
        var current = SiteModelNavigationEvidence.sharedOnPage(sources.keySet(), path, document);
        for (var entry : current) {
            for (var observation : entry.observations()) {
                if (result.size() >= MAX_SUGGESTIONS) return;
                if (observation.targetPath() == null || document.select(observation.selector()).size() != 1) continue;
                var corroboration = entries.stream().filter(other -> entry.name().equals(other.name()))
                        .flatMap(other -> other.observations().stream())
                        .filter(other -> !path.equals(other.path())
                                && observation.origin().equals(other.origin())
                                && observation.linkIdentity().equals(other.linkIdentity()))
                        .findFirst().orElse(null);
                if (corroboration == null) continue;
                var target = sources.get(observation.targetPath());
                var crossPage = sources.get(corroboration.path());
                if (target == null || crossPage == null) continue;
                add(result, "SHARED_NAVIGATION", path, observation.selector(), observation.targetPath(),
                        Map.of("origin", observation.origin(), "label", sample(entry.name()),
                                "targetDigest", target.digest(), "corroborationPath", corroboration.path(),
                                "corroborationDigest", crossPage.digest()),
                        "不同来源的公共导航链接一致，可审核共享入口与栏目路由。");
            }
        }
    }

    private void sharedFragments(
            String path, SiteModelNavigationEvidence.Sources snapshot, List<Suggestion> result) {
        var catalog = pages.catalog(snapshot);
        for (var fragment : catalog.sharedFragments()) {
            var occurrences = fragment.occurrences();
            var current = occurrences.stream().filter(item -> path.equals(item.sourcePath())).findFirst().orElse(null);
            if (current == null) continue;
            var otherPages = occurrences.stream().map(SiteModelEvidenceService.FragmentOccurrence::sourcePath)
                    .filter(item -> !path.equals(item)).distinct().limit(8).toList();
            add(result, "SHARED_FRAGMENT", path, current.selector(), null,
                    Map.of("fragmentKind", fragment.kind(), "fragmentDigest", fragment.digest(),
                            "otherPages", otherPages), "公共片段跨页一致，可审核复用范围，保留各页栏目归属。");
        }
    }

    private static boolean add(
            List<Suggestion> result, String kind, String path, String selector, String targetPath,
            Map<String, Object> facts, String reason) {
        if (result.size() >= MAX_SUGGESTIONS || !locator(path) || !locator(selector)
                || (targetPath != null && !locator(targetPath))
                || facts.values().stream().anyMatch(value -> value instanceof String string && !locator(string)
                    || value instanceof Collection<?> collection && collection.stream()
                            .anyMatch(item -> item instanceof String string && !locator(string)))) return false;
        var suggestion = new Suggestion(kind, path, selector, targetPath, Collections.unmodifiableMap(new TreeMap<>(facts)), reason);
        if (!result.contains(suggestion)) result.add(suggestion);
        return true;
    }

    private static boolean locator(String value) {
        return value != null && value.length() <= MAX_LOCATOR_LENGTH;
    }

    private static String sample(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static void checkDigest(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new IllegalStateException("结构取证期间页面摘要变化，请重新开始");
        }
    }
}

package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 从固定版本的原包识别导航和有交叉证据的页内分类入口。 */
@Component
final class SiteModelNavigationEvidence {
    private static final int MAX_CACHED_VERSIONS = 8;
    private static final Pattern SEARCH_LINK = Pattern.compile(
            "^javascript:\\s*(?:window\\.)?location\\.href\\s*=\\s*(['\"])([^'\"]+)\\1\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private final UiPackageStorageService storage;
    private final LinkedHashMap<PackageVersion, CachedEvidence> cache =
            new LinkedHashMap<>(MAX_CACHED_VERSIONS, 0.75f, true);

    private record PackageVersion(String code, String version) {}

    private record CachedEvidence(Map<String, String> digests, List<Entry> entries) {}

    SiteModelNavigationEvidence(UiPackageStorageService storage) {
        this.storage = storage;
    }

    record Entry(
            String key, String parentKey, String name, int order, String sourcePath,
            String evidencePath, String evidenceSelector, String sourceHref,
            List<Observation> observations, boolean independentLanding,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ListDetailRoleEvidence.Match listDetailEvidence) {
        Entry(String key, String parentKey, String name, int order, String sourcePath,
                String evidencePath, String evidenceSelector, String sourceHref,
                List<Observation> observations, boolean independentLanding) {
            this(key, parentKey, name, order, sourcePath, evidencePath, evidenceSelector, sourceHref,
                    observations, independentLanding, null);
        }
        Entry(String key, String parentKey, String name, int order, String sourcePath,
                String evidencePath, String evidenceSelector, String sourceHref) {
            this(key, parentKey, name, order, sourcePath, evidencePath, evidenceSelector,
                    sourceHref, List.of(), false);
        }

        boolean keepsOriginalLink(List<Entry> supplemental) {
            if (observations.isEmpty()
                    || supplemental.stream().anyMatch(entry -> key.equals(entry.parentKey())))
                return false;
            var first = observations.getFirst();
            return ("EXTERNAL".equals(first.linkKind()) || "ANCHOR".equals(first.linkKind()))
                    && observations.stream()
                            .allMatch(
                                    observation ->
                                            first.linkKind().equals(observation.linkKind())
                                                    && first.href()
                                                            .strip()
                                                            .equals(observation.href().strip()));
        }

        Entry withObservation(Observation item) {
            if (observations.contains(item)
                    || observations.stream()
                                    .filter(
                                            old ->
                                                    old.origin().equals(item.origin())
                                                            && old.linkKind()
                                                                    .equals(item.linkKind())
                                                            && Objects.equals(
                                                                    old.targetPath(),
                                                                    item.targetPath())
                                                            && Objects.equals(
                                                                    old.linkIdentity(),
                                                                    item.linkIdentity()))
                                    .count()
                            >= 2) return this;
            var merged = new ArrayList<>(observations);
            merged.add(item);
            return new Entry(key, parentKey, name, order, sourcePath, evidencePath, evidenceSelector,
                    sourceHref, List.copyOf(merged), independentLanding, listDetailEvidence);
        }
    }

    record Observation(String origin, String path, String selector, String href,
            String targetPath, String linkKind) {
        String linkIdentity() {
            if (targetPath == null) return href;
            int suffix = href.length();
            int query = href.indexOf('?');
            int fragment = href.indexOf('#');
            if (query >= 0) suffix = query;
            if (fragment >= 0) suffix = Math.min(suffix, fragment);
            return targetPath + href.substring(suffix);
        }
    }

    private record Category(String name, String target, Element anchor) {}

    /** 单次导航请求的已验证原包输入，目录复验与区域取证使用同一次读取。 */
    record Sources(String code, String version, List<UiPackageStorageService.InspectionFile> files,
            Set<String> filePaths, Map<String, StaticPageSource.Page> pages) {}

    Sources readSources(String code, String version) {
        var files = storage.inspectionFiles(code, version);
        var filePaths = files.stream()
                .map(UiPackageStorageService.InspectionFile::path)
                .collect(Collectors.toUnmodifiableSet());
        var orderedPaths = filePaths.stream().filter(StaticPageSource::supports).sorted().toList();
        if (orderedPaths.size() > SiteModelEvidenceService.MAX_PAGES)
            throw new IllegalArgumentException("页面数量超过整站取证上限，无法核对完整导航");
        Map<String, StaticPageSource.Page> sources = new LinkedHashMap<>();
        long readChars = 0;
        if (orderedPaths.isEmpty()) return new Sources(code, version, files, filePaths, Map.of());
        var reader = storage.openStaticPageReader(code, version);
        for (String path : orderedPaths) {
            var source = reader.read(path);
            readChars += source.html().length();
            if (readChars > SiteModelEvidenceService.MAX_SITE_CHARS)
                throw new IllegalArgumentException("页面总文本超过整站取证上限，无法核对完整导航");
            sources.put(path, source);
        }
        return new Sources(code, version, files, filePaths, java.util.Collections.unmodifiableMap(sources));
    }

    List<Entry> read(String code, String version) {
        return read(readSources(code, version));
    }

    List<Entry> read(Sources sources) {
        var pages = sources.pages();
        Map<String, String> digests = new LinkedHashMap<>();
        pages.forEach((path, source) -> digests.put(path, source.digest()));
        var scope = new PackageVersion(sources.code(), sources.version());
        synchronized (cache) {
            var saved = cache.get(scope);
            if (saved != null && saved.digests().equals(digests)) return saved.entries();
        }
        Map<String, Document> documents = new HashMap<>();
        pages.forEach((path, source) -> documents.put(path, Jsoup.parse(source.html())));
        var entries = extract(pages.keySet(), List.copyOf(pages.keySet()), documents);
        synchronized (cache) {
            cache.put(scope, new CachedEvidence(Map.copyOf(digests), entries));
            if (cache.size() > MAX_CACHED_VERSIONS) cache.pollFirstEntry();
        }
        return entries;
    }

    static List<Entry> sharedOnPage(Set<String> pages, String path, Document document) {
        var entries = new ArrayList<Entry>();
        readHeader(entries, pages, path, document, false);
        readFooter(entries, pages, path, document);
        return List.copyOf(entries);
    }

    private static List<Entry> extract(
            Set<String> pages, List<String> orderedPaths, Map<String, Document> documents) {
        Function<String, Document> read = documents::get;
        var entries = new ArrayList<Entry>();
        for (String path :
                List.of("inc/header.aspx", "inc/header.html", "index.html", "index.aspx")) {
            if (!pages.contains(path)) continue;
            readHeader(entries, pages, path, read.apply(path), true);
            if (!entries.isEmpty()) break;
        }
        checkEntries(entries);
        var countedSources = entries.stream().filter(entry -> entry.sourcePath() != null)
                .collect(Collectors.groupingBy(Entry::sourcePath, Collectors.counting()));
        for (Entry parent : List.copyOf(entries)) {
            String path = parent.sourcePath();
            if (path == null || countedSources.get(path) != 1
                    || entries.stream().anyMatch(child -> parent.key().equals(child.parentKey())))
                continue;
            Document doc = read.apply(path);
            addTabs(entries, pages, parent, doc);
            if (entries.stream().noneMatch(child -> parent.key().equals(child.parentKey())))
                addCategories(entries, pages, parent, doc, read);
            checkEntries(entries);
        }
        for (String path : orderedPaths) {
            var secondary = new ArrayList<Entry>();
            readHeader(secondary, pages, path, read.apply(path), false);
            mergeHeader(entries, secondary, null, null);
            checkEntries(entries);
        }
        for (String path : orderedPaths) {
            readFooter(entries, pages, path, read.apply(path));
            checkEntries(entries);
        }
        addAuxiliary(entries, pages, read);
        for (String path : orderedPaths) addSearch(entries, pages, path, read);
        checkEntries(entries);
        var navigationPages = entries.stream().map(Entry::sourcePath).filter(Objects::nonNull).collect(Collectors.toSet());
        var parents = entries.stream().map(Entry::parentKey).filter(Objects::nonNull).collect(Collectors.toSet());
        var listRoles = new HashMap<String, java.util.Optional<ListDetailRoleEvidence.Match>>();
        return entries.stream().map(entry -> {
            if (entry.sourcePath() == null || parents.contains(entry.key()))
                return classifyLanding(entry, entries, read);
            if (entry.parentKey() == null && entry.sourcePath().matches("(?i)index\\.(?:html?|aspx|shtml)")) return entry;
            var evidence = listRoles.computeIfAbsent(entry.sourcePath(), path -> java.util.Optional.ofNullable(
                    ListDetailRoleEvidence.find(path, documents, navigationPages)));
            if (evidence.isEmpty()) return entry;
            return new Entry(entry.key(), entry.parentKey(), entry.name(), entry.order(), entry.sourcePath(),
                    entry.evidencePath(), entry.evidenceSelector(), entry.sourceHref(), entry.observations(),
                    entry.independentLanding(), evidence.get());
        }).toList();
    }

    private static void mergeHeader(List<Entry> entries, List<Entry> secondary, String oldParent, String parent) {
        for (Entry candidate : secondary) {
            if (!Objects.equals(candidate.parentKey(), oldParent)) continue;
            Entry existing = sameName(entries, parent, candidate.name());
            if (existing == null) {
                existing = newEntry(entries, parent, candidate.name(), candidate.observations().getFirst());
            } else {
                attach(entries, existing, candidate.observations().getFirst());
            }
            mergeHeader(entries, secondary, candidate.key(), existing.key());
        }
    }

    private static void readFooter(List<Entry> entries, Set<String> pages, String path, Document doc) {
        var roots = doc.select("footer, .footer");
        if (roots.isEmpty() && path.startsWith("inc/footer.")) roots.add(doc.body());
        for (Element footer : roots) {
            var grouped = new HashSet<Element>();
            for (Element group :
                    footer.select(".t > .item:has(> .title a[href]), ul:has(> li > a[href])")) {
                Element title = group.selectFirst("> .title a[href], > li:first-child > a[href]");
                if (title == null) continue;
                Entry parent = sameName(entries, null, title.text());
                if (parent == null) continue;
                var links = group.select("a[href]");
                // Flat lists of top-level links are not category groups.
                if (links.stream().skip(1).anyMatch(link -> sameName(entries, null, link.text()) != null))
                    continue;
                for (Element link : links) {
                    mergeFooter(entries, pages, path, link == title ? null : parent.key(), link);
                    grouped.add(link);
                }
            }
            for (Element link : footer.select("a[href]")) {
                if (!grouped.contains(link)) mergeFooter(entries, pages, path, null, link);
            }
        }
    }

    private static void mergeFooter(List<Entry> entries, Set<String> pages, String path,
            String parent, Element link) {
        String name = link.text();
        if (!validLabel(name) || link.attr("href").length() > 512) return;
        Observation evidence = observation("FOOTER", pages, path, link, link.attr("href"));
        Entry current = sameName(entries, parent, name);
        if (current == null && parent == null && evidence.targetPath() != null) {
            var aliases = entries.stream().filter(item -> item.name().equals(name)
                    && Objects.equals(item.sourcePath(), evidence.targetPath())).toList();
            if (aliases.size() == 1) current = aliases.getFirst();
        }
        if (current != null) attach(entries, current, evidence);
        else if (evidence.targetPath() != null && !evidence.targetPath().startsWith("inc/")
                && !evidence.linkKind().equals("ANCHOR"))
            newEntry(entries, parent, name, evidence);
    }

    private static void addSearch(List<Entry> entries, Set<String> pages, String path,
            Function<String, Document> read) {
        for (Element link :
                read.apply(path).select("header a[href], .header a[href], .query a[href]")) {
            var href = link.attr("href");
            var literal = SEARCH_LINK.matcher(href);
            if (href.length() > 512 || !literal.matches()) continue;
            String target = SiteModelEvidenceService.resolveLocal(path, literal.group(2), pages);
            if (target == null
                    || target.equals(path)
                    || read.apply(target).select("title, h1, h2").stream()
                            .noneMatch(item -> item.text().contains("搜索"))) continue;
            String origin =
                    link.closest("header, .header") != null ? "HEADER_SEARCH" : "BODY_SEARCH";
            var evidence =
                    new Observation(origin, path, link.cssSelector(), href, target, "LOCAL_PAGE");
            Entry existing = entries.stream().filter(entry -> target.equals(entry.sourcePath())).findFirst().orElse(null);
            if (existing != null) attach(entries, existing, evidence);
            else if (sameName(entries, null, "搜索") == null) newEntry(entries, null, "搜索", evidence);
        }
    }

    private static void addAuxiliary(List<Entry> entries, Set<String> pages, Function<String, Document> read) {
        for (String path : pages.stream().sorted().toList()) {
            if (path.startsWith("inc/")) continue;
            for (Element link : read.apply(path).select("a[href], button[formaction]")) {
                if (outsideBodyContent(link)) continue;
                String label = link.text();
                if (!label.matches(".*(?:申请|留言|询价|咨询|报名|联络|反馈|投递).*")) continue;
                String href = link.hasAttr("href") ? link.attr("href") : link.attr("formaction");
                Observation evidence = observation("BODY_ACTION", pages, path, link, href);
                String target = evidence.targetPath();
                if (target == null || target.equals(path) || evidence.linkKind().equals("ANCHOR"))
                    continue;
                Document page = read.apply(target);
                if (page.select("input:not([type=hidden]), textarea, select").stream()
                        .filter(control -> !outsideBodyContent(control)).count() < 2) continue;
                var matches = entries.stream().filter(entry -> target.equals(entry.sourcePath())).toList();
                if (matches.size() == 1) {
                    attach(entries, matches.getFirst(), evidence);
                } else if (matches.isEmpty()) {
                    Element heading = page.select("main h1, main h2, h1, h2").stream()
                            .filter(item -> !outsideBodyContent(item) && validLabel(item.text()))
                            .findFirst().orElse(null);
                    String name = heading == null ? label : heading.text();
                    if (validLabel(name) && sameName(entries, null, name) == null)
                        newEntry(entries, null, name, evidence);
                }
            }
        }
    }

    private static Entry classifyLanding(Entry entry, List<Entry> entries, Function<String, Document> read) {
        String path = entry.sourcePath();
        if (path == null) return entry;
        var children = entries.stream().filter(child -> entry.key().equals(child.parentKey())).toList();
        if (children.isEmpty() || children.stream().anyMatch(child -> path.equals(child.sourcePath())))
            return entry;
        boolean independent = read.apply(path).select("h1, h2, h3, h4").stream()
                .filter(item -> !outsideBodyContent(item))
                .filter(item -> !item.text().isBlank() && !item.text().equals(entry.name())
                        && !item.text().matches(".*(?:建设中|更新中|敬请期待|暂未开放|暂无内容|即将上线).*")
                        && children.stream().noneMatch(child -> item.text().equals(child.name())))
                .anyMatch(SiteModelNavigationEvidence::hasLandingText);
        return independent ? new Entry(entry.key(), entry.parentKey(), entry.name(), entry.order(), path,
                entry.evidencePath(), entry.evidenceSelector(), entry.sourceHref(), entry.observations(), true) : entry;
    }

    private static boolean hasLandingText(Element heading) {
        Element container = heading.parent();
        if (container == null || container.is("body, html")) return false;
        Element body = container.clone();
        body.select("h1, h2, h3, h4, a, button, nav, header, footer, script, style").remove();
        String text = body.text();
        return text.codePointCount(0, text.length()) >= 24;
    }

    private static void readHeader(List<Entry> entries, Set<String> pages, String path, Document doc,
            boolean legacyPrimary) {
        var primary = doc.select(".header .r.rline > .item");
        if (!primary.isEmpty()) {
            var home = doc.selectFirst(".header .t > .l > a[href]");
            if (home != null) add(entries, pages, path, null, home, "首页", home.attr("href"));
            for (var item : primary) {
                var anchor = item.selectFirst("> a[href]");
                if (anchor == null) continue;
                Entry parent = addAnchor(entries, pages, path, null, anchor);
                if (parent == null) continue;
                for (var child : item.select(".sub-list .sub-item > a[href]"))
                    addAnchor(entries, pages, path, parent.key(), child);
            }
            return;
        }
        var nav = doc.select("header nav, .header nav, nav[aria-label]").stream()
                .filter(element -> legacyPrimary || element.closest("footer, .footer") == null)
                .filter(element -> element.selectFirst("> ul > li > a[href], > a[href]") != null)
                .findFirst().orElse(null);
        if (nav == null) return;
        var items = nav.select("> ul > li");
        if (nav.select("> ul > li > a[href], > a[href]").stream()
                .noneMatch(link -> "首页".equals(link.text()))) {
            var home = doc.selectFirst("header h1 > a[href], .header h1 > a[href]");
            if (home != null) add(entries, pages, path, null, home, "首页", home.attr("href"));
        }
        for (var item : items) readList(entries, pages, path, null, item, 0);
        for (var anchor : nav.select("> a[href]")) {
            var existing = sameName(entries, null, anchor.text());
            if (existing == null) addAnchor(entries, pages, path, null, anchor);
            else
                attach(
                        entries,
                        existing,
                        observation("HEADER", pages, path, anchor, anchor.attr("href")));
        }
    }

    private static void readList(
            List<Entry> entries, Set<String> pages, String path, String parent, Element item, int depth) {
        var anchor = item.selectFirst("> a[href]");
        if (anchor == null || depth >= 5) return;
        Entry added = addAnchor(entries, pages, path, parent, anchor);
        if (added == null) return;
        for (var child : item.select("> ul > li"))
            readList(entries, pages, path, added.key(), child, depth + 1);
        for (var definition : item.select("> .navlist > dl > dd")) {
            var label = definition.selectFirst("> h2 > a[href]");
            if (label == null) {
                for (var link : definition.select("> a[href]"))
                    addAnchor(entries, pages, path, added.key(), link);
                continue;
            }
            Entry group = addAnchor(entries, pages, path, added.key(), label);
            if (group == null) continue;
            for (var link : definition.select("> p > a[href]"))
                addAnchor(entries, pages, path, group.key(), link);
        }
        for (var definition : item.select("> .nav_list .nav_p > dl")) {
            var label = definition.selectFirst("> dd > a[href]");
            if (label == null) continue;
            Entry group = addAnchor(entries, pages, path, added.key(), label);
            if (group == null) continue;
            for (var link : definition.select("> dt > p > a[href]"))
                addAnchor(entries, pages, path, group.key(), link);
        }
    }

    private static void addTabs(List<Entry> entries, Set<String> pages, Entry parent, Document doc) {
        String path = parent.sourcePath();
        for (Element tabs : doc.select("[role=tablist], .tab")) {
            if (outsideBodyContent(tabs)) continue;
            var labels = tabs.children().stream()
                    .filter(label -> label.is("[role=tab], span, button")).toList();
            if (labels.size() < 2 || labels.size() > 12 || tabs.parent() == null) continue;
            boolean semantic = tabs.is("[role=tablist]");
            var panels = tabs.parent().children().stream()
                    .filter(panel -> panel.is(semantic ? "[role=tabpanel]" : ".tab_item")).toList();
            if (panels.size() != labels.size()) continue;
            var links = new ArrayList<Element>();
            for (int i = 0; i < labels.size(); i++) {
                Element label = labels.get(i);
                Element panel = panels.get(i);
                if (semantic
                        && (panel.id().isEmpty()
                                || !panel.id().equals(label.attr("aria-controls")))) break;
                var targets =
                        panel.select("a[href]").stream()
                                .filter(
                                        link ->
                                                SiteModelEvidenceService.resolveLocal(
                                                                path, link.attr("href"), pages)
                                                        != null)
                                .filter(
                                        link ->
                                                "查看更多".equals(link.text())
                                                        || "查看全部".equals(link.text())
                                                        || link.is("a[rel=collection]"))
                                .toList();
                if (label.text().isBlank() || targets.size() != 1) break;
                links.add(targets.getFirst());
            }
            if (links.size() != labels.size()) continue;
            for (int i = 0; i < labels.size(); i++)
                add(entries, pages, path, parent.key(), labels.get(i), labels.get(i).text(),
                        links.get(i).attr("href"));
        }
    }

    private static void addCategories(
            List<Entry> entries, Set<String> pages, Entry parent, Document doc,
            Function<String, Document> read) {
        String path = parent.sourcePath();
        for (var group : doc.select("main div:has(> a[href]), body > div div:has(> a[href])")) {
            if (outsideBodyContent(group)) continue;
            List<Category> categories = categories(group, path, pages);
            if (categories.isEmpty()
                    || categories.stream().anyMatch(category -> path.equals(category.target()))
                    || categories.stream().anyMatch(category ->
                            category.anchor().selectFirst(".title, h2, h3, h4") == null))
                continue;
            var siblings = categories.stream().collect(Collectors.toMap(Category::name, Category::target));
            boolean verified =
                    categories.stream()
                            .allMatch(
                                    category ->
                                            read
                                                    .apply(category.target())
                                                    .select("[role=tablist], .title, .tabs")
                                                    .stream()
                                                    .anyMatch(
                                                            tabs -> {
                                                                if (outsideBodyContent(tabs))
                                                                    return false;
                                                                var links =
                                                                        categories(
                                                                                tabs,
                                                                                category.target(),
                                                                                pages);
                                                                return links.size()
                                                                                == siblings.size()
                                                                        && links.stream()
                                                                                .allMatch(
                                                                                        link ->
                                                                                                Objects
                                                                                                        .equals(
                                                                                                                siblings
                                                                                                                        .get(
                                                                                                                                link
                                                                                                                                        .name()),
                                                                                                                link
                                                                                                                        .target()))
                                                                        && links.stream()
                                                                                .anyMatch(
                                                                                        link ->
                                                                                                link.name()
                                                                                                                .equals(
                                                                                                                        category
                                                                                                                                .name())
                                                                                                        && link.anchor()
                                                                                                                .is(
                                                                                                                        ".on, .active,"
                                                                                                                            + " [aria-current=page],"
                                                                                                                            + " [aria-selected=true]"));
                                                            }));
            if (!verified) continue;
            for (var category : categories)
                add(entries, pages, path, parent.key(), category.anchor(), category.name(),
                        category.anchor().attr("href"));
            return;
        }
    }

    private static List<Category> categories(Element group, String path, Set<String> pages) {
        var children = group.children();
        if (children.size() < 2 || children.size() > 12
                || !children.stream().allMatch(child -> child.is("a[href]"))) return List.of();
        var result = new ArrayList<Category>();
        var names = new HashSet<String>();
        var targets = new HashSet<String>();
        for (var anchor : children) {
            var title = anchor.selectFirst(".title, h2, h3, h4");
            String label = (title == null ? anchor : title).text();
            String target = SiteModelEvidenceService.resolveLocal(path, anchor.attr("href"), pages);
            if (label.isBlank() || target == null || !names.add(label) || !targets.add(target))
                return List.of();
            result.add(new Category(label, target, anchor));
        }
        return result;
    }

    private static boolean outsideBodyContent(Element element) {
        return element.closest("header, footer, nav, .header, .footer") != null;
    }

    private static void checkEntries(List<Entry> entries) {
        var keys = new HashSet<String>();
        if (entries.size() > 150 || entries.stream().anyMatch(entry ->
                !keys.add(entry.parentKey() + "\n" + entry.name())))
            throw new IllegalArgumentException("导航数量超限或同级入口重复，请检查源页面");
    }

    private static Entry addAnchor(List<Entry> entries, Set<String> pages, String path,
            String parent, Element anchor) {
        return add(entries, pages, path, parent, anchor, anchor.text(), anchor.attr("href"));
    }

    private static Entry add(
            List<Entry> entries, Set<String> pages, String path, String parent, Element element,
            String name, String href) {
        if (!validLabel(name) || href.length() > 512
                || href.startsWith("javascript:")
                || href.startsWith("mailto:") || href.startsWith("tel:")) return null;
        return newEntry(entries, parent, name, observation(
                path.startsWith("inc/header.") || element.closest("header, .header, nav") != null
                        ? "HEADER" : "PAGE_CATEGORY", pages, path, element, href));
    }

    private static boolean validLabel(String name) {
        return !name.isBlank() && name.codePointCount(0, name.length()) <= 64;
    }

    private static Observation observation(String origin, Set<String> pages, String path, Element element, String href) {
        String target = SiteModelEvidenceService.resolveLocal(path, href, pages);
        String kind;
        if (href.isBlank() || href.matches("#+")) kind = "PLACEHOLDER";
        else if (href.startsWith("#") || target != null && href.contains("#")) kind = "ANCHOR";
        else if (target != null) kind = "LOCAL_PAGE";
        else if (href.matches("(?i)^(?:https?:)?//.*")) kind = "EXTERNAL";
        else kind = "UNRESOLVED";
        return new Observation(origin, path, element.cssSelector(), href, target, kind);
    }

    private static Entry sameName(List<Entry> entries, String parent, String name) {
        return entries.stream().filter(item -> Objects.equals(item.parentKey(), parent) && name.equals(item.name()))
                .findFirst().orElse(null);
    }

    private static void attach(List<Entry> entries, Entry item, Observation evidence) {
        int index = entries.indexOf(item);
        if (index >= 0) entries.set(index, item.withObservation(evidence));
    }

    private static Entry newEntry(List<Entry> entries, String parent, String name, Observation evidence) {
        String key = UUID.nameUUIDFromBytes(
                (parent + "\n" + name + "\n" + evidence.href()).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        int order = (int) entries.stream()
                .filter(item -> Objects.equals(item.parentKey(), parent)).count();
        Entry entry = new Entry(key, parent, name, order, evidence.targetPath(), evidence.path(),
                evidence.selector(), evidence.href(), List.of(evidence), false);
        entries.add(entry);
        return entry;
    }
}

package com.xinhongru.cms.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.apache.commons.collections4.map.LRUMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 从受控静态源收集站级建模证据。 */
@Service
public class SiteModelEvidenceService {
    static final int MAX_PAGES = 500;
    static final int MAX_SITE_CHARS = 24 * 1024 * 1024;
    private static final int MAX_DOM_ELEMENTS = 5000;
    private static final int MAX_DOM_DEPTH = 32;
    private static final int MAX_HEADINGS = 24;
    private static final int MAX_LINKS = 160;
    private static final int MAX_REGIONS = 24;
    private static final int MAX_RECORDS = 100;
    private static final int MAX_VALUES = 32;
    private static final int MAX_VALUE_CHARS = 2048;
    private static final int MAX_PAGE_VALUES = 64;
    private static final int MAX_PAGE_VALUE_CHARS = 24 * 1024;
    private static final int MAX_SHARED_VALUES = 128;
    private static final int MAX_REPEATED_VALUES = 600;
    private static final int MAX_REPEATED_CHARS = 120 * 1024;
    private static final String DOM_DEPTH_WARNING = "存在超过扫描深度的节点，待核对。";
    private static final String HEADING_LIMIT_WARNING = "业务标题超过扫描上限，后续标题待核对。";
    private static final String REGION_LIMIT_WARNING = "重复区域超过扫描上限，后续区域待核对。";
    private static final String PAIRED_LIMIT_WARNING = "成对文本区域超出取证上限，整组条目待核对。";
    private static final String MEDIA_LIMIT_WARNING = "同构图文区域超出取证上限，整组条目待核对。";
    private static final Set<String> LOCAL_LIMIT_WARNINGS = Set.of(
            HEADING_LIMIT_WARNING, REGION_LIMIT_WARNING, PAIRED_LIMIT_WARNING, MEDIA_LIMIT_WARNING);
    private static final String SHARED = "header, nav, footer, .header, .nav, .footer";
    private static final String HEADINGS =
            "h1, h2, h3, .site_banner .title, .page_banner .title, .banner .text";
    private static final String REGION_PARENTS = ".swiper-wrapper, ul, ol";
    private static final String REPEAT_CONTROLS =
            "aside, [role=navigation], [role=tablist], .pagination, .pager, .breadcrumb,"
                    + " .breadcrumbs";
    private static final String HIDDEN = "[hidden], [aria-hidden=true]";
    private static final String PAIRED_TEXT = "h1, h2, h3, h4, h5, h6, p, span";
    private static final Pattern DISPLAY_ORDER = Pattern.compile("[a-zA-Z]?[0-9]+[.、]?");
    private static final Pattern LABELLED_TEXT =
            Pattern.compile("([^:：\\r\\n。！？!?；;]{1,40})[:：]([^:：\\r\\n]+)");
    private static final Pattern YEAR = Pattern.compile("[12][0-9]{3}");
    private static final Pattern ATTACHMENT =
            Pattern.compile("(?i)\\.(?:pdf|docx?|xlsx?|pptx?|zip)(?:[?#].*)?$");
    private static final List<String> FRAGMENT_ATTRIBUTES =
            List.of("data-src", "data-url", "data-load", "data-ajax");
    private static final Pattern BACKGROUND_DECLARATION =
            Pattern.compile("(?i)(?:^|;)\\s*background(?:-image)?\\s*:([^;]*)");
    private static final Pattern BACKGROUND_URL =
            Pattern.compile("(?i)\\burl\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)]*))\\s*\\)");

    static List<String> inlineBackgroundSources(String style) {
        var values = new ArrayList<String>();
        var declarations = BACKGROUND_DECLARATION.matcher(style);
        while (declarations.find()) {
            var matcher = BACKGROUND_URL.matcher(declarations.group(1));
            while (matcher.find())
                values.add(matcher.group(1) != null ? matcher.group(1)
                        : matcher.group(2) != null ? matcher.group(2) : matcher.group(3).strip());
        }
        return values;
    }

    private final UiPackageStorageService storage;
    private final TemplateSeedService seeds;
    // 仅保存静态分析产物。源页与文件清单仍由当前请求通过受控读取器取得。
    private final Map<EvidenceKey, CatalogPage> catalogPages =
            Collections.synchronizedMap(new LRUMap<>(MAX_PAGES));
    private final Map<EvidenceKey, CachedPage> details =
            Collections.synchronizedMap(new LRUMap<>(32));

    public SiteModelEvidenceService(UiPackageStorageService storage) {
        this.storage = storage;
        seeds = new TemplateSeedService(storage);
    }

    public record SiteCatalog(
            String packageCode,
            String packageVersion,
            List<PageSummary> pages,
            List<SharedFragment> sharedFragments,
            List<String> warnings,
            List<UiPackageStorageService.InspectionFile> files) {}

    public record PageSummary(
            String sourcePath,
            String digest,
            String title,
            List<Value> headings,
            List<PageLink> links,
            boolean analyzed,
            List<String> warnings,
            List<String> includedPaths,
            List<PageLink> fragmentLinks) {}

    public record PageEvidence(
            String sourcePath,
            String digest,
            String title,
            List<Value> headings,
            List<PageLink> links,
            List<Region> regions,
            List<String> warnings,
            List<String> includedPaths,
            List<PageLink> fragmentLinks) {}

    public record Region(
            String id,
            String kind,
            String selector,
            List<SourceRecord> records,
            List<String> warnings) {}

    public record SourceRecord(
            String id, int order, String selector, List<Value> values, List<String> warnings) {}

    public record Value(
            String id,
            String selector,
            String attribute,
            String rawValue,
            String typeHint,
            @JsonInclude(JsonInclude.Include.NON_NULL) String targetPath,
            @JsonInclude(JsonInclude.Include.NON_NULL) TextRange valueRangeHint) {
        public Value(
                String id, String selector, String attribute, String rawValue, String typeHint) {
            this(id, selector, attribute, rawValue, typeHint, null);
        }

        public Value(
                String id,
                String selector,
                String attribute,
                String rawValue,
                String typeHint,
                String targetPath) {
            this(id, selector, attribute, rawValue, typeHint, targetPath, null);
        }
    }

    public record TextRange(int start, int end) {}

    public record PageLink(String selector, String rawValue, String targetPath) {}

    public record SharedPage(
            String sourcePath, String digest, List<String> includedPaths,
            List<SharedValue> values, List<String> warnings) {}

    public record SharedValue(
            String id, String region, String selector, String attribute, String rawValue,
            String typeHint, int start, int end, String targetPath) {}

    static boolean publicRegion(Region region) {
        return region.kind().equals("PUBLIC_HEADER") || region.kind().equals("PUBLIC_FOOTER");
    }

    public static boolean publicRegion(String sourcePath, String regionId) {
        return sharedId(sourcePath, "HEADER", "region").equals(regionId)
                || sharedId(sourcePath, "FOOTER", "region").equals(regionId);
    }

    List<SiteModelNavigationEvidence.Entry> navigation(String code, String version) {
        checkVersion(code, version);
        var result = new SiteModelNavigationEvidence(storage).read(code, version);
        checkVersion(code, version);
        return result;
    }

    public record SharedFragment(
            String kind, String digest, List<FragmentOccurrence> occurrences) {}

    public record FragmentOccurrence(String sourcePath, String selector) {}

    private record FragmentKey(String kind, String digest) {}

    private record EvidenceKey(
            String code, String version, String path, String pathSetDigest, String sourceDigest,
            List<String> includedPaths, List<String> warnings) {
        EvidenceKey(String code, String version, String path, String pathSetDigest, StaticPageSource.Page source) {
            this(code, version, path, pathSetDigest, source.digest(), source.includedPaths(), source.warnings());
        }
    }

    private record CatalogPage(PageSummary summary, Map<FragmentKey, List<FragmentOccurrence>> fragments) {}
    private record CachedPage(PageEvidence evidence, Map<String, String> resources) {}

    private static String pathSetDigest(Set<String> paths) {
        return digest(String.join("\0", paths.stream().sorted().toList()));
    }

    private record RepeatedEvidence(List<Region> regions, Set<String> legacySelectors) {}

    record RepeatedRegionReview(
            String selector, String context, String layout, int rowCount, List<String> samples,
            String parentSelector, boolean containsNestedGroups, DisplayOrdinal displayOrdinal,
            Integer linkPatterns) {}

    record DisplayOrdinal(String firstSelector, String firstSample, String lastSample) {}

    /** Collects review context without changing the set of regions eligible for binding. */
    static List<RepeatedRegionReview> repeatedRegionReview(Document document, List<String> warnings) {
        var reviews = new ArrayList<RepeatedRegionReview>();
        for (var group : document.body().select(REGION_PARENTS)) {
            if (group.closest(SHARED + ", " + REPEAT_CONTROLS + ", .nav-wrap, form, " + HIDDEN)
                    != null) continue;
            boolean carousel = group.hasClass("swiper-wrapper");
            var rows = group.children().stream()
                    .filter(row -> !row.is(HIDDEN + ", .swiper-slide-duplicate"))
                    .toList();
            if (rows.size() < 2 || rows.stream().anyMatch(
                    row -> carousel ? !row.hasClass("swiper-slide") : !row.is("li"))) continue;
            if (rows.stream().allMatch(SiteModelEvidenceService::navigationRow)) continue;
            var samples = new LinkedHashSet<String>();
            for (var row : rows) {
                String sample = reviewRow(row);
                if (!sample.isBlank()) samples.add(sample);
            }
            if (samples.isEmpty()) continue;
            if (reviews.size() == MAX_REGIONS) {
                warnings.add("部分重复区样例超过扫描上限，请人工审核其余候选。");
                break;
            }
            var enclosingGroup = group.parent().closest(REGION_PARENTS);
            boolean containsNested = rows.stream()
                    .anyMatch(row -> row.selectFirst(REGION_PARENTS) != null);
            Integer linkPatterns = carousel ? (int) rows.stream().limit(MAX_RECORDS)
                    .map(row -> row.select("a[href]").stream().filter(link -> link.closest(HIDDEN) == null)
                            .map(link -> link.attr("href").strip()).toList())
                    .distinct().count() : null;
            String layout = carousel ? "CAROUSEL" : "ROWS";
            if (enclosingGroup != null || containsNested) layout = "NESTED_GROUP";
            if (carousel && samples.size() == 1 && linkPatterns == 1 && rows.size() <= MAX_RECORDS)
                layout = "CAROUSEL_PLACEHOLDER";
            var ordinal = enclosingGroup == null && !containsNested && samples.size() > 1
                            && rows.size() <= MAX_RECORDS && rows.equals(group.children())
                    ? displayOrdinal(rows) : null;
            reviews.add(new RepeatedRegionReview(
                    selector(group), repeatContext(group), layout, rows.size(),
                    samples.stream().limit(2).map(value -> excerpt(value, 95)).toList(),
                    enclosingGroup == null ? "" : selector(enclosingGroup), containsNested, ordinal, linkPatterns));
        }
        return List.copyOf(reviews);
    }

    private static DisplayOrdinal displayOrdinal(List<Element> rows) {
        var first = rows.getFirst();
        DisplayOrdinal ordinal = null;
        for (var candidate : first.select("dl > dt:first-child")) {
            if (!"01".equals(candidate.wholeText().strip()) || candidate.childrenSize() != 0) continue;
            var path = new ArrayList<Integer>();
            for (var node = candidate; node != first; node = node.parent()) {
                path.add(node.elementSiblingIndex());
            }
            Element position = null;
            for (int i = 0; i < rows.size(); i++) {
                position = QueryCarouselCounter.locate(rows.get(i), path.reversed());
                if (position == null || !position.is("dl > dt:first-child") || position.childrenSize() != 0
                        || position.closest(HIDDEN + ", a, button, [role=button]") != null
                        || QueryCarouselCounter.businessNumber(position)
                        || QueryCarouselCounter.businessNumber(position.parent())
                        || !candidate.classNames().equals(position.classNames())
                        || position.nextElementSibling() == null || !position.nextElementSibling().is("dd")
                        || position.nextElementSibling().selectFirst("h1, h2, h3, h4, h5, h6") == null
                        || !String.format(Locale.ROOT, "%02d", i + 1).equals(position.wholeText().strip())) {
                    position = null;
                    break;
                }
            }
            if (position == null) continue;
            if (ordinal != null) return null;
            ordinal = new DisplayOrdinal(selector(candidate), candidate.wholeText().strip(), position.wholeText().strip());
        }
        return ordinal;
    }

    private static boolean navigationRow(Element row) {
        var anchor = row.selectFirst("a[href]");
        return anchor != null && row.selectFirst("img, video, picture, ul, ol") == null
                && row.text().equals(anchor.text());
    }

    private static String reviewRow(Element row) {
        var values = new ArrayList<String>();
        for (var item : row.getAllElements()) {
            if (item.closest(HIDDEN + ", script, style") != null) continue;
            String text = item.ownText();
            if (!text.isBlank() && !DISPLAY_ORDER.matcher(text).matches()) values.add(text);
            if (item.is("img")) {
                String source = item.hasAttr("data-src") ? item.attr("data-src") : item.attr("src");
                if (!source.isBlank() && !source.startsWith("data:")) values.add(source);
            }
        }
        return String.join(" · ", values);
    }

    private static String repeatContext(Element group) {
        for (var scope = group; scope != null && !scope.is("body"); scope = scope.parent()) {
            for (var previous = scope.previousElementSibling(); previous != null;
                    previous = previous.previousElementSibling()) {
                if (previous.is(REGION_PARENTS) || previous.selectFirst(REGION_PARENTS) != null) break;
                var heading = previous.is("h1, h2, h3, h4, h5, h6, dt")
                        ? previous : previous.selectFirst("h1, h2, h3, h4, h5, h6");
                if (heading != null && !heading.text().isBlank()) return excerpt(heading.text(), 75);
            }
        }
        return "";
    }

    public SiteCatalog catalog(String code, String version) {
        return catalog(code, version, null);
    }

    SiteCatalog catalog(String code, String version, Set<String> retainedPages) {
        return catalog(code, version, retainedPages, null);
    }

    SiteCatalog catalog(SiteModelNavigationEvidence.Sources sources) {
        return catalog(sources.code(), sources.version(), null, sources);
    }

    private SiteCatalog catalog(String code, String version, Set<String> retainedPages,
            SiteModelNavigationEvidence.Sources sources) {
        checkVersion(code, version);
        var files = sources == null ? storage.inspectionFiles(code, version) : sources.files();
        if (retainedPages != null)
            files =
                    files.stream()
                            .filter(
                                    file ->
                                            !StaticPageSource.supports(file.path())
                                                    || retainedPages.contains(file.path()))
                            .toList();
        var paths = sources == null ? filePaths(files) : sources.filePaths();
        String pathDigest = pathSetDigest(paths);
        var summaries = new ArrayList<PageSummary>();
        var fragments = new LinkedHashMap<FragmentKey, List<FragmentOccurrence>>();
        var warnings = new ArrayList<String>();
        warnings.add("站级清单汇总标题、静态链接和公共片段。区域记录需按页取证，异步执行与业务身份仍待核对。");
        if (files.size() >= 10000) warnings.add("包内文件清单已达上限，可能尚有未列出的文件。");
        int readChars = 0;
        int attemptedPages = 0;
        for (var file : files) {
            String path = file.path();
            if (!StaticPageSource.supports(path)) continue;
            if (attemptedPages >= MAX_PAGES || readChars >= MAX_SITE_CHARS) {
                summaries.add(pending(path, "已达到整站扫描上限，请单独取证。"));
                continue;
            }
            attemptedPages++;
            StaticPageSource.Page source;
            try {
                source = sources == null ? storage.readStaticPage(code, version, path) : sources.pages().get(path);
            } catch (IllegalArgumentException error) {
                summaries.add(pending(path, error.getMessage()));
                continue;
            }
            readChars += source.html().length();
            if (readChars > MAX_SITE_CHARS) {
                summaries.add(pending(path, "已达到整站扫描上限，请单独取证。"));
                continue;
            }
            var key = new EvidenceKey(code, version, path, pathDigest, source);
            var row = catalogPage(key, source, paths);
            row.fragments().forEach((fragment, occurrences) ->
                    fragments.computeIfAbsent(fragment, ignored -> new ArrayList<>()).addAll(occurrences));
            summaries.add(row.summary());
        }
        if (summaries.isEmpty()) warnings.add("包内没有可盘点的静态页面。");
        if (summaries.stream().anyMatch(page -> !page.analyzed()))
            warnings.add("部分页面仍待取证，请核对各页提示。");
        var shared =
                fragments.entrySet().stream()
                        .filter(
                                e ->
                                        e.getValue().stream()
                                                        .map(FragmentOccurrence::sourcePath)
                                                        .distinct()
                                                        .count()
                                                > 1)
                        .map(
                                e ->
                                        new SharedFragment(
                                                e.getKey().kind(),
                                                e.getKey().digest(),
                                                List.copyOf(e.getValue())))
                        .toList();
        checkVersion(code, version);
        return new SiteCatalog(
                code, version, List.copyOf(summaries), shared, List.copyOf(warnings), files);
    }

    private CatalogPage catalogPage(EvidenceKey key, StaticPageSource.Page source, Set<String> paths) {
        var cached = catalogPages.get(key);
        if (cached != null) return cached;
        var scanned = scan(key.path(), source, paths);
        var fragments = new LinkedHashMap<FragmentKey, List<FragmentOccurrence>>();
        collectFragments(scanned, paths, fragments);
        fragments.replaceAll((fragment, occurrences) -> List.copyOf(occurrences));
        // 公共片段的首次出现顺序参与整站摘要。
        var result = new CatalogPage(scanned.summary(), Collections.unmodifiableMap(fragments));
        catalogPages.put(key, result);
        return result;
    }

    public PageEvidence page(String code, String version, String path) {
        var paths = checkedPagePaths(code, version, path);
        return page(code, version, path, paths, storage.readStaticPage(code, version, path));
    }

    PageEvidence page(SiteModelNavigationEvidence.Sources sources, String path) {
        var source = sources.pages().get(path);
        if (source == null) throw new IllegalArgumentException("页面不在当前包清单内");
        return page(sources.code(), sources.version(), path, sources.filePaths(), source);
    }

    private PageEvidence page(String code, String version, String path, Set<String> paths, StaticPageSource.Page source) {
        var key = new EvidenceKey(code, version, path, pathSetDigest(paths), source);
        var cached = details.get(key);
        if (cached != null && resourcesUnchanged(code, version, cached.resources())) {
            checkVersion(code, version);
            return cached.evidence();
        }
        var resources = new HashMap<String, String>();
        var evidence = inspectPage(code, version, path, paths, source, resources).evidence();
        if (!resources.containsValue(null)) details.put(key, new CachedPage(evidence, Map.copyOf(resources)));
        return evidence;
    }

    private boolean resourcesUnchanged(String code, String version, Map<String, String> resources) {
        try {
            for (var resource : resources.entrySet())
                if (!resource.getValue().equals(digest(storage.readInspectionResource(code, version, resource.getKey()))))
                    return false;
            return true;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private record InspectedPage(PageEvidence evidence, Document document, boolean incomplete) {}

    private InspectedPage inspectPage(String code, String version, String path) {
        var paths = checkedPagePaths(code, version, path);
        return inspectPage(code, version, path, paths, storage.readStaticPage(code, version, path), null);
    }

    private Set<String> checkedPagePaths(String code, String version, String path) {
        checkVersion(code, version);
        if (!StaticPageSource.supports(path)) throw new IllegalArgumentException("请选择静态页面");
        var paths = filePaths(storage.inspectionFiles(code, version));
        if (!paths.contains(path)) throw new IllegalArgumentException("页面不在当前包清单内");
        return paths;
    }

    private InspectedPage inspectPage(String code, String version, String path, Set<String> paths,
            StaticPageSource.Page source, Map<String, String> resources) {
        var page = scan(path, source, paths);
        var preview = seeds.preview(path, source, resource -> {
            // 缺失或无法读取的脚本也会影响候选字段，本次结果不可跨请求复用。
            if (resources != null) resources.put(resource, null);
            byte[] bytes = storage.readInspectionResource(code, version, resource);
            if (resources != null) resources.put(resource, digest(bytes));
            return bytes;
        });
        var nodes = pageNodes(page);
        var editorRoots = new HashSet<Element>();
        for (var item : preview.items()) {
            if (!item.kind().equals("HTML_REGION")) continue;
            var matches = nodes.getOrDefault(item.selector(), List.of());
            if (matches.size() == 1) editorRoots.add(matches.getFirst());
        }
        var repeated = regions(page, editorRoots, paths);
        var regions = new ArrayList<>(repeated.regions());
        var pageSource = pageSource(page, preview, repeated.legacySelectors(), nodes);
        if (pageSource != null) regions.add(pageSource);
        regions.addAll(sharedRegions(sharedValues(page, paths)));
        checkVersion(code, version);
        return new InspectedPage(
                new PageEvidence(
                        path,
                        page.source.digest(),
                        page.title,
                        List.copyOf(page.headings),
                        List.copyOf(page.links),
                        List.copyOf(regions),
                        List.copyOf(page.warnings),
                        page.source.includedPaths(),
                        List.copyOf(page.fragmentLinks)),
                page.document,
                page.hasBase || incompleteStructureScan(page.warnings));
    }

    static boolean incompleteStructureScan(List<String> warnings) {
        return warnings.stream().anyMatch(warning -> DOM_DEPTH_WARNING.equals(warning)
                || warning.contains("上限") && !LOCAL_LIMIT_WARNINGS.contains(warning));
    }

    static List<String> localStructureLimits(List<String> warnings) {
        return warnings.stream().filter(LOCAL_LIMIT_WARNINGS::contains).toList();
    }

    static boolean truncatedRegion(Region region) {
        return region.warnings().stream().anyMatch(warning -> warning.contains("上限"))
                || region.records().stream().flatMap(record -> record.warnings().stream())
                        .anyMatch(warning -> warning.contains("上限"));
    }

    boolean confirmsDisplayOnlyList(
            String code, String version, SiteModelPlanService.RegionUse use) {
        InspectedPage page;
        try {
            page = inspectPage(code, version, use.sourcePath());
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
        if (page.incomplete()) return false;
        var region =
                page.evidence().regions().stream()
                        .filter(
                                item ->
                                        item.id().equals(use.regionId())
                                                && "REPEATED".equals(item.kind()))
                        .findFirst()
                        .orElse(null);
        if (region == null || region.records().size() != use.records().size()
                || !displayOnlyList(page.document(), region)) return false;
        for (var record : region.records()) {
            var bindings =
                    use.records().stream()
                            .filter(item -> record.id().equals(item.recordId()))
                            .map(SiteModelPlanService.RecordUse::bindings)
                            .toList();
            if (bindings.size() != 1 || bindings.getFirst().isEmpty()) return false;
            var ids =
                    record.values().stream()
                            .map(Value::id)
                            .collect(java.util.stream.Collectors.toSet());
            if (!ids.containsAll(bindings.getFirst().values())) return false;
        }
        return true;
    }

    static boolean displayOnlyList(Document document, Region region) {
        if (!"REPEATED".equals(region.kind())
                || region.records().isEmpty()
                || region.warnings().stream()
                        .anyMatch(warning -> warning.contains("上限") || warning.contains("分组导航控件")))
            return false;
        var containers = document.select(region.selector());
        if (containers.size() != 1) return false;
        var container = containers.first();
        if (container.childrenSize() != region.records().size()) return false;
        if (container.getAllElements().stream().anyMatch(SiteModelEvidenceService::canOpenCard))
            return false;
        for (Element outer = container.parent(); outer != null; outer = outer.parent()) {
            if (canOpenCard(outer)) return false;
        }
        return region.records().stream().allMatch(record -> record.warnings().isEmpty());
    }

    private static boolean canOpenCard(Element element) {
        return element.is(
                        "a, area, button, input, select, textarea, form, summary, script, iframe,"
                            + " object, embed, [href], [tabindex], [role=link], [role=button]")
                || element.attributes().asList().stream()
                        .map(attr -> attr.getKey().toLowerCase(Locale.ROOT))
                        .anyMatch(
                                name ->
                                        name.startsWith("on")
                                                || name.startsWith("data-")
                                                        && !name.equals("data-ratio"));
    }

    public SharedPage sharedValues(String code, String version, String path) {
        checkVersion(code, version);
        var paths = filePaths(storage.inspectionFiles(code, version));
        if (!StaticPageSource.supports(path) || !paths.contains(path))
            throw new IllegalArgumentException("公共证据页面不在当前包清单内");
        var result = sharedValues(scan(path, storage.readStaticPage(code, version, path), paths), paths);
        checkVersion(code, version);
        return result;
    }

    private static SharedPage sharedValues(ScannedPage page, Set<String> paths) {
        var values = new ArrayList<SharedValue>();
        var warnings = new ArrayList<String>();
        page.warnings.stream()
                .filter(item -> item.contains("扫描") || item.contains("深度") || item.contains("base"))
                .forEach(warnings::add);
        var selectors = new LinkedHashMap<String, List<Element>>();
        for (var element : page.elements) {
            if (TemplateSeedService.publicRegionOwner(element) == null) continue;
            if (element.closest("script,style,noscript,template,svg,button,[role=button],form")
                    != null) continue;
            var anchor = element.closest("a[href]");
            if (anchor != null && (TemplateSeedService.actionAddress(anchor.attr("href"))
                    || anchor.attr("href").strip().startsWith("#"))) continue;
            boolean navigation = anchor != null && (TemplateSeedService.publicNavigationAnchor(anchor)
                    || !isPublicInformationLink(anchor.attr("href"), anchor.text()));
            // 标识图片仍是公共媒体，即使图片外层锚点指向首页。
            if (navigation && !element.is("img")) continue;
            if (element.textNodes().stream().noneMatch(node -> !node.getWholeText().isBlank())
                    && !element.is("img,a[href]")) continue;
            selectors.computeIfAbsent(element.cssSelector(), ignored -> new ArrayList<>()).add(element);
        }
        for (var entry : selectors.entrySet()) {
            if (entry.getValue().size() != 1
                    || entry.getValue().getFirst().ownerDocument().select(entry.getKey()).size() != 1) {
                warn(warnings, "部分公共候选位置不唯一，需核对原页面。");
                continue;
            }
            var element = entry.getValue().getFirst();
            var owner = TemplateSeedService.publicRegionOwner(element);
            String region = owner.is("header,.header,[role=banner]") ? "HEADER" : "FOOTER";
            String selector = entry.getKey();
            for (int i = 0; i < element.textNodes().size(); i++) {
                String raw = element.textNodes().get(i).getWholeText();
                if (element.textNodes().stream()
                                .filter(node -> node.getWholeText().equals(raw))
                                .count()
                        == 1)
                    addSharedValue(
                            page, values, warnings, region, selector, "text", raw, "TEXT", i, null);
                else if (!raw.isBlank()) warn(warnings, "同一公共元素含重复文字节点，需人工核对。");
            }
            if (element.is("img")) {
                String attribute = element.hasAttr("src") ? "src" : "data-src";
                String raw = element.attr(attribute);
                String target = page.hasBase ? null : resolveLocal(page.path, raw, paths);
                addSharedValue(
                        page, values, warnings, region, selector, attribute, raw, "IMAGE", 0,
                        target);
            } else if (element.is("a[href]")) {
                String raw = element.attr("href");
                String target = page.hasBase ? null : resolveLocal(page.path, raw, paths);
                addSharedValue(
                        page, values, warnings, region, selector, "href", raw, "LINK", 0, target);
            }
        }
        return new SharedPage(page.path, page.source.digest(), page.source.includedPaths(),
                List.copyOf(values), List.copyOf(warnings));
    }

    private static boolean isPublicInformationLink(String href, String label) {
        String raw = href.strip();
        return raw.regionMatches(true, 0, "mailto:", 0, 7)
                || raw.regionMatches(true, 0, "tel:", 0, 4)
                || label.matches("(?iu).*(?:ICP\\s*备|公安\\s*备案|公网安备|版权所有|©).*");
    }

    private static void addSharedValue(
            ScannedPage page, List<SharedValue> values, List<String> warnings, String region,
            String selector, String attribute, String raw, String type, int nodeIndex, String target) {
        if (raw.isBlank()) return;
        if (values.size() >= MAX_SHARED_VALUES || raw.length() > MAX_VALUE_CHARS) {
            warn(warnings, "部分公共证据超过读取范围，请核对原文。");
            return;
        }
        String id =
                digest(page.path + "\npublic\n" + selector + "\n" + attribute + "\n" + nodeIndex);
        values.add(new SharedValue(id, region, selector, attribute, raw, type, 0, raw.length(), target));
    }

    private static List<Region> sharedRegions(SharedPage shared) {
        var result = new ArrayList<Region>();
        for (String area : List.of("HEADER", "FOOTER")) {
            var values = shared.values().stream().filter(item -> area.equals(item.region()))
                    .map(item -> new Value(item.id(), item.selector(), item.attribute(), item.rawValue(), item.typeHint()))
                    .toList();
            if (values.isEmpty()) continue;
            result.add(
                    new Region(
                            sharedId(shared.sourcePath(), area, "region"),
                            "PUBLIC_" + area,
                            "",
                            List.of(
                                    new SourceRecord(
                                            sharedId(shared.sourcePath(), area, "record"),
                                            1,
                                            "",
                                            values,
                                            List.of())),
                            shared.warnings()));
        }
        return List.copyOf(result);
    }

    static String sharedId(String path, String area, String purpose) {
        return digest(path + "\npublic\n" + area + "\n" + purpose);
    }

    private void checkVersion(String code, String version) {
        var active = storage.getActivePath(code);
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("请选择有效的 UI 包版本");
        Path expected = storage.root().resolve(code).resolve(version);
        try {
            if (!Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)
                    || (active.isPresent()
                            && !active.get().toRealPath().equals(expected.toRealPath())))
                throw new IllegalArgumentException("UI 包版本不可用或已切换，请刷新后重试");
        } catch (IOException error) {
            throw new IllegalArgumentException("无法核对 UI 包版本，请刷新后重试", error);
        }
    }

    private static Set<String> filePaths(List<UiPackageStorageService.InspectionFile> files) {
        return files.stream()
                .map(UiPackageStorageService.InspectionFile::path)
                .collect(Collectors.toSet());
    }

    private static PageSummary pending(String path, String message) {
        return new PageSummary(
                path,
                null,
                "",
                List.of(),
                List.of(),
                false,
                List.of(message),
                List.of(),
                List.of());
    }

    private static final class ScannedPage {
        private final String path;
        private final StaticPageSource.Page source;
        private final Document document;
        private final List<Element> elements = new ArrayList<>();
        private final List<Value> headings = new ArrayList<>();
        private final List<PageLink> links = new ArrayList<>();
        private final List<PageLink> fragmentLinks = new ArrayList<>();
        private final List<String> warnings;
        private final String title;
        private final boolean hasBase;

        private ScannedPage(String path, StaticPageSource.Page source, Document document) {
            this.path = path;
            this.source = source;
            this.document = document;
            title = excerpt(document.title(), MAX_VALUE_CHARS);
            hasBase = document.selectFirst("base[href]") != null;
            warnings = new ArrayList<>(source.warnings());
        }

        private PageSummary summary() {
            return new PageSummary(
                    path,
                    source.digest(),
                    title,
                    List.copyOf(headings),
                    List.copyOf(links),
                    true,
                    List.copyOf(warnings),
                    source.includedPaths(),
                    List.copyOf(fragmentLinks));
        }
    }

    private static ScannedPage scan(String path, StaticPageSource.Page source, Set<String> paths) {
        var document = Jsoup.parse(source.html());
        var page = new ScannedPage(path, source, document);
        if (page.hasBase) page.warnings.add("页面设有 base 地址，包内链接和异步候选需人工核对。");
        int scanned = 0;
        for (var element : document.body().getAllElements()) {
            if (scanned++ == MAX_DOM_ELEMENTS) {
                page.warnings.add("DOM 超过扫描上限，后续节点待核对。");
                break;
            }
            if (!supportedDepth(element)) {
                warn(page.warnings, DOM_DEPTH_WARNING);
                continue;
            }
            page.elements.add(element);
            if (element.is(HEADINGS) && element.closest(SHARED) == null) {
                if (page.headings.size() < MAX_HEADINGS)
                    addValue(
                            page.path,
                            element,
                            "text",
                            element.wholeText().strip(),
                            "TEXT",
                            page.headings,
                            page.warnings);
                else warn(page.warnings, HEADING_LIMIT_WARNING);
            }
            if (page.hasBase) continue;
            if (element.is("a[href], area[href]"))
                addLink(page, paths, element, "href", page.links);
            for (String attribute : FRAGMENT_ATTRIBUTES) {
                if (element.hasAttr(attribute))
                    addLink(page, paths, element, attribute, page.fragmentLinks);
            }
            if (element.is("iframe[src]")) addLink(page, paths, element, "src", page.fragmentLinks);
        }
        return page;
    }

    private static void addLink(
            ScannedPage page,
            Set<String> paths,
            Element element,
            String attribute,
            List<PageLink> links) {
        String raw = element.attr(attribute);
        String target = resolveLocal(page.path, raw, paths);
        if (!StaticPageSource.supports(target)) return;
        if (links.size() == MAX_LINKS) {
            warn(page.warnings, "包内页面引用超过扫描上限，后续链接待核对。");
            return;
        }
        links.add(new PageLink(selector(element), raw, target));
    }

    static String resolveLocal(String owner, String raw, Set<String> paths) {
        String value = raw.strip();
        if (value.isEmpty() || value.startsWith("//") || value.length() > MAX_VALUE_CHARS)
            return null;
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute()
                    || uri.getAuthority() != null
                    || uri.getPath().isEmpty()
                    || uri.getPath().contains("\\")) return null;
            String resolved =
                    URI.create("/" + owner.replace("%", "%25").replace(" ", "%20"))
                            .resolve(uri)
                            .normalize()
                            .getPath()
                            .substring(1);
            return paths.contains(resolved) ? resolved : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static void collectFragments(
            ScannedPage page,
            Set<String> paths,
            Map<FragmentKey, List<FragmentOccurrence>> fragments) {
        int remaining = 16;
        for (var element : page.elements) {
            if (!element.is(SHARED) || element.parent().closest(SHARED) != null) continue;
            if (remaining-- == 0) {
                warn(page.warnings, "公共区候选超过扫描上限，后续片段待核对。");
                return;
            }
            var clone = element.clone();
            if (!page.hasBase) {
                for (var reference : clone.select("[href], [src]")) {
                    for (String attribute : List.of("href", "src")) {
                        if (!reference.hasAttr(attribute)) continue;
                        String raw = reference.attr(attribute);
                        String target = resolveLocal(page.path, raw, paths);
                        if (target != null) {
                            int suffix = raw.strip().split("[?#]", 2)[0].length();
                            reference.attr(attribute, "/" + target + raw.strip().substring(suffix));
                        }
                    }
                }
            }
            String kind =
                    element.is("header, .header")
                            ? "HEADER"
                            : element.is("footer, .footer") ? "FOOTER" : "NAV";
            var key = new FragmentKey(kind, digest(clone.outerHtml()));
            fragments
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new FragmentOccurrence(page.path, selector(element)));
        }
    }

    private static RepeatedEvidence regions(
            ScannedPage page, Set<Element> editorRoots, Set<String> paths) {
        Set<Element> scannedElements = new HashSet<>(page.elements);
        var candidates = new LinkedHashMap<Element, List<Element>>();
        for (var parent : page.elements) {
            if (!parent.is(REGION_PARENTS) || parent.closest(SHARED) != null) continue;
            boolean slider = parent.hasClass("swiper-wrapper");
            var items =
                    parent.children().stream()
                            .filter(
                                    child ->
                                            slider
                                                    ? child.hasClass("swiper-slide")
                                                    : child.is("li")
                                                            && child.selectFirst("a[href]") != null
                                                            && child.selectFirst(
                                                                            "img, h1, h2, h3, h4,"
                                                                                    + " time")
                                                                    != null)
                            .toList();
            if (items.size() >= 2) candidates.put(parent, items);
        }
        var notes = new LinkedHashMap<Element, String>();
        var candidateAncestors = new HashSet<Element>();
        candidates.keySet().forEach(parent -> addAncestors(candidateAncestors, parent));
        editorRoots.forEach(parent -> addAncestors(candidateAncestors, parent));
        for (var parent : page.elements) {
            if (!parent.is("div, ul, ol")
                    || parent.closest(SHARED + ", " + REPEAT_CONTROLS) != null) continue;
            // Keep the original record sets and budget priority of legacy evidence.
            if (candidateAncestors.contains(parent)) continue;
            boolean nested = false;
            for (var ancestor = parent; ancestor != null; ancestor = ancestor.parent()) {
                if (candidates.containsKey(ancestor) || editorRoots.contains(ancestor)) {
                    nested = true;
                    break;
                }
            }
            if (nested) continue;
            var items = parent.children();
            if (items.size() < 2
                    || items.stream().limit(3).anyMatch(e -> !scannedElements.contains(e)))
                continue;
            String year = groupYear(parent, scannedElements);
            if (year == null) {
                if (!uniformRows(items, parent.is("div") ? "div" : "li", parent.is("div") ? 3 : 2)
                        || !items.stream()
                                .filter(scannedElements::contains)
                                .allMatch(item -> datedOrAttachmentRow(item, scannedElements)))
                    continue;
            }
            candidates.put(parent, items);
            addAncestors(candidateAncestors, parent);
            notes.put(
                    parent,
                    year == null
                            ? "按相邻记录的标题、日期或附件识别，记录边界待核对。"
                            : "分组年份 " + year + " 来自列表外标题，记录边界及分组关系待核对。");
            if (candidates.size() > MAX_REGIONS) break;
        }
        // Preserve legacy record budgets and page-source IDs while adding a second evidence view.
        var plainLists = new HashSet<Element>();
        for (var parent : page.elements) {
            if (candidates.size() > MAX_REGIONS) break;
            if (!parent.is("ul, ol")
                    || parent.closest(
                                    SHARED
                                            + ", "
                                            + REPEAT_CONTROLS
                                            + ", form, [hidden], [aria-hidden=true]")
                            != null
                    || candidates.containsKey(parent)
                    || parent.parents().stream().anyMatch(candidates::containsKey)
                    || !plainListRows(parent, scannedElements)) continue;
            candidates.put(parent, parent.children());
            plainLists.add(parent);
            notes.put(
                    parent,
                    "同级纯文本条目按页面原序取证，维护粒度和业务归属待审核。"
                            + "与页面正文候选可能共用原始位置，同一位置只能绑定一次。");
        }
        // Append new repeated evidence after the existing regions, preserving their value budgets.
        var pairedLists = new LinkedHashMap<Element, List<List<Element>>>();
        var repeatAncestors = new HashSet<Element>();
        candidates.keySet().forEach(parent -> addAncestors(repeatAncestors, parent));
        for (var parent : page.elements) {
            if (candidates.size() > MAX_REGIONS) break;
            if (!parent.is("ul, ol")
                    || parent.closest(SHARED + ", " + REPEAT_CONTROLS + ", form, " + HIDDEN)
                            != null
                    || repeatAncestors.contains(parent)
                    || candidates.containsKey(parent)
                    || parent.parents().stream().anyMatch(candidates::containsKey)) continue;
            var pairs = pairedRows(parent, scannedElements);
            if (pairs.isEmpty()) continue;
            candidates.put(parent, parent.children());
            pairedLists.put(parent, pairs);
            notes.put(
                    parent,
                    "同构条目含两个独立文本位置，保留页面原序；业务归属及维护粒度待审核。"
                            + "与页面正文可能共用原始位置，同一位置只能绑定一次。");
        }
        // Unlinked media rows are evidence, not a decision about their business model.
        // Append them after every established candidate to retain old IDs and budget priority.
        var mediaLists = new HashSet<Element>();
        var mediaAncestors = new HashSet<Element>();
        candidates.keySet().forEach(parent -> addAncestors(mediaAncestors, parent));
        editorRoots.forEach(parent -> addAncestors(mediaAncestors, parent));
        for (var parent : page.elements) {
            if (candidates.size() > MAX_REGIONS) break;
            if (!parent.is("ul, ol, div")
                    || parent.closest(SHARED + ", " + REPEAT_CONTROLS + ", form, " + HIDDEN) != null
                    || mediaAncestors.contains(parent)
                    || candidates.containsKey(parent)
                    || parent.parents().stream().anyMatch(candidates::containsKey)
                    || parent.parents().stream().anyMatch(editorRoots::contains)
                    || !mediaRows(parent, scannedElements)) continue;
            candidates.put(parent, parent.children());
            mediaLists.add(parent);
            addAncestors(mediaAncestors, parent);
            notes.put(
                    parent,
                    "无链接的同构图文条目，图片与文字按同一行取证，业务用途及是否独立维护由Agent结合页面判断。"
                            + "多张图片是同条不同位置，不能直接认定为多条记录或猜测悬停行为；相同素材和切换标签不能证明内容身份或年份。"
                            + "与已有页面来源共用位置，重复占位及初值归属须审核。");
        }
        for (var group : MaintainableCardEvidence.read(page.document)) {
            if (candidates.size() >= MAX_REGIONS) break;
            var parent = group.container();
            var items = group.cards().stream().map(MaintainableCardEvidence.Row::row).toList();
            if (candidates.containsKey(parent) || !scannedElements.contains(parent)
                    || !scannedElements.containsAll(items)
                    || parent.parents().stream().anyMatch(candidates::containsKey)
                    || parent.closest(SHARED + ", " + REPEAT_CONTROLS) != null) continue;
            candidates.put(parent, items);
            mediaLists.add(parent);
            notes.put(parent, "同组卡片的独立标题及正文或素材构成记录证据，无须先有分页或详情。"
                    + "目录显示位置与卡片按来源标题核对，审核后决定是否维护同一条记录。");
        }
        var regions = new ArrayList<Region>();
        var legacySelectors = new HashSet<String>();
        var incompleteText = new HashSet<Element>();
        if (!notes.isEmpty()) {
            for (var element : page.elements) {
                if (element.children().stream().anyMatch(child -> !scannedElements.contains(child))
                        && incompleteText.add(element)) addAncestors(incompleteText, element);
            }
        }
        int valuesRemaining = MAX_REPEATED_VALUES;
        int charsRemaining = MAX_REPEATED_CHARS;
        for (var entry : candidates.entrySet()) {
            var parent = entry.getKey();
            var items = entry.getValue();
            boolean slider = parent.hasClass("swiper-wrapper");
            if (regions.size() == MAX_REGIONS) {
                page.warnings.add(REGION_LIMIT_WARNING);
                break;
            }
            var warnings = new ArrayList<String>();
            boolean inferred = notes.containsKey(parent);
            if (inferred) warnings.add(notes.get(parent));
            var pairs = pairedLists.get(parent);
            if (plainLists.contains(parent) || pairs != null || mediaLists.contains(parent)) {
                var heading = parent.previousElementSibling();
                if (heading != null
                        && scannedElements.contains(heading)
                        && heading.is("h1, h2, h3, h4, h5, h6")) {
                    String label = excerpt(heading.text(), 160);
                    if (!label.isBlank()) warnings.add("区域前相邻标题：" + label);
                }
            }
            if (slider && items.stream().allMatch(item -> yearLabel(item) != null))
                warnings.add("滑块仅含年份标签，可能为分组导航控件，待核对实际内容区域。");
            var records = new ArrayList<SourceRecord>();
            String selector = selector(parent);
            if (!inferred) legacySelectors.add(selector);
            for (var item : items) {
                if (records.size() == MAX_RECORDS || !scannedElements.contains(item)) {
                    warnings.add("重复记录超过扫描上限，后续记录待核对。");
                    break;
                }
                int order = records.size() + 1;
                var record =
                        pairs == null
                                ? record(
                                        page.path,
                                        order,
                                        item,
                                        scannedElements,
                                        inferred ? incompleteText : null,
                                        page.hasBase ? Set.of() : paths)
                                : pairedRecord(page.path, item, order, pairs.get(order - 1));
                int valueChars =
                        record.values().stream().mapToInt(value -> value.rawValue().length()).sum();
                if (record.values().size() > valuesRemaining || valueChars > charsRemaining) {
                    warnings.add("重复区域字段超过取证上限，剩余记录待核对。");
                    break;
                }
                valuesRemaining -= record.values().size();
                charsRemaining -= valueChars;
                records.add(record);
            }
            if ((pairs != null || mediaLists.contains(parent)) && records.size() != items.size()) {
                warn(
                        page.warnings,
                        pairs != null ? PAIRED_LIMIT_WARNING : MEDIA_LIMIT_WARNING);
                break;
            }
            if (records.stream().anyMatch(record -> !record.warnings().isEmpty()))
                warnings.add("区域存在待核对的记录或字段，详见各记录。");
            if (sharesLinkTarget(records))
                warnings.add("多条记录链接指向同一包内页面。该路径可能是共用模板，不能据此认定内容身份。");
            String kind =
                    slider && parent.closest(".banner, .site_banner, .page_banner") != null
                            ? "BANNER"
                            : "REPEATED";
            regions.add(
                    new Region(
                            digest(page.path + "\n" + selector),
                            kind,
                            selector,
                            List.copyOf(records),
                            List.copyOf(warnings)));
        }
        if (regions.isEmpty()) page.warnings.add("当前规则未取得重复区域，其他重复布局仍待核对。");
        return new RepeatedEvidence(List.copyOf(regions), Set.copyOf(legacySelectors));
    }

    static boolean mediaRows(Element parent, Set<Element> scanned) {
        var rows = parent.children();
        if (rows.size() < 2 || rows.size() > MAX_RECORDS) return false;
        String tag = parent.is("ul, ol") ? "li" : "div";
        boolean optionalPhoto = rows.stream().anyMatch(row -> row.selectFirst("img") == null);
        if (optionalPhoto && rows.stream().noneMatch(row -> row.selectFirst("img") != null))
            return false;
        List<String> signature = null;
        for (var row : rows) {
            if (!row.normalName().equals(tag)) return false;
            var nodes = row.getAllElements();
            if (nodes.size() > 80
                    || nodes.stream()
                            .anyMatch(
                                    node ->
                                            !scanned.contains(node)
                                                    || node.is(HIDDEN + ", " + REPEAT_CONTROLS)
                                                    || !node.is(
                                                            "li, div, dl, dt, dd, p, span, h1, h2,"
                                                                + " h3, h4, h5, h6, img, strong, b,"
                                                                + " em, i, small, br, hr")))
                return false;
            var images = row.select("img");
            if (images.size() > (optionalPhoto ? 1 : 2)
                    || images.stream()
                            .anyMatch(
                                    img ->
                                            img.attr("src").isBlank()
                                                    && img.attr("data-src").isBlank()
                                                    && img.attr("data-loadimg").isBlank())
                    || images.size() == 2 && images.get(0).parent() != images.get(1).parent())
                return false;
            long texts = nodes.stream().filter(node -> !node.ownText().isBlank()).count();
            if (texts == 0 || texts > 8) return false;
            var shape =
                    nodes.stream()
                            .filter(
                                    node ->
                                            !node.is("br, hr")
                                                    && (!optionalPhoto || !node.text().isBlank()))
                            .map(Element::normalName)
                            .toList();
            if (signature == null) signature = shape;
            else if (!signature.equals(shape)) return false;
        }
        return true;
    }

    private static List<List<Element>> pairedRows(Element list, Set<Element> scanned) {
        var rows = list.children();
        if (rows.size() > MAX_RECORDS
                || !uniformRows(rows, "li", 2)
                || rows.getFirst().childrenSize() != 2) return List.of();
        var pairs = new ArrayList<List<Element>>();
        var firstRow = rows.getFirst();
        for (var row : rows) {
            if (!row.ownText().isBlank()
                    || !row.child(0).normalName().equals(firstRow.child(0).normalName())
                    || !row.child(1).normalName().equals(firstRow.child(1).normalName())
                    || row.getAllElements().stream()
                            .anyMatch(e -> !scanned.contains(e) || e.is(HIDDEN)))
                return List.of();
            Element first = pairedText(row.child(0));
            Element second = pairedText(row.child(1));
            if (first == null
                    || second == null
                    || !second.is("p")
                    || !pairs.isEmpty()
                            && !first.normalName().equals(pairs.getFirst().getFirst().normalName()))
                return List.of();
            pairs.add(List.of(first, second));
        }
        return pairs;
    }

    private static Element pairedText(Element slot) {
        Element text = slot;
        if (slot.is("div")) {
            if (!slot.ownText().isBlank()) return null;
            text = null;
            for (var child : slot.children()) {
                // Ignore an empty icon or a display ordinal next to a text position.
                if (child.is("i, em")
                        && child.childrenSize() == 0
                        && (child.ownText().isBlank()
                                || DISPLAY_ORDER.matcher(child.ownText()).matches()))
                    continue;
                if (text != null) return null;
                text = child;
            }
        }
        if (text == null
                || !text.is(PAIRED_TEXT)
                || text.children().stream().anyMatch(e -> !e.is("br")))
            return null;
        String value = text.wholeText().strip();
        return value.isBlank() || value.length() > MAX_VALUE_CHARS ? null : text;
    }

    private static SourceRecord pairedRecord(
            String path, Element item, int order, List<Element> pair) {
        var values = new ArrayList<Value>();
        var warnings = new ArrayList<String>();
        for (var field : pair)
            addValue(path, field, "text", field.wholeText().strip(), "TEXT", values, warnings);
        String locator = selector(item);
        return new SourceRecord(
                digest(path + "\n" + locator),
                order,
                locator,
                List.copyOf(values),
                List.copyOf(warnings));
    }

    private static boolean plainListRows(Element list, Set<Element> scanned) {
        var rows = list.children();
        if (rows.size() > MAX_RECORDS || !uniformRows(rows, "li", 2)) return false;
        var first = rows.getFirst();
        var structure = first.children().stream().map(Element::normalName).toList();
        for (var row : rows) {
            if (!scanned.contains(row)
                    || row.is("[hidden], [aria-hidden=true]")
                    || !structure.equals(row.children().stream().map(Element::normalName).toList()))
                return false;
            if (structure.isEmpty()) {
                if (!plainListText(row)) return false;
                continue;
            }
            var text = row.child(0);
            if (!row.ownText().isBlank() || !text.is("p, span, div") || !plainListText(text))
                return false;
            for (var child : row.children()) {
                if (!scanned.contains(child)
                        || child.childrenSize() != 0
                        || child != text
                                && (!child.is("i, em, span") || !child.ownText().isBlank()))
                    return false;
            }
        }
        return true;
    }

    private static boolean plainListText(Element element) {
        String text = element.ownText().strip();
        return !element.is("[hidden], [aria-hidden=true]")
                && text.length() >= 4
                && text.length() <= MAX_VALUE_CHARS
                && yearLabel(element) == null
                && textType(text).equals("TEXT");
    }

    private static boolean sharesLinkTarget(List<SourceRecord> records) {
        var targets = new HashSet<String>();
        for (var record : records) {
            var rowTargets =
                    record.values().stream()
                            .map(Value::targetPath)
                            .filter(Objects::nonNull)
                            .filter(StaticPageSource::supports)
                            .distinct()
                            .toList();
            for (String target : rowTargets) {
                if (!targets.add(target)) return true;
            }
        }
        return false;
    }

    private static void addAncestors(Set<Element> ancestors, Element element) {
        for (var ancestor = element.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (!ancestors.add(ancestor)) return;
        }
    }

    private static boolean uniformRows(List<Element> rows, String tag, int minimum) {
        if (rows.size() < minimum) return false;
        var first = rows.getFirst();
        var classes = first.classNames();
        return rows.stream()
                .allMatch(
                        row ->
                                row.normalName().equals(tag)
                                        && (!tag.equals("div")
                                                || classes.stream().anyMatch(row::hasClass))
                                        && row.childrenSize() == first.childrenSize());
    }

    private static String groupYear(Element list, Set<Element> scanned) {
        var group = list.parent();
        if (group == null
                || group.childrenSize() != 2
                || group.child(1) != list
                || !scanned.contains(group.child(0))) return null;
        String year = yearLabel(group.child(0));
        if (year == null || !textRows(list, scanned)) return null;
        var siblings = group.parent().children();
        if (!uniformRows(siblings, "div", 2)) return null;
        // Confirm a second independently labelled group in the same container.
        for (var sibling : siblings.stream().limit(3).toList()) {
            if (sibling == group
                    || !scanned.contains(sibling)
                    || !scanned.contains(sibling.child(0))
                    || !scanned.contains(sibling.child(1))) continue;
            String otherYear = yearLabel(sibling.child(0));
            if (otherYear != null && !year.equals(otherYear) && textRows(sibling.child(1), scanned))
                return year;
        }
        return null;
    }

    private static String yearLabel(Element node) {
        if (node.childrenSize() != 0) return null;
        String text = node.ownText().strip();
        return YEAR.matcher(text).matches() ? text : null;
    }

    private static boolean textRows(Element list, Set<Element> scanned) {
        if (!list.is("div, ul, ol")) return false;
        var rows = list.children();
        return uniformRows(rows, list.is("div") ? "div" : "li", 2)
                && rows.stream()
                        .allMatch(
                                row ->
                                        scanned.contains(row)
                                                && row.childrenSize() == 0
                                                && row.ownText().length() >= 4
                                                && yearLabel(row) == null);
    }

    private static boolean datedOrAttachmentRow(Element row, Set<Element> scanned) {
        Element link = null;
        for (var child : row.children()) {
            if (scanned.contains(child) && child.is("a[href]")) {
                if (link != null) return false;
                link = child;
            }
        }
        if (link == null) return false;
        String href = link.attr("href").strip();
        if (href.length() > MAX_VALUE_CHARS || isPlaceholder(href) || href.startsWith("#"))
            return false;
        try {
            String scheme = URI.create(href).getScheme();
            if (scheme != null
                    && !scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https")) return false;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        boolean date = hasDate(row, scanned) || hasDate(link, scanned);
        boolean attachment = link.hasAttr("download") || ATTACHMENT.matcher(href).find();
        return (date || attachment)
                && (hasTitle(row, scanned)
                        || hasTitle(link, scanned)
                        || link.ownText().strip().length() >= 4);
    }

    private static boolean hasDate(Element parent, Set<Element> scanned) {
        return parent.children().stream()
                .anyMatch(
                        child ->
                                scanned.contains(child)
                                        && child.childrenSize() == 0
                                        && textType(child.ownText().strip()).equals("DATE"));
    }

    private static boolean hasTitle(Element parent, Set<Element> scanned) {
        return parent.children().stream()
                .anyMatch(
                        child ->
                                scanned.contains(child)
                                        && child.is("div, span, p, h1, h2, h3, h4, h5, h6")
                                        && child.childrenSize() == 0
                                        && child.ownText().strip().length() >= 4
                                        && !textType(child.ownText().strip()).equals("DATE")
                                        && yearLabel(child) == null);
    }

    private static Map<String, List<Element>> pageNodes(ScannedPage page) {
        var nodes = new LinkedHashMap<String, List<Element>>();
        for (var element : page.elements) {
            if (element.is("body") || element.closest(SHARED) != null) continue;
            nodes.computeIfAbsent(element.cssSelector(), ignored -> new ArrayList<>()).add(element);
        }
        return nodes;
    }

    private static Region pageSource(
            ScannedPage page,
            TemplateSeedService.Preview preview,
            Set<String> repeatSelectors,
            Map<String, List<Element>> nodes) {
        var values = new ArrayList<Value>();
        var warnings = new ArrayList<String>();
        var blockedRegions = new ArrayList<String>();
        Set<String> ids = new HashSet<>();
        int chars = 0;
        var ordered = new ArrayList<TemplateSeedService.Item>();
        var editableSelectors = preview.items().stream()
                .filter(item -> item.kind().equals("HTML_REGION"))
                .map(TemplateSeedService.Item::selector)
                .collect(Collectors.toSet());
        preview.items().stream()
                .filter(item -> item.kind().equals("HTML_REGION"))
                .forEach(ordered::add);
        preview.items().stream()
                .filter(item -> !item.kind().equals("HTML_REGION"))
                .forEach(ordered::add);
        for (var item : ordered) {
            var matches = nodes.getOrDefault(item.selector(), List.of());
            if (matches.size() != 1) {
                if (matches.size() > 1) warn(warnings, "部分候选定位不唯一，需核对原页面。");
                continue;
            }
            Element node = matches.getFirst();
            String selector = selector(node);
            if (item.kind().equals("HTML_REGION_PENDING")) {
                if (editableSelectors.contains(item.selector())) continue;
                if (blockedRegions.size() < 4)
                    warn(
                            blockedRegions,
                            "正文候选[" + selector + "]暂不能绑定：" + excerpt(item.context(), 160));
                else warn(warnings, "其余暂不能绑定的正文候选需核对原页面。");
                continue;
            }
            if (repeatSelectors.stream()
                    .anyMatch(
                            parent ->
                                    selector.equals(parent)
                                            || selector.startsWith(parent + " > ")
                                            || (item.kind().equals("HTML_REGION")
                                                    && parent.startsWith(selector + " > "))))
                continue;
            if (item.scriptInput() != null || item.kind().endsWith("_PENDING")) {
                warn(warnings, "脚本输入、交互或布局原值待单独核对。");
                continue;
            }
            String attribute =
                    switch (item.kind()) {
                        case "TEXT" -> "text";
                        case "IMAGE" -> node.hasAttr("src") ? "src" : "data-src";
                        case "LINK" -> "href";
                        case "HTML_REGION" -> "html";
                        case "VIDEO_SRC", "VIDEO_SOURCE" -> "src";
                        case "VIDEO_POSTER" -> "poster";
                        default -> null;
                    };
            if (attribute == null || item.value().isBlank()) continue;
            int max = attribute.equals("html") ? MAX_PAGE_VALUE_CHARS / 2 : MAX_VALUE_CHARS;
            if (values.size() == MAX_PAGE_VALUES
                    || item.value().length() > max
                    || chars + item.value().length() > MAX_PAGE_VALUE_CHARS) {
                warn(warnings, "页面候选超出取证上限，部分字段或正文需核对原文。");
                continue;
            }
            String type =
                    switch (item.kind()) {
                        case "TEXT" -> textType(item.value());
                        case "VIDEO_SRC", "VIDEO_SOURCE" -> "VIDEO";
                        case "VIDEO_POSTER" -> "IMAGE";
                        case "LINK" -> isPlaceholder(item.value()) ? "PLACEHOLDER_LINK" : "LINK";
                        default -> item.kind();
                    };
            String id = digest(page.path + "\n" + selector + "\n" + attribute + "\n" + type);
            if (!ids.add(id)) continue;
            TextRange range = "TEXT".equals(type) ? valueRangeHint(node, item.value()) : null;
            values.add(new Value(id, selector, attribute, item.value(), type, null, range));
            chars += item.value().length();
        }
        warnings.addAll(0, preview.warnings());
        warnings.addAll(0, blockedRegions);
        if (values.isEmpty()) {
            page.warnings.addAll(0, blockedRegions);
            warnings.forEach(warning -> warn(page.warnings, warning));
            page.warnings.add("页面正文候选未取得可定位值。");
            return null;
        }
        var record =
                new SourceRecord(
                        digest(page.path + "\nbody\nrecord"),
                        1,
                        "body",
                        List.copyOf(values),
                        List.of());
        return new Region(
                digest(page.path + "\nbody\nregion"),
                "PAGE_SOURCE",
                "body",
                List.of(record),
                List.copyOf(warnings));
    }

    private static TextRange valueRangeHint(Element element, String original) {
        if (!element.is("li, dt, dd, td, h1, h2, h3, h4, h5, h6")) return null;
        var match = LABELLED_TEXT.matcher(original);
        if (!match.matches() || match.group(1).codePoints().noneMatch(Character::isLetter)) return null;
        int start = match.start(2);
        int end = match.end(2);
        while (start < end && Character.isWhitespace(original.charAt(start))) start++;
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) end--;
        if (start == end) return null;
        return element.lastChild() instanceof TextNode node
                        && node.getWholeText().stripTrailing().endsWith(original.substring(start, end))
                ? new TextRange(start, end)
                : null;
    }

    private static SourceRecord record(
            String path,
            int order,
            Element item,
            Set<Element> scannedElements,
            Set<Element> incompleteText,
            Set<String> paths) {
        var values = new ArrayList<Value>();
        var warnings = new ArrayList<String>();
        boolean inferred = incompleteText != null;
        for (var element : item.getAllElements()) {
            if (!scannedElements.contains(element)) {
                warn(warnings, "记录含未扫描节点，字段待核对。");
                continue;
            }
            if (element.closest(".swiper-slide") != item && item.hasClass("swiper-slide")) continue;
            boolean structuredLink =
                    inferred
                            && element.is("a")
                            && (hasTitle(element, scannedElements)
                                    || hasDate(element, scannedElements));
            if (element.is("h1, h2, h3, h4, h5, h6, p, a, .text, .title, time")
                    || (inferred && element.childrenSize() == 0 && element.is("li, div, span"))
                    || (element.is("div, span")
                            && element.children().isEmpty()
                            && element.parent()
                                            .closest(
                                                    "h1, h2, h3, h4, h5, h6, p, a, .text, .title,"
                                                            + " time")
                                    == null))
                if (inferred && incompleteText.contains(element)) warn(warnings, "文本含未扫描节点，字段待核对。");
                else
                    addValue(
                            path,
                            element,
                            "text",
                            (structuredLink ? element.ownText() : element.wholeText()).strip(),
                            "TEXT",
                            values,
                            warnings);
            if (element.is("a[href]")) {
                String href = element.attr("href");
                boolean placeholder = isPlaceholder(href);
                addValue(
                        path,
                        element,
                        "href",
                        href,
                        placeholder ? "PLACEHOLDER_LINK" : "LINK",
                        values,
                        warnings,
                        placeholder ? null : resolveLocal(path, href, paths));
                if (placeholder) warn(warnings, "存在占位链接，待核对实际跳转。");
            }
            if (element.hasAttr("style")) {
                for (String url : inlineBackgroundSources(element.attr("style"))) {
                    addValue(path, element, "style", url, "IMAGE", values, warnings);
                }
            }
            if (element.is("img")) {
                for (String attribute : List.of("src", "data-src", "data-loadimg"))
                    addValue(
                            path,
                            element,
                            attribute,
                            element.attr(attribute),
                            "IMAGE",
                            values,
                            warnings);
            }
            if (element.is("video, video source, [data-video]")) {
                warn(warnings, "视频变体待核对，保留视频和封面原值。");
                for (String attribute : List.of("src", "data-src", "data-video", "poster"))
                    addValue(
                            path,
                            element,
                            attribute,
                            element.attr(attribute),
                            attribute.equals("poster") ? "IMAGE" : "VIDEO",
                            values,
                            warnings);
            }
        }
        if (values.isEmpty()) warnings.add("未取得记录字段，待人工核对。");
        String selector = selector(item);
        return new SourceRecord(
                digest(path + "\n" + selector),
                order,
                selector,
                List.copyOf(values),
                List.copyOf(warnings));
    }

    private static boolean isPlaceholder(String value) {
        String stripped = value.strip();
        return stripped.isEmpty()
                || stripped.matches("#+")
                || stripped.regionMatches(true, 0, "javascript:", 0, 11);
    }

    private static void addValue(
            String path,
            Element element,
            String attribute,
            String raw,
            String type,
            List<Value> values,
            List<String> warnings) {
        addValue(path, element, attribute, raw, type, values, warnings, null);
    }

    private static void addValue(
            String path,
            Element element,
            String attribute,
            String raw,
            String type,
            List<Value> values,
            List<String> warnings,
            String targetPath) {
        if (raw.isBlank() && !type.equals("PLACEHOLDER_LINK")) return;
        if (values.size() == MAX_VALUES || raw.length() > MAX_VALUE_CHARS) {
            warn(warnings, "部分字段值超过取证上限，待核对原文。");
            return;
        }
        String selector = selector(element);
        String hint = type.equals("TEXT") ? textType(raw) : type;
        String key = path + "\n" + selector + "\n" + attribute + "\n" + hint;
        long ordinal =
                values.stream()
                        .filter(
                                value ->
                                        value.selector().equals(selector)
                                                && value.attribute().equals(attribute)
                                                && value.typeHint().equals(hint))
                        .count();
        values.add(
                new Value(
                        digest(key + "\n" + ordinal), selector, attribute, raw, hint, targetPath));
    }

    private static String textType(String raw) {
        return raw.length() == 10 && TemplateDateValues.supported(raw) ? "DATE" : "TEXT";
    }

    private static boolean supportedDepth(Element element) {
        for (int depth = 0; element != null; depth++, element = element.parent()) {
            if (depth > MAX_DOM_DEPTH) return false;
        }
        return true;
    }

    private static String selector(Element element) {
        var parts = new ArrayDeque<String>();
        while (element != null && !element.normalName().equals("body")) {
            parts.addFirst(":nth-child(" + (element.elementSiblingIndex() + 1) + ")");
            element = element.parent();
        }
        return parts.isEmpty() ? "body" : "body > " + String.join(" > ", parts);
    }

    private static String excerpt(String value, int length) {
        if (value.length() <= length) return value;
        return value.substring(
                0, Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length);
    }

    private static void warn(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) warnings.add(warning);
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

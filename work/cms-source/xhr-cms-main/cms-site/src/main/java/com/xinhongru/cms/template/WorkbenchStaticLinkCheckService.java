package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.storage.SiteStoragePathResolver;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 核对本机已生成的 HTML 与活动页面包，全程只读且无网络访问。 */
@Service
public class WorkbenchStaticLinkCheckService {
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_PAGES = 200;
    private static final int MAX_LINKS = 10_000;
    private static final int MAX_ITEMS = 1_000;
    private static final int MAX_HTML = 2 * 1024 * 1024;
    private static final Pattern NUMERIC_SLOT =
            Pattern.compile("\\{(?:classId|parentId|pageIndex|pageSize|id|productId)}");
    private static final URI LOCAL_ORIGIN = URI.create("https://workbench.invalid/");

    private final CmsBuilderHtmlConfigProperties html;
    private final SiteStoragePathResolver storage;
    private final UiPackageStorageService packages;
    private final SiteWorkbenchHandoffService handoff;

    public WorkbenchStaticLinkCheckService(
            CmsBuilderHtmlConfigProperties html,
            SiteStoragePathResolver storage,
            UiPackageStorageService packages,
            SiteWorkbenchHandoffService handoff) {
        this.html = html;
        this.storage = storage;
        this.packages = packages;
        this.handoff = handoff;
    }

    public record Result(String state, int scannedPages, int checkedLinks, boolean truncated, List<Item> items) {}

    public record Item(String state, String outputPath, String href, String targetPath,
            String sourcePath, String fragment, String packagePath, String reason, String reviewHref) {}

    record Route(String path, Pattern pattern, String sourcePath) {
        Route(String path, String sourcePath) {
            this(path, routePattern(path), sourcePath);
        }

        Route(Pattern pattern, String sourcePath) {
            this(null, pattern, sourcePath);
        }
    }

    private record OriginalLink(URI target, String packagePath) {}

    public Result read(SiteProjectEntity site) {
        return inspect(site, handoff.activeApplications(site)).result();
    }

    record Inspection(Result result, Path output, Path original, Map<String, Set<String>> graph) {}

    Inspection inspect(SiteProjectEntity site, List<SiteWorkbenchHandoffService.Application> applications) {
        return inspect(site, applications, MAX_PAGES);
    }

    Inspection inspect(SiteProjectEntity site, List<SiteWorkbenchHandoffService.Application> applications,
            int pageLimit) {
        String saveRoot = html.getSaveRoot();
        if (saveRoot == null || saveRoot.isBlank())
            return new Inspection(empty("UNAVAILABLE"), null, null, Map.of());
        try {
            var base = Path.of(saveRoot).toAbsolutePath().normalize();
            var output = Path.of(storage.siteRootPath(saveRoot, site.getProjectCode())).toAbsolutePath().normalize();
            var original = original(site);
            if (!base.equals(output.getParent()))
                return new Inspection(empty("UNAVAILABLE"), null, original, Map.of());
            if (!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return new Inspection(empty("NO_OUTPUT"), null, original, Map.of());
            if (!within(base.toRealPath(), output))
                return new Inspection(empty("UNAVAILABLE"), null, original, Map.of());
            var routes = applications.stream()
                    .filter(item -> item.route() != null && !item.route().isBlank())
                    .map(item -> new Route(item.route(),
                            "TEMPLATE_VERIFIED".equals(item.state()) ? item.sourcePath() : null))
                    .toList();
            var graph = new HashMap<String, Set<String>>();
            return new Inspection(scan(output, original, origin(site.getSiteDomain()), routes, graph, pageLimit),
                    output, original, graph);
        } catch (IOException | IllegalArgumentException | UncheckedIOException failure) {
            return new Inspection(empty("UNAVAILABLE"), null, null, Map.of());
        }
    }

    private Path original(SiteProjectEntity site) throws IOException {
        var active = packages.getActivePath(site.getProjectCode()).orElse(null);
        if (active == null) return null;
        var base = packages.root().toRealPath();
        var packageSite = base.resolve(site.getProjectCode());
        if (!within(base, packageSite)) return null;
        var real = active.toRealPath();
        return real.startsWith(packageSite) && !real.equals(packageSite) ? real : null;
    }

    static Pattern routePattern(String route) {
        var matcher = NUMERIC_SLOT.matcher(route);
        var regex = new StringBuilder("^");
        int end = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(route.substring(end, matcher.start()))).append("\\d+");
            end = matcher.end();
        }
        return Pattern.compile(regex.append(Pattern.quote(route.substring(end))).append('$').toString());
    }

    static URI origin(String domain) {
        if (domain != null && !domain.isBlank()) {
            try {
                var value = URI.create(domain.contains("://") ? domain : "https://" + domain);
                if (value.getHost() != null && Set.of("http", "https").contains(value.getScheme()))
                    return value.resolve("/");
            } catch (IllegalArgumentException invalid) {
                return LOCAL_ORIGIN;
            }
        }
        return LOCAL_ORIGIN;
    }

    static Result scan(Path output, Path original, URI origin, List<Route> routes) throws IOException {
        return scan(output, original, origin, routes, new HashMap<>(), MAX_PAGES);
    }

    private static Result scan(Path output, Path original, URI origin, List<Route> routes,
            Map<String, Set<String>> graph, int pageLimit) throws IOException {
        var items = new ArrayList<Item>();
        var documents = new HashMap<Path, Set<String>>();
        var originalLinks = new HashMap<String, Set<String>>();
        int pages = 0;
        int links = 0;
        boolean truncated = false;
        try (var paths = Files.find(output, 24, (path, attributes) -> true)) {
            var entries = paths.iterator();
            for (int index = 0; entries.hasNext(); index++) {
                if (index == MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                var file = entries.next();
                if (!isHtml(file) || !within(output, file) || !Files.isRegularFile(file)) continue;
                if (pages == pageLimit || links == MAX_LINKS || items.size() == MAX_ITEMS) {
                    truncated = true;
                    break;
                }
                String outputPath = relative(output, file);
                URI page = origin.resolve(new URI(null, null, "/" + outputPath, null));
                String source = sourcePath(routes, page.getPath());
                var parsed = readHtml(file);
                pages++;
                if (parsed == null) {
                    items.add(new Item("UNVERIFIED", outputPath, "", null, source, null, null,
                            "输出页超过读取上限或读取期间发生变化", null));
                    continue;
                }
                documents.put(file, anchors(parsed));
                var baseElement = parsed.selectFirst("base[href]");
                var base = baseElement == null ? page : resolve(page, baseElement.attr("href").strip());
                var outgoing = graph.computeIfAbsent(outputPath, ignored -> new HashSet<>());
                var visited = new HashSet<String>();
                for (var element : parsed.select("a[href], area[href]")) {
                    if (links == MAX_LINKS || items.size() == MAX_ITEMS) {
                        truncated = true;
                        break;
                    }
                    String rawHref = element.attr("href");
                    String href = rawHref.strip();
                    if (!visited.add(element.normalName() + (interaction(element) ? "1" : "0") + rawHref)) continue;
                    links++;
                    var item = check(output, original, page, base, outputPath, source, element,
                            href, routes, documents, baseElement == null ? originalLinks : null);
                    if (item != null) {
                        items.add(new Item(item.state(), item.outputPath(), item.href(), item.targetPath(),
                                item.sourcePath(), item.fragment(), item.packagePath(), item.reason(),
                                source != null && element.normalName().equals("a") ? reviewHref(rawHref) : null));
                    }
                    var target = resolve(base, href);
                    if (item == null && target != null && sameOrigin(page, target) && safeUrlPath(target)) {
                        var artifact = localFile(output, target.getPath());
                        if (artifact != null && isHtml(artifact)) outgoing.add(relative(output, artifact));
                    }
                }
            }
        } catch (java.net.URISyntaxException invalidPath) {
            return empty("UNAVAILABLE");
        }
        return new Result(pages == 0 ? (truncated ? "UNAVAILABLE" : "NO_OUTPUT") : "SCANNED",
                pages, links, truncated, List.copyOf(items));
    }

    private static Item check(Path output, Path original, URI page, URI base, String outputPath,
            String source, Element element, String href, List<Route> routes, Map<Path, Set<String>> documents,
            Map<String, Set<String>> originalLinks)
            throws IOException {
        if (href.length() > 2048)
            return item("UNVERIFIED", outputPath, href.substring(0, 2048), null, source, null, null,
                    "链接超过读取上限");
        if (href.regionMatches(true, 0, "javascript:", 0, 11)
                || interaction(element) && (href.isEmpty() || href.equals("#") || href.equals("##")))
            return item("DYNAMIC", outputPath, href, null, source, null, null,
                    "链接由页面交互处理，需在浏览器验证");
        var target = resolve(base, href);
        if (target == null)
            return item("UNVERIFIED", outputPath, href, null, source, null, null,
                    "链接或页面基准地址无法安全解析");
        if (!sameOrigin(page, target))
            return item("EXTERNAL", outputPath, href, null, source, null, null, "站外或非网页链接，未发起请求");
        String path = target.getPath();
        String fragment = target.getFragment();
        if (target.getRawPath().contains("//"))
            return item("UNVERIFIED", outputPath, href, path, source, fragment, null,
                    "同站链接路径含连续斜杠，无法按发布产物路径核验");
        if (!safeUrlPath(target))
            return item("UNVERIFIED", outputPath, href, path, source, fragment, null, "链接路径无法安全映射到本地目录");
        var artifact = localFile(output, path);
        if (artifact == null) {
            var fallback = original == null ? null : localFile(original, path);
            if (fallback != null)
                return item("PACKAGE_FALLBACK", outputPath, href, path, source, fragment,
                        relative(original, fallback), "发布目录缺少目标，活动页面包内有同路径文件，可能回退到原包");
            var originalLink = originalLinks == null ? null
                    : originalRelativeTarget(original, source, element, originalLinks);
            if (originalLink != null) {
                String suggestion = publishedSuggestion(output, page, originalLink, routes);
                return item("MISSING_ARTIFACT", outputPath, href, path, source, fragment, originalLink.packagePath(),
                        suggestion == null
                                ? "发布后相对链接解析到不同目录，已核实原包目标。请核对正式落点后调整链接"
                                : "发布后相对链接解析到不同目录，已找到对应发布产物。请审核是否调整为 " + suggestion);
            }
            boolean configured = routes.stream().anyMatch(route -> route.pattern().matcher(path).matches());
            return item("MISSING_ARTIFACT", outputPath, href, path, source, fragment, null,
                    configured ? "已配置栏目路由缺少对应发布产物，动态服务结果需另行验证"
                            : "当前发布目录未找到链接目标");
        }
        if (fragment == null || fragment.isEmpty() || !isHtml(artifact)) return null;
        // 文本片段指令由浏览器执行；有锚点前缀时只核对该锚点。
        String anchor = fragment.split(":~:", 2)[0];
        if (anchor.isEmpty())
            return item("DYNAMIC", outputPath, href, path, source, fragment, null,
                    "浏览器文本片段需在浏览器验证");
        if (!documents.containsKey(artifact) && documents.size() < MAX_PAGES * 2) {
            var parsed = readHtml(artifact);
            documents.put(artifact, parsed == null ? null : anchors(parsed));
        }
        var expected = documents.get(artifact);
        if (expected == null)
            return item("UNVERIFIED", outputPath, href, path, source, fragment, null,
                    "目标 HTML 未能完整读取，锚点待核实");
        return expected.contains(anchor) || "top".equalsIgnoreCase(anchor) ? null
                : item("MISSING_ANCHOR", outputPath, href, path, source, fragment, null,
                        "目标发布 HTML 不含该 ID 或具名锚点");
    }

    private static OriginalLink originalRelativeTarget(Path original, String source, Element element,
            Map<String, Set<String>> originalLinks) throws IOException {
        if (original == null || source == null || !element.normalName().equals("a")) return null;
        String href = reviewHref(element.attr("href"));
        if (href == null || href.startsWith("/") || interaction(element)) return null;
        URI sourcePath;
        try {
            sourcePath = new URI(null, null, "/" + source, null);
        } catch (java.net.URISyntaxException invalid) {
            return null;
        }
        if (!safeUrlPath(sourcePath)) return null;
        URI target = resolve(LOCAL_ORIGIN.resolve(sourcePath), href);
        if (target == null || !sameOrigin(LOCAL_ORIGIN, target) || !safeUrlPath(target)) return null;
        if (!originalLinks.containsKey(source)) {
            var sourceFile = localFile(original, sourcePath.getPath());
            var document = sourceFile == null || !isHtml(sourceFile) ? null : readHtml(sourceFile);
            Set<String> links = new HashSet<>();
            if (document != null && document.selectFirst("base[href]") == null) {
                for (var link : document.select("a[href]")) {
                    if (!interaction(link)) links.add(link.attr("href"));
                }
            }
            originalLinks.put(source, links);
        }
        if (!originalLinks.get(source).contains(href)) return null;
        var targetFile = localFile(original, target.getPath());
        return targetFile == null ? null : new OriginalLink(target, relative(original, targetFile));
    }

    private static String publishedSuggestion(Path output, URI page, OriginalLink originalLink,
            List<Route> routes) throws IOException {
        var paths = routes.stream().filter(route -> originalLink.packagePath().equals(route.sourcePath()) && route.path() != null
                        && !NUMERIC_SLOT.matcher(route.path()).find())
                .map(Route::path).distinct().toList();
        if (paths.size() != 1 || !paths.getFirst().startsWith("/")) return null;
        URI target = resolve(page, paths.getFirst());
        if (target == null || !sameOrigin(page, target) || !safeUrlPath(target)
                || target.getRawQuery() != null || target.getRawFragment() != null
                || localFile(output, target.getPath()) == null) return null;
        URI originalTarget = originalLink.target();
        return target.getRawPath()
                + (originalTarget.getRawQuery() == null ? "" : "?" + originalTarget.getRawQuery())
                + (originalTarget.getRawFragment() == null ? "" : "#" + originalTarget.getRawFragment());
    }

    private static Item item(String state, String output, String href, String path, String source,
            String fragment, String packagePath, String reason) {
        return new Item(state, output, href, path, source, fragment, packagePath, reason, null);
    }

    private static String reviewHref(String rawHref) {
        if (rawHref.isBlank() || rawHref.length() > 512 || rawHref.startsWith("//")) return null;
        try {
            var uri = URI.create(rawHref);
            var path = uri.getPath();
            return !uri.isAbsolute() && uri.getRawAuthority() == null
                    && (uri.getRawFragment() == null || !uri.getRawFragment().isBlank() && uri.getRawQuery() == null)
                    && path != null && !path.isBlank() && path.indexOf('\\') < 0 ? rawHref : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static URI resolve(URI base, String href) {
        try {
            if (base == null || href.indexOf('\\') >= 0) return null;
            // HTTP 页面中，开头三个以上斜杠仍引入主机；不可当作站内文件路径。
            if (href.startsWith("///")) {
                int host = 3;
                while (host < href.length() && href.charAt(host) == '/') host++;
                href = "//" + href.substring(host);
                if (URI.create(href.replace(" ", "%20")).getHost() == null) return null;
            }
            int hash = href.indexOf('#');
            if (hash >= 0)
                href = href.substring(0, hash + 1) + href.substring(hash + 1).replace("#", "%23");
            URI ref = URI.create(href.replace(" ", "%20"));
            if (!ref.isAbsolute() && ref.getRawAuthority() == null && ref.getRawPath().isEmpty()) {
                var current = base.toString().split("[?#]", 2)[0];
                return URI.create(current + ref);
            }
            var resolved = base.resolve(ref);
            if (resolved.isOpaque() || resolved.getRawAuthority() == null) return resolved.normalize();
            String path = ref.getRawPath();
            if (!ref.isAbsolute() && ref.getRawAuthority() == null && !path.startsWith("/")) {
                String directory = base.getRawPath();
                path = directory.isEmpty() ? "/" + path : directory.substring(0, directory.lastIndexOf('/') + 1) + path;
            }
            if (!path.contains("//")) return resolved.normalize();
            // URI.resolve 和 URI.normalize 都可能丢掉空路径段；浏览器保留这些斜杠。
            String query = resolved.getRawQuery();
            String fragment = resolved.getRawFragment();
            return URI.create(resolved.getScheme() + "://" + resolved.getRawAuthority() + removeDotSegments(path)
                    + (query == null ? "" : "?" + query) + (fragment == null ? "" : "#" + fragment));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String removeDotSegments(String path) {
        var segments = new ArrayList<String>();
        var parts = path.substring(1).split("/", -1);
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.equals("..") && !segments.isEmpty()) segments.removeLast();
            if (!part.equals(".") && !part.equals("..")) segments.add(part);
            else if (index == parts.length - 1) segments.add("");
        }
        return "/" + String.join("/", segments);
    }

    static boolean sameOrigin(URI page, URI target) {
        return target.getUserInfo() == null
                && page.getScheme().equalsIgnoreCase(Objects.toString(target.getScheme(), ""))
                && page.getHost().equalsIgnoreCase(Objects.toString(target.getHost(), ""))
                && port(page) == port(target);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static boolean safeUrlPath(URI target) {
        String raw = Objects.toString(target.getRawPath(), "").toLowerCase(Locale.ROOT);
        String path = Objects.toString(target.getPath(), "");
        return path.startsWith("/") && !path.contains("//")
                && !raw.contains("%2f") && !raw.contains("%5c")
                && path.indexOf('\\') < 0 && path.indexOf(':') < 0
                && path.chars().noneMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/"))
                        .noneMatch(segment -> segment.equals(".") || segment.equals("..")
                                || segment.endsWith(".") || segment.endsWith(" "));
    }

    static Path localFile(Path root, String path) throws IOException {
        Path file;
        try {
            file = root.resolve(path.substring(1)).normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            return null;
        }
        if (!root.equals(file) && !within(root, file)) return null;
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        return within(root, file) && Files.isRegularFile(file) ? file : null;
    }

    private static boolean within(Path root, Path file) throws IOException {
        return file.startsWith(root) && !file.equals(root)
                && Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && file.toRealPath().equals(file);
    }

    private static Document readHtml(Path file) throws IOException {
        if (Files.size(file) > MAX_HTML) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_HTML + 1);
            return bytes.length > MAX_HTML ? null
                    : Jsoup.parse(new java.io.ByteArrayInputStream(bytes), null, "");
        } catch (java.nio.file.NoSuchFileException missing) {
            return null;
        }
    }

    private static Set<String> anchors(Document document) {
        var anchors = new HashSet<String>();
        for (var node : document.select("[id], a[name]")) {
            if (node.hasAttr("id")) anchors.add(node.id());
            if (node.normalName().equals("a") && node.hasAttr("name")) anchors.add(node.attr("name"));
        }
        return anchors;
    }

    private static boolean interaction(Element element) {
        return element.hasAttr("onclick") || element.hasAttr("data-toggle")
                || element.hasAttr("data-bs-toggle") || element.hasAttr("role")
                && "button".equalsIgnoreCase(element.attr("role"));
    }

    private static boolean isHtml(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".aspx");
    }

    private static String sourcePath(List<Route> routes, String path) {
        var matches = routes.stream()
                .filter(route -> route.pattern().matcher(path).matches()
                        || "/index.html".equals(path) && route.pattern().matcher("/").matches())
                .map(Route::sourcePath).distinct().toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Result empty(String state) {
        return new Result(state, 0, 0, false, List.of());
    }
}

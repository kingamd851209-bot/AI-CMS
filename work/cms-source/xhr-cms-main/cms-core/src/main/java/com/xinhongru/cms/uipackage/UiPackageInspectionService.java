package com.xinhongru.cms.uipackage;

import com.xinhongru.cms.template.StaticScriptLiterals;
import com.xinhongru.security.upload.UploadDelivery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 工作台静态预览：本地资源内联，外部样式和字体由浏览器按允许域加载。 */
@Service
public class UiPackageInspectionService {
    private final UiPackageStorageService storage;
    private static final String BINDING_SOURCES = "data-cms-binding-sources";
    private static final String CSS_STRING = "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'";
    private static final String CSS_URL_VALUE = CSS_STRING + "|[^\\s)'\"(]+";
    private static final Pattern CSS_RESOURCE =
            Pattern.compile(
                    "/\\*.*?\\*/"
                            + "|@import\\s*(?:url\\(\\s*(?<importUrl>"
                            + CSS_URL_VALUE
                            + ")\\s*\\)|(?<importString>"
                            + CSS_STRING
                            + "))(?<condition>[^;{}]*);"
                            + "|url\\(\\s*(?<url>"
                            + CSS_URL_VALUE
                            + ")\\s*\\)"
                            + "|"
                            + CSS_STRING,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTTPS_HOST =
            Pattern.compile(
                    "(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final Pattern EMBEDDED_BITMAP =
            Pattern.compile("(?i)data:image/(?:png|jpe?g|gif|webp);base64");
    private static final Pattern STYLE_END = Pattern.compile("(?i)</style");
    private static final Pattern SVG_VIEW_BOX = Pattern.compile("[0-9eE,.+\\-\\s]+");
    private static final Pattern SVG_PATH =
            Pattern.compile("[0-9eEAaCcHhLlMmQqSsTtVvZz,.+\\-\\s]+");
    private static final Pattern SVG_FILL = Pattern.compile("(?i)#[0-9a-f]{3,8}|none|currentColor");
    private static final Pattern INTRO_NAME =
            Pattern.compile(
                    "(?i)(?:loading|(?:preloader|page-loader|page-loading|loading-mask|loading-overlay|logo-animate)(?:-[a-z0-9]+)*)");
    private static final Pattern INTRO_REMOVE =
            Pattern.compile(
                    "/\\*.*?\\*/|//[^\\r"
                        + "\\n"
                        + "]*|(?<![\\w$])(?:\\$|jQuery)\\s*\\(\\s*(['\"])(?<selector>[.#][a-zA-Z][\\w-]*)\\1"
                        + "\\s*\\)\\s*\\.\\s*remove\\s*\\(\\s*\\)|"
                            + CSS_STRING
                            + "|`(?:\\\\.|[^`\\\\])*+`",
                    Pattern.DOTALL);
    private static final int MAX_RESOURCE = 2 * 1024 * 1024;
    private static final int MAX_BUDGET = 12 * 1024 * 1024;
    private static final int MAX_CSS_IMPORT_DEPTH = 8;
    private static final String DEFAULT_STYLE_HOSTS = "at.alicdn.com,g.alicdn.com";
    private List<String> styleHosts = parseStyleHosts(DEFAULT_STYLE_HOSTS);

    public UiPackageInspectionService(UiPackageStorageService storage) {
        this.storage = storage;
    }

    @Value("${cms.ui-package.preview-style-hosts:" + DEFAULT_STYLE_HOSTS + "}")
    void configureStyleHosts(String hosts) {
        styleHosts = parseStyleHosts(hosts);
    }

    private static List<String> parseStyleHosts(String hosts) {
        return Arrays.stream(hosts.split(","))
                .map(String::strip)
                .filter(host -> HTTPS_HOST.matcher(host).matches())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String policy() {
        String cdns = String.join("", styleHosts.stream().map(host -> " https://" + host).toList());
        return "default-src 'none'; script-src 'none'; style-src 'unsafe-inline' data:"
                + cdns
                + "; img-src data:; font-src data:"
                + cdns
                + "; connect-src 'none'; form-action 'none'; base-uri 'none';"
                + " frame-src 'none'; object-src 'none'; media-src 'none'";
    }

    public record Inspection(
            String version,
            List<UiPackageStorageService.InspectionFile> files,
            List<String> warnings) {}

    public record Preview(String html, List<String> warnings) {}

    public record Video(String path, String contentType, long size) {}

    public record Videos(String sourcePath, String packageVersion, List<Video> videos) {}

    public record AnchoredPreview(String html, List<String> warnings, String digest) {}

    public Inspection inspect(String code, String version) {
        var files = storage.inspectionFiles(code, version);
        var warnings = new ArrayList<String>();
        warnings.add("ZIP 上传拒绝危险路径、超过 10000 个条目或总解压大小超过 1 GB 的包；隔离预览内联资源上限为单项 2 MB、累计 12 MB，原页 MP4/WebM 视频单独流式播放。");
        warnings.add("包内 JS、说明文档仅登记，不执行安装命令或脚本；静态预览不运行客服、统计、留言与业务接口。");
        files.stream()
                .filter(f -> f.size() > 2 * 1024 * 1024 && !UploadDelivery.isVideo(f.path()))
                .forEach(f -> warnings.add("超出预览单文件限制：" + f.path()));
        return new Inspection(version, files, warnings);
    }

    public Videos videos(String code, String version, String sourcePath) throws IOException {
        List<Video> videos = new ArrayList<>();
        for (var entry : originalVideos(code, version, sourcePath).entrySet()) {
            String path = entry.getKey();
            videos.add(new Video(path, UploadDelivery.contentType(path), Files.size(entry.getValue())));
        }
        return new Videos(sourcePath, version, List.copyOf(videos));
    }

    public UploadDelivery.Delivery video(
            String code, String version, String sourcePath, String path, String range)
            throws IOException {
        Path file = originalVideos(code, version, sourcePath).get(path);
        if (file == null) throw new IllegalArgumentException("原页未引用此本地视频或视频不可用");
        return UploadDelivery.prepare(file, range);
    }

    private Map<String, Path> originalVideos(String code, String version, String sourcePath)
            throws IOException {
        Path root = storage.exactInspectionRoot(code, version);
        var source = StaticPageSource.read(root, sourcePath);
        var document = Jsoup.parse(source.html());
        var videos = new LinkedHashMap<String, Path>();
        for (var node : document.select("video[src],video source[src],[data-video]")) {
            String reference = node.hasAttr("data-video") ? node.attr("data-video") : node.attr("src");
            String path = localPath(sourcePath, reference.strip());
            if (path == null || !UploadDelivery.isVideo(path) || videos.containsKey(path)) continue;
            Path file = root.resolve(path);
            if (UploadDelivery.within(root, file) && file.equals(file.toRealPath()) && Files.size(file) > 0)
                videos.put(path, file);
        }
        return videos;
    }

    public Preview preview(String code, String version, String path) {
        return previewPage(code, version, path, storage.readStaticPage(code, version, path), null);
    }

    /** selectorMap 由内容单元工作区提供，以来源 ID 为键。 */
    public AnchoredPreview preview(
            String code,
            String version,
            String path,
            Map<String, String> selectorMap,
            String expectedDigest) {
        var source = storage.readStaticPage(code, version, path);
        if (!source.digest().equals(expectedDigest))
            throw new IllegalArgumentException("页面来源已变化，请重新读取内容单元");
        var isolated = previewPage(code, version, path, source, selectorMap);
        return new AnchoredPreview(isolated.html(), isolated.warnings(), source.digest());
    }

    private Preview previewPage(
            String code,
            String version,
            String path,
            StaticPageSource.Page source,
            Map<String, String> selectorMap) {
        var isolated = isolateRendered(code, version, path, source.html(), selectorMap);
        var warnings = new ArrayList<>(source.warnings());
        warnings.addAll(isolated.warnings());
        return new Preview(isolated.html(), List.copyOf(warnings));
    }

    /** 真实草稿渲染结果复用静态隔离流程。 */
    public Preview isolateRendered(String code, String version, String path, String html) {
        return isolateRendered(code, version, path, html, null);
    }

    private Preview isolateRendered(
            String code,
            String version,
            String path,
            String html,
            Map<String, String> selectorMap) {
        if (html == null || html.length() > 4 * 1024 * 1024)
            throw new IllegalArgumentException("草稿预览HTML为空或超限");
        StaticPageSource.rejectUnexpanded(html);
        var warnings = new ArrayList<String>();
        int[] budget = {MAX_BUDGET};
        var doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        if (selectorMap != null) addBindingSources(doc, selectorMap);
        var staticSvgs = restoreScriptPresentation(doc, code, version, path, budget, warnings);
        for (var video : doc.select("video")) {
            if (!video.attr("poster").isBlank())
                video.before(
                        new org.jsoup.nodes.Element("img")
                                .attr("src", video.attr("poster"))
                                .attr("alt", "视频封面（未播放）"));
            video.before(new org.jsoup.nodes.Element("p").text("视频未播放；隔离预览仅展示封面，真实播放须在专用本地站验证。"));
            video.remove();
        }

        doc.select(
                        "script,noscript,template,iframe,frame,frameset,object,embed,applet,base,meta,math,video,audio,source,track")
                .remove();
        var svgImages = new IdentityHashMap<Element, String>();
        for (var svg : doc.select("svg").not("svg svg")) {
            if (staticSvgs.contains(svg)) continue;
            svg.attr("xmlns", "http://www.w3.org/2000/svg");
            var image = new Element("img");
            for (var key : List.of("id", "class", "width", "height", "style", BINDING_SOURCES))
                if (svg.hasAttr(key)) image.attr(key, svg.attr(key));
            image.attr("alt", svg.select("title").text());
            byte[] data = svg.outerHtml().getBytes(StandardCharsets.UTF_8);
            if (charge(data.length, budget, warnings))
                svgImages.put(image, dataUrl("image/svg+xml", data));
            svg.replaceWith(image);
        }
        for (var style : doc.select("style")) {
            String content = css(code, version, path, style.data(), budget, warnings);
            style.empty().appendChild(new DataNode(content));
        }
        for (var element : doc.select("[style]"))
            element.attr(
                    "style", css(code, version, path, element.attr("style"), budget, warnings));
        var imageSources = new IdentityHashMap<Element, String>();
        for (var image : doc.select("img"))
            imageSources.put(
                    image,
                    image.attr("src").isBlank() ? image.attr("data-src") : image.attr("src"));
        var externalStyles = new IdentityHashMap<Element, String>();
        for (var link : doc.select("link")) {
            if (link.attr("rel").equalsIgnoreCase("stylesheet") && !link.hasAttr("disabled")) {
                String href = link.attr("href");
                String target = localPath(path, href);
                if (target != null) {
                    var css =
                            readStylesheet(
                                    code, version, target, budget, warnings, new ArrayDeque<>());
                    if (css != null) {
                        var style = new Element("style").appendChild(new DataNode(css));
                        if (link.hasAttr("media")) style.attr("media", link.attr("media"));
                        link.before(style);
                    }
                } else {
                    String external = externalStyleUrl(href);
                    if (external != null) {
                        var safe =
                                new Element("link")
                                        .attr("rel", "stylesheet")
                                        .attr("referrerpolicy", "no-referrer");
                        if (link.hasAttr("media")) safe.attr("media", link.attr("media"));
                        if (link.hasAttr("integrity"))
                            safe.attr("integrity", link.attr("integrity"))
                                    .attr("crossorigin", "anonymous");
                        link.before(safe);
                        externalStyles.put(safe, external);
                    } else warnings.add("阻止外部样式：" + href);
                }
            }
            link.remove();
        }
        for (var element : doc.getAllElements()) {
            for (var attr : new ArrayList<>(element.attributes().asList())) {
                String key = attr.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("on")
                        || List.of(
                                        "src",
                                        "srcset",
                                        "href",
                                        "action",
                                        "formaction",
                                        "background",
                                        "ping",
                                        "poster",
                                        "srcdoc",
                                        "data",
                                        "target",
                                        "autofocus")
                                .contains(key)) element.removeAttr(attr.getKey());
            }
        }
        imageSources.forEach(
                (image, value) ->
                        image.attr(
                                "src",
                                svgImages.containsKey(image)
                                        ? svgImages.get(image)
                                        : asset(code, version, path, value, budget, warnings)));
        externalStyles.forEach((link, href) -> link.attr("href", href));
        doc.head().prependElement("meta").attr("name", "referrer").attr("content", "no-referrer");
        doc.head()
                .prependElement("meta")
                .attr("http-equiv", "Content-Security-Policy")
                .attr("content", policy());
        warnings.add("静态隔离预览：浏览器仅可加载允许域的 HTTPS 样式和字体，脚本、表单、嵌套页面及媒体播放禁用。");
        return new Preview(doc.outerHtml(), warnings.stream().distinct().limit(100).toList());
    }

    private Set<Element> restoreScriptPresentation(
            Document doc,
            String code,
            String version,
            String path,
            int[] budget,
            List<String> warnings) {
        Set<Element> staticSvgs = Collections.newSetFromMap(new IdentityHashMap<>());
        var candidates = new LinkedHashMap<String, Element>();
        for (var element : doc.body().children()) {
            for (String name : element.classNames()) {
                if (INTRO_NAME.matcher(name).matches()) candidates.put("." + name, element);
            }
            if (INTRO_NAME.matcher(element.id()).matches())
                candidates.put("#" + element.id(), element);
        }
        int remaining = MAX_RESOURCE;
        var scripts = doc.select("script");
        for (var script : scripts.reversed()) {
            String content = script.data();
            if (script.hasAttr("src")) {
                String source = localPath(path, script.attr("src").strip());
                if (remaining <= 0
                        || source == null
                        || !source.toLowerCase(Locale.ROOT).endsWith(".js")) continue;
                try {
                    byte[] bytes = storage.readInspectionResource(code, version, source);
                    remaining -= bytes.length;
                    if (remaining < 0) continue;
                    content = new String(bytes, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    // 缺失脚本不参与入场遮罩识别。
                    continue;
                }
            }
            restoreStaticSvg(doc, content, staticSvgs, budget, warnings);
            if (candidates.isEmpty()) continue;
            var matcher = INTRO_REMOVE.matcher(content);
            while (matcher.find()) {
                String selector = matcher.group("selector");
                if (selector == null) continue;
                var candidate = candidates.remove(selector);
                if (candidate == null || doc.select(selector).size() != 1) continue;
                candidate.attr("style", candidate.attr("style") + ";display:none!important");
                warnings.add("隔离预览跳过入场遮罩：" + selector);
            }
        }
        return staticSvgs;
    }

    private void restoreStaticSvg(
            Document doc,
            String script,
            Set<Element> staticSvgs,
            int[] budget,
            List<String> warnings) {
        var insertion = StaticScriptLiterals.svgInsertion(script);
        if (insertion == null) return;
        var nodes = Jsoup.parseBodyFragment(insertion.markup()).body();
        if (nodes.childNodeSize() != 1 || nodes.childrenSize() != 1) return;
        var original = nodes.child(0);
        if (!original.normalName().equals("svg")
                || original.childrenSize() == 0
                || !SVG_VIEW_BOX.matcher(original.attr("viewBox")).matches()) return;
        // 重建几何元素以保留页面CSS着色。原始标签和属性不进入预览文档。
        var svg =
                new Element("svg")
                        .attr("xmlns", "http://www.w3.org/2000/svg")
                        .attr("viewBox", original.attr("viewBox"));
        for (var path : original.children()) {
            String d = path.attr("d");
            if (!path.normalName().equals("path")
                    || path.childNodeSize() != 0
                    || !SVG_PATH.matcher(d).matches()) return;
            var safePath = svg.appendElement("path").attr("d", d);
            String fill = path.attr("fill");
            if (SVG_FILL.matcher(fill).matches()) safePath.attr("fill", fill);
        }
        int bytes = svg.outerHtml().getBytes(StandardCharsets.UTF_8).length;
        boolean restored = false;
        for (var target : doc.select(insertion.selector())) {
            if (!List.of("div", "span", "a").contains(target.normalName())
                    || target.childrenSize() != 0
                    || !target.ownText().isBlank()) continue;
            if (!charge(bytes, budget, warnings)) break;
            var copy = svg.clone();
            target.appendChild(copy);
            staticSvgs.add(copy);
            restored = true;
        }
        if (restored) warnings.add("隔离预览补齐静态矢量图形：" + insertion.selector());
    }

    private void addBindingSources(Document doc, Map<String, String> selectorMap) {
        doc.select("[" + BINDING_SOURCES + "]").removeAttr(BINDING_SOURCES);
        var anchors = new IdentityHashMap<Element, List<String>>();
        for (var entry : selectorMap.entrySet()) {
            String selector = entry.getValue();
            if (selector == null || selector.isBlank()) continue;
            try {
                var matches = doc.select(selector);
                if (matches.size() == 1) {
                    anchors.computeIfAbsent(matches.getFirst(), node -> new ArrayList<>())
                            .add(entry.getKey());
                }
            } catch (Selector.SelectorParseException ignored) {
                continue;
            }
        }
        anchors.forEach((element, ids) -> element.attr(BINDING_SOURCES, String.join(" ", ids)));
    }

    private String css(
            String code,
            String version,
            String owner,
            String text,
            int[] budget,
            List<String> warnings) {
        return css(code, version, owner, text, budget, warnings, new ArrayDeque<>());
    }

    private String css(
            String code,
            String version,
            String owner,
            String text,
            int[] budget,
            List<String> warnings,
            ArrayDeque<String> imports) {
        var matcher = CSS_RESOURCE.matcher(text);
        var out = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group("url");
            if (url != null) {
                matcher.appendReplacement(
                        out,
                        Matcher.quoteReplacement(
                                "url(\""
                                        + cssAsset(
                                                code,
                                                version,
                                                owner,
                                                unquote(url),
                                                budget,
                                                warnings)
                                        + "\")"));
                continue;
            }
            String imported = matcher.group("importUrl");
            if (imported == null) imported = matcher.group("importString");
            if (imported == null) continue;
            String value = unquote(imported);
            String target = localPath(owner, value);
            String reference = externalStyleUrl(value);
            if (target != null) {
                String nested = readStylesheet(code, version, target, budget, warnings, imports);
                if (nested != null) {
                    byte[] data = nested.getBytes(StandardCharsets.UTF_8);
                    if (charge(data.length, budget, warnings))
                        reference = dataUrl("text/css;charset=UTF-8", data);
                }
            } else if (reference == null) {
                warnings.add("阻止外部样式：" + value);
            }
            String rule =
                    reference == null
                            ? ""
                            : "@import url(\""
                                    + reference
                                    + "\")"
                                    + matcher.group("condition")
                                    + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(rule));
        }
        matcher.appendTail(out);
        return STYLE_END
                .matcher(out)
                .replaceAll(
                        match -> Matcher.quoteReplacement("\\3c " + match.group().substring(1)));
    }

    private String readStylesheet(
            String code,
            String version,
            String target,
            int[] budget,
            List<String> warnings,
            ArrayDeque<String> imports) {
        if (!target.toLowerCase(Locale.ROOT).endsWith(".css")) {
            warnings.add("预览样式目标不是 CSS 文件：" + target);
            return null;
        }
        if (imports.size() >= MAX_CSS_IMPORT_DEPTH || imports.contains(target)) {
            warnings.add("样式导入存在循环或超过层数限制：" + target);
            return null;
        }
        byte[] data = read(code, version, target, budget, warnings);
        if (data == null) return null;
        imports.push(target);
        try {
            return css(
                    code,
                    version,
                    target,
                    new String(data, StandardCharsets.UTF_8),
                    budget,
                    warnings,
                    imports);
        } finally {
            imports.pop();
        }
    }

    private static String unquote(String value) {
        return value.startsWith("\"") || value.startsWith("'")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private String externalStyleUrl(String value) {
        try {
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && styleHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)) return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            // 无效 URL 不保留。
        }
        return null;
    }

    private String cssAsset(
            String code,
            String version,
            String owner,
            String value,
            int[] budget,
            List<String> warnings) {
        String external = externalStyleUrl(value);
        if (external != null && fontMime(URI.create(external).getPath()) != null) return external;
        return asset(code, version, owner, value, budget, warnings);
    }

    private static String fontMime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        return null;
    }

    private String asset(
            String code,
            String version,
            String owner,
            String value,
            int[] budget,
            List<String> warnings) {
        value = value.strip();
        int comma = value.indexOf(',');
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            if (comma > 0
                    && EMBEDDED_BITMAP.matcher(value.substring(0, comma)).matches()
                    && value.length() - comma - 1 <= 4 * ((MAX_RESOURCE + 2) / 3)) {
                try {
                    byte[] data = Base64.getDecoder().decode(value.substring(comma + 1));
                    if (data.length > 0 && charge(data.length, budget, warnings)) return value;
                } catch (IllegalArgumentException ignored) {
                    // 非法 Base64 资源不保留。
                }
            }
            warnings.add("阻止无效或超限的内联图片");
            return "data:,";
        }
        String path =
                value.startsWith("assets:/upload/") || value.startsWith("assets:/global/")
                        ? value.split("[?#]", 2)[0]
                        : localPath(owner, value);
        if (path == null) {
            if (!value.isBlank()) warnings.add("阻止外部或无效资源：" + value);
            return "data:,";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String mime =
                lower.endsWith(".png")
                        ? "image/png"
                        : lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                                ? "image/jpeg"
                                : lower.endsWith(".gif")
                                        ? "image/gif"
                                        : lower.endsWith(".webp")
                                                ? "image/webp"
                                                : lower.endsWith(".svg")
                                                        ? "image/svg+xml"
                                                        : fontMime(lower);
        if (mime == null) {
            warnings.add("预览不支持的资源：" + path);
            return "data:,";
        }
        byte[] data = read(code, version, path, budget, warnings);
        return data == null ? "data:," : dataUrl(mime, data);
    }

    private static String dataUrl(String mime, byte[] data) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    private static boolean charge(int length, int[] budget, List<String> warnings) {
        if (length > MAX_RESOURCE) {
            warnings.add("资源超过 2 MB 预览限制");
            return false;
        }
        budget[0] -= length;
        if (budget[0] >= 0) return true;
        warnings.add("预览资源累计超过 12 MB");
        return false;
    }

    private byte[] read(
            String code, String version, String path, int[] budget, List<String> warnings) {
        if (budget[0] <= 0) {
            warnings.add("预览资源累计超过 12 MB，后续资源省略");
            return null;
        }
        try {
            byte[] data =
                    path.startsWith("assets:/")
                            ? storage.readInspectionAsset(code, path)
                            : storage.readInspectionResource(code, version, path);
            return charge(data.length, budget, warnings) ? data : null;
        } catch (IllegalArgumentException e) {
            warnings.add(e.getMessage());
            return null;
        }
    }

    private String localPath(String owner, String value) {
        if (value != null && value.startsWith("ui:/")) value = "/" + value.substring(4);
        if (value == null
                || value.isBlank()
                || value.startsWith("//")
                || value.contains(":")
                || value.contains("\\")
                || value.indexOf(0) >= 0) return null;
        try {
            String clean = value.split("[?#]", 2)[0];
            if (clean.isBlank()) return null;
            Path parent = Path.of(owner).getParent();
            Path resolved =
                    (clean.startsWith("/")
                                    ? Path.of(clean.substring(1))
                                    : (parent == null ? Path.of(clean) : parent.resolve(clean)))
                            .normalize();
            String result = resolved.toString().replace('\\', '/');
            return resolved.isAbsolute() || result.startsWith("..") ? null : result;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}

package com.xinhongru.cms.siteservice;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/** 在已审的站点、页面包和正式页面位置应用公开入口的处理决定。 */
public final class RetainedNavigationText {
    private static final String QUOTED_ATTRIBUTE = "(?:\"[^\"]*\"|'[^']*')";
    private static final String PRESENTATION_ATTRIBUTE =
            "(?:\\s+(?:class|target)\\s*=\\s*" + QUOTED_ATTRIBUTE + ")*";
    private static final Pattern INTERNAL_LINK = Pattern.compile("(?s)<a(" + PRESENTATION_ATTRIBUTE
            + "\\s+href\\s*=\\s*)" + QUOTED_ATTRIBUTE + "(" + PRESENTATION_ATTRIBUTE + "\\s*>)");
    private final JsonNode releases;
    private final Path publishedRoot;
    private static final Pattern INTERNAL_DESTINATION = Pattern.compile(
            "((?:/[A-Za-z0-9_-][A-Za-z0-9._~-]*)+)(?:#([A-Za-z0-9][A-Za-z0-9_-]{0,127}))?");
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

    public RetainedNavigationText(Path packageRoot, String projectCode) {
        this(packageRoot, projectCode, null);
    }

    public RetainedNavigationText(Path packageRoot, String projectCode, Path publishedRoot) {
        Path config = packageRoot.resolve(".retained-navigation-text.json");
        releases = Files.isRegularFile(config)
                ? new ObjectMapper().readTree(config.toFile()).path(projectCode)
                : MissingNode.getInstance();
        this.publishedRoot = publishedRoot == null ? null : publishedRoot.toAbsolutePath().normalize();
    }

    boolean configured() {
        return releases.isObject() && !releases.isEmpty();
    }

    String apply(String html, Path activePackage, String route) throws IOException {
        JsonNode rules = releases.path(activePackage.toRealPath().getFileName().toString());
        if (!rules.isArray()) return html;
        var doc = Parser.htmlParser().setTrackPosition(true).parseInput(html, "");
        var edits = new ArrayList<Edit>();
        var anchors = new HashMap<String, Set<String>>();
        for (JsonNode rule : rules) {
            if (!approvedRoute(rule, route)) continue;
            Element anchor = anchor(doc, rule);
            if (anchor == null || !anchor.attr("href").equals(rule.path("href").asString())
                    || anchor.attributes().asList().stream().anyMatch(attribute -> {
                        String name = attribute.getKey();
                        return name.startsWith("on") || name.startsWith("data-")
                                || name.equals("role") || name.equals("tabindex");
                    })) continue;
            var range = anchor.sourceRange();
            if (!range.isTracked()) continue;
            var start = range.start().pos();
            var end = range.end().pos();
            String tag = html.substring(start, end);
            if (rule.has("internalHref") && !rule.path("removeWrapper").asBoolean()) {
                var match = INTERNAL_LINK.matcher(tag);
                var target = anchor.attr("target");
                var href = destination(rule, anchors);
                if (href != null && (!anchor.hasAttr("target") || target.equals("_self") || target.equals("_blank"))
                        && match.matches())
                    edits.add(new Edit(start, end, "<a" + match.group(1) + "\"" + href + "\"" + match.group(2)));
                continue;
            }
            if (!tag.matches("(?s)<a\\s+href=(\"[^\"]*\"|'[^']*')\\s*>")) continue;
            if (rule.path("removeWrapper").asBoolean()) {
                Element wrapper = anchor.parent();
                var open = wrapper.sourceRange();
                var close = wrapper.endSourceRange();
                int attributes = wrapper.attributes().asList().size();
                boolean removable = wrapper.is("div") && attributes == 1 && wrapper.hasAttr("class")
                        || wrapper.is("dd") && attributes == 0;
                if (removable
                        && wrapper.childNodes().stream().allMatch(node -> node == anchor
                                || node instanceof TextNode text && text.isBlank())
                        && anchor.childrenSize() == 0 && open.isTracked() && close.isTracked()) {
                    edits.add(new Edit(open.start().pos(), close.end().pos(), ""));
                }
            } else if (rule.has("externalHref") || rule.has("internalHref")) {
                String href = destination(rule, anchors);
                if (href != null) edits.add(new Edit(start, end, "<a href=\"" + href + "\">"));
            } else edits.add(new Edit(start, end, "<a aria-disabled=\"true\">"));
        }
        var result = new StringBuilder(html);
        edits.stream().distinct().sorted(Comparator.comparingInt(Edit::start).reversed())
                .forEach(edit -> result.replace(edit.start(), edit.end(), edit.value()));
        return result.toString();
    }

    public String mismatch(String version, String route, String html) {
        JsonNode rules = releases.path(version);
        if (!rules.isArray()) return null;
        var doc = Parser.parse(html, "");
        var anchors = new HashMap<String, Set<String>>();
        for (JsonNode rule : rules) {
            if (!approvedRoute(rule, route)) continue;
            Element anchor = anchor(doc, rule);
            if (rule.path("removeWrapper").asBoolean()) {
                if (!doc.select(rule.path("selector").asString()).isEmpty())
                    return "请重新生成并核对已审核撤下的占位入口「" + rule.path("label").asString() + "」";
                continue;
            }
            if (rule.has("externalHref") || rule.has("internalHref")) {
                String href = destination(rule, anchors);
                if (href == null || anchor == null || !href.equals(anchor.attr("href")))
                    return "请重新生成并核对已审核导航地址「" + rule.path("label").asString() + "」";
                continue;
            }
            if (anchor == null || anchor.hasAttr("href") || anchor.hasAttr("tabindex")
                    || anchor.hasAttr("role") || anchor.hasAttr("onclick")
                    || anchor.hasAttr("data-src") || anchor.parent().hasAttr("data-src")) {
                return "请重新生成并核对已审核保留文字「" + rule.path("label").asString() + "」";
            }
        }
        return null;
    }

    public boolean matchesInternalOverride(
            String version, String route, Element document, Element link, String approvedHref) {
        JsonNode rules = releases.path(version);
        if (!rules.isArray()) return false;
        var anchors = new HashMap<String, Set<String>>();
        for (JsonNode rule : rules) {
            if (!rule.has("internalHref") || !approvedRoute(rule, route)
                    || !approvedHref.equals(rule.path("href").asString())) continue;
            String href = destination(rule, anchors);
            if (href != null && href.equals(link.attr("href")) && link == anchor(document, rule)) return true;
        }
        return false;
    }

    public List<String> reviewRoutes(String version, Integer menuId, String label) {
        var rules = releases.path(version);
        if (menuId == null || !rules.isArray()) return List.of();
        var routes = new ArrayList<String>();
        for (JsonNode rule : rules) {
            if (rule.path("menuId").asInt() != menuId || !label.equals(rule.path("label").asString())) continue;
            for (JsonNode route : rule.path("routes")) {
                String path = route.asString();
                if (path.startsWith("/") && approvedRoute(rule, path)) routes.add(path);
            }
        }
        return routes.stream().distinct().toList();
    }

    public String reviewedExternalDestination(String version, Integer menuId, String label) {
        var rules = releases.path(version);
        if (menuId == null || !rules.isArray()) return null;
        var destinations = new java.util.HashSet<String>();
        for (JsonNode rule : rules) {
            if (rule.path("menuId").asInt() == menuId && label.equals(rule.path("label").asString()))
                destinations.add(externalHref(rule));
        }
        return destinations.size() == 1 ? destinations.iterator().next() : null;
    }

    public boolean internalDestinationMismatch(String version, Integer menuId, String label, String route) {
        var rules = releases.path(version);
        if (menuId == null || !rules.isArray()) return false;
        var anchors = new HashMap<String, Set<String>>();
        for (JsonNode rule : rules) {
            if (rule.path("menuId").asInt() != menuId || !label.equals(rule.path("label").asString())
                    || !rule.has("internalHref")) continue;
            String href = destination(rule, anchors);
            if (href == null || !href.equals(route)) return true;
        }
        return false;
    }

    private String destination(JsonNode rule, Map<String, Set<String>> anchors) {
        if (!rule.has("internalHref")) return externalHref(rule);
        String href = rule.path("internalHref").asString();
        var target = INTERNAL_DESTINATION.matcher(href);
        if (rule.has("externalHref") || !target.matches()) return null;
        String fragment = target.group(2);
        return fragment == null || anchors.computeIfAbsent(target.group(1), this::publishedAnchors).contains(fragment)
                ? href : null;
    }

    private Set<String> publishedAnchors(String path) {
        if (publishedRoot == null) return Set.of();
        Path file = publishedRoot.resolve(path.substring(1)).normalize();
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        try {
            if (!file.startsWith(publishedRoot)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(publishedRoot.toRealPath())
                    || Files.size(file) > MAX_HTML_BYTES) return Set.of();
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(MAX_HTML_BYTES + 1);
                if (bytes.length > MAX_HTML_BYTES) return Set.of();
                var unique = new HashSet<String>();
                var duplicates = new HashSet<String>();
                for (var element : Parser.parse(new String(bytes, StandardCharsets.UTF_8), "").select("[id]")) {
                    String id = element.id();
                    if (!unique.add(id)) duplicates.add(id);
                }
                unique.removeAll(duplicates);
                return unique;
            }
        } catch (IOException inaccessible) {
            return Set.of();
        }
    }

    private static String externalHref(JsonNode rule) {
        String href = rule.path("externalHref").asString();
        if (!href.matches("https://[A-Za-z0-9.-]+(?:/[A-Za-z0-9._~-]+)+")) return null;
        URI uri = URI.create(href);
        return uri.getHost() == null ? null : href;
    }

    private static boolean approvedRoute(JsonNode rule, String route) {
        if (!rule.path("reason").isString() || rule.path("reason").asString().isBlank()) return false;
        return containsRoute(rule.path("routes"), route) || containsRoute(rule.path("renderAliases"), route);
    }

    private static boolean containsRoute(JsonNode paths, String route) {
        for (JsonNode path : paths) if (path.asString().equals(route)) return true;
        return false;
    }

    private static Element anchor(Element doc, JsonNode rule) {
        String selector = rule.path("selector").asString();
        String label = rule.path("label").asString();
        if (selector.isBlank() || label.isBlank()) return null;
        if (rule.path("removeWrapper").asBoolean()) selector += " > a";
        String imageSrc = rule.path("imageSrc").asString();
        if (rule.has("icon")) {
            JsonNode icon = rule.path("icon");
            String tag = icon.path("tag").asString();
            String cssClass = icon.path("class").asString();
            if (!rule.has("internalHref") || rule.has("externalHref") || !imageSrc.isEmpty()
                    || rule.path("removeWrapper").asBoolean() || !tag.matches("i|span")
                    || !cssClass.matches("[A-Za-z_][A-Za-z0-9_-]*")) return null;
            List<Element> icons = doc.select(selector).stream()
                    .filter(element -> element.is("a") && element.text().isBlank()
                            && element.childrenSize() == 1)
                    .filter(element -> {
                        Element child = element.child(0);
                        return child.is(tag) && child.childNodeSize() == 0
                                && child.attributes().asList().size() == 1 && child.attr("class").equals(cssClass);
                    }).toList();
            return icons.size() == 1 ? icons.getFirst() : null;
        }
        if (!imageSrc.isEmpty()) {
            if (!rule.has("internalHref") || rule.has("externalHref")
                    || rule.path("removeWrapper").asBoolean()
                    || !imageSrc.matches("(?:/[A-Za-z0-9_-][A-Za-z0-9._~-]*)+")) return null;
            String imageOrigin = rule.path("imageOrigin").asString();
            String absoluteImage = imageOrigin.matches("https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")
                    ? imageOrigin + imageSrc : imageSrc;
            List<Element> images = doc.select(selector).stream()
                    .filter(element -> element.is("a") && element.text().isBlank()
                            && element.childrenSize() == 1 && element.child(0).is("img")
                            && (imageSrc.equals(element.child(0).attr("src"))
                                    || absoluteImage.equals(element.child(0).attr("src")))).toList();
            return images.size() == 1 ? images.getFirst() : null;
        }
        List<Element> anchors = doc.select(selector).stream()
                .filter(element -> element.is("a") && element.text().equals(label)).toList();
        return anchors.size() == 1 ? anchors.getFirst() : null;
    }

    private record Edit(int start, int end, String value) {}
}

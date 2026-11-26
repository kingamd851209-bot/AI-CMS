package com.xinhongru.cms.siteservice;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 在成功渲染的原生页面中省略经过交付审核、原包仍缺件的可选图标声明。 */
final class MissingIconLinks {
    private static final Set<String> METADATA =
            Set.of("base", "link", "meta", "script", "style", "title", "template", "noscript");
    private static final Pattern ICON_PATH = Pattern.compile(
            "(?:/[a-zA-Z0-9_-][a-zA-Z0-9_.-]*)+\\.(?i:ico|png|svg|gif|webp|jpg|jpeg|avif)");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([^\\s=<>/]+)(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>]+))?");
    private static final Set<Set<String>> RELATIONS = Set.of(
            Set.of("icon"), Set.of("shortcut", "icon"), Set.of("apple-touch-icon"),
            Set.of("apple-touch-icon-precomposed"), Set.of("mask-icon"), Set.of("bookmark"));
    private final JsonNode releases;

    MissingIconLinks(Path packageRoot, String projectCode) {
        Path config = packageRoot.resolve(".icon-omissions.json");
        if (Files.notExists(config, LinkOption.NOFOLLOW_LINKS)) {
            releases = MissingNode.getInstance();
        } else {
            releases = new ObjectMapper().readTree(config.toFile()).path(projectCode);
        }
    }

    boolean configured() {
        return releases.isObject() && !releases.isEmpty();
    }

    String apply(String html, Path activePackage, List<String> origins) throws IOException {
        Path versionRoot = activePackage.toRealPath();
        JsonNode paths = releases.path(versionRoot.getFileName().toString());
        if (!paths.isObject() || paths.isEmpty()) return html;
        var doc = Parser.htmlParser().setTrackPosition(true).parseInput(html, "");
        if (doc.selectFirst("base[href]") != null) return html;
        var metadata = new ArrayList<>(doc.head().children());
        for (var node : doc.body().childNodes()) {
            if (node instanceof Element element) {
                if (!METADATA.contains(element.normalName())) break;
                metadata.add(element);
            } else if (node instanceof TextNode text && !text.getWholeText().replace("\uFEFF", "").isBlank()) {
                break;
            }
        }
        var ranges = new ArrayList<int[]>();
        for (Element link : metadata) {
            if (!link.normalName().equals("link")) continue;
            String href = link.attr("href");
            String path = href;
            for (String origin : origins) {
                if (href.startsWith(origin + "/")) {
                    path = href.substring(origin.length());
                    break;
                }
            }
            if (!paths.path(path).isString() || paths.path(path).asString().isBlank()
                    || !ICON_PATH.matcher(path).matches()
                    || path.startsWith("/assets/global/") || path.startsWith("/assets/upload/")
                    || path.startsWith("/assets/vendor/")) continue;
            var rel = Set.copyOf(List.of(link.attr("rel").toLowerCase(Locale.ROOT).trim().split("\\s+")));
            if (!RELATIONS.contains(rel) || rel.contains("bookmark")
                    && !path.toLowerCase(Locale.ROOT).endsWith(".ico")) continue;
            if (!Files.notExists(versionRoot.resolve(path.substring(1)), LinkOption.NOFOLLOW_LINKS)) continue;
            var range = link.sourceRange();
            if (!range.isTracked()) continue;
            int start = range.start().pos();
            int end = range.end().pos();
            String tag = html.substring(start, end);
            if (hasUniqueAttributes(tag)) ranges.add(new int[] {start, end});
        }
        ranges.sort(Comparator.comparingInt((int[] range) -> range[0]).reversed());
        var result = new StringBuilder(html);
        for (int[] range : ranges) {
            String newlines = html.substring(range[0], range[1]).replaceAll("[^\\r\\n]", "");
            result.replace(range[0], range[1], newlines);
        }
        return result.toString();
    }

    private static boolean hasUniqueAttributes(String tag) {
        var attributes = ATTRIBUTE.matcher(tag).region("<link".length(), tag.length());
        var names = new HashSet<String>();
        while (attributes.find()) {
            if (!names.add(attributes.group(1).toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }
}

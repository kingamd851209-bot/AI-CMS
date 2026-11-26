package com.xinhongru.site.storage;

import org.jsoup.Jsoup;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 将固定路径分页限制在发布目录与已配置的分页路由内。 */
public final class StaticPaginationFiles {
    private StaticPaginationFiles() {}

    public static Pattern pagePattern(String route) {
        if (!route.startsWith("/")) route = "/" + route;
        return Arrays.stream(route.split("\\{pageIndex}", -1))
                .map(Pattern::quote)
                .collect(Collectors.collectingAndThen(
                        Collectors.joining("[1-9][0-9]*"), Pattern::compile));
    }

    public static List<Path> existingFiles(Path root, String route) throws IOException {
        if (route == null || !route.contains("{pageIndex}")) return List.of();
        String marker = "{pageIndex}";
        URI uri;
        try {
            uri = URI.create(route.replace(marker, "%7BpageIndex%7D"));
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
        String decoded = uri.getPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || decoded == null || decoded.startsWith("//")
                || decoded.contains("\\") || Arrays.stream(decoded.split("/"))
                        .anyMatch(part -> part.equals(".") || part.equals(".."))) return List.of();
        String local = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        String output = local.endsWith("/") ? local + "index.html" : local;
        if (!output.endsWith(".html")) return List.of();
        int parentEnd = output.lastIndexOf('/', output.indexOf(marker));
        Path parent = parentEnd < 0 ? root : root.resolve(output.substring(0, parentEnd));
        if (!containsDirectory(root, parent)) return List.of();
        int depth = output.substring(parentEnd + 1).split("/").length;
        Pattern pattern = pagePattern(output);
        try (var paths = Files.find(parent, depth, (file, attributes) ->
                attributes.isRegularFile()
                        && pattern.matcher("/" + root.relativize(file).toString().replace('\\', '/')).matches())) {
            return paths.sorted().toList();
        }
    }

    public static boolean isPublishedFile(Path root, Path file) throws IOException {
        return file.startsWith(root) && containsDirectory(root, file.getParent())
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && file.toRealPath().startsWith(root.toRealPath());
    }

    public static Path outputPath(Path root, String route) {
        try {
            URI uri = URI.create(route);
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || path == null || path.startsWith("//")
                    || path.matches("(?s).*[<>:\"|?*\\\\\\p{Cntrl}].*")
                    || Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".") || part.equals("..")))
                return null;
            String relative = path.startsWith("/") ? path.substring(1) : path;
            return root.resolve(relative.isEmpty() || path.endsWith("/") ? relative + "index.html" : relative);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 没有渲染回执的旧发布文件，靠原有分页链接证明其归属。 */
    public static Set<Path> linkedFiles(Path root, Path base, List<Path> candidates, String selector)
            throws IOException {
        var known = candidates.stream().collect(Collectors.toMap(
                path -> root.relativize(path).toString().replace('\\', '/'), path -> path));
        var linked = new HashSet<Path>();
        if (!isPublishedFile(root, base)) return linked;
        var pending = new ArrayDeque<Path>();
        pending.add(base);
        linked.add(base);
        while (!pending.isEmpty()) {
            Path file = pending.removeFirst();
            if (!isPublishedFile(root, file)) continue;
            URI current = URI.create("http://cms-static.invalid" + UriUtils.encodePath(
                    "/" + root.relativize(file).toString().replace('\\', '/'), StandardCharsets.UTF_8));
            for (var element : Jsoup.parse(Files.readString(file)).select(selector)) {
                URI target;
                try {
                    target = current.resolve(element.attr("href"));
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                String path = target.getPath();
                if (!current.getRawAuthority().equals(target.getRawAuthority())
                        || path == null || !path.startsWith("/") || path.startsWith("//")
                        || path.contains("\\")
                        || target.getRawQuery() != null && !"__cms_filter=1".equals(target.getRawQuery()))
                    continue;
                String output = path.substring(1);
                Path next = known.get(output.endsWith("/") ? output + "index.html" : output);
                if (next != null && linked.add(next)) pending.add(next);
            }
        }
        return linked;
    }

    private static boolean containsDirectory(Path root, Path directory) throws IOException {
        if (!directory.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return false;
        }
        return directory.toRealPath().startsWith(root.toRealPath());
    }
}

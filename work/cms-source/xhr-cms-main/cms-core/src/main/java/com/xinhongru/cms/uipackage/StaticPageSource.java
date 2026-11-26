package com.xinhongru.cms.uipackage;

import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 仅展开同一包内静态HTML片段，不运行ASP、SSI命令或HTTP服务。 */
public final class StaticPageSource {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_INCLUDES = 100;
    private static final int MAX_READER_FILES = 512;
    private static final int MAX_READER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_VALIDATED_DIGESTS = 2048;
    private static final Pattern SSI_START = Pattern.compile("<!--\\s*#", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSI =
            Pattern.compile("<!--\\s*#([\\s\\S]*?)-->", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE =
            Pattern.compile("(?is)^include\\s+(file|virtual)\\s*=\\s*([\"'])(.*?)\\2\\s*$");

    private StaticPageSource() {}

    public record Page(
            String html, String digest, List<String> includedPaths, List<String> warnings) {}

    public static boolean supports(String path) {
        if (path == null) return false;
        String name = path.toLowerCase(Locale.ROOT);
        return name.endsWith(".html")
                || name.endsWith(".htm")
                || name.endsWith(".aspx")
                || name.endsWith(".asp");
    }

    /** base必须是当前站点已验证的active/version真实根目录。 */
    public static Page read(Path base, String sourcePath) {
        try {
            return new Reader(base.toRealPath(), null).read(sourcePath);
        } catch (IOException e) {
            throw new IllegalArgumentException("静态页面或包含文件不存在/不可读取", e);
        }
    }

    static Reader open(Path realBase, ContentValidationCache safeContent) {
        return new Reader(realBase, safeContent);
    }

    /** 一轮取证内使用。下一轮重新读取并验证实际路径和文件内容。 */
    public static final class Reader {
        private final Path base;
        private final ContentValidationCache safeContent;
        private final LinkedHashMap<Path, RawPage> sources = new LinkedHashMap<>(16, 0.75f, true);
        private int cachedBytes;

        private Reader(Path base, ContentValidationCache safeContent) {
            this.base = base;
            this.safeContent = safeContent;
        }

        public Page read(String sourcePath) {
            try {
                var state = new State(this);
                Path source = state.resolve(base, sourcePath, false);
                String html = state.expand(source, 0);
                String digest = state.includeCount == 0 ? state.sourceDigest : digest(html);
                // 展开可能在片段交界处形成新的服务器代码或 SSI 指令。
                if (state.includeCount > 0) {
                    validateServerCode(html, digest);
                    rejectSsi(html);
                }
                var paths = List.copyOf(state.included);
                var warnings = new ArrayList<String>();
                if (!paths.isEmpty())
                    warnings.add("仅展开本包内静态HTML片段，未执行服务器代码：" + String.join("、", paths));
                return new Page(html, digest, paths, List.copyOf(warnings));
            } catch (IOException e) {
                throw new IllegalArgumentException("静态页面或包含文件不存在/不可读取", e);
            }
        }

        private void validateServerCode(String html, String digest) {
            if (safeContent == null) rejectServerCode(html);
            else safeContent.validate(html, digest);
        }

        private RawPage raw(Path path, int remainingBytes) throws IOException {
            var cached = sources.get(path);
            if (cached != null) {
                if (cached.bytes() > remainingBytes)
                    throw new IllegalArgumentException("静态页面单文件或累计读取超过2MB");
                return cached;
            }
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(remainingBytes + 1);
            }
            if (bytes.length > remainingBytes)
                throw new IllegalArgumentException("静态页面单文件或累计读取超过2MB");
            String html = new String(bytes, StandardCharsets.UTF_8);
            String digest = digest(html);
            validateServerCode(html, digest);
            var result = new RawPage(html, digest, bytes.length);
            if (safeContent != null) {
                sources.put(path, result);
                cachedBytes += bytes.length;
                while (sources.size() > MAX_READER_FILES || cachedBytes > MAX_READER_BYTES)
                    cachedBytes -= sources.pollFirstEntry().getValue().bytes();
            }
            return result;
        }
    }

    private record RawPage(String html, String digest, int bytes) {}

    /** 跨读取轮次只保留已通过 DOM 安全校验的内容摘要。 */
    static final class ContentValidationCache {
        private final LinkedHashMap<String, Boolean> digests = new LinkedHashMap<>(16, 0.75f, true);

        void validate(String html, String digest) {
            synchronized (digests) {
                if (digests.get(digest) != null) return;
            }
            rejectServerCode(html);
            synchronized (digests) {
                digests.put(digest, true);
                if (digests.size() > MAX_VALIDATED_DIGESTS) digests.pollFirstEntry();
            }
        }
    }

    public static void rejectServerCode(String html) {
        if (html.contains("<%")) throw new IllegalArgumentException("不支持ASP服务器代码");
        for (var element : Jsoup.parse(html).getAllElements()) {
            if (element.tagName().toLowerCase(Locale.ROOT).startsWith("asp:")
                    || element.attr("runat").trim().equalsIgnoreCase("server"))
                throw new IllegalArgumentException("不支持runat=server服务器控件或脚本");
        }
    }

    public static void rejectUnexpanded(String html) {
        rejectServerCode(html);
        rejectSsi(html);
    }

    private static void rejectSsi(String html) {
        if (SSI_START.matcher(html).find())
            throw new IllegalArgumentException("静态HTML仍含未处理或不支持的服务器指令");
    }

    private static String digest(String html) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(html.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class State {
        private final Reader reader;
        private final Path base;
        private String sourceDigest;
        private int totalBytes;
        private int includeCount;
        private final ArrayDeque<Path> stack = new ArrayDeque<>();
        private final LinkedHashSet<String> included = new LinkedHashSet<>();

        private State(Reader reader) {
            this.reader = reader;
            this.base = reader.base;
        }

        private Path resolve(Path directory, String value, boolean virtual) throws IOException {
            if (value == null
                    || value.isBlank()
                    || value.indexOf(0) >= 0
                    || value.contains("\\")
                    || value.contains(":")
                    || value.contains("?")
                    || value.contains("#")
                    || value.startsWith("//")) throw new IllegalArgumentException("静态页面/包含路径无效");
            if (!virtual && value.startsWith("/"))
                throw new IllegalArgumentException("file包含不允许绝对路径");
            String relative = virtual && value.startsWith("/") ? value.substring(1) : value;
            Path candidate = (virtual ? base : directory).resolve(relative).normalize();
            if (!candidate.startsWith(base) || !supports(candidate.toString()))
                throw new IllegalArgumentException("包含文件越出当前UI包或不是静态HTML：" + value);
            if (reader.sources.containsKey(candidate)) return candidate;
            Path cursor = base;
            for (Path component : base.relativize(candidate)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor))
                    throw new IllegalArgumentException("静态页面/包含路径不允许符号链接或目录重定向：" + value);
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("静态页面/包含目标不是普通文件：" + value);
            return candidate;
        }

        private String expand(Path path, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("静态包含深度超过8层");
            if (stack.contains(path))
                throw new IllegalArgumentException("静态包含存在循环：" + relative(path));
            var source = reader.raw(path, MAX_BYTES - totalBytes);
            totalBytes += source.bytes();
            if (depth == 0) sourceDigest = source.digest();
            String raw = source.html();
            if (!raw.contains("<!--")) return raw;
            stack.push(path);
            try {
                var matches = SSI.matcher(raw);
                var output = new StringBuilder();
                int end = 0;
                while (matches.find()) {
                    String prefix = raw.substring(end, matches.start());
                    if (SSI_START.matcher(prefix).find())
                        throw new IllegalArgumentException("静态包含指令未闭合");
                    output.append(prefix);
                    var instruction = INCLUDE.matcher(matches.group(1).trim());
                    if (!instruction.matches())
                        throw new IllegalArgumentException(
                                "仅支持静态file/virtual include，禁止exec及其他服务器指令");
                    if (++includeCount > MAX_INCLUDES)
                        throw new IllegalArgumentException("静态包含超过100次");
                    Path child =
                            resolve(
                                    path.getParent(),
                                    instruction.group(3),
                                    instruction.group(1).equalsIgnoreCase("virtual"));
                    included.add(relative(child));
                    output.append(expand(child, depth + 1));
                    if (output.length() > MAX_BYTES)
                        throw new IllegalArgumentException("静态展开结果超过2MB");
                    end = matches.end();
                }
                String suffix = raw.substring(end);
                if (SSI_START.matcher(suffix).find())
                    throw new IllegalArgumentException("静态包含指令未闭合");
                output.append(suffix);
                if (output.length() > MAX_BYTES) throw new IllegalArgumentException("静态展开结果超过2MB");
                return output.toString();
            } finally {
                stack.pop();
            }
        }

        private String relative(Path path) {
            return base.relativize(path).toString().replace('\\', '/');
        }
    }
}

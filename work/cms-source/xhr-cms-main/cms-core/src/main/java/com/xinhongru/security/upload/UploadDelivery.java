package com.xinhongru.security.upload;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 仅用于用户上传资源。静态 SVG 经过安全校验后展示，其他附件按二进制下载。 */
public final class UploadDelivery {
    public static final String CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'";
    private static final Map<String, String> INLINE =
            Map.ofEntries(
                    Map.entry("svg", "image/svg+xml"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("png", "image/png"),
                    Map.entry("gif", "image/gif"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("bmp", "image/bmp"),
                    Map.entry("mp4", "video/mp4"),
                    Map.entry("webm", "video/webm"),
                    Map.entry("mp3", "audio/mpeg"));

    private UploadDelivery() {}

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean attachment(Path file) {
        return !INLINE.containsKey(extension(file.getFileName().toString()));
    }

    public static String contentType(Path file) {
        return contentType(file.getFileName().toString());
    }

    public static String contentType(String name) {
        return INLINE.getOrDefault(extension(name), "application/octet-stream");
    }

    public static boolean isVideo(String name) {
        return contentType(name).startsWith("video/");
    }

    public static boolean within(Path root, Path file) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && file.toRealPath().startsWith(root.toRealPath());
    }

    /** Native HTTP 服务共享 Range 和流式读取，避免视频整体载入内存。 */
    public static Delivery prepare(Path file, String range) throws IOException {
        byte[] svg = null;
        if ("svg".equals(extension(file.toString()))) {
            try (var input = Files.newInputStream(file)) {
                svg = SafeSvg.read(input);
            }
        }
        long size = svg == null ? Files.size(file) : svg.length;
        long start = 0, end = size - 1;
        int status = 200;
        if (range != null) {
            try {
                if (!range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
                String[] parts = range.substring(6).split("-", -1);
                if (parts[0].isEmpty()) {
                    long suffix = Long.parseLong(parts[1]);
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, size - suffix);
                } else {
                    start = Long.parseLong(parts[0]);
                    if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                }
                if (start > end || start >= size) throw new IllegalArgumentException();
                status = 206;
            } catch (IllegalArgumentException failure) {
                return new Delivery(file, 416, 0, 0, size, svg);
            }
        }
        return new Delivery(file, status, start, Math.max(0, end - start + 1), size, svg);
    }

    public record Delivery(
            Path file, int status, long offset, long length, long total, byte[] svg) {
        public Map<String, String> headers() {
            var headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", contentType(file));
            headers.put("Content-Disposition", attachment(file) ? "attachment" : "inline");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Content-Security-Policy", CSP);
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Length", Long.toString(length));
            headers.put("Cache-Control", "no-cache");
            if (status == 206)
                headers.put(
                        "Content-Range",
                        "bytes " + offset + "-" + (offset + length - 1) + "/" + total);
            if (status == 416) headers.put("Content-Range", "bytes */" + total);
            return headers;
        }

        public void write(OutputStream output) throws IOException {
            if (length == 0) return;
            if (svg != null) {
                output.write(svg, (int) offset, (int) length);
                return;
            }
            try (var input = Files.newInputStream(file)) {
                input.skipNBytes(offset);
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("资源读取中断");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }
}

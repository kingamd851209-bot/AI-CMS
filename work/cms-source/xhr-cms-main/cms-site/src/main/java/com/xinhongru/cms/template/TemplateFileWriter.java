package com.xinhongru.cms.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;

/** Site-confined, atomic template replacement with five previous versions. */
final class TemplateFileWriter {
    private TemplateFileWriter() {}

    static void write(Path directory, String relativePath, String content) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path relative = Path.of(relativePath);
        Path target = root.resolve(relative).normalize();
        if (relative.isAbsolute()
                || !target.startsWith(root)
                || target.equals(root)
                || root.relativize(target).startsWith(".history")) {
            throw new IllegalArgumentException("模板路径超出站点目录");
        }
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("站点模板根目录不能是符号链接");
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Path current = root;
        // Check each existing component before creating any missing directory.
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)
                    || (Files.exists(current) && !current.toRealPath().startsWith(realRoot))) {
                throw new IllegalArgumentException("模板路径包含符号链接或超出站点目录");
            }
        }
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target)) {
            Path history = root.resolve(".history").resolve(root.relativize(target));
            Path check = root;
            for (Path part : root.relativize(history)) {
                check = check.resolve(part);
                if (Files.isSymbolicLink(check))
                    throw new IllegalArgumentException("模板历史目录不能包含符号链接");
            }
            Files.createDirectories(history);
            Files.copy(
                    target,
                    history.resolve(
                            System.currentTimeMillis() + "-" + UUID.randomUUID() + ".html"));
            try (var files = Files.list(history)) {
                var old =
                        files.filter(Files::isRegularFile)
                                .sorted(Comparator.reverseOrder())
                                .skip(5)
                                .toList();
                for (Path path : old) Files.delete(path);
            }
        }
        Path temporary = Files.createTempFile(target.getParent(), ".template-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

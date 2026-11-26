package com.xinhongru.ai.agent.opencode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** CMS and the sidecar share only disposable task files, never the live template directory. */
record OpenCodeWorkspace(Path local, String remote) {
    static OpenCodeWorkspace create(
            OpenCodeProperties config, String taskId, Map<String, String> files)
            throws IOException {
        if (!taskId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("无效任务 ID");
        Path root = Path.of(config.getWorkspaceRoot()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("任务根目录不能是符号链接");
        Files.createDirectories(root);
        root = root.toRealPath();
        if (config.getRemoteWorkspaceRoot().isBlank()) {
            for (Path parent = root; parent != null; parent = parent.getParent()) {
                if (Files.exists(parent.resolve(".git"))) {
                    throw new IOException("本机 Agent 工作目录应位于 Git 仓库之外");
                }
            }
        }
        Path local = Files.createDirectory(root.resolve(taskId));
        String remoteRoot =
                config.getRemoteWorkspaceRoot().isBlank()
                        ? root.toString()
                        : config.getRemoteWorkspaceRoot().replaceAll("/+$", "");
        OpenCodeWorkspace workspace = new OpenCodeWorkspace(local, remoteRoot + "/" + taskId);
        try {
            for (var file : files.entrySet()) {
                Files.writeString(
                        local.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
            return workspace;
        } catch (IOException error) {
            workspace.delete();
            throw error;
        }
    }

    void delete() throws IOException {
        // walkFileTree does not follow symlinks. Agent-created links cannot escape cleanup.
        if (!Files.exists(local, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(
                local,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException error)
                            throws IOException {
                        if (error != null) throw error;
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}

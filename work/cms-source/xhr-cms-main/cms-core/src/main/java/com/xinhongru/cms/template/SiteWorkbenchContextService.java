package com.xinhongru.cms.template;

import com.xinhongru.site.mapper.SiteProjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** CMS 自有站点工作区，不将上传包文件作为 Agent 约定。 */
@Service
public class SiteWorkbenchContextService {
    private static final int MAX_BYTES = 32768;
    private static final int MAX_RELEASE_BYTES = 16 * 1024 * 1024;
    private final Path root;
    private final SiteProjectMapper projects;

    public SiteWorkbenchContextService(SiteProjectMapper projects,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model") String root) {
        this.projects = projects;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public record Document(boolean present, String content, String digest) {}

    public Document agents(Long siteId, String code) {
        return read(directory(siteId, code).resolve("AGENTS.md"));
    }

    public synchronized Document saveAgents(Long siteId, String code, String content, String expectedDigest) {
        return write(directory(siteId, code).resolve("AGENTS.md"), content, expectedDigest);
    }

    public Document progress(Long siteId, String code) {
        return read(directory(siteId, code).resolve("建站进度.md"));
    }

    public synchronized Document exportProgress(Long siteId, String code, String content, String expectedDigest) {
        return write(directory(siteId, code).resolve("建站进度.md"), content, expectedDigest);
    }

    Document parentEntryDecisions(Long siteId, String code, String planScope) {
        return read(parentEntryFile(siteId, code, planScope));
    }

    synchronized Document saveParentEntryDecisions(
            Long siteId, String code, String planScope, String content, String expectedDigest) {
        return write(parentEntryFile(siteId, code, planScope), content, expectedDigest);
    }

    public Document staticSearchCheck(Long siteId, String code) {
        return read(directory(siteId, code).resolve("static-search-check.json"));
    }

    public Document staticHostCheck(Long siteId, String code) {
        return read(directory(siteId, code).resolve("static-host-check.json"), MAX_RELEASE_BYTES);
    }

    public Document staticReleaseReview(Long siteId, String code, String scope) {
        return read(directory(siteId, code).resolve("static-review-" + digest(scope) + ".json"), MAX_RELEASE_BYTES);
    }

    public synchronized Document saveStaticReleaseReview(
            Long siteId, String code, String scope, String value, String expectedDigest) {
        return write(directory(siteId, code).resolve("static-review-" + digest(scope) + ".json"),
                value, expectedDigest, MAX_RELEASE_BYTES);
    }

    public String staticArtifactProof(Long siteId, String code, Path output, String html) {
        var file = staticArtifactFile(siteId, code, output);
        var document = read(file);
        var suffix = ":" + digest(html);
        return document.content().endsWith(suffix)
                ? document.content().substring(0, document.content().length() - suffix.length()) : null;
    }

    public synchronized void recordStaticArtifact(
            Long siteId, String code, Path output, String proof, String html) {
        var file = staticArtifactFile(siteId, code, output);
        write(file, proof + ":" + digest(html), read(file).digest());
    }

    private Path staticArtifactFile(Long siteId, String code, Path output) {
        return directory(siteId, code).resolve("static-artifacts")
                .resolve(digest(output.toAbsolutePath().normalize().toString()) + ".txt");
    }

    private Path parentEntryFile(Long siteId, String code, String planScope) {
        return directory(siteId, code).resolve("parent-entry-decisions-" + digest(planScope) + ".json");
    }

    private Document write(Path target, String content, String expectedDigest) {
        return write(target, content, expectedDigest, MAX_BYTES);
    }

    private Document write(Path target, String content, String expectedDigest, int maxBytes) {
        if (content == null || content.indexOf('\0') >= 0
                || content.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("站点文档须为" + (maxBytes / 1024) + "KB以内的UTF-8文本");
        Path temporary = null;
        try {
            safeParents(target.getParent());
            Files.createDirectories(target.getParent());
            safeParents(target.getParent());
            Path lock = target.getParent().resolve(".context.lock");
            if (Files.isSymbolicLink(lock)) throw new IllegalArgumentException("站点工作区锁路径无效");
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS); var held = channel.lock()) {
                var current = read(target, maxBytes);
                if (expectedDigest == null || !expectedDigest.equals(current.digest()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "站点文档已变化，请重新读取后保存");
                if (current.present() && current.content().equals(content)) return current;
                temporary = Files.createTempFile(target.getParent(), ".context-", ".tmp");
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new Document(true, content, digest(content));
            }
        } catch (IOException error) {
            throw new IllegalStateException("站点文档无法原子保存", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException error) { throw new IllegalStateException("站点文档临时文件清理失败", error); }
            }
        }
    }

    private Document read(Path target) {
        return read(target, MAX_BYTES);
    }

    private Document read(Path target, int maxBytes) {
        try {
            safeParents(target.getParent());
            if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("站点文档路径无效");
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new Document(false, "", digest(""));
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > maxBytes)
                throw new IllegalArgumentException("站点文档无效或超出" + (maxBytes / 1024) + "KB");
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return new Document(true, content, digest(content));
        } catch (IOException error) {
            throw new IllegalStateException("站点文档无法读取", error);
        }
    }

    private Path directory(Long siteId, String code) {
        if (siteId == null || siteId <= 0 || code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("站点工作区身份无效");
        var project = projects.selectById(siteId);
        if (project == null || Integer.valueOf(1).equals(project.getDeleted()) || !code.equals(project.getProjectCode()))
            throw new IllegalArgumentException("站点工作区与真实项目不一致");
        return root.resolve(code).resolve(siteId.toString());
    }

    private void safeParents(Path directory) {
        if (!directory.startsWith(root)) throw new IllegalArgumentException("站点工作区路径越界");
        for (Path cursor = directory; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("站点工作区不能包含符号链接");
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

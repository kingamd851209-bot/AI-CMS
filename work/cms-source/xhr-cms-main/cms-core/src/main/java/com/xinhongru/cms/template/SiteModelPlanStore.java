package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelPlanService.Plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

final class SiteModelPlanStore {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_REVISIONS = 1000;
    private final Path root;
    private final ObjectMapper json;

    SiteModelPlanStore(String root, ObjectMapper json) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.json = json;
    }

    Plan latest(Long siteId, String code, String version, String id) {
        var directory = directory(siteId, code, version);
        if (id != null) checkId(id);
        return heads(directory, id).stream()
                .map(path -> read(path, siteId, code, version))
                .max(
                        Comparator.comparing((Plan plan) -> Instant.parse(plan.updatedAt()))
                                .thenComparing(Plan::id))
                .orElse(null);
    }

    private List<Path> heads(Path directory, String id) {
        var heads = new LinkedHashMap<String, Path>();
        for (Path path : files(directory)) {
            String fileId = path.getFileName().toString().substring(0, 36);
            if (id == null || id.equals(fileId))
                heads.merge(fileId, path, (a, b) -> fileRevision(a) > fileRevision(b) ? a : b);
        }
        return List.copyOf(heads.values());
    }

    List<Plan> index(Long siteId, String code, String version) {
        return heads(directory(siteId, code, version), null).stream()
                .map(path -> read(path, siteId, code, version))
                .sorted(Comparator.comparing(Plan::id)).toList();
    }

    private static long fileRevision(Path path) {
        String name = path.getFileName().toString();
        return Long.parseLong(name.substring(37, name.length() - 5));
    }

    Plan revision(Long siteId, String code, String version, String id, long revision) {
        checkId(id);
        if (revision <= 0) throw new IllegalArgumentException("整站计划版本无效");
        Path file = directory(siteId, code, version).resolve(id + "-" + revision + ".json");
        if (!Files.exists(file)) throw notFound();
        return read(file, siteId, code, version);
    }

    void save(Plan plan) {
        byte[] bytes = checkSave(plan);
        Path directory = directory(plan.siteId(), plan.code(), plan.packageVersion());
        Path target = directory.resolve(plan.id() + "-" + plan.revision() + ".json");
        write(target, bytes);
    }

    byte[] checkSave(Plan plan) {
        Path directory = directory(plan.siteId(), plan.code(), plan.packageVersion());
        checkId(plan.id());
        byte[] bytes = json.writeValueAsBytes(plan);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("整站计划超过1MB限制");
        if (files(directory).size() >= MAX_REVISIONS)
            throw new IllegalStateException("整站计划版本数量已达上限");
        return bytes;
    }

    void saveCheckpoint(Plan plan) {
        Path target = checkpointFile(plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        byte[] bytes = json.writeValueAsBytes(plan);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("整站校验草案超过1MB限制");
        if (!Files.exists(target) && Files.exists(target.getParent())) {
            try (var files = Files.list(target.getParent())) {
                if (files.filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .matches("checkpoint-[a-f0-9-]{36}\\.json"))
                                .limit(MAX_REVISIONS)
                                .count()
                        >= MAX_REVISIONS) throw new IllegalStateException("整站校验草案数量已达上限");
            } catch (IOException error) {
                throw new IllegalStateException("无法访问整站校验草案", error);
            }
        }
        write(target, bytes, true);
    }

    Plan checkpoint(Long siteId, String code, String version, String id) {
        Path path = checkpointFile(siteId, code, version, id);
        if (Files.isSymbolicLink(path)) throw new IllegalStateException("整站校验草案文件无效");
        if (!Files.exists(path)) return null;
        return read(path, siteId, code, version, true);
    }

    void clearCheckpoint(Plan plan) {
        try {
            Files.deleteIfExists(
                    checkpointFile(plan.siteId(), plan.code(), plan.packageVersion(), plan.id()));
        } catch (IOException error) {
            // 已提交的正式版本优先读取，清理失败不影响恢复。
        }
    }

    private Path checkpointFile(Long siteId, String code, String version, String id) {
        checkId(id);
        return directory(siteId, code, version).resolve("checkpoint-" + id + ".json");
    }

    record Derived(String sitePlanId, long revision, SiteModelPlanService.PackageFieldOrigin packageOrigin) {
        Derived(String sitePlanId, long revision) {
            this(sitePlanId, revision, null);
        }
    }

    SiteModelWorkbenchApplyService.Intent workbenchApplication(Long siteId, String code, String version, String id) {
        return readWorkbenchApplication(workbenchApplicationFile(siteId, code, version, id));
    }

    private SiteModelWorkbenchApplyService.Intent readWorkbenchApplication(Path file) {
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("工作台应用回执超限");
            return json.readValue(bytes, SiteModelWorkbenchApplyService.Intent.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取工作台应用回执", error);
        }
    }

    void saveWorkbenchApplication(Long siteId, String code, String version, String id,
                                  SiteModelWorkbenchApplyService.Intent intent) {
        saveWorkbenchApplication(workbenchApplicationFile(siteId, code, version, id), intent);
    }

    SiteModelWorkbenchApplyService.Intent closedWorkbenchApplication(
            Long siteId, String code, String version, String id, String requestId) {
        return readWorkbenchApplication(closedWorkbenchApplicationFile(siteId, code, version, id, requestId));
    }

    void saveClosedWorkbenchApplication(Long siteId, String code, String version, String id,
            SiteModelWorkbenchApplyService.Intent intent) {
        saveWorkbenchApplication(closedWorkbenchApplicationFile(siteId, code, version, id, intent.requestId()), intent);
    }

    private Path closedWorkbenchApplicationFile(Long siteId, String code, String version, String id, String requestId) {
        checkId(id);
        checkId(requestId);
        return directory(siteId, code, version).resolve("workbench-apply-closed-" + id + "-" + requestId + ".json");
    }

    private void saveWorkbenchApplication(Path file, SiteModelWorkbenchApplyService.Intent intent) {
        byte[] bytes = json.writeValueAsBytes(intent);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("工作台应用回执超限");
        write(file, bytes, true);
    }

    private Path workbenchApplicationFile(Long siteId, String code, String version, String id) {
        checkId(id);
        return directory(siteId, code, version).resolve("workbench-apply-" + id + ".json");
    }

    record ModelDraftOrigin(
            String sitePlanId, long revision, String nodeKey, String sourcePath, String modelKey) {}

    void registerModelDraft(
            Plan parent, Plan draft, String nodeKey, String sourcePath, String modelKey) {
        var link =
                new ModelDraftOrigin(parent.id(), parent.revision(), nodeKey, sourcePath, modelKey);
        var previous =
                modelDraftOrigin(draft.siteId(), draft.code(), draft.packageVersion(), draft.id());
        if (link.equals(previous)) return;
        write(
                modelDraftOriginFile(
                        draft.siteId(), draft.code(), draft.packageVersion(), draft.id()),
                json.writeValueAsBytes(link));
    }

    ModelDraftOrigin modelDraftOrigin(Long siteId, String code, String version, String draftId) {
        Path file = modelDraftOriginFile(siteId, code, version, draftId);
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(1025);
            if (data.length > 1024) throw new IllegalStateException("模型草稿的来源回执超限");
            return json.readValue(data, ModelDraftOrigin.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取模型草稿的来源回执", error);
        }
    }

    private Path modelDraftOriginFile(Long siteId, String code, String version, String draftId) {
        checkId(draftId);
        return directory(siteId, code, version)
                .resolve("navigation-model-origin-" + draftId + ".json");
    }

    void register(Long siteId, String code, String version, String fieldPlanId, Derived link) {
        Path target = derivedFile(siteId, code, version, fieldPlanId);
        var previous = derived(siteId, code, version, fieldPlanId);
        if (link.equals(previous)) return;
        if (previous != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "字段计划已关联其他整站计划版本");
        write(target, json.writeValueAsBytes(link));
    }

    Derived derived(Long siteId, String code, String version, String fieldPlanId) {
        Path target = derivedFile(siteId, code, version, fieldPlanId);
        if (Files.isSymbolicLink(target)) throw new IllegalStateException("派生字段计划关联文件无效");
        if (!Files.exists(target)) return null;
        try {
            if (Files.size(target) > 8192) throw new IllegalStateException("派生字段计划关联文件无效");
            return json.readValue(Files.readAllBytes(target), Derived.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取派生字段计划关联", error);
        }
    }

    SiteModelNavigationService.SavedNavigation navigation(
            Long siteId, String code, String version, String id) {
        Path file = navigationFile(siteId, code, version, id);
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("导航计划文件无效");
        if (!Files.exists(file)) return null;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("导航计划文件超限");
            return json.readValue(
                    Files.readAllBytes(file), SiteModelNavigationService.SavedNavigation.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取导航计划", error);
        }
    }

    String appliedNavigationPlanId(Long siteId, String code, String version, String excludedId) {
        var directory = directory(siteId, code, version);
        if (excludedId != null) checkId(excludedId);
        var path = excludedId == null ? null : directory.resolve(excludedId + "-1.json");
        var first = path == null || !Files.exists(path) ? null : read(path, siteId, code, version);
        return appliedNavigationPlanId(
                siteId,
                code,
                version,
                excludedId,
                first == null ? null : Instant.parse(first.updatedAt()));
    }

    String latestAppliedNavigationPlanId(Long siteId, String code, String version) {
        return appliedNavigationPlanId(siteId, code, version, null, null);
    }

    private String appliedNavigationPlanId(
            Long siteId, String code, String version, String excludedId, Instant asOf) {
        var eligible = new LinkedHashMap<String, SiteModelNavigationService.Navigation>();
        for (Path head : heads(directory(siteId, code, version), null)) {
            String id = head.getFileName().toString().substring(0, 36);
            if (id.equals(excludedId)) continue;
            var receipt = navigation(siteId, code, version, id);
            if (receipt == null
                    || receipt.view().status() != SiteModelNavigationService.Status.APPLIED)
                continue;
            var view = receipt.view();
            // 续分析发布日期及旧公开绑定所见的导航链保持稳定。
            if (asOf != null
                    && view.originPlanId() != null
                    && Instant.parse(revision(siteId, code, version, id, 1).updatedAt())
                            .isAfter(asOf)) continue;
            eligible.put(id, view);
        }
        var superseded =
                eligible.values().stream()
                        .map(SiteModelNavigationService.Navigation::originPlanId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        var origins = eligible.keySet().stream().filter(id -> !superseded.contains(id)).toList();
        if (origins.size() > 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存在多个已应用导航，请选择原整站计划核对");
        return origins.isEmpty() ? null : origins.getFirst();
    }

    void requireNoOtherPendingNavigationAppend(
            Long siteId, String code, String version, String id) {
        var directory = directory(siteId, code, version);
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.matches("navigation-[a-f0-9-]{36}\\.json")) continue;
                String otherId = name.substring(11, 47);
                if (otherId.equals(id)) continue;
                var other = navigation(siteId, code, version, otherId);
                if (other != null
                        && other.view().originPlanId() != null
                        && other.view().status() == SiteModelNavigationService.Status.APPLYING)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "有尚未完成的导航追加，请从原预览重试");
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法读取导航追加意图", error);
        }
    }

    void saveNavigation(
            Long siteId,
            String code,
            String version,
            String id,
            SiteModelNavigationService.SavedNavigation navigation) {
        byte[] bytes = json.writeValueAsBytes(navigation);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("导航计划超过1MB限制");
        write(navigationFile(siteId, code, version, id), bytes, true);
    }

    private Path navigationFile(Long siteId, String code, String version, String id) {
        checkId(id);
        return directory(siteId, code, version).resolve("navigation-" + id + ".json");
    }

    SiteModelNavigationService.ContinuationPreview navigationContinuation(Plan plan) {
        return navigationContinuation(navigationContinuationFile(plan));
    }

    private SiteModelNavigationService.ContinuationPreview navigationContinuation(Path file) {
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("导航接续回执文件无效");
        if (!Files.exists(file)) return null;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("导航接续回执文件超限");
            return json.readValue(Files.readAllBytes(file), SiteModelNavigationService.ContinuationPreview.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取导航接续回执", error);
        }
    }

    void saveNavigationContinuation(
            Plan plan, SiteModelNavigationService.ContinuationPreview receipt, boolean replace) {
        byte[] bytes = json.writeValueAsBytes(receipt);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("导航接续回执超限");
        write(navigationContinuationFile(plan), bytes, replace);
    }

    List<SiteModelNavigationService.ModelLink> navigationModelLinks(
            Plan plan, SiteModelNavigationService.Navigation saved, Integer rootMenuId) {
        return navigationModelLinks(plan, saved, rootMenuId, plan.revision());
    }

    List<SiteModelNavigationService.ModelLink> navigationModelLinks(
            Plan plan, SiteModelNavigationService.Navigation saved, Integer rootMenuId, long maxRevision) {
        if (saved.status() != SiteModelNavigationService.Status.APPLIED) return List.of();
        var links = new ArrayList<SiteModelNavigationService.ModelLink>();
        for (long rev = saved.planRevision(); rev <= maxRevision; rev++) {
            var path = navigationContinuationFile(plan, rev);
            var receipt = navigationContinuation(path);
            if (receipt == null || receipt.modelLinks().isEmpty()
                    || receipt.navigationRevision() != saved.revision()) continue;
            var reviewed = rev == plan.revision() ? plan
                    : revision(plan.siteId(), plan.code(), plan.packageVersion(), plan.id(), rev);
            if (!SiteModelNavigationService.matchesContinuation(reviewed, saved, rootMenuId, receipt))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "导航模型关联回执已变化，请重新预览");
            links.addAll(receipt.modelLinks());
        }
        return List.copyOf(links);
    }

    private Path navigationContinuationFile(Plan plan) {
        return navigationContinuationFile(plan, plan.revision());
    }

    private Path navigationContinuationFile(Plan plan, long revision) {
        checkId(plan.id());
        return directory(plan.siteId(), plan.code(), plan.packageVersion())
                .resolve("navigation-continuation-" + plan.id() + "-" + revision + ".json");
    }

    <T> T navigationLinks(Long siteId, String code, String version, String pageKey, Class<T> type) {
        return pageBinding(siteId, code, version, pageKey, "navigation-links-", type);
    }

    <T> T sharedContent(Long siteId, String code, String version, String pageKey, Class<T> type) {
        return pageBinding(siteId, code, version, pageKey, "shared-content-", type);
    }

    TemplateListPairingService.Review listPairingReview(Long siteId, String code, String version, String key) {
        return pageBinding(
                siteId,
                code,
                version,
                key,
                "list-pairing-review-",
                TemplateListPairingService.Review.class);
    }

    void saveListPairingReview(Long siteId, String code, String version, String key, TemplateListPairingService.Review value) {
        savePageBinding(siteId, code, version, key, "list-pairing-review-", value);
    }

    private <T> T pageBinding(
            Long siteId,
            String code,
            String version,
            String pageKey,
            String prefix,
            Class<T> type) {
        Path file = pageBindingFile(siteId, code, version, pageKey, prefix);
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("导航绑定文件无效");
        if (!Files.exists(file)) return null;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("导航绑定文件超限");
            return json.readValue(Files.readAllBytes(file), type);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取导航绑定", error);
        }
    }

    void saveNavigationLinks(
            Long siteId, String code, String version, String pageKey, Object value) {
        savePageBinding(siteId, code, version, pageKey, "navigation-links-", value);
    }

    void saveSharedContent(Long siteId, String code, String version, String pageKey, Object value) {
        savePageBinding(siteId, code, version, pageKey, "shared-content-", value);
    }

    private void savePageBinding(
            Long siteId, String code, String version, String pageKey, String prefix, Object value) {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("导航绑定超过1MB限制");
        write(pageBindingFile(siteId, code, version, pageKey, prefix), bytes, true);
    }

    private Path pageBindingFile(
            Long siteId, String code, String version, String pageKey, String prefix) {
        if (pageKey == null || !pageKey.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("导航来源标识无效");
        return directory(siteId, code, version).resolve(prefix + pageKey + ".json");
    }

    private Path derivedFile(Long siteId, String code, String version, String fieldPlanId) {
        checkId(fieldPlanId);
        return directory(siteId, code, version).resolve("derived-" + fieldPlanId + ".json");
    }

    private void write(Path target, byte[] bytes) {
        write(target, bytes, false);
    }

    private void write(Path target, byte[] bytes, boolean replace) {
        Path directory = target.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target) || !replace && Files.exists(target))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "整站计划版本已保存");
            temporary = Files.createTempFile(directory, ".site-model-", ".tmp");
            Files.write(temporary, bytes);
            if (replace)
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new IllegalStateException("整站计划保存失败", error);
        } finally {
            if (temporary != null && Files.exists(temporary)) {
                try {
                    Files.delete(temporary);
                } catch (IOException error) {
                    // 工作台只枚举已提交的 JSON 版本。
                }
            }
        }
    }

    private Path directory(Long siteId, String code, String version) {
        if (siteId == null
                || siteId <= 0
                || code == null
                || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || version == null
                || !version.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("站点或页面包版本无效");
        Path directory = root;
        for (String part : List.of(code, siteId.toString(), version)) {
            directory = directory.resolve(part);
            if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("整站计划目录不可使用链接");
        }
        return directory;
    }

    private List<Path> files(Path directory) {
        if (!Files.exists(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            var result =
                    stream.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .matches(
                                                            "[a-f0-9-]{36}-[1-9][0-9]{0,8}\\.json"))
                            .limit(MAX_REVISIONS + 1)
                            .toList();
            if (result.size() > MAX_REVISIONS) throw new IllegalStateException("整站计划版本数量已达上限");
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("无法列出整站计划", error);
        }
    }

    private Plan read(Path path, Long siteId, String code, String version) {
        return read(path, siteId, code, version, false);
    }

    private Plan read(Path path, Long siteId, String code, String version, boolean checkpoint) {
        try {
            if (Files.isSymbolicLink(path) || Files.size(path) > MAX_BYTES)
                throw new IllegalStateException("整站计划文件无效");
            Plan plan = json.readValue(Files.readAllBytes(path), Plan.class);
            String name =
                    checkpoint
                            ? "checkpoint-" + plan.id() + ".json"
                            : plan.id() + "-" + plan.revision() + ".json";
            if (plan.schemaVersion() != 1
                    || !siteId.equals(plan.siteId())
                    || !code.equals(plan.code())
                    || !version.equals(plan.packageVersion())
                    || !path.getFileName().toString().equals(name)
                    || checkpoint && (plan.revision() != 1 || !"DRAFT".equals(plan.status())))
                throw new IllegalStateException("整站计划范围或版本无效");
            return plan;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取整站计划", error);
        }
    }

    static void checkId(String id) {
        if (id == null
                || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("整站计划标识无效");
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "整站计划不存在");
    }
}

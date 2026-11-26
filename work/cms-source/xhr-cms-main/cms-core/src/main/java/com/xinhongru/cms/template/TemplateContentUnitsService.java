package com.xinhongru.cms.template;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 内容单元仅保存人工判断和原文引用，不改业务内容或上传包。 */
@Service
public class TemplateContentUnitsService {
    static final int MAX_UNITS = 200;
    public static final Set<String> TYPES =
            Set.of("SingleLine", "MultiLine", "Editor", "Image", "Video", "File", "DateTime");
    private final TemplateSeedService seeds;
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public TemplateContentUnitsService(
            TemplateSeedService seeds,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.seeds = seeds;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public record Source(
            String id,
            String kind,
            String rawValue,
            String selector,
            String context,
            String repeatGroup,
            CounterScriptInputs.ScriptInput scriptInput,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean publicNavigation,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String publicRegion,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean interactionPending,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean backgroundBindable,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean nonBodyMetadata) {
        public Source(String id, String kind, String rawValue, String selector, String context, String repeatGroup,
                CounterScriptInputs.ScriptInput scriptInput, Boolean publicNavigation, String publicRegion,
                Boolean interactionPending, Boolean backgroundBindable) {
            this(id, kind, rawValue, selector, context, repeatGroup, scriptInput, publicNavigation, publicRegion,
                    interactionPending, backgroundBindable, null);
        }
        public Source(String id, String kind, String rawValue, String selector, String context, String repeatGroup,
                CounterScriptInputs.ScriptInput scriptInput, Boolean publicNavigation, String publicRegion,
                Boolean interactionPending) {
            this(id, kind, rawValue, selector, context, repeatGroup, scriptInput, publicNavigation,
                    publicRegion, interactionPending, null);
        }
        public Source(
                String id,
                String kind,
                String rawValue,
                String selector,
                String context,
                String repeatGroup,
                CounterScriptInputs.ScriptInput scriptInput,
                Boolean publicNavigation) {
            this(
                    id,
                    kind,
                    rawValue,
                    selector,
                    context,
                    repeatGroup,
                    scriptInput,
                    publicNavigation,
                    null,
                    null);
        }

        public Source(
                String id,
                String kind,
                String rawValue,
                String selector,
                String context,
                String repeatGroup,
                CounterScriptInputs.ScriptInput scriptInput) {
            this(id, kind, rawValue, selector, context, repeatGroup, scriptInput, null);
        }

        public Source(
                String id,
                String kind,
                String rawValue,
                String selector,
                String context,
                String repeatGroup) {
            this(id, kind, rawValue, selector, context, repeatGroup, null);
        }

        public Source withId(String value) {
            return new Source(
                    value,
                    kind,
                    rawValue,
                    selector,
                    context,
                    repeatGroup,
                    scriptInput,
                    publicNavigation,
                    publicRegion,
                    interactionPending,
                    backgroundBindable,
                    nonBodyMetadata);
        }
    }

    static boolean businessCandidate(Source source) {
        return source.publicRegion() == null
                && !Boolean.TRUE.equals(source.publicNavigation())
                && !Boolean.TRUE.equals(source.interactionPending())
                && !Boolean.TRUE.equals(source.nonBodyMetadata())
                && (!"BACKGROUND_IMAGE".equals(source.kind()) || Boolean.TRUE.equals(source.backgroundBindable()))
                && !("LINK".equals(source.kind())
                        && TemplateSeedService.actionAddress(source.rawValue()));
    }

    public record Reference(String sourceId, int start, int end) {}

    public record Unit(
            String id,
            String name,
            String fieldType,
            List<Reference> sources,
            boolean ignored,
            String doubts) {}

    public record Workspace(
            String sourcePath,
            String digest,
            long revision,
            List<Source> sources,
            List<Unit> units,
            List<String> warnings) {}

    public record SaveRequest(
            String sourcePath,
            String digest,
            long revision,
            List<Unit> units,
            String expectedPackageVersion) {
        public SaveRequest(String sourcePath, String digest, long revision, List<Unit> units) {
            this(sourcePath, digest, revision, units, null);
        }
    }

    public record Correction(
            String id,
            long version,
            boolean enabled,
            Long operatorId,
            String timestamp,
            String packageVersion,
            String sourcePath,
            String digest,
            List<Source> sources,
            List<Unit> before,
            List<Unit> after,
            Long statusOperatorId,
            String statusTimestamp,
            String baselineId,
            String baselineKind,
            Long baseRevision) {
        public Correction(
                String id,
                long version,
                boolean enabled,
                Long operatorId,
                String timestamp,
                String packageVersion,
                String sourcePath,
                String digest,
                List<Source> sources,
                List<Unit> before,
                List<Unit> after) {
            this(
                    id,
                    version,
                    enabled,
                    operatorId,
                    timestamp,
                    packageVersion,
                    sourcePath,
                    digest,
                    sources,
                    before,
                    after,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    public record Archive(String id, String sha256, int count, Long operatorId, String timestamp) {}

    public record ExperienceState(
            long version, boolean enabled, Long operatorId, String timestamp) {}

    public record HistoryPage(
            List<Correction> items,
            int total,
            int offset,
            int activeCount,
            List<Archive> archives) {}

    public record Stored(
            String digest,
            long revision,
            List<Unit> units,
            List<Correction> history,
            Boolean confirmed,
            String draftId,
            List<Archive> archives,
            java.util.Map<String, ExperienceState> experienceStates) {
        public Stored(String digest, long revision, List<Unit> units) {
            this(digest, revision, units, List.of(), true, null, List.of(), java.util.Map.of());
        }

        public Stored(String digest, long revision, List<Unit> units, List<Correction> history) {
            this(digest, revision, units, history, true, null, List.of(), java.util.Map.of());
        }

        public Stored(
                String digest,
                long revision,
                List<Unit> units,
                List<Correction> history,
                Boolean confirmed,
                String draftId) {
            this(
                    digest,
                    revision,
                    units,
                    history,
                    confirmed,
                    draftId,
                    List.of(),
                    java.util.Map.of());
        }
    }

    private static List<Correction> active(Stored stored) {
        return stored == null || stored.history() == null ? List.of() : stored.history();
    }

    private static List<Archive> archives(Stored stored) {
        return stored == null || stored.archives() == null ? List.of() : stored.archives();
    }

    private static java.util.Map<String, ExperienceState> states(Stored stored) {
        return stored == null || stored.experienceStates() == null
                ? java.util.Map.of()
                : stored.experienceStates();
    }

    private static Stored content(
            Stored old,
            String digest,
            long revision,
            List<Unit> units,
            List<Correction> history,
            Boolean confirmed,
            String draftId) {
        return new Stored(
                digest, revision, units, history, confirmed, draftId, archives(old), states(old));
    }

    private static Correction effective(Correction item, Stored stored) {
        var state = states(stored).get(item.id());
        if (state == null) return item;
        return new Correction(
                item.id(),
                state.version(),
                state.enabled(),
                item.operatorId(),
                item.timestamp(),
                item.packageVersion(),
                item.sourcePath(),
                item.digest(),
                item.sources(),
                item.before(),
                item.after(),
                state.operatorId(),
                state.timestamp(),
                item.baselineId(),
                item.baselineKind(),
                item.baseRevision());
    }

    /** 仅表示候选资源预算耗尽，不代表坏档、链接越界或权限错误。 */
    public static final class ExperienceCapacityException extends IllegalArgumentException {
        public ExperienceCapacityException(String message) {
            super(message);
        }
    }

    public synchronized HistoryPage historyPage(
            String code, String version, String path, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 50)
            throw new IllegalArgumentException("历史分页每次1至50条，偏移不能为负");
        Path target = file(code, version, path);
        var stored = read(target);
        int total = active(stored).size();
        for (var archive : archives(stored)) total = Math.addExact(total, archive.count());
        var result = new ArrayList<Correction>();
        int index = 0;
        for (var archive : archives(stored)) {
            if (index + archive.count() > offset && index < offset + limit) {
                var batch = readArchive(target, archive, new long[] {0});
                for (int i = 0; i < batch.size(); i++)
                    if (index + i >= offset && result.size() < limit)
                        result.add(effective(batch.get(i), stored));
            }
            index += archive.count();
        }
        for (var item : active(stored)) {
            if (index++ >= offset && result.size() < limit) result.add(effective(item, stored));
        }
        return new HistoryPage(
                List.copyOf(result), total, offset, active(stored).size(), archives(stored));
    }

    private void forEachHistory(
            Path target,
            Stored stored,
            long[] budget,
            java.util.function.Consumer<Correction> visitor) {
        for (var archive : archives(stored))
            for (var item : readArchive(target, archive, budget))
                visitor.accept(effective(item, stored));
        for (var item : active(stored)) visitor.accept(effective(item, stored));
    }

    private Correction findCorrection(Path target, Stored stored, String id) {
        for (var item : active(stored)) if (item.id().equals(id)) return effective(item, stored);
        for (var archive : archives(stored))
            for (var item : readArchive(target, archive, new long[] {0}))
                if (item.id().equals(id)) return effective(item, stored);
        throw new IllegalArgumentException("经验不存在");
    }

    private Path archiveFile(Path target, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("归档标识无效");
        Path archive = target.resolveSibling(target.getFileName() + ".archive-" + id);
        if (Files.isSymbolicLink(archive)) throw new IllegalArgumentException("归档文件不可使用链接");
        return archive;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Correction> readArchive(Path target, Archive archive, long[] budget) {
        try (var input = Files.newInputStream(archiveFile(target, archive.id()))) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            budget[0] += bytes.length;
            if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("单个经验归档超过1MB");
            if (budget[0] > 8 * 1024 * 1024)
                throw new ExperienceCapacityException("经验归档读取超过8MB候选预算");
            if (!sha256(bytes).equals(archive.sha256()))
                throw new IllegalStateException("经验归档校验失败");
            var items = List.of(mapper.readValue(bytes, Correction[].class));
            if (items.size() != archive.count()) throw new IllegalStateException("经验归档数量无效");
            return items;
        } catch (IOException e) {
            throw new IllegalStateException("经验归档读取失败");
        }
    }

    public synchronized Archive archiveHistory(
            String code, String version, String path, Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw new IllegalArgumentException("操作者无效");
        Path target = file(code, version, path);
        var stored = read(target);
        if (active(stored).isEmpty()) return new Archive("", "", 0, operatorId, null);
        byte[] bytes = mapper.writeValueAsBytes(active(stored));
        var archive =
                new Archive(
                        UUID.randomUUID().toString(),
                        sha256(bytes),
                        active(stored).size(),
                        operatorId,
                        java.time.Instant.now().toString());
        // 只有当前Stored提交引用后批次才生效；提交失败留下的副本永远不参与读取。
        writeBytes(archiveFile(target, archive.id()), bytes, false);
        var refs = new ArrayList<>(archives(stored));
        refs.add(archive);
        write(
                target,
                new Stored(
                        stored.digest(),
                        stored.revision(),
                        stored.units(),
                        List.of(),
                        stored.confirmed(),
                        stored.draftId(),
                        List.copyOf(refs),
                        states(stored)));
        return archive;
    }

    public record ExperienceReference(
            Correction correction, List<String> targetSourceIds, boolean conflict) {}

    /** 候选仅供有来源的语义参考，不把旧值或名称自动覆盖到当前页面。 */
    public synchronized List<ExperienceReference> references(
            String code, String version, String path) {
        var workspace = load(code, version, path);
        Path directory = file(code, version, path).getParent();
        var result = new ArrayList<ExperienceReference>();
        try (var files = Files.list(directory)) {
            var paths =
                    files.filter(
                                    candidate ->
                                            candidate
                                                    .getFileName()
                                                    .toString()
                                                    .matches("[a-f0-9]{64}\\.json"))
                            .sorted()
                            .limit(201)
                            .toList();
            if (paths.size() > 200) throw new ExperienceCapacityException("项目经验扫描超过200页");
            long[] bytes = {0};
            for (Path candidate : paths) {
                if (Files.isSymbolicLink(candidate))
                    throw new IllegalArgumentException("经验文件不可使用链接");
                bytes[0] += Files.size(candidate);
                if (bytes[0] > 8 * 1024 * 1024)
                    throw new ExperienceCapacityException("项目经验扫描超过8MB");
                var stored = read(candidate);
                if (stored == null) continue;
                forEachHistory(
                        candidate,
                        stored,
                        bytes,
                        correction -> {
                            if (result.size() >= 8
                                    || !correction.enabled()
                                    || (Objects.equals(correction.sourcePath(), path)
                                            && Objects.equals(
                                                    correction.packageVersion(), version))) return;
                            var changedIds = new HashSet<String>();
                            changedAfter(correction)
                                    .forEach(
                                            unit ->
                                                    unit.sources()
                                                            .forEach(
                                                                    ref ->
                                                                            changedIds.add(
                                                                                    ref
                                                                                            .sourceId())));
                            var targets =
                                    workspace.sources().stream()
                                            .filter(
                                                    target ->
                                                            correction.sources().stream()
                                                                    .anyMatch(
                                                                            source ->
                                                                                    changedIds
                                                                                                    .contains(
                                                                                                            source
                                                                                                                    .id())
                                                                                            && matchesCandidateSource(
                                                                                                    source,
                                                                                                    target)))
                                            .map(Source::id)
                                            .toList();
                            if (!targets.isEmpty())
                                result.add(new ExperienceReference(correction, targets, false));
                        });
                if (result.size() >= 8) break;
            }
        } catch (IOException e) {
            throw new IllegalStateException("项目经验读取失败");
        }
        return result.stream()
                .map(
                        reference ->
                                new ExperienceReference(
                                        reference.correction(),
                                        reference.targetSourceIds(),
                                        result.stream()
                                                .anyMatch(
                                                        other ->
                                                                other != reference
                                                                        && other
                                                                                .targetSourceIds()
                                                                                .stream()
                                                                                .anyMatch(
                                                                                        reference
                                                                                                        .targetSourceIds()
                                                                                                ::contains)
                                                                        && !decision(
                                                                                        other
                                                                                                .correction())
                                                                                .equals(
                                                                                        decision(
                                                                                                reference
                                                                                                        .correction())))))
                .toList();
    }

    private static String semantic(String value) {
        return value == null ? "" : value.replaceAll("[0-9０-９\\s+＋,.，。%％-]+", "");
    }

    static boolean matchesCandidateSource(Source previous, Source current) {
        return previous.kind().equals(current.kind())
                && !semantic(previous.rawValue()).isBlank()
                && semantic(previous.rawValue()).equals(semantic(current.rawValue()))
                && semantic(previous.context()).equals(semantic(current.context()));
    }

    static List<Unit> changedAfter(Correction correction) {
        return correction.after().stream()
                .filter(
                        after ->
                                correction.before().stream()
                                        .noneMatch(before -> sameDecision(before, after)))
                .toList();
    }

    private static boolean sameDecision(Unit before, Unit after) {
        return Objects.equals(before.name(), after.name())
                && Objects.equals(before.fieldType(), after.fieldType())
                && Objects.equals(before.sources(), after.sources())
                && before.ignored() == after.ignored()
                && Objects.equals(before.doubts(), after.doubts());
    }

    static List<Source> referencedSources(
            List<Source> sources, List<Unit> before, List<Unit> after) {
        var ids = new HashSet<String>();
        java.util.stream.Stream.concat(before.stream(), after.stream())
                .forEach(
                        unit -> unit.sources().forEach(reference -> ids.add(reference.sourceId())));
        var evidence = sources.stream().filter(source -> ids.contains(source.id())).toList();
        if (evidence.size() != ids.size()) throw new IllegalStateException("经验来源证据不完整");
        return evidence;
    }

    private static List<String> decision(Correction correction) {
        return changedAfter(correction).stream()
                .map(unit -> unit.name() + ":" + unit.fieldType() + ":" + unit.ignored())
                .toList();
    }

    public synchronized void setExperienceEnabled(
            String code,
            String version,
            String path,
            String id,
            long expectedVersion,
            boolean enabled,
            Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw new IllegalArgumentException("操作者无效");
        Path target = file(code, version, path);
        var stored = read(target);
        var old = findCorrection(target, stored, id);
        if (old.version() != expectedVersion) throw new IllegalArgumentException("经验已更新，请重新读取");
        if (old.enabled() == enabled) return;
        var updates = new java.util.LinkedHashMap<>(states(stored));
        updates.put(
                id,
                new ExperienceState(
                        old.version() + 1,
                        enabled,
                        operatorId,
                        java.time.Instant.now().toString()));
        write(
                target,
                new Stored(
                        stored.digest(),
                        stored.revision(),
                        stored.units(),
                        active(stored),
                        stored.confirmed(),
                        stored.draftId(),
                        archives(stored),
                        java.util.Map.copyOf(updates)));
    }

    public synchronized Workspace confirmed(String code, String version, String path) {
        var stored = read(file(code, version, path));
        if (stored == null || Boolean.FALSE.equals(stored.confirmed())) return null;
        var workspace = load(code, version, path);
        return Objects.equals(stored.digest(), workspace.digest()) ? workspace : null;
    }

    public synchronized Workspace recordAnalysis(
            String code,
            String version,
            String path,
            String digest,
            long baseRevision,
            List<Unit> units,
            String draftId) {
        var workspace = load(code, version, path);
        var current = read(file(code, version, path));
        if (!Objects.equals(digest, workspace.digest()) || workspace.revision() != baseRevision)
            throw new IllegalArgumentException("识别期间页面或方案已更新，请重新读取");
        if (current != null
                && Objects.equals(current.digest(), digest)
                && !Boolean.FALSE.equals(current.confirmed()))
            throw new IllegalArgumentException("当前来源已有已确认决定，不能被模型草稿覆盖");
        write(
                file(code, version, path),
                content(
                        current,
                        digest,
                        baseRevision + 1,
                        validate(units, workspace.sources()),
                        active(current),
                        false,
                        draftId));
        return load(code, version, path);
    }

    public java.util.Map<String, java.util.Set<String>> sourceAncestors(
            String code, String version, Workspace workspace) {
        return seeds.sourceAncestors(
                code,
                version,
                workspace.sourcePath(),
                workspace.digest(),
                workspace.sources().stream().map(Source::selector).distinct().toList());
    }

    public synchronized Workspace load(String code, String version, String path) {
        var preview = seeds.preview(code, version, path);
        var sources = sources(preview);
        var saved = read(file(code, version, path));
        List<String> warnings = new ArrayList<>(preview.warnings());
        warnings.add("来源原文只读；拆分使用原文区间，合并保留各来源。类型仅为建议，尚未创建字段或写入内容。");
        if (sources.stream().filter(TemplateContentUnitsService::businessCandidate).count() > 100)
            warnings.add("当前只初始化前100个来源单元，其余来源尚未处理；可从待处理原文来源中手动新增。候选总上限1000项，超出部分需分页面核对。");
        if (saved != null && Objects.equals(saved.digest(), preview.digest())) {
            return new Workspace(
                    path,
                    preview.digest(),
                    saved.revision(),
                    sources,
                    validate(saved.units(), sources),
                    warnings);
        }
        if (saved != null) warnings.add("页面已变更，旧判断没有套用；请核对新来源后重新保存。");
        List<Unit> units =
                sources.stream()
                        .filter(TemplateContentUnitsService::businessCandidate)
                        .filter(source -> !"BACKGROUND_IMAGE".equals(source.kind()))
                        .limit(100)
                        .map(
                                source ->
                                        new Unit(
                                                "unit-"
                                                        + UUID.nameUUIDFromBytes(
                                                                (preview.digest()
                                                                                + "\0"
                                                                                + source.id())
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)),
                                                "待命名内容",
                                                source.kind().equals("IMAGE")
                                                        ? "Image"
                                                        : "SingleLine",
                                                List.of(
                                                        new Reference(
                                                                source.id(),
                                                                0,
                                                                source.rawValue().length())),
                                                false,
                                                "静态来源候选，尚未由模型识别；请结合区域原文与预览判断"))
                        .toList();
        return new Workspace(
                path,
                preview.digest(),
                saved == null ? 0 : saved.revision(),
                sources,
                units,
                warnings);
    }

    /** 计划只能引用当前页面已经真正保存过的人工方案，不能使用来源变化后的默认候选。 */
    public synchronized Workspace requireSaved(String code, String version, String path) {
        Workspace workspace = load(code, version, path);
        Stored stored = read(file(code, version, path));
        if (stored == null
                || Boolean.FALSE.equals(stored.confirmed())
                || !Objects.equals(stored.digest(), workspace.digest())
                || stored.revision() != workspace.revision()) {
            throw new IllegalArgumentException("请先保存人工修正，再按当前来源版本生成计划");
        }
        return workspace;
    }

    public synchronized Workspace save(String code, String version, SaveRequest request) {
        return saveInternal(code, version, request, null);
    }

    public synchronized Workspace saveWithActor(
            String code, String version, SaveRequest request, Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw new IllegalArgumentException("操作者无效");
        return saveInternal(code, version, request, operatorId);
    }

    private Workspace saveInternal(
            String code, String version, SaveRequest request, Long operatorId) {
        if (request == null) throw new IllegalArgumentException("请提供内容单元");
        var preview = seeds.preview(code, version, request.sourcePath());
        if (!Objects.equals(preview.digest(), request.digest()))
            throw new IllegalArgumentException("页面来源已变更，请重新读取");
        var sources = sources(preview);
        var units = validate(request.units(), sources);
        Path target = file(code, version, request.sourcePath());
        var current = read(target);
        long revision = current == null ? 0 : current.revision();
        if (request.revision() != revision) throw new IllegalArgumentException("内容单元已更新，请重新读取后再保存");
        boolean same =
                current != null
                        && Objects.equals(current.digest(), preview.digest())
                        && current.units().equals(units);
        if (same && !Boolean.FALSE.equals(current.confirmed()))
            return load(code, version, request.sourcePath());
        var history = new ArrayList<>(active(current));
        var before = load(code, version, request.sourcePath()).units();
        if (operatorId != null && !before.equals(units)) {
            history.add(
                    new Correction(
                            UUID.randomUUID().toString(),
                            1,
                            true,
                            operatorId,
                            java.time.Instant.now().toString(),
                            version,
                            request.sourcePath(),
                            preview.digest(),
                            referencedSources(sources, before, units),
                            before,
                            units,
                            null,
                            null,
                            current != null && Objects.equals(current.digest(), preview.digest())
                                    ? (current.draftId() != null
                                            ? current.draftId()
                                            : "revision-" + current.revision())
                                    : "dom-" + preview.digest(),
                            current != null && Objects.equals(current.digest(), preview.digest())
                                    ? (current.draftId() != null ? "MODEL" : "CONFIRMED")
                                    : "DOM",
                            revision));
        }
        var next =
                content(
                        current,
                        preview.digest(),
                        revision + 1,
                        units,
                        List.copyOf(history),
                        true,
                        null);
        byte[] bytes = mapper.writeValueAsBytes(next);
        if ((history.size() > 50 || bytes.length > 1024 * 1024)
                && history.size() > active(current).size()
                && !active(current).isEmpty()) {
            byte[] archivedBytes = mapper.writeValueAsBytes(active(current));
            var archive =
                    new Archive(
                            UUID.randomUUID().toString(),
                            sha256(archivedBytes),
                            active(current).size(),
                            operatorId,
                            java.time.Instant.now().toString());
            var refs = new ArrayList<>(archives(current));
            refs.add(archive);
            next =
                    new Stored(
                            next.digest(),
                            next.revision(),
                            next.units(),
                            List.of(history.getLast()),
                            next.confirmed(),
                            next.draftId(),
                            List.copyOf(refs),
                            states(current));
            bytes = mapper.writeValueAsBytes(next);
            if (bytes.length > 1024 * 1024)
                throw new IllegalArgumentException("单条人工修正及当前方案元数据超过 1 MB，无法保存");
            // 批次先落盘，新方案与归档引用只在下方同一次Stored提交后生效。
            writeBytes(archiveFile(target, archive.id()), archivedBytes, false);
        }
        writeBytes(target, bytes, true);
        return load(code, version, request.sourcePath());
    }

    private void write(Path target, Stored stored) {
        writeBytes(target, mapper.writeValueAsBytes(stored), true);
    }

    private void writeBytes(Path target, byte[] bytes, boolean replace) {
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("内容单元元数据超过 1 MB");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".units-", ".tmp");
            Files.write(temporary, bytes);
            if (replace)
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            else {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("归档已存在");
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("内容单元保存失败，请重试");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    throw new IllegalStateException("内容单元临时文件清理失败");
                }
            }
        }
    }

    public static List<Source> sources(TemplateSeedService.Preview preview) {
        return preview.items().stream()
                .map(
                        item ->
                                new Source(
                                        item.id(),
                                        item.kind(),
                                        item.value(),
                                        item.selector(),
                                        item.context(),
                                        item.repeatGroup(),
                                        item.scriptInput(),
                                        item.publicNavigation(),
                                        item.publicRegion(),
                                        item.interactionPending(),
                                        item.backgroundBindable(),
                                        item.nonBodyMetadata()))
                .toList();
    }

    public static List<Unit> validate(List<Unit> units, List<Source> sources) {
        if (units == null || units.size() > MAX_UNITS)
            throw new IllegalArgumentException("内容单元最多 " + MAX_UNITS + " 项");
        var byId =
                sources.stream()
                        .collect(java.util.stream.Collectors.toMap(Source::id, source -> source));
        Set<String> ids = new HashSet<>();
        for (var unit : units) {
            if (unit == null
                    || unit.id() == null
                    || !unit.id().matches("[A-Za-z0-9-]{1,64}")
                    || !ids.add(unit.id())) throw new IllegalArgumentException("内容单元标识无效或重复");
            if (unit.name() == null
                    || unit.name().isBlank()
                    || unit.name().length() > 100
                    || unit.fieldType() == null
                    || !TYPES.contains(unit.fieldType()))
                throw new IllegalArgumentException("业务名或建议类型无效");
            if (unit.doubts() != null && unit.doubts().length() > 2000)
                throw new IllegalArgumentException("疑点说明超过 2000 字");
            if (unit.sources() == null || unit.sources().isEmpty() || unit.sources().size() > 20)
                throw new IllegalArgumentException("每个单元须引用 1 至 20 个来源");
            for (var reference : unit.sources()) {
                var source = reference == null ? null : byId.get(reference.sourceId());
                if (source == null
                        || reference.start() < 0
                        || reference.end() <= reference.start()
                        || reference.end() > source.rawValue().length())
                    throw new IllegalArgumentException("来源引用或原文区间无效");
                if ("BACKGROUND_IMAGE".equals(source.kind())
                        && (!Boolean.TRUE.equals(source.backgroundBindable()) || !"Image".equals(unit.fieldType())))
                    throw new IllegalArgumentException("仅可将单条本地背景图作为图片单元审核");
                if (!source.kind().equals("TEXT")
                        && (reference.start() != 0 || reference.end() != source.rawValue().length())
                        && !("SingleLine".equals(unit.fieldType())
                                && CounterScriptInputs.counterReference(source, reference)))
                    throw new IllegalArgumentException("图片和链接资源不能拆分地址");
                if (splitsSurrogate(source.rawValue(), reference.start())
                        || splitsSurrogate(source.rawValue(), reference.end()))
                    throw new IllegalArgumentException("拆分位置不能切开完整字符");
            }
        }
        return List.copyOf(units);
    }

    private static boolean splitsSurrogate(String value, int index) {
        return index > 0
                && index < value.length()
                && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private Path file(String code, String version, String path) {
        if (code == null
                || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || version == null
                || !version.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("站点或包版本无效");
        try {
            Files.createDirectories(root);
            Path base = root.toRealPath();
            Path directory = base.resolve(code);
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(base))
                throw new IllegalArgumentException("内容单元目录越界");
            byte[] hash =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest((version + "\0" + path).getBytes(StandardCharsets.UTF_8));
            Path result = directory.resolve(java.util.HexFormat.of().formatHex(hash) + ".json");
            if (Files.isSymbolicLink(result)) throw new IllegalArgumentException("内容单元文件不可使用链接");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("无法访问内容单元存储");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Stored read(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("内容单元存档超限");
            return mapper.readValue(bytes, Stored.class);
        } catch (IOException | tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("内容单元存档读取失败");
        }
    }
}

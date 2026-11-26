package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.cms.content.record.ContentRecordService;
import com.xinhongru.cms.template.SiteModelEvidenceService.SourceRecord;
import com.xinhongru.cms.template.SiteModelEvidenceService.Value;
import com.xinhongru.cms.template.SiteModelPlanService.Model;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.cms.template.SiteModelPlanService.RegionUse;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.ContentReferences;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentSeedReceiptMapper;
import com.xinhongru.mapper.ContentSeedReceiptMapper.Receipt;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 将已确认的重复区域原值初始化为正式栏目下的独立草稿。 */
@Service
public class SiteModelRecordSeedService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE_KEY_LENGTH = 24;
    private static final int REGION_KEY_LENGTH = 20;
    private static final int REGION_PREFIX_LENGTH = PAGE_KEY_LENGTH + REGION_KEY_LENGTH;
    private static final int OWNER_KEY_LENGTH = 9;
    private final SiteModelPlanService plans;
    private final SiteModelStructureService structures;
    private final SiteModelEvidenceService evidence;
    private final UiPackageStorageService storage;
    private final SiteProjectMapper projects;
    private final OneShotMapper menus;
    private final ContentRecordService records;
    private final ContentSeedReceiptMapper receipts;
    private final ContentExtensionResources resources;
    private final TransactionTemplate transactions;

    public SiteModelRecordSeedService(
            SiteModelPlanService plans,
            SiteModelStructureService structures,
            SiteModelEvidenceService evidence,
            UiPackageStorageService storage,
            SiteProjectMapper projects,
            OneShotMapper menus,
            ContentRecordService records,
            ContentSeedReceiptMapper receipts,
            ContentExtensionResources resources,
            TransactionTemplate transactions) {
        this.plans = plans;
        this.structures = structures;
        this.evidence = evidence;
        this.storage = storage;
        this.projects = projects;
        this.menus = menus;
        this.records = records;
        this.receipts = receipts;
        this.resources = resources;
        this.transactions = transactions;
    }

    public record Scope(Long siteId, Integer rootMenuId, String code, String packageVersion) {}

    public record Selection(
            long revision, String sourcePath, String regionId, Long ownerRecordId, Long menuId) {
        public Selection(long revision, String sourcePath, String regionId) {
            this(revision, sourcePath, regionId, null, null);
        }

        public Selection(long revision, String sourcePath, String regionId, Long ownerRecordId) {
            this(revision, sourcePath, regionId, ownerRecordId, null);
        }
    }

    public record Result(
            String sourcePath,
            String regionId,
            String modelKey,
            Long menuId,
            boolean ready,
            String previewDigest,
            List<String> issues,
            List<SeedRecord> records,
            Long ownerRecordId) {}

    public record SeedRecord(
            String sourceRecordId,
            String seedKey,
            Long contentId,
            Status status,
            List<FieldValue> fields,
            List<String> issues) {}

    public enum Status {
        PENDING,
        EXISTING,
        CREATED,
        BLOCKED,
        REMOVED
    }

    public record FieldValue(String fieldName, String label, Object rawValue, Object value) {}

    private record Target(
            RegionUse use,
            Model model,
            Set<String> samePageRegionIds,
            Set<String> referenceKeys,
            Long menuId,
            Integer ownerMenuId,
            Long ownerRecordId) {}

    private record Prepared(Result result, Map<String, String> signatures) {}

    private record PreparedRecord(SeedRecord row, String signature) {}

    public Result preview(Scope scope, String planId, Selection selection, Runnable check) {
        synchronized (plans) {
            check.run();
            var target = target(scope, confirmed(scope, planId, selection), selection);
            var menu = menus.getModelById(Math.toIntExact(target.menuId()));
            var result = prepare(scope, target, menu, false);
            check.run();
            return result.result();
        }
    }

    public Result importDrafts(
            Scope scope,
            String planId,
            Selection selection,
            String expectedDigest,
            long actor,
            Runnable check) {
        if (actor <= 0) throw new IllegalArgumentException("请重新登录后导入");
        if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("请预览并确认区域原值后再导入");
        synchronized (plans) {
            check.run();
            var plan = confirmed(scope, planId, selection);
            return Objects.requireNonNull(
                    transactions.execute(
                            transaction -> {
                                // 与栏目结构应用使用同一站点锁，再锁目标栏目字段。
                                var project = projects.selectByIdForUpdate(scope.siteId());
                                if (project == null
                                        || !Objects.equals(
                                                project.getRootMenuId(), scope.rootMenuId())
                                        || !Objects.equals(project.getProjectCode(), scope.code())
                                        || !Objects.equals(
                                                project.getUiPackageVersion(),
                                                scope.packageVersion()))
                                    throw new IllegalArgumentException("站点或页面包已变化，请重新读取整站计划");
                                check.run();
                                var target = target(scope, plan, selection);
                                var menu =
                                        menus.lockFieldConfiguration(
                                                scope.siteId(), target.menuId());
                                var prepared = prepare(scope, target, menu, true);
                                var preview = prepared.result();
                                if (!expectedDigest.equals(preview.previewDigest()))
                                    throw new IllegalArgumentException("初值预览已变化，请重新预览并确认");
                                if (!preview.ready()) return preview;
                                var imported =
                                        preview.records().stream()
                                                .map(
                                                        record ->
                                                                save(
                                                                        scope,
                                                                        preview.menuId(),
                                                                        target.ownerRecordId(),
                                                                        record,
                                                                        prepared.signatures()
                                                                                .get(
                                                                                        record
                                                                                                .seedKey()),
                                                                        actor))
                                                .toList();
                                check.run();
                                return new Result(
                                        preview.sourcePath(),
                                        preview.regionId(),
                                        preview.modelKey(),
                                        preview.menuId(),
                                        true,
                                        preview.previewDigest(),
                                        preview.issues(),
                                        imported,
                                        target.ownerRecordId());
                            }));
        }
    }

    private Plan confirmed(Scope scope, String planId, Selection selection) {
        if (selection == null
                || selection.revision() <= 0
                || selection.sourcePath() == null
                || selection.regionId() == null)
            throw new IllegalArgumentException("请选择已确认计划中的页面区域");
        return plans.confirmed(
                scope.siteId(), scope.code(), scope.packageVersion(), planId, selection.revision());
    }

    private Target target(Scope scope, Plan plan, Selection selection) {
        var use =
                plan.proposal().regions().stream()
                        .filter(
                                region ->
                                        selection.sourcePath().equals(region.sourcePath())
                                                && selection.regionId().equals(region.regionId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("页面区域不属于已确认计划"));
        var model =
                plan.proposal().models().stream()
                        .filter(item -> item.key().equals(use.modelKey()))
                        .findFirst()
                        .orElseThrow();
        var maintenance = use.maintenance();
        Long menuId = selection.menuId() == null ? model.menuId() : selection.menuId();
        Integer ownerMenuId = null;
        if (maintenance != null && "PAGE_LIST".equals(maintenance.mode())) {
            if (selection.ownerRecordId() != null || !Objects.equals(menuId, model.menuId()))
                throw new IllegalArgumentException("单页区域初值须使用审核计划对应的专用列表");
            ownerMenuId = plan.proposal().models().stream()
                    .filter(item -> item.key().equals(maintenance.ownerModelKey()))
                    .map(Model::menuId).filter(Objects::nonNull)
                    .map(Math::toIntExact).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("单页主体栏目已变化"));
        } else if (maintenance != null) {
            if (!"OWNED".equals(maintenance.mode()))
                throw new IllegalArgumentException("引用区域只能挑选已有内容，不能将原卡片导入为新内容");
            if (selection.ownerRecordId() == null)
                throw new IllegalArgumentException("请选择要归属的主体内容");
            var actual =
                    menuId == null || menuId <= 0 || menuId > Integer.MAX_VALUE
                            ? null
                            : menus.getModelById(menuId.intValue());
            if (actual == null
                    || actual.getParentId() <= 0
                    || !Objects.equals(scope.siteId(), actual.getSiteProjectId())
                    || !Objects.equals(scope.rootMenuId(), actual.getSiteRootId()))
                throw new IllegalArgumentException("请选择已应用的本站所属条目栏目");
            ownerMenuId = actual.getParentId();
            var structureScope =
                    new SiteModelStructureService.Scope(
                            scope.siteId(),
                            scope.code(),
                            scope.packageVersion(),
                            scope.rootMenuId());
            if (!Objects.equals(
                    actual.getId(),
                    structures.ownedMenuId(structureScope, plan, model, ownerMenuId)))
                throw new IllegalArgumentException("所属条目栏目与审核模型不一致");
        } else if (selection.ownerRecordId() != null) {
            throw new IllegalArgumentException("只有已审核的所属条目可以指定主体内容");
        } else if (!Objects.equals(menuId, model.menuId())) {
            throw new IllegalArgumentException("普通列表初值须使用审核计划对应的栏目");
        }
        var referenceKeys =
                plan.proposal().relations().stream()
                        .filter(relation -> model.key().equals(relation.sourceModelKey()))
                        .map(SiteModelPlanService.Relation::fieldKey)
                        .collect(Collectors.toSet());
        if (!"LIST".equals(use.mode())
                || use.records().size() < 2
                || !"LIST".equals(model.kind())
                || model.menuId() == null
                || model.fields().stream()
                        .anyMatch(
                                field ->
                                        field.fieldName() == null
                                                && !referenceKeys.contains(field.key())))
            throw new IllegalArgumentException("请先确认重复列表区域及对应的正式栏目字段");
        var modelsOnMenu =
                plan.proposal().models().stream()
                        .filter(item -> model.menuId().equals(item.menuId()))
                        .map(Model::key)
                        .collect(Collectors.toSet());
        var samePageRegionIds =
                plan.proposal().regions().stream()
                        .filter(
                                item ->
                                        use.sourcePath().equals(item.sourcePath())
                                                && modelsOnMenu.contains(item.modelKey())
                                                && "LIST".equals(item.mode()))
                        .map(RegionUse::regionId)
                        .collect(Collectors.toSet());
        return new Target(
                use,
                model,
                samePageRegionIds,
                referenceKeys,
                menuId,
                ownerMenuId,
                selection.ownerRecordId());
    }

    private Prepared prepare(Scope scope, Target target, OneshotEntity menu, boolean lock) {
        var use = target.use();
        var model = target.model();
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                || Objects.equals(menu.getId(), scope.rootMenuId())
                || !Integer.valueOf(1).equals(menu.getPageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel()))
            throw new IllegalArgumentException("目标须为本站已开启内容管理的列表栏目");
        if (target.ownerMenuId() != null) {
            boolean owned = target.ownerRecordId() != null;
            if (!Objects.equals(menu.getSiteRootId(), scope.rootMenuId())
                    || owned != Boolean.TRUE.equals(menu.getIsSubMenu())
                    || !target.ownerMenuId().equals(menu.getParentId()))
                throw new IllegalArgumentException("条目栏目已不属于已审核的主体栏目");
            if (owned) records.requireOwnerRecord(
                    scope.siteId(), menu.getParentId(), target.ownerRecordId(), lock);
        } else if (Boolean.TRUE.equals(menu.getIsSubMenu())) {
            throw new IllegalArgumentException("原生内容子栏目需先审核所属条目方式和主体内容");
        }
        var definitions =
                ContentModelCodec.read(menu.getModelField()).stream()
                        .filter(
                                field ->
                                        ContentFieldDefinition.enabled(field.getIsOpen())
                                                && !ContentFieldDefinition.enabled(
                                                        field.getIsReadOnly()))
                        .collect(
                                Collectors.toMap(
                                        ContentFieldDefinition::getFieldName, Function.identity()));
        var page = evidence.page(scope.code(), scope.packageVersion(), use.sourcePath());
        var region =
                page.regions().stream()
                        .filter(item -> use.regionId().equals(item.id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("页面区域已变化"));
        var bindingByRecord =
                use.records().stream()
                        .collect(
                                Collectors.toMap(
                                        SiteModelPlanService.RecordUse::recordId,
                                        SiteModelPlanService.RecordUse::bindings));
        if (!region.records().stream()
                .map(SourceRecord::id)
                .collect(Collectors.toSet())
                .equals(bindingByRecord.keySet()))
            throw new IllegalArgumentException("区域记录已变化，请重新确认");
        var issues = new ArrayList<String>();
        region.warnings().stream()
                .filter(warning -> !warning.equals("区域存在待核对的记录或字段，详见各记录。"))
                .forEach(issues::add);
        boolean hasSort =
                model.fields().stream()
                        .anyMatch(
                                field ->
                                        "Sort".equals(field.type())
                                                && "sort".equals(field.fieldName()));
        if (!hasSort) issues.add("请在已确认的列表模型中配置原生排序字段");
        Object seedSource =
                target.ownerRecordId() == null
                        ? use.sourcePath()
                        : List.of(use.sourcePath(), target.ownerRecordId());
        String pagePrefix = digest(use.sourcePath()).substring(0, PAGE_KEY_LENGTH);
        String regionPrefix = regionPrefix(pagePrefix, region.id());
        var pageReceipts = receipts.findPage(scope.siteId(), target.menuId(), pagePrefix + "%");
        String ownerKey =
                target.ownerRecordId() == null
                        ? ""
                        : "o" + HexFormat.of().toHexDigits(target.ownerRecordId().intValue());
        var otherScopes =
                receipts.findSourcePage(scope.siteId(), pagePrefix + "%").stream()
                        .filter(
                                receipt ->
                                        target.ownerRecordId() == null
                                                ? ownerFromKey(receipt.seedKey()) != 0
                                                : !target.menuId().equals(receipt.menuId())
                                                        || ownerFromKey(receipt.seedKey())
                                                                != target.ownerRecordId())
                        .toList();
        var conflicts =
                otherScopes.stream()
                        .filter(receipt -> receipt.seedKey().startsWith(regionPrefix))
                        .toList();
        if (!conflicts.isEmpty()) {
            var conflictingOwners =
                    conflicts.stream()
                            .map(receipt -> ownerFromKey(receipt.seedKey()))
                            .distinct()
                            .sorted()
                            .toList();
            var owners = conflictingOwners.stream().filter(owner -> owner > 0).toList();
            if (conflictingOwners.contains(0L)) issues.add("该原始区域已有普通列表初值，请核对现有内容及归属");
            if (!owners.isEmpty()) issues.add("该原始区域已登记给主体内容 " + owners + "，请核对现有归属");
            var otherMenus =
                    conflicts.stream()
                            .map(ContentSeedReceiptMapper.SourceReceipt::menuId)
                            .filter(id -> !target.menuId().equals(id))
                            .distinct()
                            .sorted()
                            .toList();
            if (!otherMenus.isEmpty()) issues.add("该原始区域的已有导入栏目：" + otherMenus);
            String blockedDigest =
                    digest(
                            List.of(
                                    scope,
                                    page.digest(),
                                    seedSource,
                                    target.menuId(),
                                    region.id(),
                                    conflicts.stream()
                                            .sorted(
                                                    Comparator.comparing(
                                                                    ContentSeedReceiptMapper
                                                                                    .SourceReceipt
                                                                            ::menuId)
                                                            .thenComparing(
                                                                    ContentSeedReceiptMapper
                                                                                    .SourceReceipt
                                                                            ::seedKey))
                                            .map(
                                                    receipt ->
                                                            List.of(
                                                                    receipt.menuId(),
                                                                    receipt.seedKey()))
                                            .toList()));
            return new Prepared(
                    new Result(
                            use.sourcePath(),
                            use.regionId(),
                            model.key(),
                            target.menuId(),
                            false,
                            blockedDigest,
                            List.copyOf(issues),
                            List.of(),
                            target.ownerRecordId()),
                    Map.of());
        }
        var previous =
                target.ownerRecordId() == null
                        ? pageReceipts
                        : receipts.findOwnedPage(
                                scope.siteId(),
                                target.menuId(),
                                pagePrefix + "%",
                                target.ownerRecordId());
        var existing =
                previous.stream().collect(Collectors.toMap(Receipt::seedKey, Function.identity()));
        var paths =
                storage.inspectionFiles(scope.code(), scope.packageVersion()).stream()
                        .map(UiPackageStorageService.InspectionFile::path)
                        .collect(Collectors.toSet());
        var rows = new ArrayList<SeedRecord>();
        var signatures = new LinkedHashMap<String, String>();
        var confirmedBindings = new LinkedHashMap<String, Map<String, String>>();
        var resourceDigests = new HashMap<String, String>();
        for (int index = 0; index < region.records().size(); index++) {
            var source = region.records().get(index);
            String key =
                    regionPrefix
                            + ownerKey
                            + digest(source.id())
                                    .substring(0, 64 - REGION_PREFIX_LENGTH - ownerKey.length());
            var bindings = bindingByRecord.get(source.id());
            confirmedBindings.put(source.id(), new TreeMap<>(bindings));
            var prepared =
                    prepareRecord(
                            scope,
                            use,
                            model,
                            target.referenceKeys(),
                            definitions,
                            paths,
                            resourceDigests,
                            source,
                            bindings,
                            region.records().size() - index,
                            key,
                            existing.get(key));
            rows.add(prepared.row());
            signatures.put(key, prepared.signature());
        }
        boolean ambiguous =
                regionIdentityChanged(
                        pagePrefix, regionPrefix, target.samePageRegionIds(), existing, signatures);
        if (ambiguous) issues.add("同页同栏目已有初值导入，但区域或记录位置变化，或原值重叠，请核对现有内容后人工处理");
        var sourceSignatures = Set.copyOf(signatures.values());
        boolean importedElsewhere =
                otherScopes.stream()
                        .anyMatch(receipt -> sourceSignatures.contains(receipt.sourceSignature()));
        if (importedElsewhere) issues.add("同页原值在其他栏目或主体下已有导入记录，请核对现有归属");
        String digest =
                digest(
                        List.of(
                                scope,
                                page.digest(),
                                seedSource,
                                use.regionId(),
                                model,
                                target.menuId(),
                                menu.getModelField(),
                                confirmedBindings,
                                signatures));
        var result =
                new Result(
                        use.sourcePath(),
                        use.regionId(),
                        model.key(),
                        target.menuId(),
                        hasSort
                                && !ambiguous
                                && !importedElsewhere
                                && region.warnings().stream()
                                        .noneMatch(SiteModelRecordSeedService::incomplete)
                                && rows.stream().noneMatch(row -> row.status() == Status.BLOCKED),
                        digest,
                        List.copyOf(issues),
                        List.copyOf(rows),
                        target.ownerRecordId());
        return new Prepared(result, signatures);
    }

    private static long ownerFromKey(String key) {
        if (key.charAt(REGION_PREFIX_LENGTH) != 'o') return 0L;
        return Long.parseLong(
                key.substring(REGION_PREFIX_LENGTH + 1, REGION_PREFIX_LENGTH + OWNER_KEY_LENGTH),
                16);
    }

    private static boolean regionIdentityChanged(
            String pagePrefix,
            String selectedRegion,
            Set<String> currentRegions,
            Map<String, Receipt> existing,
            Map<String, String> signatures) {
        var knownRecordKeys =
                existing.keySet().stream().filter(key -> key.startsWith(selectedRegion)).toList();
        if (!knownRecordKeys.isEmpty())
            return !signatures.keySet().containsAll(knownRecordKeys)
                    && signatures.keySet().stream().anyMatch(key -> !existing.containsKey(key));
        var currentPrefixes =
                currentRegions.stream()
                        .map(id -> regionPrefix(pagePrefix, id))
                        .collect(Collectors.toSet());
        var sourceSignatures = Set.copyOf(signatures.values());
        return existing.values().stream()
                .anyMatch(
                        receipt ->
                                !currentPrefixes.contains(
                                                receipt.seedKey()
                                                        .substring(0, REGION_PREFIX_LENGTH))
                                        || sourceSignatures.contains(receipt.sourceSignature()));
    }

    private PreparedRecord prepareRecord(
            Scope scope,
            RegionUse use,
            Model model,
            Set<String> referenceKeys,
            Map<String, ContentFieldDefinition> definitions,
            Set<String> paths,
            Map<String, String> resourceDigests,
            SourceRecord source,
            Map<String, String> bindings,
            int sort,
            String key,
            Receipt existing) {
        var issues = new ArrayList<>(source.warnings());
        int warnings = issues.size();
        var fields = new ArrayList<FieldValue>();
        var bindingsInOrder = new LinkedHashMap<String, List<String>>();
        var fingerprints = new LinkedHashMap<String, String>();
        var values =
                source.values().stream().collect(Collectors.toMap(Value::id, Function.identity()));
        for (var field : model.fields()) {
            String name = field.fieldName();
            var definition = definitions.get(name);
            if (referenceKeys.contains(field.key()) || ContentReferences.applies(definition)) {
                if (bindings.containsKey(field.key()))
                    issues.add(field.name() + "：内容引用只能选择实际记录，不能导入页面原值");
                continue;
            }
            var original = values.get(bindings.get(field.key()));
            if (original != null)
                bindingsInOrder.put(
                        field.key(), List.of(original.attribute(), original.typeHint()));
            Object raw = original == null ? null : original.rawValue();
            Object value = null;
            try {
                if ("Sort".equals(field.type()) && "sort".equals(name)) value = sort;
                else if (definition == null || !field.type().equals(definition.getFieldType()))
                    throw new IllegalArgumentException("字段未开启、不可编辑或类型已变化");
                else if (original != null) {
                    value = prepareValue(scope, use.sourcePath(), definition, original, paths);
                    if (value instanceof String reference
                            && Set.of("Image", "File").contains(field.type())) {
                        String path = reference.substring(4).split("[?#]", 2)[0];
                        fingerprints.put(
                                name,
                                resourceDigests.computeIfAbsent(
                                        path,
                                        item ->
                                                storage.inspectionResourceDigest(
                                                        scope.code(),
                                                        scope.packageVersion(),
                                                        item)));
                    }
                }
            } catch (DateTimeParseException failure) {
                value = null;
                issues.add(field.name() + "：来源不是有效的完整日期");
            } catch (IllegalArgumentException failure) {
                value = null;
                issues.add(field.name() + "：" + failure.getMessage());
            }
            fields.add(new FieldValue(name, field.name(), raw, value));
        }
        for (var definition : definitions.values()) {
            if (ContentFieldDefinition.enabled(definition.getIsRequired())
                    && fields.stream()
                            .noneMatch(
                                    field ->
                                            definition.getFieldName().equals(field.fieldName())
                                                    && field.value() != null
                                                    && !field.value().toString().isBlank()))
                issues.add("缺少必填字段：" + definition.getTitle());
        }
        if (fields.stream()
                .noneMatch(field -> !"sort".equals(field.fieldName()) && field.value() != null))
            issues.add("此记录没有可导入的业务字段");
        boolean blocked =
                issues.size() > warnings
                        || source.warnings().stream()
                                .anyMatch(
                                        warning ->
                                                incomplete(warning) || warning.startsWith("视频变体"));
        String signature =
                digest(
                        List.of(
                                bindingsInOrder,
                                fields.stream()
                                        .filter(field -> !"sort".equals(field.fieldName()))
                                        .toList(),
                                fingerprints));
        if (existing != null && !signature.equals(existing.sourceSignature())) {
            issues.add("当前包内记录或资源与原导入证据不同，请在内容管理中人工核对");
            blocked = true;
        }
        var status =
                blocked
                        ? Status.BLOCKED
                        : existing == null
                                ? Status.PENDING
                                : existing.activeContentId() == null
                                        ? Status.REMOVED
                                        : Status.EXISTING;
        return new PreparedRecord(
                new SeedRecord(
                        source.id(),
                        key,
                        existing == null ? null : existing.activeContentId(),
                        status,
                        List.copyOf(fields),
                        List.copyOf(issues)),
                signature);
    }

    private static boolean incomplete(String warning) {
        return warning.contains("超过") || warning.contains("未扫描节点");
    }

    private Object prepareValue(
            Scope scope,
            String sourcePath,
            ContentFieldDefinition definition,
            Value original,
            Set<String> paths) {
        String raw = original.rawValue();
        return switch (definition.getFieldType()) {
            case "Image", "File" ->
                    resources.prepareTemplateMedia(
                            definition,
                            EditorContentPreparation.packageReference(sourcePath, raw),
                            scope.siteId());
            case "SingleLine", "MultiLine" -> {
                if ("LINK".equals(original.typeHint())) validateLink(sourcePath, raw, paths);
                if (definition.getMaxLength() != null
                        && definition.getMaxLength() > 0
                        && raw.length() > definition.getMaxLength())
                    throw new IllegalArgumentException("原值超过栏目字段长度");
                yield raw;
            }
            case "DateTime" -> {
                TemplateDateValues.validateField(definition, raw);
                yield TemplateDateValues.initial(raw);
            }
            case "Video" -> throw new IllegalArgumentException("视频播放结构待人工核对，未导入");
            case "Editor" -> throw new IllegalArgumentException("重复记录富文本待人工核对，未导入");
            default -> throw new IllegalArgumentException("当前记录字段类型待人工处理");
        };
    }

    private static void validateLink(String sourcePath, String raw, Set<String> paths) {
        URI uri = URI.create(raw.strip());
        if (uri.isAbsolute()) {
            if (Set.of("http", "https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null) return;
        } else if (SiteModelEvidenceService.resolveLocal(sourcePath, raw, paths) != null) return;
        throw new IllegalArgumentException("链接未指向有效的包内文件或 HTTP(S) 地址");
    }

    private SeedRecord save(
            Scope scope,
            Long menuId,
            Long ownerRecordId,
            SeedRecord record,
            String signature,
            long actor) {
        if (record.status() != Status.PENDING) return record;
        Map<String, Object> payload = new LinkedHashMap<>();
        int sort = 0;
        for (var field : record.fields()) {
            if ("sort".equals(field.fieldName())) sort = (int) field.value();
            else if (field.value() != null) payload.put(field.fieldName(), field.value());
        }
        if (ownerRecordId != null) payload.put("xgid", ownerRecordId);
        var saved =
                records.saveSeedDraft(
                        new ContentRecordService.SaveDraftCommand(
                                scope.siteId(), menuId, null, Map.of(), payload, actor),
                        sort);
        if (receipts.insert(scope.siteId(), menuId, record.seedKey(), signature, saved.recordId())
                != 1) throw new IllegalStateException("初值导入收据保存失败");
        return new SeedRecord(
                record.sourceRecordId(),
                record.seedKey(),
                saved.recordId(),
                Status.CREATED,
                record.fields(),
                record.issues());
    }

    // 固定长度前缀用于核对同一页面、栏目下已导入的区域，记录仍以源DOM定位区分。
    private static String regionPrefix(String pagePrefix, String regionId) {
        return pagePrefix + digest(regionId).substring(0, REGION_KEY_LENGTH);
    }

    private static String digest(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(JSON.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

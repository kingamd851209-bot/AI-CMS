package com.xinhongru.cms.template;

import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.CounterNumbers;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 字段计划仅核验与映射；新增字段必须调用既有人工作字段保存API。 */
@Service
public class TemplateFieldPlanService {
    private static final String MENU_NAME = "MENU_NAME";

    private final TemplateContentUnitsService units;
    private final OneShotMapper menus;
    private final String metadataRoot;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SiteModelPlanService siteModelPlans;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SourceValidator sourceValidator;

    @FunctionalInterface
    public interface SourceValidator {
        void validate(Plan plan, OneshotEntity menu);
    }

    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();

    public TemplateFieldPlanService(
            TemplateContentUnitsService units,
            OneShotMapper menus,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String metadataRoot) {
        this.units = units;
        this.menus = menus;
        this.metadataRoot = metadataRoot;
    }

    public record Request(
            String sourcePath,
            String digest,
            long unitsRevision,
            Long menuId,
            Map<String, String> choices) {}

    public record Field(
            String fieldName,
            String name,
            String fieldType,
            int capacity,
            boolean writable,
            boolean currentValuePresent,
            String currentValuePreview,
            String inputType) {
        public Field(
                String fieldName,
                String name,
                String fieldType,
                int capacity,
                boolean writable,
                boolean currentValuePresent,
                String currentValuePreview) {
            this(
                    fieldName,
                    name,
                    fieldType,
                    capacity,
                    writable,
                    currentValuePresent,
                    currentValuePreview,
                    null);
        }
    }

    public record Binding(
            String unitId,
            String name,
            String fieldType,
            List<TemplateContentUnitsService.Reference> sources,
            String action,
            String fieldName,
            int capacity,
            int valueLength,
            String valuePreview,
            String message,
            boolean currentValuePresent,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean locked,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Long menuId) {
        public Binding(String unitId, String name, String fieldType, List<TemplateContentUnitsService.Reference> sources,
                       String action, String fieldName, int capacity, int valueLength, String valuePreview,
                       String message, boolean currentValuePresent, Boolean locked) {
            this(unitId, name, fieldType, sources, action, fieldName, capacity, valueLength,
                    valuePreview, message, currentValuePresent, locked, null);
        }

        public Binding(String unitId, String name, String fieldType, List<TemplateContentUnitsService.Reference> sources,
                       String action, String fieldName, int capacity, int valueLength, String valuePreview,
                       String message, boolean currentValuePresent) {
            this(unitId, name, fieldType, sources, action, fieldName, capacity, valueLength,
                    valuePreview, message, currentValuePresent, null, null);
        }
    }

    public record PairingReviewIdentity(TemplateListPairingService.Request pairing, String cardId,
            long reviewRevision, String evidenceDigest,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Boolean sharedDetail) {
        public PairingReviewIdentity(TemplateListPairingService.Request pairing, String cardId,
                long reviewRevision, String evidenceDigest) {
            this(pairing, cardId, reviewRevision, evidenceDigest, null);
        }
    }

    public record Origin(String role, String sourcePath, String digest, long unitsRevision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            PairingReviewIdentity pairingReview) {
        public Origin(String role, String sourcePath, String digest, long unitsRevision) {
            this(role, sourcePath, digest, unitsRevision, null);
        }
    }

    public record Plan(
            int schemaVersion,
            String id,
            Long siteId,
            String code,
            String packageVersion,
            String sourcePath,
            String digest,
            long unitsRevision,
            Long menuId,
            int pageType,
            String fieldConfigDigest,
            String afterFieldConfigDigest,
            List<Binding> bindings,
            List<TemplateContentUnitsService.Source> sources,
            List<Field> fields,
            SetFieldDto fieldUpdate,
            boolean canConfirm,
            String status,
            List<String> warnings,
            List<Origin> origins,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String sharedDetailSeedPlanId) {
        public Plan(int schemaVersion, String id, Long siteId, String code, String packageVersion, String sourcePath,
                String digest, long unitsRevision, Long menuId, int pageType, String fieldConfigDigest,
                String afterFieldConfigDigest, List<Binding> bindings, List<TemplateContentUnitsService.Source> sources,
                List<Field> fields, SetFieldDto fieldUpdate, boolean canConfirm, String status,
                List<String> warnings, List<Origin> origins) {
            this(schemaVersion, id, siteId, code, packageVersion, sourcePath, digest, unitsRevision, menuId, pageType,
                    fieldConfigDigest, afterFieldConfigDigest, bindings, sources, fields, fieldUpdate, canConfirm,
                    status, warnings, origins, null);
        }
        public Plan(
                int schemaVersion,
                String id,
                Long siteId,
                String code,
                String packageVersion,
                String sourcePath,
                String digest,
                long unitsRevision,
                Long menuId,
                int pageType,
                String fieldConfigDigest,
                String afterFieldConfigDigest,
                List<Binding> bindings,
                List<TemplateContentUnitsService.Source> sources,
                List<Field> fields,
                SetFieldDto fieldUpdate,
                boolean canConfirm,
                String status,
                List<String> warnings) {
            this(
                    schemaVersion,
                    id,
                    siteId,
                    code,
                    packageVersion,
                    sourcePath,
                    digest,
                    unitsRevision,
                    menuId,
                    pageType,
                    fieldConfigDigest,
                    afterFieldConfigDigest,
                    bindings,
                    sources,
                    fields,
                    fieldUpdate,
                    canConfirm,
                    status,
                    warnings,
                    null);
        }
    }

    public synchronized Plan preview(Long siteId, String code, String version, Request request) {
        return previewInternal(siteId, code, version, request, null, false, Set.of());
    }

    synchronized Plan previewWithOrigin(
            Long siteId,
            String code,
            String version,
            Request request,
            Consumer<Plan> beforePersist) {
        return previewInternal(
                siteId, code, version, request, Objects.requireNonNull(beforePersist), false, Set.of());
    }

    synchronized Plan previewWithExplicitCreation(Long siteId, String code, String version,
                                                   Request request, Set<String> lockedUnits, Consumer<Plan> beforePersist) {
        return previewInternal(siteId, code, version, request, Objects.requireNonNull(beforePersist), true, lockedUnits);
    }

    private Plan previewInternal(
            Long siteId,
            String code,
            String version,
            Request request,
            Consumer<Plan> beforePersist, boolean explicitCreation, Set<String> lockedUnits) {
        if (request == null
                || request.menuId() == null
                || request.menuId() <= 0
                || request.menuId() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("请选择目标栏目");
        var workspace = units.requireSaved(code, version, request.sourcePath());
        if (workspace.revision() <= 0
                || workspace.revision() != request.unitsRevision()
                || !Objects.equals(workspace.digest(), request.digest()))
            throw new IllegalArgumentException("请先保存人工修正，再按当前来源版本生成计划");
        Plan plan =
                build(
                        siteId,
                        code,
                        version,
                        request,
                        workspace,
                        menus.getModelById(Math.toIntExact(request.menuId())),
                        UUID.randomUUID().toString(),
                        List.of(), explicitCreation, lockedUnits);
        if (beforePersist != null) beforePersist.accept(plan);
        persist(plan);
        return plan;
    }

    private Plan build(
            Long siteId,
            String code,
            String version,
            Request request,
            TemplateContentUnitsService.Workspace workspace,
            OneshotEntity menu,
            String id,
            List<Origin> origins, boolean explicitCreation, Set<String> lockedUnits) {
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || Boolean.FALSE.equals(menu.getHasManageChannel())
                || (!Integer.valueOf(0).equals(menu.getPageType())
                        && !Integer.valueOf(1).equals(menu.getPageType())))
            throw new IllegalArgumentException("目标必须为当前站点可管理的单页或列表栏目");
        int type = menu.getPageType() == 0 ? 1 : 0;
        var definitions =
                new ArrayList<>(
                        ContentModelCodec.read(
                                type == 1 ? menu.getMenuField() : menu.getModelField()));
        var fields = definitions.stream().map(field -> describe(menu, field)).toList();
        var sourceMap =
                workspace.sources().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        TemplateContentUnitsService.Source::id, source -> source));
        Map<String, String> choices = request.choices() == null ? Map.of() : request.choices();
        Set<String> knownUnits =
                workspace.units().stream()
                        .map(TemplateContentUnitsService.Unit::id)
                        .collect(java.util.stream.Collectors.toSet());
        if (!knownUnits.containsAll(choices.keySet()))
            throw new IllegalArgumentException("映射包含当前方案之外的单元");
        var rows = new ArrayList<Binding>();
        var warnings = new ArrayList<String>();
        Set<String> assigned = new HashSet<>();
        int created = 0;
        boolean canConfirm = true;
        for (var unit : workspace.units()) {
            String value = original(unit, sourceMap);
            if (unit.ignored()) {
                rows.add(row(unit, "IGNORED", null, 0, value, "已忽略，不进入字段更新", false));
                continue;
            }
            boolean text =
                    unit.sources().stream()
                            .allMatch(
                                    reference ->
                                            "TEXT"
                                                    .equals(
                                                            sourceMap
                                                                    .get(reference.sourceId())
                                                                    .kind()));
            boolean date =
                    "DateTime".equals(unit.fieldType())
                            && text
                            && unit.sources().size() == 1
                            && unit.sources().getFirst().start() == 0
                            && unit.sources().getFirst().end()
                                    == sourceMap
                                            .get(unit.sources().getFirst().sourceId())
                                            .rawValue()
                                            .length()
                            && TemplateDateValues.supported(value);
            boolean counterRequested =
                    unit.sources().stream()
                            .anyMatch(
                                    reference ->
                                            CounterScriptInputs.knownCounter(
                                                    sourceMap.get(reference.sourceId())));
            String selection = choices.get(unit.id());
            if (selection != null && selection.startsWith(MENU_NAME + ":")) {
                if (!origins.isEmpty() || lockedUnits.contains(unit.id())
                        || !"SingleLine".equals(unit.fieldType()) || !text || counterRequested)
                    throw new IllegalArgumentException("栏目名称仅支持独立页面的单行静态文本单元");
                Long childId = parseChildMenuId(selection);
                var child = requireChildMenuNameSource(siteId, request.menuId(), childId);
                rows.add(row(unit, MENU_NAME, null, 0, value,
                        "显示子栏目“" + child.getClassName() + "”的当前名称，初值不写入内容字段",
                        true, childId));
                continue;
            }
            boolean counter =
                    counterRequested
                            && "SingleLine".equals(unit.fieldType())
                            && unit.sources().size() == 1
                            && CounterScriptInputs.counterReference(
                                    sourceMap.get(unit.sources().getFirst().sourceId()),
                                    unit.sources().getFirst());
            String counterInput =
                    counter
                            ? CounterScriptInputs.inputType(
                                    sourceMap.get(unit.sources().getFirst().sourceId()))
                            : "";
            if (counterRequested && !counter) {
                rows.add(
                        row(
                                unit,
                                "PENDING_TYPE",
                                null,
                                0,
                                value,
                                "脚本统计需一个已确认的数值入口；限定符与特殊布局须人工审查，不能直接绑定整段原文",
                                false));
                continue;
            }
            text = text || counter;
            boolean media = false;
            if (Set.of("Image", "Video").contains(unit.fieldType()) && unit.sources().size() == 1) {
                var reference = unit.sources().getFirst();
                var evidence = sourceMap.get(reference.sourceId());
                media =
                        reference.start() == 0
                                && reference.end() == evidence.rawValue().length()
                                && ("Image".equals(unit.fieldType())
                                        ? Set.of("IMAGE", "VIDEO_POSTER").contains(evidence.kind())
                                                || "BACKGROUND_IMAGE".equals(evidence.kind())
                                                        && Boolean.TRUE.equals(evidence.backgroundBindable())
                                        : Set.of("VIDEO_SRC", "VIDEO_SOURCE")
                                                .contains(evidence.kind()));
            }
            boolean editor = "Editor".equals(unit.fieldType()) && unit.sources().size() == 1;
            if (editor) {
                var reference = unit.sources().getFirst();
                var evidence = sourceMap.get(reference.sourceId());
                editor =
                        "HTML_REGION".equals(evidence.kind())
                                && reference.start() == 0
                                && reference.end() == evidence.rawValue().length();
                String problem =
                        EditorRegions.problem(
                                org.jsoup.Jsoup.parseBodyFragment(evidence.rawValue())
                                        .body()
                                        .tagName("div"));
                if (!editor || problem != null) {
                    rows.add(
                            row(
                                    unit,
                                    "PENDING_TYPE",
                                    null,
                                    0,
                                    value,
                                    problem != null
                                            ? problem
                                            : "Editor须完整独立HTML区域证据；固定布局来源或拆分范围仍待处理",
                                    false));
                    continue;
                }
            }
            if (!editor
                    && !media
                    && !date
                    && (!text || !Set.of("SingleLine", "MultiLine").contains(unit.fieldType()))) {
                rows.add(
                        row(
                                unit,
                                "PENDING_TYPE",
                                null,
                                0,
                                value,
                                "DateTime".equals(unit.fieldType())
                                        ? "日期须一个完整合法日精度TEXT，支持yyyy/MM/dd、yyyy-MM-dd或yyyy.MM.dd；范围、模糊日期和时间不推测，保留待处理"
                                        : "此来源/类型的绑定尚未开放，保留待处理，不假装完成",
                                false));
                continue;
            }
            int maximum =
                    media
                            ? 2048
                            : editor
                                    ? 200000
                                    : date
                                            ? 10
                                            : counter
                                                    ? 64
                                                    : unit.fieldType().equals("SingleLine")
                                                            ? 500
                                                            : 10000;
            if (value.length() > maximum
                    || (unit.fieldType().equals("SingleLine")
                            && (value.contains("\n") || value.contains("\r")))) {
                rows.add(
                        row(
                                unit,
                                "CONFLICT",
                                null,
                                maximum,
                                value,
                                "原文超出所选类型容量或含换行，请先人工修正类型/拆分",
                                false));
                canConfirm = false;
                continue;
            }
            List<Field> compatible =
                    fields.stream()
                            .filter(
                                    field ->
                                            field.writable()
                                                    && field.fieldType().equals(unit.fieldType())
                                                    && field.capacity() >= value.length()
                                                    && (!date
                                                            || definitions.stream()
                                                                    .anyMatch(
                                                                            definition ->
                                                                                    Objects.equals(
                                                                                                    definition
                                                                                                            .getFieldName(),
                                                                                                    field
                                                                                                            .fieldName())
                                                                                            && ContentExtensionFields
                                                                                                    .isName(
                                                                                                            definition
                                                                                                                    .getFieldName())
                                                                                            && "DAY"
                                                                                                    .equals(
                                                                                                            com
                                                                                                                    .xinhongru
                                                                                                                    .content
                                                                                                                    .model
                                                                                                                    .ContentDateTimes
                                                                                                                    .precision(
                                                                                                                            definition))))
                                                    && (!counter
                                                            || definitions.stream()
                                                                    .anyMatch(
                                                                            definition ->
                                                                                    Objects.equals(
                                                                                                    definition
                                                                                                            .getFieldName(),
                                                                                                    field
                                                                                                            .fieldName())
                                                                                            && counterInput
                                                                                                    .equals(
                                                                                                            definition
                                                                                                                    .getInputType()))))
                            .toList();
            if (selection == null && !explicitCreation) {
                var exact =
                        compatible.stream()
                                .filter(
                                        field ->
                                                field.name()
                                                        .trim()
                                                        .equalsIgnoreCase(unit.name().trim()))
                                .toList();
                if (exact.size() == 1) selection = exact.get(0).fieldName();
                else if (exact.size() > 1) {
                    rows.add(
                            row(
                                    unit,
                                    "CONFLICT",
                                    null,
                                    0,
                                    value,
                                    "存在多个同名兼容字段，请人工选择用途一致的字段",
                                    false));
                    canConfirm = false;
                    continue;
                }
            }
            if (selection == null && explicitCreation) {
                rows.add(row(unit, "CONFLICT", null, 0, value,
                        "请选择复用字段、显示子栏目名称或新增字段，或回原页忽略此项", false));
                canConfirm = false;
                continue;
            }
            if (selection != null && !selection.equals("$new")) {
                String selected = selection;
                Field field =
                        compatible.stream()
                                .filter(candidate -> candidate.fieldName().equals(selected))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        counter
                                                                ? "统计复用必须是显式"
                                                                        + counterInput
                                                                        + "字段；旧字段不自动改约束，请新建或选择同约束字段"
                                                                : "所选复用字段未启用、只读、类型或容量不兼容"));
                if (!assigned.add(field.fieldName())) {
                    rows.add(
                            row(
                                    unit,
                                    "CONFLICT",
                                    field.fieldName(),
                                    field.capacity(),
                                    value,
                                    "多个单元占用同一目标字段，请先合并或改选",
                                    field.currentValuePresent()));
                    canConfirm = false;
                    continue;
                }
                rows.add(
                        row(
                                unit,
                                "REUSE",
                                field.fieldName(),
                                field.capacity(),
                                value,
                                (counter
                                                ? "明确复用" + counterInput + "统计数值约束；"
                                                : date ? "明确复用DAY日精度DateTime，保原页面日期格式、不补时间；" : "")
                                        + "请确认与已有用途“"
                                        + field.name()
                                        + "”同语义；仅新增映射，不改原名称、类型或已有值",
                                field.currentValuePresent()));
            } else {
                String fieldName = "ext_" + UUID.randomUUID().toString().replace("-", "");
                var field =
                        new ContentFieldDefinition()
                                .setFieldName(fieldName)
                                .setFromName(unit.name())
                                .setTitle(unit.name())
                                .setFieldType(unit.fieldType())
                                .setDatePrecision(date ? "DAY" : null)
                                .setMaxLength(maximum)
                                .setIsOpen("on")
                                .setIsList("")
                                .setIsSearch("")
                                .setInputType(counterInput)
                                .setInputMultiple(false)
                                .setDefaultValue("");
                ContentExtensionFields.validateDefinition(field);
                definitions.add(field);
                created++;
                rows.add(
                        row(
                                unit,
                                "CREATE",
                                fieldName,
                                maximum,
                                value,
                                date
                                        ? "新增DAY日精度DateTime：只存日期、不补时刻；公开/私有显示保持原页面白名单格式，请确认影响"
                                        : counter
                                                ? "新增显式统计数值约束"
                                                        + counterInput
                                                        + "：数字与单位分离；沿CMS必填配置，不迁移旧字段，请确认用途与范围"
                                                : "缺少已确认复用字段：将通过CMS人工同一API新增业务字段；不占原槽位、不执行DDL、不初始化内容",
                                false));
            }
        }
        rows.replaceAll(row -> lockedUnits.contains(row.unitId()) && "REUSE".equals(row.action())
                ? new Binding(row.unitId(), row.name(), row.fieldType(), row.sources(), row.action(),
                        row.fieldName(), row.capacity(), row.valueLength(), row.valuePreview(), row.message(),
                        row.currentValuePresent(), true) : row);
        SetFieldDto update =
                created == 0
                        ? null
                        : new SetFieldDto()
                                .setId(menu.getId())
                                .setType(type)
                                .setFieldOptions(definitions)
                                .setFieldListDisplay(ContentModelCodec.read(menu.getModelList()))
                                .setFieldSearch(ContentModelCodec.read(menu.getModelSearch()));
        if (update != null
                && type == 0
                && (update.getFieldListDisplay().isEmpty() || update.getFieldSearch().isEmpty())) {
            canConfirm = false;
            warnings.add("目标列表栏目缺少列表/搜索配置，请先在人工字段界面补齐，正式保存API不会被绕过。");
        }
        OneshotEntity after = new OneshotEntity();
        BeanUtils.copyProperties(menu, after);
        if (update != null) {
            if (type == 1) after.setMenuField(ContentModelCodec.write(definitions));
            else after.setModelField(ContentModelCodec.write(definitions));
            after.setModelList(ContentModelCodec.write(update.getFieldListDisplay()));
            after.setModelSearch(ContentModelCodec.write(update.getFieldSearch()));
        }
        warnings.add("精确业务名只用于确定候选，不代表AI语义识别；可人工从兼容字段确认同义复用。所有已有用途和内容值保持不变。");
        warnings.add("本计划保留原DOM定位、来源区间与页面摘要；跨文本节点能否安全绑定须后续核对，不能整块替换吞掉子元素。");
        warnings.add("本计划只确认字段配置。单页使用menuField/Oneshot，列表使用modelField/Content；未初始化内容或绑定模板。");
        Plan plan =
                new Plan(
                        3,
                        id,
                        siteId,
                        code,
                        version,
                        request.sourcePath(),
                        workspace.digest(),
                        workspace.revision(),
                        request.menuId(),
                        menu.getPageType(),
                        ContentFieldConfigDigest.of(menu),
                        ContentFieldConfigDigest.of(after),
                        rows,
                        workspace.sources().stream()
                                .filter(
                                        source ->
                                                rows.stream()
                                                        .flatMap(row -> row.sources().stream())
                                                        .anyMatch(
                                                                reference ->
                                                                        reference
                                                                                .sourceId()
                                                                                .equals(
                                                                                        source
                                                                                                .id())))
                                .toList(),
                        fields,
                        update,
                        canConfirm,
                        "DRAFT",
                        warnings,
                        origins);
        return plan;
    }

    private TemplateListPairingService listPairingService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setListPairingService(TemplateListPairingService value) {
        listPairingService = value;
    }

    public List<Summary> reviewedPairingDetails(Long site, String code, String version,
            TemplateListPairingService.Request request) {
        var choices = new ArrayList<Summary>();
        for (String path : listPairingService.candidateDetailPaths(site, code, version, request)) {
            var saved = recent(site, code, version, path, request.menuId());
            saved.plans().stream().filter(row -> "CONFIRMED".equals(row.status()))
                    .limit(10).forEach(choices::add);
        }
        return List.copyOf(choices);
    }

    public String reviewedPairingDetailPath(Long site, String code, String version,
            TemplateListPairingService.Request request) {
        if (request.detailPlanId() == null) return null;
        var saved = readSavedDisplaySource(site, code, version, request.detailPlanId());
        if (!"CONFIRMED".equals(saved.status()) || saved.pageType() != 1
                || !Objects.equals(saved.menuId(), request.menuId())
                || Objects.equals(saved.sourcePath(), request.listSourcePath()))
            throw new IllegalArgumentException("详情计划须是当前站点、包版本及栏目的已确认独立详情");
        return saved.sourcePath();
    }

    public record PairedOptionsRequest(TemplateListPairingService.Request pairing, String cardId) {}

    public record SharedDetailTemplateRequest(TemplateListPairingService.Request pairing, List<String> cardIds,
            String sourcePath, String digest, long unitsRevision) {}

    public synchronized Plan previewSharedDetailTemplate(Long site, String code, String version,
            SharedDetailTemplateRequest target, java.util.function.Predicate<Plan> initialized) {
        if (target == null || target.pairing() == null || target.cardIds() == null || target.cardIds().isEmpty()
                || target.cardIds().size() > 20 || target.cardIds().stream().anyMatch(id -> id == null || id.length() > 64))
            throw new IllegalArgumentException("请选择已审核的共享详情及其原始卡片");
        var pairing = target.pairing();
        var workspace = units.requireSaved(code, version, target.sourcePath());
        requireVersion(workspace, target.digest(), target.unitsRevision());
        var recent = recent(site, code, version, target.sourcePath(), pairing.menuId());
        var seed = recent.plans().stream().filter(summary -> "CONFIRMED".equals(summary.status())
                        && summary.unitsRevision() == workspace.revision() && workspace.digest().equals(summary.digest()))
                .map(summary -> read(site, code, version, summary.id()))
                .filter(TemplateFieldPlanService::usesSharedDetail)
                .filter(plan -> {
                    var proof = sharedDetailOrigin(plan.origins()).pairingReview();
                    return Objects.equals(proof.pairing(), pairing) && target.cardIds().contains(proof.cardId());
                })
                .filter(initialized).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先初始化此组至少一条已审核共享详情草稿"));
        assertCurrentPairingReview(seed);
        var proof = sharedDetailOrigin(seed.origins()).pairingReview();
        var current = listPairingService.inspectForImport(site, code, version, pairing, proof.cardId(),
                reviewedPairingDetailPath(site, code, version, pairing));
        var item = current.items().stream().filter(row -> proof.cardId().equals(row.id())).findFirst().orElseThrow();
        if (!"MATCHED".equals(item.status()) || item.detail() == null
                || !workspace.sourcePath().equals(item.detail().sourcePath()) || !workspace.digest().equals(item.detail().digest()))
            throw new IllegalArgumentException("共享详情来源或审核已变化");
        var editor = seed.bindings().stream().filter(row -> "Editor".equals(row.fieldType())
                && row.unitId().startsWith("DETAIL:")).findFirst().orElseThrow();
        var allowedBodies = bodySourceIds(workspace, item);
        var heading = item.comparisons().stream().filter(fact -> "标题".equals(fact.name()) && fact.detail() != null)
                .map(ListPairingComparison.Fact::detail).findFirst().orElseThrow();
        var headingSources = workspace.sources().stream()
                .filter(source -> "TEXT".equals(source.kind())
                        && heading.selector().equals(source.selector())
                        && heading.value().equals(ListPairingComparison.clean(source.rawValue())))
                .map(TemplateContentUnitsService.Source::id).collect(java.util.stream.Collectors.toSet());
        var title = sharedTemplateUnit(workspace, "SingleLine", headingSources, null);
        var body = sharedTemplateUnit(workspace, "Editor", allowedBodies, editor.unitId().substring("DETAIL:".length()));
        var menu = menus.lockFieldConfiguration(site, pairing.menuId());
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), site)
                || !Integer.valueOf(1).equals(menu.getPageType()) || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !ContentFieldConfigDigest.of(menu).equals(seed.afterFieldConfigDigest()))
            throw new IllegalArgumentException("目标栏目或初始化时的字段配置已变化");
        var choices = Map.of(title.id(), "title", body.id(), editor.fieldName());
        var request = new Request(workspace.sourcePath(), workspace.digest(), workspace.revision(), pairing.menuId(), choices);
        var selected = new TemplateContentUnitsService.Workspace(workspace.sourcePath(), workspace.digest(),
                workspace.revision(), workspace.sources(), List.of(title, body), workspace.warnings());
        var identity = UUID.nameUUIDFromBytes(json.writeValueAsBytes(List.of("shared-detail-template-v1", site, code,
                version, pairing.menuId(), request.sourcePath(), request.digest(), request.unitsRevision(),
                new java.util.TreeMap<>(choices), ContentFieldConfigDigest.of(menu)))).toString();
        if (java.nio.file.Files.exists(file(code, identity))) return read(site, code, version, identity);
        var plan = build(site, code, version, request, selected, menu, identity, List.of(), false, Set.of());
        if (!plan.canConfirm() || plan.fieldUpdate() != null
                || plan.bindings().stream().anyMatch(binding -> !"REUSE".equals(binding.action())))
            throw new IllegalArgumentException("标题和正文须映射到当前栏目已启用的兼容字段");
        var independent = new Plan(plan.schemaVersion(), plan.id(), plan.siteId(), plan.code(), plan.packageVersion(),
                plan.sourcePath(), plan.digest(), plan.unitsRevision(), plan.menuId(), plan.pageType(),
                plan.fieldConfigDigest(), plan.afterFieldConfigDigest(), plan.bindings(), plan.sources(), plan.fields(),
                plan.fieldUpdate(), plan.canConfirm(), plan.status(), List.of("模板计划仅绑定标题和正文。其他显示信息请另行审核。"),
                plan.origins(), seed.id());
        persist(independent);
        return independent;
    }

    private static TemplateContentUnitsService.Unit sharedTemplateUnit(TemplateContentUnitsService.Workspace workspace,
            String type, Set<String> sources, String expectedUnit) {
        var complete = workspace.sources().stream().filter(source -> sources.contains(source.id()))
                .map(source -> new TemplateContentUnitsService.Reference(source.id(), 0, source.rawValue().length()))
                .collect(java.util.stream.Collectors.toSet());
        var choices = workspace.units().stream().filter(unit -> !unit.ignored() && type.equals(unit.fieldType())
                && (expectedUnit == null || expectedUnit.equals(unit.id())) && unit.sources().size() == 1
                && complete.contains(unit.sources().getFirst())).toList();
        if (choices.size() != 1) throw new IllegalArgumentException("请在详情原页保存唯一且完整的标题和正文单元");
        return choices.getFirst();
    }

    public record PairedUnit(
            String id,
            String name,
            String fieldType,
            String valuePreview,
            int valueLength,
            String doubts) {}

    public record PairedPage(
            String sourcePath, String digest, long revision, List<PairedUnit> units) {}

    public record PairedOptions(
            String cardId,
            PairedPage list,
            PairedPage detail,
            String fieldConfigDigest,
            List<Field> fields,
            List<String> warnings) {}

    public PairedOptions pairedOptions(
            Long siteId, String code, String version, PairedOptionsRequest body) {
        if (body == null
                || body.pairing() == null
                || body.cardId() == null
                || body.cardId().length() > 64) throw new IllegalArgumentException("请选择已确认卡片");
        var inspection = listPairingService.inspectForImport(siteId, code, version, body.pairing(), body.cardId(),
                reviewedPairingDetailPath(siteId, code, version, body.pairing()));
        var item =
                inspection.items().stream()
                        .filter(row -> row.id().equals(body.cardId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("卡片不在当前检查范围"));
        if (!"MATCHED".equals(item.status())
                || item.detail() == null
                || !item.list().evidenceComplete())
            throw new IllegalArgumentException("重复、歧义或证据不完整的卡片仍待处理");
        var list = units.requireSaved(code, version, inspection.listSourcePath());
        var detail = units.requireSaved(code, version, item.detail().sourcePath());
        if (!list.digest().equals(inspection.listDigest())
                || !detail.digest().equals(item.detail().digest()))
            throw new IllegalArgumentException("来源已变化，请重新检查卡片");
        var menu = menus.selectActiveMenuModelBySite(siteId, inspection.menuId().intValue());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Integer.valueOf(1).equals(menu.getPageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel()))
            throw new IllegalArgumentException("目标内容列表栏目不可用");
        var allowed =
                item.list().evidence().stream()
                        .map(TemplateListPairingService.Evidence::sourceId)
                        .collect(java.util.stream.Collectors.toSet());
        var warnings = new ArrayList<String>();
        return new PairedOptions(
                item.id(),
                pairedPage(list, allowed, warnings),
                pairedPage(detail, sharedDetail(inspection.review(), item.id()) ? bodySourceIds(detail, item) : null,
                        warnings),
                ContentFieldConfigDigest.of(menu),
                ContentModelCodec.read(menu.getModelField()).stream()
                        .map(field -> describe(menu, field))
                        .toList(),
                List.copyOf(warnings));
    }

    private static PairedPage pairedPage(
            TemplateContentUnitsService.Workspace workspace,
            Set<String> allowed,
            List<String> warnings) {
        var sources = new java.util.HashMap<String, TemplateContentUnitsService.Source>();
        for (var source : workspace.sources()) sources.put(source.id(), source);
        var eligible =
                workspace.units().stream()
                        .filter(
                                unit ->
                                        !unit.ignored()
                                                && unit.sources() != null
                                                && !unit.sources().isEmpty()
                                                && (allowed == null
                                                        || unit.sources().stream()
                                                                .allMatch(
                                                                        ref ->
                                                                                allowed.contains(
                                                                                        ref
                                                                                                .sourceId()))))
                        .toList();
        if (eligible.size() > 100) warnings.add(workspace.sourcePath() + "仅展示前100个人工单元，余下待分批处理");
        var rows =
                eligible.stream()
                        .limit(100)
                        .map(
                                unit -> {
                                    String value = original(unit, sources);
                                    return new PairedUnit(
                                            unit.id(),
                                            unit.name(),
                                            unit.fieldType(),
                                            value.length() > 200
                                                    ? value.substring(0, 200) + "…"
                                                    : value,
                                            value.length(),
                                            unit.doubts());
                                })
                        .toList();
        return new PairedPage(
                workspace.sourcePath(), workspace.digest(), workspace.revision(), rows);
    }

    public record PairedRequest(
            TemplateListPairingService.Request pairing,
            String cardId,
            String detailDigest,
            long listRevision,
            long detailRevision,
            String fieldConfigDigest,
            Map<String, String> listChoices,
            Map<String, String> detailChoices) {}

    @org.springframework.transaction.annotation.Transactional
    public synchronized Plan previewPaired(
            Long siteId, String code, String version, PairedRequest body) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive())
            throw new IllegalStateException("双来源计划必须通过CMS实际事务入口");
        if (body == null
                || body.pairing() == null
                || body.cardId() == null
                || body.cardId().length() > 64) throw new IllegalArgumentException("请选择已确认卡片");
        var inspection = listPairingService.inspectForImport(siteId, code, version, body.pairing(), body.cardId(),
                reviewedPairingDetailPath(siteId, code, version, body.pairing()));
        var item =
                inspection.items().stream()
                        .filter(row -> row.id().equals(body.cardId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("卡片不在当前检查范围"));
        if (!"MATCHED".equals(item.status())
                || item.detail() == null
                || !item.list().evidenceComplete())
            throw new IllegalArgumentException("重复、歧义或证据不完整的卡片仍待处理");
        var list = units.requireSaved(code, version, inspection.listSourcePath());
        var detail = units.requireSaved(code, version, item.detail().sourcePath());
        requireVersion(list, inspection.listDigest(), body.listRevision());
        requireVersion(detail, body.detailDigest(), body.detailRevision());
        if (!detail.digest().equals(item.detail().digest()))
            throw new IllegalArgumentException("详情来源已变化");
        var menu = menus.lockFieldConfiguration(siteId, inspection.menuId());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getId().longValue(), inspection.menuId())
                || !Integer.valueOf(1).equals(menu.getPageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !ContentFieldConfigDigest.of(menu).equals(body.fieldConfigDigest()))
            throw new IllegalArgumentException("目标栏目或字段配置已变化");
        var selected = new ArrayList<TemplateContentUnitsService.Unit>();
        var allSources = new ArrayList<TemplateContentUnitsService.Source>();
        var choices = new java.util.LinkedHashMap<String, String>();
        var allowed =
                item.list().evidence().stream()
                        .map(TemplateListPairingService.Evidence::sourceId)
                        .collect(java.util.stream.Collectors.toSet());
        boolean shared = sharedDetail(inspection.review(), item.id());
        if (shared && (body.listChoices() == null || !body.listChoices().containsValue("title")
                || body.detailChoices() == null || body.detailChoices().size() != 1))
            throw new IllegalArgumentException("共享详情须从本卡片选择标题，并从详情选择一处已审核正文");
        selectPaired("LIST", list, body.listChoices(), allowed, selected, allSources, choices);
        if (shared) {
            var sources = allSources.stream().collect(java.util.stream.Collectors.toMap(
                    TemplateContentUnitsService.Source::id, source -> source));
            var title = selected.stream().filter(unit -> "title".equals(choices.get(unit.id())))
                    .map(unit -> ListPairingComparison.clean(original(unit, sources))).findFirst().orElse("");
            if (item.comparisons().stream().noneMatch(fact -> "标题".equals(fact.name())
                    && fact.list() != null && title.equals(fact.list().value())))
                throw new IllegalArgumentException("共享详情记录须以审核卡片的原始标题作为标题初值");
        }
        selectPaired("DETAIL", detail, body.detailChoices(), shared ? bodySourceIds(detail, item) : null,
                selected, allSources, choices);
        if (shared && !"Editor".equals(selected.getLast().fieldType()))
            throw new IllegalArgumentException("共享详情只能复用完整正文 Editor");
        var origins =
                List.of(
                        new Origin("LIST", list.sourcePath(), list.digest(), list.revision(),
                                new PairingReviewIdentity(body.pairing(), body.cardId(),
                                        inspection.review().revision(), inspection.review().evidenceDigest(),
                                        shared ? Boolean.TRUE : null)),
                        new Origin(
                                "DETAIL", detail.sourcePath(), detail.digest(), detail.revision()));
        String identity = seedIdentity(code, siteId, inspection.menuId(), detail.sourcePath(), origins);
        String versionIdentity =
                identity
                        + "\n"
                        + version
                        + "\n"
                        + json.writeValueAsString(origins)
                        + "\n"
                        + json.writeValueAsString(new java.util.TreeMap<>(choices))
                        + "\n"
                        + ContentFieldConfigDigest.of(menu);
        String id =
                UUID.nameUUIDFromBytes(
                                versionIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString();
        if (java.nio.file.Files.exists(file(code, id))) {
            var existing = read(siteId, code, version, id);
            Map<String, String> savedChoices = new java.util.LinkedHashMap<>();
            for (var binding : existing.bindings())
                if ("REUSE".equals(binding.action()))
                    savedChoices.put(binding.unitId(), binding.fieldName());
            if (!Objects.equals(existing.origins(), origins)
                    || !choices.equals(savedChoices)
                    || !ContentFieldConfigDigest.of(menu).equals(existing.afterFieldConfigDigest()))
                throw new IllegalArgumentException("同一详情已有计划，不能自动覆盖来源、字段或既有应用；请先核对持久状态");
            validateSources(existing);
            validateScalarFields(existing, menu);
            validateCounterSchema(existing, menu);
            validateDateSchema(existing, menu);
            return existing;
        }
        if (!shared) {
            var legacy = recent(siteId, code, version, detail.sourcePath(), inspection.menuId(), true);
            if (legacy.truncated() || !legacy.warnings().isEmpty()
                    || legacy.plans().stream().anyMatch(summary -> hasApplicationFile(code, summary.id())))
                throw new IllegalArgumentException("旧单源或旧版本计划可能已应用，不能另建重复内容；请先核对已有计划");
        }
        var workspace =
                new TemplateContentUnitsService.Workspace(
                        detail.sourcePath(),
                        detail.digest(),
                        detail.revision(),
                        List.copyOf(allSources),
                        List.copyOf(selected),
                        List.of());
        var request =
                new Request(
                        detail.sourcePath(),
                        detail.digest(),
                        detail.revision(),
                        inspection.menuId(),
                        choices);
        Plan plan = build(siteId, code, version, request, workspace, menu, id, origins, false, Set.of());
        if (!plan.canConfirm()
                || plan.fieldUpdate() != null
                || plan.bindings().stream().anyMatch(row -> !"REUSE".equals(row.action())))
            throw new IllegalArgumentException("双来源字段映射仍有冲突或待处理；请修正后再生成，不保存不可用计划");
        persist(plan);
        return plan;
    }

    private static String canonicalDetailPath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("详情路径无效");
        return java.nio.file.Path.of(path)
                .normalize()
                .toString()
                .replace('\\', '/')
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String detailIdentity(String code, Long siteId, Long menuId, String path) {
        return "paired-entry-v1\n"
                + code
                + "\n"
                + siteId
                + "\n"
                + menuId
                + "\n"
                + canonicalDetailPath(path);
    }

    private static boolean sharedDetail(TemplateListPairingService.Review review, String cardId) {
        return review.decisions().stream().anyMatch(decision -> decision.cardId().equals(cardId)
                && decision.ownership() == TemplateListPairingService.Ownership.SHARED_DETAIL);
    }

    private static Set<String> bodySourceIds(TemplateContentUnitsService.Workspace detail,
            TemplateListPairingService.Item item) {
        String excerpt = item.comparisons().stream()
                .filter(fact -> "正文".equals(fact.name()) && fact.detail() != null)
                .map(fact -> fact.detail().value().replaceFirst("…$", ""))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("缺少已核对的共享详情正文"));
        return detail.sources().stream()
                .filter(source -> "HTML_REGION".equals(source.kind())
                        && ListPairingComparison.clean(org.jsoup.Jsoup.parseBodyFragment(source.rawValue()).text())
                                .contains(excerpt))
                .map(TemplateContentUnitsService.Source::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Origin sharedDetailOrigin(List<Origin> origins) {
        if (origins == null) return null;
        return origins.stream().filter(origin -> origin.pairingReview() != null
                && Boolean.TRUE.equals(origin.pairingReview().sharedDetail())).findFirst().orElse(null);
    }

    public static boolean usesSharedDetail(Plan plan) {
        return sharedDetailOrigin(plan.origins()) != null;
    }

    private static String seedIdentity(String code, Long siteId, Long menuId, String detailPath, List<Origin> origins) {
        var list = sharedDetailOrigin(origins);
        return list == null ? detailIdentity(code, siteId, menuId, detailPath)
                : "shared-detail-entry-v1\n" + code + "\n" + siteId + "\n" + menuId + "\n"
                        + canonicalDetailPath(list.sourcePath()) + "\n" + list.pairingReview().cardId();
    }

    public static String detailApplicationId(Plan plan) {
        return UUID.nameUUIDFromBytes(
                        seedIdentity(plan.code(), plan.siteId(), plan.menuId(), plan.sourcePath(), plan.origins())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    public static String applicationId(Plan plan) {
        return plan.origins() == null || plan.origins().isEmpty()
                ? plan.id()
                : detailApplicationId(plan);
    }

    public List<String> legacyApplicationPlans(Plan plan) {
        var manifests =
                recent(
                        plan.siteId(),
                        plan.code(),
                        plan.packageVersion(),
                        plan.sourcePath(),
                        plan.menuId(),
                        true);
        if (manifests.truncated() || !manifests.warnings().isEmpty())
            throw new IllegalArgumentException("既有计划核对范围不足，不能自动初始化同一详情");
        return manifests.plans().stream()
                .map(Summary::id)
                .filter(id -> hasApplicationFile(plan.code(), id))
                .toList();
    }

    private boolean hasApplicationFile(String code, String id) {
        var directory = file(code, id).getParent().getParent().resolve("text-drafts");
        if (java.nio.file.Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && (!java.nio.file.Files.isDirectory(
                                directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || java.nio.file.Files.isSymbolicLink(directory)))
            throw new IllegalArgumentException("既有应用目录无法安全核对");
        return java.nio.file.Files.exists(
                directory.resolve(id + ".json"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireVersion(
            TemplateContentUnitsService.Workspace workspace, String digest, long revision) {
        if (revision <= 0
                || workspace.revision() != revision
                || !Objects.equals(workspace.digest(), digest))
            throw new IllegalArgumentException("页面来源或人工修正版本已变化");
    }

    private static TemplateContentUnitsService.Source qualified(
            String role, TemplateContentUnitsService.Source source) {
        return source.withId(role + ":" + source.id());
    }

    private static void selectPaired(
            String role,
            TemplateContentUnitsService.Workspace workspace,
            Map<String, String> selections,
            Set<String> allowed,
            List<TemplateContentUnitsService.Unit> selected,
            List<TemplateContentUnitsService.Source> sources,
            Map<String, String> choices) {
        if (selections == null || selections.isEmpty() || selections.size() > 20)
            throw new IllegalArgumentException("每份来源请选择1至20个已保存单元与已有字段");
        Map<String, TemplateContentUnitsService.Unit> known = new java.util.HashMap<>();
        for (var unit : workspace.units()) known.put(unit.id(), unit);
        for (var entry : selections.entrySet()) {
            var unit = known.get(entry.getKey());
            if (unit == null
                    || unit.ignored()
                    || entry.getValue() == null
                    || entry.getValue().isBlank()
                    || entry.getValue().equals("$new"))
                throw new IllegalArgumentException("仅允许已保存未忽略单元复用正式配置字段；缺字段请先走人工入口");
            if (allowed != null
                    && unit.sources().stream().anyMatch(ref -> !allowed.contains(ref.sourceId())))
                throw new IllegalArgumentException("LIST".equals(role)
                        ? "列表单元跨出当前卡片范围，不能按条初始化"
                        : "详情单元超出已核对的共享正文范围");
            String id = role + ":" + unit.id();
            var refs =
                    unit.sources().stream()
                            .map(
                                    ref ->
                                            new TemplateContentUnitsService.Reference(
                                                    role + ":" + ref.sourceId(),
                                                    ref.start(),
                                                    ref.end()))
                            .toList();
            selected.add(
                    new TemplateContentUnitsService.Unit(
                            id, unit.name(), unit.fieldType(), refs, false, unit.doubts()));
            choices.put(id, entry.getValue());
        }
        for (var source : workspace.sources()) sources.add(qualified(role, source));
    }

    public void assertCurrentPairingReview(Plan plan) {
        if (plan.origins() == null) return;
        for (var origin : plan.origins()) {
            var pairing = origin.pairingReview();
            if (pairing == null) continue;
            var current = listPairingService.inspectForImport(plan.siteId(), plan.code(),
                    plan.packageVersion(), pairing.pairing(), pairing.cardId(),
                    reviewedPairingDetailPath(plan.siteId(), plan.code(), plan.packageVersion(), pairing.pairing()));
            if (current.review().revision() != pairing.reviewRevision()
                    || !Objects.equals(current.review().evidenceDigest(), pairing.evidenceDigest())
                    || Boolean.TRUE.equals(pairing.sharedDetail()) != sharedDetail(current.review(), pairing.cardId()))
                throw new IllegalArgumentException("列表归属审核已变化，请重新检查来源和确认初值");
        }
    }

    private void validateSources(Plan plan) {
        Map<String, TemplateContentUnitsService.Source> current = new java.util.HashMap<>();
        if (plan.origins() == null || plan.origins().isEmpty()) {
            var workspace =
                    units.requireSaved(plan.code(), plan.packageVersion(), plan.sourcePath());
            requireVersion(workspace, plan.digest(), plan.unitsRevision());
            for (var source : workspace.sources()) current.put(source.id(), source);
        } else {
            if (plan.origins().size() != 2
                    || plan.origins().stream()
                                    .map(Origin::role)
                                    .collect(java.util.stream.Collectors.toSet())
                                    .size()
                            != 2) throw new IllegalArgumentException("双来源计划形状无效");
            for (var origin : plan.origins()) {
                if (!Set.of("LIST", "DETAIL").contains(origin.role()))
                    throw new IllegalArgumentException("来源角色无效");
                if (origin.role().equals("DETAIL")
                        && (!Objects.equals(origin.sourcePath(), plan.sourcePath())
                                || !Objects.equals(origin.digest(), plan.digest())
                                || origin.unitsRevision() != plan.unitsRevision()))
                    throw new IllegalArgumentException("详情来源与主计划不一致");
                var workspace =
                        units.requireSaved(plan.code(), plan.packageVersion(), origin.sourcePath());
                requireVersion(workspace, origin.digest(), origin.unitsRevision());
                for (var source : workspace.sources()) {
                    var value = qualified(origin.role(), source);
                    current.put(value.id(), value);
                }
            }
        }
        Set<String> boundSources = new HashSet<>();
        for (var binding : plan.bindings()) {
            if ("CREATE".equals(binding.action()) || "REUSE".equals(binding.action())
                    || MENU_NAME.equals(binding.action()))
                binding.sources().forEach(reference -> boundSources.add(reference.sourceId()));
        }
        if (plan.sources() == null
                || plan.sources().stream()
                        .anyMatch(
                                saved -> {
                                    var source = current.get(saved.id());
                                    return boundSources.contains(saved.id())
                                            ? !saved.equals(source)
                                            : !sameSourceEvidence(saved, source);
                                })) throw new IllegalArgumentException("来源定位或原值证据已变化，请重新生成计划");
    }

    private static boolean sameSourceEvidence(
            TemplateContentUnitsService.Source saved, TemplateContentUnitsService.Source current) {
        // 未绑定项仍核对来源证据，允许新增的公共区域、导航和交互分类提示。
        return current != null
                && Objects.equals(saved.id(), current.id())
                && Objects.equals(saved.kind(), current.kind())
                && Objects.equals(saved.rawValue(), current.rawValue())
                && Objects.equals(saved.selector(), current.selector())
                && Objects.equals(saved.context(), current.context())
                && Objects.equals(saved.repeatGroup(), current.repeatGroup())
                && Objects.equals(saved.scriptInput(), current.scriptInput());
    }

    public record Confirmation(
            String planId, boolean acknowledgeReuse, boolean acknowledgeCreate) {}

    public record Preflight(Plan plan, boolean requiresFieldUpdate) {}

    public record Summary(
            String id,
            String sourcePath,
            Long menuId,
            String status,
            long unitsRevision,
            String digest) {}

    public record Recent(List<Summary> plans, boolean truncated, List<String> warnings) {}

    /** 按栏目恢复既有计划，不依赖浏览器localStorage；扫描和读取均有硬上限。 */
    public Recent recent(Long siteId, String code, String version, String sourcePath, Long menuId) {
        return recent(siteId, code, version, sourcePath, menuId, false);
    }

    private Recent recent(
            Long siteId,
            String code,
            String version,
            String sourcePath,
            Long menuId,
            boolean identitySearch) {
        if (sourcePath == null || sourcePath.isBlank() || menuId == null || menuId <= 0)
            throw new IllegalArgumentException("请选择来源页面与目标栏目");
        var directory = file(code, UUID.randomUUID().toString()).getParent();
        var found = new ArrayList<Summary>();
        int remaining = 8 * 1024 * 1024, skipped = 0;
        boolean truncated = false;
        List<java.nio.file.Path> paths;
        try (var stream = java.nio.file.Files.list(directory)) {
            paths = new ArrayList<>(stream.limit(1001).toList());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法检索已有字段计划", error);
        }
        if (paths.size() > 1000)
            throw new IllegalArgumentException("字段计划目录超过1000项，请使用已知计划ID读取并整理项目存档");
        paths.removeIf(path -> !path.getFileName().toString().endsWith(".json"));
        paths.sort(
                java.util.Comparator.comparingLong(
                                (java.nio.file.Path path) -> path.toFile().lastModified())
                        .reversed());
        for (var path : paths) {
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            String name = path.getFileName().toString();
            String id = name.substring(0, name.length() - 5);
            if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    || !java.nio.file.Files.isRegularFile(
                            path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped++;
                continue;
            }
            int limit = Math.min(1024 * 1024, remaining);
            try (var input = java.nio.file.Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(limit + 1);
                remaining -= bytes.length;
                if (bytes.length > limit) {
                    truncated = true;
                    skipped++;
                    continue;
                }
                Plan plan = json.readValue(bytes, Plan.class);
                if (plan == null
                        || !Objects.equals(plan.id(), id)
                        || plan.status() == null
                        || !Set.of("DRAFT", "CONFIRMED", "CONFIRMED_WITH_PENDING")
                                .contains(plan.status())) {
                    skipped++;
                    continue;
                }
                if (!Objects.equals(plan.siteId(), siteId)
                        || !Objects.equals(plan.code(), code)
                        || (!identitySearch && !Objects.equals(plan.packageVersion(), version))
                        || !(Objects.equals(plan.sourcePath(), sourcePath)
                                || identitySearch
                                        && canonicalDetailPath(plan.sourcePath())
                                                .equals(canonicalDetailPath(sourcePath)))
                        || !Objects.equals(plan.menuId(), menuId)) continue;
                if (identitySearch && plan.origins() != null && !plan.origins().isEmpty()) continue;
                if (found.size() < 30)
                    found.add(
                            new Summary(
                                    plan.id(),
                                    plan.sourcePath(),
                                    plan.menuId(),
                                    plan.status(),
                                    plan.unitsRevision(),
                                    plan.digest()));
                else truncated = true;
            } catch (java.io.IOException | tools.jackson.core.JacksonException error) {
                skipped++;
            }
        }
        var warnings = new ArrayList<String>();
        if (skipped > 0) warnings.add("有" + skipped + "份损坏、超限或非普通文件未纳入列表；不会自动改写存档。");
        if (truncated) warnings.add("已有计划列表未覆盖全部存档：扫描上限1000份、读取总量8MB、显示目标最近30份；可输入已知计划ID单独读取。");
        return new Recent(List.copyOf(found), truncated, warnings);
    }

    public Plan read(Long siteId, String code, String version, String id) {
        return read(siteId, code, version, id, false);
    }

    /** 撤回既有应用时仅校验存档范围，允许使用已被新版替代的派生计划。 */
    public Plan readForWithdrawal(Long siteId, String code, String version, String id) {
        return read(siteId, code, version, id, true);
    }

    /** 已确认列表复核源时由调用方核对实际字段、来源摘要与卡片映射。 */
    Plan readSavedDisplaySource(Long siteId, String code, String version, String id) {
        return read(siteId, code, version, id, true);
    }

    private Plan read(Long siteId, String code, String version, String id, boolean withdrawal) {
        if (!withdrawal && siteModelPlans != null)
            siteModelPlans.assertDerivedCurrent(siteId, code, version, id);
        java.nio.file.Path file = file(code, id);
        try (var input = java.nio.file.Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("字段计划存档超限");
            Plan plan = json.readValue(bytes, Plan.class);
            if (plan == null
                    || !Objects.equals(plan.siteId(), siteId)
                    || !Objects.equals(plan.code(), code)
                    || !Objects.equals(plan.packageVersion(), version)
                    || !Objects.equals(plan.id(), id))
                throw new IllegalArgumentException("字段计划站点或包版本已变化，请重新生成");
            return plan;
        } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("字段计划不存在或无法读取");
        }
    }

    public synchronized Preflight preflight(Long siteId, String code, String version, String id) {
        Plan plan = read(siteId, code, version, id);
        OneshotEntity menu = validateCurrent(plan);
        String digest = ContentFieldConfigDigest.of(menu);
        if (digest.equals(plan.afterFieldConfigDigest())) return new Preflight(plan, false);
        if ("DRAFT".equals(plan.status()) && digest.equals(plan.fieldConfigDigest()))
            return new Preflight(plan, plan.fieldUpdate() != null);
        throw new IllegalArgumentException("字段配置已变化，请重新生成计划");
    }

    public synchronized Plan confirm(
            Long siteId, String code, String version, Confirmation request) {
        if (request == null) throw new IllegalArgumentException("请明确确认字段计划");
        Plan plan = read(siteId, code, version, request.planId());
        OneshotEntity menu = validateCurrent(plan);
        if (!ContentFieldConfigDigest.of(menu).equals(plan.afterFieldConfigDigest()))
            throw new IllegalArgumentException("请先通过CMS正式字段保存API补齐计划字段，或重新生成已变化的配置计划");
        if (!"DRAFT".equals(plan.status())) return plan;
        if (plan.bindings().stream().anyMatch(row ->
                "REUSE".equals(row.action()) || MENU_NAME.equals(row.action()))
                && !request.acknowledgeReuse())
            throw new IllegalArgumentException("请确认复用字段和子栏目名称与原文用途一致");
        if (plan.bindings().stream().anyMatch(row -> row.action().equals("CREATE"))
                && !request.acknowledgeCreate())
            throw new IllegalArgumentException("请确认新增字段名称、类型与影响");
        String status =
                plan.bindings().stream().anyMatch(row -> row.action().equals("PENDING_TYPE"))
                        ? "CONFIRMED_WITH_PENDING"
                        : "CONFIRMED";
        Plan confirmed =
                new Plan(
                        plan.schemaVersion(),
                        plan.id(),
                        plan.siteId(),
                        plan.code(),
                        plan.packageVersion(),
                        plan.sourcePath(),
                        plan.digest(),
                        plan.unitsRevision(),
                        plan.menuId(),
                        plan.pageType(),
                        plan.fieldConfigDigest(),
                        plan.afterFieldConfigDigest(),
                        plan.bindings(),
                        plan.sources(),
                        plan.fields(),
                        plan.fieldUpdate(),
                        plan.canConfirm(),
                        status,
                        plan.warnings(),
                        plan.origins(), plan.sharedDetailSeedPlanId());
        persist(confirmed);
        return confirmed;
    }

    private OneshotEntity validateCurrent(Plan plan) {
        if (!plan.canConfirm()) throw new IllegalArgumentException("字段计划仍有冲突，请修正后重新生成");
        assertCurrentPairingReview(plan);
        validateSources(plan);
        var menu = menus.getModelById(Math.toIntExact(plan.menuId()));
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), plan.siteId())
                || !Objects.equals(menu.getPageType(), plan.pageType())
                || Boolean.FALSE.equals(menu.getHasManageChannel()))
            throw new IllegalArgumentException("目标栏目已变化或不属于当前站点");
        validateScalarFields(plan, menu);
        validateCounterSchema(plan, menu);
        validateDateSchema(plan, menu);
        for (var binding : plan.bindings()) {
            if (MENU_NAME.equals(binding.action()))
                requireChildMenuNameSource(plan.siteId(), plan.menuId(), binding.menuId());
        }
        if (sourceValidator != null) sourceValidator.validate(plan, menu);
        return menu;
    }

    private static Long parseChildMenuId(String selection) {
        try {
            return Long.valueOf(selection.substring(MENU_NAME.length() + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("子栏目编号无效");
        }
    }

    private OneshotEntity requireChildMenuNameSource(Long siteId, Long parentMenuId, Long menuId) {
        if (menuId == null || menuId <= 0 || menuId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("子栏目编号无效");
        var child = menus.selectActiveMenuModelBySite(siteId, menuId.intValue());
        if (!isChildMenuNameSource(child, siteId, parentMenuId))
            throw new IllegalArgumentException("名称来源须为当前站点、当前维护栏目下名称有效的直属子栏目");
        return child;
    }

    public static boolean isChildMenuNameSource(OneshotEntity menu, Long siteId, Long parentMenuId) {
        return menu != null
                && siteId != null
                && Objects.equals(menu.getSiteProjectId(), siteId)
                && parentMenuId != null
                && menu.getParentId() == parentMenuId.longValue()
                && !Boolean.TRUE.equals(menu.getIsDeleted())
                && menu.getClassName() != null
                && !menu.getClassName().isBlank();
    }

    private static void validateScalarFields(Plan plan, OneshotEntity menu) {
        var referenceFields =
                ContentModelCodec.read(
                                plan.pageType() == 0 ? menu.getMenuField() : menu.getModelField())
                        .stream()
                        .filter(field -> "content".equals(field.getInputType()))
                        .map(ContentFieldDefinition::getFieldName)
                        .collect(java.util.stream.Collectors.toSet());
        if (plan.bindings().stream()
                .anyMatch(
                        binding ->
                                Set.of("CREATE", "REUSE").contains(binding.action())
                                        && referenceFields.contains(binding.fieldName())))
            throw new IllegalArgumentException("关联内容字段请从CMS选择内容记录，不能使用页面原文或链接作为初值");
    }

    private static void validateDateSchema(Plan plan, OneshotEntity menu) {
        var fields =
                ContentModelCodec.read(
                        plan.pageType() == 0 ? menu.getMenuField() : menu.getModelField());
        for (var binding : plan.bindings()) {
            if (!"DateTime".equals(binding.fieldType())
                    || !Set.of("CREATE", "REUSE").contains(binding.action())) continue;
            TemplateDateValues.original(binding, plan.sources());
            var field =
                    fields.stream()
                            .filter(f -> Objects.equals(f.getFieldName(), binding.fieldName()))
                            .findFirst()
                            .orElse(null);
            if (field == null
                    && "DRAFT".equals(plan.status())
                    && "CREATE".equals(binding.action())
                    && plan.fieldUpdate() != null)
                field =
                        plan.fieldUpdate().getFieldOptions().stream()
                                .filter(f -> Objects.equals(f.getFieldName(), binding.fieldName()))
                                .findFirst()
                                .orElse(null);
            TemplateDateValues.validateField(binding, field);
        }
    }

    private static void validateCounterSchema(Plan plan, OneshotEntity menu) {
        var actual =
                ContentModelCodec.read(
                        plan.pageType() == 0 ? menu.getMenuField() : menu.getModelField());
        var sources =
                plan.sources().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        TemplateContentUnitsService.Source::id, source -> source));
        for (var binding : plan.bindings()) {
            if (!Set.of("CREATE", "REUSE").contains(binding.action())) continue;
            if (binding.sources().stream()
                    .noneMatch(
                            reference ->
                                    CounterScriptInputs.knownCounter(
                                            sources.get(reference.sourceId())))) continue;
            if (!"SingleLine".equals(binding.fieldType())
                    || binding.sources().size() != 1
                    || !CounterScriptInputs.counterReference(
                            sources.get(binding.sources().getFirst().sourceId()),
                            binding.sources().getFirst()))
                throw new IllegalArgumentException("统计入口范围已变化，请重新确认统计数值约束计划");
            var field =
                    actual.stream()
                            .filter(
                                    candidate ->
                                            Objects.equals(
                                                    candidate.getFieldName(), binding.fieldName()))
                            .findFirst()
                            .orElse(null);
            // 只允许新字段草稿使用计划定义；存在的旧字段不可被计划暗中升级。
            if (field == null
                    && "CREATE".equals(binding.action())
                    && "DRAFT".equals(plan.status())
                    && plan.fieldUpdate() != null)
                field =
                        plan.fieldUpdate().getFieldOptions().stream()
                                .filter(
                                        candidate ->
                                                Objects.equals(
                                                        candidate.getFieldName(),
                                                        binding.fieldName()))
                                .findFirst()
                                .orElse(null);
            if (!CounterNumbers.applies(field)
                    || !CounterScriptInputs.inputType(
                                    sources.get(binding.sources().getFirst().sourceId()))
                            .equals(field.getInputType())
                    || !"SingleLine".equals(field.getFieldType())
                    || !ContentFieldDefinition.enabled(field.getIsOpen())
                    || ContentFieldDefinition.enabled(field.getIsReadOnly()))
                throw new IllegalArgumentException(
                        "统计绑定必须显式确认"
                                + CounterScriptInputs.inputType(
                                        sources.get(binding.sources().getFirst().sourceId()))
                                + "约束，旧字段不会自动迁移，请重新生成计划");
            CounterNumbers.validateDefinition(field);
        }
    }

    private java.nio.file.Path file(String code, String id) {
        if (code == null
                || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || id == null
                || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("字段计划标识无效");
        try {
            var root = java.nio.file.Path.of(metadataRoot).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(root);
            root = root.toRealPath();
            var project = root.resolve(code);
            if (java.nio.file.Files.isSymbolicLink(project))
                throw new IllegalArgumentException("字段计划项目目录不可使用链接");
            var directory = project.resolve("field-plans");
            if (java.nio.file.Files.isSymbolicLink(directory))
                throw new IllegalArgumentException("字段计划目录不可使用链接");
            java.nio.file.Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(root))
                throw new IllegalArgumentException("字段计划目录越界");
            var target = directory.resolve(id + ".json");
            if (java.nio.file.Files.isSymbolicLink(target))
                throw new IllegalArgumentException("字段计划文件不可使用链接");
            return target;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法访问字段计划存储");
        }
    }

    private void persist(Plan plan) {
        var target = file(plan.code(), plan.id());
        byte[] bytes = json.writeValueAsBytes(plan);
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("字段计划超过1MB限制");
        java.nio.file.Path temporary = null;
        try {
            if (!java.nio.file.Files.exists(target))
                try (var files = java.nio.file.Files.list(target.getParent())) {
                    if (files.filter(path -> path.getFileName().toString().endsWith(".json"))
                                    .limit(1001)
                                    .count()
                            >= 1000)
                        throw new IllegalArgumentException("本项目字段计划已达1000份限制，请保留历史并人工整理后继续");
                }
            temporary =
                    java.nio.file.Files.createTempFile(target.getParent(), ".field-plan-", ".tmp");
            java.nio.file.Files.write(temporary, bytes);
            java.nio.file.Files.move(
                    temporary,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("字段计划保存失败，未确认计划");
        } finally {
            if (temporary != null)
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("字段计划临时文件清理失败");
                }
        }
    }

    private static Binding row(
            TemplateContentUnitsService.Unit unit,
            String action,
            String fieldName,
            int capacity,
            String value,
            String message,
            boolean present) {
        return row(unit, action, fieldName, capacity, value, message, present, null);
    }

    private static Binding row(
            TemplateContentUnitsService.Unit unit,
            String action,
            String fieldName,
            int capacity,
            String value,
            String message,
            boolean present,
            Long menuId) {
        return new Binding(
                unit.id(),
                unit.name(),
                unit.fieldType(),
                unit.sources(),
                action,
                fieldName,
                capacity,
                value.length(),
                value.length() > 1000 ? value.substring(0, 1000) + "（展示截断，原文保留）" : value,
                message,
                present,
                null,
                menuId);
    }

    private static Field describe(OneshotEntity menu, ContentFieldDefinition field) {
        String name =
                field.getTitle() == null || field.getTitle().isBlank()
                        ? field.getFromName()
                        : field.getTitle();
        if (name == null || name.isBlank()) name = field.getFieldName();
        boolean nativeImage = NativeImageFields.supports(field);
        int maximum =
                nativeImage
                        ? NativeImageFields.capacity(field)
                        : "SingleLine".equals(field.getFieldType())
                                ? 500
                                : "MultiLine".equals(field.getFieldType())
                                        ? 10000
                                        : "Editor".equals(field.getFieldType())
                                                ? 200000
                                                : "DateTime".equals(field.getFieldType())
                                                        ? ("DAY"
                                                                        .equals(
                                                                                com.xinhongru
                                                                                        .content
                                                                                        .model
                                                                                        .ContentDateTimes
                                                                                        .precision(
                                                                                                field))
                                                                ? 10
                                                                : 19)
                                                        : Set.of("Image", "Video")
                                                                        .contains(
                                                                                field
                                                                                        .getFieldType())
                                                                ? 2048
                                                                : 0;
        int capacity =
                field.getMaxLength() != null && field.getMaxLength() > 0
                        ? Math.min(maximum, field.getMaxLength())
                        : maximum;
        boolean known =
                nativeImage
                        || ContentExtensionFields.isName(field.getFieldName())
                        || (field.getFieldName() != null
                                && field.getFieldName()
                                        .matches("title|singleLine[0-9]+|note[0-9]+"))
                        || ("Editor".equals(field.getFieldType())
                                && field.getFieldName() != null
                                && field.getFieldName().matches("content[0-9]+"));
        boolean writable =
                known
                        && ContentFieldDefinition.enabled(field.getIsOpen())
                        && !ContentFieldDefinition.enabled(field.getIsReadOnly())
                        && !"content".equals(field.getInputType())
                        && (field.getInputType() == null
                                || field.getInputType().isBlank()
                                || CounterNumbers.applies(field)
                                || nativeImage)
                        && !Boolean.TRUE.equals(field.getInputMultiple())
                        && capacity > 0;
        Object current = null;
        if (ContentExtensionFields.isName(field.getFieldName())) {
            if (Integer.valueOf(0).equals(menu.getPageType()))
                current = menu.getExtensionValues().get(field.getFieldName());
        } else
            try {
                Class<?> targetType =
                        Integer.valueOf(0).equals(menu.getPageType())
                                ? OneshotEntity.class
                                : com.xinhongru.entity.ContentEntity.class;
                var property = EntityFields.find(targetType, field.getFieldName());
                if (property.getType() != String.class) writable = false;
                if (Integer.valueOf(0).equals(menu.getPageType())) {
                    property.setAccessible(true);
                    current = property.get(menu);
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                writable = false;
            }
        String text = current == null ? "" : String.valueOf(current);
        return new Field(
                field.getFieldName(),
                name,
                field.getFieldType(),
                capacity,
                writable,
                !text.isBlank(),
                text.length() > 200 ? text.substring(0, 200) + "…" : text,
                field.getInputType());
    }

    private static String original(
            TemplateContentUnitsService.Unit unit,
            Map<String, TemplateContentUnitsService.Source> sources) {
        StringBuilder text = new StringBuilder();
        TemplateContentUnitsService.Reference previous = null;
        for (var reference : unit.sources()) {
            if (previous != null
                    && !(previous.sourceId().equals(reference.sourceId())
                            && previous.end() == reference.start())) text.append('\n');
            text.append(
                    sources.get(reference.sourceId()).rawValue(),
                    reference.start(),
                    reference.end());
            previous = reference;
        }
        return text.toString();
    }
}

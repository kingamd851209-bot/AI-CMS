package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelEvidenceService.Region;
import com.xinhongru.cms.template.SiteModelPlanService.Field;
import com.xinhongru.cms.template.SiteModelPlanService.Model;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.cms.template.SiteModelPlanService.Proposal;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

@Service
public class NavigationModelDraftService {
    private final SiteModelPlanService plans;
    private final SiteModelNavigationService navigation;
    private final PackageDraftAnalysisService packageDrafts;
    private final TemplateContentUnitsService content;
    private final SiteModelEvidenceService evidence;
    private final UiPackageStorageService packages;

    public NavigationModelDraftService(
            SiteModelPlanService plans,
            SiteModelNavigationService navigation,
            PackageDraftAnalysisService packageDrafts,
            TemplateContentUnitsService content,
            SiteModelEvidenceService evidence,
            UiPackageStorageService packages) {
        this.plans = plans;
        this.navigation = navigation;
        this.packageDrafts = packageDrafts;
        this.content = content;
        this.evidence = evidence;
        this.packages = packages;
    }

    public record Evidence(
            String sourcePath,
            int resultIndex,
            String unitId,
            List<String> sourceIds,
            List<String> sourceKinds) {}

    public record Candidate(String key, String name, String type, List<Evidence> evidence) {}

    public record Preview(
            String title,
            String sourcePath,
            String mode,
            Model model,
            Model existingModel,
            List<Candidate> fields,
            List<String> warnings,
            String blocked,
            Model landingModel,
            List<Candidate> landingFields,
            long fieldRevision) {}

    private record Prepared(
            Plan plan, SiteModelNavigationService.Navigation nav, Preview preview) {}

    private record Unit(String name, String type, Evidence proof, List<Element> elements) {}

    private record Slot(String regionId, String relative, String type) {}

    public record PlaceholderField(String name, String type, String selector, String value) {}

    public record PlaceholderPreview(String sourcePath, String nodeName, Integer menuId,
            List<PlaceholderField> fields, String digest) {}

    private record PlaceholderDraft(Plan plan, Proposal proposal, PlaceholderPreview preview) {}

    public PlaceholderPreview previewPlaceholder(SiteModelNavigationService.Scope scope, String id,
            long planRevision, long navigationRevision, String nodeKey, Runnable guard) {
        return buildPlaceholder(scope, id, planRevision, navigationRevision, nodeKey, guard).preview();
    }

    public Plan confirmPlaceholder(SiteModelNavigationService.Scope scope, String id,
            long planRevision, long navigationRevision, String nodeKey, String digest, Runnable guard) {
        synchronized (plans) {
            var result = buildPlaceholder(scope, id, planRevision, navigationRevision, nodeKey, guard);
            if (!result.preview().digest().equals(digest)) throw conflict("占位栏目来源或字段已变化，请重新预览");
            return plans.review(scope.siteId(), scope.code(), scope.packageVersion(), id,
                    new SiteModelPlanService.Review(planRevision, result.proposal(), true), guard);
        }
    }

    private PlaceholderDraft buildPlaceholder(SiteModelNavigationService.Scope scope, String id,
            long planRevision, long navigationRevision, String nodeKey, Runnable guard) {
        guard.run();
        Plan plan = plans.continuationBase(scope.siteId(), scope.code(), scope.packageVersion(), id, planRevision);
        var nav = navigation.get(scope, id, guard);
        if (nav.status() != SiteModelNavigationService.Status.APPLIED || nav.revision() != navigationRevision
                || nav.planRevision() != planRevision)
            throw conflict("请先接续当前已审核结构的导航");
        var node = nav.nodes().stream().filter(item -> item.key().equals(nodeKey)).findFirst().orElse(null);
        if (node == null || node.menuId() == null || node.modelKey() != null || node.drift()
                || node.kind() != SiteModelNavigationService.Kind.SINGLE || node.sourcePath() != null
                || node.proposedUrl() != null || node.reuseMenuId() != null || node.observations().isEmpty()
                || Objects.equals(scope.rootMenuId(), node.menuId()) || node.sourceHref() == null
                || !node.sourceHref().matches("#+") || node.observations().stream().anyMatch(item ->
                        !"PLACEHOLDER".equals(item.linkKind()) || item.targetPath() != null)
                || nav.nodes().stream().anyMatch(item -> nodeKey.equals(item.parentKey())))
            throw conflict("仅支持没有公开原页的已应用占位单页叶栏目");
        var packageScope = new PackageDraftAnalysisService.Scope(scope.siteId(), scope.code(), scope.packageVersion());
        var packageState = packageDrafts.recoveredApplicationState(packageScope);
        if (!Objects.equals(packageState.structurePlanId(), id)
                || !Objects.equals(packageState.structurePlanRevision(), planRevision))
            throw conflict("请先将整包审核接续到当前结构");
        var selections = packageState.pages().stream().filter(item -> "CONFIRMED".equals(item.review())
                && Objects.equals(item.reviewMenuId(), node.menuId().longValue())).toList();
        if (selections.size() != 1 || !"READY".equals(selections.getFirst().state()))
            throw conflict("请先在整包审核内将唯一原页归属到本栏目");
        String sourcePath = selections.getFirst().sourcePath();
        if (nav.nodes().stream().anyMatch(item -> sourcePath.equals(item.sourcePath()))
                || plan.proposal().models().stream().anyMatch(item -> Objects.equals(item.menuId(), node.menuId().longValue()))
                || plan.proposal().regions().stream().anyMatch(item -> sourcePath.equals(item.sourcePath())
                        && Set.of("SINGLE", "DETAIL").contains(item.mode())))
            throw conflict("栏目或原页已有内容模型归属，请在现有模型流程核对");
        var workspace = content.requireSaved(scope.code(), scope.packageVersion(), sourcePath);
        var original = packages.readStaticPage(scope.code(), scope.packageVersion(), sourcePath);
        var source = evidence.page(scope.code(), scope.packageVersion(), sourcePath);
        if (workspace.revision() <= 0 || !workspace.digest().equals(original.digest()) || !source.digest().equals(original.digest()))
            throw conflict("请审核并保存本页的可维护字段");
        var body = source.regions().stream().filter(region -> "PAGE_SOURCE".equals(region.kind())
                && region.records().size() == 1).toList();
        if (body.size() != 1) throw conflict("原页缺少可确认的唯一单页主体");
        var record = body.getFirst().records().getFirst();
        var document = Jsoup.parse(original.html());
        var sources = new HashMap<String, TemplateContentUnitsService.Source>();
        workspace.sources().forEach(row -> sources.put(row.id(), row));
        var bindings = new LinkedHashMap<String, String>();
        var fields = new ArrayList<Field>();
        var previewFields = new ArrayList<PlaceholderField>();
        var usedNames = new HashSet<String>();
        for (var unit : workspace.units()) {
            if (unit.ignored() || unit.sources().size() != 1) continue;
            var ref = unit.sources().getFirst();
            var value = sources.get(ref.sourceId());
            if (value == null || !TemplateContentUnitsService.businessCandidate(value) || value.repeatGroup() != null
                    || ref.start() != 0 || ref.end() != value.rawValue().length()) continue;
            String attribute = switch (value.kind()) {
                case "IMAGE" -> "src";
                case "TEXT" -> "text";
                default -> "";
            };
            if (attribute.isEmpty() || !TemplateDomainAgentService.acceptsSourceKind(unit.fieldType(), value.kind())) continue;
            var origin = document.select(value.selector());
            if (origin.size() != 1) continue;
            var matching = record.values().stream().filter(item -> attribute.equals(item.attribute())
                    && item.rawValue().equals(value.rawValue())).filter(item -> {
                        var positions = document.select(item.selector());
                        return positions.size() == 1 && positions.first() == origin.first();
                    }).toList();
            if (matching.size() != 1 || bindings.containsValue(matching.getFirst().id())) continue;
            String name = unit.name().strip();
            if (name.isEmpty() || "待命名内容".equals(name) || !usedNames.add(name)) continue;
            if (fields.size() >= 30) throw conflict("请在页面字段审核中将补建字段收敛到 30 项以内");
            String fieldKey = "field_" + (fields.size() + 1);
            fields.add(new Field(fieldKey, name, unit.fieldType(), null));
            bindings.put(fieldKey, matching.getFirst().id());
            previewFields.add(new PlaceholderField(name, unit.fieldType(), value.selector(), value.rawValue()));
        }
        if (fields.isEmpty()) throw conflict("请先保存具有业务名称的独立文本或单一图片字段");
        var base = plan.proposal();
        var models = new ArrayList<>(base.models());
        String modelKey = key("placeholder", nodeKey);
        models.add(new Model(modelKey, node.name(), "SINGLE", node.menuId().longValue(), List.copyOf(fields)));
        var regions = new ArrayList<>(base.regions());
        regions.add(new SiteModelPlanService.RegionUse(sourcePath, body.getFirst().id(), modelKey, "SINGLE",
                List.of(new SiteModelPlanService.RecordUse(record.id(), Collections.unmodifiableMap(bindings)))));
        var proposal = new Proposal(base.summary(), List.copyOf(models), List.copyOf(regions), base.relations(),
                base.issues(), base.pages());
        String digest = UUID.nameUUIDFromBytes(new tools.jackson.databind.ObjectMapper().writeValueAsBytes(List.of(
                scope, id, planRevision, navigationRevision, nodeKey, packageState.revision(), workspace.revision(),
                workspace.digest(), proposal))).toString();
        guard.run();
        return new PlaceholderDraft(plan, proposal,
                new PlaceholderPreview(sourcePath, node.name(), node.menuId(), List.copyOf(previewFields), digest));
    }

    public Preview preview(
            SiteModelNavigationService.Scope scope,
            String planId,
            long planRevision,
            long navigationRevision,
            String nodeKey,
            Runnable guard) {
        return prepare(scope, planId, planRevision, navigationRevision, nodeKey, guard).preview();
    }

    public Plan save(
            SiteModelNavigationService.Scope scope,
            String planId,
            long planRevision,
            long navigationRevision,
            String nodeKey,
            Long fieldRevision,
            Model edit,
            Runnable guard) {
        synchronized (plans) {
            var prepared = prepare(scope, planId, planRevision, navigationRevision, nodeKey, guard);
            var proposal = prepared.preview();
            if (fieldRevision != null && fieldRevision != proposal.fieldRevision())
                throw conflict("整包字段草稿已更新，请重新生成候选");
            if (edit == null) throw new IllegalArgumentException("请选择可保存的模型候选");
            var landing =
                    proposal.landingModel() != null
                            && proposal.landingModel().key().equals(edit.key());
            var offered = landing ? proposal.landingModel() : proposal.model();
            var fields = landing ? proposal.landingFields() : proposal.fields();
            var existing = landing ? null : proposal.existingModel();
            if (offered == null
                    || fields.isEmpty()
                    || edit.fields() == null
                    || edit.fields().isEmpty()
                    || edit.fields().size() > 30
                    || !offered.key().equals(edit.key())
                    || !offered.kind().equals(edit.kind())
                    || !Objects.equals(offered.menuId(), edit.menuId())
                    || existing != null && !existing.name().equals(edit.name()))
                throw new IllegalArgumentException("请在当前取证候选内编辑增量字段");
            var candidates = new HashMap<String, Candidate>();
            fields.forEach(field -> candidates.put(field.key(), field));
            for (var field : edit.fields()) {
                var candidate = field == null ? null : candidates.remove(field.key());
                if (candidate == null
                        || field.fieldName() != null
                        || field.type() == null
                        || !candidate.type().equals(field.type())
                                && !compatible(candidate, field.type()))
                    throw new IllegalArgumentException("新增字段须来自同一候选，字段类型须与原文来源兼容");
            }
            var base = prepared.plan().proposal();
            var models = new ArrayList<>(base.models());
            var combined = new ArrayList<Field>();
            if (existing != null) combined.addAll(existing.fields());
            combined.addAll(edit.fields());
            var model =
                    new Model(
                            edit.key(),
                            edit.name(),
                            edit.kind(),
                            edit.menuId(),
                            List.copyOf(combined));
            if (existing != null)
                models.replaceAll(old -> old.key().equals(existing.key()) ? model : old);
            else models.add(model);
            var issues = new ArrayList<>(base.issues());
            String note = "导航原页 " + proposal.sourcePath() + " 的模型字段已作草稿增补；字段与原值绑定仍待审核。";
            if (!issues.contains(note) && issues.size() < 300) issues.add(note);
            var next =
                    new Proposal(
                            base.summary(),
                            List.copyOf(models),
                            base.regions(),
                            base.relations(),
                            List.copyOf(issues),
                            base.pages());
            return plans.forkNavigationModelDraft(
                    prepared.plan(),
                    next,
                    guard,
                    draft ->
                            navigation.forkModelDraft(
                                    scope,
                                    draft,
                                    prepared.nav(),
                                    nodeKey,
                                    landing ? null : model.key()),
                    landing ? null : nodeKey, proposal.sourcePath(), landing ? null : model.key());
        }
    }

    private static boolean compatible(Candidate candidate, String type) {
        if ("Editor".equals(type)
                && candidate.evidence().stream().anyMatch(row -> row.sourceIds().size() != 1))
            return false;
        return candidate.evidence().stream()
                .flatMap(row -> row.sourceKinds().stream())
                .allMatch(kind -> TemplateDomainAgentService.acceptsSourceKind(type, kind));
    }

    private Prepared prepare(
            SiteModelNavigationService.Scope scope,
            String planId,
            long planRevision,
            long navigationRevision,
            String nodeKey,
            Runnable guard) {
        guard.run();
        var plan =
                plans.continuationBase(
                        scope.siteId(), scope.code(), scope.packageVersion(), planId, planRevision);
        var nav = navigation.get(scope, planId, guard);
        if (nav.revision() != navigationRevision) throw conflict("导航已更新，请重新读取");
        var node =
                nav.nodes().stream()
                        .filter(row -> row.key().equals(nodeKey))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("请先核对并保存对应的导航节点"));
        var existing =
                plan.proposal().models().stream()
                        .filter(model -> model.key().equals(node.modelKey()))
                        .findFirst()
                        .orElse(null);
        String mode = existing == null ? "CREATE" : "EXTEND";
        if (node.sourcePath() == null
                || node.drift()
                || node.kind() == SiteModelNavigationService.Kind.DIRECTORY
                || nav.status() == SiteModelNavigationService.Status.APPLYING
                || existing == null && nav.status() == SiteModelNavigationService.Status.APPLIED)
            return new Prepared(plan, nav, blocked(node, existing, mode, "请先确认本节点的原页、导航职责或已应用归属"));
        var pkg =
                new PackageDraftAnalysisService.Scope(
                        scope.siteId(), scope.code(), scope.packageVersion());
        var overview = packageDrafts.get(pkg);
        if (overview.pages().stream()
                .noneMatch(
                        page ->
                                node.sourcePath().equals(page.sourcePath())
                                        && "READY".equals(page.state())))
            return new Prepared(plan, nav, blocked(node, existing, mode, "本页尚无有效整包字段建议，请先完成整包分析"));
        var detail = packageDrafts.currentPage(pkg, node.sourcePath());
        var workspace = content.load(scope.code(), scope.packageVersion(), node.sourcePath());
        var source =
                packages.readStaticPage(scope.code(), scope.packageVersion(), node.sourcePath());
        if (!source.digest().equals(workspace.digest())
                || detail.results().stream()
                        .anyMatch(
                                result ->
                                        !workspace.digest().equals(result.digest())
                                                || result.revision() != workspace.revision()))
            throw conflict("原页或建议的来源修订已变化，请重新分析");
        var doc = Jsoup.parse(source.html());
        var page = evidence.page(scope.code(), scope.packageVersion(), node.sourcePath());
        var warnings = new ArrayList<String>();
        if (!page.fragmentLinks().isEmpty())
            warnings.add(
                    "检测到 "
                            + page.fragmentLinks().stream()
                                    .map(SiteModelEvidenceService.PageLink::targetPath)
                                    .distinct()
                                    .count()
                            + " 个异步片段候选；触发和记录归属须核对，本轮未把异步字段并入主页面模型。");
        long shared =
                nav.nodes().stream()
                        .filter(row -> node.sourcePath().equals(row.sourcePath()))
                        .count();
        if (shared > 1) warnings.add("当前 " + shared + " 个导航节点共用原页；模型保存仅关联所选节点，共享记录归属须核对。");
        var available = collect(node.sourcePath(), detail.results(), workspace, doc);
        var repeated =
                page.regions().stream().filter(region -> "REPEATED".equals(region.kind())).toList();
        var matches =
                plan.proposal().regions().stream()
                        .filter(
                                use ->
                                        existing != null
                                                && existing.key().equals(use.modelKey())
                                                && node.sourcePath().equals(use.sourcePath()))
                        .toList();
        String kind = existing == null ? node.kind().name() : existing.kind();
        List<Candidate> fields;
        List<Candidate> landingFields = List.of();
        Model landingModel = null;
        if ("LIST".equals(kind)) {
            var selected =
                    repeated.stream()
                            .filter(
                                    region ->
                                            matches.stream()
                                                    .anyMatch(
                                                            use ->
                                                                    use.regionId()
                                                                                    .equals(
                                                                                            region
                                                                                                    .id())
                                                                            && "LIST"
                                                                                    .equals(
                                                                                            use
                                                                                                    .mode())))
                            .toList();
            if (existing == null && repeated.size() == 1) selected = repeated;
            fields = repeatedFields(doc, selected, matches, available, existing);
            if (selected.isEmpty()) warnings.add("列表记录区域尚不明确，单页简介、详情和其他列表不会自动成为列表字段。");
            var options = independentFields(doc, repeated, available, null);
            if (!options.isEmpty()) {
                landingFields = options;
                landingModel =
                        model(
                                key("landing", node.key()),
                                node.name() + "页面内容",
                                "SINGLE",
                                null,
                                options);
                warnings.add("另列单页展示内容，不写入列表记录字段。");
            }
        } else if ("SHARED".equals(kind)) {
            fields = List.of();
            warnings.add("共享字段须在公共区域审核中确认，当前页提取不追加共享字段。");
        } else {
            fields = independentFields(doc, repeated, available, existing);
        }
        if (existing != null && existing.menuId() != null)
            warnings.add("已建栏目沿用当前字段配置。保存后可审核新增业务字段，再用现有字段增补动作分配槽位。");
        Model model =
                fields.isEmpty()
                        ? null
                        : model(
                                existing == null ? key("nav", node.key()) : existing.key(),
                                existing == null ? node.name() : existing.name(),
                                kind,
                                existing == null ? null : existing.menuId(),
                                fields);
        String blocked =
                model != null || landingModel != null ? null : "没有可明确归属且未被现有模型覆盖的字段，需核对页面与区域。";
        guard.run();
        return new Prepared(
                plan,
                nav,
                new Preview(
                        node.name(),
                        node.sourcePath(),
                        mode,
                        model,
                        existing,
                        fields,
                        List.copyOf(warnings),
                        blocked,
                        landingModel,
                        landingFields,
                        detail.fieldRevision()));
    }

    private static Preview blocked(
            SiteModelNavigationService.Node node, Model existing, String mode, String message) {
        return new Preview(
                node.name(),
                node.sourcePath(),
                mode,
                null,
                existing,
                List.of(),
                List.of(),
                message,
                null,
                List.of(),
                0);
    }

    private static Model model(
            String key, String name, String kind, Long menu, List<Candidate> fields) {
        return new Model(
                key,
                name,
                kind,
                menu,
                fields.stream()
                        .map(field -> new Field(field.key(), field.name(), field.type(), null))
                        .toList());
    }

    private static List<Unit> collect(
            String path,
            List<TemplateDomainAgentService.Result> results,
            TemplateContentUnitsService.Workspace workspace,
            Document document) {
        var sources = new HashMap<String, TemplateContentUnitsService.Source>();
        workspace.sources().forEach(source -> sources.put(source.id(), source));
        var units = new ArrayList<Unit>();
        for (int index = 0; index < results.size(); index++) {
            for (var unit : results.get(index).units()) {
                if (unit.ignored()) continue;
                var refs = unit.sources().stream().map(ref -> sources.get(ref.sourceId())).toList();
                if (refs.stream()
                        .anyMatch(
                                source ->
                                        source == null
                                                || !TemplateContentUnitsService.businessCandidate(
                                                        source)
                                                || CounterScriptInputs.knownCounter(source)))
                    continue;
                var elements =
                        refs.stream().map(source -> select(document, source.selector())).toList();
                if (elements.stream().anyMatch(Objects::isNull)) continue;
                var proof =
                        new Evidence(
                                path,
                                index,
                                unit.id(),
                                refs.stream().map(TemplateContentUnitsService.Source::id).toList(),
                                refs.stream()
                                        .map(TemplateContentUnitsService.Source::kind)
                                        .toList());
                units.add(new Unit(unit.name(), unit.fieldType(), proof, elements));
            }
        }
        return units;
    }

    private static List<Candidate> independentFields(
            Document doc, List<Region> repeated, List<Unit> units, Model existing) {
        var records =
                repeated.stream()
                        .flatMap(region -> region.records().stream())
                        .map(row -> select(doc, row.selector()))
                        .filter(Objects::nonNull)
                        .toList();
        var groups = new LinkedHashMap<String, List<Unit>>();
        var accepted = new ArrayList<Unit>();
        for (var unit : units) {
            if (unit.elements().stream()
                    .anyMatch(
                            element ->
                                    records.stream().anyMatch(record -> inside(element, record))
                                            || Set.of("a", "em", "span")
                                                            .contains(element.normalName())
                                                    && element.closest("a[href]") != null))
                continue;
            if (accepted.stream().anyMatch(other -> overlaps(unit, other))) continue;
            accepted.add(unit);
            groups.computeIfAbsent(normal(unit.name()), key -> new ArrayList<>()).add(unit);
        }
        return candidates(groups, existing, false);
    }

    private static List<Candidate> repeatedFields(
            Document doc,
            List<Region> selected,
            List<SiteModelPlanService.RegionUse> uses,
            List<Unit> units,
            Model existing) {
        var groups = new LinkedHashMap<Slot, List<Unit>>();
        for (var region : selected) {
            var mapped = new HashSet<Element>();
            uses.stream()
                    .filter(use -> region.id().equals(use.regionId()))
                    .forEach(
                            use ->
                                    use.records()
                                            .forEach(
                                                    record ->
                                                            region.records().stream()
                                                                    .filter(
                                                                            row ->
                                                                                    row.id()
                                                                                            .equals(
                                                                                                    record
                                                                                                            .recordId()))
                                                                    .flatMap(
                                                                            row ->
                                                                                    row
                                                                                            .values()
                                                                                            .stream())
                                                                    .filter(
                                                                            value ->
                                                                                    record.bindings()
                                                                                            .containsValue(
                                                                                                    value
                                                                                                            .id()))
                                                                    .map(
                                                                            value ->
                                                                                    select(
                                                                                            doc,
                                                                                            value
                                                                                                    .selector()))
                                                                    .filter(Objects::nonNull)
                                                                    .forEach(mapped::add)));
            for (var row : region.records()) {
                var node = select(doc, row.selector());
                if (node == null) continue;
                var available = new HashSet<Slot>();
                for (var unit : units) {
                    if (unit.elements().stream()
                            .anyMatch(
                                    element ->
                                            !inside(element, node)
                                                    || mapped.stream()
                                                            .anyMatch(
                                                                    used ->
                                                                            inside(element, used)
                                                                                    || inside(
                                                                                            used,
                                                                                            element))))
                        continue;
                    var relative =
                            unit.elements().stream()
                                    .map(element -> relative(element, node))
                                    .reduce((left, right) -> left + "+" + right)
                                    .orElse("");
                    var slot = new Slot(region.id(), relative, unit.type());
                    if (available.add(slot))
                        groups.computeIfAbsent(slot, key -> new ArrayList<>()).add(unit);
                }
            }
        }
        var repeated = new LinkedHashMap<String, List<Unit>>();
        groups.forEach(
                (key, group) -> {
                    if (group.size() > 1) repeated.put(key.toString(), group);
                });
        return candidates(repeated, existing, true);
    }

    private static List<Candidate> candidates(
            Map<String, List<Unit>> groups, Model existing, boolean repeat) {
        var names = new HashSet<String>();
        if (existing != null) existing.fields().forEach(field -> names.add(normal(field.name())));
        int limit =
                existing == null ? 30 : Math.max(0, Math.min(30, 80 - existing.fields().size()));
        var result = new ArrayList<Candidate>();
        for (var entry : groups.entrySet()) {
            if (result.size() >= limit) break;
            var group = entry.getValue();
            if (group.stream().map(Unit::type).distinct().count() != 1) continue;
            String name = group.getFirst().name();
            if (repeat) {
                for (var unit : group) name = commonSuffix(name, unit.name());
                name = name.replaceFirst("^[\\s·:：、-]+", "");
                if (name.length() < 2) continue;
            }
            if (!names.add(normal(name))) continue;
            String type = group.getFirst().type();
            result.add(
                    new Candidate(
                            key("field", entry.getKey()),
                            name,
                            type,
                            group.stream().map(Unit::proof).limit(10).toList()));
        }
        return List.copyOf(result);
    }

    private static boolean overlaps(Unit left, Unit right) {
        if (left.proof().sourceIds().stream().anyMatch(right.proof().sourceIds()::contains))
            return true;
        return ("Editor".equals(left.type()) || "Editor".equals(right.type()))
                && left.elements().stream()
                        .anyMatch(
                                a ->
                                        right.elements().stream()
                                                .anyMatch(b -> inside(a, b) || inside(b, a)));
    }

    private static Element select(Document doc, String selector) {
        if (selector == null || selector.isBlank()) return null;
        try {
            return doc.selectFirst(selector);
        } catch (Selector.SelectorParseException invalid) {
            return null;
        }
    }

    private static boolean inside(Element element, Element root) {
        for (var node = element; node != null; node = node.parent()) if (node == root) return true;
        return false;
    }

    private static String relative(Element element, Element root) {
        var levels = new ArrayList<String>();
        for (var node = element; node != root; node = node.parent())
            levels.add(node.normalName() + ":" + node.elementSiblingIndex());
        Collections.reverse(levels);
        return String.join("/", levels);
    }

    private static String commonSuffix(String a, String b) {
        int i = 0;
        while (i < Math.min(a.length(), b.length())
                && a.charAt(a.length() - i - 1) == b.charAt(b.length() - i - 1)) i++;
        return a.substring(a.length() - i);
    }

    private static String normal(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static String key(String prefix, String value) {
        return prefix
                + "-"
                + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .substring(0, 12);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

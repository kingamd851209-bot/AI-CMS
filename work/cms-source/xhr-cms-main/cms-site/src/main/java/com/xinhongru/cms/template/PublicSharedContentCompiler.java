package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.cms.template.SiteModelNavigationService.Scope;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PublicSharedContentCompiler {
    public record Selection(
            String sourceId, int start, int end, String modelKey, String fieldKey) {}

    public record Request(
            String packageVersion,
            long revision,
            String sourcePath,
            String sourceDigest,
            List<Selection> selections,
            String previousDigest,
            String digest,
            Boolean confirmed) {}

    public record Source(
            String id,
            String kind,
            String rawValue,
            String selector,
            String publicRegion,
            String attribute) {}

    public record OwnerField(String fieldKey, String label, String fieldType, boolean hasValue) {}

    public record Owner(
            String modelKey,
            Long menuId,
            String label,
            String contentStatus,
            List<OwnerField> fields) {}

    public record Binding(
            String sourceId,
            int start,
            int end,
            String modelKey,
            String fieldKey,
            Long menuId,
            String fieldName,
            String fieldType,
            String selector,
            String publicRegion) {}

    public record Preview(String digest, List<Binding> bindings) {}

    public record Workspace(
            long revision,
            String sourcePath,
            String sourceDigest,
            List<Source> sources,
            List<Owner> owners,
            Preview confirmed,
            List<String> warnings) {}

    // Only field metadata and a compiler-controlled key are persisted with a template.
    public record Reference(
            String key,
            Long menuId,
            String fieldName,
            String fieldType,
            String configDigest,
            String selector,
            String originalValue) {}

    record Compiled(String html, List<Reference> references) {}

    private record Stored(
            String planId,
            long revision,
            String sourcePath,
            String sourceDigest,
            List<Selection> selections,
            Preview preview) {}

    private record Evidence(
            SiteModelEvidenceService.SharedPage page,
            List<Owner> owners,
            Map<String, Reference> fields,
            List<SiteModelPlanService.RegionUse> sourceRegions) {}

    private record Edit(int start, int end, Reference ref) {}

    private static final int MAX_BINDINGS = 50;
    private static final String TEXT = "cmsSharedText";
    private static final String MEDIA = "cmsSharedMedia";
    private final SiteModelPlanService plans;
    private final SiteModelEvidenceService evidence;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final ContentExtensionResources resources;
    private final ObjectMapper json = new ObjectMapper();
    private final SiteModelPlanStore store;

    public PublicSharedContentCompiler(
            SiteModelPlanService plans,
            SiteModelEvidenceService evidence,
            OneShotMapper menus,
            SiteProjectMapper projects,
            ContentExtensionResources resources,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model")
                    String root) {
        this.plans = plans;
        this.evidence = evidence;
        this.menus = menus;
        this.projects = projects;
        this.resources = resources;
        this.store = new SiteModelPlanStore(root, json);
    }

    public Workspace read(Scope scope, String planId, String sourcePath) {
        var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
        var current = current(scope, planId, plan.revision(), sourcePath);
        var stored = stored(scope, sourcePath);
        return new Workspace(
                plan.revision(),
                sourcePath,
                current.page().digest(),
                current.page().values().stream()
                        .filter(PublicSharedContentCompiler::supported)
                        .map(
                                item ->
                                        new Source(
                                                item.id(),
                                                item.typeHint(),
                                                item.rawValue(),
                                                item.selector(),
                                                item.region(),
                                                item.attribute()))
                        .toList(),
                current.owners(),
                stored == null ? null : stored.preview(),
                current.page().warnings());
    }

    public Preview preview(Scope scope, String planId, Request request) {
        var previous = stored(scope, request.sourcePath());
        var checked =
                prepare(
                        scope,
                        planId,
                        request,
                        current(scope, planId, request.revision(), request.sourcePath()));
        verifyPrevious(previous, request, checked);
        return checked;
    }

    public Preview previewSeedMapping(Scope scope, String planId, Request request) {
        if (request.selections() == null || request.selections().isEmpty())
            throw new IllegalArgumentException("请选择待初始化的公共字段");
        return prepare(
                scope,
                planId,
                request,
                current(scope, planId, request.revision(), request.sourcePath()),
                false);
    }

    @Transactional
    public Preview confirm(Scope scope, String planId, Request request) {
        if (!Boolean.TRUE.equals(request.confirmed()))
            throw new IllegalArgumentException("请明确确认公共信息绑定");
        var project = projects.selectByIdForUpdate(scope.siteId());
        if (project == null
                || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(project.getProjectCode(), scope.code())
                || !Objects.equals(project.getUiPackageVersion(), scope.packageVersion())
                || !Objects.equals(project.getRootMenuId(), scope.rootMenuId()))
            throw new IllegalArgumentException("当前站点或原包已变化，请重新读取");
        var previous = stored(scope, request.sourcePath());
        var checked =
                prepare(
                        scope,
                        planId,
                        request,
                        current(scope, planId, request.revision(), request.sourcePath()));
        verifyPrevious(previous, request, checked);
        if (!checked.digest().equals(request.digest()))
            throw new IllegalArgumentException("公共信息预览已变化");
        if (previous == null || !checked.equals(previous.preview()))
            store.saveSharedContent(
                    scope.siteId(),
                    scope.code(),
                    scope.packageVersion(),
                    key(request.sourcePath()),
                    new Stored(
                            planId,
                            request.revision(),
                            request.sourcePath(),
                            request.sourceDigest(),
                            List.copyOf(request.selections()),
                            checked));
        return checked;
    }

    private static void verifyPrevious(Stored previous, Request request, Preview checked) {
        if (previous == null && checked.bindings().isEmpty())
            throw new IllegalArgumentException("请选择待绑定的公共信息");
        String digest = previous == null ? null : previous.preview().digest();
        if (!Objects.equals(digest, request.previousDigest())
                && !Objects.equals(digest, checked.digest()))
            throw new IllegalArgumentException("公共信息审核已变化，请重新读取");
    }

    Compiled compile(Long siteId, String code, String version, String path, String html) {
        var stored = store.sharedContent(siteId, code, version, key(path), Stored.class);
        if (stored == null) return new Compiled(html, List.of());
        var project = projects.selectById(siteId);
        if (project == null
                || Integer.valueOf(1).equals(project.getDeleted())
                || project.getRootMenuId() == null
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getUiPackageVersion(), version)
                || !Objects.equals(path, stored.sourcePath()))
            throw new IllegalArgumentException("公共信息绑定不属于当前页面或站点");
        var scope = new Scope(siteId, code, version, project.getRootMenuId());
        var request =
                new Request(
                        version,
                        stored.revision(),
                        path,
                        stored.sourceDigest(),
                        stored.selections(),
                        null,
                        stored.preview().digest(),
                        true);
        var evidence = current(scope, stored.planId(), request.revision(), path, true);
        var checked = prepare(scope, stored.planId(), request, evidence);
        if (!checked.equals(stored.preview()))
            throw new IllegalArgumentException("已审核的公共信息来源或字段已变化");
        return apply(html, checked.bindings(), evidence);
    }

    boolean requiresPublicReceipt(Long siteId, String code, String version, String path) {
        var stored = store.sharedContent(siteId, code, version, key(path), Stored.class);
        return stored != null && !stored.preview().bindings().isEmpty();
    }

    static void verifyReapplication(
            String previous,
            List<Reference> previousRefs,
            String next,
            List<Reference> nextRefs,
            boolean list,
            String sourcePath,
            java.util.function.UnaryOperator<String> navigation) {
        var oldDocument =
                Jsoup.parse(navigation.apply(withoutInstructions(previous, previousRefs)));
        var newDocument = Jsoup.parse(navigation.apply(withoutInstructions(next, nextRefs)));
        oldDocument.outputSettings().prettyPrint(false);
        newDocument.outputSettings().prettyPrint(false);
        // 已发布模板仍可能保留早期未规范的图标地址。比较同一原页下的资源实际目标。
        PublicBindingResources.rebaseLinks(oldDocument, sourcePath);
        PublicBindingResources.rebaseLinks(newDocument, sourcePath);
        if (list && oldDocument.selectFirst("[data-cms-pagination]") == null) {
            var pagination = newDocument.select("[data-cms-pagination=true]");
            if (pagination.size() == 1
                    && "${cmsBoundPage.total > 0}".equals(pagination.first().attr("th:if")))
                pagination.first().removeAttr("data-cms-pagination");
        }
        if (!oldDocument.outerHtml().equals(newDocument.outerHtml()))
            throw new IllegalArgumentException("原公开模板还包含本次审核范围以外的变化，请逐项核对");
    }

    private Evidence current(Scope scope, String planId, long revision, String path) {
        return current(scope, planId, revision, path, false);
    }

    private Evidence current(
            Scope scope, String planId, long revision, String path, boolean saved) {
        var plan =
                saved
                        ? plans.get(
                                scope.siteId(),
                                scope.code(),
                                scope.packageVersion(),
                                planId,
                                revision)
                        : plans.confirmed(
                                scope.siteId(),
                                scope.code(),
                                scope.packageVersion(),
                                planId,
                                revision);
        if (!"CONFIRMED".equals(plan.status()))
            throw new IllegalArgumentException("公共字段需要已确认的整站计划");
        var page = evidence.sharedValues(scope.code(), scope.packageVersion(), path);
        var owners = new ArrayList<Owner>();
        var references = new HashMap<String, Reference>();
        for (var model : plan.proposal().models()) {
            if (!"SINGLE".equals(model.kind())
                    || model.menuId() == null
                    || Objects.equals(model.menuId(), scope.rootMenuId().longValue())
                    || plan.proposal().regions().stream()
                            .noneMatch(
                                    use ->
                                            model.key().equals(use.modelKey())
                                                    && SiteModelEvidenceService.publicRegion(
                                                            use.sourcePath(), use.regionId())))
                continue;
            var menu = menus.selectPublicBindingMenu(scope.siteId(), model.menuId());
            if (!owned(menu, scope.siteId(), scope.rootMenuId())
                    || !Objects.equals(menu.getId().longValue(), model.menuId())) continue;
            var fields = new ArrayList<OwnerField>();
            for (var field : model.fields()) {
                if (field.fieldName() == null
                        || !Set.of("SingleLine", "Image").contains(field.type())
                        || plan.proposal().regions().stream()
                                .filter(
                                        use ->
                                                model.key().equals(use.modelKey())
                                                        && SiteModelEvidenceService.publicRegion(
                                                                use.sourcePath(), use.regionId()))
                                .noneMatch(
                                        use ->
                                                use.records().stream()
                                                        .anyMatch(
                                                                record ->
                                                                        record.bindings()
                                                                                .containsKey(
                                                                                        field
                                                                                                .key())))
                        || definition(menu, field.fieldName(), field.type()) == null) continue;
                String existing = value(menu, field.fieldName());
                fields.add(
                        new OwnerField(
                                field.key(),
                                field.name(),
                                field.type(),
                                existing != null && !existing.isBlank()));
                references.put(
                        model.key() + "/" + field.key(),
                        reference(menu, field.fieldName(), field.type()));
            }
            if (!fields.isEmpty())
                owners.add(
                        new Owner(
                                model.key(),
                                model.menuId(),
                                model.name(),
                                switch (Objects.requireNonNullElse(menu.getStatus(), 0)) {
                                    case 1 -> "PENDING_REVIEW";
                                    case 2 -> "APPROVED";
                                    case 3 -> "PUBLISHED";
                                    default -> "DRAFT";
                                },
                                List.copyOf(fields)));
        }
        var sourceRegions =
                plan.proposal().regions().stream()
                        .filter(
                                use ->
                                        path.equals(use.sourcePath())
                                                && SiteModelEvidenceService.publicRegion(
                                                        path, use.regionId()))
                        .toList();
        return new Evidence(page, List.copyOf(owners), references, sourceRegions);
    }

    private static boolean supported(SiteModelEvidenceService.SharedValue value) {
        return "TEXT".equals(value.typeHint()) && "text".equals(value.attribute())
                || "IMAGE".equals(value.typeHint()) && "src".equals(value.attribute());
    }

    private Preview prepare(Scope scope, String planId, Request request, Evidence evidence) {
        return prepare(scope, planId, request, evidence, true);
    }

    private Preview prepare(
            Scope scope, String planId, Request request, Evidence evidence, boolean published) {
        if (!Objects.equals(request.sourceDigest(), evidence.page().digest())
                || request.selections() == null
                || request.selections().size() > MAX_BINDINGS)
            throw new IllegalArgumentException("公共信息来源或选择已变化");
        var bindings = new ArrayList<Binding>();
        var sources = new HashMap<String, SiteModelEvidenceService.SharedValue>();
        evidence.page().values().forEach(source -> sources.put(source.id(), source));
        for (var selection : request.selections()) {
            if (selection == null) throw new IllegalArgumentException("公共信息映射项缺失");
            var source = sources.get(selection.sourceId());
            var ref = evidence.fields().get(selection.modelKey() + "/" + selection.fieldKey());
            if (source == null
                    || !supported(source)
                    || ref == null
                    || published
                            && evidence.owners().stream()
                                    .noneMatch(
                                            owner ->
                                                    owner.modelKey().equals(selection.modelKey())
                                                            && "PUBLISHED"
                                                                    .equals(owner.contentStatus()))
                    || selection.start() < 0
                    || selection.end() <= selection.start()
                    || selection.end() > source.rawValue().length()
                    || splitSurrogate(source.rawValue(), selection.start())
                    || splitSurrogate(source.rawValue(), selection.end())
                    || "Image".equals(ref.fieldType()) != "IMAGE".equals(source.typeHint())
                    || "Image".equals(ref.fieldType())
                            && (selection.start() != 0
                                    || selection.end() != source.rawValue().length()))
                throw new IllegalArgumentException("来源区间、字段类型或公共单页发布状态无法核验");
            if (!published
                    && evidence.sourceRegions().stream()
                            .noneMatch(
                                    use ->
                                            selection.modelKey().equals(use.modelKey())
                                                    && use.records().stream()
                                                            .anyMatch(
                                                                    record ->
                                                                            selection
                                                                                    .sourceId()
                                                                                    .equals(
                                                                                            record.bindings()
                                                                                                    .get(
                                                                                                            selection
                                                                                                                    .fieldKey())))))
                throw new IllegalArgumentException("请回到已审核的原始来源页初始化，或先更新并确认整站计划中的公共字段来源");
            bindings.add(
                    new Binding(
                            selection.sourceId(),
                            selection.start(),
                            selection.end(),
                            selection.modelKey(),
                            selection.fieldKey(),
                            ref.menuId(),
                            ref.fieldName(),
                            ref.fieldType(),
                            source.selector(),
                            source.region()));
        }
        bindings.sort(Comparator.comparing(Binding::sourceId).thenComparingInt(Binding::start));
        for (int i = 1; i < bindings.size(); i++) {
            var previous = bindings.get(i - 1);
            var next = bindings.get(i);
            if (previous.sourceId().equals(next.sourceId()) && previous.end() > next.start())
                throw new IllegalArgumentException("公共信息来源区间重叠");
        }
        String hash =
                PublicBindingApplication.digest(
                        json.writeValueAsString(
                                List.of(
                                        scope.siteId(),
                                        scope.code(),
                                        scope.packageVersion(),
                                        planId,
                                        request.revision(),
                                        request.sourcePath(),
                                        request.sourceDigest(),
                                        bindings,
                                        bindings.stream()
                                                .map(
                                                        binding ->
                                                                evidence.fields()
                                                                        .get(
                                                                                binding.modelKey()
                                                                                        + "/"
                                                                                        + binding
                                                                                                .fieldKey()))
                                                .toList())));
        return new Preview(hash, List.copyOf(bindings));
    }

    private static Compiled apply(String html, List<Binding> bindings, Evidence evidence) {
        if (bindings.isEmpty()) return new Compiled(html, List.of());
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var references = new LinkedHashMap<Element, Reference>();
        var edits = new IdentityHashMap<TextNode, List<Edit>>();
        var imageNodes = new HashSet<Element>();
        var sources = new HashMap<String, SiteModelEvidenceService.SharedValue>();
        evidence.page().values().forEach(source -> sources.put(source.id(), source));
        for (var binding : bindings) {
            var source = sources.get(binding.sourceId());
            var matches = document.select(binding.selector());
            if (matches.size() != 1 || !Objects.equals(source.region(), region(matches.first())))
                throw new IllegalArgumentException("公共信息在已编译页面中无法唯一定位");
            Element element = matches.first();
            var ref = evidence.fields().get(binding.modelKey() + "/" + binding.fieldKey());
            String original = source.rawValue().substring(binding.start(), binding.end());
            if ("Image".equals(ref.fieldType())) {
                if (!element.is("img[src]")
                        || !imageNodes.add(element)
                        || element.hasAttr("srcset")
                        || element.hasAttr("data-src")
                        || element.hasAttr("th:src"))
                    throw new IllegalArgumentException("二维码或公共图片含多重来源，须人工核对");
                if (!element.attr("src").equals(source.rawValue())
                        && !element.attr("src")
                                .equals(
                                        EditorContentPreparation.packageReference(
                                                evidence.page().sourcePath(), source.rawValue())))
                    throw new IllegalArgumentException("公共图片来源已被其他编译步骤修改");
                var image =
                        new Reference(
                                ref.key(),
                                ref.menuId(),
                                ref.fieldName(),
                                ref.fieldType(),
                                ref.configDigest(),
                                null,
                                element.attr("src"));
                element.attributes().asList().stream()
                        .filter(attr -> "src".equals(attr.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .setKey("th:src");
                element.attr("th:src", expression(image));
                references.put(element, image);
            } else {
                var nodes =
                        element.textNodes().stream()
                                .filter(node -> node.getWholeText().equals(source.rawValue()))
                                .toList();
                if (nodes.size() != 1) throw new IllegalArgumentException("公共文字跨节点或出现多次，请单独审核原文范围");
                edits.computeIfAbsent(nodes.getFirst(), ignored -> new ArrayList<>())
                        .add(
                                new Edit(
                                        binding.start(),
                                        binding.end(),
                                        new Reference(
                                                ref.key(),
                                                ref.menuId(),
                                                ref.fieldName(),
                                                ref.fieldType(),
                                                ref.configDigest(),
                                                null,
                                                original)));
            }
        }
        edits.forEach(
                (node, list) -> {
                    list.sort(Comparator.comparingInt(Edit::start));
                    String text = node.getWholeText();
                    int position = 0;
                    for (var edit : list) {
                        if (edit.start() < position) throw new IllegalArgumentException("公共文字区间重叠");
                        node.before(new TextNode(text.substring(position, edit.start())));
                        var marker =
                                new Element("th:block").attr("th:text", expression(edit.ref()));
                        node.before(marker);
                        references.put(marker, edit.ref());
                        position = edit.end();
                    }
                    node.before(new TextNode(text.substring(position)));
                    node.remove();
                });
        var ordered = new ArrayList<Reference>();
        if (document.selectFirst("[data-cms-shared-ref]") != null)
            throw new IllegalArgumentException("原模板含公共字段保留标记");
        for (var marker : document.getAllElements()) {
            var ref = references.get(marker);
            if (ref == null) continue;
            String occurrence = Integer.toString(ordered.size() + 1);
            marker.attr("data-cms-shared-ref", occurrence);
            ordered.add(
                    new Reference(
                            ref.key(),
                            ref.menuId(),
                            ref.fieldName(),
                            ref.fieldType(),
                            ref.configDigest(),
                            "[data-cms-shared-ref=" + occurrence + "]",
                            ref.originalValue()));
        }
        var compiled = new Compiled(document.outerHtml(), List.copyOf(ordered));
        withoutInstructions(compiled.html(), compiled.references());
        return compiled;
    }

    /**
     * Removes only exact trusted generated instructions before the original body validator runs.
     */
    static String withoutInstructions(String html, List<Reference> references) {
        if (references.isEmpty()) return html;
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (references.size() > MAX_BINDINGS
                || document.select("[data-cms-shared-ref]").size() != references.size())
            throw new IllegalArgumentException("公共字段表达式数量不符合编译契约");
        var markers = new LinkedHashMap<Element, Reference>();
        for (var ref : references) {
            var hits = document.select(ref.selector());
            if (hits.size() != 1
                    || region(hits.first()) == null
                    || markers.put(hits.first(), ref) != null)
                throw new IllegalArgumentException("公共字段表达式位置不符合编译契约");
        }
        markers.forEach(
                (element, ref) -> {
                    element.removeAttr("data-cms-shared-ref");
                    if ("SingleLine".equals(ref.fieldType())
                            && expression(ref).equals(element.attr("th:text"))
                            && "th:block".equals(element.tagName())
                            && element.childNodeSize() == 0
                            && element.attributes().size() == 1)
                        element.replaceWith(new TextNode(ref.originalValue()));
                    else if ("Image".equals(ref.fieldType())
                            && expression(ref).equals(element.attr("th:src"))
                            && element.is("img")
                            && !element.hasAttr("src")) restoreImage(element, ref.originalValue());
                    else throw new IllegalArgumentException("公共字段表达式不符合编译契约");
                });
        return document.outerHtml();
    }

    private static void restoreImage(Element element, String source) {
        element.attributes().asList().stream()
                .filter(attr -> "th:src".equals(attr.getKey()))
                .findFirst()
                .orElseThrow()
                .setKey("src");
        element.attr("src", source);
    }

    void contribute(Long siteId, Integer rootId, List<Reference> references, Context context) {
        var owners = new HashMap<Long, OneshotEntity>();
        var text = new LinkedHashMap<String, String>();
        var media = new LinkedHashMap<String, String>();
        for (var ref : references) {
            var owner =
                    owners.computeIfAbsent(
                            ref.menuId(), id -> menus.selectPublicBindingMenu(siteId, id));
            if (!owned(owner, siteId, rootId)
                    || !Objects.equals(ref.menuId(), owner.getId().longValue())
                    || !Integer.valueOf(3).equals(owner.getStatus())
                    || !ContentFieldConfigDigest.of(owner).equals(ref.configDigest()))
                throw new IllegalArgumentException("公共单页未发布或字段配置已变化");
            var field = definition(owner, ref.fieldName(), ref.fieldType());
            if (field == null) throw new IllegalArgumentException("公共字段已停用或类型变化");
            String value = value(owner, ref.fieldName());
            if ("Image".equals(ref.fieldType()))
                media.put(ref.key(), resources.prepareTemplateMedia(field, value, siteId));
            else text.put(ref.key(), Objects.toString(value, ""));
        }
        context.setVariable(TEXT, Map.copyOf(text));
        context.setVariable(MEDIA, Map.copyOf(media));
    }

    private static Reference reference(OneshotEntity owner, String fieldName, String type) {
        return new Reference(
                PublicBindingApplication.digest(owner.getId() + "/" + fieldName),
                owner.getId().longValue(),
                fieldName,
                type,
                ContentFieldConfigDigest.of(owner),
                null,
                null);
    }

    private static String expression(Reference ref) {
        if (ref.key() == null
                || !ref.key().matches("[a-f0-9]{64}")
                || !Set.of("SingleLine", "Image").contains(ref.fieldType()))
            throw new IllegalArgumentException("公共字段表达式标识无效");
        return "${" + ("Image".equals(ref.fieldType()) ? MEDIA : TEXT) + "['" + ref.key() + "']}";
    }

    private static String region(Element element) {
        var owner = TemplateSeedService.publicRegionOwner(element);
        return owner == null
                ? null
                : owner.is("header,.header,[role=banner]") ? "HEADER" : "FOOTER";
    }

    private static boolean owned(OneshotEntity item, Long siteId, Integer rootId) {
        return item != null
                && Objects.equals(item.getSiteProjectId(), siteId)
                && Objects.equals(item.getSiteRootId(), rootId)
                && !Objects.equals(item.getId(), rootId)
                && !Boolean.TRUE.equals(item.getIsDeleted())
                && Integer.valueOf(0).equals(item.getPageType())
                && Boolean.TRUE.equals(item.getHasManageChannel());
    }

    private static ContentFieldDefinition definition(
            OneshotEntity menu, String field, String type) {
        if (field == null) return null;
        try {
            return ContentModelCodec.read(menu.getMenuField()).stream()
                    .filter(
                            item ->
                                    item != null
                                            && field.equals(item.getFieldName())
                                            && type.equals(item.getFieldType())
                                            && ContentFieldDefinition.enabled(item.getIsOpen())
                                            && supportedSlot(item))
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException invalidLegacyConfig) {
            return null;
        }
    }

    private static boolean supportedSlot(ContentFieldDefinition field) {
        String name = field.getFieldName();
        if (ContentExtensionFields.isName(name)) {
            try {
                ContentExtensionFields.validateDefinition(field);
                return Set.of("SingleLine", "Image").contains(field.getFieldType());
            } catch (IllegalArgumentException invalidLegacySlot) {
                return false;
            }
        }
        if ("Image".equals(field.getFieldType()) && !NativeImageFields.supports(field)
                || "SingleLine".equals(field.getFieldType())
                        && !name.matches("className|singleLine[0-9]+")) return false;
        try {
            return com.xinhongru.common.reflect.EntityFields.find(OneshotEntity.class, name)
                            .getType()
                    == String.class;
        } catch (NoSuchFieldException invalidLegacySlot) {
            return false;
        }
    }

    private static String value(OneshotEntity item, String field) {
        if (ContentExtensionFields.isName(field)) {
            Object value = item.getExtensionValues().get(field);
            if (value == null || value instanceof String) return (String) value;
        } else {
            try {
                var member = com.xinhongru.common.reflect.EntityFields.find(item.getClass(), field);
                member.setAccessible(true);
                return (String) member.get(item);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("公共字段无法读取", error);
            }
        }
        throw new IllegalArgumentException("公共字段不是受控字符串");
    }

    private static boolean splitSurrogate(String value, int index) {
        return index > 0
                && index < value.length()
                && Character.isSurrogatePair(value.charAt(index - 1), value.charAt(index));
    }

    private Stored stored(Scope scope, String path) {
        return store.sharedContent(
                scope.siteId(), scope.code(), scope.packageVersion(), key(path), Stored.class);
    }

    private static String key(String path) {
        if (!com.xinhongru.cms.uipackage.StaticPageSource.supports(path)
                || path.length() > 512
                || path.indexOf('\\') >= 0
                || path.contains(":")
                || path.contains("?")
                || path.contains("#")
                || java.util.Arrays.stream(path.split("/", -1))
                        .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("无效的公共信息来源页面");
        return PublicBindingApplication.digest(path);
    }
}

package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentReferenceValues;
import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.cms.siteservice.SiteServerRegistry;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.*;
import com.xinhongru.entity.*;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.web.service.InfoService;
import com.xinhongru.web.resonseVo.IdEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 将经审核的详情集合投影到原始重复条目的样式。 */
@Component
public final class DetailCollections {
    private static final String EXCLUDED =
            "header:not(main header),footer:not(main footer),nav,form,aside,script,style,template";
    private static final String UNBOUND_MEDIA =
            "img,video,audio,picture,source,iframe,object,embed";
    private static final int MAX_COLLECTIONS = 12;

    public record Field(String fieldName, String label, String type) {}

    public record Candidate(
            String regionId,
            String mode,
            String reason,
            Long targetMenuId,
            String targetMenuName,
            int recordCount,
            List<Field> fields,
            String unavailableReason,
            List<Integer> targetMenuIds,
            String regionSelector,
            List<String> rowSelectors) {
        public Candidate {
            targetMenuIds =
                    targetMenuIds == null || targetMenuIds.isEmpty()
                            ? targetMenuId == null
                                    ? List.of()
                                    : List.of(Math.toIntExact(targetMenuId))
                            : List.copyOf(targetMenuIds);
            rowSelectors = rowSelectors == null ? List.of() : List.copyOf(rowSelectors);
        }

        public Candidate(String regionId, String mode, String reason, Long targetMenuId,
                String targetMenuName, int recordCount, List<Field> fields,
                String unavailableReason, List<Integer> targetMenuIds) {
            this(regionId, mode, reason, targetMenuId, targetMenuName, recordCount, fields,
                    unavailableReason, targetMenuIds, null, List.of());
        }

        public Candidate(
                String regionId,
                String mode,
                String reason,
                Long targetMenuId,
                String targetMenuName,
                int recordCount,
                List<Field> fields,
                String unavailableReason) {
            this(
                    regionId,
                    mode,
                    reason,
                    targetMenuId,
                    targetMenuName,
                    recordCount,
                    fields,
                    unavailableReason,
                    List.of(), null, List.of());
        }
    }

    record Slot(String selector, Field field, String originalText) {}

    record Binding(
            String regionId,
            String mode,
            Long targetMenuId,
            String referenceField,
            String regionSelector,
            String emptySelector,
            List<String> rowSelectors,
            List<Slot> slots,
            String linkSelector,
            List<Integer> targetMenuIds,
            String sitePlanId,
            String packageVersion,
            String ownerModelKey,
            String referenceFieldKey) {
        Binding(
                String regionId,
                String mode,
                Long targetMenuId,
                String referenceField,
                String regionSelector,
                String emptySelector,
                List<String> rowSelectors,
                List<Slot> slots,
                String linkSelector) {
            this(
                    regionId,
                    mode,
                    targetMenuId,
                    referenceField,
                    regionSelector,
                    emptySelector,
                    rowSelectors,
                    slots,
                    linkSelector,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    private record Proposal(Candidate candidate, Binding binding) {}

    private final SiteModelPlanService sitePlans;
    private final SiteModelEvidenceService evidence;
    private final UiPackageStorageService packages;
    private final OneShotMapper menus;
    private final InfoService info;
    private final ContentReferenceValues references;
    private final ContentLinkService links;
    private final SiteServerRegistry routes;
    private final TemplateStorageService templates;

    @org.springframework.beans.factory.annotation.Autowired
    private SiteModelNavigationService navigation;

    @org.springframework.beans.factory.annotation.Autowired
    private SiteModelStructureService structures;

    @org.springframework.beans.factory.annotation.Autowired
    private ContentExtensionResources resources;

    DetailCollections(
            SiteModelPlanService sitePlans,
            SiteModelEvidenceService evidence,
            UiPackageStorageService packages,
            OneShotMapper menus,
            InfoService info,
            ContentReferenceValues references,
            ContentLinkService links,
            SiteServerRegistry routes,
            TemplateStorageService templates) {
        this.sitePlans = sitePlans;
        this.evidence = evidence;
        this.packages = packages;
        this.menus = menus;
        this.info = info;
        this.references = references;
        this.links = links;
        this.routes = routes;
        this.templates = templates;
    }

    List<Candidate> inspect(TemplateFieldPlanService.Plan plan, OneshotEntity owner) {
        return proposals(plan, owner).stream().map(Proposal::candidate).toList();
    }

    List<Binding> confirm(
            TemplateFieldPlanService.Plan plan, OneshotEntity owner, List<String> selected) {
        if (selected.isEmpty()) return List.of();
        if (selected.size() > MAX_COLLECTIONS || new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("详情集合重复或超出数量限制");
        var candidates = proposals(plan, owner);
        for (var id : selected) {
            var choice =
                    candidates.stream()
                            .filter(c -> c.candidate.regionId.equals(id))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("详情集合未获当前页面审核"));
            if (choice.binding == null)
                throw new IllegalArgumentException(choice.candidate.unavailableReason);
        }
        var chosenReferences = new HashSet<String>();
        for (var choice : candidates) {
            if (selected.contains(choice.candidate.regionId) && "INLINE_REFERENCE".equals(choice.candidate.mode)
                    && !chosenReferences.add(choice.binding.referenceField))
                throw new IllegalArgumentException("多个推荐链接使用相同引用字段，请先核对各区域的内容归属");
        }
        return candidates.stream()
                .filter(c -> selected.contains(c.candidate.regionId))
                .map(Proposal::binding)
                .toList();
    }

    private List<Proposal> proposals(TemplateFieldPlanService.Plan plan, OneshotEntity owner) {
        if (plan.pageType() != 1 && plan.pageType() != 0) return List.of();
        var confirmed =
                sitePlans.confirmedDerivedPlan(
                        plan.siteId(), plan.code(), plan.packageVersion(), plan.id());
        if (confirmed == null) return List.of();
        var models =
                confirmed.proposal().models().stream()
                        .collect(
                                Collectors.toMap(
                                        SiteModelPlanService.Model::key, Function.identity()));
        var inlineLinks = plan.pageType() == 0
                ? singlePageLinks(plan, confirmed, owner, models) : List.<Proposal>of();
        var applicable = new java.util.HashMap<String, Boolean>();
        var uses =
                confirmed.proposal().regions().stream()
                        .filter(
                                use ->
                                        plan.sourcePath().equals(use.sourcePath())
                                                && use.maintenance() != null
                                                && ((plan.pageType() == 0)
                                                        == "PAGE_LIST".equals(use.maintenance().mode()))
                                                && models.containsKey(
                                                        use.maintenance().ownerModelKey())
                                                && applicable.computeIfAbsent(
                                                        use.maintenance().ownerModelKey(),
                                                        key ->
                                                                acceptsOwner(
                                                                        plan,
                                                                        owner,
                                                                        confirmed,
                                                                        models.get(key))))
                        .toList();
        if (uses.isEmpty()) return inlineLinks;
        var page = evidence.page(plan.code(), plan.packageVersion(), plan.sourcePath());
        var source = packages.readStaticPage(plan.code(), plan.packageVersion(), plan.sourcePath());
        if (!plan.digest().equals(page.digest()) || !plan.digest().equals(source.digest()))
            throw new IllegalArgumentException("详情集合来源已变化");
        var document = parse(source.html());
        return java.util.stream.Stream.concat(inlineLinks.stream(), uses.stream()
                .map(
                        use ->
                                proposal(
                                        plan,
                                        confirmed,
                                        owner,
                                        models.get(use.modelKey()),
                                        models.get(use.maintenance().ownerModelKey()),
                                        use,
                                        page,
                                        document)))
                .toList();
    }

    private List<Proposal> singlePageLinks(TemplateFieldPlanService.Plan plan, SiteModelPlanService.Plan reviewed,
            OneshotEntity menu, Map<String, SiteModelPlanService.Model> models) {
        var owner = models.values().stream().filter(m -> Objects.equals(m.menuId(), plan.menuId())).toList();
        if (owner.size() != 1 || menu == null || !Objects.equals(menu.getSiteProjectId(), plan.siteId())) return List.of();
        var relations = reviewed.proposal().relations().stream()
                .filter(r -> owner.getFirst().key().equals(r.sourceModelKey()) && r.targetModelKey() != null).toList();
        if (relations.isEmpty()) return List.of();
        var page = evidence.page(plan.code(), plan.packageVersion(), plan.sourcePath());
        var source = packages.readStaticPage(plan.code(), plan.packageVersion(), plan.sourcePath());
        if (!plan.digest().equals(page.digest()) || !plan.digest().equals(source.digest())) throw new IllegalArgumentException("单页引用原文已变化");
        var document = parse(source.html());
        var proposals = new ArrayList<Proposal>();
        for (var link : page.links()) {
            if (link.targetPath() == null) continue;
            var anchors = document.select(link.selector());
            if (anchors.size() != 1) continue;
            var anchor = anchors.first();
            if (!anchor.is("a[href]") || !anchor.attr("href").equals(link.rawValue()) || anchor.childrenSize()!=0
                    || anchor.text().isBlank() || anchor.closest(EXCLUDED + ",.header,.footer,[role=banner],[role=contentinfo],[role=navigation],[hidden]") != null || anchor.hasAttr("id") || anchor.hasAttr("onclick") || hasInstructions(anchor)) continue;
            var targetKeys = reviewed.proposal().regions().stream()
                    .filter(r -> "DETAIL".equals(r.mode()) && r.maintenance()==null && link.targetPath().equals(r.sourcePath()))
                    .map(SiteModelPlanService.RegionUse::modelKey).distinct().toList();
            if (targetKeys.size()!=1) continue;
            var options = relations.stream().filter(r -> targetKeys.getFirst().equals(r.targetModelKey())).toList();
            if (options.size()!=1) continue;
            var relation=options.getFirst();
            var field=owner.getFirst().fields().stream().filter(v -> v.key().equals(relation.fieldKey())).findFirst().orElse(null);
            var target=models.get(relation.targetModelKey());
            if (field==null || target==null || target.menuId()==null || !"LIST".equals(target.kind())) continue;
            String id=PublicBindingApplication.digest(plan.sourcePath()+"|"+link.selector()+"|"+field.key());
            var allowed=field.fieldName()==null ? List.<Integer>of() : structures.referenceTargetIds(
                    new SiteModelStructureService.Scope(plan.siteId(),plan.code(),plan.packageVersion(),menu.getSiteRootId()),
                    reviewed.id(),owner.getFirst().key(),field.key(),field.fieldName(),menu.getId());
            var title=new Field("title","内容标题","SingleLine");
            String missing=allowed.isEmpty() || referenceField(menu,field.fieldName())==null ? "先在内容模型中增补引用字段并确认其来源栏目。" : null;
            var candidate=new Candidate(id,"INLINE_REFERENCE","原文「"+anchor.text()+"」链接至 "+link.targetPath()
                    +"。按「"+field.name()+"」中选定的已发布记录显示标题和正式详情链接。",target.menuId(),target.name(),1,List.of(title),missing,allowed);
            var binding=missing!=null ? null : new Binding(id,"INLINE_REFERENCE",target.menuId(),field.fieldName(),
                    link.selector(),link.selector(),List.of(link.selector()),List.of(new Slot(link.selector(),title,anchor.text())),
                    link.selector(),allowed,reviewed.id(),plan.packageVersion(),owner.getFirst().key(),field.key());
            proposals.add(new Proposal(candidate,binding));
        }
        return List.copyOf(proposals);
    }

    private boolean acceptsOwner(
            TemplateFieldPlanService.Plan plan,
            OneshotEntity owner,
            SiteModelPlanService.Plan confirmed,
            SiteModelPlanService.Model model) {
        if (owner == null
                || owner.getId() == null
                || !Objects.equals(plan.menuId(), owner.getId().longValue())) return false;
        if (navigation == null) return Objects.equals(model.menuId(), plan.menuId());
        var scope =
                new SiteModelNavigationService.Scope(
                        plan.siteId(), plan.code(), plan.packageVersion(), owner.getSiteRootId());
        return navigation
                .modelMenuIds(scope, confirmed.id(), confirmed.revision(), model.key(), () -> {})
                .contains(owner.getId());
    }

    private Proposal proposal(
            TemplateFieldPlanService.Plan plan,
            SiteModelPlanService.Plan confirmed,
            OneshotEntity owner,
            SiteModelPlanService.Model model,
            SiteModelPlanService.Model ownerModel,
            SiteModelPlanService.RegionUse use,
            SiteModelEvidenceService.PageEvidence page,
            Document document) {
        var maintenance = use.maintenance();
        boolean pageList = "PAGE_LIST".equals(maintenance.mode());
        Long targetId = model.menuId();
        var targetIds = List.<Integer>of();
        String regionSelector = null;
        List<String> rows = List.of();
        try {
            if ("OWNED".equals(maintenance.mode()) && structures != null) {
                var scope =
                        new SiteModelStructureService.Scope(
                                plan.siteId(),
                                plan.code(),
                                plan.packageVersion(),
                                owner.getSiteRootId());
                Integer selected =
                        structures.ownedMenuId(
                                scope,
                                confirmed.id(),
                                confirmed.revision(),
                                model.key(),
                                owner.getId(),
                                () -> {});
                targetId = selected == null ? null : selected.longValue();
            }
            if (targetId == null) throw new IllegalArgumentException("请先应用集合目标栏目");
            var target = menus.selectPublicBindingMenu(plan.siteId(), targetId);
            if (target == null
                    || (pageList || "OWNED".equals(maintenance.mode()))
                            && !targetMenu(plan.siteId(), owner, target, maintenance.mode()))
                throw new IllegalArgumentException("目标栏目未启用或集合归属已变化");
            targetIds = List.of(Math.toIntExact(targetId));
            String reference = null;
            if ("REFERENCE".equals(maintenance.mode())) {
                reference =
                        ownerModel.fields().stream()
                                .filter(f -> Objects.equals(f.key(), maintenance.fieldKey()))
                                .map(SiteModelPlanService.Field::fieldName)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("请先应用有序内容引用字段"));
                var field = referenceField(owner, reference);
                if (field == null) throw new IllegalArgumentException("有序引用字段配置已变化");
                var configured = ContentReferences.menuIds(field);
                if (structures != null) {
                    var scope =
                            new SiteModelStructureService.Scope(
                                    plan.siteId(),
                                    plan.code(),
                                    plan.packageVersion(),
                                    owner.getSiteRootId());
                    targetIds =
                            structures.referenceTargetIds(
                                    scope,
                                    confirmed.id(),
                                    maintenance.ownerModelKey(),
                                    maintenance.fieldKey(),
                                    reference,
                                    owner.getId());
                } else if (!targetMenu(plan.siteId(), owner, target, maintenance.mode())) {
                    throw new IllegalArgumentException("目标栏目未启用或集合归属已变化");
                }
                if (!configured.contains(Math.toIntExact(targetId))
                        || !targetIds.containsAll(configured))
                    throw new IllegalArgumentException("有序引用字段超出已审核且字段兼容的目标范围");
                targetIds = configured;
            }
            var region =
                    page.regions().stream()
                            .filter(r -> use.regionId().equals(r.id()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("重复区域证据已变化"));
            regionSelector = region.selector();
            var parent = element(document, region.selector());
            var records = region.records();
            rows = records.stream().map(SiteModelEvidenceService.SourceRecord::selector).toList();
            if (records.isEmpty()
                    || records.size() != use.records().size()
                    || parent.childrenSize() != records.size()
                    || parent.closest(EXCLUDED) != null
                    || SiteModelEvidenceService.publicRegion(page.sourcePath(), region.id()))
                throw new IllegalArgumentException("集合须有完整的主体重复条目证据");
            String emptySelector = pageList ? region.selector() : selector(emptyContainer(parent));
            if ("REFERENCE".equals(maintenance.mode()) && emptySelector.equals(region.selector()))
                throw new IllegalArgumentException("未证实可整体隐藏的引用小节，请审核区域边界");
            var slots = new ArrayList<Slot>();
            List<Field> fields = null;
            String link = null;
            var ordinals = pageList ? ordinalPositions(parent) : List.<Element>of();
            for (int i = 0; i < records.size(); i++) {
                var record = records.get(i);
                var recordUse = use.records().get(i);
                var row = element(document, record.selector());
                var ordinal = ordinals.isEmpty() ? null : ordinals.get(i);
                var unsupportedMedia = pageList
                        ? "video,audio,picture,source,iframe,object,embed,img[srcset],img[data-src],img[data-srcset]"
                        : UNBOUND_MEDIA;
                if (!record.id().equals(recordUse.recordId())
                        || !row.equals(parent.child(i))
                        || !row.select("[id]").isEmpty()
                        || row.hasAttr("id")
                        || !row.select(unsupportedMedia).isEmpty()
                        || row.is(unsupportedMedia)
                        || row.getAllElements().stream().anyMatch(e -> e.is(EXCLUDED)))
                    throw new IllegalArgumentException("集合条目顺序或位置已变化");
                var rowFields = new ArrayList<Field>();
                var targets = new ArrayList<Element>();
                for (var planned : model.fields()) {
                    String id = recordUse.bindings().get(planned.key());
                    if (id == null) continue;
                    var value =
                            record.values().stream()
                                    .filter(v -> id.equals(v.id()))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("已审核字段原值不存在"));
                    // 链接位置由唯一包裹已审核文本的锚点确认，原始href不参与记录身份。
                    if ("href".equals(value.attribute()) && "REFERENCE".equals(maintenance.mode()))
                        continue;
                    var position = element(document, value.selector());
                    var field = new Field(planned.fieldName(), planned.name(), planned.type());
                    boolean validPosition = "Image".equals(field.type())
                            ? pageList && "src".equals(value.attribute())
                                    && position.is("img[src]")
                                    && position.attr("src").equals(value.rawValue())
                            : "text".equals(value.attribute())
                                    && position.childrenSize() == 0
                                    && position.wholeText().strip().equals(value.rawValue());
                    if (!displayField(target, field) || !validPosition
                            || !position.parents().contains(row) && position != row
                            || targets.contains(position))
                        throw new IllegalArgumentException("条目字段须对应已审核的独立文本或单一图片位置");
                    if (pageList && i > 0 && (rowFields.size() >= slots.size()
                            || !relativePosition(row, position).equals(relativePosition(
                                    element(document, rows.getFirst()),
                                    element(document, slots.get(rowFields.size()).selector())))))
                        throw new IllegalArgumentException("各条目字段位置须与首条结构一致");
                    rowFields.add(field);
                    targets.add(position);
                    if (i == 0) slots.add(new Slot(value.selector(), field, value.rawValue()));
                }
                if (rowFields.isEmpty()
                        || fields != null && !fields.equals(rowFields)
                        || row.getAllElements().stream()
                                .anyMatch(e -> (!e.ownText().isBlank() || e.is("img"))
                                        && e != ordinal && !targets.contains(e)))
                    throw new IllegalArgumentException("集合各条目须有一致的字段映射及完整文本证据");
                fields = rowFields;
                if ("REFERENCE".equals(maintenance.mode())) {
                    var anchors = row.select("a[href]");
                    if (anchors.size() != 1
                            || targets.stream()
                                    .noneMatch(
                                            p ->
                                                    p == anchors.first()
                                                            || p.parents()
                                                                    .contains(anchors.first())))
                        throw new IllegalArgumentException("引用卡片缺少唯一的已审核文本链接");
                    if (i == 0) link = selector(anchors.first());
                } else if (!row.select("a[href]").isEmpty()) {
                    throw new IllegalArgumentException("附属条目中的实质链接需单独审核");
                }
            }
            return new Proposal(
                    new Candidate(
                            use.regionId(),
                            maintenance.mode(),
                            maintenance.reason(),
                            targetId,
                            model.name(),
                            rows.size(),
                            fields,
                            null,
                            targetIds, regionSelector, rows),
                    new Binding(
                            use.regionId(),
                            maintenance.mode(),
                            targetId,
                            reference,
                            region.selector(),
                            emptySelector,
                            rows,
                            slots,
                            link,
                            targetIds,
                            reference == null ? null : confirmed.id(),
                            plan.packageVersion(),
                            maintenance.ownerModelKey(),
                            maintenance.fieldKey()));
        } catch (IllegalArgumentException
                | org.springframework.web.server.ResponseStatusException error) {
            return new Proposal(
                    new Candidate(
                            use.regionId(),
                            maintenance.mode(),
                            maintenance.reason(),
                            targetId,
                            model.name(),
                            use.records().size(),
                            List.of(),
                            error
                                            instanceof
                                            org.springframework.web.server.ResponseStatusException
                                                    status
                                    ? status.getReason()
                                    : error.getMessage(),
                            targetIds, regionSelector, rows),
                    null);
        }
    }

    static String compile(String html, List<Binding> bindings) {
        return instructions(html, bindings, false);
    }

    static String withoutInstructions(String html, List<Binding> bindings) {
        return instructions(html, bindings, true);
    }

    private static String instructions(String html, List<Binding> bindings, boolean remove) {
        if (bindings.isEmpty()) return html;
        var document = parse(html);
        for (var binding : bindings) {
            if (!binding.regionId.matches("[a-f0-9]{64}") || binding.rowSelectors.isEmpty())
                throw new IllegalArgumentException("详情集合凭据无效");
            if ("INLINE_REFERENCE".equals(binding.mode)) {
                var anchor=element(document,binding.linkSelector);
                if (!anchor.is("a[href]") || anchor.childrenSize()!=0 || anchor.closest(EXCLUDED)!=null
                        || binding.slots.size()!=1 || !anchor.text().equals(binding.slots.getFirst().originalText)
                        || !remove && hasInstructions(anchor)) throw new IllegalArgumentException("原页推荐链接已变化或已由其他字段绑定");
                edit(anchor,"each","cmsBoundDetailRow : ${cmsBoundDetailCollections['"+binding.regionId+"']}",remove);
                edit(anchor,"text","${cmsBoundDetailRow['title']}",remove);
                edit(anchor,"href","${cmsBoundDetailRow['_url']}",remove);
                continue;
            }
            var wrapper = element(document, binding.regionSelector);
            var empty = element(document, binding.emptySelector);
            var row = element(document, binding.rowSelectors.getFirst());
            if (wrapper.childrenSize() != binding.rowSelectors.size()
                    || empty != wrapper
                            && (!wrapper.parents().contains(empty)
                                    || empty.is("main,body,html")
                                    || empty.closest(EXCLUDED) != null)
                    || !remove
                            && empty.getAllElements().stream()
                                    .anyMatch(DetailCollections::hasInstructions))
                throw new IllegalArgumentException("详情集合区域与既有绑定重叠");
            String list = "cmsBoundDetailCollections['" + binding.regionId + "']";
            edit(empty, "if", "${" + list + ".size() > 0}", remove);
            String each = "cmsBoundDetailRow : ${" + list + "}";
            var ordinals = "PAGE_LIST".equals(binding.mode) ? ordinalPositions(wrapper) : List.<Element>of();
            if (!ordinals.isEmpty()) {
                var ordinal = ordinals.getFirst();
                boolean mapped = binding.slots.stream()
                        .anyMatch(slot -> element(document, slot.selector) == ordinal);
                if (!mapped && !(remove && each.equals(row.attr("th:each")))) {
                    each = "cmsBoundDetailRow, cmsBoundDetailRowStatus : ${" + list + "}";
                    edit(ordinal, "text", "${#numbers.formatInteger(cmsBoundDetailRowStatus.count, 2)}", remove);
                }
            }
            edit(row, "each", each, remove);
            for (int index = 0; index < binding.rowSelectors.size(); index++) {
                var original = element(document, binding.rowSelectors.get(index));
                if (original != wrapper.child(index))
                    throw new IllegalArgumentException("详情集合条目位置已变化");
                if (index > 0) edit(original, "if", "${false}", remove);
            }
            for (var slot : binding.slots) {
                String fieldName = slot.field.fieldName();
                if (fieldName == null || !fieldName.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                    throw new IllegalArgumentException("详情集合字段名无效");
                var position = element(document, slot.selector);
                if (position != row && !position.parents().contains(row))
                    throw new IllegalArgumentException("详情集合位置已变化");
                if ("Image".equals(slot.field.type())) {
                    if (!"PAGE_LIST".equals(binding.mode) || !position.is("img[src]"))
                        throw new IllegalArgumentException("集合图片位置已变化");
                    edit(position, "src", "${cmsBoundDetailRow['" + fieldName + "']}", remove);
                    continue;
                }
                if (!position.wholeText().strip().equals(slot.originalText)
                        || position.childrenSize() != 0)
                    throw new IllegalArgumentException("详情集合文本位置已变化或与其他绑定重叠");
                if ("MultiLine".equals(slot.field.type())) {
                    String style = position.attr("style");
                    if (remove && !style.endsWith(";white-space:pre-wrap"))
                        throw new IllegalArgumentException("详情集合多行样式已变化");
                    if (remove) {
                        String previous =
                                style.substring(
                                        0, style.length() - ";white-space:pre-wrap".length());
                        if (previous.isEmpty()) position.removeAttr("style");
                        else position.attr("style", previous);
                    } else {
                        if (position.hasAttr("style") && style.isEmpty())
                            throw new IllegalArgumentException("详情集合多行文本已有空样式属性");
                        position.attr("style", style + ";white-space:pre-wrap");
                    }
                }
                edit(position, "text", "${cmsBoundDetailRow['" + fieldName + "']}", remove);
            }
            if (binding.linkSelector != null) {
                var anchor = element(document, binding.linkSelector);
                if (!anchor.is("a[href]") || anchor != row && !anchor.parents().contains(row))
                    throw new IllegalArgumentException("详情集合链接位置已变化");
                edit(anchor, "href", "${cmsBoundDetailRow['_url']}", remove);
            }
        }
        return document.outerHtml();
    }

    private static void edit(Element node, String attribute, String value, boolean remove) {
        String name = "th:" + attribute;
        if (node.hasAttr("data-th-" + attribute)
                || (remove ? !value.equals(node.attr(name)) : node.hasAttr(name)))
            throw new IllegalArgumentException("详情集合与其他绑定冲突或指令不匹配");
        if (remove) node.removeAttr(name);
        else node.attr(name, value);
    }

    private static boolean hasInstructions(Element element) {
        return element.attributes().asList().stream()
                .map(org.jsoup.nodes.Attribute::getKey)
                .anyMatch(name -> name.startsWith("th:") || name.startsWith("data-th-"));
    }

    private static List<Element> ordinalPositions(Element wrapper) {
        if (wrapper.childrenSize() < 2) return List.of();
        var first = wrapper.child(0);
        List<Element> ordinals = List.of();
        for (var candidate : first.select("dl > dt")) {
            if (candidate.childrenSize() != 0 || !"01".equals(candidate.wholeText().strip())) continue;
            var path = relativePosition(first, candidate);
            var sequence = new ArrayList<Element>();
            for (var row : wrapper.children()) {
                Element position = row;
                for (int index : path) {
                    position = index < position.childrenSize() ? position.child(index) : null;
                    if (position == null) break;
                }
                if (position == null || !position.is("dl > dt") || position.childrenSize() != 0
                        || !candidate.classNames().equals(position.classNames())
                        || !String.format(Locale.ROOT, "%02d", sequence.size() + 1)
                                .equals(position.wholeText().strip())) break;
                sequence.add(position);
            }
            if (sequence.size() == wrapper.childrenSize()) {
                if (!ordinals.isEmpty()) return List.of();
                ordinals = sequence;
            }
        }
        return ordinals;
    }

    private static Element emptyContainer(Element region) {
        for (var ancestor : region.parents()) {
            if (ancestor.is("main,body,html") || ancestor.closest(EXCLUDED) != null) break;
            var headings =
                    ancestor.select("h1,h2,h3,h4,h5,h6").stream()
                            .filter(h -> !h.parents().contains(region))
                            .toList();
            if (headings.size() > 1) break;
            if (headings.size() == 1) {
                var heading = headings.getFirst();
                boolean clean =
                        ancestor.getAllElements().stream()
                                .filter(e -> e != region && !e.parents().contains(region))
                                .noneMatch(
                                        e ->
                                                e.is(
                                                                EXCLUDED
                                                                        + ",a,button,input,ol,ul,table,"
                                                                        + UNBOUND_MEDIA)
                                                        || e != heading
                                                                && !e.parents().contains(heading)
                                                                && !e.ownText().isBlank());
                if (clean) return ancestor;
                break;
            }
        }
        return region;
    }

    void contribute(
            Long siteId,
            String code,
            OneshotEntity owner,
            ContentEntity article,
            List<Binding> bindings,
            Context context) {
        Map<String, List<Map<String, String>>> projected = new LinkedHashMap<>();
        for (var binding : bindings)
            projected.put(binding.regionId, rows(siteId, code, owner, article, binding));
        context.setVariable("cmsBoundDetailCollections", Map.copyOf(projected));
    }

    private List<Map<String, String>> rows(
            Long siteId, String code, OneshotEntity owner, ContentEntity article, Binding binding) {
        List<ContentEntity> records;
        boolean owned = "OWNED".equals(binding.mode);
        boolean pageList = "PAGE_LIST".equals(binding.mode);
        Map<Integer, OneshotEntity> targets = new HashMap<>();
        if (pageList) {
            var target = menus.selectPublicBindingMenu(siteId, binding.targetMenuId);
            if (!targetMenu(siteId, owner, target, binding.mode)
                    || binding.slots.stream().anyMatch(slot -> !displayField(target, slot.field)))
                return List.of();
            targets.put(target.getId(), target);
            var identity = new IdEntity();
            identity.bindServerSite(siteId);
            records = info.getStructuredPublishedPage(identity, target.getId(), 1, 100,
                    Map.of(), List.of(), List.of()).list();
        } else if (owned) {
            var target = menus.selectPublicBindingMenu(siteId, binding.targetMenuId);
            if (!targetMenu(siteId, owner, target, binding.mode)
                    || binding.slots.stream().anyMatch(slot -> !displayField(target, slot.field)))
                return List.of();
            targets.put(target.getId(), target);
            records =
                    info.getPublishedSummariesByRelations(
                            siteId, List.of(target.getId()), List.of(article.getId()));
        } else {
            var ref = referenceField(owner, binding.referenceField);
            if (ref == null) return List.of();
            var configured = ContentReferences.menuIds(ref);
            var bound =
                    binding.targetMenuIds == null || binding.targetMenuIds.isEmpty()
                            ? List.of(Math.toIntExact(binding.targetMenuId))
                            : binding.targetMenuIds;
            var allowed = bound;
            if (binding.sitePlanId != null && structures != null) {
                try {
                    var scope =
                            new SiteModelStructureService.Scope(
                                    siteId, code, binding.packageVersion, owner.getSiteRootId());
                    allowed =
                            structures.referenceTargetIds(
                                    scope,
                                    binding.sitePlanId,
                                    binding.ownerModelKey,
                                    binding.referenceFieldKey,
                                    binding.referenceField,
                                    owner.getId());
                } catch (IllegalArgumentException
                        | org.springframework.web.server.ResponseStatusException error) {
                    return List.of();
                }
            }
            for (Integer menuId : configured) {
                if (!bound.contains(menuId) || !allowed.contains(menuId)) continue;
                var target = menus.selectPublicBindingMenu(siteId, menuId.longValue());
                if (targetMenu(siteId, owner, target, binding.mode)
                        && binding.slots.stream()
                                .allMatch(slot -> displayField(target, slot.field)))
                    targets.put(menuId, target);
            }
            if (targets.isEmpty()) return List.of();
            var published =
                    references
                            .published(
                                    siteId,
                                    ref,
                                    (article == null ? owner.getExtensionValues() : article.getExtensionValues()).get(binding.referenceField))
                            .stream()
                            .filter(row -> targets.containsKey(row.getClassid()))
                            .filter(row -> article == null || !Objects.equals(row.getId(), article.getId()))
                            .toList();
            if (published.isEmpty()) return List.of();
            var ids = published.stream().map(ContentEntity::getId).toList();
            var summary =
                    info.getPublishedSummariesByIds(siteId, ids).stream()
                            .collect(Collectors.toMap(ContentEntity::getId, Function.identity()));
            records =
                    published.stream()
                            .map(
                                    row -> {
                                        var current = summary.get(row.getId());
                                        return current != null
                                                        && Objects.equals(
                                                                current.getClassid(),
                                                                row.getClassid())
                                                ? current
                                                : null;
                                    })
                            .filter(Objects::nonNull)
                            .toList();
        }
        var result = new ArrayList<Map<String, String>>();
        for (var record : records) {
            var target = targets.get(record.getClassid());
            if (target == null
                    || !pageList && !Objects.equals(record.getSiteProjectId(), siteId)
                    || !Integer.valueOf(1).equals(record.getStatus())
                    || article != null && Objects.equals(record.getId(), article.getId())
                    || owned && !Objects.equals(record.getXgid(), article.getId())) continue;
            var row = new LinkedHashMap<String, String>();
            if (!owned && !pageList) {
                String url = detailLink(siteId, code, target, record.getId());
                if (url == null) continue;
                row.put("_url", url);
            }
            for (var slot : binding.slots) {
                String key = slot.field.fieldName;
                String value = Objects.toString(BoundTemplateModelContributor.value(record, key), "");
                if ("Image".equals(slot.field.type()))
                    value = resources.prepareTemplateMedia(field(target, key, "Image"), value, siteId);
                row.put(key, value);
            }
            result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    private static ContentFieldDefinition referenceField(OneshotEntity owner, String name) {
        var field = field(owner, name, "SingleLine");
        return ContentReferences.applies(field) && Boolean.TRUE.equals(field.getInputMultiple())
                ? field
                : null;
    }

    private String detailLink(Long siteId, String code, OneshotEntity target, Integer recordId) {
        var route = target.getDetailPageUrl();
        var template = target.getDetailPageTemplate();
        if (recordId == null
                || recordId <= 0
                || route == null
                || !route.contains("{id}")
                || !localPath(
                        route.replace("{id}", "1")
                                .replace("{classId}", "1")
                                .replace("{parentId}", "1")
                                .replace("${className}", "name"))
                || !localPath(template)
                || template.startsWith("/")) return null;
        var directory = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
        var file = directory.resolve(template).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return null;
        for (var probe = file;
                probe != null && probe.startsWith(directory);
                probe = probe.getParent()) if (Files.isSymbolicLink(probe)) return null;
        var resolved =
                new OneshotEntity()
                        .setId(target.getId())
                        .setParentId(target.getParentId())
                        .setDetailPageUrl(
                                route.replace(
                                        "${className}",
                                        Objects.toString(target.getClassName(), "")));
        String path = links.getDetailUrl(new ContentEntity().setId(recordId), resolved);
        if (!localPath(path)) return null;
        String normalized = path.replaceAll("^/|/$", "");
        if (normalized.isEmpty()
                || !routes.hasPublicDetailRoute(siteId, code, target, recordId, normalized))
            return null;
        return org.springframework.web.util.UriComponentsBuilder.fromPath("/" + normalized)
                .build()
                .encode()
                .toUriString();
    }

    private static boolean localPath(String path) {
        return path != null
                && !path.isBlank()
                && path.length() <= 512
                && !path.startsWith("//")
                && !path.matches("(?s).*[\\x00-\\x1f\\x7f:{}?%#\\\\].*")
                && Arrays.stream(path.split("/"))
                        .noneMatch(part -> part.equals(".") || part.equals(".."));
    }

    private static ContentFieldDefinition field(OneshotEntity menu, String name, String type) {
        if (name == null) return null;
        return ContentModelCodec.read(Integer.valueOf(0).equals(menu.getPageType()) ? menu.getMenuField() : menu.getModelField()).stream()
                .filter(
                        f ->
                                name.equals(f.getFieldName())
                                        && type.equals(f.getFieldType())
                                        && ContentFieldDefinition.enabled(f.getIsOpen()))
                .findFirst()
                .orElse(null);
    }

    private static boolean displayField(OneshotEntity menu, Field slot) {
        if (!Set.of("SingleLine", "MultiLine", "Image").contains(slot.type())) return false;
        var definition = field(menu, slot.fieldName(), slot.type());
        return definition != null && !"content".equals(definition.getInputType());
    }

    private static boolean targetMenu(
            Long siteId, OneshotEntity owner, OneshotEntity target, String mode) {
        if (target == null
                || !Objects.equals(target.getSiteProjectId(), siteId)
                || !Objects.equals(target.getSiteRootId(), owner.getSiteRootId())
                || !Integer.valueOf(1).equals(target.getPageType())
                || Boolean.TRUE.equals(target.getIsDeleted())) return false;
        return switch (mode) {
            case "OWNED" ->
                    Boolean.TRUE.equals(target.getIsSubMenu())
                            && Boolean.TRUE.equals(target.getHasManageChannel())
                            && Objects.equals(target.getParentId(), owner.getId());
            case "PAGE_LIST" ->
                    Integer.valueOf(0).equals(owner.getPageType())
                            && !Boolean.TRUE.equals(target.getIsSubMenu())
                            && Boolean.TRUE.equals(target.getHasManageChannel())
                            && Boolean.TRUE.equals(target.getHasFrontChannel())
                            && Objects.equals(target.getParentId(), owner.getId());
            case "REFERENCE", "INLINE_REFERENCE" ->
                    !Boolean.TRUE.equals(target.getIsSubMenu())
                            && Boolean.TRUE.equals(target.getHasFrontChannel());
            default -> false;
        };
    }

    private static Document parse(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        return document;
    }

    private static Element element(Document document, String selector) {
        var matches = document.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("详情集合定位已变化");
        return matches.first();
    }

    private static String selector(Element element) {
        var parts = new ArrayDeque<String>();
        for (var node = element;
                node != null && !node.normalName().equals("body");
                node = node.parent())
            parts.addFirst(":nth-child(" + (node.elementSiblingIndex() + 1) + ")");
        return "body > " + String.join(" > ", parts);
    }

    private static List<Integer> relativePosition(Element row, Element position) {
        var indices = new ArrayDeque<Integer>();
        for (var element = position; element != row; element = element.parent())
            indices.addFirst(element.elementSiblingIndex());
        return List.copyOf(indices);
    }
}

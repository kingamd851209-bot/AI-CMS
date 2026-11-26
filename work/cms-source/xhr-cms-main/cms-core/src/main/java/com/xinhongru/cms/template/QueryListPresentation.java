package com.xinhongru.cms.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** 列表显示与初始化分开：每个业务来源必须映射或明确声明为共用固定装饰。 */
@Service
public class QueryListPresentation {
    private static final String SHARED_REGION =
            "header,footer,nav,.header,.footer,.nav,#header,#footer,.site-header,.site-footer,"
                    + ".site_header,.site_footer,.site-nav,.site_nav,[role=banner],[role=contentinfo],[role=navigation]";
    private static final Pattern STATIC_SEARCH_BUTTON = Pattern.compile(
            "^\\s*(?:window\\.)?location\\.href\\s*=\\s*(['\"])(?!//)[A-Za-z0-9_./%-]+\\1\\s*;?\\s*$");
    private static final Pattern SEARCH_HINT = Pattern.compile(
            "搜索|检索|关键词|关键字|\\b(search|keyword|query)\\b", Pattern.CASE_INSENSITIVE);
    static final String SHARED_SEARCH_NOTICE =
            "整页无筛选分页时，页头未接线的原脚本检索输入和按钮将停用，页头布局保留。";
    private final TemplateListPairingService pairing;
    private final TemplateSeedService seeds;
    private final OneShotMapper menus;
    private final TemplateFieldPlanService plans;
    private final com.xinhongru.cms.uipackage.UiPackageStorageService packages;

    public QueryListPresentation(
            TemplateListPairingService pairing,
            TemplateSeedService seeds,
            OneShotMapper menus,
            TemplateFieldPlanService plans,
            com.xinhongru.cms.uipackage.UiPackageStorageService packages) {
        this.pairing = pairing;
        this.seeds = seeds;
        this.menus = menus;
        this.plans = plans;
        this.packages = packages;
    }

    private NativeFormImportService nativeForms;
    private com.xinhongru.cms.formservice.FormSiteApiService publicForms;
    @org.springframework.beans.factory.annotation.Autowired
    private SiteModelNavigationService navigation;

    @org.springframework.beans.factory.annotation.Autowired
    public void setFormActions(
            NativeFormImportService nativeForms,
            com.xinhongru.cms.formservice.FormSiteApiService publicForms) {
        this.nativeForms = nativeForms;
        this.publicForms = publicForms;
    }

    public record DisplayBinding(String sourceId, String fieldName) {}

    public record Counter(String selector, int start, int end) {}

    public record Pagination(
            String containerSelector,
            String numberSelector,
            String activeClass,
            String disabledClass,
            Map<String, String> links,
            Map<String, Counter> counters,
            Map<String, String> ignoredControls) {}

    public record Request(
            TemplateListPairingService.Request pairing,
            String cardId,
            List<DisplayBinding> bindings,
            Map<String, String> sharedStatic,
            boolean replaceGroupConfirmed,
            Pagination pagination,
            List<FormActionBinding.Selection> actions,
            @JsonInclude(JsonInclude.Include.NON_NULL) CategorySidebar categorySidebar,
            @JsonInclude(JsonInclude.Include.NON_NULL) DeferredRegion deferredRegion,
            @JsonInclude(JsonInclude.Include.NON_NULL) QueryTimeline.Selection timeline,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean unpaged,
            @JsonInclude(JsonInclude.Include.NON_NULL) String fixedHeadingPlanId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean carouselCounter,
            @JsonInclude(JsonInclude.Include.NON_NULL) FeaturedCarousel featuredCarousel) {
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar,
                DeferredRegion deferredRegion, QueryTimeline.Selection timeline, Boolean unpaged,
                String fixedHeadingPlanId, Boolean carouselCounter) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, deferredRegion, timeline, unpaged, fixedHeadingPlanId, carouselCounter, null);
        }

        public Request withoutFeaturedCarousel() {
            return new Request(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination,
                    actions, categorySidebar, deferredRegion, timeline, unpaged, fixedHeadingPlanId, carouselCounter);
        }
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar,
                DeferredRegion deferredRegion, QueryTimeline.Selection timeline, Boolean unpaged,
                String fixedHeadingPlanId) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, deferredRegion, timeline, unpaged, fixedHeadingPlanId, null);
        }
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar,
                DeferredRegion deferredRegion, QueryTimeline.Selection timeline, Boolean unpaged) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, deferredRegion, timeline, unpaged, null);
        }
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar,
                DeferredRegion deferredRegion, QueryTimeline.Selection timeline) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, deferredRegion, timeline, null);
        }

        public boolean fixedList() {
            return Boolean.TRUE.equals(unpaged);
        }
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar,
                DeferredRegion deferredRegion) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, deferredRegion, null);
        }
        public Request(
                TemplateListPairingService.Request pairing, String cardId, List<DisplayBinding> bindings,
                Map<String, String> sharedStatic, boolean replaceGroupConfirmed, Pagination pagination,
                List<FormActionBinding.Selection> actions, CategorySidebar categorySidebar) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions,
                    categorySidebar, null);
        }

        public Request(
                TemplateListPairingService.Request pairing,
                String cardId,
                List<DisplayBinding> bindings,
                Map<String, String> sharedStatic,
                boolean replaceGroupConfirmed,
                Pagination pagination,
                List<FormActionBinding.Selection> actions) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, actions, null);
        }

        public Request(
                TemplateListPairingService.Request pairing,
                String cardId,
                List<DisplayBinding> bindings,
                Map<String, String> sharedStatic,
                boolean replaceGroupConfirmed,
                Pagination pagination) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, pagination, null);
        }

        public Request(
                TemplateListPairingService.Request pairing,
                String cardId,
                List<DisplayBinding> bindings,
                Map<String, String> sharedStatic,
                boolean replaceGroupConfirmed) {
            this(pairing, cardId, bindings, sharedStatic, replaceGroupConfirmed, null);
        }
    }

    public record CategorySidebar(String selector, String navigationPlanId) {}

    public record DeferredRegion(String selector, String reason) {}

    public record FeaturedCarousel(String selector, String imageField, String titleField, String summaryField) {}

    public record ConfirmedDeferredRegion(String selector, String sourceDigest, String regionDigest, String reason) {}

    public record CategoryEntry(String name, Integer menuId) {}

    public record ConfirmedCategorySidebar(
            String selector, Integer currentMenuId, long navigationRevision, List<CategoryEntry> entries) {}

    public record Field(String fieldName, String name, String fieldType) {}

    public record Evidence(
            String sourcePath,
            String digest,
            String containerSelector,
            String cardSelector,
            int originalCards,
            List<TemplateContentUnitsService.Source> sources,
            List<Field> fields,
            List<String> warnings,
            String detailSourcePath,
            List<String> detailLinkSourceIds,
            List<FormActionBinding.Candidate> actions,
            List<FormActionBinding.TargetMenu> targetMenus,
            @JsonInclude(JsonInclude.Include.NON_NULL) QueryCarouselCounter carouselCounter,
            @JsonInclude(JsonInclude.Include.NON_NULL) Pagination paginationDraft) {}

    public record Plan(
            Request request,
            String containerSelector,
            String cardSelector,
            int originalCards,
            List<TemplateContentUnitsService.Source> sources,
            List<Field> fields,
            List<String> originalCardSelectors,
            List<FormActionBinding.Bound> actions,
            @JsonInclude(JsonInclude.Include.NON_NULL) ConfirmedCategorySidebar categorySidebar,
            @JsonInclude(JsonInclude.Include.NON_NULL) ConfirmedDeferredRegion deferredRegion,
            @JsonInclude(JsonInclude.Include.NON_NULL) QueryCarouselCounter carouselCounter) {
        public Plan(
                Request request, String containerSelector, String cardSelector, int originalCards,
                List<TemplateContentUnitsService.Source> sources, List<Field> fields,
                List<String> originalCardSelectors, List<FormActionBinding.Bound> actions,
                ConfirmedCategorySidebar categorySidebar, ConfirmedDeferredRegion deferredRegion) {
            this(request, containerSelector, cardSelector, originalCards, sources, fields,
                    originalCardSelectors, actions, categorySidebar, deferredRegion, null);
        }
        public Plan(
                Request request, String containerSelector, String cardSelector, int originalCards,
                List<TemplateContentUnitsService.Source> sources, List<Field> fields,
                List<String> originalCardSelectors, List<FormActionBinding.Bound> actions,
                ConfirmedCategorySidebar categorySidebar) {
            this(request, containerSelector, cardSelector, originalCards, sources, fields,
                    originalCardSelectors, actions, categorySidebar, null);
        }

        public Plan(
                Request request,
                String containerSelector,
                String cardSelector,
                int originalCards,
                List<TemplateContentUnitsService.Source> sources,
                List<Field> fields,
                List<String> originalCardSelectors,
                List<FormActionBinding.Bound> actions) {
            this(request, containerSelector, cardSelector, originalCards, sources, fields,
                    originalCardSelectors, actions, null);
        }

        public Plan(
                Request request,
                String containerSelector,
                String cardSelector,
                int originalCards,
                List<TemplateContentUnitsService.Source> sources,
                List<Field> fields,
                List<String> originalCardSelectors) {
            this(request, containerSelector, cardSelector, originalCards, sources, fields,
                    originalCardSelectors, null);
        }
    }

    public Evidence inspect(
            Long siteId,
            String code,
            String version,
            String sourcePlanId,
            TemplateListPairingService.Request request,
            String cardId) {
        return inspect(siteId, code, version, sourcePlanId, request, cardId, false, Set.of());
    }

    private Evidence inspect(Long siteId, String code, String version, String sourcePlanId,
            TemplateListPairingService.Request request, String cardId, boolean saved, Set<String> previousSources) {
        if (request == null || cardId == null || cardId.length() > 64)
            throw new IllegalArgumentException("请选择原列表卡片和内容计划");
        var origin = displaySource(siteId, code, version, sourcePlanId, saved);
        if (!"CONFIRMED".equals(origin.status())
                || !Objects.equals(origin.menuId(), request.menuId()))
            throw new IllegalArgumentException("内容计划尚未确认或不属于当前栏目");
        var inspection = inspectPairing(siteId, code, version, request, origin);
        if (inspection.totalCards() != inspection.inspectedCards())
            throw new IllegalArgumentException("静态卡片检查范围不足，不能确认整组替换");
        var item =
                inspection.items().stream()
                        .filter(row -> row.id().equals(cardId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("卡片不在当前检查范围"));
        if (!"MATCHED".equals(item.status())
                || item.detail() == null
                || !item.detail().sourcePath().equals(origin.sourcePath())
                || !item.detail().digest().equals(origin.digest())
                || !item.list().evidenceComplete())
            throw new IllegalArgumentException("模板卡片未唯一对应此详情或来源不完整");
        var current = seeds.preview(code, version, inspection.listSourcePath());
        if (!current.digest().equals(inspection.listDigest()))
            throw new IllegalArgumentException("列表来源在取证时变化");
        var ids =
                item.list().evidence().stream()
                        .map(TemplateListPairingService.Evidence::sourceId)
                        .collect(java.util.stream.Collectors.toSet());
        var sources =
                new ArrayList<TemplateContentUnitsService.Source>(
                        current.items().stream()
                                .filter(source -> ids.contains(source.id()))
                                .map(
                                        source ->
                                                new TemplateContentUnitsService.Source(
                                                        source.id(),
                                                        source.kind(),
                                                        source.value(),
                                                        source.selector(),
                                                        source.context(),
                                                        source.repeatGroup(),
                                                        source.scriptInput()))
                                .toList());
        var page = packages.readStaticPage(code, version, inspection.listSourcePath());
        if (!page.digest().equals(current.digest()))
            throw new IllegalArgumentException("列表来源在属性取证时变化");
        var document = org.jsoup.Jsoup.parse(page.html());
        var selectedCard = document.select(item.selector());
        if (selectedCard.size() != 1) throw new IllegalArgumentException("卡片属性定位变化");
        removeWrappedTextDuplicates(selectedCard.first(), sources, previousSources);
        for (var element : selectedCard.first().getAllElements()) {
            for (String attribute : List.of("alt", "title")) {
                if (!element.hasAttr(attribute) || element.attr(attribute).isBlank()) continue;
                if (attribute.equals("alt") && !element.tagName().equals("img")) continue;
                String selector = element.cssSelector();
                String id =
                        "attribute-"
                                + java.util.UUID.nameUUIDFromBytes(
                                        (current.digest() + "\n" + selector + "\n" + attribute)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sources.add(
                        new TemplateContentUnitsService.Source(
                                id,
                                "ATTRIBUTE_" + attribute.toUpperCase(Locale.ROOT),
                                element.attr(attribute),
                                selector,
                                "卡片" + attribute + "辅助文字",
                                null));
            }
        }
        var menu = menus.selectActiveMenuModelBySite(siteId, request.menuId().intValue());
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("目标栏目不可用");
        var fields =
                ContentModelCodec.read(menu.getModelField()).stream()
                        .filter(QueryListPresentation::readable)
                        .map(
                                field ->
                                        new Field(
                                                field.getFieldName(),
                                                field.getTitle(),
                                                field.getFieldType()))
                        .toList();
        var detailLinkSourceIds =
                sources.stream()
                        .filter(source -> "LINK".equals(source.kind()))
                        .filter(
                                source ->
                                        origin.sourcePath()
                                                .equals(
                                                        TemplateListPairingService.staticLinkPath(
                                                                inspection.listSourcePath(),
                                                                source.rawValue())))
                        .map(TemplateContentUnitsService.Source::id)
                        .toList();
        if (detailLinkSourceIds.isEmpty()) throw new IllegalArgumentException("已确认详情链接缺少完整来源证据");
        var actions = sources.stream()
                .filter(source -> "LINK".equals(source.kind()) && !detailLinkSourceIds.contains(source.id()))
                .flatMap(source -> FormActionBinding.inspect(
                        packages, code, version, inspection.listSourcePath(), document, source).stream())
                .toList();
        var targetMenus = actions.isEmpty() ? List.<FormActionBinding.TargetMenu>of()
                : menus.selectActiveSiteModelMenus(siteId).stream()
                        .filter(target -> FormActionBinding.single(target, siteId))
                        .map(target -> new FormActionBinding.TargetMenu(target.getId(), target.getClassName()))
                        .toList();
        var originalCards = new ArrayList<Element>();
        for (var original : inspection.items()) {
            var matches = document.select(original.selector());
            if (matches.size() != 1) throw new IllegalArgumentException("原卡片位置已变化");
            originalCards.add(matches.first());
        }
        var carouselCounter = QueryCarouselCounter.inspect(originalCards, selectedCard.first(), sources);
        return new Evidence(
                inspection.listSourcePath(),
                current.digest(),
                inspection.container().selector(),
                item.selector(),
                inspection.totalCards(),
                sources,
                fields,
                inspection.warnings(),
                origin.sourcePath(),
                detailLinkSourceIds, actions, targetMenus, carouselCounter,
                QueryPagerControls.suggest(document, originalCards));
    }

    private TemplateListPairingService.Inspection inspectPairing(
            Long siteId,
            String code,
            String version,
            TemplateListPairingService.Request request,
            TemplateFieldPlanService.Plan origin) {
        var inspection =
                independentDetail(origin)
                        ? pairing.inspectPresentation(
                                siteId, code, version, request, origin.sourcePath())
                        : pairing.inspect(siteId, code, version, request);
        if (!"CONFIRMED".equals(origin.status())
                || !Objects.equals(origin.menuId(), request.menuId())
                || !Objects.equals(origin.afterFieldConfigDigest(), inspection.fieldConfigDigest()))
            throw new IllegalArgumentException("详情字段配置已变化，请重新确认");
        return inspection;
    }

    static boolean independentDetail(TemplateFieldPlanService.Plan plan) {
        return plan.pageType() == 1 && (plan.origins() == null || plan.origins().isEmpty());
    }

    // 一个标题只有一层文字链接时，父标题的聚合 text 和链接文字是同一个可见位置。
    static void removeWrappedTextDuplicates(Element card, List<TemplateContentUnitsService.Source> sources, Set<String> previousSources) {
        var texts = new java.util.HashMap<String, String>();
        for (var source : sources) {
            if ("TEXT".equals(source.kind())) texts.put(source.selector(), source.rawValue());
        }
        var duplicates = new java.util.HashSet<String>();
        for (var wrapper : card.getAllElements()) {
            if (wrapper.childrenSize() != 1 || !wrapper.ownText().isBlank()) continue;
            var child = wrapper.child(0);
            String outer = texts.get(wrapper.cssSelector());
            if (outer != null && outer.equals(texts.get(child.cssSelector()))
                    && outer.equals(child.text())) duplicates.add(wrapper.cssSelector());
        }
        sources.removeIf(source -> "TEXT".equals(source.kind()) && duplicates.contains(source.selector())
                && !previousSources.contains(source.id()));
    }

    public Plan confirm(
            Long siteId, String code, String version, String sourcePlanId, Request request) {
        return confirm(siteId, code, version, sourcePlanId, request, null);
    }

    Plan confirm(
            Long siteId, String code, String version, String sourcePlanId, Request request, String querySelector) {
        return confirm(siteId, code, version, sourcePlanId, request, querySelector, false);
    }

    Plan confirm(Long siteId, String code, String version, String sourcePlanId, Request request,
            String querySelector, boolean saved) {
        return confirm(siteId, code, version, sourcePlanId, request, querySelector, saved, false);
    }

    Plan confirm(Long siteId, String code, String version, String sourcePlanId, Request request,
            String querySelector, boolean saved, boolean unfilteredBody) {
        if (request == null
                || !request.replaceGroupConfirmed()
                || request.bindings() == null
                || request.bindings().size() > 30
                || request.sharedStatic() == null
                || request.sharedStatic().size() > 30)
            throw new IllegalArgumentException("请明确确认整组卡片替换影响和最多30项显示映射");
        var previousSources = new HashSet<String>();
        if (saved) {
            previousSources.addAll(request.sharedStatic().keySet());
            request.bindings().forEach(binding -> previousSources.add(binding.sourceId()));
        }
        var evidence = inspect(siteId, code, version, sourcePlanId, request.pairing(),
                request.cardId(), saved, previousSources);
        var sources = new LinkedHashMap<String, TemplateContentUnitsService.Source>();
        for (var source : evidence.sources()) sources.put(source.id(), source);
        var covered = new HashSet<String>();
        QueryCarouselCounter carouselCounter = null;
        if (Boolean.TRUE.equals(request.carouselCounter())) {
            carouselCounter = evidence.carouselCounter();
            if (carouselCounter == null)
                throw new IllegalArgumentException("轮播序号缺少完整且唯一的卡片组证据");
            covered.addAll(carouselCounter.sourceIds());
        }
        var actualDetailLinks = new HashSet<String>();
        var usedFields = new LinkedHashMap<String, Field>();
        for (var binding : request.bindings()) {
            if (binding == null || !covered.add(binding.sourceId()))
                throw new IllegalArgumentException("显示来源重复");
            var source = sources.get(binding.sourceId());
            if (source == null) throw new IllegalArgumentException("显示来源不属于确认的卡片");
            if ("$detail".equals(binding.fieldName())) {
                if (!evidence.detailLinkSourceIds().contains(source.id()))
                    throw new IllegalArgumentException("详情地址只能绑定指向已确认详情的原卡片链接");
                actualDetailLinks.add(source.id());
                continue;
            }
            var field =
                    evidence.fields().stream()
                            .filter(value -> value.fieldName().equals(binding.fieldName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("显示字段不在当前模型支持范围"));
            boolean compatible =
                    switch (field.fieldType()) {
                        case "SingleLine" ->
                                Set.of("TEXT", "ATTRIBUTE_ALT", "ATTRIBUTE_TITLE")
                                        .contains(source.kind());
                        case "MultiLine", "DateTime" -> source.kind().equals("TEXT");
                        case "Editor" -> source.kind().equals("HTML_REGION");
                        case "Image" -> Set.of("IMAGE", "VIDEO_POSTER").contains(source.kind());
                        case "Video" -> Set.of("VIDEO_SRC", "VIDEO_SOURCE").contains(source.kind());
                        default -> false;
                    };
            if (!compatible) throw new IllegalArgumentException("显示来源与字段类型不兼容或仍待处理");
            usedFields.put(field.fieldName(), field);
        }
        var selectedActions = request.actions() == null
                ? List.<FormActionBinding.Selection>of() : request.actions();
        if (selectedActions.size() > 12) throw new IllegalArgumentException("最多确认12项链接预填动作");
        var actionBindings = new ArrayList<FormActionBinding.Bound>();
        for (var action : selectedActions) {
            if (action == null || !covered.add(action.sourceId()))
                throw new IllegalArgumentException("链接动作来源重复");
            var candidate = evidence.actions().stream()
                    .filter(item -> item.sourceId().equals(action.sourceId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("链接没有当前目标表单证据"));
            var input = candidate.fields().stream()
                    .filter(item -> item.inputId().equals(action.inputId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("输入框未通过预填验证"));
            var field = evidence.fields().stream()
                    .filter(item -> item.fieldName().equals(action.fieldName())
                            && "SingleLine".equals(item.fieldType()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("预填只支持已启用的普通单行文字字段"));
            String template = FormActionBinding.templatePath(
                    action.targetMenuId(), candidate.targetDigest(), input.inputId());
            var target = menus.selectPublicBindingMenu(siteId, action.targetMenuId());
            String route = FormActionBinding.requireRoute(target, siteId, template, action.formCategoryId());
            var receipt = nativeForms.read(siteId, code, version, action.formCategoryId());
            if (!Objects.equals(receipt.menuId(), action.targetMenuId())
                    || !Objects.equals(receipt.sourcePath(), candidate.targetPath())
                    || !Objects.equals(receipt.sourceDigest(), candidate.targetDigest())
                    || receipt.fields().stream().noneMatch(fieldBinding ->
                            fieldBinding.sourceKey().equals(input.inputId())))
                throw new IllegalArgumentException("正式表单回执与当前目标预填输入不一致");
            try {
                publicForms.publicDefinition(siteId, action.formCategoryId());
            } catch (com.xinhongru.cms.formservice.FormApiException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
            usedFields.put(field.fieldName(), field);
            actionBindings.add(new FormActionBinding.Bound(
                    candidate.sourceId(), field.fieldName(), input.parameterName(), route));
        }
        for (var declaration : request.sharedStatic().entrySet()) {
            if (!sources.containsKey(declaration.getKey())
                    || !covered.add(declaration.getKey())
                    || declaration.getValue() == null
                    || declaration.getValue().isBlank()
                    || declaration.getValue().length() > 300)
                throw new IllegalArgumentException("共用固定装饰须逐项明确说明，不能重复覆盖");
        }
        var unbound =
                evidence.sources().stream()
                        .filter(QueryListPresentation::atomic)
                        .filter(source -> !covered.contains(source.id()))
                        .toList();
        if (!unbound.isEmpty())
            throw new IllegalArgumentException(
                    "卡片尚有未绑定业务文字、图片或链接："
                            + unbound.stream()
                                    .limit(5)
                                    .map(source -> source.selector() + " " + source.kind())
                                    .toList());
        if (!actualDetailLinks.equals(Set.copyOf(evidence.detailLinkSourceIds())))
            throw new IllegalArgumentException("所有指向已确认详情的链接都必须绑定真实内容地址");
        if (usedFields.isEmpty()) throw new IllegalArgumentException("至少明确一个当前业务字段，不能整张卡片声明固定装饰");
        if (request.fixedHeadingPlanId() != null && !saved) requireFixedHeading(siteId, code, version, request, evidence);
        ConfirmedCategorySidebar category = null;
        ConfirmedDeferredRegion deferred = null;
        var featured = request.featuredCarousel();
        if (featured != null && (!unfilteredBody || request.pagination() == null
                || request.deferredRegion() != null || request.categorySidebar() != null
                || actualDetailLinks.isEmpty()))
            throw new IllegalArgumentException("置顶轮播须使用无筛选分页列表、已确认详情和独立展示区域");
        if (request.pagination() != null || request.categorySidebar() != null || request.deferredRegion() != null) {
            var page = packages.readStaticPage(code, version, evidence.sourcePath());
            if (!page.digest().equals(evidence.digest()))
                throw new IllegalArgumentException("列表来源在确认期间变化");
            var document = org.jsoup.Jsoup.parse(page.html());
            document.outputSettings().prettyPrint(false);
            var pager = request.pagination() == null ? null
                    : QueryPagerControls.validate(document, request.pagination());
            if (unfilteredBody) requireUnfilteredBody(document, pager);
            if (featured != null) {
                var group = QueryFeaturedCarousel.source(document, featured, evidence.containerSelector(),
                        querySelector == null ? "body" : querySelector, request.pagination());
                addCarouselField(evidence, usedFields, featured.imageField(), Set.of("Image"));
                addCarouselField(evidence, usedFields, featured.titleField(), Set.of("SingleLine"));
                if (featured.summaryField() != null) {
                    if (featured.summaryField().equals(featured.titleField())
                            || group.cards().getFirst().summary() == null)
                        throw new IllegalArgumentException("轮播摘要须有独立的原位置及字段");
                    addCarouselField(evidence, usedFields, featured.summaryField(), Set.of("MultiLine", "SingleLine"));
                }
            }
            if (pager != null) {
                var card = document.selectFirst(evidence.cardSelector());
                if (card != null && card.getAllElements().contains(pager))
                    throw new IllegalArgumentException("分页区域不能位于重复卡片内部");
            }
            if (request.categorySidebar() != null || request.deferredRegion() != null) {
                var lists = document.select(evidence.containerSelector());
                if (lists.size() != 1) throw new IllegalArgumentException("原卡片组位置已变化");
                if (request.categorySidebar() != null)
                    category = confirmCategory(siteId, code, version, request.pairing().menuId(),
                            evidence.sourcePath(), request.categorySidebar(), document, lists.first(), pager);
                if (request.deferredRegion() != null) {
                    var region = deferredRegion(document, request.deferredRegion(), evidence.containerSelector(),
                            querySelector, request.pagination(),
                            category == null ? null : category.selector(), unfilteredBody);
                    deferred = new ConfirmedDeferredRegion(request.deferredRegion().selector(),
                            page.digest(), regionDigest(region), request.deferredRegion().reason());
                }
            }
        }
        var immutable =
                new Request(
                        request.pairing(),
                        request.cardId(),
                        List.copyOf(request.bindings()),
                        Map.copyOf(request.sharedStatic()),
                        true,
                        request.pagination() == null
                                ? null
                                : QueryPagerControls.immutable(request.pagination()),
                        selectedActions.isEmpty() ? null : List.copyOf(selectedActions),
                        request.categorySidebar(), request.deferredRegion(), null,
                        request.fixedList() ? Boolean.TRUE : null, request.fixedHeadingPlanId(),
                        carouselCounter == null ? null : Boolean.TRUE, featured);
        var origin = displaySource(siteId, code, version, sourcePlanId, saved);
        var inspected = inspectPairing(siteId, code, version, request.pairing(), origin);
        if (!inspected.listDigest().equals(evidence.digest()))
            throw new IllegalArgumentException("卡片来源在确认期间变化");
        return new Plan(
                immutable,
                evidence.containerSelector(),
                evidence.cardSelector(),
                evidence.originalCards(),
                evidence.sources(),
                List.copyOf(usedFields.values()),
                inspected.items().stream().map(TemplateListPairingService.Item::selector).toList(),
                actionBindings.isEmpty() ? null : List.copyOf(actionBindings), category, deferred, carouselCounter);
    }

    private static void addCarouselField(Evidence evidence, Map<String, Field> usedFields, String name,
            Set<String> types) {
        var field = evidence.fields().stream().filter(item -> item.fieldName().equals(name)
                && types.contains(item.fieldType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("轮播字段未启用或类型不匹配"));
        usedFields.put(field.fieldName(), field);
    }

    private TemplateFieldPlanService.Plan displaySource(Long siteId, String code, String version, String id,
            boolean saved) {
        return saved ? plans.readSavedDisplaySource(siteId, code, version, id) : plans.read(siteId, code, version, id);
    }

    private void requireFixedHeading(Long siteId, String code, String version, Request request, Evidence evidence) {
        if (!request.fixedList() || !request.fixedHeadingPlanId().matches("[a-fA-F0-9-]{36}"))
            throw new IllegalArgumentException("请为固定列表标题选择已审站点导航");
        var target = menus.selectActiveMenuModelBySite(siteId, Math.toIntExact(request.pairing().menuId()));
        if (target == null || !Objects.equals(target.getSiteProjectId(), siteId) || target.getSiteRootId() == null)
            throw new IllegalArgumentException("当前栏目无权使用已审标题证据");
        var scope = new SiteModelNavigationService.Scope(siteId, code, version, target.getSiteRootId());
        var current = navigation.get(scope, request.fixedHeadingPlanId(), () -> {});
        if (current.status() != SiteModelNavigationService.Status.APPLIED)
            throw new IllegalArgumentException("栏目标题须使用已应用导航");
        var shared = current.nodes().stream().filter(node -> !node.drift()
                && node.kind() == SiteModelNavigationService.Kind.LIST && evidence.sourcePath().equals(node.sourcePath())).toList();
        var reviewed = shared.stream().filter(node -> Objects.equals(node.menuId(), target.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前栏目未对应此原列表"));
        var original = packages.readStaticPage(code, version, evidence.sourcePath());
        if (!Objects.equals(original.digest(), evidence.digest()))
            throw new IllegalArgumentException("列表标题来源已变化");
        var heading = fixedHeading(org.jsoup.Jsoup.parse(original.html()));
        if (reviewed.modelKey() == null || shared.stream().noneMatch(node ->
                Objects.equals(node.modelKey(), reviewed.modelKey()) && heading.text().equals(node.name())))
            throw new IllegalArgumentException("原页主体标题与已审同模型栏目的名称不一致");
    }

    public static Element fixedHeading(Element document) {
        var selected = document.select("main h1");
        if (selected.size() != 1 || selected.first().text().isBlank()
                || selected.first().childNodeSize() != 1
                || !(selected.first().childNode(0) instanceof org.jsoup.nodes.TextNode)
                || selected.first().closest(SHARED_REGION) != null)
            throw new IllegalArgumentException("主体须有唯一且可保留样式的纯文本一级标题");
        return selected.first();
    }

    public static Element deferredRegion(Element document, DeferredRegion selection, String listSelector,
                                          String querySelector, Pagination pagination, String categorySelector) {
        return deferredRegion(document, selection, listSelector, querySelector, pagination, categorySelector, false);
    }

    public static Element deferredRegion(Element document, DeferredRegion selection, String listSelector,
                                          String querySelector, Pagination pagination, String categorySelector,
                                          boolean unfilteredBody) {
        if (selection == null || selection.selector() == null
                || selection.selector().length() > 200
                || !selection.selector().matches("[.#][A-Za-z_][A-Za-z0-9_-]*([ ]*>[ ]*(ul|ol))?")
                || selection.reason() == null || selection.reason().isBlank()
                || selection.reason().length() > 300 || querySelector == null || pagination == null)
            throw new IllegalArgumentException("请用唯一的 ID、类名或其直接子列表指定暂不发布区域，并明确原因、保留列表和分页");
        var regions = document.select(selection.selector());
        if (regions.size() != 1) throw new IllegalArgumentException("暂不发布区域位置不唯一或已失效");
        var region = regions.first();
        var lists = document.select(listSelector);
        if (lists.size() != 1) throw new IllegalArgumentException("原卡片组位置已变化");
        var list = lists.first();
        var query = unfilteredBody ? null : QueryPagerControls.one(document, querySelector);
        var pager = QueryPagerControls.one(document, pagination.containerSelector());
        var category = categorySelector == null ? null : QueryPagerControls.one(document, categorySelector);
        boolean adjacentRegion = region.is("div,section,aside,article") && list.parent() != null
                && region.parent() == list.parent().parent();
        if ((!adjacentRegion && !deferredSiblingList(region, list, pager)) || region.closest("body") == null
                || region.closest(SHARED_REGION) != null
                || !region.select(SHARED_REGION + ",style,link,base,form,input,select,textarea,button").isEmpty()
                || overlaps(region, list) || query != null && overlaps(region, query) || overlaps(region, pager)
                || category != null && overlaps(region, category))
            throw new IllegalArgumentException("暂不发布区域必须是独立近邻业务区，不能包含共享区、样式或查询控件");
        return region;
    }

    public static List<Element> requireUnfilteredBody(Document document, Element pager) {
        if (pager == null) throw new IllegalArgumentException("整页无筛选模式需要已审核的分页");
        var sharedSearch = sharedSearchShell(document);
        var unresolved = document.body().select(
                "form,input,select,textarea,button,[contenteditable=true],[role=searchbox],"
                        + "[role=combobox],a[href^=?],[data-filter],[data-sort]").stream()
                .filter(control -> control != pager && !control.parents().contains(pager) && !sharedSearch.contains(control))
                .findFirst();
        if (unresolved.isPresent())
            throw new IllegalArgumentException("整页仍含未审核的查询或交互控件，请明确定位并审核：" + unresolved.get().cssSelector());
        return sharedSearch;
    }

    static List<Element> sharedSearchShell(Document document) {
        var controls = new ArrayList<Element>();
        for (var button : document.select("header button,.header button,#header button,.site-header button,"
                + ".site_header button,[role=banner] button")) {
            var parent = button.parent();
            if (parent == null || parent.childrenSize() != 2 || button.closest("form") != null
                    || button.is("[name],[value],[form],[formaction],[formmethod]")
                    || !button.attr("type").isBlank() && !"button".equalsIgnoreCase(button.attr("type"))
                    || !STATIC_SEARCH_BUTTON.matcher(button.attr("onclick")).matches()) continue;
            var input = button.previousElementSibling();
            if (input == null || !input.is("input") || input.is("[name],[form],[value],[onchange],[oninput],[onkeydown]"))
                continue;
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            String hint = input.attr("placeholder") + " " + input.attr("aria-label") + " "
                    + input.className().replace('_', ' ').replace('-', ' ') + " "
                    + button.className().replace('_', ' ').replace('-', ' ');
            if (!"search".equals(type) && (!(type.isBlank() || "text".equals(type))
                    || !SEARCH_HINT.matcher(hint).find())) continue;
            controls.add(input);
            controls.add(button);
        }
        return List.copyOf(controls);
    }

    private static boolean deferredSiblingList(Element region, Element list, Element pager) {
        if (!region.is("ul,ol") || region.childrenSize() < 2
                || region.children().stream().anyMatch(child -> !child.is("li"))
                || region.parent() != pager.parent() || !adjacent(region, pager))
            return false;
        var common = list.parents().stream().filter(region.parents()::contains).findFirst().orElse(null);
        if (common == null || common.is("body,html") || common == region.parent()) return false;
        return adjacent(contentBranch(list, common), contentBranch(region, common));
    }

    private static Element contentBranch(Element element, Element ancestor) {
        while (element.parent() != ancestor) element = element.parent();
        return element;
    }

    private static boolean adjacent(Element first, Element second) {
        return first.previousElementSibling() == second || first.nextElementSibling() == second;
    }

    private static boolean overlaps(Element first, Element second) {
        return first == second || first.parents().contains(second) || second.parents().contains(first);
    }

    public static String regionDigest(Element region) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(region.outerHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    ConfirmedCategorySidebar confirmCategory(
            Long siteId, String code, String version, Long menuId, String sourcePath,
            CategorySidebar selection, Element document, Element list, Element pager) {
        if (selection.selector() == null || !selection.selector().matches("[a-zA-Z0-9_.# -]{1,200}")
                || selection.selector().isBlank() || selection.navigationPlanId() == null)
            throw new IllegalArgumentException("请选择分类侧栏及已应用的整站导航计划");
        var sidebar = QueryPagerControls.one(document, selection.selector());
        requireInertCategory(sidebar);
        if (sidebar.closest("header,footer,.header,.footer") != null
                || !sidebar.select("header,footer,.header,.footer,form,input,select,button").isEmpty()
                || sidebar == list || sidebar.parents().contains(list) || list.parents().contains(sidebar)
                || pager != null && (sidebar == pager || sidebar.parents().contains(pager)
                        || pager.parents().contains(sidebar))
                || sidebar.select("a[href]").stream().anyMatch(anchor -> !anchor.attr("href").isBlank()))
            throw new IllegalArgumentException("分类侧栏范围与业务区域重叠，或含真实链接需要单独审核");
        var headings = categoryHeadings(sidebar);
        if (headings.stream().anyMatch(heading -> heading.childrenSize() != 0 || heading.text().isBlank()))
            throw new IllegalArgumentException("分类名称须是侧栏原有的直接文字");

        var menu = menus.selectPublicBindingMenu(siteId, menuId);
        if (menu == null || menu.getSiteRootId() == null || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getPageType(), 1))
            throw new IllegalArgumentException("目标列表栏目已变化");
        var scope = new SiteModelNavigationService.Scope(siteId, code, version, menu.getSiteRootId());
        var view = navigation.appliedSnapshot(scope, selection.navigationPlanId());
        var current = view.nodes().stream().filter(node -> Objects.equals(node.menuId(), menu.getId())
                && !node.drift() && node.parentKey() != null && sourcePath.equals(node.sourcePath())
                && node.kind() == SiteModelNavigationService.Kind.LIST).toList();
        if (current.size() != 1 || !Objects.equals(current.getFirst().name(), menu.getClassName()))
            throw new IllegalArgumentException("当前栏目与导航里的分类身份不一致");
        String parentKey = current.getFirst().parentKey();
        var parent = view.nodes().stream().filter(node -> parentKey.equals(node.key())).toList();
        var siblings = view.nodes().stream().filter(node -> parentKey.equals(node.parentKey())).toList();
        if (parent.size() != 1 || siblings.size() != headings.size())
            throw new IllegalArgumentException("原侧栏未覆盖同父导航的完整分类");
        var idsByKey = new HashMap<String, Integer>();
        for (var node : view.nodes()) {
            if (idsByKey.putIfAbsent(node.key(), node.menuId()) != null)
                throw new IllegalArgumentException("已应用导航节点身份重复");
        }
        requireCategoryMenu(scope, parent.getFirst(), idsByKey,
                menus.selectPublicBindingMenu(siteId, parent.getFirst().menuId().longValue()));
        var entriesByName = new LinkedHashMap<String, CategoryEntry>();
        for (var candidate : siblings) {
            if (candidate.drift() || candidate.kind() != SiteModelNavigationService.Kind.LIST
                    || candidate.menuId() == null || !sourcePath.equals(candidate.sourcePath())
                    || entriesByName.putIfAbsent(candidate.name(),
                            new CategoryEntry(candidate.name(), candidate.menuId())) != null)
                throw new IllegalArgumentException("同父导航分类身份重复、变化或来源不同");
            var actual = Objects.equals(menu.getId(), candidate.menuId()) ? menu
                    : menus.selectPublicBindingMenu(siteId, candidate.menuId().longValue());
            requireCategoryMenu(scope, candidate, idsByKey, actual);
        }
        var entries = new ArrayList<CategoryEntry>();
        var ids = new HashSet<Integer>();
        for (var heading : headings) {
            var entry = entriesByName.get(heading.text());
            if (entry == null || !ids.add(entry.menuId()))
                throw new IllegalArgumentException("原侧栏分类无法与同父实际栏目逐项对应");
            entries.add(entry);
        }
        if (!ids.contains(menu.getId()))
            throw new IllegalArgumentException("原侧栏未覆盖同父导航的完整分类");
        return new ConfirmedCategorySidebar(selection.selector(), menu.getId(), view.revision(), List.copyOf(entries));
    }

    private static void requireCategoryMenu(SiteModelNavigationService.Scope scope,
                                            SiteModelNavigationService.Node node,
                                            Map<String, Integer> ids, com.xinhongru.entity.OneshotEntity menu) {
        if (menu == null || !Objects.equals(node.menuId(), menu.getId())
                || !Objects.equals(scope.siteId(), menu.getSiteProjectId())
                || !Objects.equals(scope.rootMenuId(), menu.getSiteRootId())
                || Boolean.TRUE.equals(menu.getIsDeleted()) || !Objects.equals(node.name(), menu.getClassName())
                || !SiteModelNavigationService.matchesApplied(scope, node, menu, ids))
            throw new IllegalArgumentException("同父导航分类身份与实际栏目不一致，请重新审核");
    }

    public static List<Element> categoryHeadings(Element sidebar) {
        var lists = sidebar.children().stream().filter(child -> child.is("dl")).toList();
        if (!sidebar.is("div,aside,section,nav") || lists.size() != 1
                || lists.getFirst().children().stream().anyMatch(child -> !child.is("dt,dd")))
            throw new IllegalArgumentException("分类侧栏须有单一分类名称列表");
        var headings = lists.getFirst().children().stream().filter(child -> child.is("dt")).toList();
        if (headings.size() < 2 || headings.size() > 30)
            throw new IllegalArgumentException("分类名称列表超出可核对范围");
        return headings;
    }

    public static void requireInertCategory(Element sidebar) {
        if (!sidebar.select("script,iframe,object,embed").isEmpty()
                || sidebar.getAllElements().stream().anyMatch(element -> element.tagName().startsWith("th:")
                        || element.attributes().asList().stream().anyMatch(attribute -> {
                            String key = attribute.getKey().toLowerCase(Locale.ROOT);
                            return key.startsWith("on") || key.startsWith("th:") || key.startsWith("data-th-");
                        })))
            throw new IllegalArgumentException("分类侧栏含可执行交互或模板指令，须人工审核");
    }

    private static boolean atomic(TemplateContentUnitsService.Source source) {
        return !Set.of("HTML_REGION", "HTML_REGION_PENDING").contains(source.kind());
    }

    static boolean readable(ContentFieldDefinition field) {
        if (NativeImageFields.supports(field)) return true;
        if (!ContentFieldDefinition.enabled(field.getIsOpen())
                || Boolean.TRUE.equals(field.getInputMultiple())) return false;
        if ("DateTime".equals(field.getFieldType()))
            return ContentExtensionFields.isName(field.getFieldName());
        if (field.getInputType() != null
                && !field.getInputType().isBlank()
                && !"number".equals(field.getInputType())) return false;
        String name = field.getFieldName();
        if (Set.of("Image", "Video").contains(field.getFieldType())
                && !ContentExtensionFields.isName(name)) return false;
        if (name == null
                || !(ContentExtensionFields.isName(name)
                        || name.matches("title|singleLine[0-9]+|note[0-9]+|content[0-9]+")))
            return false;
        return field.getFieldType() != null
                && Set.of("SingleLine", "MultiLine", "Editor", "Image", "Video")
                        .contains(field.getFieldType());
    }
}

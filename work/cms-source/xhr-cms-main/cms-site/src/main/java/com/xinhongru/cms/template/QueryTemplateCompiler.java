package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.*;

/** 固定控件映射的受控GET编译；不执行源脚本，也不开放任意Thymeleaf表达式。 */
@Service
public final class QueryTemplateCompiler {
    private static final String MENU_HEADING = "${currentMenu.className}";
    private final TemplateQueryBindingService bindings;
    private final UiPackageStorageService packages;

    public QueryTemplateCompiler(
            TemplateQueryBindingService bindings, UiPackageStorageService packages) {
        this.bindings = bindings;
        this.packages = packages;
    }

    public record Compiled(
            String html,
            TemplateQueryBindingService.Snapshot snapshot,
            List<String> warnings,
            boolean completeList) {
        public Compiled(
                String html, TemplateQueryBindingService.Snapshot snapshot, List<String> warnings) {
            this(
                    html,
                    snapshot,
                    warnings,
                    snapshot != null && snapshot.completeList());
        }
    }

    public Compiled compile(Long siteId, String code, String version, String id) {
        return compile(siteId, code, version, id, true);
    }

    Compiled compile(Long siteId, String code, String version, String id, boolean connectHeaderMenu) {
        var snapshot = bindings.read(siteId, code, version, id);
        var page = packages.readStaticPage(code, version, snapshot.sourcePath());
        if (!page.digest().equals(snapshot.digest()))
            throw new IllegalArgumentException("查询来源在编译前变化");
        var candidates =
                bindings.candidates(
                        siteId,
                        code,
                        version,
                        snapshot.menuId(),
                        snapshot.sourcePath(),
                        snapshot.containerSelector());
        var selectors = new LinkedHashMap<String, String>();
        for (var control : candidates.controls()) selectors.put(control.id(), control.selector());
        return compileHtml(
                page.html(), snapshot, selectors,
                connectHeaderMenu
                        ? path -> packages.readInspectionResource(
                                code, version, path, ListHeaderMenu.RESOURCE_LIMIT)
                        : null);
    }

    public Compiled compileHtml(
            String html,
            TemplateQueryBindingService.Snapshot snapshot,
            Map<String, String> selectors) {
        return compileHtml(html, snapshot, selectors, null);
    }

    Compiled compileHtml(
            String html,
            TemplateQueryBindingService.Snapshot snapshot,
            Map<String, String> selectors,
            java.util.function.Function<String, byte[]> resource) {
        if (snapshot != null && snapshot.presentation() != null && snapshot.presentation().request().timeline() != null)
            return TimelineTemplateCompiler.compile(html, snapshot);
        // 空映射仍走既有全部源模板、注释、DOM与深度安全检查，不能靠Jsoup漏掉prototype指令。
        String normalized =
                snapshot != null && snapshot.presentation() != null
                        ? compileRows(html, snapshot.presentation(), snapshot.sharedLayout() != null)
                        : new TextBindingCompiler().compile(html, List.of(), List.of(), 1).html();
        if (snapshot == null
                || selectors == null
                || snapshot.mappings() == null
                || snapshot.mappings().size() > 20) throw new IllegalArgumentException("查询快照无效");
        var document = Jsoup.parse(normalized);
        document.outputSettings().prettyPrint(false);
        boolean unfilteredBody = unfilteredBody(snapshot);
        if (snapshot.presentation() != null && snapshot.presentation().request().fixedHeadingPlanId() != null)
            QueryListPresentation.fixedHeading(document).attr("th:text", MENU_HEADING);
        var category = snapshot.presentation() == null ? null : snapshot.presentation().categorySidebar();
        var deferred = verifiedDeferredRegion(snapshot);
        Element deferredElement = null;
        var source = deferred != null || unfilteredBody ? Jsoup.parse(html) : null;
        if (source != null) source.outputSettings().prettyPrint(false);
        if (deferred != null) {
            var plan = snapshot.presentation();
            String categorySelector = category == null ? null : category.selector();
            var original = QueryListPresentation.deferredRegion(source, plan.request().deferredRegion(),
                    plan.containerSelector(), snapshot.containerSelector(), plan.request().pagination(),
                    categorySelector, unfilteredBody);
            deferredElement = QueryListPresentation.deferredRegion(document, plan.request().deferredRegion(),
                    plan.containerSelector(), snapshot.containerSelector(), plan.request().pagination(),
                    categorySelector, unfilteredBody);
            if (!deferred.regionDigest().equals(QueryListPresentation.regionDigest(original))
                    || !deferred.regionDigest().equals(QueryListPresentation.regionDigest(deferredElement)))
                throw new IllegalArgumentException("暂不发布区域来源在编译前变化");
        }
        document.select("[data-cms-pagination]").removeAttr("data-cms-pagination");
        if (category != null) applyCategorySidebar(document, snapshot, category);
        if (!document.select("input[form],select[form],textarea[form],button[form]").isEmpty())
            throw new IllegalArgumentException("form关联控件仍待处理");
        if (!document.select("input[type=date],input[type=datetime-local]").isEmpty())
            throw new IllegalArgumentException("原生日期控件提交格式仍待处理");
        var warnings = new ArrayList<String>();
        if (unfilteredBody) {
            var pager = QueryPagerControls.validate(source, snapshot.presentation().request().pagination());
            var sharedSearch = QueryListPresentation.requireUnfilteredBody(source, pager);
            for (var control : sharedSearch) {
                var match = document.select(control.cssSelector());
                if (match.size() != 1 || !match.first().tagName().equals(control.tagName()))
                    throw new IllegalArgumentException("待停用的原页头搜索位置已变化");
                match.first().attr("disabled", "").attr("aria-disabled", "true");
            }
            if (!sharedSearch.isEmpty()) warnings.add("已停用页头未接线的原脚本搜索输入与按钮，页头布局保留。");
        }
        if (!document.select("script").isEmpty()) warnings.add("原页面脚本已移除；不会执行原客服、统计或未知交互");
        document.select("script").remove();
        for (var el : document.getAllElements())
            for (var attr : new ArrayList<>(el.attributes().asList())) {
                String key = attr.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("on")
                        || Set.of(
                                        "formaction",
                                        "formmethod",
                                        "formtarget",
                                        "formenctype",
                                        "formnovalidate")
                                .contains(key)) el.removeAttr(attr.getKey());
            }
        Element form = null;
        for (var mapping : snapshot.mappings()) {
            String selector = selectors.get(mapping.controlId());
            if (selector == null || selector.length() > 1000)
                throw new IllegalArgumentException("控件定位已失效");
            var selected = document.select(selector);
            if (selected.size() != 1) throw new IllegalArgumentException("控件定位不唯一或已失效");
            var control = selected.first();
            var owner = control.closest("form");
            if (owner == null
                    || !(owner.attr("method").isBlank()
                            || owner.attr("method").equalsIgnoreCase("get"))
                    || form != null && form != owner)
                throw new IllegalArgumentException("本片要求同一个原生GET表单；结构改造仍待明确处理");
            form = owner;
            control.removeAttr("form");
            if (mapping.role().equals("SUBMIT")) {
                control.removeAttr("name");
                continue;
            }
            if (mapping.role().equals("SORT")) {
                if (!control.tagName().equals("select") || snapshot.allowedSortKeys().isEmpty())
                    throw new IllegalArgumentException("排序控件尚未确认");
                control.attr("name", "cmsSort");
                int index = 0;
                for (var option : control.select("option"))
                    option.attr("th:selected", selectedExpression(mapping.controlId(), index++));
                continue;
            }
            if (!mapping.role().equals("FILTER")
                    || mapping.fieldName() == null
                    || !mapping.fieldName().matches("[A-Za-z][A-Za-z0-9_]{0,80}")
                    || !snapshot.allowedFilterKeys().contains(mapping.fieldName()))
                throw new IllegalArgumentException("控件角色或固定字段未授权");
            control.attr("name", mapping.fieldName());
            control.removeAttr("disabled");
            if (mapping.label() != null && !mapping.label().isBlank())
                control.attr("aria-label", mapping.label());
            if (control.tagName().equals("select")) {
                int index = 0;
                for (var option : control.select("option")) {
                    String original =
                            option.hasAttr("value") ? option.attr("value") : option.text();
                    String value = original.isEmpty() ? "" : mapping.values().get(original);
                    if (value == null) throw new IllegalArgumentException("原选项没有明确映射");
                    option.attr("value", value);
                    option.attr("th:selected", selectedExpression(mapping.controlId(), index++));
                }
            } else if (control.tagName().equals("input")) {
                control.attr("th:value", valueExpression(mapping.fieldName()));
                if (mapping.label() != null
                        && !mapping.label().isBlank()
                        && control.hasAttr("placeholder"))
                    control.attr("placeholder", "请输入" + mapping.label());
            } else throw new IllegalArgumentException("尚未支持该筛选控件");
            if (mapping.fieldName().startsWith("ext_"))
                form.appendElement("input")
                        .attr("type", "hidden")
                        .attr("name", "op_" + mapping.fieldName())
                        .attr("value", mapping.operator());
        }
        if (snapshot.mappings().isEmpty()) {
            if (!unfilteredBody) disableUnmappedQuery(document, snapshot, warnings);
        }
        else {
            if (form == null) throw new IllegalArgumentException("没有可接通的GET表单");
            if (document.select("form").size() != 1)
                throw new IllegalArgumentException("源包含其他表单，尚未确认其行为");
            form.attr("method", "get").attr("action", snapshot.firstUrl()).removeAttr("target");
            form.select("input[name=__cms_filter],input[name=pageIndex]").remove();
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "__cms_filter")
                    .attr("value", "1");
            form.appendElement("input")
                    .attr("type", "hidden")
                    .attr("name", "pageIndex")
                    .attr("value", "1");
            var allowed = new HashSet<>(snapshot.allowedFilterKeys());
            for (var field : snapshot.allowedFilterKeys()) allowed.add("op_" + field);
            allowed.addAll(List.of("__cms_filter", "pageIndex"));
            if (!snapshot.allowedSortKeys().isEmpty()) allowed.add("cmsSort");
            for (var input : form.select("input[name],select[name],textarea[name],button[name]"))
                if (!allowed.contains(input.attr("name"))) {
                    input.removeAttr("name").attr("disabled", "disabled");
                    warnings.add("未映射控件已停用，不会提交未知查询参数");
                }
        }
        if (snapshot.presentation() != null
                && snapshot.presentation().request().pagination() != null)
            wirePager(document, snapshot.presentation().request().pagination());
        if (deferredElement != null) {
            deferredElement.remove();
            warnings.add("已移除人工确认暂不发布的业务区");
        }
        var featuredPlan = snapshot.presentation() == null ? null : snapshot.presentation().request().featuredCarousel();
        if (featuredPlan != null) {
            var view = snapshot.presentation();
            if (!unfilteredBody || view.request().pagination() == null || snapshot.sharedLayout() != null)
                throw new IllegalArgumentException("置顶轮播须使用本栏无筛选分页列表");
            var group = QueryFeaturedCarousel.source(document, featuredPlan, view.containerSelector(),
                    snapshot.containerSelector(), view.request().pagination());
            ListFeaturedCarousel.bind(document.body(), group, featuredPlan);
            warnings.add("轮播在第一页按当前栏目正常排序展示正式记录。");
        }
        boolean headerMenu = snapshot.completeList() && resource != null
                && ListHeaderMenu.evidenced(html, snapshot.sourcePath(), resource)
                && ListHeaderMenu.append(document);
        if (headerMenu) warnings.add("已按原页证据接入 CMS 页头菜单展开与收起。");
        var compiled =
                new Compiled(document.outerHtml(), snapshot, warnings.stream().distinct().toList());
        validateControlled(compiled, headerMenu);
        return compiled;
    }

    private QueryListPresentation.ConfirmedDeferredRegion verifiedDeferredRegion(
            TemplateQueryBindingService.Snapshot snapshot) {
        var plan = snapshot.presentation();
        if (plan == null) return null;
        var selection = plan.request().deferredRegion();
        var receipt = plan.deferredRegion();
        if (selection == null && receipt == null) return null;
        if (selection == null || receipt == null
                || !Objects.equals(selection.selector(), receipt.selector())
                || !Objects.equals(selection.reason(), receipt.reason())
                || !Objects.equals(snapshot.digest(), receipt.sourceDigest())
                || receipt.regionDigest() == null
                || !receipt.regionDigest().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("暂不发布区域与来源回执不一致");
        return receipt;
    }

    private static boolean unfilteredBody(TemplateQueryBindingService.Snapshot snapshot) {
        return snapshot.mappings().isEmpty() && "body".equals(snapshot.containerSelector())
                && snapshot.presentation() != null && snapshot.presentation().request().pagination() != null
                && snapshot.allowedFilterKeys().isEmpty() && snapshot.allowedSortKeys().isEmpty();
    }

    private void disableUnmappedQuery(
            org.jsoup.nodes.Document document,
            TemplateQueryBindingService.Snapshot snapshot,
            List<String> warnings) {
        var rows = snapshot.presentation();
        if (rows != null && rows.request().fixedList()) {
            if (!"body".equals(snapshot.containerSelector()) || rows.request().pagination() != null
                    || !snapshot.allowedFilterKeys().isEmpty() || !snapshot.allowedSortKeys().isEmpty()
                    || !document.select("form,input,select,textarea,button").isEmpty())
                throw new IllegalArgumentException("无分页列表仍包含未审核的控件或查询映射");
            return;
        }
        if (rows == null
                || rows.request().pagination() == null
                || !snapshot.allowedFilterKeys().isEmpty()
                || !snapshot.allowedSortKeys().isEmpty())
            throw new IllegalArgumentException("无筛选模式需要完整卡片与分页");
        var scope = QueryPagerControls.one(document, snapshot.containerSelector());
        var lists = document.select(rows.containerSelector());
        if (lists.size() != 1) throw new IllegalArgumentException("卡片组位置已变化");
        var list = lists.first();
        var pager =
                QueryPagerControls.one(document, rows.request().pagination().containerSelector());
        if (overlaps(scope, list)
                || overlaps(scope, pager)
                || scope.closest("header,footer,nav,aside,[role=navigation]") != null
                || !scope.select("header,footer,nav,aside,[role=navigation]").isEmpty()
                || document.select("form").stream()
                        .anyMatch(form -> form != scope && !form.parents().contains(scope)))
            throw new IllegalArgumentException("未接线的搜索范围不能包含或位于导航、列表、分页内，也不能留下未知表单");
        if (scope.select("input,select,textarea,button").isEmpty()) {
            if (!scope.select("a[href],form").isEmpty())
                throw new IllegalArgumentException("无筛选模式不能停用未明确的链接或表单");
            return;
        }
        scope.replaceWith(new Element("p").text("当前列表暂不提供筛选，可按页浏览。"));
        warnings.add("已停用选定范围中的原筛选控件；分页仍使用实际公开列表");
    }

    private static boolean overlaps(Element left, Element right) {
        return left == right || left.parents().contains(right) || right.parents().contains(left);
    }

    public void validateControlled(Compiled compiled) {
        validateControlled(compiled, false);
    }

    void validateControlled(Compiled compiled, boolean headerMenu) {
        if (compiled == null || compiled.snapshot() == null)
            throw new IllegalArgumentException("查询编译结果无效");
        if (compiled.snapshot().presentation() != null && compiled.snapshot().presentation().request().timeline() != null) {
            if (!compiled.completeList()) throw new IllegalArgumentException("时间轴完整列表标记无效");
            TimelineTemplateCompiler.validate(compiled.html(), compiled.snapshot().presentation().request().timeline());
            return;
        }
        var allowed = new HashSet<String>();
        for (var mapping : compiled.snapshot().mappings()) {
            if (mapping.role().equals("FILTER") || mapping.role().equals("SORT")) {
                if (mapping.role().equals("FILTER"))
                    allowed.add("th:value=" + valueExpression(mapping.fieldName()));
                for (int i = 0; i <= 50; i++)
                    allowed.add("th:selected=" + selectedExpression(mapping.controlId(), i));
            }
        }
        if (compiled.snapshot().presentation() != null) {
            var rowPlan = compiled.snapshot().presentation();
            allowed.add("th:each=" + rowLoop(rowPlan));
            allowed.add(compiled.snapshot().sharedLayout() == null
                    ? "th:href=${cmsBoundRow.detailUrl}" : "th:attr=" + SHARED_DETAIL_LINK);
            allowed.add("th:if=${cmsBoundRows.isEmpty()}");
            for (var binding : rowPlan.request().bindings()) {
                var source =
                        rowPlan.sources().stream()
                                .filter(item -> item.id().equals(binding.sourceId()))
                                .findFirst()
                                .orElseThrow();
                if (source.kind().equals("ATTRIBUTE_ALT"))
                    allowed.add("th:alt=" + rowExpression("text", binding.fieldName()));
                if (source.kind().equals("ATTRIBUTE_TITLE"))
                    allowed.add("th:title=" + rowExpression("text", binding.fieldName()));
            }
            for (var action : actionBindings(rowPlan))
                allowed.add("th:href=" + actionExpression(action));
            for (var field : rowPlan.fields()) {
                if (field.fieldType().equals("Editor"))
                    allowed.add("th:utext=" + rowExpression("html", field.fieldName()));
                else if (java.util.Set.of("Image", "Video").contains(field.fieldType())) {
                    allowed.add("th:src=" + rowExpression("media", field.fieldName()));
                    allowed.add("th:poster=" + rowExpression("media", field.fieldName()));
                } else allowed.add("th:text=" + rowExpression("text", field.fieldName()));
            }
        }
        var paging =
                compiled.snapshot().presentation() == null
                        ? null
                        : compiled.snapshot().presentation().request().pagination();
        var presentation = compiled.snapshot().presentation();
        if (compiled.completeList() != (paging != null || presentation != null && presentation.request().fixedList()))
            throw new IllegalArgumentException("完整LIST标记与已确认分页不一致");
        if (paging != null) {
            allowed.add("th:if=${cmsBoundPage.total > 0}");
            allowed.add("th:each=cmsPageLink : ${cmsBoundPageNumbers}");
            allowed.add("th:href=${cmsPageLink.url}");
            allowed.add("th:text=${cmsPageLink.number}");
            allowed.add("th:classappend=" + numberClass(paging));
            for (String role : paging.links().keySet()) {
                allowed.add("th:href=${cmsBoundPage." + role + "Url}");
                allowed.add("th:classappend=" + disabledClass(role, paging));
                allowed.add("th:aria-disabled=${" + boundary(role) + "}");
                allowed.add("th:tabindex=${" + boundary(role) + " ? -1 : 0}");
            }
            for (String role : paging.counters().keySet())
                allowed.add("th:text=${cmsBoundPage." + role + "}");
        }
        var copy = Jsoup.parse(compiled.html());
        ListFeaturedCarousel.validateAndRemove(copy, presentation == null ? null : presentation.request().featuredCarousel());
        ListHeaderMenu.removeVerified(copy, headerMenu);
        var rowPlan = compiled.snapshot().presentation();
        var heading = rowPlan != null && rowPlan.request().fixedHeadingPlanId() != null
                ? QueryListPresentation.fixedHeading(copy) : null;
        if (heading != null) {
            if (!MENU_HEADING.equals(heading.attr("th:text")))
                throw new IllegalArgumentException("固定列表主体标题缺少已审核的栏目绑定");
            allowed.add("th:text=" + MENU_HEADING);
        }
        var deferred = verifiedDeferredRegion(compiled.snapshot());
        if (deferred != null && !copy.select(deferred.selector()).isEmpty())
            throw new IllegalArgumentException("正式列表仍含已确认暂不发布的区域");
        if (rowPlan != null && rowPlan.categorySidebar() != null)
            validateCategorySidebar(copy, compiled.snapshot(), rowPlan.categorySidebar());
        int rowLoops = 0, pageLoops = 0;
        var pager =
                paging == null ? null : QueryPagerControls.one(copy, paging.containerSelector());
        Element row = null;
        Map<Element, String> carouselExpressions = Map.of();
        if (rowPlan != null) {
            var container = copy.select(rowPlan.containerSelector());
            if (container.size() != 1) throw new IllegalArgumentException("原卡片组位置已变化");
            var rows = container.first().children().stream()
                    .filter(child -> rowLoop(rowPlan).equals(child.attr("th:each")))
                    .toList();
            if (rows.size() != 1) throw new IllegalArgumentException("列表循环不在确认的卡片");
            row = rows.getFirst();
            if (rowPlan.carouselCounter() != null) {
                carouselExpressions = rowPlan.carouselCounter().expressions(row);
                for (var target : carouselExpressions.entrySet())
                    if (!target.getValue().equals(target.getKey().attr("th:text")))
                        throw new IllegalArgumentException("卡片轮播序号缺少已审核的绑定");
            }
        }
        for (var el : copy.getAllElements())
            for (var attr : new ArrayList<>(el.attributes().asList())) {
                String key = attr.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("th:") || key.startsWith("data-th-")) {
                    boolean carouselText = key.equals("th:text")
                            && attr.getValue().equals(carouselExpressions.get(el));
                    if ((!carouselText && !allowed.contains(key + "=" + attr.getValue()))
                            || MENU_HEADING.equals(attr.getValue()) && el != heading
                            || key.equals("th:value") && !el.tagName().equals("input")
                            || key.equals("th:selected") && !el.tagName().equals("option")
                            || key.equals("th:alt") && !el.tagName().equals("img")
                            || key.equals("th:attr") && (!el.tagName().equals("a") || el.hasAttr("href")))
                        throw new IllegalArgumentException("查询模板含非受控指令");
                    if (key.equals("th:each")) {
                        if (rowPlan != null && attr.getValue().equals(rowLoop(rowPlan))) {
                            if (row == null || row != el || ++rowLoops != 1)
                                throw new IllegalArgumentException("列表循环不在确认的卡片");
                        } else if (pager == null
                                || !pager.getAllElements().contains(el)
                                || ++pageLoops != 1)
                            throw new IllegalArgumentException("数字分页循环不在确认的区域");
                    }
                    if (attr.getValue().contains("cmsBoundRow.")
                            && (row == null || row != el && !row.getAllElements().contains(el)))
                        throw new IllegalArgumentException("行字段不在确认的循环内部");
                    if (attr.getValue().contains("cmsPageLink.")
                            && (pager == null || !pager.getAllElements().contains(el)))
                        throw new IllegalArgumentException("数字页字段超出确认区域");
                    if (key.equals("th:if")) {
                        boolean empty =
                                attr.getValue().equals("${cmsBoundRows.isEmpty()}")
                                        && el.tagName().equals("p")
                                        && "true".equals(el.attr("data-cms-query-empty"));
                        boolean pageVisible =
                                attr.getValue().equals("${cmsBoundPage.total > 0}") && el == pager;
                        if (!empty && !pageVisible) throw new IllegalArgumentException("可见性指令不受控");
                    }
                    if (java.util.Set.of("th:text", "th:utext").contains(key)
                            && el.tagName().equals("th:block")) el.tagName("span");
                    el.removeAttr(attr.getKey());
                }
            }
        if (paging != null && pageLoops != 1) throw new IllegalArgumentException("缺少确认的数字页循环");
        if (compiled.snapshot().presentation() != null && rowLoops != 1)
            throw new IllegalArgumentException("缺少确认的行循环");
        new TextBindingCompiler().compile(copy.outerHtml(), List.of(), List.of(), 1);
    }

    private record CounterEdit(int start, int end, String role) {}

    private void wirePager(
            org.jsoup.nodes.Document document, QueryListPresentation.Pagination paging) {
        var scope = QueryPagerControls.validate(document, paging);
        var numbers = new ArrayList<>(scope.select(paging.numberSelector()));
        var number = numbers.getFirst();
        var edits = new IdentityHashMap<org.jsoup.nodes.TextNode, List<CounterEdit>>();
        for (var entry : paging.counters().entrySet()) {
            var part = entry.getValue();
            var el = QueryPagerControls.one(scope, part.selector());
            var text = (org.jsoup.nodes.TextNode) el.childNode(0);
            edits.computeIfAbsent(text, ignored -> new ArrayList<>())
                    .add(new CounterEdit(part.start(), part.end(), entry.getKey()));
        }
        for (var entry : edits.entrySet()) {
            var text = entry.getKey();
            var pieces = entry.getValue();
            pieces.sort(Comparator.comparingInt(CounterEdit::start).reversed());
            for (var piece : pieces) {
                String original = text.getWholeText().substring(piece.start(), piece.end());
                if (piece.end() < text.getWholeText().length()) text.splitText(piece.end());
                var middle = piece.start() == 0 ? text : text.splitText(piece.start());
                middle.replaceWith(
                        new Element("th:block")
                                .attr("th:text", "${cmsBoundPage." + piece.role() + "}")
                                .text(original));
            }
        }
        for (var entry : paging.links().entrySet()) {
            var link = QueryPagerControls.one(scope, entry.getValue());
            link.removeClass(paging.disabledClass())
                    .removeAttr("tabindex")
                    .removeAttr("aria-disabled");
            link.attr("th:href", "${cmsBoundPage." + entry.getKey() + "Url}")
                    .attr("th:classappend", disabledClass(entry.getKey(), paging))
                    .attr("th:aria-disabled", "${" + boundary(entry.getKey()) + "}")
                    .attr("th:tabindex", "${" + boundary(entry.getKey()) + " ? -1 : 0}");
        }
        for (String selector : paging.ignoredControls().keySet()) scope.select(selector).remove();
        if (!paging.ignoredControls().isEmpty())
            scope.appendElement("span").text("可使用页码、上一页或下一页浏览。");
        for (var other : numbers) if (other != number) other.remove();
        number.removeClass(paging.activeClass())
                .attr("th:each", "cmsPageLink : ${cmsBoundPageNumbers}")
                .attr("th:href", "${cmsPageLink.url}")
                .attr("th:text", "${cmsPageLink.number}")
                .attr("th:classappend", numberClass(paging));
        scope.attr("data-cms-pagination", "true").attr("th:if", "${cmsBoundPage.total > 0}");
    }

    private static String boundary(String role) {
        return Set.of("first", "prev").contains(role)
                ? "cmsBoundPage.page <= 1"
                : "cmsBoundPage.page >= cmsBoundPage.pageCount";
    }

    private static String disabledClass(String role, QueryListPresentation.Pagination paging) {
        return "${" + boundary(role) + " ? '" + paging.disabledClass() + "' : ''}";
    }

    private static String numberClass(QueryListPresentation.Pagination paging) {
        return "${cmsPageLink.current ? '" + paging.activeClass() + "' : ''}";
    }

    private static void applyCategorySidebar(Element doc, TemplateQueryBindingService.Snapshot snapshot,
                                             QueryListPresentation.ConfirmedCategorySidebar category) {
        var sidebar = categorySidebar(doc, snapshot, category);
        var headings = QueryListPresentation.categoryHeadings(sidebar);
        if (!headings.stream()
                        .map(Element::text)
                        .toList()
                        .equals(
                                category.entries().stream()
                                        .map(QueryListPresentation.CategoryEntry::name)
                                        .toList())
                || sidebar.select("a[href]").stream()
                        .anyMatch(link -> !link.attr("href").isBlank()))
            throw new IllegalArgumentException("原分类侧栏证据已变化");
        sidebar.select(".active").removeClass("active");
        sidebar.select(".on").removeClass("on");
        sidebar.select("[aria-current]").removeAttr("aria-current");
        sidebar.select("a").removeAttr("href").removeAttr("target").attr("aria-disabled", "true");
        for (int i = 0; i < headings.size(); i++) {
            var heading = headings.get(i);
            if (Objects.equals(category.entries().get(i).menuId(), category.currentMenuId()))
                heading.attr("aria-current", "page").appendText("（当前分类）");
            else heading.attr("aria-disabled", "true");
        }
    }

    private static void validateCategorySidebar(Element doc, TemplateQueryBindingService.Snapshot snapshot,
                                                QueryListPresentation.ConfirmedCategorySidebar category) {
        var sidebar = categorySidebar(doc, snapshot, category);
        var headings = QueryListPresentation.categoryHeadings(sidebar);
        if (headings.size() != category.entries().size()
                || sidebar.select("[aria-current=page]").size() != 1
                || !sidebar.select(".active,.on,a[href],a[target],a:not([aria-disabled=true])")
                        .isEmpty()) throw new IllegalArgumentException("分类侧栏高亮、禁用链接或数量已变化");
        for (int i = 0; i < headings.size(); i++) {
            var heading = headings.get(i);
            var entry = category.entries().get(i);
            boolean current = Objects.equals(entry.menuId(), category.currentMenuId());
            var name = entry.name() + (current ? "（当前分类）" : "");
            if (!name.equals(heading.text())
                    || !(current ? heading.attr("aria-current").equals("page")
                            : heading.attr("aria-disabled").equals("true")))
                throw new IllegalArgumentException("分类侧栏名称或当前栏目已变化");
        }
    }

    private static Element categorySidebar(Element doc, TemplateQueryBindingService.Snapshot snapshot,
                                            QueryListPresentation.ConfirmedCategorySidebar category) {
        if (category.currentMenuId() == null || !Objects.equals(category.currentMenuId().longValue(), snapshot.menuId()))
            throw new IllegalArgumentException("分类侧栏与目标栏目不一致");
        var sidebar = QueryPagerControls.one(doc, category.selector());
        QueryListPresentation.requireInertCategory(sidebar);
        var search = doc.select(snapshot.containerSelector());
        var lists = doc.select(snapshot.presentation().containerSelector());
        if (lists.size() != 1 || !unfilteredBody(snapshot) && search.stream().anyMatch(scope -> overlaps(scope, sidebar))
                || overlaps(lists.first(), sidebar))
            throw new IllegalArgumentException("分类侧栏与列表或查询控件范围重叠");
        return sidebar;
    }

    private static final String SHARED_DETAIL_LINK = "href=${cmsBoundRow.detailUrl}";

    private String compileRows(String html, QueryListPresentation.Plan plan, boolean sharedLayout) {
        if (Boolean.TRUE.equals(plan.request().carouselCounter()) != (plan.carouselCounter() != null))
            throw new IllegalArgumentException("轮播序号确认与卡片证据不一致");
        var sources = new LinkedHashMap<String, TemplateContentUnitsService.Source>();
        for (var source : plan.sources()) sources.put(source.id(), source);
        var bindings = new ArrayList<TemplateFieldPlanService.Binding>();
        var detailLinks = new LinkedHashMap<String, String>();
        for (var display : plan.request().bindings()) {
            var source = sources.get(display.sourceId());
            if (source == null) throw new IllegalArgumentException("行来源证据缺失");
            if (display.fieldName().equals("$detail")) {
                if (!"LINK".equals(source.kind()))
                    throw new IllegalArgumentException("详情链接来源证据无效");
                detailLinks.put(source.selector(), source.rawValue());
                continue;
            }
            var field =
                    plan.fields().stream()
                            .filter(row -> row.fieldName().equals(display.fieldName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("行字段证据缺失"));
            if (source.kind().startsWith("ATTRIBUTE_")) continue;
            bindings.add(
                    new TemplateFieldPlanService.Binding(
                            "row-" + source.id(),
                            field.name(),
                            field.fieldType(),
                            List.of(
                                    new TemplateContentUnitsService.Reference(
                                            source.id(), 0, source.rawValue().length())),
                            "REUSE",
                            field.fieldName(),
                            source.rawValue().length(),
                            source.rawValue().length(),
                            source.rawValue(),
                            "仅显示，不初始化",
                            false));
        }
        var compiler = new TextBindingCompiler();
        var compiled = compiler.compile(
                html, bindings, plan.sources(), 1, true, true, plan.cardSelector(), detailLinks);
        if (!compiled.pending().isEmpty())
            throw new IllegalArgumentException(
                    "卡片显示仍待处理：" + compiled.pending().getFirst().reason());
        if (!bindings.isEmpty()) compiler.validateControlled(compiled, 1);
        var doc = Jsoup.parse(compiled.html());
        doc.outputSettings().prettyPrint(false);
        var cards = new ArrayList<Element>();
        for (String selector : plan.originalCardSelectors()) {
            var selected = doc.select(selector);
            if (selected.size() != 1) throw new IllegalArgumentException("原卡片定位变化");
            cards.add(selected.first());
        }
        var selected = doc.select(plan.cardSelector());
        if (selected.size() != 1) throw new IllegalArgumentException("模板卡片定位变化");
        var card = selected.first();
        var container = doc.select(plan.containerSelector());
        if (container.size() != 1
                || card.parent() != container.first()
                || !cards.contains(card)
                || cards.size() != plan.originalCards())
            throw new IllegalArgumentException("卡片组范围变化");
        var expressions = new HashMap<String, String>();
        for (var field : plan.fields()) {
            if (field.fieldType().equals("Editor"))
                expressions.put(
                        "${cmsDraftHtml['" + field.fieldName() + "']}",
                        rowExpression("html", field.fieldName()));
            else if (field.fieldType().equals("DateTime"))
                expressions.put(
                        "${cmsDraftDates['" + field.fieldName() + "']}",
                        rowExpression("text", field.fieldName()));
            else if (java.util.Set.of("Image", "Video").contains(field.fieldType()))
                expressions.put(
                        "${cmsDraftMedia['" + field.fieldName() + "']}",
                        rowExpression("media", field.fieldName()));
            else
                expressions.put(
                        field.fieldName().startsWith("ext_")
                                ? "${content.extensionValues['" + field.fieldName() + "']}"
                                : "${content." + field.fieldName() + "}",
                        rowExpression("text", field.fieldName()));
        }
        for (var el : card.getAllElements())
            for (var attr : new ArrayList<>(el.attributes().asList())) {
                if (attr.getKey().startsWith("th:")) {
                    String value = expressions.get(attr.getValue());
                    if (value == null) throw new IllegalArgumentException("行模板含未知值表达式");
                    el.attr(attr.getKey(), value);
                }
            }
        for (var display : plan.request().bindings())
            if (display.fieldName().equals("$detail")) {
                var source = sources.get(display.sourceId());
                var anchor = doc.select(source.selector());
                if (anchor.size() != 1
                        || !anchor.first().tagName().equals("a")
                        || !card.getAllElements().contains(anchor.first()))
                    throw new IllegalArgumentException("详情链接定位变化");
                if (sharedLayout) anchor.first().removeAttr("href").attr("th:attr", SHARED_DETAIL_LINK);
                else anchor.first().attr("th:href", "${cmsBoundRow.detailUrl}");
            }
        for (var action : actionBindings(plan)) {
            var source = sources.get(action.sourceId());
            var anchor = source == null ? new org.jsoup.select.Elements() : doc.select(source.selector());
            if (source == null || !"LINK".equals(source.kind()) || anchor.size() != 1
                    || !anchor.first().tagName().equals("a")
                    || !card.getAllElements().contains(anchor.first())
                    || anchor.first().hasAttr("th:href"))
                throw new IllegalArgumentException("预填链接定位变化");
            anchor.first().attr("href", action.route()).attr("th:href", actionExpression(action));
        }
        for (var display : plan.request().bindings()) {
            var source = sources.get(display.sourceId());
            if (source == null || !source.kind().startsWith("ATTRIBUTE_")) continue;
            String attribute =
                    switch (source.kind()) {
                        case "ATTRIBUTE_ALT" -> "alt";
                        case "ATTRIBUTE_TITLE" -> "title";
                        default -> throw new IllegalArgumentException("不支持的属性来源");
                    };
            var target = doc.select(source.selector());
            if (target.size() != 1
                    || !card.getAllElements().contains(target.first())
                    || attribute.equals("alt") && !target.first().tagName().equals("img"))
                throw new IllegalArgumentException("卡片属性定位变化");
            if (plan.fields().stream()
                    .noneMatch(
                            field ->
                                    field.fieldName().equals(display.fieldName())
                                            && field.fieldType().equals("SingleLine")))
                throw new IllegalArgumentException("属性只支持已确认普通文本字段");
            target.first().attr("th:" + attribute, rowExpression("text", display.fieldName()));
        }
        if (plan.carouselCounter() != null) {
            for (var target : plan.carouselCounter().expressions(card).entrySet()) {
                for (var node = target.getKey(); node != card; node = node.parent())
                    if (node.hasAttr("th:text") || node.hasAttr("th:utext"))
                        throw new IllegalArgumentException("轮播序号与卡片文字绑定重叠");
                target.getKey().attr("th:text", target.getValue());
            }
        }
        for (var original : cards) if (original != card) original.remove();
        card.attr("th:each", rowLoop(plan));
        container
                .first()
                .appendElement("p")
                .attr("data-cms-query-empty", "true")
                .attr("th:if", "${cmsBoundRows.isEmpty()}")
                .text("当前页暂无符合条件的内容");
        return doc.outerHtml();
    }

    private static String rowLoop(QueryListPresentation.Plan plan) {
        return plan.carouselCounter() == null
                ? "cmsBoundRow : ${cmsBoundRows}"
                : "cmsBoundRow, cmsBoundRowStatus : ${cmsBoundRows}";
    }

    private static List<FormActionBinding.Bound> actionBindings(QueryListPresentation.Plan plan) {
        return plan.actions() == null ? List.of() : plan.actions();
    }

    private static String actionExpression(FormActionBinding.Bound action) {
        if (action.sourceId() == null || !action.sourceId().matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("预填链接来源标识无效");
        return "${cmsBoundRow.actionUrls['" + action.sourceId() + "']}";
    }

    private static String rowExpression(String kind, String field) {
        if (!field.matches("[A-Za-z][A-Za-z0-9_]{0,80}"))
            throw new IllegalArgumentException("行字段key无效");
        return "${cmsBoundRow." + kind + "['" + field + "']}";
    }

    private static String valueExpression(String field) {
        return "${cmsBoundForm.values['" + field + "']}";
    }

    private static String selectedExpression(String id, int index) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("控件ID无效");
        return "${cmsBoundForm.selected['" + id + ":" + index + "']}";
    }
}

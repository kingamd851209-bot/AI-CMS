package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.service.InfoService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 首页同构轮播的受控编译与公开内容投影。 */
@Component
final class HomeBannerBinding {
    private static final String EACH = "cmsBannerRow : ${cmsBannerRows}";
    private static final Pattern BACKGROUND = Pattern.compile(
            "(?i)\\s*background-image\\s*:\\s*url\\(\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s()'\"]+))\\s*\\)\\s*;?\\s*");
    static final int MAX_ROWS = 20;

    record Slot(String selector, String fieldName, String type, String attribute) {}
    record Receipt(String sourcePath, String sourceDigest, String regionId, String rowSelector,
                   List<Slot> slots, Boolean lineBreaks) {}
    record Prepared(long menuId, SiteModelEvidenceService.PageEvidence page,
                    SiteModelEvidenceService.Region region, SiteModelPlanService.Model model,
                    SiteModelPlanService.RegionUse use) {}
    record Compiled(String html, Receipt receipt) {}

    static final class Pending extends IllegalArgumentException {
        final RootTemplateApplicationService.BannerState state;

        Pending(RootTemplateApplicationService.BannerState state, String message) {
            super(message);
            this.state = state;
        }
    }

    private final SiteModelPlanService plans;
    private final SiteModelEvidenceService evidence;
    private final SiteModelRecordSeedService records;
    private final UiPackageStorageService packages;
    private final InfoService contents;
    private final ContentExtensionResources resources;
    private final PublicNavigationLinkCompiler navigation;

    HomeBannerBinding(SiteModelPlanService plans, SiteModelEvidenceService evidence,
                      SiteModelRecordSeedService records, UiPackageStorageService packages,
                      InfoService contents, ContentExtensionResources resources,
                      PublicNavigationLinkCompiler navigation) {
        this.plans = plans;
        this.evidence = evidence;
        this.records = records;
        this.packages = packages;
        this.contents = contents;
        this.resources = resources;
        this.navigation = navigation;
    }

    Prepared prepare(SiteModelRecordSeedService.Scope scope, String planId, long revision,
                     String path, String regionId) {
        if (!PublicBindingRootRoutes.indexAlias("/" + path))
            throw new IllegalArgumentException("请选择当前包根目录首页");
        var plan = plans.confirmed(scope.siteId(), scope.code(), scope.packageVersion(), planId, revision);
        var matches = plan.proposal().regions().stream()
                .filter(row -> row.sourcePath().equals(path) && row.regionId().equals(regionId)
                        && row.mode().equals("LIST") && row.maintenance() == null).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("请选择已审核的独立轮播列表区域");
        var use = matches.getFirst();
        var model = plan.proposal().models().stream().filter(row -> row.key().equals(use.modelKey()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("轮播模型不存在"));
        var page = evidence.page(scope.code(), scope.packageVersion(), path);
        var region = page.regions().stream().filter(row -> row.id().equals(regionId)
                        && row.kind().equals("BANNER"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("轮播区域证据已变化"));
        if (!model.kind().equals("LIST") || region.records().size() < 2
                || region.records().size() > MAX_ROWS || region.records().size() != use.records().size())
            throw new IllegalArgumentException("轮播须审核并覆盖全部原始条目");
        if (model.menuId() == null)
            throw new Pending(RootTemplateApplicationService.BannerState.REVIEW_REQUIRED,
                    "请先确认轮播模型绑定的实际CMS栏目");
        return new Prepared(model.menuId(), page, region, model, use);
    }

    void requireSeeded(SiteModelRecordSeedService.Scope scope, String planId, long revision,
                       Prepared prepared) {
        var seeded = records.preview(scope, planId,
                new SiteModelRecordSeedService.Selection(revision, prepared.page().sourcePath(),
                        prepared.region().id()), () -> {});
        if (!seeded.ready() || !Objects.equals(seeded.menuId(), prepared.menuId())
                || seeded.records().size() != prepared.region().records().size()
                || seeded.records().stream().anyMatch(row -> row.contentId() == null
                        || row.status() != SiteModelRecordSeedService.Status.EXISTING))
            throw new Pending(RootTemplateApplicationService.BannerState.NOT_INITIALIZED,
                    "请先通过现有初值流程初始化全部轮播记录");
    }

    Compiled compile(SiteModelRecordSeedService.Scope scope, Prepared prepared, OneshotEntity menu) {
        checkMenu(scope.siteId(), scope.rootMenuId(), prepared.menuId(), menu);
        var page = packages.readStaticPage(scope.code(), scope.packageVersion(), prepared.use().sourcePath());
        if (!page.digest().equals(prepared.page().digest()))
            throw new Pending(RootTemplateApplicationService.BannerState.SOURCE_CHANGED,
                    "轮播源页面摘要已变化");
        return compile(page.html(), prepared, menu);
    }

    static Compiled compile(String html, Prepared prepared, OneshotEntity menu) {
        var document = parse(new TextBindingCompiler().compile(html, List.of(), List.of(), 0).html());
        if (!document.select("base").isEmpty())
            throw new IllegalArgumentException("含基准地址的轮播结构待人工核对");
        var group = one(document, prepared.region().selector());
        var definitions = definitions(menu);
        if (!prepared.model().fields().stream().anyMatch(field -> "Sort".equals(field.type())
                && "sort".equals(field.fieldName())))
            throw new IllegalArgumentException("请在已确认的轮播列表模型中配置原生排序字段");
        var originals = new ArrayList<Element>();
        List<Slot> firstSlots = null;
        String firstShape = null;
        for (var record : prepared.region().records()) {
            var item = one(document, record.selector());
            if (item.parent() != group || !item.is(".swiper-slide")
                    || item != group.child(originals.size())
                    || !item.select("video,source,picture,[data-video],[id],script,iframe,"
                            + "[data-swiper-slide-index],.swiper-slide-active,.swiper-slide-duplicate,"
                            + ".swiper-slide-next,.swiper-slide-prev").isEmpty())
                throw structure();
            originals.add(item);
            var chosen = prepared.use().records().stream().filter(row -> row.recordId().equals(record.id()))
                    .findFirst().orElseThrow(HomeBannerBinding::structure);
            var candidate = item.clone();
            var slots = new ArrayList<Slot>();
            for (var field : prepared.model().fields()) {
                String valueId = chosen.bindings().get(field.key());
                if (valueId == null) continue;
                var value = record.values().stream().filter(row -> row.id().equals(valueId))
                        .findFirst().orElseThrow(HomeBannerBinding::structure);
                var node = one(document, value.selector());
                String attribute = value.attribute();
                if (attribute.equals("text")) node = textTarget(node, value.rawValue(), field.type());
                String relative = relative(item, node);
                var slot = new Slot(relative, field.fieldName(), field.type(), attribute);
                requireField(definitions, slot);
                var copy = relative(candidate, relative);
                checkValue(copy, slot, value);
                if (attribute.equals("text")) copy.empty();
                else copy.removeAttr(attribute);
                slots.add(slot);
            }
            if (slots.isEmpty()) throw structure();
            if (slots.stream().filter(slot -> slot.attribute().equals("href")).count()
                    != item.select("a[href]").size())
                throw new IllegalArgumentException("轮播仍有未审核的跳转链接，须补充确认后编译");
            candidate.getAllElements().forEach(element ->
                    element.textNodes().stream().filter(TextNode::isBlank).toList().forEach(TextNode::remove));
            String shape = candidate.outerHtml();
            if (firstSlots == null) {
                firstSlots = List.copyOf(slots);
                firstShape = shape;
            } else if (!firstSlots.equals(slots) || !firstShape.equals(shape)) throw structure();
        }
        if (firstSlots == null || originals.size() != group.childrenSize()) throw structure();
        var first = originals.getFirst();
        var receipt = new Receipt(prepared.page().sourcePath(), prepared.page().digest(), prepared.region().id(),
                prepared.region().records().getFirst().selector(), firstSlots, true);
        for (var slot : firstSlots) edit(relative(first, slot.selector()), slot, true, false);
        first.attr("th:each", EACH);
        originals.subList(1, originals.size()).forEach(Element::remove);
        return new Compiled(document.outerHtml(), receipt);
    }

    static String withoutInstructions(String html, Receipt receipt) {
        var doc = parse(html);
        var first = one(doc, receipt.rowSelector());
        if (!EACH.equals(first.attr("th:each")) || !first.is(".swiper-slide")
                || receipt.slots() == null || receipt.slots().isEmpty()) throw structure();
        first.removeAttr("th:each");
        for (var slot : receipt.slots())
            edit(relative(first, slot.selector()), slot, Boolean.TRUE.equals(receipt.lineBreaks()), true);
        return doc.outerHtml();
    }

    void contribute(Long site, String code, String version, Integer rootMenuId, OneshotEntity menu,
                    String planId, Receipt receipt, Context context, IdEntity identity) {
        checkMenu(site, rootMenuId, menu.getId().longValue(), menu);
        var definitions = definitions(menu);
        var result = contents.getStructuredPublishedPage(identity, menu.getId(), 1, MAX_ROWS,
                Map.of(), List.of(), List.of());
        var projected = new ArrayList<Map<String, String>>();
        var scope = new SiteModelNavigationService.Scope(site, code, version, rootMenuId);
        var resolvedLinks = new LinkedHashMap<String, String>();
        for (var row : result.list()) {
            if (!Objects.equals(row.getClassid(), menu.getId()) || !Objects.equals(row.getStatus(), 1))
                throw new IllegalArgumentException("轮播内容归属或发布状态不一致");
            var fields = new LinkedHashMap<String, String>();
            for (var slot : receipt.slots()) {
                var definition = requireField(definitions, slot);
                String raw = value(row, slot.fieldName());
                String projectedValue = switch (slot.attribute()) {
                    case "style" -> css(resources.prepareTemplateMedia(definition, raw, site));
                    case "src" -> resources.prepareTemplateMedia(definition, raw, site);
                    case "href" -> raw.isBlank() ? "" : resolvedLinks.computeIfAbsent(raw,
                            key -> navigation.routeForSourceLink(scope, planId, receipt.sourcePath(), key));
                    default -> multiline(slot) && Boolean.TRUE.equals(receipt.lineBreaks())
                            ? escapedLines(raw) : raw;
                };
                fields.put(slot.fieldName(), projectedValue);
            }
            projected.add(Map.copyOf(fields));
        }
        context.setVariable("cmsBannerRows", List.copyOf(projected));
    }

    long countPublished(Long site, OneshotEntity menu) {
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel())) return 0;
        var identity = new IdEntity();
        identity.bindServerSite(site);
        return contents.getStructuredPublishedPage(identity, menu.getId(), 1, 1,
                Map.of(), List.of(), List.of()).total();
    }

    static void checkMenu(Long site, Integer root, long id, OneshotEntity menu) {
        if (menu == null || menu.getId() == null || menu.getId().longValue() != id
                || !Objects.equals(menu.getSiteProjectId(), site) || !Objects.equals(menu.getSiteRootId(), root)
                || !Objects.equals(menu.getPageType(), 1) || Boolean.TRUE.equals(menu.getIsDeleted()))
            throw new IllegalArgumentException("轮播栏目须属于当前站点");
    }

    private static Map<String, ContentFieldDefinition> definitions(OneshotEntity menu) {
        var result = new LinkedHashMap<String, ContentFieldDefinition>();
        for (var field : ContentModelCodec.read(menu.getModelField())) result.put(field.getFieldName(), field);
        return result;
    }

    private static boolean enabled(ContentFieldDefinition field) {
        return field != null && ContentFieldDefinition.enabled(field.getIsOpen());
    }

    private static ContentFieldDefinition requireField(Map<String, ContentFieldDefinition> fields, Slot slot) {
        var field = fields.get(slot.fieldName());
        if (slot.fieldName() == null || !slot.fieldName().matches("[A-Za-z][A-Za-z0-9_]{0,80}")
                || !enabled(field) || !field.getFieldType().equals(slot.type())
                || !switch (slot.attribute()) {
                    case "text" -> Set.of("SingleLine", "MultiLine").contains(slot.type());
                    case "style", "src" -> slot.type().equals("Image");
                    case "href" -> slot.type().equals("SingleLine");
                    default -> false;
                }) throw new IllegalArgumentException("轮播字段已关闭、类型改变或不支持");
        return field;
    }

    private static String value(ContentEntity row, String fieldName) {
        if (ContentExtensionFields.isName(fieldName))
            return Objects.toString(row.getExtensionValues().get(fieldName), "");
        try {
            var field = EntityFields.find(ContentEntity.class, fieldName);
            field.setAccessible(true);
            return Objects.toString(field.get(row), "");
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("轮播字段不可读取", error);
        }
    }

    private static String css(String raw) {
        if (raw.isEmpty() || raw.indexOf('"') >= 0 || raw.indexOf('\\') >= 0
                || raw.chars().anyMatch(Character::isISOControl)) return "";
        return "background-image: url(\"" + raw + "\");";
    }

    private static void checkValue(Element node, Slot slot, SiteModelEvidenceService.Value value) {
        String attribute = slot.attribute();
        if (attribute.equals("text")) return;
        if (attribute.equals("style")) {
            var m = BACKGROUND.matcher(node.attr("style"));
            if (!m.matches() || !value.rawValue().equals(m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2) : m.group(3))) throw structure();
        } else if (!node.attr(attribute).equals(value.rawValue())
                || attribute.equals("href") && (!node.is("a[href]") || value.targetPath() == null)
                || attribute.equals("src") && (!node.is("img[src]:not([srcset])"))) throw structure();
    }

    private static Element textTarget(Element node, String raw, String type) {
        String expected = raw.strip();
        if (!node.wholeText().strip().equals(expected)) throw structure();
        if (node.children().stream().allMatch(child -> child.is("br") && child.attributes().isEmpty()))
            return node;
        if (!type.equals("SingleLine") || !node.ownText().isBlank()) throw structure();
        var children = node.children().stream().filter(child -> !child.wholeText().isBlank()).toList();
        if (children.size() != 1 || !children.getFirst().is("span")
                || children.getFirst().childrenSize() != 0) throw structure();
        return children.getFirst();
    }

    private static boolean multiline(Slot slot) {
        return "text".equals(slot.attribute()) && "MultiLine".equals(slot.type());
    }

    private static String escapedLines(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8").replace("\r\n", "\n")
                .replace('\r', '\n').replace("\n", "<br>");
    }

    private static void edit(Element node, Slot slot, boolean lineBreaks, boolean remove) {
        String name = slot.fieldName();
        if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,80}")) throw structure();
        String expression = "${cmsBannerRow['" + name + "']}";
        if (slot.attribute().equals("href"))
            instruction(node, "th:if", "${!cmsBannerRow['" + name + "'].isEmpty()}", remove);
        instruction(node, "th:" + (multiline(slot) && lineBreaks ? "utext" : slot.attribute()), expression, remove);
        if (multiline(slot) && !lineBreaks) {
            String suffix = ";white-space:pre-wrap";
            String style = node.attr("style");
            if (!style.endsWith(suffix)) throw structure();
            style = style.substring(0, style.length() - suffix.length());
            if (style.isEmpty()) node.removeAttr("style");
            else node.attr("style", style);
        }
    }

    private static void instruction(Element element, String key, String value, boolean remove) {
        if (remove ? !value.equals(element.attr(key)) : element.hasAttr(key)) throw structure();
        if (remove) element.removeAttr(key);
        else element.attr(key, value);
    }

    private static String relative(Element row, Element target) {
        var positions = new ArrayList<Integer>();
        for (var element = target; element != row; element = element.parent()) {
            if (element == null) throw structure();
            positions.addFirst(element.elementSiblingIndex());
        }
        return String.join("/", positions.stream().map(String::valueOf).toList());
    }

    private static Element relative(Element row, String path) {
        var target = row;
        if (!path.isEmpty()) for (String position : path.split("/")) {
            if (!position.matches("[0-9]{1,3}")) throw structure();
            int index = Integer.parseInt(position);
            if (index >= target.childrenSize()) throw structure();
            target = target.child(index);
        }
        return target;
    }

    private static Element one(Element document, String selector) {
        var selected = document.select(selector);
        if (selected.size() != 1) throw structure();
        return selected.first();
    }

    private static org.jsoup.nodes.Document parse(String html) {
        var doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        return doc;
    }

    private static IllegalArgumentException structure() {
        return new IllegalArgumentException("轮播各条目布局、字段位置或交互不同，须人工审核，原模板保留");
    }
}

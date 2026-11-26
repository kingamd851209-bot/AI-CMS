package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.OneshotEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/** 包内链接与目标输入的可复查证据；只允许预填业务对象名称。 */
public final class FormActionBinding {
    public static final int MAX_VALUE_LENGTH = 200;
    public static final String FORM_REGION = "form,.form_group,[role=form]";
    static final String SHARED_FORM_CONTEXT = "header,footer,nav,.header,.footer,.nav,[role=navigation]";
    private static final String SEARCH_CONTEXT = "[role=search],.search,.search_wrap,.site-search";

    private static final Set<String> RESERVED_PARAMETERS = Set.of(
            "pageindex", "pagesize", "classid", "parentid", "infoid", "productid", "relationid",
            "siteid", "siteprojectid", "serversiteprojectid", "pagepath", "bizcode", "id",
            "cmssort", "key", "keywords", "type", "area", "yearstring", "classids", "year", "month",
            "categoryid", "captchakey", "captchacode", "token", "session", "csrf");

    private FormActionBinding() {}

    public record Input(String inputId, String parameterName, String label) {}

    public record Candidate(
            String sourceId,
            String label,
            String targetPath,
            String targetTitle,
            String targetDigest,
            List<Input> fields,
            String suggestedInputId) {}

    public record TargetMenu(Integer id, String name) {}

    public record Selection(
            String sourceId, String fieldName, String inputId, Long targetMenuId,
            Integer formCategoryId) {}

    public record Bound(String sourceId, String fieldName, String parameterName, String route) {}

    static Optional<Candidate> inspect(
            UiPackageStorageService packages,
            String code,
            String version,
            String sourcePath,
            Document sourceDocument,
            TemplateContentUnitsService.Source source) {
        try {
            var selected = sourceDocument.select(source.selector());
            if (selected.size() != 1 || !selected.first().tagName().equals("a"))
                return Optional.empty();
            var anchor = selected.first();
            String label = anchor.text().strip();
            if (label.isBlank() || label.length() > 100) return Optional.empty();
            String path = TemplateListPairingService.staticLinkPath(sourcePath, source.rawValue());
            var target = packages.readStaticPage(code, version, path);
            var doc = Jsoup.parse(target.html());
            var headings = doc.select("h1,h2,h3,title").stream()
                    .filter(element -> element.closest(SHARED_FORM_CONTEXT) == null)
                    .map(Element::text)
                    .filter(text -> !text.isBlank() && text.length() <= 150)
                    .limit(20).toList();
            var sameHeading = headings.stream()
                    .filter(text -> normalized(text).equals(normalized(label))).findFirst();
            var fields = inputs(doc);
            if (fields.isEmpty()) return Optional.empty();
            String targetTitle = sameHeading.orElse(headings.isEmpty() ? "" : headings.getFirst());
            return Optional.of(new Candidate(
                    source.id(), label, path, targetTitle, target.digest(), fields,
                    fields.size() == 1 ? fields.getFirst().inputId() : null));
        } catch (IllegalArgumentException invalidSource) {
            // 该链接不具备足够证据，仍可走原有链接处理。
            return Optional.empty();
        }
    }

    public static List<Input> inputs(Document doc) {
        var inputs = new ArrayList<Input>();
        for (var input : doc.select("input[id]")) {
            String id = input.id();
            String type = input.attr("type");
            if (!allowedParameter(id)
                    || !type.isEmpty() && !type.equalsIgnoreCase("text")
                    || input.hasAttr("disabled") || input.hasAttr("readonly") || input.hasAttr("form")
                    || input.closest("[hidden],[inert],[aria-hidden=true],fieldset[disabled]") != null
                    || doc.select("#" + id).size() != 1)
                continue;
            var group = input.closest(FORM_REGION);
            var label = group == null ? null : associatedLabel(group, input, id);
            if (label == null
                    || group.closest(SHARED_FORM_CONTEXT + "," + SEARCH_CONTEXT) != null
                    || group.select("button,input[type=submit]").stream().anyMatch(button ->
                            (button.text() + button.attr("value") + button.attr("aria-label"))
                                    .strip().matches("(?i)搜索|查找|筛选|查询|search|filter")))
                continue;
            String text = label.text().strip();
            // 仅在标签明确指向业务对象时提供候选；联系人、账号等不进入URL。
            if (!objectLabel(text)
                    || text.matches(".*(?:个人|姓名|联系人|客户|公司|企业|单位|机构|组织|部门|用户|账户|账号|登录|密码|手机|电话|邮箱|地址).*")
                    || (id + " " + text).toLowerCase(Locale.ROOT).matches(
                            ".*(?:user|person|contact|customer|company|organization|department|account|login|password|phone|email|address).*"))
                continue;
            String name = input.attr("name");
            if (!name.isBlank() && !id.equals(name)) continue;
            inputs.add(new Input(id, id, text));
            if (inputs.size() > 12) return List.of();
        }
        return List.copyOf(inputs);
    }

    public static Element associatedLabel(Element region, Element control, String key) {
        if (region == null || control == null || key == null
                || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,79}")) return null;
        var labels = region.select("label[for=" + key + "]");
        if (labels.size() == 1) return labels.first();
        if (!labels.isEmpty()) return null;
        var wrapper = control.closest("label");
        if (wrapper == null || !region.getAllElements().contains(wrapper) || wrapper.hasAttr("for"))
            return null;
        var labelable = wrapper.select("input:not([type=hidden]),textarea,select,button,meter,output,progress");
        return labelable.size() == 1 && labelable.first() == control ? wrapper : null;
    }

    public static boolean allowedParameter(String key) {
        return key != null && key.matches("[A-Za-z][A-Za-z0-9_]{0,62}")
                && !RESERVED_PARAMETERS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static boolean objectLabel(String label) {
        return label.length() <= 36 && (label.matches(".{1,28}名称[：:]?")
                || label.matches(".*(?:产品|商品|样品|型号|课程|项目|服务|主题|活动).*")
                || label.toLowerCase(Locale.ROOT).matches(
                        ".*\\b(?:product|item|sample|model|course|project|service|subject|topic|event)\\b.*"));
    }

    public static String templatePath(Long menuId, String digest, String inputId) {
        if (menuId == null || menuId <= 0 || menuId > Integer.MAX_VALUE
                || digest == null || !digest.matches("[0-9a-f]{64}")
                || !allowedParameter(inputId))
            throw new IllegalArgumentException("预填目标证据无效");
        return "cms-form-prefill/" + menuId + "/" + digest + "/" + inputId + ".html";
    }

    public static boolean single(OneshotEntity menu, Long siteId) {
        return menu != null
                && Objects.equals(menu.getSiteProjectId(), siteId)
                && Integer.valueOf(0).equals(menu.getPageType())
                && menu.getId() != null
                && !Objects.equals(menu.getId(), menu.getSiteRootId());
    }

    static String requireRoute(
            OneshotEntity menu, Long siteId, String templatePath, Integer formCategoryId) {
        if (!single(menu, siteId)
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !matchesSubmissionTemplate(templatePath, menu.getListPageTemplate(), formCategoryId))
            throw new IllegalArgumentException("请先为同站单页应用对应正式表单的预填提交模板，并启用公开路由");
        return publicRoute(menu, siteId);
    }

    private static boolean matchesSubmissionTemplate(String expected, String current, Integer categoryId) {
        if (current == null || categoryId == null || categoryId <= 0) return false;
        String stem = expected.substring(0, expected.length() - 5);
        return current.matches(java.util.regex.Pattern.quote(stem)
                + "--form-" + categoryId + "-[0-9a-f]{64}[.]html");
    }

    public static String publicRoute(OneshotEntity menu, Long siteId) {
        if (!single(menu, siteId) || !Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("请先在栏目设置启用同站单页的公开路由");
        String route = menu.getListPageUrl();
        if (route == null) throw new IllegalArgumentException("目标单页缺少公开路由");
        route = route.replace("{classId}", menu.getId().toString())
                .replace("{parentId}", String.valueOf(menu.getParentId()));
        if (!route.startsWith("/")) route = "/" + route;
        if (!localRoute(route)) throw new IllegalArgumentException("目标必须是固定本地公开路径");
        return route;
    }

    public static boolean localRoute(String route) {
        return route != null && route.length() <= 180
                && route.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?/?");
    }

    public static boolean acceptsValue(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_VALUE_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String normalized(String text) {
        return text.replaceAll("[\\s\\p{Punct}：。！？，、]", "").toLowerCase(Locale.ROOT);
    }
}

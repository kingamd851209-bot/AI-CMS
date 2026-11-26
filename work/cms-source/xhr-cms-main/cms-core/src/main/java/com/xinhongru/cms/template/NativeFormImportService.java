package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.service.cmsPlatform.FormConfigService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 将经过确认的原包控件接入现有站点表单分类及字段配置。 */
@Service
public class NativeFormImportService {
    private static final String REGION = FormActionBinding.FORM_REGION;
    private static final String SHARED = FormActionBinding.SHARED_FORM_CONTEXT;
    private static final String HEADING = "legend,h1,h2,h3,h4";
    private static final int MAX_FIELDS = 35;
    private static final String SOURCE_KEY = "cmsImportSourceKey";
    private static final String SOURCE_PATH = "cmsImportSourcePath";
    private static final String SOURCE_DIGEST = "cmsImportSourceDigest";
    private static final String FORM_SELECTOR = "cmsImportFormSelector";
    private static final String MENU_ID = "cmsImportMenuId";
    private static final String PREFIX = "cms-form-import-v1:";

    private final UiPackageStorageService packages;
    private final FormConfigService forms;
    private final FormOneshotMapper formRows;
    private final SiteProjectMapper projects;
    private final OneShotMapper menus;
    private final ObjectMapper json = new ObjectMapper();

    public NativeFormImportService(
            UiPackageStorageService packages,
            FormConfigService forms,
            FormOneshotMapper formRows,
            SiteProjectMapper projects,
            OneShotMapper menus) {
        this.packages = packages;
        this.forms = forms;
        this.formRows = formRows;
        this.projects = projects;
        this.menus = menus;
    }

    public record Field(
            String sourceKey,
            String selector,
            String label,
            String kind,
            boolean required,
            int maxLength,
            List<String> options,
            String status,
            String reason) {}

    public record Form(String selector, String name, String category, List<Field> fields) {}

    public record Inspection(
            String sourcePath,
            String sourceDigest,
            Long menuId,
            List<Form> forms,
            List<String> warnings) {}

    public record Request(
            Long menuId,
            String sourcePath,
            String sourceDigest,
            String formSelector,
            String name,
            List<String> fieldKeys,
            boolean confirmed) {}

    public record BoundField(
            String sourceKey,
            String selector,
            String label,
            String kind,
            String fieldName,
            boolean required,
            int maxLength,
            List<String> options) {}

    public record Receipt(
            Long siteId,
            Long menuId,
            String packageVersion,
            String sourcePath,
            String sourceDigest,
            Integer categoryId,
            String formSelector,
            List<BoundField> fields) {}

    public static boolean importedIdentity(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public Inspection inspect(
            Long siteId, String code, String version, Long menuId, String sourcePath) {
        var project = projects.selectById(siteId);
        requireProject(project, code, version);
        return inspectSource(siteId, code, version, menuId, sourcePath);
    }

    @Transactional
    public Receipt apply(Long siteId, String code, String version, Request request) {
        if (request == null || !request.confirmed())
            throw new IllegalArgumentException("请确认要创建的原生表单及字段");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("原生表单导入需要在事务内执行");
        var project = projects.selectByIdForUpdate(siteId);
        requireProject(project, code, version);
        var plan = prepare(siteId, code, version, request);
        var sameSource = formRows.lockImportedForms(siteId, plan.sourcePrefix());
        var matches =
                sameSource.stream()
                        .filter(row -> row.getIdentifies().startsWith(plan.formPrefix()))
                        .toList();
        if (matches.size() > 1) throw new IllegalArgumentException("存在重复来源表单，请人工核对");
        if (!matches.isEmpty()) {
            var row = matches.getFirst();
            verify(row, siteId, request.name().strip(), plan);
            return receipt(siteId, version, request, row.getId(), plan);
        }
        if (sameSource.stream()
                .anyMatch(row -> !row.getIdentifies().contains(":" + request.sourceDigest() + ":")))
            throw new IllegalArgumentException("同一页面已有其他版本的原生表单，请先核对来源变化");
        if (forms.all(siteId).stream()
                .anyMatch(row -> request.name().strip().equals(row.getClassName())))
            throw new IllegalArgumentException("本站已有同名表单，请先核对原生表单设置");
        var row =
                forms.create(
                        siteId,
                        new FormOneshotEntity()
                                .setClassName(request.name().strip())
                                .setParentId(0)
                                .setIdentifies(plan.identity())
                                .setPageType(1)
                                .setHasManageChannel(true)
                                .setHasFrontChannel(true)
                                .setIsForm(true)
                                .setFormCaptcha(1)
                                .setIsCanDeleted(true)
                                .setModelField(plan.model())
                                .setModelList(plan.listModel())
                                .setModelSearch("[]"));
        if (row == null || row.getId() == null) throw new IllegalStateException("原生表单分类未创建");
        return receipt(siteId, version, request, row.getId(), plan);
    }

    public Receipt read(Long siteId, String code, String version, Integer categoryId) {
        requireProject(projects.selectById(siteId), code, version);
        var row = categoryId == null ? null : forms.getById(categoryId);
        if (row == null
                || !Objects.equals(row.getSiteProjectId(), siteId)
                || Boolean.TRUE.equals(row.getIsDeleted())
                || !importedIdentity(row.getIdentifies())
                || row.getModelField() == null)
            throw new IllegalArgumentException("该分类不属于当前站点已确认的来源表单");
        var metadata = readModel(row.getModelField());
        if (!metadata.isArray() || metadata.isEmpty() || metadata.size() > MAX_FIELDS)
            throw new IllegalArgumentException("表单来源配置已变化，请重新审核");
        JsonNode first = metadata.get(0);
        var keys = new ArrayList<String>();
        metadata.forEach(field -> keys.add(field.path(SOURCE_KEY).asText("")));
        var request =
                new Request(
                        first.path(MENU_ID).asLong(),
                        first.path(SOURCE_PATH).asText(""),
                        first.path(SOURCE_DIGEST).asText(""),
                        first.path(FORM_SELECTOR).asText(""),
                        row.getClassName(),
                        keys,
                        true);
        var plan = prepare(siteId, code, version, request);
        verify(row, siteId, request.name(), plan);
        return receipt(siteId, version, request, categoryId, plan);
    }

    private record Plan(
            String sourcePrefix,
            String formPrefix,
            String identity,
            String model,
            String listModel,
            List<BoundField> fields) {}

    private Plan prepare(Long siteId, String code, String version, Request request) {
        if (request.name() == null
                || request.name().isBlank()
                || request.name().strip().length() > 100
                || request.fieldKeys() == null
                || request.fieldKeys().isEmpty()
                || request.fieldKeys().size() > MAX_FIELDS
                || request.fieldKeys().stream().anyMatch(key -> key == null || key.isBlank()))
            throw new IllegalArgumentException("请选择表单名称及1至35个有效字段");
        var inspection =
                inspectSource(siteId, code, version, request.menuId(), request.sourcePath());
        if (!Objects.equals(inspection.sourceDigest(), request.sourceDigest()))
            throw new IllegalArgumentException("表单来源已变化，请重新审核");
        var selected =
                inspection.forms().stream()
                        .filter(form -> form.selector().equals(request.formSelector()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("所选表单区域已变化"));
        var keys = new HashSet<>(request.fieldKeys());
        if (keys.size() != request.fieldKeys().size()) throw new IllegalArgumentException("表单字段重复");
        var mapped = new ArrayList<BoundField>();
        var config = new ArrayList<Map<String, Object>>();
        var visible = new ArrayList<Map<String, Object>>();
        int text = 0, note = 0;
        for (var field : selected.fields()) {
            boolean picked = keys.remove(field.sourceKey());
            if (field.required() && !picked)
                throw new IllegalArgumentException("必填项尚未确认：" + field.label());
            if (!picked) continue;
            if (!"CANDIDATE".equals(field.status()))
                throw new IllegalArgumentException("字段暂不支持：" + field.label());
            boolean multiline = field.kind().equals("TEXTAREA");
            String name = multiline ? "note" + ++note : "singleLine" + ++text;
            if (text > 20 || note > 15) throw new IllegalArgumentException("超出原生表单字段容量");
            var bound =
                    new BoundField(
                            field.sourceKey(),
                            field.selector(),
                            field.label(),
                            field.kind(),
                            name,
                            field.required(),
                            field.maxLength(),
                            field.options());
            mapped.add(bound);
            var definition = new LinkedHashMap<String, Object>();
            definition.put("fieldName", name);
            definition.put("fieldType", multiline ? "MultiLine" : "SingleLine");
            definition.put("title", field.label());
            definition.put("fromName", field.label());
            definition.put("isOpen", "on");
            definition.put("isRequired", field.required() ? "on" : "off");
            definition.put("maxLength", field.maxLength());
            if (field.kind().equals("TEL") && field.label().contains("手机"))
                definition.put("isTel", "on");
            if (field.kind().equals("EMAIL")) definition.put("isEmail", "on");
            if (field.kind().equals("SELECT")) {
                definition.put("inputType", "enum");
                definition.put("defaultValue", String.join(",", field.options()));
            }
            visible.add(new LinkedHashMap<>(definition));
            definition.put(SOURCE_KEY, field.sourceKey());
            if (config.isEmpty()) {
                definition.put(SOURCE_PATH, request.sourcePath());
                definition.put(SOURCE_DIGEST, request.sourceDigest());
                definition.put(FORM_SELECTOR, selected.selector());
                definition.put(MENU_ID, request.menuId());
            }
            config.add(definition);
        }
        if (!keys.isEmpty() || config.isEmpty()) throw new IllegalArgumentException("所选字段不在当前表单");
        String model = json.writeValueAsString(config);
        String sourcePrefix = PREFIX + hash(request.sourcePath()).substring(0, 32) + ":";
        String formPrefix = sourcePrefix + hash(selected.selector()).substring(0, 32) + ":";
        return new Plan(
                sourcePrefix,
                formPrefix,
                formPrefix
                        + request.sourceDigest()
                        + ":"
                        + hash(version + "\n" + request.name().strip() + "\n" + model),
                model,
                json.writeValueAsString(visible),
                List.copyOf(mapped));
    }

    private void verify(FormOneshotEntity row, Long siteId, String name, Plan plan) {
        if (!Objects.equals(row.getSiteProjectId(), siteId)
                || Boolean.TRUE.equals(row.getIsDeleted())
                || !Objects.equals(row.getClassName(), name)
                || !Objects.equals(row.getIdentifies(), plan.identity())
                || !Objects.equals(row.getPageType(), 1)
                || !Boolean.TRUE.equals(row.getIsForm())
                || !Boolean.TRUE.equals(row.getHasFrontChannel())
                || !Objects.equals(row.getFormCaptcha(), 1)
                || row.getModelField() == null
                || !readModel(row.getModelField()).equals(readModel(plan.model())))
            throw new IllegalArgumentException("原生表单或来源配置已变化，拒绝覆盖或重复创建");
    }

    private JsonNode readModel(String value) {
        try {
            return json.readTree(value);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("原生表单字段配置已变化，请重新审核");
        }
    }

    private static Receipt receipt(
            Long siteId, String version, Request request, Integer categoryId, Plan plan) {
        return new Receipt(
                siteId,
                request.menuId(),
                version,
                request.sourcePath(),
                request.sourceDigest(),
                categoryId,
                request.formSelector(),
                plan.fields());
    }

    private Inspection inspectSource(
            Long siteId, String code, String version, Long menuId, String sourcePath) {
        if (menuId == null || menuId <= 0 || menuId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("请选择当前站点的表单目标单页");
        var menu = menus.selectActiveMenuModelBySite(siteId, menuId.intValue());
        if (!FormActionBinding.single(menu, siteId)
                || !Objects.equals(menu.getId(), menuId.intValue()))
            throw new IllegalArgumentException("请选择当前站点的表单目标单页");
        var source = packages.readStaticPage(code, version, sourcePath);
        var document = Jsoup.parse(source.html());
        DefinitionListForm.prepare(document, null);
        var warnings = new ArrayList<>(source.warnings());
        var result = new ArrayList<Form>();
        for (var region : document.select(REGION)) {
            if (region.closest(SHARED) != null || formOwner(region) != region) continue;
            var fields = fields(document, region);
            if (fields.isEmpty()) continue;
            if (result.size() == 8) {
                warnings.add("最多检查8个表单区域");
                break;
            }
            String category = localCategory(document, region);
            result.add(new Form(region.cssSelector(), formName(category), category, fields));
        }
        if (result.size() == 1 && "FORM".equals(result.getFirst().category())) {
            var pageCategories =
                    document.select("title," + HEADING).stream()
                            .filter(heading -> heading.closest(SHARED) == null)
                            .limit(20)
                            .map(heading -> category(heading.text()))
                            .filter(kind -> !"FORM".equals(kind))
                            .distinct()
                            .toList();
            if (pageCategories.size() == 1) {
                var form = result.getFirst();
                String category = pageCategories.getFirst();
                result.set(
                        0, new Form(form.selector(), formName(category), category, form.fields()));
            }
        }
        if (result.isEmpty()) {
            var controls =
                    document.select("input:not([type=hidden]),textarea,select,button")
                            .stream()
                            .filter(control -> control.closest(SHARED) == null)
                            .filter(control -> control.closest("[hidden],[inert],[aria-hidden=true]") == null)
                            .limit(12)
                            .map(control -> control.cssSelector())
                            .toList();
            warnings.add(
                    controls.isEmpty()
                            ? "待审：原页没有可定位的控件。请补充包含字段、容器和提交控件的原页片段。"
                            : "待审：发现控件但未识别到可审核的表单区域。请补充原页的表单容器标记、字段标签和提交控件归属。控件位置："
                                    + String.join("；", controls));
        }
        return new Inspection(
                sourcePath, source.digest(), menuId, List.copyOf(result), List.copyOf(warnings));
    }

    private static Element formOwner(Element element) {
        var nativeForm = element.closest("form");
        return nativeForm == null ? element.closest(REGION) : nativeForm;
    }

    private static String localCategory(Document document, Element region) {
        String category = category(region.attr("aria-label"));
        if (!"FORM".equals(category)) return category;
        String labelledBy = region.attr("aria-labelledby").strip();
        if (labelledBy.matches("[A-Za-z][A-Za-z0-9_-]{0,79}")) {
            var labels = document.select("#" + labelledBy);
            if (labels.size() == 1 && labels.first().closest(SHARED) == null) {
                category = category(labels.first().text());
                if (!"FORM".equals(category)) return category;
            }
        }
        for (var heading : region.select(HEADING)) {
            if (formOwner(heading) != region) continue;
            category = category(heading.text());
            if (!"FORM".equals(category)) return category;
        }
        for (Element container = region;
                container != null && !container.is("body,html");
                container = container.parent()) {
            var heading = container.previousElementSibling();
            if (heading != null && heading.is(HEADING) && heading.closest(SHARED) == null) {
                category = category(heading.text());
                if (!"FORM".equals(category)) return category;
            }
            // 上层同时包含其他表单时，不能用该容器的外部标题判断当前表单。
            var parent = container.parent();
            var current = container;
            if (parent == null
                    || parent.children().stream()
                            .anyMatch(
                                    child ->
                                            child != current
                                                    && (child.is(REGION)
                                                            || child.selectFirst(REGION) != null)))
                break;
        }
        return "FORM";
    }

    private static String formName(String category) {
        return switch (category) {
            case "SAMPLE" -> "申请样品";
            case "INQUIRY" -> "询价";
            case "MESSAGE" -> "留言";
            default -> "页面表单";
        };
    }

    private static List<Field> fields(Document document, Element region) {
        var found = new ArrayList<Field>();
        var keys = new HashSet<String>();
        for (var control : region.select("input,textarea,select,.dropdown")) {
            if (formOwner(control) != region
                    || control.closest("[hidden],[inert],[aria-hidden=true],fieldset[disabled]")
                            != null
                    || control.hasAttr("disabled")
                    || control.hasAttr("readonly")) continue;
            boolean dropdown = control.hasClass("dropdown") && !control.is("input,textarea,select");
            String type = control.attr("type").toLowerCase(Locale.ROOT);
            if (control.is("input")
                    && Set.of("submit", "button", "reset", "image", "hidden").contains(type))
                continue;
            String key = control.id().isBlank() ? control.attr("name") : control.id();
            Element label = FormActionBinding.associatedLabel(region, control, key);
            if (label != null && formOwner(label) != region) label = null;
            String pending = "";
            List<String> options = List.of();
            if (dropdown) {
                var parent = control.parent();
                var direct =
                        parent.children().stream()
                                .filter(element -> element.is("label[for]"))
                                .toList();
                if (direct.size() != 1
                        || control.select("input[type=hidden].input-value").size() != 1
                        || control.select(".dropdown-toggle").size() != 1
                        || control.select("input:not([type=hidden]),textarea,select").size() != 0)
                    continue;
                label = direct.getFirst();
                key = label.attr("for");
                if (keys.contains(key) && key.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"))
                    key = "dropdown-" + hash(control.cssSelector()).substring(0, 32);
                options = control.select(".dropdown-menu li").stream().map(Element::text).toList();
            } else if (control.is("select")) {
                var enabled =
                        control.select("option").stream()
                                .filter(
                                        option ->
                                                !option.hasAttr("disabled")
                                                        && option.closest("optgroup[disabled]")
                                                                == null
                                                        && !option.val().isBlank())
                                .toList();
                if (enabled.stream()
                        .anyMatch(
                                option ->
                                        !option.val()
                                                .equals(
                                                        option.hasAttr("label")
                                                                ? option.attr("label")
                                                                : option.text())))
                    pending = "下拉选项的显示文字与提交值不同，当前原生配置无法完整保留";
                else options = enabled.stream().map(Element::val).toList();
            }
            String labelText = fieldLabel(control, label);
            boolean required =
                    control.hasAttr("required")
                            || control.attr("aria-required").equals("true")
                            || label != null && label.hasClass("required");
            String kind =
                    dropdown || control.is("select")
                            ? "SELECT"
                            : control.is("textarea") ? "TEXTAREA" : inputKind(type, key, labelText);
            boolean safe =
                    key.matches("[A-Za-z][A-Za-z0-9_-]{0,79}")
                            && !labelText.isBlank()
                            && labelText.length() <= 100
                            && !kind.equals("UNKNOWN")
                            && (!control.hasAttr("form") && !control.hasAttr("multiple"))
                            && document.select(control.cssSelector()).size() == 1
                            && (dropdown
                                    || control.id().isBlank()
                                    || document.select("#" + control.id()).size() == 1)
                            && (!kind.equals("SELECT") || safeOptions(options));
            if (!pending.isEmpty() || !safe || !keys.add(key)) {
                found.add(
                        new Field(
                                "pending-" + found.size(),
                                control.cssSelector(),
                                labelText.isBlank() ? "未识别字段" : excerpt(labelText),
                                kind,
                                required,
                                0,
                                List.of(),
                                "PENDING",
                                pending.isEmpty() ? "缺少唯一定位、可见标签、受支持的输入类型或完整选项" : pending));
            } else {
                int limit = kind.equals("TEXTAREA") ? 2000 : 250;
                try {
                    int maximum = Integer.parseInt(control.attr("maxlength"));
                    if (maximum > 0) limit = Math.min(limit, maximum);
                } catch (NumberFormatException ignored) {
                    // 未配置长度时采用原生字段的保守上限。
                }
                if (kind.equals("SELECT"))
                    limit = options.stream().mapToInt(String::length).max().orElseThrow();
                found.add(
                        new Field(
                                key,
                                control.cssSelector(),
                                labelText,
                                kind,
                                required,
                                limit,
                                options,
                                "CANDIDATE",
                                ""));
            }
            if (found.size() > MAX_FIELDS) throw new IllegalArgumentException("表单控件超出检查范围");
        }
        return List.copyOf(found);
    }

    private static String fieldLabel(Element control, Element label) {
        if (label == null) return control.attr("aria-label").strip();
        var labelText = label.clone();
        labelText.select("input,textarea,select,button").remove();
        return labelText.text().strip();
    }

    private static boolean safeOptions(List<String> options) {
        return !options.isEmpty()
                && options.size() <= 20
                && new HashSet<>(options).size() == options.size()
                && options.stream()
                        .allMatch(
                                value ->
                                        !value.isBlank()
                                                && value.equals(value.strip())
                                                && value.length() <= 80
                                                && !value.contains(",")
                                                && value.chars()
                                                        .noneMatch(Character::isISOControl));
    }

    private static String inputKind(String type, String key, String label) {
        if (type.equals("tel")
                || Set.of("", "text").contains(type)
                        && key.matches("(?i)phone|mobile|tel|telephone")
                        && label.matches(".*(?:手机|电话).*")) return "TEL";
        if (type.equals("email")
                || Set.of("", "text").contains(type)
                        && key.equalsIgnoreCase("email")
                        && label.matches(".*(?:邮箱|邮件).*")) return "EMAIL";
        return Set.of("", "text").contains(type) ? "TEXT" : "UNKNOWN";
    }

    private static String category(String text) {
        if (text.contains("申请样品") || text.contains("样品申请")) return "SAMPLE";
        if (text.contains("询价")) return "INQUIRY";
        return text.contains("留言") ? "MESSAGE" : "FORM";
    }

    private static String excerpt(String label) {
        return label.length() > 100 ? label.substring(0, 100) : label;
    }

    private static void requireProject(
            com.xinhongru.site.entity.SiteProjectEntity project, String code, String version) {
        if (project == null
                || !Objects.equals(project.getProjectCode(), code)
                || !Objects.equals(project.getUiPackageVersion(), version))
            throw new IllegalArgumentException("当前站点或包版本已变化");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

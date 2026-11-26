package com.xinhongru.cms.template;

import com.xinhongru.cms.formservice.FormSiteApiService;
import com.xinhongru.cms.formservice.FormApiException;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.util.*;

/** 将已核对的表单原页应用到同站单页；不接收任意表达式或写入业务内容。 */
@Service
public class FormPrefillTemplateCompiler {
    private final UiPackageStorageService packages;
    private final TemplateStorageService templates;
    private final OneShotMapper menus;

    public FormPrefillTemplateCompiler(
            UiPackageStorageService packages, TemplateStorageService templates, OneShotMapper menus) {
        this.packages = packages;
        this.templates = templates;
        this.menus = menus;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private FormSiteApiService publicForms;

    public record Compiled(String html, String templatePath) {}

    public record Result(
            String templatePath, String previousTemplate, String publicUrl,
            boolean applied, String message, Integer formCategoryId, boolean submissionReady) {}

    @org.springframework.beans.factory.annotation.Autowired
    private PublicNavigationLinkCompiler navigationLinks;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicSharedContentCompiler sharedContent;

    public Compiled compile(
            String code, String version, String targetPath, String expectedDigest,
            String inputId, Long menuId) {
        return compile(code, version, targetPath, expectedDigest, inputId, menuId, null, null);
    }

    private Compiled compile(
            String code, String version, String targetPath, String expectedDigest,
            String inputId, Long menuId, String formSelector,
            FormPrefillSubmission.Contract submission) {
        return compile(null, code, version, targetPath, expectedDigest, inputId, menuId,
                formSelector, submission);
    }

    private Compiled compile(
            Long siteId, String code, String version, String targetPath, String expectedDigest,
            String inputId, Long menuId, String formSelector,
            FormPrefillSubmission.Contract submission) {
        if (siteId != null && sharedContent != null && sharedContent.requiresPublicReceipt(siteId, code, version, targetPath))
            throw new IllegalArgumentException("此表单页面的公共字段还需人工接入，正式表单模板暂不能消费该绑定");
        var page = packages.readStaticPage(code, version, targetPath);
        if (!Objects.equals(page.digest(), expectedDigest))
            throw new IllegalArgumentException("目标表单原页在取证后变化，请重新检查");
        String path = inputId != null
                ? FormActionBinding.templatePath(menuId, page.digest(), inputId)
                : "cms-form-native/" + menuId + "/" + page.digest() + "/index.html";
        var document = Jsoup.parse(page.html());
        if (submission != null && inputId == null) DefinitionListForm.prepare(document, formSelector);
        document.outputSettings().prettyPrint(false);
        Element input = null;
        if (inputId != null) {
            if (FormActionBinding.inputs(document).stream().noneMatch(field -> field.inputId().equals(inputId)))
                throw new IllegalArgumentException("没有允许预填的目标输入");
            input = document.getElementById(inputId);
        }
        Element group;
        if (submission != null) {
            if (formSelector == null || formSelector.length() > 1000)
                throw new IllegalArgumentException("正式表单缺少原区域证据");
            var groups = document.select(formSelector);
            if (groups.size() != 1 || input != null && !groups.first().getAllElements().contains(input))
                throw new IllegalArgumentException("正式表单区域与当前输入证据不一致");
            group = groups.first();
        } else group = input.closest(FormActionBinding.FORM_REGION);
        document.select("script,iframe,object,embed,meta[http-equiv],base").remove();
        // 不保留表单提交语义，避免键入回车、隐式 GET 或原站提交处理。
        document.select("form").forEach(form -> {
            if (form != group || submission == null) form.tagName("div").attr("role", "form");
        });
        for (var element : document.getAllElements()) {
            for (var attribute : new ArrayList<>(element.attributes().asList())) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue().replaceAll("[\\p{Cntrl}\\s]", "")
                        .toLowerCase(Locale.ROOT);
                if (key.startsWith("on") || key.startsWith("form")
                        || key.startsWith("data-cms-form") || key.startsWith("data-cms-captcha")
                        || Set.of("action", "method", "enctype", "srcdoc", "ping", "data-cms-field").contains(key)
                        || Set.of("src", "href", "poster", "xlink:href").contains(key)
                                && (value.startsWith("javascript:") || value.startsWith("vbscript:")))
                    {
                        element.removeAttr(attribute.getKey());
                        if (key.equals("href") && element.tagName().equals("a"))
                            element.attr("aria-disabled", "true");
                    }
            }
            if (element.tagName().equals("button")
                    || element.tagName().equals("input")
                            && Set.of("submit", "image", "reset").contains(element.attr("type").toLowerCase(Locale.ROOT)))
                element.attr("disabled", "disabled");
        }
        group.attr("role", "form");
        if (submission == null)
            group.appendChild(new Element("p").text("此页仅预填信息，暂未接入提交。"));
        else FormPrefillSubmission.wire(document, group, input, submission);
        document.select(".wow").forEach(element -> element.removeClass("wow"));
        if (input != null) input.removeAttr("value");
        var checked = new TextBindingCompiler().compile(document.outerHtml(), List.of(), List.of(), 0);
        document = Jsoup.parse(PublicBindingResources.rebase(checked.html(), targetPath));
        document.outputSettings().prettyPrint(false);
        if (inputId != null) {
            var control = document.getElementById(inputId);
            control.attr("th:value", "${cmsFormPrefillValue}");
            if (submission != null) control.attr("data-cms-form-prefill", inputId);
        }
        if (submission != null)
            document.head().appendElement("script").attr("src", "/api/form/public/script.js").attr("defer", "");
        String html = document.outerHtml();
        if (siteId != null && navigationLinks != null)
            html = navigationLinks.bind(siteId, code, version, targetPath, html);
        if (submission != null) {
            path = path.substring(0, path.length() - 5) + "--form-" + submission.categoryId()
                    + "-" + PublicBindingApplication.digest(html) + ".html";
        } else if (!html.equals(document.outerHtml())) {
            throw new IllegalArgumentException("先接通正式表单，再应用已确认的公共导航");
        }
        return new Compiled(html, path);
    }

    public static String requestValue(
            String templatePath, Integer menuId, Map<String, Deque<String>> query) {
        if (templatePath == null || menuId == null || menuId <= 0 || query == null) return null;
        var match = java.util.regex.Pattern.compile(
                "cms-form-prefill/" + menuId + "/[0-9a-f]{64}/([A-Za-z][A-Za-z0-9_]{0,62})(?:--form-[1-9][0-9]{0,9}-[0-9a-f]{64})?[.]html")
                .matcher(templatePath);
        if (!match.matches() || !FormActionBinding.allowedParameter(match.group(1))) return null;
        var values = query.get(match.group(1));
        if (values == null || values.size() != 1 || !FormActionBinding.acceptsValue(values.getFirst()))
            return "";
        return values.getFirst();
    }

    public Result preview(
            Long siteId, String code, String version, Long menuId,
            FormActionBinding.Candidate candidate, String inputId) {
        return preview(siteId, code, version, menuId, candidate, inputId, null);
    }

    public Result preview(
            Long siteId, String code, String version, Long menuId,
            FormActionBinding.Candidate candidate, String inputId, NativeFormImportService.Receipt receipt) {
        var menu = menus.selectPublicBindingMenu(siteId, menuId);
        String route = FormActionBinding.publicRoute(menu, siteId);
        var compiled = compileTarget(siteId, code, version, menuId, candidate, inputId, receipt);
        return result(compiled, menu.getListPageTemplate(), route,
                Objects.equals(menu.getListPageTemplate(), compiled.templatePath()), receipt);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, Long menuId,
            FormActionBinding.Candidate candidate, String inputId,
            String expectedTemplate, boolean confirmed) {
        return apply(siteId, code, version, menuId, candidate, inputId, null, expectedTemplate, confirmed);
    }

    @Transactional
    public Result apply(
            Long siteId, String code, String version, Long menuId,
            FormActionBinding.Candidate candidate, String inputId, NativeFormImportService.Receipt receipt,
            String expectedTemplate, boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("请明确确认应用目标表单模板");
        var menu = menus.lockFieldConfiguration(siteId, menuId);
        String route = FormActionBinding.publicRoute(menu, siteId);
        String current = menu.getListPageTemplate();
        if (!Objects.equals(current, expectedTemplate))
            throw new IllegalArgumentException("目标模板已变化，请重新预览");
        var compiled = compileTarget(siteId, code, version, menuId, candidate, inputId, receipt);
        if (!Objects.equals(current, compiled.templatePath())
                && (PublicBindingApplication.managed(current)
                        || current != null && (current.startsWith("cms-form-prefill/")
                                || current.startsWith("cms-form-native/"))
                            && !sameSource(current, menuId, candidate, inputId, receipt)))
            throw new IllegalArgumentException("目标已有其他公开绑定，请先明确处理原模板");
        Path root = templates.resolveTemplateDir(code);
        Path file = root.resolve(compiled.templatePath());
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!file.toRealPath().startsWith(root.toRealPath())
                        || !Files.isRegularFile(file)
                        || !Files.readString(file).equals(compiled.html()))
                    throw new IllegalArgumentException("目标预填模板文件已经变化");
            } else TemplateFileWriter.write(root, compiled.templatePath(), compiled.html());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法保存目标预填模板", error);
        }
        if (!Objects.equals(current, compiled.templatePath())
                && menus.updatePublicBindingTemplate(siteId, menuId, 0, current, compiled.templatePath()) != 1)
            throw new IllegalArgumentException("目标模板并发变化，请重新预览");
        return result(compiled, current, route, true, receipt);
    }

    private static Result result(Compiled compiled, String previous, String route, boolean applied,
            NativeFormImportService.Receipt receipt) {
        boolean ready = receipt != null;
        return new Result(compiled.templatePath(), previous, route, applied,
                (applied ? "目标模板已应用；请重载站点路由并核对公开页面。" : "已核对原表单。")
                        + (ready ? "已匹配正式表单字段和验证码。" : "仅预填，不提交。"),
                ready ? receipt.categoryId() : null, ready);
    }

    private static boolean sameSource(String current, Long menuId, FormActionBinding.Candidate candidate,
            String inputId, NativeFormImportService.Receipt receipt) {
        if (receipt == null) return false;
        String suffix = "--form-" + receipt.categoryId() + "-[0-9a-f]{64}[.]html";
        String standalone = "cms-form-native/" + menuId + "/" + receipt.sourceDigest() + "/index";
        if (current.matches(java.util.regex.Pattern.quote(standalone) + suffix)) return true;
        if (candidate == null || inputId == null) return false;
        String base = FormActionBinding.templatePath(menuId, candidate.targetDigest(), inputId);
        return current.equals(base) || current.matches(
                java.util.regex.Pattern.quote(base.substring(0, base.length() - 5)) + suffix);
    }

    private Compiled compileTarget(Long siteId, String code, String version, Long menuId,
            FormActionBinding.Candidate candidate, String inputId, NativeFormImportService.Receipt receipt) {
        if (receipt == null) {
            if (candidate == null) throw new IllegalArgumentException("缺少正式表单审核回执");
            return compile(siteId, code, version, candidate.targetPath(), candidate.targetDigest(), inputId,
                    menuId, null, null);
        }
        if (!Objects.equals(receipt.siteId(), siteId) || !Objects.equals(receipt.menuId(), menuId)
                || !Objects.equals(receipt.packageVersion(), version)
                || candidate != null && (!Objects.equals(candidate.targetPath(), receipt.sourcePath())
                        || !Objects.equals(candidate.targetDigest(), receipt.sourceDigest())))
            throw new IllegalArgumentException("正式表单回执与当前站点、单页、包版本或原页不一致");
        var contract = submissionContract(siteId, receipt);
        return compile(siteId, code, version, receipt.sourcePath(), receipt.sourceDigest(), inputId, menuId,
                receipt.formSelector(), contract);
    }

    private FormPrefillSubmission.Contract submissionContract(
            Long siteId, NativeFormImportService.Receipt receipt) {
        Map<String, Object> definition;
        try {
            definition = publicForms.publicDefinition(siteId, receipt.categoryId());
        } catch (FormApiException unavailable) {
            throw new IllegalArgumentException(unavailable.getMessage(), unavailable);
        }
        if (!Boolean.TRUE.equals(definition.get("captchaRequired"))
                || !(definition.get("fields") instanceof List<?> fields) || receipt.fields() == null
                || fields.size() != receipt.fields().size())
            throw new IllegalArgumentException("正式表单字段或验证码设置已变化，请重新审核");
        var controls = new ArrayList<FormPrefillSubmission.Control>();
        for (var bound : receipt.fields()) {
            var actual = fields.stream().filter(f -> f instanceof Map<?, ?> map
                    && Objects.equals(map.get("fieldName"), bound.fieldName()))
                    .map(f -> (Map<?, ?>) f).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("正式表单缺少已审核的字段"));
            if (!Objects.equals(bound.label(), actual.get("label"))
                    || !Objects.equals("TEXTAREA".equals(bound.kind()) ? "MultiLine" : "SingleLine",
                            actual.get("fieldType"))
                    || bound.required() != Boolean.TRUE.equals(actual.get("required"))
                    || Boolean.TRUE.equals(actual.get("multiple"))
                    || !(actual.get("maxLength") instanceof Number limit)
                    || !(actual.get("options") instanceof List<?> actualOptions))
                throw new IllegalArgumentException("正式字段类型、标签或限制已变化，请重新审核");
            var values = actualOptions.stream().map(option -> option instanceof Map<?, ?> map
                    ? String.valueOf(map.get("value")) : "").toList();
            if (!Objects.equals(values, bound.options()))
                throw new IllegalArgumentException("正式表单选项已变化，请重新审核");
            controls.add(new FormPrefillSubmission.Control(bound.selector(), bound.sourceKey(), bound.label(),
                    bound.kind(), bound.fieldName(), bound.required(), limit.intValue(), values));
        }
        return new FormPrefillSubmission.Contract(receipt.categoryId(), List.copyOf(controls));
    }
}

package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

@Component
final class DetailFormActions {
    private static final String MARKER = "data-cms-detail-form-action";
    private static final int MAX_ACTIONS = 12;
    private static final String EXCLUDED =
            FormActionBinding.SHARED_FORM_CONTEXT + ",aside,[role=complementary]";

    private final UiPackageStorageService packages;
    private final TemplateSeedService seeds;
    private final OneShotMapper menus;
    private final NativeFormImportService forms;
    private final FormPrefillTemplateCompiler prefill;
    private final TemplateStorageService templates;

    DetailFormActions(
            UiPackageStorageService packages,
            TemplateSeedService seeds,
            OneShotMapper menus,
            NativeFormImportService forms,
            FormPrefillTemplateCompiler prefill,
            TemplateStorageService templates) {
        this.packages = packages;
        this.seeds = seeds;
        this.menus = menus;
        this.forms = forms;
        this.prefill = prefill;
        this.templates = templates;
    }

    record Binding(
            FormActionBinding.Selection selection,
            FormActionBinding.Bound action,
            String selector,
            String originalHref,
            String targetTemplate) {}

    record CandidateSource(
            FormActionBinding.Candidate candidate, TemplateContentUnitsService.Source source) {}

    List<CandidateSource> inspect(TemplateFieldPlanService.Plan plan) {
        if (plan.pageType() != 1
                || !Set.of("CONFIRMED", "CONFIRMED_WITH_PENDING").contains(plan.status())
                || TextBindingCompiler.publicBindings(plan).stream()
                        .noneMatch(DetailFormActions::titleBinding)) return List.of();
        var source = packages.readStaticPage(plan.code(), plan.packageVersion(), plan.sourcePath());
        var preview = seeds.preview(plan.code(), plan.packageVersion(), plan.sourcePath());
        if (!Objects.equals(source.digest(), plan.digest())
                || !Objects.equals(preview.digest(), plan.digest()))
            throw new IllegalArgumentException("详情原页已变化，请重新取证");
        var document = Jsoup.parse(source.html());
        return TemplateContentUnitsService.sources(preview).stream()
                .filter(
                        item ->
                                "LINK".equals(item.kind())
                                        && item.publicRegion() == null
                                        && !Boolean.TRUE.equals(item.publicNavigation())
                                        && !Boolean.TRUE.equals(item.interactionPending())
                                        && anchor(document, item.selector(), item.rawValue())
                                                != null)
                .flatMap(
                        item ->
                                FormActionBinding.inspect(
                                        packages,
                                        plan.code(),
                                        plan.packageVersion(),
                                        plan.sourcePath(),
                                        document,
                                        item)
                                        .map(candidate -> new CandidateSource(candidate, item))
                                        .stream())
                .limit(MAX_ACTIONS)
                .toList();
    }

    static FormActionBinding.Candidate requireCandidate(
            List<CandidateSource> sources, FormActionBinding.Selection selection) {
        if (selection == null || !"title".equals(selection.fieldName()))
            throw new IllegalArgumentException("详情预填只支持已绑定的当前内容标题");
        return sources.stream()
                .map(CandidateSource::candidate)
                .filter(
                        candidate ->
                                candidate.sourceId().equals(selection.sourceId())
                                        && candidate.fields().stream()
                                                .anyMatch(
                                                        input ->
                                                                input.inputId()
                                                                        .equals(
                                                                                selection
                                                                                        .inputId())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("详情按钮或表单输入缺少当前原页证据"));
    }

    List<Binding> confirm(
            TemplateFieldPlanService.Plan plan,
            OneshotEntity menu,
            List<FormActionBinding.Selection> selections) {
        if (selections.isEmpty()) return List.of();
        if (selections.size() > MAX_ACTIONS || !titleEnabled(menu))
            throw new IllegalArgumentException("详情预填需要已启用的当前内容标题，最多确认12个动作");
        var sources = inspect(plan);
        var seen = new HashSet<String>();
        var bindings = new ArrayList<Binding>();
        for (var selection : selections) {
            var candidate = requireCandidate(sources, selection);
            if (!seen.add(selection.sourceId())
                    || selection.formCategoryId() == null
                    || selection.formCategoryId() <= 0)
                throw new IllegalArgumentException("详情按钮重复或缺少正式表单分类");
            var original =
                    sources.stream()
                            .filter(item -> item.candidate().equals(candidate))
                            .findFirst()
                            .orElseThrow()
                            .source();
            var target = menus.selectPublicBindingMenu(plan.siteId(), selection.targetMenuId());
            String route =
                    FormActionBinding.requireRoute(
                            target,
                            plan.siteId(),
                            FormActionBinding.templatePath(
                                    selection.targetMenuId(),
                                    candidate.targetDigest(),
                                    selection.inputId()),
                            selection.formCategoryId());
            if (!Objects.equals(target.getStatus(), 3)
                    || Boolean.TRUE.equals(target.getIsDeleted()))
                throw new IllegalArgumentException("目标表单单页须已发布");
            var receipt =
                    forms.read(
                            plan.siteId(),
                            plan.code(),
                            plan.packageVersion(),
                            selection.formCategoryId());
            if (!Objects.equals(receipt.menuId(), selection.targetMenuId())
                    || !Objects.equals(receipt.sourcePath(), candidate.targetPath())
                    || !Objects.equals(receipt.sourceDigest(), candidate.targetDigest())
                    || receipt.fields().stream()
                            .noneMatch(field -> field.sourceKey().equals(selection.inputId())))
                throw new IllegalArgumentException("正式表单回执与详情按钮的目标不一致");
            var preview =
                    prefill.preview(
                            plan.siteId(),
                            plan.code(),
                            plan.packageVersion(),
                            selection.targetMenuId(),
                            candidate,
                            selection.inputId(),
                            receipt);
            if (!preview.applied()
                    || !preview.submissionReady()
                    || !Objects.equals(route, preview.publicUrl()))
                throw new IllegalArgumentException("目标单页未应用当前审核的正式预填模板");
            verifyTargetFile(plan.code(), preview.templatePath());
            bindings.add(
                    new Binding(
                            selection,
                            new FormActionBinding.Bound(
                                    candidate.sourceId(),
                                    selection.fieldName(),
                                    selection.inputId(),
                                    route),
                            original.selector(),
                            original.rawValue(),
                            preview.templatePath()));
        }
        return List.copyOf(bindings);
    }

    private void verifyTargetFile(String code, String path) {
        var directory = templates.resolveTemplateDir(code);
        var file = directory.resolve(path);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(directory.toRealPath())
                    || Files.size(file) > 2 * 1024 * 1024
                    || !path.endsWith(
                            "-"
                                    + PublicBindingApplication.digest(Files.readString(file))
                                    + ".html")) throw new IllegalArgumentException("目标正式表单模板已变化");
        } catch (IOException error) {
            throw new IllegalArgumentException("目标正式表单模板不可读", error);
        }
    }

    static boolean titleBinding(TemplateFieldPlanService.Binding binding) {
        return "title".equals(binding.fieldName())
                && "SingleLine".equals(binding.fieldType())
                && ("CREATE".equals(binding.action()) || "REUSE".equals(binding.action()))
                && binding.sources().size() == 1;
    }

    static boolean currentTarget(OneshotEntity target, Long siteId, Binding binding) {
        if (!FormActionBinding.single(target, siteId)
                || !Boolean.TRUE.equals(target.getHasFrontChannel())
                || !Objects.equals(target.getStatus(), 3)
                || Boolean.TRUE.equals(target.getIsDeleted())
                || !Objects.equals(target.getListPageTemplate(), binding.targetTemplate()))
            return false;
        try {
            return Objects.equals(
                    FormActionBinding.publicRoute(target, siteId), binding.action().route());
        } catch (IllegalArgumentException invalidRoute) {
            return false;
        }
    }

    static boolean titleEnabled(OneshotEntity menu) {
        return ContentModelCodec.read(menu.getModelField()).stream()
                .anyMatch(
                        field ->
                                "title".equals(field.getFieldName())
                                        && "SingleLine".equals(field.getFieldType())
                                        && ContentFieldDefinition.enabled(field.getIsOpen()));
    }

    static String compile(String html, List<Binding> bindings) {
        if (bindings.isEmpty()) return html;
        var document = parse(html);
        if (!document.select("[" + MARKER + "]").isEmpty())
            throw new IllegalArgumentException("原详情模板含保留的表单动作标记");
        for (var binding : bindings) {
            var anchor = anchor(document, binding.selector(), binding.originalHref());
            if (anchor == null || anchor.hasAttr("th:href") || anchor.hasAttr("data-th-href"))
                throw new IllegalArgumentException("详情按钮定位或原链接已变化");
            anchor.attributes().asList().stream()
                    .filter(attr -> "href".equals(attr.getKey()))
                    .findFirst()
                    .orElseThrow()
                    .setKey(MARKER);
            anchor.attr(MARKER, marker(binding)).attr("th:href", expression(binding));
        }
        return document.outerHtml();
    }

    static String withoutInstructions(String html, List<Binding> bindings) {
        if (bindings.isEmpty()) return html;
        var document = parse(html);
        if (bindings.size() > MAX_ACTIONS
                || document.select("[" + MARKER + "]").size() != bindings.size())
            throw new IllegalArgumentException("详情表单动作数量不一致");
        for (var binding : bindings) {
            var matches = document.select(binding.selector());
            if (matches.size() != 1
                    || !validAnchor(matches.first())
                    || !marker(binding).equals(matches.first().attr(MARKER))
                    || !expression(binding).equals(matches.first().attr("th:href"))
                    || matches.first().hasAttr("href")
                    || matches.first().hasAttr("data-th-href"))
                throw new IllegalArgumentException("详情表单动作不符合审核的按钮位置");
            var anchor = matches.first();
            anchor.attributes().asList().stream()
                    .filter(attr -> MARKER.equals(attr.getKey()))
                    .findFirst()
                    .orElseThrow()
                    .setKey("href");
            anchor.removeAttr("th:href").attr("href", binding.originalHref());
        }
        return document.outerHtml();
    }

    private static Document parse(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        return document;
    }

    private static String marker(Binding binding) {
        return binding.action().sourceId()
                + "-"
                + PublicBindingApplication.digest(
                        binding.selection().targetMenuId()
                                + "\n"
                                + binding.targetTemplate()
                                + "\n"
                                + binding.action().route());
    }

    private static String expression(Binding binding) {
        var id = binding.action().sourceId();
        if (id == null || !id.matches("item-[1-9][0-9]{0,3}"))
            throw new IllegalArgumentException("详情表单动作来源无效");
        return "${cmsBoundDetailActionUrls['" + id + "']}";
    }

    private static Element anchor(Document document, String selector, String href) {
        var matches = document.select(selector);
        return matches.size() == 1
                        && validAnchor(matches.first())
                        && matches.first().hasAttr("href")
                        && href.equals(matches.first().attr("href"))
                ? matches.first()
                : null;
    }

    private static boolean validAnchor(Element element) {
        return element.is("a") && element.closest(EXCLUDED) == null;
    }
}

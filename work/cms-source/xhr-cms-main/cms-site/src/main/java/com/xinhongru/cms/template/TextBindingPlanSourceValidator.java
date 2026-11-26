package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在字段配置保存前用同一编译器验证计划在原页面上的绑定。 */
@Component
final class TextBindingPlanSourceValidator implements TemplateFieldPlanService.SourceValidator {
    private final UiPackageStorageService packages;
    private final TextBindingCompiler compiler = new TextBindingCompiler();

    TextBindingPlanSourceValidator(UiPackageStorageService packages) {
        this.packages = packages;
    }

    @Override
    public void validate(TemplateFieldPlanService.Plan plan, OneshotEntity menu) {
        if (plan.pageType() != 0 && plan.pageType() != 1) return;
        var definitions = plan.fieldUpdate() == null
                ? ContentModelCodec.read(plan.pageType() == 0 ? menu.getMenuField() : menu.getModelField())
                : plan.fieldUpdate().getFieldOptions();
        Map<String, ContentFieldDefinition> fields = new HashMap<>();
        definitions.forEach(field -> fields.put(field.getFieldName(), field));
        var bindings = plan.bindings().stream()
                .filter(binding -> !TextBindingDraftService.isDeferredSubtype(
                        binding, fields.get(binding.fieldName())))
                .toList();
        if (plan.origins() == null || plan.origins().isEmpty()) {
            validatePage(plan, plan.sourcePath(), plan.digest(), bindings);
            return;
        }
        for (var origin : plan.origins()) {
            boolean list = "LIST".equals(origin.role());
            validatePage(plan, origin.sourcePath(), origin.digest(), bindings.stream()
                    .filter(binding -> list == TextBindingDraftService.isListBinding(plan, binding))
                    .toList());
        }
    }

    private void validatePage(
            TemplateFieldPlanService.Plan plan,
            String path,
            String digest,
            List<TemplateFieldPlanService.Binding> bindings) {
        var source = packages.readStaticPage(plan.code(), plan.packageVersion(), path);
        if (!Objects.equals(source.digest(), digest))
            throw new IllegalArgumentException("页面或包含片段已变化，请重新确认来源与字段计划");
        compiler.compile(source.html(), bindings, plan.sources(), plan.pageType(), true,
                plan.schemaVersion() >= 3);
    }
}

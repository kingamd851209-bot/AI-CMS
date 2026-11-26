package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只投影当前栏目直属子栏目的实时名称。 */
final class MenuNameBinding {
    private MenuNameBinding() {}

    static boolean applies(TemplateFieldPlanService.Binding binding) {
        return "MENU_NAME".equals(binding.action());
    }

    static int id(TemplateFieldPlanService.Binding binding) {
        if (!"SingleLine".equals(binding.fieldType())
                || binding.fieldName() != null
                || binding.menuId() == null
                || binding.menuId() <= 0
                || binding.menuId() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("栏目名称引用无效");
        return binding.menuId().intValue();
    }

    static Map<String, String> names(
            OneShotMapper menus,
            OneshotEntity parent,
            List<TemplateFieldPlanService.Binding> bindings,
            boolean required) {
        var names = new LinkedHashMap<String, String>();
        for (var binding : bindings) {
            if (!applies(binding)) continue;
            int id = id(binding);
            names.computeIfAbsent(Integer.toString(id), key -> {
                var child = menus.selectActiveMenuBySite(parent.getSiteProjectId(), id);
                if (child != null
                        && Objects.equals(child.getId(), id)
                        && TemplateFieldPlanService.isChildMenuNameSource(
                                child, parent.getSiteProjectId(), parent.getId().longValue()))
                    return child.getClassName();
                if (required) throw new IllegalArgumentException("引用的子栏目已不可用，请重新审核");
                return "";
            });
        }
        return Map.copyOf(names);
    }
}

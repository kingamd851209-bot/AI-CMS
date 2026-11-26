package com.xinhongru.cms.template;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** 识别由 dt 标签、dd 输入与唯一提交按钮组成的匿名留言区。仅在受控表单流程使用。 */
final class DefinitionListForm {
    static void prepare(Document page, String selectedGroup) {
        for (Element button : page.select("button")) {
            if (button.closest("form,[role=form],.form_group,header,footer,nav,[role=search]") != null
                    || !button.text().strip().matches("提交留言|提交反馈|提交咨询")) continue;
            for (Element group : button.parents()) {
                if (!group.is("div,section")) break;
                if (group.selectFirst("textarea") == null || group.selectFirst("input") == null) continue;
                if (selectedGroup == null || group.cssSelector().equals(selectedGroup))
                    prepareGroup(page, group, button);
                break;
            }
        }
    }

    private static void prepareGroup(Document page, Element group, Element button) {
        if (group.select("button").size() != 1 || group.selectFirst("form,select,input[name],textarea[name],input[id],textarea[id]") != null) return;
        var controls = group.select("input,textarea");
        if (controls.size() < 2 || controls.size() > 20) return;
        var fields = new LinkedHashMap<Element,String>();
        var ids = new LinkedHashMap<Element,String>();
        Element captchaRow = null;
        for (Element field : controls) {
            Element dd = field.closest("dd");
            Element dl = dd == null ? null : dd.parent();
            if (dl == null || !dl.is("dl") || !group.getAllElements().contains(dl)
                    || dl.select("input,textarea").size() != 1 || dl.select("> dt").size() != 1
                    || !field.is("textarea,input[type=text],input[type=email],input[type=tel]")) return;
            String label = dl.selectFirst("> dt").text().strip();
            if (label.isEmpty() || label.length() > 50 || fields.containsValue(label)) return;
            if (label.equals("验证码")) {
                Element li = dl.closest("li");
                Element box = li == null ? null : li.parent();
                if (captchaRow != null || box == null || !box.is("div") || box.childrenSize() != 2
                        || box.select("img").size() != 1 || box.select("input,textarea,button,select").size() != 1) return;
                captchaRow = box;
                continue;
            }
            String id = "field-" + UUID.nameUUIDFromBytes(field.cssSelector().getBytes(StandardCharsets.UTF_8));
            if (page.getElementById(id) != null || ids.containsValue(id)) return;
            fields.put(field, label);
            ids.put(field, id);
        }
        if (fields.isEmpty()) return;
        group.attr("role", "form");
        fields.forEach((field, label) -> {
            boolean required = label.startsWith("*") || label.startsWith("＊");
            String text = required ? label.substring(1).strip() : label;
            field.attr("id", ids.get(field)).attr("aria-label", text);
            if (required) field.attr("required", "required");
            if (field.is("input") && text.matches("电话|联系电话|手机|手机号")) field.attr("type", "tel");
            if (field.is("input") && text.matches("邮箱|电子邮箱|电子邮件")) field.attr("type", "email");
        });
        // 在真正应用受控提交时，编译器会生成原生验证码和提交按钮。
        if (captchaRow != null) captchaRow.remove();
        button.remove();
    }

    private DefinitionListForm() {}
}

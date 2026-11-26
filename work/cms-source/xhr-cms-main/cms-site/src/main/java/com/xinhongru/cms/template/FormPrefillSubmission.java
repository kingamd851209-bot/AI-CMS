package com.xinhongru.cms.template;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/** 只把审核过的控件接到现有的同站公开表单适配器。 */
final class FormPrefillSubmission {
    record Control(String selector, String sourceKey, String label, String kind, String fieldName,
            boolean required, int maxLength, List<String> options) {}

    record Contract(Integer categoryId, List<Control> controls) {}

    private FormPrefillSubmission() {}

    static void wire(Document document, Element group, Element prefill, Contract contract) {
        if (contract.categoryId() == null || contract.categoryId() <= 0 || contract.controls() == null
                || contract.controls().isEmpty() || contract.controls().size() > 50)
            throw new IllegalArgumentException("原生提交字段契约无效");
        var keys = new HashSet<String>();
        var targets = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        for (var field : contract.controls()) {
            if (field == null || field.fieldName() == null
                    || !field.fieldName().matches("title|singleLine[0-9]+|note[0-9]+")
                    || !keys.add(field.fieldName()) || field.options() == null)
                throw new IllegalArgumentException("原生提交字段存在未知或重复映射");
            var control = originalControl(document, group, field);
            if (!targets.add(control))
                throw new IllegalArgumentException("同一原控件不能接入多个提交字段");
            if ("TEL".equals(field.kind())) control.attr("type", "tel");
            if ("EMAIL".equals(field.kind())) control.attr("type", "email");
            control.attr("data-cms-field", field.fieldName());
            if (field.required()) control.attr("required", "required");
            else control.removeAttr("required");
            if (field.maxLength() > 0 && !control.tagName().equals("select"))
                control.attr("maxlength", Integer.toString(field.maxLength()));
        }
        if (prefill != null && !targets.contains(prefill))
            throw new IllegalArgumentException("正式提交字段未包含预填的原输入");
        // 不设置 name；脚本未加载时，浏览器不会自行把用户输入送到任何地址。
        for (var input : group.select("input,textarea,select,button")) input.removeAttr("name");
        group.tagName("form").removeAttr("role").attr("method", "post")
                .attr("action", "/api/form/public/submit")
                .attr("data-cms-form-category", contract.categoryId().toString());
        for (var link : group.parent().select("a[aria-disabled=true]"))
            if (link.text().strip().matches("(?i)提交|发送|确认提交|submit|send")
                    && (group.getAllElements().contains(link)
                            || link.closest(".modular") != null))
                link.remove();
        var row = group.appendElement("div").addClass("group");
        var item = row.appendElement("div").addClass("item");
        var label = item.appendElement("label").text("验证码");
        label.appendElement("input").attr("type", "text").attr("data-cms-captcha-code", "")
                .attr("maxlength", "16").attr("required", "required").attr("autocomplete", "off");
        item.appendElement("img").attr("data-cms-captcha-image", "").attr("alt", "图形验证码");
        item.appendElement("button").attr("type", "button").attr("data-cms-captcha-refresh", "")
                .text("刷新验证码");
        group.appendElement("p").attr("data-cms-form-status", "").attr("role", "status")
                .text("正在加载表单验证，请稍候。");
        group.appendElement("button").attr("type", "submit").attr("data-cms-form-submit", "")
                .attr("disabled", "disabled").text("提交");
    }

    private static Element originalControl(Document document, Element group, Control field) {
        String key = field.sourceKey();
        if ("SELECT".equals(field.kind()) && field.selector() != null
                && field.selector().length() <= 1000) {
            var selected = document.select(field.selector());
            if (selected.size() == 1 && selected.first().hasClass("dropdown"))
                return enumControl(group, field, selected.first());
        }
        Element control = key != null ? document.getElementById(key) : null;
        if (control == null && key != null && key.matches("[A-Za-z][A-Za-z0-9_-]{0,79}")) {
            var byName = group.select("input[name=" + key + "],textarea[name=" + key
                    + "],select[name=" + key + "]");
            if (byName.size() == 1) control = byName.first();
        }
        if (control == null && field.selector() != null && field.selector().length() <= 1000) {
            var selected = document.select(field.selector());
            if (selected.size() == 1 && selected.first().is("input,textarea,select")) control = selected.first();
        }
        if (!field.options().isEmpty()) return enumControl(group, field, control);
        if (control == null || !group.getAllElements().contains(control)
                || !control.is("input,textarea") || control.hasAttr("disabled") || control.hasAttr("readonly")
                || control.tagName().equals("input")
                        && !Set.of("", "text", "email", "tel", "url", "number", "search")
                                .contains(control.attr("type").toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("原提交控件不唯一、不可编辑或已变化：" + field.label());
        return control;
    }

    private static Element enumControl(Element group, Control field, Element old) {
        String key = field.sourceKey();
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("枚举控件缺少原标签证据：" + field.label());
        List<String> values;
        if (old != null && group.getAllElements().contains(old) && old.tagName().equals("select")) {
            var label = FormActionBinding.associatedLabel(group, old, key);
            if (label == null && old.attr("aria-label").isBlank())
                throw new IllegalArgumentException("枚举控件标签已变化：" + field.label());
            values = old.select("option").stream()
                    .filter(option -> !option.hasAttr("disabled") && option.closest("optgroup[disabled]") == null
                            && !(option.hasAttr("value") && option.val().isBlank()))
                    .map(Element::val).toList();
            if (!values.equals(field.options()))
                throw new IllegalArgumentException("原枚举选项与审核后表单不一致：" + field.label());
            return old;
        }
        if (old == null || !old.hasClass("dropdown") || !group.getAllElements().contains(old))
            throw new IllegalArgumentException("原枚举控件证据已变化：" + field.label());
        var labels = old.parent().children().stream().filter(label -> label.is("label[for]")).toList();
        if (labels.size() != 1)
            throw new IllegalArgumentException("枚举控件标签已变化：" + field.label());
        var label = labels.getFirst();
        var options = old.select(".dropdown-menu li");
        values = options.stream().map(Element::text).toList();
        if (!values.equals(field.options()))
            throw new IllegalArgumentException("原枚举选项与审核后表单不一致：" + field.label());
        var select = new Element("select").attr("id", key);
        select.appendElement("option").attr("value", "").text("请选择");
        for (String value : values) select.appendElement("option").attr("value", value).text(value);
        label.attr("for", key);
        old.replaceWith(select);
        return select;
    }
}

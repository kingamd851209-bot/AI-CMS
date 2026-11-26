package com.xinhongru.cms.template;

import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.NativeImageFields;

import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

/** 严格识别一个直接图像与父级单URL背景的同址证据，不执行脚本。 */
final class LinkedImageBackground {
    private static final Pattern SINGLE =
            Pattern.compile(
                    "(?i)^\\s*background-image\\s*:\\s*url\\(\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s()'\"]+))\\s*\\)\\s*;?\\s*$");

    private LinkedImageBackground() {}

    static boolean standalone(Element element, String source) {
        if (!InlineBackgroundSource.isBindable(element.attr("style"), source)) return false;
        return hasNoMatchingImage(element, source);
    }

    static boolean controlledStandalone(
            Element element, TemplateFieldPlanService.Binding binding, boolean publicResources) {
        if (binding.sources().size() != 1
                || !binding.sources().getFirst().sourceId().startsWith("background-")
                || !expression(binding.fieldName()).equals(element.attr("th:style"))) return false;
        String style = element.attr("style");
        var match = SINGLE.matcher(style);
        if (!match.matches()) return false;
        String url = matchedUrl(match);
        if (!publicResources || !url.startsWith("ui:/")) return standalone(element, url);
        String path = url.substring(3);
        if (!InlineBackgroundSource.isBindable(style.replace(url, path), path)
                || !hasNoMatchingImage(element, url)) return false;
        try {
            return url.equals(EditorContentPreparation.packageReference("", url));
        } catch (IllegalArgumentException invalidReference) {
            return false;
        }
    }

    private static boolean hasNoMatchingImage(Element element, String source) {
        return element.select("img[src]").stream().noneMatch(image -> source.equals(image.attr("src")));
    }

    private static String matchedUrl(java.util.regex.Matcher match) {
        if (match.group(1) != null) return match.group(1);
        return match.group(2) != null ? match.group(2) : match.group(3);
    }

    static Element target(Element image, String source) {
        if (!image.tagName().equals("img")
                || image.parent() == null
                || source.isBlank()
                || image.attr("style").toLowerCase(java.util.Locale.ROOT).contains("background"))
            return null;
        Element parent = image.parent();
        for (Element other : parent.select("img")) {
            if (other == image) continue;
            if (other.parent() == parent
                    || other.attr("src").isBlank()
                    || source.equals(other.attr("src"))
                    || other.hasAttr("srcset")
                    || other.closest("picture") != null) return null;
        }
        var match = SINGLE.matcher(parent.attr("style"));
        if (!match.matches()) return null;
        return source.equals(matchedUrl(match)) ? parent : null;
    }

    static boolean controlled(Element element, String name) {
        if (!expression(name).equals(element.attr("th:style"))) return false;
        return element.children().stream()
                .anyMatch(
                        child ->
                                ("${cmsDraftMedia['" + name + "']}").equals(child.attr("th:src"))
                                        && target(child, child.attr("src")) == element);
    }

    static String expression(String name) {
        if (!ContentExtensionFields.isName(name) && !NativeImageFields.isName(name))
            throw new IllegalArgumentException("背景字段无效");
        String slot = "cmsDraftMedia['" + name + "']";
        if (!NativeImageFields.isName(name)) slot = "#uris.escapePath(" + slot + ")";
        return "${'background-image: url(\"' + " + slot + " + '\");'}";
    }

    static String validateReference(String html, String namespace, String name, String reference) {
        if (NativeImageFields.isName(name)
                && html.contains(" + " + namespace + "['" + name + "'] + ")
                && (reference.indexOf('"') >= 0
                        || reference.indexOf('\\') >= 0
                        || reference.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("图片地址包含背景引用不支持的字符");
        return reference;
    }
}

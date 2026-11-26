package com.xinhongru.security.upload;

import com.xinhongru.security.upload.ResourceReferenceResolver.Context;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 富文本在存储时保留逻辑引用，在最终HTML输出时解析；不改写普通正文。 */
@Service
public class ResourceHtml {
    private static final Pattern CSS =
            Pattern.compile(
                    "url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s)]*))\\s*\\)",
                    Pattern.CASE_INSENSITIVE);
    private final ResourceReferenceResolver resolver;

    public ResourceHtml(ResourceReferenceResolver resolver) {
        this.resolver = resolver;
    }

    public String render(String html, String code) {
        return transform(html, resolver.context(code), false);
    }

    /** 仅用于已校验来源与受控绑定的公开模板；保留原静态链接行为，不作为内容清洗入口。 */
    public String renderTemplate(String html, String code) {
        return transform(html, resolver.context(code), false, true);
    }

    public String canonical(String html, String code) {
        return transform(html, resolver.context(code), true);
    }

    public String canonicalValue(String value, String code) {
        var context = resolver.context(code);
        return value.lines()
                .map(line -> canonicalUrl(line.trim(), context))
                .collect(Collectors.joining("\n"));
    }

    private String canonicalUrl(String value, Context context) {
        String reference = context.canonical(value);
        if (reference.startsWith("assets:") || reference.startsWith("ui:"))
            context.resolve(reference);
        return reference;
    }

    private String transform(String html, Context context, boolean canonical) {
        return transform(html, context, canonical, false);
    }

    private static String renderUrl(String value, Context context, boolean controlledTemplate) {
        if (controlledTemplate && !value.startsWith("assets:") && !value.startsWith("ui:"))
            return value;
        return context.resolve(value);
    }

    private String transform(
            String html, Context context, boolean canonical, boolean controlledTemplate) {
        if (html == null || html.isBlank()) return html;
        if (!canonical && !html.contains("assets:") && !html.contains("ui:")) return html;
        boolean page = html.matches("(?is)^\\s*(<!doctype|<html).*");
        Document doc = page ? Jsoup.parse(html) : Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (Element element : doc.getAllElements()) {
            for (String attribute : new String[] {"src", "href", "poster"}) {
                if (!element.hasAttr(attribute)) continue;
                String value = element.attr(attribute);
                String mapped =
                        canonical
                                ? canonicalUrl(value, context)
                                : renderUrl(value, context, controlledTemplate);
                element.attr(attribute, mapped == null ? "" : mapped);
            }
            if (element.hasAttr("srcset")
                    && !element.attr("srcset").contains("data:")
                    && (canonical || element.attr("srcset").matches("(?s).*(assets:|ui:).*"))) {
                String set =
                        Arrays.stream(element.attr("srcset").split(","))
                                .map(
                                        part -> {
                                            String[] values = part.trim().split("\\s+", 2);
                                            String url =
                                                    canonical
                                                            ? canonicalUrl(values[0], context)
                                                            : renderUrl(
                                                                    values[0],
                                                                    context,
                                                                    controlledTemplate);
                                            return (url == null ? "" : url)
                                                    + (values.length == 2 ? " " + values[1] : "");
                                        })
                                .collect(Collectors.joining(", "));
                element.attr("srcset", set);
            }
            if (element.hasAttr("style")) {
                var matcher = CSS.matcher(element.attr("style"));
                StringBuffer changed = new StringBuffer();
                while (matcher.find()) {
                    String value =
                            matcher.group(1) != null
                                    ? matcher.group(1)
                                    : matcher.group(2) != null
                                            ? matcher.group(2)
                                            : matcher.group(3);
                    String url =
                            canonical
                                    ? canonicalUrl(value, context)
                                    : renderUrl(value, context, controlledTemplate);
                    matcher.appendReplacement(
                            changed,
                            Matcher.quoteReplacement("url(\"" + (url == null ? "" : url) + "\")"));
                }
                matcher.appendTail(changed);
                element.attr("style", changed.toString());
            }
        }
        return page ? doc.outerHtml() : doc.body().html();
    }
}

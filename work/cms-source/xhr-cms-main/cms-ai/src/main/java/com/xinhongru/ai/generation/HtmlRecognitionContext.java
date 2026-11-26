package com.xinhongru.ai.generation;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public final class HtmlRecognitionContext {
    private HtmlRecognitionContext() {}

    public static String navigation(String html, int maxLength) {
        var document = Jsoup.parse(html == null ? "" : html);
        document.select("script, style, noscript, svg").remove();
        var navigation = document.select("nav, [role=navigation]");
        StringBuilder result = new StringBuilder();
        for (Element element : navigation.isEmpty() ? document.select("body") : navigation) {
            if (result.length() + element.outerHtml().length() <= maxLength) {
                result.append(element.outerHtml()).append('\n');
            } else {
                // Keep complete links rather than cutting a navigation tree in the middle of
                // markup.
                for (Element link : element.select("a[href]")) {
                    String snippet = link.outerHtml();
                    if (result.length() + snippet.length() + 1 > maxLength) break;
                    result.append(snippet).append('\n');
                }
                result.append("[导航上下文达到大小限制]\n");
                break;
            }
        }
        return result.toString();
    }
}

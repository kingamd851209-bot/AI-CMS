package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** 共享一个面板的静态片段 GET 由原页脚本渲染，仅控制请求顺序与失败提示。 */
final class FragmentRequestGuard {
    private static final String MARK = "data-cms-fragment-tabs";
    private static final String SCRIPT_MARK = "data-cms-fragment-guard";
    private static final String LEGACY_SCRIPT_DIGEST =
            "d7df88c18b104adf28faf3fedd7d27a8d554508ad4101f9b10f2b65e627ba512";
    private static final String SCRIPT = script();
    private static final String SCRIPT_DIGEST = PublicBindingApplication.digest(SCRIPT);
    private FragmentRequestGuard() {}

    static String apply(String html, String code, String version, String sourcePath, UiPackageStorageService storage) {
        if (!html.contains("data-src") || !html.contains("jquery")) return html;
        var doc = Jsoup.parse(html);
        if (doc.selectFirst("base[href]") != null) return html;
        if (doc.selectFirst("[" + MARK + "]") != null) {
            var scripts = doc.select("body > script:not([src])").stream()
                    .filter(FragmentRequestGuard::managedScript).toList();
            if (scripts.size() != 1 || SCRIPT_DIGEST.equals(scripts.getFirst().attr(SCRIPT_MARK)))
                return html;
            writeScript(scripts.getFirst());
            doc.outputSettings().prettyPrint(false);
            return doc.outerHtml();
        }
        if (doc.selectFirst("[data-cms-fragment-path],[" + SCRIPT_MARK + "]") != null) return html;
        var groups = doc.select("[data-src]").stream().map(Element::parent).distinct()
                .filter(g -> g != null && g.childrenSize() >= 2 && g.childrenSize() <= 32
                        && g.children().stream().allMatch(e -> e.hasAttr("data-src") && !e.is("img,source,video,iframe"))
                        && g.closest("header,footer,nav") == null).toList();
        if (groups.isEmpty()) return html;
        var inspection = new TemplateFragmentInspectionService(storage).inspect(code, version, sourcePath);
        var original = Jsoup.parse(storage.readStaticPage(code, version, sourcePath).html());
        var reader = storage.openStaticPageReader(code, version);
        var targets = new LinkedHashMap<String,TemplateFragmentInspectionService.Candidate>();
        for (var candidate : inspection.candidates()) {
            if ("REVIEW".equals(candidate.status()) && "data-src".equals(candidate.attribute()))
                targets.put(candidate.triggerSelector(), candidate);
        }
        boolean modified = false;
        for (var group : groups) {
            var paths = new LinkedHashMap<Element,String>();
            for (var item : group.children()) {
                var candidate = targets.get(item.cssSelector());
                if (candidate == null || !item.attr("data-src").equals(candidate.rawTarget())
                        || paths.containsValue(candidate.targetPath())) break;
                paths.put(item, candidate.targetPath());
            }
            if (paths.size() != group.childrenSize()) continue;
            var first = original.selectFirst(group.child(0).cssSelector());
            if (first == null) continue;
            var defaultPage = reader.read(paths.values().iterator().next());
            var fragment = Jsoup.parseBodyFragment(defaultPage.html());
            if (fragment.body().childrenSize() != 1 || !fragment.select("script,form,iframe").isEmpty()) continue;
            var sample = fragment.body().child(0);
            if (sample.selectFirst("img[src]") == null || sample.getAllElements().size() > 500) continue;
            var prototype = structure(sample);
            boolean matches = false;
            for (var parent = first.parent(); parent != null && !parent.is("body,html"); parent = parent.parent()) {
                var panels = parent.getElementsByTag(sample.tagName()).stream().filter(el ->
                        !el.classNames().isEmpty() && el.classNames().equals(sample.classNames())
                                && el.getAllElements().size() == prototype.size() && structure(el).equals(prototype))
                        .limit(2).count();
                if (panels > 0) {
                    matches = panels == 1;
                    break;
                }
            }
            if (!matches) continue;
            group.attr(MARK, "");
            for (var pair : paths.entrySet()) pair.getKey().attr("data-cms-fragment-path", "/" + pair.getValue());
            modified = true;
        }
        if (!modified) return html;
        doc.outputSettings().prettyPrint(false);
        writeScript(doc.body().appendElement("script"));
        return doc.outerHtml();
    }

    private static boolean managedScript(Element element) {
        String digest = PublicBindingApplication.digest(element.data());
        return element.hasAttr(SCRIPT_MARK) ? digest.equals(element.attr(SCRIPT_MARK))
                : LEGACY_SCRIPT_DIGEST.equals(digest);
    }

    private static void writeScript(Element element) {
        element.attr(SCRIPT_MARK, SCRIPT_DIGEST).empty().appendChild(new DataNode(SCRIPT));
    }

    private static java.util.List<String> structure(Element root) {
        return root.getAllElements().stream()
                .map(el -> el.normalName() + "|" + el.className() + "|" + el.childrenSize()).toList();
    }

    private static String script() {
        try (var in = FragmentRequestGuard.class.getResourceAsStream("/cms-template/fragment-requests.js")) {
            if (in == null) throw new IllegalStateException("缺少片段请求脚本");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("片段请求脚本无法读取", e); }
    }
}

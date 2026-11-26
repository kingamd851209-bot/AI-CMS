package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** 将缺失视频占位和图集片段收束在已审核的媒体正文。 */
record ReviewedMediaLibrary(String galleryRoute, String videoRoute, List<Fragment> gallery, Video video) {
    private static final String MARK = "data-reviewed-media";
    private static final String TABS = """
            document.querySelectorAll('[data-gallery-tab]').forEach(function(button) {
              button.addEventListener('click', function() {
                const selected = button.dataset.galleryTab;
                document.querySelectorAll('[data-gallery-tab]').forEach(function(tab) {
                  const active = tab.dataset.galleryTab === selected;
                  tab.setAttribute('aria-pressed', active ? 'true' : 'false');
                  tab.closest('li').classList.toggle('on', active);
                });
                document.querySelectorAll('[data-gallery-panel]').forEach(function(panel) {
                  panel.hidden = panel.dataset.galleryPanel !== selected;
                });
                window.dispatchEvent(new Event('resize'));
              });
            });
            """;

    record Fragment(String source, String digest, String title, int images) {}
    record Video(String title, String source, String sha256, String poster,
                 int sampleCount, String sampleTitle, String samplePath) {}

    static java.util.function.UnaryOperator<String> read(Path workspace, TemplateFieldPlanService.Plan plan,
            UiPackageStorageService packages) {
        var rule = ReviewedReportCards.readRule(workspace, plan, "reviewed-media-library", ReviewedMediaLibrary.class);
        if (rule == null) return null;
        if (!local(rule.galleryRoute, "html") || !local(rule.videoRoute, "html")
                || (rule.gallery == null) == (rule.video == null))
            throw new IllegalArgumentException("媒体库缺少审核路线或类型");
        if (rule.video != null) {
            rule.verifyVideo(packages.getActivePath(plan.code()).orElseThrow());
            return html -> rule.apply(html, List.of());
        }
        if (rule.gallery.isEmpty() || rule.gallery.size() > 10)
            throw new IllegalArgumentException("图集分类超出审核范围");
        var fragments = new ArrayList<String>();
        for (var fragment : rule.gallery) {
            var source = packages.readStaticPage(plan.code(), plan.packageVersion(), fragment.source);
            if (!source.digest().equals(fragment.digest))
                throw new IllegalArgumentException("图集片段和审核摘要不一致");
            fragments.add(PublicBindingResources.rebase(source.html(), fragment.source));
        }
        return html -> rule.apply(html, fragments);
    }

    private static boolean local(String path, String extensions) {
        return path != null && path.matches("/(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_-]+\\.(?:" + extensions + ")");
    }

    private void verifyVideo(Path root) {
        if (!local(video.source, "mp4") || !local(video.poster, "jpe?g|png|webp") || video.sha256 == null
                || !video.sha256.matches("[a-f0-9]{64}") || video.title == null || video.title.isBlank()
                || video.sampleCount < 1 || video.sampleCount > 30)
            throw new IllegalArgumentException("视频缺少真实原包素材或样板证据");
        try {
            Path base = root.toRealPath();
            Path file = base.resolve(video.source.substring(1)).toRealPath();
            if (!file.startsWith(base) || !Files.isRegularFile(file) || Files.size(file) > 128 * 1024 * 1024L)
                throw new IllegalArgumentException("视频素材无法读取");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(video.sha256))
                throw new IllegalArgumentException("视频与审核摘要不一致");
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("视频素材无法校验", error);
        }
    }

    String apply(String html, List<String> fragments) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var main = only(document, "body > .main");
        if (main.hasAttr(MARK)) return html;
        var tabs = only(main, ".v_tab");
        var links = tabs.select("a[href]");
        if (!links.eachText().equals(List.of("图集", "视频")))
            throw new IllegalArgumentException("媒体资料库切换入口已变化");
        links.get(0).attr("href", galleryRoute);
        links.get(1).attr("href", videoRoute);
        if (video != null) {
            var sample = only(main, ".video_cont");
            var entries = sample.select("a.video_button[data-video]");
            if (entries.size() != video.sampleCount || entries.stream().anyMatch(link ->
                    !video.samplePath.equals(link.attr("data-video")) || !link.text().equals(video.sampleTitle)))
                throw new IllegalArgumentException("视频样板已发生变化");
            for (var sibling : new ArrayList<>(tabs.parent().children())) if (sibling != tabs) sibling.remove();
            var section = tabs.parent().appendElement("section").addClass("media-video").attr("aria-labelledby", "reviewed-media-title");
            section.appendElement("h3").addClass("fnt_30 bold").attr("id", "reviewed-media-title").text(video.title);
            var player = section.appendElement("video").attr("id", "jinyu-feature-video").attr("controls", "")
                    .attr("playsinline", "").attr("preload", "none").attr("poster", video.poster)
                    .attr("aria-labelledby", "reviewed-media-title");
            player.appendElement("source").attr("src", video.source).attr("type", "video/mp4");
            player.appendElement("a").attr("href", video.source).text("打开" + video.title + "视频");
            document.select("script[src$=/video_cont.js]").remove();
            main.attr(MARK, "video").appendElement("style").appendText(
                    "#jinyu-feature-video{display:block;width:min(100%,960px);height:auto;margin:20px auto;background:#111}"
                            + ".media-video h3{margin-top:30px;text-align:center}");
            return document.outerHtml();
        }

        var groups = main.select(".picture_tab li[data-src]");
        if (groups.size() != gallery.size() || fragments.size() != groups.size())
            throw new IllegalArgumentException("图集原分类数量已变化");
        var output = only(main, ".picture_list").empty().attr("aria-live", "polite");
        for (int index = 0; index < groups.size(); index++) {
            var expected = gallery.get(index);
            var group = groups.get(index);
            String fileName = Path.of(expected.source).getFileName().toString();
            if (!group.attr("data-src").equals(fileName) || !group.text().equals(expected.title))
                throw new IllegalArgumentException("图集名称或片段目标已变化");
            var contents = only(Jsoup.parseBodyFragment(fragments.get(index)), "body > ul");
            if (contents.select("img").size() != expected.images || !contents.select("script,form,iframe,object,a[href]").isEmpty())
                throw new IllegalArgumentException("图集片段包含未审核内容");
            for (var image : contents.select("img")) {
                String source = image.attr("src").replaceFirst("^ui:", "");
                if (!local(source, "jpe?g|png|webp")) throw new IllegalArgumentException("图集图片格式无效");
                image.attr("src", source).attr("alt", image.closest("dl").text()).attr("loading", "lazy");
            }
            String id = Integer.toString(index + 1);
            var panel = output.appendElement("section").attr("id", "gallery-" + id)
                    .attr("aria-labelledby", "gallery-tab-" + id).attr("data-gallery-panel", id);
            if (index != 0) panel.attr("hidden", "");
            panel.appendChild(contents);
            group.removeAttr("data-src");
            only(group, "a[href]").tagName("button").removeAttr("href").attr("type", "button")
                    .attr("id", "gallery-tab-" + id).attr("data-gallery-tab", id).attr("aria-controls", "gallery-" + id)
                    .attr("aria-pressed", Boolean.toString(index == 0));
        }
        document.select("script[src$=/ljx_function.js]").remove();
        main.attr(MARK, "gallery").appendElement("style").appendText(
                ".picture_tab button{display:block;width:100%;border:0;background:none;color:inherit;font:inherit;cursor:pointer}"
                        + ".picture_tab li:focus-within{outline:2px solid #8a1b24;outline-offset:3px}"
                        + ".picture_list [hidden]{display:none!important}");
        main.appendElement("script").append(TABS);
        return document.outerHtml();
    }

    private static Element only(Element parent, String selector) {
        var matches = parent.select(selector);
        if (matches.size() != 1) throw new IllegalArgumentException("媒体资料库位置不唯一：" + selector);
        return matches.first();
    }
}

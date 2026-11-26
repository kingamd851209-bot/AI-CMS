package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 只读发现DOM内包内页面引用，不将候选当成已证明的脚本调用，也不执行包内代码。 */
@Service
public class TemplateFragmentInspectionService {
    private static final List<String> ATTRIBUTES =
            List.of("data-src", "data-url", "data-load", "data-ajax");
    private final UiPackageStorageService storage;

    public TemplateFragmentInspectionService(UiPackageStorageService storage) {
        this.storage = storage;
    }

    public record Candidate(
            String triggerSelector,
            String label,
            String attribute,
            String rawTarget,
            String targetPath,
            String targetDigest,
            String status,
            String message) {}

    public record Inspection(
            String sourcePath, String digest, List<Candidate> candidates, List<String> warnings) {}

    private record Target(String digest, String message) {}

    public Inspection inspect(String code, String version, String sourcePath) {
        var source = storage.readStaticPage(code, version, sourcePath);
        var document = Jsoup.parse(source.html());
        var warnings = new ArrayList<>(source.warnings());
        warnings.add("仅发现DOM中的页面路径候选；须人工确认触发动作与加载区域。脚本拼接URL、远程接口及未列出的加载方式仍需人工检查。");
        var candidates = new ArrayList<Candidate>();
        var targets = new LinkedHashMap<String, Target>();
        boolean hasBase = !document.select("base[href]").isEmpty();
        int scanned = 0, remaining = 4 * 1024 * 1024;
        boolean deepSkipped = false;
        outer:
        for (var element : document.getAllElements()) {
            if (++scanned > 5000) {
                warnings.add("DOM超过5000个节点，尚有未检查区域。");
                break;
            }
            if (List.of("img", "video", "audio", "source", "iframe", "script", "link")
                    .contains(element.normalName())) continue;
            int depth = 0;
            for (var ancestor = element;
                    ancestor != null && depth <= 32;
                    ancestor = ancestor.parent()) depth++;
            if (depth > 32) {
                if (!deepSkipped) warnings.add("DOM深度超过32层的区域未检查，请人工复核。");
                deepSkipped = true;
                continue;
            }
            for (String attribute : ATTRIBUTES) {
                String raw = element.attr(attribute).strip();
                if (raw.isEmpty() || !looksLikePage(raw)) continue;
                if (candidates.size() == 50) {
                    warnings.add("片段引用超过50项，其余未检查。");
                    break outer;
                }
                String path = null;
                Target target;
                try {
                    if (hasBase) throw new IllegalArgumentException("存在base地址，须人工确认实际加载位置");
                    path = resolve(sourcePath, raw);
                    target = targets.get(path);
                    if (target == null) {
                        if (remaining <= 0) {
                            target = new Target(null, "已解析片段文本累计超过4MB，请单独检查该页面");
                        } else {
                            var page = storage.readStaticPage(code, version, path);
                            remaining -= page.html().getBytes(StandardCharsets.UTF_8).length;
                            target =
                                    remaining < 0
                                            ? new Target(null, "已解析片段文本累计超过4MB，请单独检查该页面")
                                            : new Target(
                                                    page.digest(), "包内页面可读取；请核对脚本是否在此处加载，再进入片段识别");
                        }
                        targets.put(path, target);
                    }
                } catch (IllegalArgumentException error) {
                    target =
                            new Target(
                                    null,
                                    hasBase
                                            ? "页面存在base地址，须人工确认实际加载位置"
                                            : "路径或片段不可安全读取，请人工检查（查询参数、片段定位、外部地址、缺失文件或服务器代码均不自动处理）");
                }
                // 失败文件也缓存；4MB限制成功解析的文本，每次实际读取另受storage的2MB上限约束。
                if (path != null) targets.putIfAbsent(path, target);
                String label = element.text();
                if (label.length() > 120) label = label.substring(0, 120);
                candidates.add(
                        new Candidate(
                                element.cssSelector(),
                                label,
                                attribute,
                                raw,
                                path,
                                target.digest(),
                                target.digest() == null ? "PENDING" : "REVIEW",
                                target.message()));
            }
        }
        if (candidates.isEmpty()) warnings.add("未发现可核对的DOM片段路径，不代表页面没有异步内容。");
        return new Inspection(
                sourcePath, source.digest(), List.copyOf(candidates), List.copyOf(warnings));
    }

    private static boolean looksLikePage(String value) {
        String path = value.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        return StaticPageSource.supports(path);
    }

    private static String resolve(String owner, String raw) {
        if (raw.startsWith("//")
                || raw.chars().anyMatch(c -> c <= 32 || "\\:%?#{}".indexOf(c) >= 0)
                || raw.length() > 1024) throw new IllegalArgumentException("仅支持无参数的包内字面路径");
        Path parent = Path.of(owner).getParent();
        Path target =
                (raw.startsWith("/")
                                ? Path.of(raw.substring(1))
                                : (parent == null ? Path.of(raw) : parent.resolve(raw)))
                        .normalize();
        String path = target.toString().replace('\\', '/');
        if (target.isAbsolute()
                || path.equals("..")
                || path.startsWith("../")
                || !StaticPageSource.supports(path)
                || path.equals(owner)) throw new IllegalArgumentException("片段路径越界或引用自身");
        return path;
    }
}

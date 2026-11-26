package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionHtml;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 正文初值仅做本地路径映射与既有清洗，保留独立原HTML证据。 */
public final class EditorContentPreparation {
    private final UiPackageStorageService storage;

    public EditorContentPreparation(UiPackageStorageService storage) {
        this.storage = storage;
    }

    public record Mapping(String attribute, String original, String resolved) {}

    public record Prepared(
            String originalHtml,
            String valueHtml,
            List<String> changes,
            List<Mapping> resourceMappings) {}

    public Prepared prepare(String code, String version, String sourcePath, String raw) {
        if (raw == null || raw.length() > 200000)
            throw new IllegalArgumentException("正文原文为空或超过200000字");
        var original = Jsoup.parseBodyFragment(raw);
        original.outputSettings().prettyPrint(false);
        String problem = EditorRegions.problem(original.body().tagName("div"));
        if (problem != null) throw new IllegalArgumentException(problem);
        var changes = new ArrayList<String>();
        var mappings = new ArrayList<Mapping>();
        String clean = ContentExtensionHtml.clean(raw);
        if (!clean.equals(raw)) changes.add("既有正文安全清洗已移除不允许的标签/属性/样式或规整格式；须核对原HTML与将写入HTML差异");
        var document = Jsoup.parseBodyFragment(clean);
        document.outputSettings().prettyPrint(false);
        int total = 0;
        for (var element : document.select("img[src],a[href]")) {
            String attribute = element.tagName().equals("img") ? "src" : "href";
            String value = element.attr(attribute);
            if (value.isBlank() || value.startsWith("#")) continue;
            if (value.startsWith("http:")
                    || value.startsWith("https:")
                    || value.startsWith("mailto:")) {
                if (attribute.equals("src"))
                    throw new IllegalArgumentException("正文图片为外部资源，未访问或导入，请先人工处理");
                changes.add("外部正文链接保留为内容证据；隔离预览禁止跳转，未访问外部地址");
                continue;
            }
            String mapped = packageReference(sourcePath, value);
            String path = mapped.substring(4).split("[?#]", 2)[0];
            byte[] bytes = storage.readInspectionResource(code, version, path);
            total += bytes.length;
            if (total > 12 * 1024 * 1024) throw new IllegalArgumentException("正文资源累计超过12MB，未导入");
            element.attr(attribute, mapped);
            mappings.add(new Mapping(attribute, value, mapped));
        }
        return new Prepared(
                raw, document.body().html(), List.copyOf(changes), List.copyOf(mappings));
    }

    /** 将入口页面相对地址转为包内逻辑引用；存在性和字段类型由原生资源校验负责。 */
    public static String packageReference(String sourcePath, String value) {
        if (value.contains("\\") || value.indexOf(0) >= 0 || value.startsWith("//"))
            throw new IllegalArgumentException("正文资源路径不安全");
        boolean logical = value.startsWith("ui:/");
        String pathPart = value.split("[?#]", 2)[0];
        String suffix = value.substring(pathPart.length());
        String decoded =
                UriUtils.decode(logical ? pathPart.substring(4) : pathPart, StandardCharsets.UTF_8);
        if (decoded.contains(":")
                || decoded.contains("%")
                || decoded.contains("\\")
                || decoded.indexOf(0) >= 0) throw new IllegalArgumentException("正文资源路径不安全或类型未支持");
        Path parent = Path.of(sourcePath).getParent();
        Path resolved =
                (logical || decoded.startsWith("/")
                                ? Path.of(decoded.startsWith("/") ? decoded.substring(1) : decoded)
                                : parent == null ? Path.of(decoded) : parent.resolve(decoded))
                        .normalize();
        String path = resolved.toString().replace('\\', '/');
        if (resolved.isAbsolute() || path.startsWith("..") || path.isBlank())
            throw new IllegalArgumentException("正文资源越出当前UI包");
        return "ui:/" + path + suffix;
    }
}

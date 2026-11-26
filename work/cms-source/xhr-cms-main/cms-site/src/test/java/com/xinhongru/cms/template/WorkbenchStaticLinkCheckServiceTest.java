package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

class WorkbenchStaticLinkCheckServiceTest {
    @TempDir Path temp;

    @Test
    void distinguishesMissingArtifactsFromOriginalPackageFallbackAndInteraction() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        var original = Files.createDirectory(temp.resolve("original"));
        write(output, "about/details/104.html", """
                <a href="../about/history.aspx">相对路径错层</a>
                <a href="/about/history.aspx">仅活动原包落点</a>
                <a href="/about/history.html?print=true#heading">发布产物</a>
                <a href="/about/history.html#missing">缺失跨页锚点</a>
                <a href="?sort=name#local">查询参数和本页锚点</a>
                <a href="#%E6%8A%A5%E5%91%8A">编码后的具名锚点</a>
                <a id="local" name="报告" href="##">缺失本页锚点</a>
                <area href="#!" onclick="return false">
                <a href="#" role="button">切换菜单</a>
                <a href="javascript:void(0)">脚本动作</a>
                <a href="https://other.example/about/history.aspx">站外链接</a>
                <a href="/about/downloads/#manual">目录首页</a>
                <a href="/about/news/list/9.html">已配置栏目路由缺产物</a>
                <a href="/about/%2e%2e/%2e%2e/private.html">编码越界路径</a>
                <a href="/about/news/list/9.html">重复链接</a>
                """);
        write(output, "about/history.html", "<h1 id=heading>发展历程</h1>");
        write(output, "about/downloads/index.html", "<h1 id=manual>下载</h1>");
        write(original, "about/history.aspx", "<!--#include file='public-header' -->");

        var result = WorkbenchStaticLinkCheckService.scan(output, original,
                WorkbenchStaticLinkCheckService.origin("https://cms.example"), List.of(
                        new WorkbenchStaticLinkCheckService.Route(
                                WorkbenchStaticLinkCheckService.routePattern("/about/details/{id}.html"),
                                "about/detail.aspx"),
                        new WorkbenchStaticLinkCheckService.Route(
                                WorkbenchStaticLinkCheckService.routePattern("/about/news/list/{pageIndex}.html"),
                                "about/news.aspx")));
        assertEquals("SCANNED", result.state());
        assertEquals(3, result.scannedPages());
        assertEquals(14, result.checkedLinks());
        assertFalse(result.truncated());
        var links = result.items().stream().collect(Collectors.toMap(
                WorkbenchStaticLinkCheckService.Item::href, Function.identity()));
        var missing = links.get("../about/history.aspx");
        assertEquals("MISSING_ARTIFACT", missing.state());
        assertEquals("/about/about/history.aspx", missing.targetPath());
        assertEquals("about/detail.aspx", missing.sourcePath());
        var fallback = links.get("/about/history.aspx");
        assertEquals("PACKAGE_FALLBACK", fallback.state());
        assertEquals("about/history.aspx", fallback.packagePath());
        assertEquals("MISSING_ANCHOR", links.get("/about/history.html#missing").state());
        assertEquals("MISSING_ANCHOR", links.get("##").state());
        assertEquals("DYNAMIC", links.get("#").state());
        assertEquals("DYNAMIC", links.get("javascript:void(0)").state());
        assertEquals("EXTERNAL", links.get("https://other.example/about/history.aspx").state());
        assertTrue(links.get("/about/news/list/9.html").reason().contains("已配置栏目路由"));
        assertEquals("UNVERIFIED", links.get("/about/%2e%2e/%2e%2e/private.html").state());
        assertFalse(links.containsKey("/about/history.html?print=true#heading"));
        assertFalse(links.containsKey("?sort=name#local"));
        assertFalse(links.containsKey("#%E6%8A%A5%E5%91%8A"));
        assertFalse(links.containsKey("/about/downloads/#manual"));
    }

    @Test
    void onlyOffersPublicLinkReviewForSupportedOriginalAnchorHrefs() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        write(output, "published.html", """
                <area href="..//service/exhibition.aspx">
                <a href="..//service/exhibition.aspx">可审核的原 href</a>
                <a href=" ..//service/exhibition.aspx ">原地址含空白</a>
                <a href="//cms.example/missing.html">同站但非原包相对路径</a>
                <a href="#missing">缺少页内锚点</a>
                <a href="../catalog/index.aspx#sec02">跨页面分区入口</a>
                """);

        var result = WorkbenchStaticLinkCheckService.scan(output, null,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of(
                        new WorkbenchStaticLinkCheckService.Route(
                                WorkbenchStaticLinkCheckService.routePattern("/published.html"), "source.aspx")));

        assertEquals(6, result.items().size());
        assertEquals(List.of("..//service/exhibition.aspx", "../catalog/index.aspx#sec02"), result.items().stream()
                .map(WorkbenchStaticLinkCheckService.Item::reviewHref)
                .filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void usesOriginalDirectoryAsEvidenceAndSuggestsOnlyVerifiedPublishedTargets() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        var original = Files.createDirectory(temp.resolve("original"));
        String originalLinks = """
                <a href="../about/history.aspx#heading">正式路由已验证</a>
                <a href="../resources/manual.pdf">仅原包存在</a>
                <a href="../resources/events.aspx">动态路由</a>
                """;
        write(original, "about/detail.aspx", originalLinks);
        write(output, "published/details/104.html", originalLinks
                + "<a href='../resources/other.pdf'>仅输出页含此链接</a>");
        write(original, "about/history.aspx", "<h1 id=heading>发展历程</h1>");
        write(original, "resources/manual.pdf", "manual");
        write(original, "resources/events.aspx", "<h1>活动</h1>");
        write(original, "resources/other.pdf", "other");
        write(output, "history/index.html", "<h1 id=heading>发展历程</h1>");
        write(output, "events/1.html", "<h1>活动</h1>");

        var result = WorkbenchStaticLinkCheckService.scan(output, original,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of(
                        new WorkbenchStaticLinkCheckService.Route("/published/details/{id}.html", "about/detail.aspx"),
                        new WorkbenchStaticLinkCheckService.Route("/history/", "about/history.aspx"),
                        new WorkbenchStaticLinkCheckService.Route("/manual/", null),
                        new WorkbenchStaticLinkCheckService.Route("/events/{id}.html", "resources/events.aspx")));
        var links = result.items().stream().collect(Collectors.toMap(
                WorkbenchStaticLinkCheckService.Item::href, Function.identity()));
        var history = links.get("../about/history.aspx#heading");
        assertEquals("MISSING_ARTIFACT", history.state());
        assertEquals("/published/about/history.aspx", history.targetPath());
        assertEquals("about/history.aspx", history.packagePath());
        assertEquals("../about/history.aspx#heading", history.reviewHref());
        assertTrue(history.reason().contains("/history/#heading"));
        var manual = links.get("../resources/manual.pdf");
        assertEquals("resources/manual.pdf", manual.packagePath());
        assertTrue(manual.reason().contains("核对正式落点"));
        assertTrue(links.get("../resources/events.aspx").reason().contains("核对正式落点"));
        assertNull(links.get("../resources/other.pdf").packagePath());
    }

    @Test
    void avoidsDirectorySuggestionsForBaseOverridesExternalOrEscapingPaths() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        var original = Files.createDirectory(temp.resolve("original"));
        String relative = "<a href='../docs/guide.html'>原包说明</a>";
        write(original, "old/guide.aspx", relative);
        write(original, "old/base.aspx", "<base href='/somewhere/'>" + relative);
        write(original, "docs/guide.html", "<h1>说明</h1>");
        write(output, "released/public/one.html", "<base href='/released/sub/'>" + relative);
        write(output, "released/public/two.html", relative);
        write(output, "released/public/three.html", """
                <a href="//external.example/docs/guide.html">外链</a>
                <a href="../../../../docs/guide.html">越界</a>
                <a href="../%2fdocs/guide.html">编码斜杠</a>
                """);

        var result = WorkbenchStaticLinkCheckService.scan(output, original,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of(
                        new WorkbenchStaticLinkCheckService.Route("/released/public/one.html", "old/guide.aspx"),
                        new WorkbenchStaticLinkCheckService.Route("/released/public/two.html", "old/base.aspx"),
                        new WorkbenchStaticLinkCheckService.Route("/released/public/three.html", "old/guide.aspx")));
        assertEquals(5, result.items().size());
        assertTrue(result.items().stream().allMatch(item -> item.packagePath() == null));
    }

    @Test
    void respectsHtmlBaseAndExplicitSiteOrigin() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        write(output, "index.html", """
                <base href="/about/">
                <a href="history.html">同站相对链接</a>
                <a href="https://cms.example:443/about/history.html#ok">同站绝对链接</a>
                <a href="//elsewhere.example/about/history.html">站外无协议链接</a>
                """);
        write(output, "about/history.html", "<h1 id=ok>发展历程</h1>");
        var result = WorkbenchStaticLinkCheckService.scan(output, null,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of());
        assertEquals(3, result.checkedLinks());
        assertEquals(1, result.items().size());
        assertEquals("EXTERNAL", result.items().getFirst().state());
    }

    @Test
    void reportsRepeatedSlashesWithoutAcceptingTheCollapsedArtifact() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        write(output, "about/index.html", """
                <a id="section" href="..//service/exhibition.aspx">晨光参展信息</a>
                <a href="https://cms.example/service//exhibition.aspx#booth">同站绝对地址</a>
                <a href="//cms.example/service//exhibition.aspx">同站无协议地址</a>
                <a href="//other.example/service//exhibition.aspx">外部地址</a>
                <a href="../service/exhibition.aspx?redirect=//directory#booth">正常相对地址</a>
                <a href="#section">正常页内锚点</a>
                """);
        write(output, "service/exhibition.aspx", "<h1 id=booth>参展信息</h1>");
        write(output, "base.html", """
                <base href="/service//">
                <a href="exhibition.aspx">继承基准路径</a>
                <a href="/service/exhibition.aspx">覆盖基准路径</a>
                """);

        var result = WorkbenchStaticLinkCheckService.scan(output, null,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of(
                        new WorkbenchStaticLinkCheckService.Route(
                                WorkbenchStaticLinkCheckService.routePattern("/about/index.html"), "about/index.aspx")));

        assertEquals(8, result.checkedLinks());
        assertEquals(5, result.items().size());
        var links = result.items().stream().collect(Collectors.toMap(
                WorkbenchStaticLinkCheckService.Item::href, Function.identity()));
        var morning = links.get("..//service/exhibition.aspx");
        assertEquals("UNVERIFIED", morning.state());
        assertEquals("about/index.html", morning.outputPath());
        assertEquals("about/index.aspx", morning.sourcePath());
        assertEquals("//service/exhibition.aspx", morning.targetPath());
        assertTrue(morning.reason().contains("连续斜杠"));
        assertEquals("UNVERIFIED", links.get("https://cms.example/service//exhibition.aspx#booth").state());
        assertEquals("booth", links.get("https://cms.example/service//exhibition.aspx#booth").fragment());
        assertEquals("UNVERIFIED", links.get("//cms.example/service//exhibition.aspx").state());
        assertEquals("EXTERNAL", links.get("//other.example/service//exhibition.aspx").state());
        assertEquals("/service//exhibition.aspx", links.get("exhibition.aspx").targetPath());
    }

    @Test
    void redundantLeadingSlashesRespectTheBrowserHostBoundary() throws IOException {
        var output = Files.createDirectory(temp.resolve("published"));
        write(output, "index.html", "<a href='///outside.example/page.html'>外链</a>");
        write(output, "outside.example/page.html", "<p>站内同名文件</p>");
        var report = WorkbenchStaticLinkCheckService.scan(output, null,
                WorkbenchStaticLinkCheckService.origin("cms.example"), List.of());
        assertEquals(1, report.items().size());
        assertEquals("EXTERNAL", report.items().getFirst().state());
    }

    @Test
    void emptyOutputDoesNotScanTheOriginalPackage() throws IOException {
        var output = Files.createDirectory(temp.resolve("output"));
        var original = Files.createDirectory(temp.resolve("original"));
        write(original, "index.html", "<a href='missing.html'>仅原包</a>");
        var result = WorkbenchStaticLinkCheckService.scan(output, original,
                WorkbenchStaticLinkCheckService.origin(null), List.of());
        assertEquals("NO_OUTPUT", result.state());
        assertEquals(0, result.scannedPages());
        assertTrue(result.items().isEmpty());
    }

    private static void write(Path root, String path, String content) throws IOException {
        var file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

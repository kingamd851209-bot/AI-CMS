package com.xinhongru.cms.uipackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticPageSourceValidationTest {
    @TempDir Path folder;

    @Test
    void deliveryAssetsAreVersionScopedAndExcludedFromPackageEvidence() throws IOException {
        var storage = new UiPackageStorageService(folder);
        var first = Files.createDirectories(folder.resolve("site/v1/js"));
        var second = Files.createDirectories(folder.resolve("site/v2"));
        Files.writeString(first.resolve("index.js"), "original");
        var overlay = Files.createDirectories(folder.resolve("site/.delivery-assets/v1/js"));
        var revised = Files.writeString(overlay.resolve("index.js"), "delivery");
        Files.writeString(overlay.resolve("other.js"), "supplement");
        Files.writeString(overlay.getParent().resolve("index.html"), "page");

        assertEquals(revised.toRealPath(), storage.deliveryAsset("site", first.getParent(), "js/index.js").orElseThrow());
        assertTrue(storage.deliveryAsset("site", second, "js/index.js").isEmpty());
        assertTrue(storage.deliveryAsset("site", first.getParent(), "index.html").isEmpty());
        assertTrue(storage.deliveryAsset("site", first.getParent(), "js/../js/index.js").isEmpty());
        assertEquals("original", new String(storage.readInspectionResource("site", "v1", "js/index.js"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(java.util.List.of("js/index.js"),
                storage.inspectionFiles("site", "v1").stream().map(UiPackageStorageService.InspectionFile::path).toList());
    }

    @Test
    void rejectsServerCodeAndUnclosedIncludesInUnexpandedPages() throws IOException {
        var source = folder.resolve("index.html");
        for (String illegal : new String[] {
                "<% reject %>", "<p runat='server'>正文</p>", "<!--#include file='missing.html'"
        }) {
            Files.writeString(source, illegal);
            assertThrows(IllegalArgumentException.class, () -> StaticPageSource.read(folder, "index.html"));
        }
    }

    @Test
    void rejectsServerCodeCreatedOnlyAfterJoiningIncludes() throws IOException {
        Files.writeString(folder.resolve("index.html"), "<<!--#include file='part.html'-->");
        var part = folder.resolve("part.html");
        Files.writeString(part, "p>普通内容</p>");
        assertEquals("<p>普通内容</p>", StaticPageSource.read(folder, "index.html").html());

        Files.writeString(part, "% reject %>");
        var error = assertThrows(IllegalArgumentException.class, () -> StaticPageSource.read(folder, "index.html"));
        assertTrue(error.getMessage().contains("ASP"));
    }

    @Test
    void rechecksSameSizeReplacementsAndMissingDependenciesOnTheNextRead() throws IOException {
        var storage = new UiPackageStorageService(folder);
        var pkg = Files.createDirectories(folder.resolve("site/v1"));
        Files.writeString(pkg.resolve("index.html"), "<!--#include file='part.html'-->");
        Files.writeString(pkg.resolve("other.html"), "other <!--#include file='part.html'-->");
        var part = pkg.resolve("part.html");
        Files.writeString(part, "<p>abc</p>");
        var time = Files.getLastModifiedTime(part);

        var firstReader = storage.openStaticPageReader("site", "v1");
        var first = firstReader.read("index.html");
        assertEquals("other <p>abc</p>", firstReader.read("other.html").html());
        assertEquals(first, StaticPageSource.read(pkg, "index.html"));

        Files.writeString(part, "<p>def</p>");
        Files.setLastModifiedTime(part, time);
        var changed = storage.openStaticPageReader("site", "v1").read("index.html");
        assertEquals("<p>def</p>", changed.html());
        assertNotEquals(first.digest(), changed.digest());

        Files.writeString(part, "<!--#include file='index.html'-->");
        assertThrows(IllegalArgumentException.class,
                () -> storage.openStaticPageReader("site", "v1").read("index.html"));
        Files.delete(part);
        assertThrows(IllegalArgumentException.class,
                () -> storage.openStaticPageReader("site", "v1").read("index.html"));
    }

    @Test
    void countsCachedDependenciesTowardEveryPageLimit() throws IOException {
        var storage = new UiPackageStorageService(folder);
        var pkg = Files.createDirectories(folder.resolve("site/v1"));
        String include = "<!--#include file='part.html'-->";
        Files.writeString(pkg.resolve("part.html"), "a".repeat(800_000));
        Files.writeString(pkg.resolve("allowed.html"), include.repeat(2));
        Files.writeString(pkg.resolve("rejected.html"), include.repeat(3));
        Files.writeString(pkg.resolve("many.html"), "<!--#include file='small.html'-->".repeat(101));
        Files.writeString(pkg.resolve("small.html"), "x");
        for (int depth = 0; depth <= 8; depth++)
            Files.writeString(pkg.resolve("depth" + depth + ".html"),
                    "<!--#include file='depth" + (depth + 1) + ".html'-->");
        Files.writeString(pkg.resolve("depth9.html"), "end");
        var reader = storage.openStaticPageReader("site", "v1");

        assertEquals(1_600_000, reader.read("allowed.html").html().length());
        var bytes = assertThrows(IllegalArgumentException.class, () -> reader.read("rejected.html"));
        assertTrue(bytes.getMessage().contains("2MB"));
        var count = assertThrows(IllegalArgumentException.class, () -> reader.read("many.html"));
        assertTrue(count.getMessage().contains("100"));
        assertEquals("end", reader.read("depth1.html").html());
        var depth = assertThrows(IllegalArgumentException.class, () -> reader.read("depth0.html"));
        assertTrue(depth.getMessage().contains("8层"));
    }

    @Test
    void cachedContentCannotBypassJoiningChecksOrPackageContainment() throws IOException {
        var storage = new UiPackageStorageService(folder);
        var pkg = Files.createDirectories(folder.resolve("site/v1"));
        Files.writeString(pkg.resolve("safe.html"), "% reject %>");
        Files.writeString(pkg.resolve("bad.html"), "<<!--#include file='safe.html'-->");
        Files.writeString(pkg.resolve("escape.html"), "<!--#include file='../outside.html'-->");
        Files.writeString(pkg.getParent().resolve("outside.html"), "<p>outside</p>");

        var reader = storage.openStaticPageReader("site", "v1");
        assertEquals("% reject %>", reader.read("safe.html").html());
        var error = assertThrows(IllegalArgumentException.class, () -> reader.read("bad.html"));
        assertTrue(error.getMessage().contains("ASP"));
        assertThrows(IllegalArgumentException.class, () -> reader.read("escape.html"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.openStaticPageReader("site", "v1").read("bad.html"));
    }

    @Test
    void doesNotReuseAnEarlierPathAuthorizationAfterSymlinkReplacement() throws IOException {
        var storage = new UiPackageStorageService(folder);
        var pkg = Files.createDirectories(folder.resolve("site/v1"));
        var page = pkg.resolve("index.html");
        Files.writeString(page, "<p>path</p>");
        var original = storage.openStaticPageReader("site", "v1").read("index.html");
        assertEquals("<p>path</p>", original.html());

        var outside = Files.writeString(folder.resolve("outside.html"), original.html());
        Files.delete(page);
        try {
            Files.createSymbolicLink(page, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "测试环境无法创建符号链接");
        }
        assertThrows(IllegalArgumentException.class,
                () -> storage.openStaticPageReader("site", "v1").read("index.html"));
    }
}

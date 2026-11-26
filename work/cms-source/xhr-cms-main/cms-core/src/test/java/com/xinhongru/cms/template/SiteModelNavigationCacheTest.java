package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteModelNavigationCacheTest {
    @TempDir Path packages;

    @Test
    void reusesResultsOnlyWhileTheExpandedPagesAndTheirPathsMatch() throws IOException {
        var folder = Files.createDirectories(packages.resolve("site/v1"));
        var source = folder.resolve("index.html");
        var target = folder.resolve("story.html");
        var snippet = folder.resolve("navigation.html");
        Files.writeString(source, "<!--#include file='navigation.html'-->");
        Files.writeString(target, "<main><h1>内容</h1></main>");
        Files.writeString(snippet, header("旧栏目"));
        var originalMtime = Files.getLastModifiedTime(snippet);
        var evidence = new SiteModelNavigationEvidence(new UiPackageStorageService(packages));

        var original = evidence.read("site", "v1");
        assertEquals("旧栏目", original.getFirst().name());
        assertEquals("story.html", original.getFirst().sourcePath());
        assertSame(original, evidence.read("site", "v1"));
        assertThrows(UnsupportedOperationException.class, original::clear);
        assertThrows(UnsupportedOperationException.class, original.getFirst().observations()::clear);

        Files.writeString(snippet, header("新栏目"));
        Files.setLastModifiedTime(snippet, originalMtime);
        var changed = evidence.read("site", "v1");
        assertNotSame(original, changed);
        assertEquals("新栏目", changed.getFirst().name());
        assertSame(changed, evidence.read("site", "v1"));

        Files.move(target, folder.resolve("renamed.html"));
        var renamed = evidence.read("site", "v1");
        assertNotSame(changed, renamed);
        assertNull(renamed.getFirst().sourcePath());

        Files.writeString(snippet, "<% reject %>");
        assertThrows(IllegalArgumentException.class, () -> evidence.read("site", "v1"));
    }

    @Test
    void keepsIdenticalPackagesSeparateAndEvictsOldVersions() throws IOException {
        var evidence = new SiteModelNavigationEvidence(new UiPackageStorageService(packages));
        for (int version = 1; version <= 9; version++) {
            var folder = Files.createDirectories(packages.resolve("site/v" + version));
            Files.writeString(folder.resolve("index.html"), header("导航"));
        }
        var first = evidence.read("site", "v1");
        var second = evidence.read("site", "v2");
        assertEquals(first, second);
        assertNotSame(first, second);
        for (int version = 3; version <= 9; version++) evidence.read("site", "v" + version);
        assertSame(second, evidence.read("site", "v2"));
        var reloaded = evidence.read("site", "v1");
        assertEquals(first, reloaded);
        assertNotSame(first, reloaded);
    }

    private static String header(String label) {
        return "<header><nav><ul><li><a href='story.html'>" + label + "</a></li></ul></nav></header>";
    }
}

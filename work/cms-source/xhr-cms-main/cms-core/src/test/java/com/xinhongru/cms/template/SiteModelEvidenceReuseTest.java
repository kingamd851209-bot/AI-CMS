package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.cms.uipackage.UiPackageStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteModelEvidenceReuseTest {
    @TempDir Path root;

    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void identicalInputReusesAnalysisButRereadsTheAuthorizedSource() throws Exception {
        write("demo/v1/index.html", "<main><h1>测试</h1><p>原文</p></main>");
        var storage = spy(new UiPackageStorageService(root));
        var service = new SiteModelEvidenceService(storage);
        var first = service.page("demo", "v1", "index.html");
        clearInvocations(storage);
        assertSame(first, service.page("demo", "v1", "index.html"));
        verify(storage, times(1)).readStaticPage("demo", "v1", "index.html");
        var catalog = service.catalog("demo", "v1");
        assertSame(catalog.pages().getFirst(), service.catalog("demo", "v1").pages().getFirst());
    }

    @Test
    void navigationAndPlanUseTheSamePageReadAndRecheckScriptDependencies() throws Exception {
        write("demo/v1/index.html", "<h1>测试</h1><p>正文</p><script src='app.js'></script>");
        var script = write("demo/v1/app.js", "let n=2;");
        var storage = spy(new UiPackageStorageService(root));
        var navigation = new SiteModelNavigationEvidence(storage);
        var service = new SiteModelEvidenceService(storage);
        var batch = navigation.readSources("demo", "v1");
        var first = service.page(batch, "index.html");
        service.catalog(batch);
        navigation.read(batch);
        verify(storage, times(1)).inspectionFiles("demo", "v1");
        verify(storage, never()).readStaticPage(anyString(), anyString(), anyString());
        assertSame(first, service.page(batch, "index.html"));
        var time = Files.getLastModifiedTime(script);
        Files.writeString(script, "let n=3;");
        Files.setLastModifiedTime(script, time);
        assertNotSame(first, service.page(navigation.readSources("demo", "v1"), "index.html"));
    }

    @Test
    void includesPathsAndSitesRemainDistinctEvenWhenSizeAndTimeDoNotChange() throws Exception {
        String page = "<!--#include file=\"part.html\"--><a href='detail.html'>详情</a>";
        write("demo/v1/index.html", page);
        Path fragment = write("demo/v1/part.html", "<header>共用</header><h1>甲一</h1>");
        FileTime time = Files.getLastModifiedTime(fragment);
        write("other/v1/index.html", "<h1>独立站</h1>");
        var storage = new UiPackageStorageService(root);
        var service = new SiteModelEvidenceService(storage);
        var first = service.page("demo", "v1", "index.html");
        var catalog = service.catalog("demo", "v1");
        write("demo/v1/part.html", "<header>共用</header><h1>乙二</h1>");
        Files.setLastModifiedTime(fragment, time);
        var edited = service.page("demo", "v1", "index.html");
        assertNotEquals(first.digest(), edited.digest());
        assertNotEquals(catalog.pages(), service.catalog("demo", "v1").pages());
        assertNotEquals(edited.digest(), service.page("other", "v1", "index.html").digest());
        write("demo/v1/detail.html", "<h1>详情</h1>");
        var linked = service.page("demo", "v1", "index.html");
        assertTrue(linked.links().stream().anyMatch(link -> "detail.html".equals(link.targetPath())));
        Files.delete(fragment);
        assertThrows(IllegalArgumentException.class, () -> service.page("demo", "v1", "index.html"));
        assertTrue(service.catalog("demo", "v1").pages().stream()
                .anyMatch(p -> p.sourcePath().equals("index.html") && !p.analyzed()));
    }
}

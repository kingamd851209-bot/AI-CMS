package com.xinhongru.cms.template;

import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.properties.CmsBuilderHtmlConfigProperties;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.site.render.StaticRenderProof;
import com.xinhongru.site.storage.SiteStoragePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicBindingNativeHomeTest {
    @TempDir Path directory;

    @Test
    void inheritsOnlyPreviouslyApprovedAliasFromCurrentPublishedOwnerAndVerifiedFiles() throws Exception {
        long site = 9L;
        String code = "TEST";
        String version = "v1";
        String nativePath = "home/native.html";
        String maintenanceUrl = "/home-source.aspx";
        var route = menu(10, site, 0, nativePath);
        var owner = menu(12, site, 3, nativePath);
        owner.setListPageUrl(maintenanceUrl);
        var project = new SiteProjectEntity();
        project.setId(site);
        project.setProjectCode(code);
        project.setRootMenuId(route.getId());
        project.setUiPackageVersion(version);
        var projects = mock(SiteProjectMapper.class);
        when(projects.selectById(site)).thenReturn(project);
        var menus = mock(OneShotMapper.class);
        when(menus.selectPublicBindingMenu(site, 12L)).thenReturn(owner);
        var proofs = new SiteWorkbenchContextService(projects, directory.resolve("proofs").toString());
        var templates = new TemplateStorageService(directory.resolve("templates").toString());
        var publishing = new CmsBuilderHtmlConfigProperties();
        publishing.setSaveRoot(directory.resolve("published").toString());
        var receipts = directory.resolve("receipts");
        var service = new PublicBindingRootRoutes(projects, menus, templates, proofs, new SiteStoragePathResolver(),
                publishing, true, receipts.toString());

        String key = "07354d04-0ed2-404f-b87e-a7bbf3ea4a90";
        String originalHtml = "<html><body>old</body></html>";
        String originalDigest = PublicBindingApplication.digest(originalHtml);
        String originalPath = "cms-applied/" + key + "/" + originalDigest + ".html";
        var binding = new TemplateFieldPlanService.Binding("header", "header", "text", List.of(),
                "USE", "singleLine1", 80, 0, "", "", false);
        new PublicBindingApplication(key, site, 12L, "plan", version, 0,
                ContentFieldConfigDigest.of(owner), List.of(binding), originalPath, originalDigest,
                "legacy.html", null, null, 10L, List.of("/index.aspx")).write(receipts, code);
        String nativeHtml = "<html><body>native</body></html>";
        Path nativeFile = templates.resolveTemplateDir(code).resolve(nativePath);
        write(nativeFile, nativeHtml);
        write(templates.resolveTemplateDir(code).resolve(originalPath), originalHtml);
        write(receipts.resolve(code).resolve("native-home.json"), """
                {"previousTemplate":"%s","template":"%s","templateDigest":"%s"}
                """.formatted(originalPath, nativePath, PublicBindingApplication.digest(nativeHtml)));
        Path output = directory.resolve("published").resolve(code).resolve(maintenanceUrl.substring(1));
        assertTrue(service.nativeAliases(site, code, route).isEmpty());
        write(output, nativeHtml);
        var proof = StaticRenderProof.of(site, owner.getId(), false, nativePath);
        proofs.recordStaticArtifact(site, code, output, proof, nativeHtml);
        assertEquals(List.of("/index.aspx"), service.aliases(site, code, route));
        assertTrue(service.nativeAliases(99L, code, route).isEmpty());
        assertTrue(service.nativeAliases(site, code, owner).isEmpty());
        owner.setStatus(0);
        assertTrue(service.nativeAliases(site, code, route).isEmpty());
        owner.setStatus(3);
        write(output, "tampered");
        assertTrue(service.nativeAliases(site, code, route).isEmpty());
        write(output, nativeHtml);
        write(nativeFile, "changed template");
        assertTrue(service.nativeAliases(site, code, route).isEmpty());
    }

    private static OneshotEntity menu(int id, long site, int status, String template) {
        var menu = new OneshotEntity();
        menu.setId(id);
        menu.setSiteProjectId(site);
        menu.setSiteRootId(10);
        menu.setParentId(10);
        menu.setPageType(0);
        menu.setHasFrontChannel(true);
        menu.setStatus(status);
        menu.setListPageTemplate(template);
        return menu;
    }

    private static void write(Path file, String value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }
}

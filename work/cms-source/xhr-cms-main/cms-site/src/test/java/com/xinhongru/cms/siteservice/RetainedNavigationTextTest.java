package com.xinhongru.cms.siteservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedNavigationTextTest {
    @TempDir Path root;

    @Test
    void requiresTheApprovedVersionRoutePositionLabelAndAddress() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {
                  "sample": {
                    "v1": [{
                      "routes": ["/one.html"], "renderAliases": ["/one.aspx"],
                      "menuId": 3, "selector": "nav > a", "label": "Archive",
                      "href": "../old.aspx", "reason": "Approved as text"
                    }]
                  }
                }
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String original = "<nav><a href=\"../old.aspx\">Archive</a></nav>"
                + "<a href=\"../old.aspx\">Archive</a>";
        String published = "<nav><a aria-disabled=\"true\">Archive</a></nav>"
                + "<a href=\"../old.aspx\">Archive</a>";
        assertEquals(published, rules.apply(original, active, "/one.html"));
        assertEquals(published, rules.apply(original, active, "/one.aspx"));
        assertEquals(java.util.List.of("/one.html"), rules.reviewRoutes("v1", 3, "Archive"));
        assertNull(rules.mismatch("v1", "/one.html", published));
        assertNotNull(rules.mismatch("v1", "/one.html", original));
        assertEquals(original, rules.apply(original, active, "/other.html"));
        assertEquals(original, rules.apply(original, Files.createDirectory(root.resolve("v2")), "/one.html"));
        String changed = original.replace("../old.aspx", "/different.html");
        assertEquals(changed, rules.apply(changed, active, "/one.html"));
        String ambiguous = "<nav><a href='../old.aspx'>Archive</a><a href='../old.aspx'>Archive</a></nav>";
        assertEquals(ambiguous, rules.apply(ambiguous, active, "/one.html"));
        String activePlaceholder = "<nav data-src='fragment.aspx'><a>Archive</a></nav>";
        assertNotNull(rules.mismatch("v1", "/one.html", activePlaceholder));
    }

    @Test
    void appliesExternalLinkAndRemovesOnlyTheApprovedEmptyWrapper() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample": {"v1": [
                    {"routes":["/"],"selector":"nav > a","label":"Official","href":"##",
                     "reason":"Reviewed","externalHref":"https://example.org/section/index.html","menuId":8},
                    {"routes":["/"],"selector":".cta","label":"Explore","href":"##",
                     "reason":"Reviewed","removeWrapper":true}
                ]}}
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String original = "<nav><a href='##'>Official</a></nav><div class='cta'><a href='##'>Explore</a></div><p>Body</p>";
        String published = "<nav><a href=\"https://example.org/section/index.html\">Official</a></nav><p>Body</p>";
        assertEquals(published, rules.apply(original, active, "/"));
        assertEquals("https://example.org/section/index.html", rules.reviewedExternalDestination("v1", 8, "Official"));
        assertNull(rules.mismatch("v1", "/", published));
        String changed = original.replace("</div>", "<span>Keep</span></div>");
        String result = rules.apply(changed, active, "/");
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("<span>Keep</span>"));
        assertNotNull(rules.mismatch("v1", "/", result));
    }

    @Test
    void changesOnlyTheHrefOfAnApprovedStyledInternalLink() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample": {"v1":[{"routes":["/"],"selector":".button","label":"More","href":"old.aspx",
                 "reason":"Reviewed","internalHref":"/new.html"}]}}
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String original = "<a class='button href=&quot;class&quot;' href='old.aspx' target='_blank'>More</a>";
        String published = "<a class='button href=&quot;class&quot;' href=\"/new.html\" target='_blank'>More</a>";
        assertEquals(published, rules.apply(original, active, "/"));
        assertNull(rules.mismatch("v1", "/", published));
        String unexpectedTarget = original.replace("_blank", "frame");
        assertEquals(unexpectedTarget, rules.apply(unexpectedTarget, active, "/"));
        String scripting = original.replace("target=", "onclick='action()' target=");
        assertEquals(scripting, rules.apply(scripting, active, "/"));
    }

    @Test
    void preservesTheApprovedFragmentWhileItsPublishedTargetHasOneExactId() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Path published = Files.createDirectory(root.resolve("published"));
        Path target = published.resolve("section.html");
        Files.writeString(target, "<main><h2 id='7-area'>Reviewed section</h2></main>");
        writeFragmentRule("/section.html#7-area");
        var rules = new RetainedNavigationText(root, "sample", published);
        String original = "<nav><a href='old.aspx'>Section</a></nav>";
        String expected = "<nav><a href=\"/section.html#7-area\">Section</a></nav>";
        assertEquals(expected, rules.apply(original, active, "/entry.html"));
        assertEquals(expected, rules.apply(original, active, "/entry.aspx"));
        assertEquals(original, rules.apply(original, active, "/other.html"));
        assertNull(rules.mismatch("v1", "/entry.html", expected));
        assertFalse(rules.internalDestinationMismatch("v1", 7, "Section", "/section.html#7-area"));
        var doc = Jsoup.parse(expected);
        assertTrue(rules.matchesInternalOverride("v1", "/entry.html", doc, doc.selectFirst("a"), "old.aspx"));
        assertEquals(original, new RetainedNavigationText(root, "sample").apply(original, active, "/entry.html"));

        for (String html : new String[] {"<h2 id='other'>Other</h2>",
                "<h2 id='7-AREA'>Case mismatch</h2>",
                "<h2 id='7-area'>First</h2><p id='7-area'>Duplicate</p>"}) {
            Files.writeString(target, html);
            assertEquals(original, rules.apply(original, active, "/entry.html"));
            assertNotNull(rules.mismatch("v1", "/entry.html", expected));
            assertFalse(rules.matchesInternalOverride("v1", "/entry.html", doc, doc.selectFirst("a"), "old.aspx"));
        }
        Files.delete(target);
        assertEquals(original, rules.apply(original, active, "/entry.html"));
        assertNotNull(rules.mismatch("v1", "/entry.html", expected));
        assertTrue(rules.internalDestinationMismatch("v1", 7, "Section", "/section.html#7-area"));
    }

    @Test
    void rejectsUnqualifiedOrUnknownFragmentsAndKeepsExistingLocalPaths() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Path published = Files.createDirectory(root.resolve("published"));
        Files.writeString(published.resolve("section.html"), "<main id='valid'></main>");
        String original = "<nav><a href='old.aspx'>Section</a></nav>";
        for (String destination : new String[] {"https://example.org/section.html#valid",
                "//example.org/section.html#valid", "/section.html#", "/section.html#unknown",
                "/section.html#-invalid", "/section.html#valid/other", "/section.html#%76alid",
                "/section.html?x=1#valid", "/../section.html#valid"}) {
            writeFragmentRule(destination);
            var rules = new RetainedNavigationText(root, "sample", published);
            assertEquals(original, rules.apply(original, active, "/entry.html"), destination);
            assertNotNull(rules.mismatch("v1", "/entry.html", original), destination);
        }
        writeFragmentRule("/not-yet-written.html");
        var rules = new RetainedNavigationText(root, "sample", published);
        assertEquals("<nav><a href=\"/not-yet-written.html\">Section</a></nav>",
                rules.apply(original, active, "/entry.html"));
    }

    private void writeFragmentRule(String destination) throws Exception {
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample": {"v1":[{"routes":["/entry.html"],"renderAliases":["/entry.aspx"],
                 "selector":"nav > a","label":"Section","href":"old.aspx","menuId":7,
                 "reason":"Reviewed","internalHref":"%s"}]}}
                """.formatted(destination));
    }

    @Test
    void removesOnlyTheApprovedPlainDefinitionItemAndKeepsTheFooter() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample":{"v1":[{"routes":["/index.html"],"removeWrapper":true,
                 "selector":".header dd:has(> a:matchesOwn(^Gallery（hidden）$))","label":"Gallery（hidden）",
                 "href":"gallery.aspx","reason":"Reviewed hidden collection"}]}}
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String item = "<dd><a href='gallery.aspx'>Gallery（hidden）</a></dd>";
        String footer = "<footer><a href='/video.html'>Gallery</a></footer>";
        String original = "<header class='header'><dl>" + item
                + "<dd><a href='/video.html'>Video</a></dd></dl></header>" + footer;
        String output = original.replace(item, "");
        assertEquals(output, rules.apply(original, active, "/index.html"));
        assertNull(rules.mismatch("v1", "/index.html", output));
        for (String altered : new String[] {
                original.replace("gallery.aspx", "changed.aspx"),
                original.replace(item, item.replace("<dd>", "<dd class='other'>")),
                original.replace(item, item.replace("</dd>", "<span>Keep</span></dd>")),
                original.replace(item, item + item)}) {
            assertEquals(altered, rules.apply(altered, active, "/index.html"));
            assertNotNull(rules.mismatch("v1", "/index.html", altered));
        }
    }

    @Test
    void requiresExactImageSourceForAnUnlabelledInternalLink() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample": {"v1":[{"routes":["/"],"selector":".card > a","label":"Reviewed card",
                 "href":"old.aspx","reason":"Reviewed","internalHref":"/record/1.html",
                 "imageSrc":"/img/card.png","imageOrigin":"http://localhost:17651"}]}}
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String original = "<div class='card'><a href='old.aspx'><img src='/img/card.png'></a></div>";
        String published = original.replace("href='old.aspx'", "href=\"/record/1.html\"");
        assertEquals(published, rules.apply(original, active, "/"));
        assertNull(rules.mismatch("v1", "/", published));
        String absolute = original.replace("/img/card.png", "http://localhost:17651/img/card.png");
        assertEquals(published.replace("/img/card.png", "http://localhost:17651/img/card.png"),
                rules.apply(absolute, active, "/"));
        String otherOrigin = absolute.replace("localhost", "example.org");
        assertEquals(otherOrigin, rules.apply(otherOrigin, active, "/"));
        String otherImage = original.replace("/img/card.png", "/img/other.png");
        assertEquals(otherImage, rules.apply(otherImage, active, "/"));
        assertEquals(original + original, rules.apply(original + original, active, "/"));
    }

    @Test
    void requiresTheApprovedCardAndExactIconForAnUnlabelledLink() throws Exception {
        Path active = Files.createDirectory(root.resolve("v1"));
        Files.writeString(root.resolve(".retained-navigation-text.json"), """
                {"sample":{"v1":[{"routes":["/section.html"],"selector":".reviewed-card .more > a",
                 "label":"Section details","href":"section.aspx","internalHref":"/section/details.html",
                 "reason":"Reviewed","icon":{"tag":"span","class":"arrow-icon"}}]}}
                """);
        var rules = new RetainedNavigationText(root, "sample");
        String route = "/section.html";
        String original = "<div class='reviewed-card'><div class='more'>"
                + "<a href='section.aspx'><span class='arrow-icon'></span></a></div></div>";
        String published = original.replace("href='section.aspx'", "href=\"/section/details.html\"");
        assertEquals(published, rules.apply(original, active, route));
        assertNull(rules.mismatch("v1", route, published));
        assertEquals(original, rules.apply(original, active, "/other.html"));
        for (String changed : new String[] {
                original.replace("reviewed-card", "other-card"),
                original.replace("section.aspx", "other.aspx"),
                original.replace("arrow-icon", "arrow-icon other"),
                original.replace("span", "i"),
                original.replace("</span>", "Text</span>"),
                original.replace("</span>", "</span>More"),
                original + original}) {
            assertEquals(changed, rules.apply(changed, active, route));
            assertNotNull(rules.mismatch("v1", route, changed));
        }
    }
}

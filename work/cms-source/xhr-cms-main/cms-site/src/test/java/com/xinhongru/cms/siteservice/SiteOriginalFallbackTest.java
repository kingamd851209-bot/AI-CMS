package com.xinhongru.cms.siteservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SiteOriginalFallbackTest {
    @Test
    void blocksOnlyExactReviewedPathsForTheActivePackage() {
        var decisions = new ObjectMapper().readTree("""
                {"site":{"v1":{"441":{"reason":"后台保留",
                "visitorFallbackPaths":["about/picture.aspx","about/picture_01.aspx"]}}},
                "neighbor":{"v1":{"441":{"reason":"后台保留"}}}}
                """);
        var site = decisions.path("site");
        assertTrue(SiteHttpServer.hiddenOriginalFallback(site, "v1", "about/picture.aspx"));
        assertTrue(SiteHttpServer.hiddenOriginalFallback(site, "v1", "ABOUT/PICTURE_01.ASPX"));
        assertFalse(SiteHttpServer.hiddenOriginalFallback(site, "v2", "about/picture.aspx"));
        assertFalse(SiteHttpServer.hiddenOriginalFallback(site, "v1", "about/video.aspx"));
        assertFalse(SiteHttpServer.hiddenOriginalFallback(site, "v1", "archive/about/picture.aspx"));
        assertFalse(SiteHttpServer.hiddenOriginalFallback(decisions.path("neighbor"), "v1", "about/picture.aspx"));
    }
}

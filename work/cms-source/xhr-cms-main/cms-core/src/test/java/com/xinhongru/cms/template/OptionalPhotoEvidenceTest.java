package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class OptionalPhotoEvidenceTest {
    private boolean matches(String rows) {
        var root = Jsoup.parseBodyFragment("<ul>" + rows + "</ul>").selectFirst("ul");
        return SiteModelEvidenceService.mediaRows(root, Set.copyOf(root.getAllElements()));
    }

    @Test
    void recognizesRepeatedRowsWhenOnlyOneHasAnImage() {
        String plain = "<li><h3>事件一</h3></li>";
        String photo = "<li><div><img src='cert.jpg'></div><h3>证书二</h3></li>";
        assertTrue(matches(plain + photo));
        assertTrue(matches(photo + plain));
        assertFalse(matches(plain + plain));
        assertFalse(matches(plain + "<li><div><img src='a.jpg'><img src='b.jpg'></div><h3>双图</h3></li>"));
        assertFalse(matches(plain + "<li><img src='a.jpg'><p>另一种结构</p></li>"));
    }
}

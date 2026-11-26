package com.xinhongru.cms.template;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** 受控识别同构 Swiper 内容卡与独立的分页容器。 */
public final class QueryFeaturedCarousel {
    private QueryFeaturedCarousel() {}

    public record Card(Element root, Element title, Element image, List<Element> links, Element summary) {}
    public record Group(Element region, List<Card> cards, Element dots) {}

    static Group source(Element document, QueryListPresentation.FeaturedCarousel option, String listSelector,
            String querySelector, QueryListPresentation.Pagination pagination) {
        var group = locate(document, option.selector(), false);
        for (String selector : List.of(listSelector, querySelector, pagination.containerSelector())) {
            for (var other : document.select(selector)) {
                if (inside(other, group.region()) || inside(group.region(), other) && !other.is("body"))
                    throw invalid();
            }
        }
        String shape = shape(group.cards().getFirst());
        if (group.cards().stream().anyMatch(card -> !shape.equals(shape(card)))) throw invalid();
        return group;
    }

    public static Group rendered(Element document, String selector) {
        return locate(document, selector, true);
    }

    private static Group locate(Element document, String selector, boolean rendered) {
        if (selector == null || selector.isBlank() || selector.length() > 500) throw invalid();
        var matches = document.select(selector);
        if (matches.size() != 1) throw invalid();
        var region = matches.first();
        if (region.is("html,body") || region.closest("header,footer,nav,form") != null
                || !region.select("[id],form,script,iframe,video,input,button").isEmpty()) throw invalid();
        var containers = region.select(".swiper-container");
        var dots = region.select(".swiper-pagination");
        if (containers.size() != 1 || dots.size() != 1 || !dots.first().children().isEmpty()) throw invalid();
        var container = containers.first();
        var wrappers = container.select(".swiper-wrapper");
        if (wrappers.size() != 1 || wrappers.first().parent() != container) throw invalid();
        var slides = wrappers.first().children();
        if (rendered ? slides.size() != 1 : slides.size() < 2 || slides.size() > 20) throw invalid();
        if (region.select("a").size() != slides.size() * 2 || region.select("img").size() != slides.size()) throw invalid();
        var cards = new ArrayList<Card>();
        for (var slide : slides) {
            if (!slide.hasClass("swiper-slide")) throw invalid();
            var images = slide.select("img");
            var headingLinks = slide.select("h1 > a,h2 > a,h3 > a,h4 > a,h5 > a,h6 > a");
            if (images.size() != 1 || headingLinks.size() != 1) throw invalid();
            var title = headingLinks.first();
            var image = images.first();
            var imageLink = image.closest("a");
            if (imageLink == null || imageLink == title || slide.select("a").size() != 2
                    || !title.children().isEmpty() || !imageLink.text().isBlank()
                    || !rendered && (title.text().isBlank() || image.attr("src").isBlank()
                    || !title.attr("href").equals(imageLink.attr("href"))
                    || !title.attr("href").matches("[A-Za-z0-9_./%-]+"))) throw invalid();
            var summaries = slide.getAllElements().stream()
                    .filter(element -> element != title && (!element.ownText().isBlank() || element.hasAttr("th:text")))
                    .toList();
            if (summaries.size() > 1 || summaries.stream().anyMatch(element -> !element.children().isEmpty()))
                throw invalid();
            cards.add(new Card(slide, title, image, List.of(imageLink, title),
                    summaries.isEmpty() ? null : summaries.getFirst()));
        }
        return new Group(region, List.copyOf(cards), dots.first());
    }

    private static boolean inside(Element node, Element ancestor) {
        return node == ancestor || node.parents().contains(ancestor);
    }

    private static String shape(Card card) {
        var clone = card.root().clone();
        clone.select("img").removeAttr("src").removeAttr("alt");
        clone.select("a").removeAttr("href").removeAttr("title");
        for (var element : clone.getAllElements()) if (!element.ownText().isBlank()) element.text("_");
        return clone.outerHtml();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("轮播须为独立同构轮播组，并含唯一封面、标题、分页及两处同源详情位置");
    }
}

package com.xinhongru.cms.template;

import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;

/** 公共列表轮播只运行固定脚本，不重新启用来源包脚本。 */
final class ListFeaturedCarousel {
    private ListFeaturedCarousel() {}

    private static final String MARKER = "data-cms-featured-carousel";
    private static final String SCRIPT = """
            (() => {
              const region = document.querySelector('[data-cms-featured-carousel="region"]');
              if (!region) return;
              const slides = Array.from(region.querySelectorAll('.swiper-wrapper > .swiper-slide'));
              const dots = region.querySelector('.swiper-pagination');
              if (!slides.length || !dots) return;
              Object.assign(dots.style, { position: 'static', width: '100%', display: 'flex',
                justifyContent: 'center', alignItems: 'center', flexWrap: 'wrap', gap: '4px' });
              let active = 0;
              const buttons = slides.map((slide, index) => {
                const dot = document.createElement('button');
                dot.type = 'button';
                dot.className = 'swiper-pagination-bullet';
                Object.assign(dot.style, { boxSizing: 'border-box', minWidth: '44px', height: '44px',
                  padding: '20px 5px', border: '0', backgroundClip: 'content-box' });
                dot.setAttribute('aria-label', '第 ' + (index + 1) + ' 条资讯');
                dot.addEventListener('click', () => select(index));
                dots.append(dot);
                return dot;
              });
              function select(index) {
                active = (index + slides.length) % slides.length;
                slides.forEach((slide, position) => {
                  const shown = position === active;
                  slide.hidden = !shown;
                  slide.style.display = shown ? '' : 'none';
                  slide.classList.toggle('swiper-slide-active', shown);
                  buttons[position].classList.toggle('swiper-pagination-bullet-active', shown);
                  buttons[position].setAttribute('aria-current', String(shown));
                });
              }
              region.addEventListener('keydown', event => {
                if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
                event.preventDefault();
                select(active + (event.key === 'ArrowRight' ? 1 : -1));
                if (dots.contains(event.target)) buttons[active].focus();
                else if (slides.some(slide => slide.contains(event.target))) slides[active].querySelector('a').focus();
              });
              let start = null, swiped = false;
              region.addEventListener('touchstart', event => {
                swiped = false;
                start = event.touches.length === 1 ? event.touches[0] : null;
              }, { passive: true });
              region.addEventListener('touchend', event => {
                if (!start || !event.changedTouches.length) return;
                const touch = event.changedTouches[0];
                const dx = touch.clientX - start.clientX;
                if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(touch.clientY - start.clientY)) {
                  swiped = true;
                  select(active + (dx < 0 ? 1 : -1));
                }
                start = null;
              }, { passive: true });
              region.addEventListener('touchcancel', () => { start = null; });
              region.addEventListener('click', event => {
                if (!swiped) return;
                swiped = false;
                if (event.target.closest('a')) event.preventDefault();
              }, true);
              select(0);
              if (slides.length > 1) {
                const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
                let paused = false;
                const toggle = document.createElement('button');
                toggle.type = 'button';
                Object.assign(toggle.style, { minHeight: '44px', padding: '0 8px' });
                toggle.textContent = '暂停轮播';
                toggle.addEventListener('click', () => {
                  paused = !paused;
                  toggle.textContent = paused ? '播放轮播' : '暂停轮播';
                });
                toggle.hidden = reducedMotion.matches;
                reducedMotion.addEventListener('change', () => { toggle.hidden = reducedMotion.matches; });
                dots.append(toggle);
                setInterval(() => {
                  if (!paused && !reducedMotion.matches && !document.hidden
                      && !region.matches(':hover') && !region.contains(document.activeElement)) select(active + 1);
                }, 5000);
              }
            })();
            """;
    static final String LOOP = "cmsBoundRow : ${cmsBoundRows}";
    static final String VISIBLE = "${cmsBoundPage.page == 1 and !cmsBoundRows.isEmpty()}";

    static void bind(Element groupRoot, QueryFeaturedCarousel.Group group,
            QueryListPresentation.FeaturedCarousel option) {
        if (!groupRoot.select("[" + MARKER + "]").isEmpty() || group.region().hasAttr(MARKER))
            throw new IllegalArgumentException("原页含受控轮播标记");
        var card = group.cards().getFirst();
        group.cards().stream().skip(1).forEach(item -> item.root().remove());
        group.region().attr(MARKER, "region").attr("th:if", VISIBLE);
        card.root().attr("th:each", LOOP);
        card.image().removeAttr("src").removeAttr("alt")
                .attr("th:src", expression("media", option.imageField()))
                .attr("th:alt", expression("text", option.titleField()));
        card.title().empty().attr("th:text", expression("text", option.titleField()));
        for (var link : card.links()) link.removeAttr("href").removeAttr("title")
                .attr("th:href", "${cmsBoundRow.detailUrl}")
                .attr("rel", "noopener");
        if (card.summary() != null) {
            if (option.summaryField() == null) card.summary().remove();
            else card.summary().empty().attr("th:text", expression("text", option.summaryField()))
                    .attr("th:if", "${!#strings.isEmpty(cmsBoundRow.text['" + option.summaryField() + "'])}");
        }
        groupRoot.appendElement("script").attr(MARKER, "script").appendChild(new DataNode(SCRIPT));
    }

    static void validateAndRemove(Element document, QueryListPresentation.FeaturedCarousel option) {
        var markers = document.select("[" + MARKER + "]");
        if (option == null) {
            if (!markers.isEmpty()) throw invalid();
            return;
        }
        var group = QueryFeaturedCarousel.rendered(document, option.selector());
        var region = group.region();
        var script = document.select("script[" + MARKER + "=script]");
        if (markers.size() != 2 || !"region".equals(region.attr(MARKER)) || script.size() != 1
                || script.first().attributes().asList().size() != 1 || !SCRIPT.equals(script.first().data())
                || !VISIBLE.equals(region.attr("th:if"))) throw invalid();
        var card = group.cards().getFirst();
        if (!LOOP.equals(card.root().attr("th:each"))
                || !expression("media", option.imageField()).equals(card.image().attr("th:src"))
                || !expression("text", option.titleField()).equals(card.title().attr("th:text"))
                || !expression("text", option.titleField()).equals(card.image().attr("th:alt"))
                || card.links().stream().anyMatch(link -> link.hasAttr("href")
                        || !"${cmsBoundRow.detailUrl}".equals(link.attr("th:href")))
                || (option.summaryField() == null ? card.summary() != null
                        : card.summary() == null || !expression("text", option.summaryField())
                                .equals(card.summary().attr("th:text")) || !("${!#strings.isEmpty(cmsBoundRow.text['"
                                + option.summaryField() + "'])}").equals(card.summary().attr("th:if")))) throw invalid();
        // 已精确匹配的指令由该组件负责；其余指令仍交由完整列表验证器拒绝。
        region.removeAttr("th:if");
        card.root().removeAttr("th:each");
        card.image().removeAttr("th:src").removeAttr("th:alt");
        card.title().removeAttr("th:text");
        card.links().forEach(link -> link.removeAttr("th:href"));
        if (card.summary() != null) card.summary().removeAttr("th:text").removeAttr("th:if");
        if (region.getAllElements().stream().anyMatch(element -> element.attributes().asList().stream()
                .anyMatch(attribute -> attribute.getKey().startsWith("th:") || attribute.getKey().startsWith("data-th-"))))
            throw invalid();
        script.first().remove();
    }

    static String withoutScript(String html) {
        var document = org.jsoup.Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        var script = document.select("script[" + MARKER + "=script]");
        if (script.size() != 1 || script.first().attributes().asList().size() != 1
                || !SCRIPT.equals(script.first().data())) throw invalid();
        script.first().remove();
        return document.outerHtml();
    }

    static String withScript(String html) {
        var document = org.jsoup.Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (!document.select("script[" + MARKER + "]").isEmpty()) throw invalid();
        document.body().appendElement("script").attr(MARKER, "script").appendChild(new DataNode(SCRIPT));
        return document.outerHtml();
    }

    private static String expression(String map, String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,80}")) throw invalid();
        return "${cmsBoundRow." + map + "['" + name + "']}";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("列表轮播的范围、字段指令或固定互动脚本与已审方案不一致");
    }
}

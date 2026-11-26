package com.xinhongru.cms.template;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** 只为有同源结构、样式和行为证据的页头接入固定菜单行为。 */
final class ListHeaderMenu {
    private ListHeaderMenu() {}

    static final int RESOURCE_LIMIT = 256 * 1024;
    private static final String MARKER = "data-cms-list-header-menu";
    private static final String SCRIPT =
            """
            (() => {
              const header = document.querySelector('header.header');
              const nav = header.querySelector('nav.nav');
              const mobile = header.querySelector('.nav_phone_btn');
              const backdrop = header.querySelector('.nav_bg');
              const viewport = matchMedia('(max-width: 1024px)');
              const items = Array.from(nav.querySelectorAll(':scope > ul > li'), item => ({
                item,
                link: item.querySelector(':scope > a[href]'),
                button: item.querySelector(':scope > span'),
                panel: item.querySelector(':scope > .navlist')
              }));
              const disclosures = items.filter(item => item.panel);
              const expanded = (element, open) => element.setAttribute('aria-expanded', String(open));
              function identify(element, hint) {
                if (element.id) return element.id;
                let id = hint;
                for (let i = 1; document.getElementById(id); i++) id = hint + '-' + i;
                return element.id = id;
              }
              function button(element, label, target, action) {
                element.setAttribute('role', 'button');
                element.setAttribute('aria-label', label);
                element.setAttribute('aria-controls', target);
                element.addEventListener('click', action);
                element.addEventListener('keydown', event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  if (!event.repeat) action();
                });
              }
              let active = null, mobileOpen = false, restoringFocus = false;
              function show(next) {
                active = next;
                for (const item of disclosures) {
                  const open = item === next;
                  item.panel.style.display = open ? 'block' : 'none';
                  item.button.classList.toggle('on', open && viewport.matches);
                  expanded(item.button, open);
                  if (!viewport.matches) expanded(item.link, open);
                }
                const desktopOpen = next && !viewport.matches;
                backdrop.style.display = desktopOpen ? 'block' : '';
                if (desktopOpen) {
                  const position = next.link.getBoundingClientRect().left;
                  next.panel.firstElementChild.style.marginLeft =
                    Math.max(0, Math.min(position, header.clientWidth / 2)) + 'px';
                  backdrop.style.height = next.panel.offsetHeight + 'px';
                }
                header.classList.toggle('hover', Boolean(desktopOpen) || (!viewport.matches && header.matches(':hover')));
              }
              function setMobile(open) {
                mobileOpen = viewport.matches && open;
                nav.inert = viewport.matches && !mobileOpen;
                mobile.classList.toggle('visible_nav', mobileOpen);
                document.body.classList.toggle('body_nav_phone', mobileOpen);
                expanded(mobile, mobileOpen);
                show(null);
              }
              button(mobile, '网站导航', identify(nav, 'cms-list-navigation'), () => setMobile(!mobileOpen));
              for (const [index, item] of disclosures.entries()) {
                const target = identify(item.panel, 'cms-list-submenu-' + (index + 1));
                button(item.button, item.link.textContent.trim(), target, () => show(active === item ? null : item));
              }
              for (const item of items) {
                item.item.addEventListener('pointerenter', () => {
                  if (!viewport.matches) show(item.panel ? item : null);
                });
                item.item.addEventListener('pointerleave', () => {
                  if (!viewport.matches && active === item && !item.item.contains(document.activeElement)) show(null);
                });
                item.item.addEventListener('focusin', () => {
                  if (!viewport.matches && !restoringFocus) show(item.panel ? item : null);
                });
                item.item.addEventListener('focusout', event => {
                  if (!viewport.matches && active === item && !item.item.contains(event.relatedTarget)) show(null);
                });
              }
              header.addEventListener('pointerenter', () => {
                if (!viewport.matches) header.classList.add('hover');
              });
              header.addEventListener('pointerleave', () => {
                if (!active) header.classList.remove('hover');
              });
              nav.addEventListener('click', event => {
                if (!active || !active.panel.contains(event.target) || !event.target.closest('a[href]')) return;
                if (viewport.matches) setMobile(false);
                else show(null);
              });
              document.addEventListener('click', event => {
                if (header.contains(event.target)) return;
                if (mobileOpen) setMobile(false);
                else if (active) show(null);
              });
              document.addEventListener('keydown', event => {
                if (event.key !== 'Escape' || (!active && !mobileOpen)) return;
                event.preventDefault();
                const focus = active ? (viewport.matches ? active.button : active.link) : mobile;
                if (active) show(null);
                else setMobile(false);
                restoringFocus = true;
                focus.focus({preventScroll: true});
                restoringFocus = false;
              });
              function layout() {
                mobile.hidden = !viewport.matches;
                mobile.tabIndex = viewport.matches ? 0 : -1;
                for (const item of disclosures) {
                  item.button.tabIndex = viewport.matches ? 0 : -1;
                  item.panel.firstElementChild.style.marginLeft = '';
                  if (viewport.matches) {
                    item.link.removeAttribute('aria-controls');
                    item.link.removeAttribute('aria-expanded');
                  } else item.link.setAttribute('aria-controls', item.panel.id);
                }
                setMobile(false);
              }
              viewport.addEventListener('change', layout);
              layout();
            })();
            """
                    .strip();

    static boolean evidenced(String html, String sourcePath, Function<String, byte[]> resource) {
        // 拼入页头的 UTF-8 BOM 可能令 HTML 解析器提前结束 head。
        var document = Jsoup.parse(html.replace("\uFEFF", ""));
        if (!supportedMarkup(document) || !document.select("[" + MARKER + "]").isEmpty()) return false;
        boolean style = document.select("head link[rel=stylesheet][href]").stream()
                .limit(8)
                .anyMatch(link -> evidence(sourcePath, link.attr("href"), ".css", resource,
                        ".navlist{display:none",
                        "@mediaonlyscreenand(max-width:1024px)",
                        ".nav_phone_btn{",
                        ".body_nav_phone.nav{right:0"));
        return style && document.select("head script[src]").stream()
                .limit(10)
                .anyMatch(script -> evidence(sourcePath, script.attr("src"), ".js", resource,
                        "$(window).width()>1024",
                        "$(\".navul>li\").hover(",
                        ".find(\".navlist\").stop().slideDown(",
                        ".on('click','.nav_phone_btn'",
                        ".toggleClass('body_nav_phone')",
                        ".on('click','.navullispan'",
                        ".siblings(\".navlist\").slideToggle("));
    }

    private static boolean evidence(
            String sourcePath, String reference, String extension,
            Function<String, byte[]> resource, String... signatures) {
        String path = reference.trim().split("[?#]", 2)[0];
        if (!path.endsWith(extension) || path.startsWith("//") || path.contains(":")) return false;
        try {
            String logical = EditorContentPreparation.packageReference(sourcePath, path);
            byte[] bytes = resource.apply(logical.substring(4));
            if (bytes.length > RESOURCE_LIMIT) return false;
            String content = new String(bytes, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", "");
            for (String signature : signatures)
                if (!content.contains(signature)) return false;
            return true;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private static boolean supportedMarkup(Document document) {
        var headers = document.select("body header.header");
        if (headers.size() != 1) return false;
        var header = headers.first();
        var navs = header.select("nav.nav");
        var triggers = header.select("div.nav_phone_btn");
        var backdrop = header.select("div.nav_bg");
        if (navs.size() != 1 || triggers.size() != 1 || backdrop.size() != 1
                || !triggers.first().select("a,form,input,button,[tabindex]").isEmpty())
            return false;
        var nav = navs.first();
        if (nav.childrenSize() != 1 || !nav.child(0).is("ul")) return false;
        int panels = 0;
        for (var item : nav.child(0).children()) {
            if (!item.is("li") || item.childrenSize() == 0) return false;
            var direct = item.children();
            if (!direct.first().is("a[href]")) return false;
            if (direct.size() == 1) continue;
            if (direct.size() != 3 || !direct.get(1).is("span:empty")
                    || !direct.get(2).is("div.navlist")
                    || direct.get(2).childrenSize() != 1 || !direct.get(2).child(0).is("dl")
                    || direct.get(2).select("a[href]").isEmpty())
                return false;
            panels++;
        }
        return panels > 0;
    }

    static boolean append(Document document) {
        if (!supportedMarkup(document) || !document.select("[" + MARKER + "]").isEmpty())
            return false;
        document.body().appendElement("script").attr(MARKER, "v1").appendChild(new DataNode(SCRIPT));
        return true;
    }

    static boolean generated(String html) {
        return removeVerified(Jsoup.parse(html), true);
    }

    static boolean hasMarker(String html) {
        return html.contains(MARKER);
    }

    static String withGenerated(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        if (!document.select("script").isEmpty() || !append(document))
            throw new IllegalArgumentException("公开组合改变了受控菜单所需的页头结构");
        return document.outerHtml();
    }

    static String withoutGenerated(String html) {
        var document = Jsoup.parse(html);
        if (!removeVerified(document, true)) return html;
        document.outputSettings().prettyPrint(false);
        return document.outerHtml();
    }

    static boolean removeVerified(Document document, boolean allowed) {
        var scripts = document.select("script");
        var marked = document.select("[" + MARKER + "]");
        if (scripts.isEmpty() && marked.isEmpty()) return false;
        Element script = scripts.first();
        if (!allowed || scripts.size() != 1 || marked.size() != 1 || marked.first() != script
                || !script.is("script[" + MARKER + "=v1]") || script.attributesSize() != 1
                || script.parent() != document.body() || script.nextElementSibling() != null
                || !SCRIPT.equals(script.data()) || !supportedMarkup(document))
            throw new IllegalArgumentException("列表页头菜单脚本、位置或原有结构已变化");
        script.remove();
        return true;
    }
}

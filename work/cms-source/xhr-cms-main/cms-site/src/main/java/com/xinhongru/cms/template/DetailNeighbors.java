package com.xinhongru.cms.template;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.service.InfoService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 详情模板只消费经审核的上下篇位置和同栏目的已发布内容。 */
final class DetailNeighbors {
    static final String VARIABLE = "cmsDetailNeighbors";
    static final String RETURN_URL = "cmsDetailReturnUrl";
    private static final List<String> INACTIVE_CLASSES = List.of("no", "disabled", "is-disabled");
    private DetailNeighbors() {}

    enum Kind { NEIGHBORS, RETURN_ONLY }

    record Candidate(String id, String selector, Kind kind, String previousHref, String previousTitle,
                     String nextHref, String nextTitle, String returnSelector, String returnHref) {}

    record Binding(Candidate candidate, String previousClass, String nextClass, String returnQueryId,
                   String previousAnchorClass, String nextAnchorClass) {}

    record ListTarget(String sourcePath, String queryId) {}

    static List<Binding> inspect(String html, String sourcePath, ListTarget list) {
        var document = parse(html);
        var results = new ArrayList<Binding>();
        var returns = list == null ? List.<Element>of() : document.select("a[href]").stream()
                .filter(anchor -> returnAnchor(anchor) && returnSource(anchor, sourcePath, list.sourcePath()))
                .toList();
        var returnAnchor = returns.size() == 1 ? returns.getFirst() : null;
        if (returnAnchor != null && document.select(returnAnchor.cssSelector()).size() != 1)
            returnAnchor = null;
        for (var group : document.select("main,article,section,div,nav,ul,ol")) {
            if (results.size() == 12) break;
            if (!DetailNeighborMarkup.candidateGroup(group))
                continue;
            var previous = DetailNeighborMarkup.neighborEdge(group.child(0), true, false);
            var next = DetailNeighborMarkup.neighborEdge(group.child(1), false, false);
            if (previous == null || next == null) continue;
            String selector = group.cssSelector();
            if (document.select(selector).size() != 1) continue;
            var candidate = new Candidate(PublicBindingApplication.digest(selector), selector, Kind.NEIGHBORS,
                    previous.anchor().attr("href"), previous.title(), next.anchor().attr("href"), next.title(),
                    returnAnchor == null ? null : returnAnchor.cssSelector(),
                    returnAnchor == null ? null : returnAnchor.attr("href"));
            results.add(new Binding(candidate, originalClass(group.child(0)), originalClass(group.child(1)),
                    returnAnchor == null ? null : list.queryId(), anchorClass(previous), anchorClass(next)));
        }
        if (results.isEmpty() && returnAnchor != null) {
            String selector = returnAnchor.cssSelector();
            var candidate = new Candidate(PublicBindingApplication.digest(selector), selector, Kind.RETURN_ONLY,
                    null, null, null, null, selector, returnAnchor.attr("href"));
            results.add(new Binding(candidate, null, null, list.queryId(), null, null));
        }
        return List.copyOf(results);
    }

    static String compile(String html, Binding binding) {
        return transform(html, binding, false);
    }

    static String withoutInstructions(String html, Binding binding) {
        return transform(html, binding, true);
    }

    private static String transform(String html, Binding binding, boolean remove) {
        if (binding == null) return html;
        var document = parse(html);
        var candidate = binding.candidate();
        if (candidate == null || candidate.selector() == null
                || !PublicBindingApplication.digest(candidate.selector()).equals(candidate.id())) throw changed();
        if (candidate.kind() == Kind.NEIGHBORS) {
            var groups = document.select(candidate.selector());
            if (groups.size() != 1 || groups.first().childrenSize() != 2) throw changed();
            var group = groups.first();
            transformEdge(group.child(0), true, candidate.previousHref(), candidate.previousTitle(),
                    binding.previousClass(), binding.previousAnchorClass(), remove);
            transformEdge(group.child(1), false, candidate.nextHref(), candidate.nextTitle(),
                    binding.nextClass(), binding.nextAnchorClass(), remove);
        } else if (candidate.kind() != Kind.RETURN_ONLY
                || !candidate.selector().equals(candidate.returnSelector())) throw changed();
        if (candidate.returnSelector() != null) {
            var matches = document.select(candidate.returnSelector());
            if (matches.size() != 1 || !returnAnchor(matches.first())
                    || !Objects.equals(candidate.returnHref(), matches.first().attr("href"))
                    || binding.returnQueryId() == null) throw changed();
            instruction(matches.first(), "if", "${" + RETURN_URL + " != null}", remove);
            instruction(matches.first(), "href", "${" + RETURN_URL + "}", remove);
        }
        return document.outerHtml();
    }

    private static void transformEdge(Element row, boolean previous, String href, String title,
                                      String rowClass, String anchorClass, boolean remove) {
        var edge = DetailNeighborMarkup.neighborEdge(row, previous, remove);
        if (edge == null || !Objects.equals(href, edge.anchor().attr("href")) || !Objects.equals(title, edge.title()))
            throw changed();
        var anchor = edge.anchor();
        transformClasses(row, rowClass, inline(edge), remove);
        if (inline(edge)) transformClasses(anchor, anchorClass, true, remove);
        String side = previous ? "prev" : "next";
        String titleExpression = VARIABLE + "." + side + "Title";
        if (edge.inlinePrefix() != null) titleExpression = "'" + edge.inlinePrefix() + " ' + " + titleExpression;
        var titleNode = anchor;
        if (edge.inlineLabel() != null) {
            var text = edge.inlineLabel().nextSibling();
            if (remove) {
                titleNode = (Element) text;
            } else {
                titleNode = new Element("th:block");
                text.before(titleNode);
                titleNode.appendChild(text);
            }
        }
        instruction(titleNode, "text", "${" + titleExpression + "}", remove);
        if (remove && titleNode != anchor) titleNode.unwrap();
        instruction(row, "if", "${" + VARIABLE + "." + side + "Url != null}", remove);
        instruction(anchor, "href", "${" + VARIABLE + "." + side + "Url}", remove);
    }

    private static void transformClasses(Element element, String original, boolean inline, boolean remove) {
        if (remove) {
            var expected = element.clone();
            restoreClass(expected, original);
            clearInactiveClasses(expected, inline);
            if (!Objects.equals(originalClass(element), originalClass(expected))) throw changed();
            restoreClass(element, original);
        } else {
            if (!Objects.equals(originalClass(element), original)) throw changed();
            clearInactiveClasses(element, inline);
        }
    }

    private static void instruction(Element node, String name, String expression, boolean remove) {
        String key = "th:" + name;
        if (node.hasAttr("data-th-" + name)
                || (remove ? !expression.equals(node.attr(key)) : node.hasAttr(key))) throw changed();
        if (remove) node.removeAttr(key);
        else node.attr(key, expression);
    }

    private static boolean returnAnchor(Element anchor) {
        return anchor.is("a[href]") && anchor.text().matches("返回|返回列表")
                && anchor.closest("header,footer,aside,nav,.header,.footer,[hidden],[aria-hidden=true]") == null
                && !DetailNeighborMarkup.hasEvent(anchor)
                && anchor.children().stream().allMatch(child -> child.is("i") && child.childrenSize() == 0
                        && child.text().isEmpty() && !DetailNeighborMarkup.hasEvent(child));
    }

    private static boolean returnSource(Element anchor, String sourcePath, String listPath) {
        try {
            String href = anchor.attr("href");
            var uri = URI.create(href);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            return !sourcePath.equals(listPath) && listPath.equals(
                    SiteModelEvidenceService.resolveLocal(sourcePath, href, Set.of(listPath)));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean inline(DetailNeighborMarkup.Edge edge) {
        return edge.inlinePrefix() != null || edge.inlineLabel() != null;
    }

    private static String anchorClass(DetailNeighborMarkup.Edge edge) {
        return inline(edge) ? originalClass(edge.anchor()) : null;
    }

    private static String originalClass(Element row) {
        return row.hasAttr("class") ? row.attr("class") : null;
    }

    private static void restoreClass(Element row, String value) {
        if (value == null) row.removeAttr("class");
        else row.attr("class", value);
    }

    private static void clearInactiveClasses(Element element, boolean inline) {
        for (String name : element.classNames()) {
            if (INACTIVE_CLASSES.contains(name)
                    || (inline && (name.endsWith("_disabled") || name.endsWith("-disabled"))))
                element.removeClass(name);
        }
    }

    static DetailsVo project(Long site, OneshotEntity menu, ContentEntity current,
                             InfoService contents, ContentLinkService links) {
        var result = new DetailsVo();
        if (!Objects.equals(site, menu.getSiteProjectId()) || !Objects.equals(menu.getId(), current.getClassid()))
            throw new IllegalArgumentException("上下篇必须使用当前站点详情栏目");
        var previous = contents.getNewsNeighbor(site, menu.getId(), current.getCreateTime(), current.getId(), true);
        var next = contents.getNewsNeighbor(site, menu.getId(), current.getCreateTime(), current.getId(), false);
        String previousUrl = publicUrl(site, menu, current, previous, links);
        String nextUrl = publicUrl(site, menu, current, next, links);
        if (previousUrl != null) result.setPrevUrl(previousUrl).setPrevTitle(previous.getTitle());
        if (nextUrl != null) result.setNextUrl(nextUrl).setNextTitle(next.getTitle());
        return result;
    }

    static PublicBindingApplication activeList(Path metadata, String code, String version, OneshotEntity menu) {
        if (!Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !PublicBindingApplication.managed(menu.getListPageTemplate())) return null;
        try {
            var list = PublicBindingApplication.read(metadata, code,
                    PublicBindingApplication.keyFrom(menu.getListPageTemplate()));
            if (list != null && list.queryId() != null && list.routeMenuId() == null
                    && Objects.equals(list.siteId(), menu.getSiteProjectId())
                    && Objects.equals(list.menuId(), menu.getId().longValue())
                    && Objects.equals(version, list.packageVersion())
                    && Objects.equals(menu.getListPageTemplate(), list.templatePath())
                    && Objects.equals(menu.getListPageUrl(), list.routeUrl())
                    && Objects.equals(ContentFieldConfigDigest.of(menu), list.fieldConfigDigest())) return list;
            return null;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return null;
        }
    }

    static String returnUrl(Path metadata, String code, String version, String queryId, OneshotEntity menu,
                            ContentLinkService links, TemplateStorageService templates) {
        if (queryId == null) return null;
        var list = activeList(metadata, code, version, menu);
        if (list == null || !queryId.equals(list.queryId())) return null;
        try {
            var root = templates.resolveTemplateDir(code).toAbsolutePath().normalize();
            var target = root.resolve(menu.getListPageTemplate()).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !target.toRealPath().startsWith(root.toRealPath())) return null;
            return PublicListModelProjection.localUrl(links.getListUrl(menu, 1));
        } catch (java.io.IOException | IllegalArgumentException error) {
            return null;
        }
    }

    private static String publicUrl(Long site, OneshotEntity menu, ContentEntity current,
                                    ContentEntity other, ContentLinkService links) {
        if (other == null || other.getId() == null || other.getId() <= 0
                || Objects.equals(current.getId(), other.getId())
                || !Objects.equals(site, other.getSiteProjectId())
                || !Objects.equals(menu.getId(), other.getClassid()) || !Objects.equals(other.getStatus(), 1)
                || other.getTitle() == null || other.getTitle().isBlank()) return null;
        try {
            return PublicListModelProjection.localUrl(links.getDetailUrl(other, menu));
        } catch (IllegalArgumentException invalidRoute) {
            return null;
        }
    }

    private static org.jsoup.nodes.Document parse(String html) {
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        return document;
    }

    private static IllegalArgumentException changed() {
        return new IllegalArgumentException("详情上下篇位置或原始标记已变化，请重新审核");
    }
}

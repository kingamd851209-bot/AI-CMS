package com.xinhongru.ai.site.visual;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VisualDomSnapshotService {
    private static final Set<String> NON_SELECTABLE_TAGS =
            Set.of(
                    "html",
                    "head",
                    "body",
                    "script",
                    "style",
                    "meta",
                    "link",
                    "base",
                    "title",
                    "noscript",
                    "template");
    private static final Set<String> CONTENT_TAGS =
            Set.of(
                    "a",
                    "button",
                    "img",
                    "input",
                    "textarea",
                    "select",
                    "option",
                    "label",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "p",
                    "li",
                    "td",
                    "th",
                    "span",
                    "strong",
                    "em",
                    "div");
    private static final Set<String> CONTAINER_TAGS =
            Set.of(
                    "div", "section", "article", "main", "header", "footer", "nav", "ul", "ol",
                    "table", "tbody", "thead", "tr");
    private static final String COMMON_CHROME_SELECTOR =
            String.join(
                    ",",
                    "header",
                    "footer",
                    "nav",
                    "[role=banner]",
                    "[role=contentinfo]",
                    "[role=navigation]",
                    "#header",
                    "#footer",
                    "#nav",
                    "#navbar",
                    ".header",
                    ".footer",
                    ".nav",
                    ".navbar",
                    ".site-header",
                    ".site-footer",
                    ".topbar");

    private final SelectorStabilityScorer selectorStabilityScorer;

    public VisualDomSnapshotService() {
        this(new SelectorStabilityScorer());
    }

    public VisualDomSnapshotService(SelectorStabilityScorer selectorStabilityScorer) {
        this.selectorStabilityScorer =
                Objects.requireNonNull(selectorStabilityScorer, "selectorStabilityScorer");
    }

    public List<VisualElementCandidate> candidatesFromRenderedHtml(String html, String pagePath) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document document = Jsoup.parse(html);
        List<ElementCandidateSeed> seeds = new ArrayList<>();
        for (Element element : document.getAllElements()) {
            if (!isSelectable(element)) {
                continue;
            }
            CandidateContent content = content(element);
            if (content.isEmpty() || isDecorativeImage(element, content)) {
                continue;
            }
            String selector = cssPath(document, element);
            SelectorChoice stableSelector = stableSelector(document, element, selector);
            seeds.add(
                    new ElementCandidateSeed(
                            element,
                            selector,
                            stableSelector.selector(),
                            content,
                            stableSelector.diagnostics()));
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<VisualElementCandidate> candidates = new ArrayList<>();
        for (ElementCandidateSeed seed : seeds) {
            String repeatKey = signature(seed);
            int repeatIndex = seen.merge(repeatKey, 1, Integer::sum) - 1;
            List<String> diagnostics = new ArrayList<>(seed.diagnostics());
            if (isInCommonChrome(seed.element())) {
                diagnostics.add("common_chrome_region");
            }
            VisualElementCandidate.BBox bbox = bbox(seed.element(), diagnostics);
            int score =
                    selectorStabilityScorer.score(
                            seed.element(), seed.selector(), seed.stableSelector(), repeatIndex);
            if (isInCommonChrome(seed.element())) {
                score = Math.max(0, score - 12);
            }
            candidates.add(
                    new VisualElementCandidate(
                            seed.selector(),
                            seed.stableSelector(),
                            score,
                            seed.element().tagName(),
                            seed.content().text(),
                            seed.content().imageSrc(),
                            seed.content().linkHref(),
                            bbox,
                            domDepth(seed.element()),
                            repeatIndex,
                            true,
                            role(seed.element()),
                            type(seed.element()),
                            Map.of(
                                    "pagePath",
                                    pagePath == null ? "" : pagePath,
                                    "source",
                                    "JSOUP_RENDERED_HTML"),
                            diagnostics));
        }
        return candidates.stream()
                .sorted(
                        Comparator.comparingInt(VisualElementCandidate::selectorScore)
                                .reversed()
                                .thenComparing(VisualElementCandidate::domDepth)
                                .thenComparing(VisualElementCandidate::selector))
                .toList();
    }

    public ViewportComparison compareAcrossViewports(List<ViewportSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new ViewportComparison(
                    List.of(), List.of(), List.of(), "stable=0 missing=0 changed=0");
        }
        Map<String, List<SnapshotCandidate>> bySignature = new LinkedHashMap<>();
        for (ViewportSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.candidates() == null) {
                continue;
            }
            for (VisualElementCandidate candidate : snapshot.candidates()) {
                bySignature
                        .computeIfAbsent(
                                candidateSignature(candidate), ignored -> new ArrayList<>())
                        .add(new SnapshotCandidate(snapshot, candidate));
            }
        }
        int snapshotCount = snapshots.size();
        String baselineLabel = snapshots.getFirst().label();
        List<VisualElementCandidate> stable = new ArrayList<>();
        List<VisualElementCandidate> changed = new ArrayList<>();
        List<MissingCandidate> missing = new ArrayList<>();
        for (Map.Entry<String, List<SnapshotCandidate>> entry : bySignature.entrySet()) {
            Set<String> presentLabels =
                    entry.getValue().stream()
                            .map(item -> item.snapshot().label())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            VisualElementCandidate representative = entry.getValue().getFirst().candidate();
            if (presentLabels.size() == snapshotCount) {
                stable.add(representative);
                if (bboxChanged(entry.getValue())) {
                    changed.add(representative);
                }
                continue;
            }
            if (!presentLabels.contains(baselineLabel)) {
                continue;
            }
            List<String> missingLabels =
                    snapshots.stream()
                            .map(ViewportSnapshot::label)
                            .filter(label -> !presentLabels.contains(label))
                            .toList();
            missing.add(
                    new MissingCandidate(
                            representative.stableSelector(),
                            representative.text(),
                            representative.linkHref(),
                            representative.imageSrc(),
                            missingLabels));
        }
        return new ViewportComparison(
                List.copyOf(stable),
                List.copyOf(missing),
                List.copyOf(changed),
                "stable="
                        + stable.size()
                        + " missing="
                        + missing.size()
                        + " changed="
                        + changed.size());
    }

    private boolean isSelectable(Element element) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (NON_SELECTABLE_TAGS.contains(tag)) {
            return false;
        }
        if (!isVisible(element)) {
            return false;
        }
        return CONTENT_TAGS.contains(tag)
                || !element.id().isBlank()
                || selectorStabilityScorer.hasStableDataAttribute(element)
                || !element.attr("role").isBlank()
                || !element.attr("aria-label").isBlank();
    }

    private boolean isInCommonChrome(Element element) {
        Element chrome = element.closest(COMMON_CHROME_SELECTOR);
        return chrome != null;
    }

    private boolean isVisible(Element element) {
        for (Element current = element; current != null; current = current.parent()) {
            String style = current.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
            if (current.hasAttr("hidden")
                    || "true".equalsIgnoreCase(current.attr("aria-hidden"))
                    || style.contains("display:none")
                    || style.contains("visibility:hidden")
                    || style.contains("opacity:0")) {
                return false;
            }
        }
        return !"hidden".equalsIgnoreCase(element.attr("type"));
    }

    private CandidateContent content(Element element) {
        String text = normalizeText(element.ownText());
        if (text.isBlank()
                && !Set.of("a", "button", "label").contains(element.tagName())
                && !CONTAINER_TAGS.contains(element.tagName())) {
            text = normalizeText(element.text());
        }
        String imageSrc = "";
        if ("img".equals(element.tagName())) {
            imageSrc =
                    firstNonBlank(
                            element.attr("src"),
                            element.attr("data-src"),
                            element.attr("data-original"));
        } else {
            Element image = element.selectFirst("img[src], img[data-src], img[data-original]");
            if (image != null) {
                imageSrc =
                        firstNonBlank(
                                image.attr("src"),
                                image.attr("data-src"),
                                image.attr("data-original"));
            }
        }
        String linkHref = "";
        if ("a".equals(element.tagName())) {
            linkHref = element.attr("href");
        } else {
            Element link = element.closest("a[href]");
            linkHref = link == null ? "" : link.attr("href");
        }
        if (text.isBlank() && "img".equals(element.tagName())) {
            text =
                    normalizeText(
                            firstNonBlank(
                                    element.attr("alt"),
                                    element.attr("title"),
                                    element.attr("aria-label")));
        }
        return new CandidateContent(text, blankToNull(imageSrc), blankToNull(linkHref));
    }

    private boolean isDecorativeImage(Element element, CandidateContent content) {
        if (!"img".equals(element.tagName())) {
            return false;
        }
        String alt = element.attr("alt");
        boolean explicitlyDecorative =
                "presentation".equalsIgnoreCase(element.attr("role"))
                        || "true".equalsIgnoreCase(element.attr("aria-hidden"))
                        || (element.hasAttr("alt") && alt.isBlank());
        boolean hasMeaning =
                !content.text().isBlank()
                        || content.linkHref() != null
                        || selectorStabilityScorer.hasStableDataAttribute(element)
                        || !element.attr("title").isBlank();
        return explicitlyDecorative && !hasMeaning;
    }

    private VisualElementCandidate.BBox bbox(Element element, List<String> diagnostics) {
        double x = parseDouble(element.attr("data-visual-x"));
        double y = parseDouble(element.attr("data-visual-y"));
        double width =
                parseDouble(
                        firstNonBlank(element.attr("data-visual-width"), element.attr("width")));
        double height =
                parseDouble(
                        firstNonBlank(element.attr("data-visual-height"), element.attr("height")));
        if (x == 0 && y == 0 && width == 0 && height == 0) {
            diagnostics.add("bbox_unavailable_static_html");
        }
        return new VisualElementCandidate.BBox(x, y, width, height);
    }

    private SelectorChoice stableSelector(Document document, Element element, String fallback) {
        List<String> diagnostics = new ArrayList<>();
        if (!element.id().isBlank()) {
            String selector = "#" + cssIdentifier(element.id());
            if (isUnique(document, selector)) {
                return new SelectorChoice(selector, diagnostics);
            }
            diagnostics.add("non_unique_stable_selector:" + selector);
        }
        for (String attribute : selectorStabilityScorer.stableDataAttributes()) {
            String value = element.attr(attribute);
            if (!value.isBlank()) {
                String selector = "[" + attribute + "=\"" + cssAttribute(value) + "\"]";
                if (isUnique(document, selector)) {
                    return new SelectorChoice(selector, diagnostics);
                }
                diagnostics.add("non_unique_stable_selector:" + selector);
                return new SelectorChoice(fallback, diagnostics);
            }
        }
        String ariaLabel = element.attr("aria-label");
        if (!ariaLabel.isBlank()) {
            String selector =
                    element.tagName() + "[aria-label=\"" + cssAttribute(ariaLabel) + "\"]";
            if (isUnique(document, selector)) {
                return new SelectorChoice(selector, diagnostics);
            }
            diagnostics.add("non_unique_stable_selector:" + selector);
        }
        String classSelector = classSelector(element);
        if (!classSelector.isBlank()) {
            String selector = element.tagName() + classSelector;
            if (isUnique(document, selector)) {
                return new SelectorChoice(selector, diagnostics);
            }
            diagnostics.add("non_unique_stable_selector:" + selector);
            return new SelectorChoice(fallback, diagnostics);
        }
        return new SelectorChoice(fallback, diagnostics);
    }

    private boolean isUnique(Document document, String selector) {
        try {
            return document.select(selector).size() == 1;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String cssPath(Document document, Element element) {
        List<String> parts = new ArrayList<>();
        Element current = element;
        while (current != null && !"#root".equals(current.tagName())) {
            String tag = current.tagName();
            if ("html".equals(tag)) {
                parts.add("html");
                break;
            }
            if (!current.id().isBlank()) {
                String selector = "#" + cssIdentifier(current.id());
                if (isUnique(document, selector)) {
                    parts.add(selector);
                    break;
                }
            }
            String part = tag + classSelector(current);
            int siblingIndex = siblingIndex(current);
            if (siblingIndex > 1 || hasSameTagSibling(current)) {
                part += ":nth-of-type(" + siblingIndex + ")";
            }
            parts.add(part);
            current = current.parent();
        }
        Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    private int siblingIndex(Element element) {
        if (element.parent() == null) {
            return 1;
        }
        int index = 1;
        for (Element sibling : element.previousElementSiblings()) {
            if (sibling.tagName().equals(element.tagName())) {
                index++;
            }
        }
        return index;
    }

    private boolean hasSameTagSibling(Element element) {
        if (element.parent() == null) {
            return false;
        }
        for (Element sibling : element.siblingElements()) {
            if (sibling.tagName().equals(element.tagName())) {
                return true;
            }
        }
        return false;
    }

    private String classSelector(Element element) {
        List<String> classNames =
                element.classNames().stream()
                        .filter(className -> !className.isBlank())
                        .limit(3)
                        .map(this::cssIdentifier)
                        .toList();
        if (classNames.isEmpty()) {
            return "";
        }
        return "." + String.join(".", classNames);
    }

    private int domDepth(Element element) {
        int depth = 0;
        for (Element current = element.parent(); current != null; current = current.parent()) {
            depth++;
        }
        return depth;
    }

    private String role(Element element) {
        String role = element.attr("role");
        if (!role.isBlank()) {
            return role;
        }
        return switch (element.tagName()) {
            case "a" -> "link";
            case "button" -> "button";
            case "img" -> "image";
            case "input", "textarea", "select" -> "form-control";
            default -> null;
        };
    }

    private String type(Element element) {
        String type = element.attr("type");
        return type.isBlank() ? null : type;
    }

    private String signature(ElementCandidateSeed seed) {
        return seed.stableSelector()
                + "|"
                + seed.content().text()
                + "|"
                + seed.content().linkHref()
                + "|"
                + seed.content().imageSrc();
    }

    private String candidateSignature(VisualElementCandidate candidate) {
        if (!candidate.stableSelector().isBlank()) {
            return "selector:" + candidate.stableSelector() + "|repeat:" + candidate.repeatIndex();
        }
        if (candidate.linkHref() != null) {
            return "link:" + candidate.linkHref() + "|" + candidate.text();
        }
        if (candidate.imageSrc() != null) {
            return "image:" + candidate.imageSrc() + "|" + candidate.text();
        }
        return "text:" + candidate.text() + "|" + candidate.tagName();
    }

    private boolean bboxChanged(List<SnapshotCandidate> candidates) {
        if (candidates.size() < 2) {
            return false;
        }
        VisualElementCandidate.BBox first = candidates.getFirst().candidate().bbox();
        return candidates.stream()
                .skip(1)
                .map(item -> item.candidate().bbox())
                .anyMatch(
                        bbox ->
                                Math.abs(first.x() - bbox.x()) > 1
                                        || Math.abs(first.y() - bbox.y()) > 1
                                        || Math.abs(first.width() - bbox.width()) > 1
                                        || Math.abs(first.height() - bbox.height()) > 1);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim().replace("px", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String cssIdentifier(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace(".", "\\.")
                .replace(":", "\\:")
                .replace("#", "\\#")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace(" ", "\\ ");
    }

    private String cssAttribute(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ViewportSnapshot(
            String label, int width, List<VisualElementCandidate> candidates) {
        public ViewportSnapshot {
            label = label == null || label.isBlank() ? String.valueOf(width) : label;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record MissingCandidate(
            String stableSelector,
            String text,
            String linkHref,
            String imageSrc,
            List<String> missingViewportLabels) {
        public MissingCandidate {
            stableSelector = stableSelector == null ? "" : stableSelector;
            text = text == null ? "" : text;
            linkHref = blankToNullStatic(linkHref);
            imageSrc = blankToNullStatic(imageSrc);
            missingViewportLabels =
                    missingViewportLabels == null ? List.of() : List.copyOf(missingViewportLabels);
        }
    }

    public record ViewportComparison(
            List<VisualElementCandidate> stable,
            List<MissingCandidate> missing,
            List<VisualElementCandidate> changed,
            String summary) {
        public ViewportComparison {
            stable = stable == null ? List.of() : List.copyOf(stable);
            missing = missing == null ? List.of() : List.copyOf(missing);
            changed = changed == null ? List.of() : List.copyOf(changed);
            summary = summary == null ? "" : summary;
        }
    }

    private record ElementCandidateSeed(
            Element element,
            String selector,
            String stableSelector,
            CandidateContent content,
            List<String> diagnostics) {
        private ElementCandidateSeed {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private record CandidateContent(String text, String imageSrc, String linkHref) {
        boolean isEmpty() {
            return text.isBlank() && imageSrc == null && linkHref == null;
        }
    }

    private record SnapshotCandidate(ViewportSnapshot snapshot, VisualElementCandidate candidate) {}

    private record SelectorChoice(String selector, List<String> diagnostics) {
        private SelectorChoice {
            selector = selector == null ? "" : selector;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private static String blankToNullStatic(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

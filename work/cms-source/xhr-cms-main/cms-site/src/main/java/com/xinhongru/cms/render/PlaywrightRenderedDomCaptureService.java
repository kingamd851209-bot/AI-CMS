package com.xinhongru.cms.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import com.xinhongru.ai.site.security.UrlSampleSafetyValidator;
import com.xinhongru.ai.site.security.UrlSampleValidationResult;
import com.xinhongru.ai.site.visual.VisualDomSnapshotService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PlaywrightRenderedDomCaptureService implements RenderedDomCaptureService {
    private static final int DEFAULT_VIEWPORT_WIDTH = 1440;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 900;
    private static final double DEFAULT_TIMEOUT_MS = 15_000D;
    private static final int MAX_VISUAL_DOM_SCAN = 300;
    private static final int MAX_VISUAL_CANDIDATES = 300;
    private static final int MAX_VISUAL_TEXT_LENGTH = 240;
    private static final int MAX_VISUAL_ATTRIBUTE_LENGTH = 512;
    private static final int MAX_NETWORK_REQUESTS = 100;
    private static final int MAX_CAPTURE_DIAGNOSTICS = 100;

    private final UrlSampleSafetyValidator urlSampleSafetyValidator;
    private final VisualDomSnapshotService visualDomSnapshotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlaywrightRenderedDomCaptureService(UrlSampleSafetyValidator urlSampleSafetyValidator) {
        this(urlSampleSafetyValidator, new VisualDomSnapshotService());
    }

    @Autowired
    public PlaywrightRenderedDomCaptureService(
            UrlSampleSafetyValidator urlSampleSafetyValidator,
            VisualDomSnapshotService visualDomSnapshotService) {
        this.urlSampleSafetyValidator =
                Objects.requireNonNull(urlSampleSafetyValidator, "urlSampleSafetyValidator");
        this.visualDomSnapshotService =
                Objects.requireNonNull(visualDomSnapshotService, "visualDomSnapshotService");
    }

    @Override
    public RenderedDomCaptureResult capture(RenderedDomCaptureRequest request) {
        Objects.requireNonNull(request, "request");
        int width = viewportOrDefault(request.viewportWidth(), DEFAULT_VIEWPORT_WIDTH);
        int height = viewportOrDefault(request.viewportHeight(), DEFAULT_VIEWPORT_HEIGHT);
        String normalizedUrl = null;
        if (request.pageUrl() != null && !request.pageUrl().isBlank()) {
            UrlSampleValidationResult validation =
                    urlSampleSafetyValidator.validate(request.pageUrl());
            if (!validation.accepted()) {
                return RenderedDomCaptureResult.failed(
                        "NETWORK_BLOCKED",
                        "rendered DOM capture URL was rejected by safety policy",
                        width,
                        height,
                        "{\"source\":\"URL_SAFETY_VALIDATOR\",\"violations\":\""
                                + escape(validation.violations().toString())
                                + "\"}");
            }
            normalizedUrl = validation.normalizedUrl();
        }
        if ((normalizedUrl == null || normalizedUrl.isBlank())
                && (request.htmlContent() == null || request.htmlContent().isBlank())) {
            return RenderedDomCaptureResult.failed(
                    "EMPTY_PAGE",
                    "rendered DOM capture has neither URL nor HTML content",
                    width,
                    height,
                    "{\"source\":\"PLAYWRIGHT_RENDERER\"}");
        }
        EvidenceBudget evidenceBudget = new EvidenceBudget();
        List<Map<String, Object>> networkRequests = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String guardedHtml =
                guardStaticSubresourceUrls(
                        request.htmlContent(), networkRequests, diagnostics, evidenceBudget);
        Path screenshotPath = screenshotPath(request);
        try (Playwright playwright = Playwright.create();
                Browser browser =
                        playwright
                                .chromium()
                                .launch(new BrowserType.LaunchOptions().setHeadless(true));
                BrowserContext context =
                        browser.newContext(
                                new Browser.NewContextOptions().setViewportSize(width, height))) {
            Page page = context.newPage();
            page.route(
                    "**/*",
                    route ->
                            guardNetworkRequest(
                                    route, networkRequests, diagnostics, evidenceBudget));
            if (guardedHtml != null && !guardedHtml.isBlank()) {
                page.setContent(
                        injectBaseTag(guardedHtml, normalizedUrl),
                        new Page.SetContentOptions()
                                .setTimeout(DEFAULT_TIMEOUT_MS)
                                .setWaitUntil(WaitUntilState.NETWORKIDLE));
            } else {
                page.navigate(
                        normalizedUrl,
                        new Page.NavigateOptions()
                                .setTimeout(DEFAULT_TIMEOUT_MS)
                                .setWaitUntil(WaitUntilState.NETWORKIDLE));
            }
            page.waitForTimeout(250);
            Files.createDirectories(screenshotPath.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
            String renderedHtml = page.content();
            VisualCandidateEvidence visualEvidence =
                    collectVisualCandidates(page, renderedHtml, request.pagePath(), diagnostics);
            Object visualCandidates = visualEvidence.candidates();
            int visualCandidateCount = visualCandidateCount(visualCandidates);
            String screenshotAbsolutePath = screenshotPath.toAbsolutePath().normalize().toString();
            return RenderedDomCaptureResult.captured(
                    renderedHtml,
                    screenshotAbsolutePath,
                    networkSummaryJson(networkRequests, evidenceBudget),
                    width,
                    height,
                    capturedEvidenceJson(
                            request.pagePath(),
                            normalizedUrl,
                            screenshotAbsolutePath,
                            diagnostics,
                            evidenceBudget,
                            visualCandidates,
                            visualEvidence.captureDiagnostics(),
                            visualCandidateCount));
        } catch (RuntimeException ex) {
            return RenderedDomCaptureResult.failed(
                    "BROWSER_UNAVAILABLE",
                    "Playwright rendered DOM capture failed: " + ex.getMessage(),
                    width,
                    height,
                    toJson(
                            Map.of(
                                    "source", "PLAYWRIGHT_RENDERER",
                                    "pagePath", safe(request.pagePath()),
                                    "normalizedUrl", safe(normalizedUrl),
                                    "diagnostics", diagnostics,
                                    "droppedDiagnosticCount",
                                            evidenceBudget.droppedDiagnosticCount(),
                                    "droppedNetworkRequestCount",
                                            evidenceBudget.droppedNetworkRequestCount(),
                                    "networkEvidenceTruncated", evidenceBudget.networkTruncated(),
                                    "exception", ex.getClass().getName())));
        } catch (Exception ex) {
            return RenderedDomCaptureResult.failed(
                    "RENDER_CAPTURE_FAILED",
                    "Playwright rendered DOM capture failed: " + ex.getMessage(),
                    width,
                    height,
                    toJson(
                            Map.of(
                                    "source", "PLAYWRIGHT_RENDERER",
                                    "pagePath", safe(request.pagePath()),
                                    "normalizedUrl", safe(normalizedUrl),
                                    "diagnostics", diagnostics,
                                    "droppedDiagnosticCount",
                                            evidenceBudget.droppedDiagnosticCount(),
                                    "droppedNetworkRequestCount",
                                            evidenceBudget.droppedNetworkRequestCount(),
                                    "networkEvidenceTruncated", evidenceBudget.networkTruncated(),
                                    "exception", ex.getClass().getName())));
        }
    }

    private String guardStaticSubresourceUrls(
            String html,
            List<Map<String, Object>> networkRequests,
            List<String> diagnostics,
            EvidenceBudget evidenceBudget) {
        if (html == null || html.isBlank()) {
            return html;
        }
        Document document = Jsoup.parse(html);
        guardElements(
                document,
                "img[src], script[src], iframe[src], frame[src], source[src], track[src],"
                        + " audio[src], video[src], embed[src]",
                "src",
                networkRequests,
                diagnostics,
                evidenceBudget);
        guardElements(document, "link[href]", "href", networkRequests, diagnostics, evidenceBudget);
        guardElements(
                document, "video[poster]", "poster", networkRequests, diagnostics, evidenceBudget);
        guardElements(
                document, "object[data]", "data", networkRequests, diagnostics, evidenceBudget);
        return document.outerHtml();
    }

    private void guardElements(
            Document document,
            String selector,
            String attribute,
            List<Map<String, Object>> networkRequests,
            List<String> diagnostics,
            EvidenceBudget evidenceBudget) {
        for (Element element : document.select(selector)) {
            String url = element.attr(attribute);
            if (url == null || url.isBlank()) {
                continue;
            }
            if (!isAbsoluteNetworkUrl(url)) {
                continue;
            }
            NetworkDecision decision = decideNetwork(url);
            if (decision.allowed()) {
                continue;
            }
            recordNetwork(
                    networkRequests,
                    evidenceBudget,
                    url,
                    "GET",
                    element.tagName(),
                    true,
                    decision.reason());
            addDiagnostic(
                    diagnostics,
                    evidenceBudget,
                    "blocked static subresource: "
                            + sanitizeUrl(url)
                            + " reason="
                            + decision.reason());
            element.attr(attribute, "about:blank");
        }
    }

    private boolean isAbsoluteNetworkUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private void guardNetworkRequest(
            Route route,
            List<Map<String, Object>> networkRequests,
            List<String> diagnostics,
            EvidenceBudget evidenceBudget) {
        Request request = route.request();
        NetworkDecision decision = decideNetwork(request.url());
        recordNetwork(
                networkRequests,
                evidenceBudget,
                request.url(),
                request.method(),
                request.resourceType(),
                !decision.allowed(),
                decision.reason());
        if (decision.allowed()) {
            route.resume();
            return;
        }
        addDiagnostic(
                diagnostics,
                evidenceBudget,
                "blocked network request: "
                        + sanitizeUrl(request.url())
                        + " reason="
                        + decision.reason());
        route.fulfill(new Route.FulfillOptions().setStatus(204).setBody(""));
    }

    private NetworkDecision decideNetwork(String url) {
        if (url == null || url.isBlank()) {
            return new NetworkDecision(false, "empty_url");
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("about:")
                || trimmed.startsWith("data:")
                || trimmed.startsWith("blob:")) {
            return new NetworkDecision(true, "browser_internal");
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return new NetworkDecision(false, "scheme_not_allowed");
        }
        UrlSampleValidationResult validation = urlSampleSafetyValidator.validate(trimmed);
        if (validation.accepted()) {
            return new NetworkDecision(true, "url_safety_validator_accepted");
        }
        return new NetworkDecision(
                false,
                validation.violations().isEmpty()
                        ? "url_safety_validator_rejected"
                        : validation.violations().getFirst().code());
    }

    private void recordNetwork(
            List<Map<String, Object>> networkRequests,
            EvidenceBudget evidenceBudget,
            String url,
            String method,
            String resourceType,
            boolean blocked,
            String reason) {
        if (networkRequests.size() >= MAX_NETWORK_REQUESTS) {
            evidenceBudget.dropNetworkRequest();
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("url", sanitizeUrl(url));
        request.put("method", method);
        request.put("resourceType", resourceType);
        request.put("blocked", blocked);
        request.put("reason", reason);
        networkRequests.add(request);
    }

    private void addDiagnostic(
            List<String> diagnostics, EvidenceBudget evidenceBudget, String diagnostic) {
        if (diagnostics.size() >= MAX_CAPTURE_DIAGNOSTICS) {
            evidenceBudget.dropDiagnostic();
            return;
        }
        diagnostics.add(diagnostic);
    }

    private String networkSummaryJson(
            List<Map<String, Object>> networkRequests, EvidenceBudget evidenceBudget) {
        return toJson(
                Map.of(
                        "requests",
                        networkRequests,
                        "truncated",
                        evidenceBudget.networkTruncated(),
                        "maxNetworkRequestCount",
                        MAX_NETWORK_REQUESTS,
                        "droppedNetworkRequestCount",
                        evidenceBudget.droppedNetworkRequestCount()));
    }

    private String capturedEvidenceJson(
            String pagePath,
            String normalizedUrl,
            String screenshotAbsolutePath,
            List<String> diagnostics,
            EvidenceBudget evidenceBudget,
            Object visualCandidates,
            Object visualCapture,
            int visualCandidateCount) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "PLAYWRIGHT_RENDERER");
        evidence.put("pagePath", safe(pagePath));
        evidence.put("normalizedUrl", safe(normalizedUrl));
        evidence.put("screenshotPath", screenshotAbsolutePath);
        evidence.put("capturedAt", Instant.now().toString());
        evidence.put("diagnostics", diagnostics);
        evidence.put("droppedDiagnosticCount", evidenceBudget.droppedDiagnosticCount());
        evidence.put("droppedNetworkRequestCount", evidenceBudget.droppedNetworkRequestCount());
        evidence.put("networkEvidenceTruncated", evidenceBudget.networkTruncated());
        evidence.put("visualCandidates", visualCandidates);
        evidence.put("visualCapture", visualCapture);
        evidence.put("visualCandidateCount", visualCandidateCount);
        return toJson(evidence);
    }

    private VisualCandidateEvidence collectVisualCandidates(
            Page page, String renderedHtml, String pagePath, List<String> diagnostics) {
        try {
            Object result =
                    page.evaluate(
                            """
                            (limits) => {
                              const maxScan = limits.maxScan || 300;
                              const maxCandidates = limits.maxCandidates || 300;
                              const maxTextLength = limits.maxTextLength || 240;
                              const maxAttributeLength = limits.maxAttributeLength || 512;
                              const capture = {
                                maxScan,
                                visualCandidateLimit: maxCandidates,
                                textLimit: maxTextLength,
                                attributeLimit: maxAttributeLength,
                                totalElements: document.body ? document.body.getElementsByTagName('*').length : 0,
                                totalScanned: 0,
                                totalCandidates: 0,
                                truncated: false
                              };
                              const nonSelectableTags = new Set(['HTML', 'HEAD', 'BODY', 'SCRIPT', 'STYLE', 'META', 'LINK', 'BASE', 'TITLE', 'NOSCRIPT', 'TEMPLATE']);
                              const contentTags = new Set(['A', 'BUTTON', 'IMG', 'INPUT', 'TEXTAREA', 'SELECT', 'OPTION', 'LABEL', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'P', 'LI', 'TD', 'TH', 'SPAN', 'STRONG', 'EM', 'DIV']);
                              const containerTags = new Set(['DIV', 'SECTION', 'ARTICLE', 'MAIN', 'HEADER', 'FOOTER', 'NAV', 'UL', 'OL', 'TABLE', 'TBODY', 'THEAD', 'TR']);
                              const stableDataAttrs = ['data-cms-field', 'data-field', 'data-bind', 'data-binding', 'data-testid', 'data-test', 'data-id', 'data-action', 'data-key', 'data-name'];
                              const chromeSelector = 'header,footer,nav,[role="banner"],[role="contentinfo"],[role="navigation"],#header,#footer,#nav,#navbar,.header,.footer,.nav,.navbar,.site-header,.site-footer,.topbar';
                              const cssEscape = (value) => {
                                if (window.CSS && window.CSS.escape) return window.CSS.escape(value);
                                return String(value).replace(/([ #.:\\[\\]\\"\\\\])/g, '\\\\$1');
                              };
                              const attrEscape = (value) => String(value).replace(/\\\\/g, '\\\\\\\\').replace(/"/g, '\\\\"');
                              const compact = (value) => String(value || '').replace(/\\s+/g, ' ').trim();
                              const truncate = (value, limit) => {
                                const text = String(value || '');
                                if (text.length <= limit) return text;
                                return text.slice(0, limit);
                              };
                              const first = (...values) => values.find((value) => value && String(value).trim()) || '';
                              const stableDataAttr = (element) => stableDataAttrs.find((attribute) => element.getAttribute(attribute));
                              const classSelector = (element) => Array.from(element.classList || []).slice(0, 3).map(cssEscape).map((name) => '.' + name).join('');
                              const visible = (element) => {
                                if (element.hidden || element.getAttribute('aria-hidden') === 'true') return false;
                                if ((element.getAttribute('type') || '').toLowerCase() === 'hidden') return false;
                                const style = window.getComputedStyle(element);
                                if (!style || style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
                                const rect = element.getBoundingClientRect();
                                return rect.width > 0 && rect.height > 0;
                              };
                              const textOf = (element) => {
                                let own = Array.from(element.childNodes || [])
                                  .filter((node) => node.nodeType === Node.TEXT_NODE)
                                  .map((node) => node.textContent || '')
                                  .join(' ');
                                own = truncate(compact(own), maxTextLength);
                                if (own) return own;
                                if (['A', 'BUTTON', 'LABEL'].includes(element.tagName)) return truncate(compact(element.innerText || element.textContent || ''), maxTextLength);
                                if (element.tagName === 'IMG') return truncate(compact(first(element.getAttribute('alt'), element.getAttribute('title'), element.getAttribute('aria-label'))), maxTextLength);
                                if (containerTags.has(element.tagName)) return '';
                                return truncate(compact(element.innerText || element.textContent || ''), maxTextLength);
                              };
                              const imageSrcOf = (element) => {
                                const image = element.tagName === 'IMG' ? element : element.querySelector('img[src],img[data-src],img[data-original]');
                                return image ? truncate(first(image.getAttribute('src'), image.getAttribute('data-src'), image.getAttribute('data-original')), maxAttributeLength) : '';
                              };
                              const linkHrefOf = (element) => {
                                const link = element.tagName === 'A' ? element : element.closest('a[href]');
                                return link ? truncate(first(link.getAttribute('href')), maxAttributeLength) : '';
                              };
                              const roleOf = (element) => first(element.getAttribute('role'),
                                element.tagName === 'A' ? 'link' : '',
                                element.tagName === 'BUTTON' ? 'button' : '',
                                element.tagName === 'IMG' ? 'image' : '',
                                ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName) ? 'form-control' : '');
                              const domDepth = (element) => {
                                let depth = 0;
                                for (let current = element.parentElement; current; current = current.parentElement) depth++;
                                return depth;
                              };
                              const siblingIndex = (element) => {
                                let index = 1;
                                for (let sibling = element.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
                                  if (sibling.tagName === element.tagName) index++;
                                }
                                return index;
                              };
                              const hasSameTagSibling = (element) => {
                                for (let sibling = element.parentElement ? element.parentElement.firstElementChild : null; sibling; sibling = sibling.nextElementSibling) {
                                  if (sibling !== element && sibling.tagName === element.tagName) return true;
                                }
                                return false;
                              };
                              const cssPath = (element) => {
                                const parts = [];
                                for (let current = element; current && current.nodeType === Node.ELEMENT_NODE; current = current.parentElement) {
                                  const tag = current.tagName.toLowerCase();
                                  if (tag === 'html') {
                                    parts.push('html');
                                    break;
                                  }
                                  if (current.id) {
                                    const selector = '#' + cssEscape(current.id);
                                    if (unique(selector)) {
                                      parts.push(selector);
                                      break;
                                    }
                                  }
                                  let part = tag + classSelector(current);
                                  const index = siblingIndex(current);
                                  if (index > 1 || hasSameTagSibling(current)) part += `:nth-of-type(${index})`;
                                  parts.push(part);
                                }
                                return parts.reverse().join(' > ');
                              };
                              const unique = (selector) => {
                                try { return document.querySelectorAll(selector).length === 1; } catch (error) { return false; }
                              };
                              const stableSelector = (element, fallback) => {
                                const diagnostics = [];
                                if (element.id) {
                                  const selector = '#' + cssEscape(element.id);
                                  if (unique(selector)) return { selector, diagnostics };
                                  diagnostics.push('non_unique_stable_selector:' + selector);
                                }
                                for (const attribute of stableDataAttrs) {
                                  const value = element.getAttribute(attribute);
                                  if (value) {
                                    const selector = `[${attribute}="${attrEscape(truncate(value, maxAttributeLength))}"]`;
                                    if (unique(selector)) return { selector, diagnostics };
                                    diagnostics.push('non_unique_stable_selector:' + selector);
                                    return { selector: fallback, diagnostics };
                                  }
                                }
                                const ariaLabel = element.getAttribute('aria-label');
                                if (ariaLabel) {
                                  const selector = `${element.tagName.toLowerCase()}[aria-label="${attrEscape(truncate(ariaLabel, maxAttributeLength))}"]`;
                                  if (unique(selector)) return { selector, diagnostics };
                                  diagnostics.push('non_unique_stable_selector:' + selector);
                                }
                                const classes = classSelector(element);
                                if (classes) {
                                  const selector = element.tagName.toLowerCase() + classes;
                                  if (unique(selector)) return { selector, diagnostics };
                                  diagnostics.push('non_unique_stable_selector:' + selector);
                                  return { selector: fallback, diagnostics };
                                }
                                return { selector: fallback, diagnostics };
                              };
                              const score = (selector, stable, repeatIndex) => {
                                const evaluated = stable || selector || '';
                                const lower = evaluated.toLowerCase();
                                let value = 58;
                                if (lower.startsWith('#')) value = 100;
                                else if (stableDataAttrs.some((attribute) => lower.includes(`[${attribute}=`))) value = 95;
                                else if (lower.includes('[aria-label=') || lower.includes('[name=')) value = 88;
                                else if (evaluated.includes('.')) value = evaluated.includes('>') ? 68 : 78;
                                value -= (evaluated.match(/>/g) || []).length * 4;
                                value -= (lower.match(/:nth-/g) || []).length * 10;
                                if (repeatIndex > 0) value -= Math.min(25, repeatIndex * 5);
                                return Math.max(0, Math.min(100, value));
                              };
                              const decorativeImage = (element, text, linkHref) => {
                                if (element.tagName !== 'IMG') return false;
                                const explicit = element.getAttribute('role') === 'presentation'
                                  || element.getAttribute('aria-hidden') === 'true'
                                  || (element.hasAttribute('alt') && !element.getAttribute('alt'));
                                const meaningful = Boolean(text || linkHref || stableDataAttr(element) || element.getAttribute('title'));
                                return explicit && !meaningful;
                              };
                              const selectable = (element) => {
                                if (nonSelectableTags.has(element.tagName)) return false;
                                if (!visible(element)) return false;
                                return contentTags.has(element.tagName)
                                  || Boolean(element.id)
                                  || Boolean(stableDataAttr(element))
                                  || Boolean(element.getAttribute('role'))
                                  || Boolean(element.getAttribute('aria-label'));
                              };
                              const seen = new Map();
                              const candidates = [];
                              const elements = document.body ? document.body.getElementsByTagName('*') : [];
                              for (const element of elements) {
                                if (capture.totalScanned >= maxScan) {
                                  capture.truncated = true;
                                  break;
                                }
                                capture.totalScanned++;
                                if (!selectable(element)) continue;
                                const text = textOf(element);
                                const imageSrc = imageSrcOf(element);
                                const linkHref = linkHrefOf(element);
                                if (!text && !imageSrc && !linkHref) continue;
                                if (decorativeImage(element, text, linkHref)) continue;
                                capture.totalCandidates++;
                                if (candidates.length >= maxCandidates) {
                                  capture.truncated = true;
                                  continue;
                                }
                                const selector = cssPath(element);
                                const stableChoice = stableSelector(element, selector);
                                const stable = stableChoice.selector;
                                const signature = `${stable}|${text}|${linkHref}|${imageSrc}`;
                                const repeatIndex = seen.get(signature) || 0;
                                seen.set(signature, repeatIndex + 1);
                                const rect = element.getBoundingClientRect();
                                const diagnostics = Array.from(stableChoice.diagnostics);
                                let selectorScore = score(selector, stable, repeatIndex);
                                if (element.closest(chromeSelector)) {
                                  diagnostics.push('common_chrome_region');
                                  selectorScore = Math.max(0, selectorScore - 12);
                                }
                                candidates.push({
                                  selector,
                                  stableSelector: stable,
                                  selectorScore,
                                  tagName: element.tagName.toLowerCase(),
                                  text,
                                  imageSrc: imageSrc || null,
                                  linkHref: linkHref || null,
                                  bbox: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
                                  domDepth: domDepth(element),
                                  repeatIndex,
                                  visible: true,
                                  role: truncate(roleOf(element) || '', maxAttributeLength) || null,
                                  type: truncate(element.getAttribute('type') || '', maxAttributeLength) || null,
                                  evidence: { source: 'PLAYWRIGHT_LAYOUT' },
                                  diagnostics
                                });
                              }
                              if (capture.totalElements > capture.totalScanned) {
                                capture.truncated = true;
                              }
                              candidates.sort((a, b) => b.selectorScore - a.selectorScore || a.domDepth - b.domDepth || a.selector.localeCompare(b.selector));
                              return { candidates, capture };
                            }
                            """,
                            Map.of(
                                    "maxScan", MAX_VISUAL_DOM_SCAN,
                                    "maxCandidates", MAX_VISUAL_CANDIDATES,
                                    "maxTextLength", MAX_VISUAL_TEXT_LENGTH,
                                    "maxAttributeLength", MAX_VISUAL_ATTRIBUTE_LENGTH));
            if (result instanceof Map<?, ?> map) {
                Object candidates = map.get("candidates");
                Object capture = map.get("capture");
                return new VisualCandidateEvidence(
                        candidates == null ? List.of() : candidates,
                        capture instanceof Map<?, ?>
                                ? capture
                                : defaultVisualCaptureDiagnostics(false, 0, 0));
            }
            return new VisualCandidateEvidence(
                    result == null ? List.of() : result,
                    defaultVisualCaptureDiagnostics(false, 0, 0));
        } catch (RuntimeException ex) {
            diagnostics.add("visual candidate capture fallback: " + ex.getClass().getSimpleName());
            List<?> candidates =
                    visualDomSnapshotService
                            .candidatesFromRenderedHtml(renderedHtml, pagePath)
                            .stream()
                            .limit(MAX_VISUAL_CANDIDATES)
                            .toList();
            return new VisualCandidateEvidence(
                    candidates, defaultVisualCaptureDiagnostics(true, 0, candidates.size()));
        }
    }

    private Map<String, Object> defaultVisualCaptureDiagnostics(
            boolean truncated, int totalScanned, int totalCandidates) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("maxScan", MAX_VISUAL_DOM_SCAN);
        diagnostics.put("visualCandidateLimit", MAX_VISUAL_CANDIDATES);
        diagnostics.put("textLimit", MAX_VISUAL_TEXT_LENGTH);
        diagnostics.put("attributeLimit", MAX_VISUAL_ATTRIBUTE_LENGTH);
        diagnostics.put("totalScanned", totalScanned);
        diagnostics.put("totalCandidates", totalCandidates);
        diagnostics.put("truncated", truncated);
        return diagnostics;
    }

    private int visualCandidateCount(Object visualCandidates) {
        if (visualCandidates instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private Path screenshotPath(RenderedDomCaptureRequest request) {
        String batch = request.batchId() == null ? "unknown-batch" : "batch-" + request.batchId();
        String page =
                request.pageSnapshotId() == null
                        ? "unknown-page"
                        : "page-" + request.pageSnapshotId();
        return Path.of("target", "ai-rendered-dom-captures", batch, page + ".png");
    }

    private String injectBaseTag(String html, String normalizedUrl) {
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            return html;
        }
        String baseTag = "<base href=\"" + escapeAttribute(normalizedUrl) + "\">";
        String lower = html.toLowerCase();
        int headStart = lower.indexOf("<head");
        if (headStart >= 0) {
            int headEnd = lower.indexOf('>', headStart);
            if (headEnd >= 0) {
                return html.substring(0, headEnd + 1) + baseTag + html.substring(headEnd + 1);
            }
        }
        return baseTag + html;
    }

    private static int viewportOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "failed to serialize rendered DOM capture evidence", ex);
        }
    }

    private String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim();
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int end = value.length();
        if (queryIndex >= 0) {
            end = Math.min(end, queryIndex);
        }
        if (fragmentIndex >= 0) {
            end = Math.min(end, fragmentIndex);
        }
        return value.substring(0, end);
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record NetworkDecision(boolean allowed, String reason) {}

    private record VisualCandidateEvidence(Object candidates, Object captureDiagnostics) {}

    private static final class EvidenceBudget {
        private int droppedNetworkRequestCount;
        private int droppedDiagnosticCount;

        void dropNetworkRequest() {
            droppedNetworkRequestCount++;
        }

        void dropDiagnostic() {
            droppedDiagnosticCount++;
        }

        int droppedNetworkRequestCount() {
            return droppedNetworkRequestCount;
        }

        int droppedDiagnosticCount() {
            return droppedDiagnosticCount;
        }

        boolean networkTruncated() {
            return droppedNetworkRequestCount > 0;
        }
    }
}

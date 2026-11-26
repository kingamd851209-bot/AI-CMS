package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 基于 DOM 的只读初始内容提取；不执行脚本、不访问远程资源。 */
@Service
public class TemplateSeedService {
    private static final String METADATA = "head,title,meta,base,link,svg desc";
    private final UiPackageStorageService storage;

    public TemplateSeedService(UiPackageStorageService storage) {
        this.storage = storage;
    }

    public record Page(String sourcePath, String fileName) {}

    public record Item(
            String id,
            String kind,
            String text,
            String value,
            String selector,
            String sourcePath,
            String repeatGroup,
            String context,
            CounterScriptInputs.ScriptInput scriptInput,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean publicNavigation,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String publicRegion,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean interactionPending,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean backgroundBindable,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean nonBodyMetadata) {
        public Item(String id, String kind, String text, String value, String selector, String sourcePath,
                String repeatGroup, String context, CounterScriptInputs.ScriptInput scriptInput,
                Boolean publicNavigation, String publicRegion, Boolean interactionPending, Boolean backgroundBindable) {
            this(id, kind, text, value, selector, sourcePath, repeatGroup, context, scriptInput,
                    publicNavigation, publicRegion, interactionPending, backgroundBindable, null);
        }
        public Item(String id, String kind, String text, String value, String selector, String sourcePath,
                String repeatGroup, String context, CounterScriptInputs.ScriptInput scriptInput,
                Boolean publicNavigation, String publicRegion, Boolean interactionPending) {
            this(id, kind, text, value, selector, sourcePath, repeatGroup, context, scriptInput,
                    publicNavigation, publicRegion, interactionPending, null);
        }
        public Item(
                String id,
                String kind,
                String text,
                String value,
                String selector,
                String sourcePath,
                String repeatGroup,
                String context,
                CounterScriptInputs.ScriptInput scriptInput,
                Boolean publicNavigation) {
            this(
                    id,
                    kind,
                    text,
                    value,
                    selector,
                    sourcePath,
                    repeatGroup,
                    context,
                    scriptInput,
                    publicNavigation,
                    null,
                    null);
        }

        public Item(
                String id,
                String kind,
                String text,
                String value,
                String selector,
                String sourcePath,
                String repeatGroup,
                String context,
                CounterScriptInputs.ScriptInput scriptInput) {
            this(
                    id,
                    kind,
                    text,
                    value,
                    selector,
                    sourcePath,
                    repeatGroup,
                    context,
                    scriptInput,
                    null);
        }

        public Item(
                String id,
                String kind,
                String text,
                String value,
                String selector,
                String sourcePath,
                String repeatGroup,
                String context) {
            this(id, kind, text, value, selector, sourcePath, repeatGroup, context, null);
        }

        public Item(
                String id,
                String kind,
                String text,
                String value,
                String selector,
                String sourcePath,
                String repeatGroup) {
            this(id, kind, text, value, selector, sourcePath, repeatGroup, text);
        }
    }

    public record Preview(
            String sourcePath,
            String title,
            String method,
            List<Item> items,
            List<String> warnings,
            String digest) {}

    public List<Page> pages(String code, String version) {
        return storage.listSeedPages(code, version).stream()
                .filter(f -> isHtml(f.relativePath()))
                .map(f -> new Page(f.relativePath(), f.fileName()))
                .toList();
    }

    private boolean isHtml(String path) {
        return StaticPageSource.supports(path);
    }

    /** 同一原文摘要的只读DOM关系快照；不把CSS选择器字符串当祖先关系。 */
    public java.util.Map<String, java.util.Set<String>> sourceAncestors(
            String code, String version, String path, String digest, List<String> selectors) {
        var source = storage.readStaticPage(code, version, path);
        if (!java.util.Objects.equals(digest, source.digest()))
            throw new IllegalArgumentException("页面已变化，请重新读取DOM证据");
        var document = Jsoup.parse(source.html());
        var nodes = new java.util.LinkedHashMap<String, Element>();
        if (selectors.size() > 2000) throw new IllegalArgumentException("DOM关系来源超限");
        for (String selector : selectors) {
            var matches = document.select(selector);
            if (matches.size() != 1) throw new IllegalArgumentException("DOM来源定位不唯一，请重新读取证据");
            nodes.put(selector, matches.getFirst());
        }
        var result = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        var reverse = new java.util.IdentityHashMap<Element, java.util.Set<String>>();
        nodes.forEach(
                (selector, node) ->
                        reverse.computeIfAbsent(node, key -> new java.util.HashSet<>())
                                .add(selector));
        nodes.forEach(
                (selector, node) -> {
                    var ancestors = new java.util.HashSet<String>();
                    int depth = 0;
                    for (Element current = node; current != null; current = current.parent()) {
                        if (++depth > 32) throw new IllegalArgumentException("DOM关系深度超限");
                        ancestors.addAll(reverse.getOrDefault(current, java.util.Set.of()));
                    }
                    result.put(selector, java.util.Set.copyOf(ancestors));
                });
        return java.util.Map.copyOf(result);
    }

    public Preview preview(String code, String version, String sourcePath) {
        if (sourcePath == null || !isHtml(sourcePath)) {
            throw new IllegalArgumentException("仅支持静态 HTML/HTM/ASPX/ASP 页面");
        }
        var source = storage.readStaticPage(code, version, sourcePath);
        return preview(sourcePath, source, path -> storage.readInspectionResource(code, version, path));
    }

    Preview preview(String sourcePath, StaticPageSource.Page source, java.util.function.Function<String, byte[]> resource) {
        String html = source.html();
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        List<Item> regions = new ArrayList<>();
        List<Item> editableRegions = new ArrayList<>();
        List<Element> regionElements = new ArrayList<>();
        List<String> warnings = new ArrayList<>(source.warnings());
        boolean preprocessingLimited = false;
        // 定位和上下文必须取自原DOM，删除兄弟节点后nth-child会变化。
        var selectors = new java.util.IdentityHashMap<Element, String>();
        var contexts = new java.util.IdentityHashMap<Element, String>();
        var groups = new java.util.IdentityHashMap<Element, String>();
        int scanned = 0;
        for (Element element : document.body().getAllElements()) {
            if (++scanned > 2000) {
                preprocessingLimited = true;
                break;
            }
            int depth = 0;
            boolean expensive = false;
            for (Element node = element; node != null; node = node.parent()) {
                if (++depth > 32
                        || (node.parent() != null && node.parent().childrenSize() > 2000)) {
                    expensive = true;
                    break;
                }
            }
            if (expensive) {
                preprocessingLimited = true;
                continue;
            }
            selectors.put(element, element.cssSelector());
            if (EditorRegions.candidate(element) && regionElements.size() < 100)
                regionElements.add(element);

            groups.put(element, repeatGroup(element));
            Element region = element;
            while (!region.isBlock() && region.parent() != null) region = region.parent();
            String context = boundedRegionText(region);
            if (context == null) {
                preprocessingLimited = true;
                context = "区域上下文超过预处理限制，请结合隔离预览核对";
            }
            contexts.put(element, context.length() > 1000 ? context.substring(0, 1000) : context);
        }
        for (Element region : regionElements) {
            if (regionElements.stream()
                    .anyMatch(other -> other != region && other.parents().contains(region)))
                continue;
            if (regions.size() >= 30) {
                warnings.add("HTML区域候选超过30项，其余未覆盖");
                break;
            }
            String raw = region.html();
            if (raw.isEmpty() || raw.length() > 200000) {
                warnings.add("HTML区域原文为空或超过200000字，未覆盖");
                continue;
            }
            String selector = selectors.get(region);
            String id =
                    "region-"
                            + java.util.UUID.nameUUIDFromBytes(
                                    selector.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String reason = EditorRegions.problem(region);
            regions.add(
                    new Item(
                            id,
                            reason == null ? "HTML_REGION" : "HTML_REGION_PENDING",
                            region.text(),
                            raw,
                            selector,
                            sourcePath,
                            null,
                            reason == null ? "完整HTML来源，须人工确认独立正文区域" : reason));
            var fixedLayout = EditorRegions.fixedLayout(region);
            if (fixedLayout != null && fixedLayout.bodyHtml().length() <= 200000)
                editableRegions.add(
                        new Item(
                                id + "-editable",
                                "HTML_REGION",
                                region.text(),
                                fixedLayout.bodyHtml(),
                                selector,
                                sourcePath,
                                null,
                                "完整正文来源，固定排版样式由模板保留，须核对清洗差异"));
        }
        if (regionElements.size() >= 100) warnings.add("HTML区域预候选达到100项，后续未覆盖");
        document.select("script,style,noscript,template,[hidden],[aria-hidden=true]").remove();
        List<Item> items = new ArrayList<>();
        warnings.add("页面清单最多显示 500 页、目录深度最多 16 层；单页最多 2 MB、提取项最多 1000 条。");
        warnings.add("仅提取静态 DOM；未执行脚本，未分析 CSS 可见性，未调用 AI。重复分组仅为同级结构候选，需人工确认。");
        warnings.add("资源地址保留原始值；尚未映射栏目模型或导入内容。");
        int inspected = 0;
        for (Element element : document.body().getAllElements()) {
            if (++inspected > 2000) {
                preprocessingLimited = true;
                break;
            }
            if (!selectors.containsKey(element)) continue;
            if (items.size() >= 1000) {
                warnings.add("提取项超过 1000，结果已截断。");
                break;
            }
            String group = groups.get(element);
            if (element.tagName().equals("img")) {
                String src =
                        element.hasAttr("src") ? element.attr("src") : element.attr("data-src");
                if (!src.isBlank())
                    add(
                            items,
                            "IMAGE",
                            element.attr("alt"),
                            src,
                            selectors.get(element),
                            sourcePath,
                            group,
                            contexts.get(element));
            }
            if (element.tagName().equals("a") && element.hasAttr("href")) {
                add(
                        items,
                        "LINK",
                        element.text(),
                        element.attr("href"),
                        selectors.get(element),
                        sourcePath,
                        group,
                        contexts.get(element),
                        publicNavigationAnchor(element));
            }
            // 内联标签经常拆开数值与限定词；同一文本区域保留完整原文，不推算含义。
            boolean inlineRegion = element.children().stream().noneMatch(Element::isBlock);
            String text = inlineRegion ? boundedRegionText(element) : element.ownText().trim();
            if (text == null) {
                preprocessingLimited = true;
                text = "";
            }
            if (!text.isEmpty())
                add(
                        items,
                        "TEXT",
                        text,
                        text,
                        selectors.get(element),
                        sourcePath,
                        group,
                        contexts.get(element),
                        publicNavigationLabel(element, text));
        }
        for (Item region : regions) {
            if (items.size() >= 1000) {
                warnings.add("HTML区域超出来源总上限1000，未覆盖");
                break;
            }
            items.add(region);
        }
        warnings.add("HTML区域候选限原DOM前2000节点、每区256节点、100预候选与30输出；超出范围未覆盖，不能声称整页正文已识别。");
        if (!regions.isEmpty()) warnings.add("HTML区域保留完整原文证据；包含布局或固定样式的区域待处理，不能声称全正文已绑定。");
        boolean nativeMediaFound = false;
        for (Element element : document.select("video,video source")) {
            String selector = selectors.get(element);
            if (selector == null) continue;
            for (String attribute :
                    element.tagName().equals("video") ? List.of("src", "poster") : List.of("src")) {
                if (!element.hasAttr(attribute) || element.attr(attribute).isBlank()) continue;
                if (items.size() >= 1000) {
                    warnings.add("媒体来源超出总上限1000，其余未覆盖");
                    break;
                }
                String kind =
                        element.tagName().equals("source")
                                ? "VIDEO_SOURCE"
                                : attribute.equals("poster") ? "VIDEO_POSTER" : "VIDEO_SRC";
                String id =
                        "media-"
                                + java.util.UUID.nameUUIDFromBytes(
                                        (selector + "\0" + kind)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String context =
                        contexts.getOrDefault(element.parent(), contexts.getOrDefault(element, ""));
                items.add(
                        new Item(
                                id,
                                kind,
                                element.attr("title"),
                                element.attr(attribute),
                                selector,
                                sourcePath,
                                groups.get(element),
                                "原生媒体属性 " + element.tagName() + "." + attribute + "；" + context));
                nativeMediaFound = true;
            }
        }
        if (nativeMediaFound)
            warnings.add(
                    "原生媒体仅登记src/source/poster原始证据，未播放、未访问远程地址；须按业务语义人工确认，不能默认首张logo为封面。背景与脚本触发媒体尚未绑定。");
        if (preprocessingLimited)
            warnings.add(
                    "原DOM预处理范围不足：最多2000节点、32层定位、每层2000兄弟节点；上下文最多256节点/10000字。超出部分未处理，不能声称整页已识别，请分页面核对。");
        var scripts =
                CounterScriptInputs.inspect(sourcePath, html, resource);
        warnings.addAll(scripts.warnings());
        var located =
                scripts.readings().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        CounterScriptInputs.Reading::selector, reading -> reading));
        var represented = new java.util.HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            var reading = located.get(item.selector());
            if (reading != null && item.kind().equals("TEXT")) {
                items.set(
                        i,
                        new Item(
                                item.id(),
                                reading.pending() ? "SCRIPT_PENDING" : "TEXT",
                                item.text(),
                                item.value(),
                                item.selector(),
                                item.sourcePath(),
                                item.repeatGroup(),
                                item.context() + "；" + reading.scriptInput().reason(),
                                reading.scriptInput(),
                                item.publicNavigation()));
                represented.add(item.selector());
            }
        }
        for (var reading : scripts.readings()) {
            if (represented.contains(reading.selector())
                    || items.size() >= 1000
                    || reading.rawValue().isBlank()) continue;
            items.add(
                    new Item(
                            "script-"
                                    + java.util.UUID.nameUUIDFromBytes(
                                            reading.selector()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)),
                            "SCRIPT_PENDING",
                            "脚本来源待人工核对",
                            reading.rawValue(),
                            reading.selector(),
                            sourcePath,
                            null,
                            reading.scriptInput().reason(),
                            reading.scriptInput()));
        }
        // 可编辑投影使用独立标识并在末尾追加，保留旧待审来源及既有序号。
        int editableRegionSlots = Math.min(30 - regions.size(), 1000 - items.size());
        items.addAll(editableRegions.subList(0, Math.min(editableRegionSlots, editableRegions.size())));
        if (editableRegions.size() > editableRegionSlots) warnings.add("正文投影超过来源上限，未覆盖部分待审核");
        appendBackgroundEvidence(document, selectors, groups, sourcePath, items, warnings);
        var originalElements = new java.util.HashMap<String, Element>();
        selectors.forEach((element, selector) -> originalElements.put(selector, element));
        items.replaceAll(item -> classify(item, originalElements.get(item.selector())));
        if (items.stream().anyMatch(item -> item.publicRegion() != null))
            warnings.add("页头页尾仅保留来源证据和已保存判断，不参与本页字段识别。");
        if (items.stream().anyMatch(item -> Boolean.TRUE.equals(item.interactionPending())))
            warnings.add("占位链接和明确的交互控件保留原文，需单独核对交互。");
        return new Preview(
                sourcePath, document.title(), "DOM_RULES", items, warnings, source.digest());
    }

    private static void appendBackgroundEvidence(Document document,
            java.util.Map<Element, String> selectors, java.util.Map<Element, String> groups,
            String sourcePath, List<Item> items, List<String> warnings) {
        for (var element : document.body().select("[style]")) {
            String selector = selectors.get(element);
            if (selector == null) continue;
            var urls = new java.util.LinkedHashSet<>(SiteModelEvidenceService.inlineBackgroundSources(element.attr("style")));
            for (String url : urls) {
                if (url.isBlank() || url.length() > 2048 || url.regionMatches(true, 0, "data:", 0, 5)) continue;
                if (items.size() >= 1000) {
                    warnings.add("背景图来源超出总上限1000，其余未覆盖");
                    return;
                }
                // 背景凭据独立追加，旧版顺序 ID、人工范围与已审计划均继续可读。
                String id = "background-" + java.util.UUID.nameUUIDFromBytes((selector + "\0" + url)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                boolean bindable = InlineBackgroundSource.isBindable(element.attr("style"), url);
                items.add(new Item(id, "BACKGROUND_IMAGE", element.attr("aria-label"), url, selector,
                        sourcePath, groups.get(element), bindable ? "内联本地背景图，核对用途后可加入图片审核"
                                : "复杂或外部背景，仅保留源证据", null, null, null, null, bindable));
            }
        }
    }

    private static Item classify(Item item, Element element) {
        String region = null;
        if (element != null) {
            var owner = publicRegionOwner(element);
            if (owner != null)
                region = owner.is("header,.header,[role=banner]") ? "HEADER" : "FOOTER";
        }
        boolean interaction =
                "LINK".equals(item.kind()) && actionAddress(item.value())
                        || element != null && isInteractionContent(element, item);
        boolean metadata = element != null && (element.closest(METADATA) != null
                || "TEXT".equals(item.kind()) && element.children().stream().noneMatch(Element::isBlock)
                        && element.selectFirst(METADATA) != null);
        if (region == null && !interaction && !metadata) return item;
        return new Item(
                item.id(),
                item.kind(),
                item.text(),
                item.value(),
                item.selector(),
                item.sourcePath(),
                item.repeatGroup(),
                item.context(),
                item.scriptInput(),
                item.publicNavigation(),
                region,
                interaction ? true : null,
                item.backgroundBindable(),
                metadata ? true : null);
    }

    static boolean actionAddress(String href) {
        String value = href.strip();
        return value.isEmpty()
                || "#".equals(value)
                || value.regionMatches(true, 0, "javascript:", 0, 11);
    }

    private static boolean isInteractionContent(Element element, Item item) {
        if (!java.util.Set.of("TEXT", "SCRIPT_PENDING", "IMAGE").contains(item.kind()))
            return false;
        var control = element.closest("a,button,[role=button]");
        if (control == null
                && !item.kind().equals("IMAGE")
                && element.children().stream().noneMatch(Element::isBlock)) {
            var nested = element.select("a,button,[role=button]");
            if (nested.size() == 1 && item.value().equals(boundedRegionText(nested.first())))
                control = nested.first();
        }
        if (control == null || control.is("a[href]") && !actionAddress(control.attr("href")))
            return false;
        return control.is("[aria-expanded],[aria-controls],[data-toggle],[data-bs-toggle]")
                || control.closest(".search,.search-toggle,[role=search]") != null;
    }

    private static String boundedRegionText(Element region) {
        var pending = new java.util.ArrayDeque<org.jsoup.nodes.Node>();
        pending.push(region);
        var text = new StringBuilder();
        int count = 0;
        while (!pending.isEmpty()) {
            if (++count > 256) return null;
            var node = pending.pop();
            if (node instanceof Element element) {
                if (java.util.Set.of("script", "style", "noscript", "template")
                                .contains(element.tagName())
                        || element.hasAttr("hidden")
                        || element.attr("aria-hidden").equals("true")) continue;
                if (element.tagName().equals("br")) text.append('\n');
            }
            if (node instanceof org.jsoup.nodes.TextNode value) text.append(value.getWholeText());
            if (text.length() > 10000 || node.childNodeSize() > 256) return null;
            for (int i = node.childNodeSize() - 1; i >= 0; i--) pending.push(node.childNode(i));
        }
        return text.toString().trim();
    }

    private void add(
            List<Item> items,
            String kind,
            String text,
            String value,
            String selector,
            String path,
            String group,
            String context) {
        add(items, kind, text, value, selector, path, group, context, false);
    }

    private void add(
            List<Item> items,
            String kind,
            String text,
            String value,
            String selector,
            String path,
            String group,
            String context,
            boolean publicNavigation) {
        if (items.size() < 1000) {
            items.add(
                    new Item(
                            "item-" + (items.size() + 1),
                            kind,
                            text,
                            value,
                            selector,
                            path,
                            group,
                            context,
                            null,
                            publicNavigation ? true : null));
        }
    }

    static boolean publicNavigationAnchor(Element anchor) {
        if (!anchor.is("a[href]") || publicRegionOwner(anchor) == null) return false;
        if (!reviewablePageLink(anchor.attr("href"))
                || anchor.closest(".copyright,.copy-right,.copy,[class*=copyright],[id*=copyright]")
                        != null
                || anchor.text()
                        .matches("(?iu).*(?:ICP\\s*备|公安\\s*备案|公网安备|版权所有|copyright\\s*[©0-9]|©).*"))
            return false;
        return anchor.closest("nav,[role=navigation],.nav,.navigation,.navlist,.nav_list") != null
                || anchor.is(
                        "header ul li a[href],footer ul li a[href],.header ul li a[href],.footer ul"
                            + " li a[href],.header .r.rline > .item > a[href],.header .sub-list"
                            + " .sub-item > a[href],.footer .t > .item > .title a[href],.footer .t"
                            + " > .item > .li a[href]");
    }

    private static boolean reviewablePageLink(String href) {
        if (href.isBlank() || href.length() > 512 || href.startsWith("//")) return false;
        try {
            var uri = java.net.URI.create(href);
            String path = uri.getPath();
            return !uri.isAbsolute()
                    && uri.getRawAuthority() == null
                    && uri.getRawFragment() == null
                    && path != null
                    && path.indexOf('\\') < 0
                    && (path.equals("/") || StaticPageSource.supports(path));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean publicNavigationLabel(Element element, String text) {
        var anchor = element.closest("a[href]");
        if (anchor != null) return publicNavigationAnchor(anchor);
        if (publicRegionOwner(element) == null
                || element.children().stream().anyMatch(Element::isBlock)) return false;
        var anchors = element.select("a[href]");
        if (anchors.size() != 1 || !publicNavigationAnchor(anchors.first())) return false;
        return text.equals(boundedRegionText(anchors.first()));
    }

    static Element publicRegionOwner(Element element) {
        var owner =
                element.closest("header,footer,.header,.footer,[role=banner],[role=contentinfo]");
        if (owner == null) return null;
        // 标题和落款可以属于正文、文章或卡片，不能按头尾标签直接识别为站点公共区。
        var parent = owner.parent();
        if (parent == null) return null;
        if (parent.closest("main,[role=main],.main,article,section,aside,.card") == null)
            return owner;
        // 有的首页用 body 直属 section 包住头部、正文和尾部。
        if (parent.is("body > section:not(main,[role=main],.main,.card)")
                && parent.selectFirst("> header, > .header, > [role=banner]") != null
                && parent.selectFirst("> main, > .main, > [role=main]") != null
                && parent.selectFirst("> footer, > .footer, > [role=contentinfo]") != null)
            return owner;
        return null;
    }

    private String repeatGroup(Element element) {
        // 从当前项向外寻找相同标签/class 的同级区块；组只表示结构重复，不推断业务模型。
        for (Element node = element;
                node != null && !node.tagName().equals("body");
                node = node.parent()) {
            Element parent = node.parent();
            if (parent == null) break;
            String signature = node.tagName() + "." + node.className();
            int count = 0;
            for (Element sibling : parent.children()) {
                if ((sibling.tagName() + "." + sibling.className()).equals(signature)) count++;
            }
            if (count >= 2) return parent.cssSelector() + " > " + signature;
        }
        return null;
    }
}

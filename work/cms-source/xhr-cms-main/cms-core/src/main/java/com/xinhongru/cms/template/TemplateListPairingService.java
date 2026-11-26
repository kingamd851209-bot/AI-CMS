package com.xinhongru.cms.template;

import com.xinhongru.cms.uipackage.StaticPageSource;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.cms.content.record.ContentRecordService;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只读的列表卡片与包内详情证据配对；不推断业务身份、不创建内容或执行脚本。 */
@Service
public class TemplateListPairingService {
    private static final int MAX_CARDS = 20, MAX_DETAILS = 10, MAX_BYTES = 8 * 1024 * 1024;
    private final UiPackageStorageService packages;
    private final TemplateSeedService seeds;
    private final OneShotMapper menus;
    private final ContentRecordService records;
    private final SiteModelPlanStore reviews;
    @org.springframework.beans.factory.annotation.Autowired private com.xinhongru.mapper.ContentMapper contents;

    public TemplateListPairingService(
            UiPackageStorageService packages, TemplateSeedService seeds, OneShotMapper menus,
            ContentRecordService records,
            @Value("${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-site-model") String root) {
        this.packages = packages;
        this.seeds = seeds;
        this.menus = menus;
        this.records = records;
        this.reviews = new SiteModelPlanStore(root, new tools.jackson.databind.ObjectMapper());
    }

    public record Request(
            Long menuId, String listSourcePath, String listDigest, String containerId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String detailPlanId) {
        public Request(Long menuId, String listSourcePath, String listDigest, String containerId) {
            this(menuId, listSourcePath, listDigest, containerId, null);
        }
    }

    public enum Ownership { PLACEHOLDER, EXISTING_RECORD, OTHER_DETAIL, LIST_ONLY, SHARED_DETAIL }

    public record Decision(String cardId, Ownership ownership, Long recordId, String detailPath, String detailDigest) {
        public Decision(String cardId, Ownership ownership, Long recordId, String detailPath) {
            this(cardId, ownership, recordId, detailPath, null);
        }
    }

    public record Review(long revision, String evidenceDigest, List<Decision> decisions) {}

    public record ReviewRequest(Request pairing, long revision, String evidenceDigest, List<Decision> decisions) {}

    public record Container(
            String id,
            String selector,
            String cardTag,
            String cardClass,
            int count,
            String example) {}

    public record Candidates(
            String sourcePath, String digest, List<Container> containers, List<String> warnings) {}

    public record Evidence(
            String sourceId, String kind, String selector, String preview, int originalLength) {}

    public record Source(
            String sourcePath, String digest, List<Evidence> evidence, boolean evidenceComplete) {}

    public record RecordCandidate(Integer recordId, Integer menuId, String title, Integer status,
            String date, boolean bodyExcerptMatched) {}

    public record Item(
            String id,
            String selector,
            List<String> hrefs,
            String status,
            String reason,
            Source list,
            Source detail,
            List<ListPairingComparison.Fact> comparisons,
            String detailBodyHash,
            List<RecordCandidate> recordCandidates) {
        public Item(String id, String selector, List<String> hrefs, String status, String reason,
                Source list, Source detail) {
            this(id, selector, hrefs, status, reason, list, detail, List.of(), null, List.of());
        }
    }

    public record Inspection(
            Long siteId,
            Long menuId,
            String packageVersion,
            String fieldConfigDigest,
            String listSourcePath,
            String listDigest,
            Container container,
            int totalCards,
            int inspectedCards,
            List<Item> items,
            List<String> warnings,
            Review review) {}

    private record Group(Container view, List<Element> cards) {}

    private record Input(
            Document document,
            TemplateSeedService.Preview sources,
            int bytes,
            List<Group> groups,
            List<String> warnings) {}

    public Candidates candidates(
            Long siteId, String code, String version, Long menuId, String path) {
        menu(siteId, menuId);
        var input = input(code, version, path);
        return new Candidates(
                path,
                input.sources().digest(),
                input.groups().stream().map(Group::view).toList(),
                List.copyOf(input.warnings()));
    }

    public Inspection inspect(Long siteId, String code, String version, Request request) {
        return inspect(siteId, code, version, request, null, false, null);
    }

    Inspection inspectForImport(Long site, String code, String version, Request request, String cardId,
            String reviewedDetailPath) {
        var inspection = inspect(site, code, version, request, reviewedDetailPath, false, null);
        var review = reviewFor(site, code, version, request, inspection);
        var decision = review.decisions().stream().filter(row -> row.cardId().equals(cardId))
                .findFirst().orElse(null);
        if (decision == null) return withReview(inspection, inspection.items(), review);
        if (decision.ownership() == Ownership.EXISTING_RECORD) {
            records.requireOwnerRecord(site, Math.toIntExact(request.menuId()), decision.recordId(), false);
            throw new IllegalArgumentException("本卡片已关联已有记录 #" + decision.recordId() + "，请在内容管理中维护");
        }
        if (decision.ownership() == Ownership.SHARED_DETAIL) {
            var current = inspection.items().stream().filter(row -> row.id().equals(cardId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("卡片不在当前检查范围"));
            if (!"SHARED_DETAIL".equals(current.status()))
                throw new IllegalArgumentException("共享详情证据已变化，请重新审核");
            var resolved = new Item(current.id(), current.selector(), current.hrefs(), "MATCHED",
                    "已审核由本卡片保留记录字段，复用包内详情正文", current.list(), current.detail(),
                    current.comparisons(), current.detailBodyHash(), current.recordCandidates());
            return withReview(inspection,
                    inspection.items().stream().map(row -> row.id().equals(cardId) ? resolved : row).toList(), review);
        }
        if (decision.ownership() != Ownership.OTHER_DETAIL)
            throw new IllegalArgumentException("此卡片已审核为占位或仅维护列表，不能导入详情");
        var current = inspection.items().stream().filter(row -> row.id().equals(cardId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("卡片不在当前检查范围"));
        var list = input(code, version, request.listSourcePath());
        if (!list.sources().digest().equals(inspection.listDigest()) || !current.list().evidenceComplete())
            throw new IllegalArgumentException("列表来源或卡片证据已变化，请重新审核");
        var cards = list.document().select(current.selector());
        var alternate = input(code, version, decision.detailPath());
        if (!Objects.equals(decision.detailDigest(), alternate.sources().digest()))
            throw new IllegalArgumentException("已审替代详情来源已变化，请重新保存归属");
        var comparison = cards.size() == 1 ? ListPairingComparison.compare(cards.first(), alternate.document()) : null;
        if (comparison == null || !comparison.titleMatched() || comparison.conflicting())
            throw new IllegalArgumentException("替代详情的标题、日期或摘要不能与原卡片核对，请保留待核");
        checkDigest(code, version, decision.detailPath(), alternate.sources().digest());
        var resolved = new Item(current.id(), current.selector(), current.hrefs(), "MATCHED",
                "已审核并核对独立详情来源", current.list(), source(alternate, null, 50),
                comparison.facts(), comparison.bodyHash(), List.of());
        return withReview(inspection,
                inspection.items().stream().map(row -> row.id().equals(cardId) ? resolved : row).toList(), review);
    }

    private static Inspection withReview(Inspection inspection, List<Item> items, Review review) {
        return new Inspection(inspection.siteId(), inspection.menuId(), inspection.packageVersion(),
                inspection.fieldConfigDigest(), inspection.listSourcePath(), inspection.listDigest(),
                inspection.container(), inspection.totalCards(), inspection.inspectedCards(), items,
                inspection.warnings(), review);
    }

    public Inspection inspectForReview(Long siteId, String code, String version, Request request,
            String reviewedDetailPath, java.util.function.IntPredicate canAccess) {
        var inspection = inspect(siteId, code, version, request, reviewedDetailPath, false, canAccess);
        return withReview(inspection, inspection.items(), reviewFor(siteId, code, version, request, inspection));
    }

    public synchronized Review saveReview(Long siteId, String code, String version, ReviewRequest request,
            String reviewedDetailPath) {
        var pairing = request.pairing();
        var inspection = inspect(siteId, code, version, pairing, reviewedDetailPath, false, null);
        var previous = reviewFor(siteId, code, version, pairing, inspection);
        if (!previous.evidenceDigest().equals(request.evidenceDigest()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "列表归属或证据已变化，请重新读取");
        if (request.decisions() == null || request.decisions().size() > MAX_CARDS)
            throw new IllegalArgumentException("归属审核项数量超限");
        var counts = new HashMap<String, Long>();
        for (var row : inspection.items()) {
            if (row.detail() != null) counts.merge(row.detail().sourcePath().toLowerCase(Locale.ROOT), 1L, Long::sum);
        }
        var allowed = inspection.items().stream()
                .collect(java.util.stream.Collectors.toMap(Item::id, row -> row));
        var decisions = new HashMap<String, Decision>();
        Map<Long, Item> owned = new HashMap<>();
        var detailPaths = new HashSet<String>();
        for (var value : request.decisions()) {
            if (value == null || value.ownership() == null || !allowed.containsKey(value.cardId())
                    || decisions.putIfAbsent(value.cardId(), value) != null)
                throw new IllegalArgumentException("请选择当前检查范围内的不重复卡片");
            if ((value.ownership() == Ownership.EXISTING_RECORD) != (value.recordId() != null)
                    || (value.ownership() == Ownership.OTHER_DETAIL) != (value.detailPath() != null))
                throw new IllegalArgumentException("请选择与归属用途对应的已有记录或独立详情");
            if (value.ownership() == Ownership.SHARED_DETAIL
                    && !"SHARED_DETAIL".equals(allowed.get(value.cardId()).status()))
                throw new IllegalArgumentException("此卡片不具备可审核的共享详情正文来源");
            if (value.recordId() != null) {
                records.requireOwnerRecord(siteId, Math.toIntExact(pairing.menuId()), value.recordId(), false);
                var item = allowed.get(value.cardId());
                var other = owned.putIfAbsent(value.recordId(), item);
                if (other != null && !sameReviewedRecord(item, other))
                    throw new IllegalArgumentException("同一记录的重复卡片须有一致标题、日期与详情来源");
            }
            if (value.detailPath() != null) {
                String path = value.detailPath().toLowerCase(Locale.ROOT);
                if (path.equals(pairing.listSourcePath().toLowerCase(Locale.ROOT)) || counts.containsKey(path)
                        || !detailPaths.add(path) || !StaticPageSource.supports(path))
                    throw new IllegalArgumentException("请选择当前包中不同于共用详情和列表的独立详情页");
                var alternate = packages.readStaticPage(code, version, value.detailPath());
                decisions.put(value.cardId(), new Decision(value.cardId(), value.ownership(), null,
                        value.detailPath(), alternate.digest()));
            }
        }
        var ordered = inspection.items().stream().map(row -> decisions.get(row.id())).filter(Objects::nonNull).toList();
        if (ordered.equals(previous.decisions())) return previous;
        if (previous.revision() != request.revision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "列表归属审核已变化，请重新读取");
        var next = new Review(previous.revision() + 1, previous.evidenceDigest(), ordered);
        reviews.saveListPairingReview(siteId, code, version, reviewKey(pairing), next);
        return next;
    }

    private Review reviewFor(Long siteId, String code, String version, Request pairing, Inspection inspection) {
        var previous = reviews.listPairingReview(siteId, code, version, reviewKey(pairing));
        String sources = inspection.listDigest() + "\n" + inspection.items().stream()
                .map(row -> row.id() + ":" + (row.detail() == null ? "" : row.detail().digest())).toList();
        String legacy = hash(inspection.packageVersion() + "\n" + sources);
        String digest = hash(inspection.packageVersion() + "\n" + inspection.fieldConfigDigest() + "\n" + sources);
        if (previous == null) return new Review(0, digest, List.of());
        if (digest.equals(previous.evidenceDigest())) return previous;
        return new Review(previous.revision(), digest,
                legacy.equals(previous.evidenceDigest()) ? previous.decisions() : List.of());
    }

    private static String reviewKey(Request request) {
        return hash(request.menuId() + "\n" + request.listSourcePath() + "\n" + request.containerId()
                + (request.detailPlanId() == null ? "" : "\n" + request.detailPlanId()));
    }

    /** 仅用于显示样板；可信详情来源由调用方从当前已确认字段计划读取。 */
    Inspection inspectPresentation(
            Long siteId, String code, String version, Request request, String confirmedDetailPath) {
        return inspect(siteId, code, version, request, Objects.requireNonNull(confirmedDetailPath), true, null);
    }

    private Inspection inspect(
            Long siteId, String code, String version, Request request, String confirmedDetailPath,
            boolean presentationOnly, java.util.function.IntPredicate canAccess) {
        if (request == null || request.containerId() == null || request.containerId().length() > 64)
            throw new IllegalArgumentException("请确认服务端列出的卡片容器");
        var menu = menu(siteId, request.menuId());
        String config = ContentFieldConfigDigest.of(menu);
        var input = input(code, version, request.listSourcePath());
        if (!Objects.equals(input.sources().digest(), request.listDigest()))
            throw new IllegalArgumentException("列表来源已变化，请重新选择容器");
        var group =
                input.groups().stream()
                        .filter(g -> g.view().id().equals(request.containerId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("卡片容器已变化或不在本次有界候选内"));
        var warnings = new ArrayList<>(input.warnings());
        warnings.add("请核对所选业务列表。共享详情组会同时对照包内链接和可见标题，记录归属由审核确认。");
        if (group.cards().size() > MAX_CARDS) warnings.add("本次仅检查前20张卡片，其余未覆盖");
        var details = new LinkedHashMap<String, Input>();
        var sharedHints = new HashMap<String,String>();
        var detailTitles = new HashMap<String,List<ListDetailRoleEvidence.Title>>();
        var items = new ArrayList<Item>();
        Map<String, List<RecordCandidate>> candidates = new HashMap<>();
        int bytes = input.bytes();
        int detailAttempts = 0;
        for (var card : group.cards().stream().limit(MAX_CARDS).toList()) {
            var cardWalk = walk(card, 500, 32, false);
            var nodes = cardWalk.elements();
            var hrefs =
                    nodes.stream()
                            .filter(e -> e.normalName().equals("a") && e.hasAttr("href"))
                            .map(e -> e.attr("href"))
                            .distinct()
                            .limit(9)
                            .toList();
            var list = source(input, card, 30);
            String reason = null, target = null;
            if (cardWalk.limited()) reason = "卡片节点超过检查范围，未确认完整链接";
            else if (hasLayoutBoundary(card, nodes)
                    || input.document().selectFirst("base[href]") != null)
                reason = "包含导航、交互或base路径依赖，需人工缩小业务卡片范围";
            else if (hrefs.stream().anyMatch(href -> href.length() > 2048))
                reason = "卡片存在超长链接，未确认详情身份";
            else if (hrefs.isEmpty() || hrefs.size() > 8) reason = "卡片缺少可核对链接或超过8个不同链接";
            else if (nodes.stream()
                    .anyMatch(e -> e.normalName().equals("a") && e.hasAttr("onclick")))
                reason = "链接含脚本点击行为，不能确认静态详情身份";
            var paths = new LinkedHashSet<String>();
            if (reason == null) {
                try {
                    for (String href : hrefs) {
                        paths.add(staticLinkPath(request.listSourcePath(), href));
                    }
                    if (confirmedDetailPath != null) {
                        if (!paths.contains(confirmedDetailPath)) reason = "卡片没有指向已确认详情的包内链接";
                        else if (confirmedDetailPath.equals(request.listSourcePath()))
                            reason = "链接指向列表自身";
                        else target = confirmedDetailPath;
                    } else if (paths.size() != 1) reason = "同一卡片指向多个详情文件，无法唯一配对";
                    else if (paths.iterator().next().equals(request.listSourcePath()))
                        reason = "链接指向列表自身";
                    else target = paths.iterator().next();
                } catch (IllegalArgumentException error) {
                    reason = error.getMessage();
                }
            }
            Source detail = null;
            ListPairingComparison.Evidence comparison = null;
            if (reason == null) {
                try {
                    if (!details.containsKey(target)) {
                        if (detailAttempts >= MAX_DETAILS)
                            throw new IllegalArgumentException("本次最多检查10个详情文件，其余未覆盖");
                        if (bytes >= MAX_BYTES)
                            throw new IllegalArgumentException("累计展开预算已用完，其余未覆盖");
                        detailAttempts++;
                        var next = input(code, version, target);
                        if (bytes + next.bytes() > MAX_BYTES) {
                            bytes = MAX_BYTES;
                            throw new IllegalArgumentException("列表与详情展开结果累计超过8MB，其余未覆盖");
                        }
                        details.put(target, next);
                        bytes += next.bytes();
                    }
                    detail = source(details.get(target), null, 50);
                    if (!presentationOnly)
                        comparison = ListPairingComparison.compare(card, details.get(target).document());
                    if (comparison != null && comparison.conflicting())
                        reason = "原列表与详情的标题、日期或摘要冲突，须审核归属后处理";
                    if (!list.evidenceComplete()
                            || list.evidence().stream().noneMatch(e -> e.kind().equals("LINK")))
                        reason = "卡片来源证据未完整覆盖，不能确认配对";
                } catch (IllegalArgumentException error) {
                    reason = error.getMessage();
                }
            }
            String selector = card.cssSelector();
            if (!presentationOnly && detail != null) {
                var titles = detailTitles.computeIfAbsent(target, path ->
                        ListDetailRoleEvidence.detailHeadings(details.get(path).document()));
                if (reason != null) sharedHints.put(selector, reason);
                else if (!titles.isEmpty()) {
                    var match = card.select("a[href]").stream().map(a -> ListDetailRoleEvidence.matchingTitle(a, titles))
                            .filter(Objects::nonNull).findFirst();
                    sharedHints.put(selector, match.isPresent()
                            ? "卡片文字与共用详情的标题区「" + match.get().heading() + "」吻合。请核对是否复用同一条记录，并将重复展示单独标记。"
                            : "原链接共用详情《" + titles.getFirst().heading() + "》，卡片标题未能与其核对。请另选详情或核对列表用途。");
                }
            }
            var compared = comparison;
            items.add(
                    new Item(
                            hash(request.listSourcePath() + "\n" + selector),
                            selector,
                            hrefs.stream().map(href -> shorten(href, 2048)).toList(),
                            reason == null ? "MATCHED" : "PENDING",
                            reason != null
                                    ? reason
                                    : presentationOnly ? "卡片含已确认的详情链接；仅可作为显示样板"
                                            : confirmedDetailPath == null
                                                    ? "同卡片静态链接唯一指向该包内详情；业务语义仍须人工确认"
                                                    : "已审详情计划确认本卡片的一个链接；其他动作保留原证据",
                            list,
                            detail,
                            comparison == null ? List.of() : comparison.facts(),
                            comparison == null ? null : comparison.bodyHash(),
                            canAccess == null || comparison == null || comparison.detailTitle() == null ? List.of()
                                    : candidates.computeIfAbsent(detail.sourcePath(), ignored ->
                                            recordCandidates(siteId, menu.getId(), compared, canAccess))));
        }
        Map<String, Long> counts = new HashMap<>();
        Map<String, Map<String, Long>> sharedTitles = new HashMap<>();
        for (var item : items) {
            if (item.detail() == null) continue;
            var key = item.detail().sourcePath().toLowerCase(Locale.ROOT);
            counts.merge(key, 1L, Long::sum);
            var title = sharedTitle(item);
            if (title != null) sharedTitles.computeIfAbsent(key, ignored -> new HashMap<>())
                    .merge(title, 1L, Long::sum);
        }
        items.replaceAll(
                item ->
                        !presentationOnly
                                        && item.detail() != null
                                        && counts.get(
                                                        item.detail()
                                                                .sourcePath()
                                                                .toLowerCase(Locale.ROOT))
                                                > 1
                                ? sharedDetail(item, sharedTitles.get(item.detail().sourcePath().toLowerCase(Locale.ROOT)),
                                        sharedHints.get(item.selector()))
                                : item);
        // 再读同一静态展开接缝，避免多次读取期间包或SSI片段变化仍返回混合证据。
        checkDigest(code, version, request.listSourcePath(), input.sources().digest());
        for (var entry : details.entrySet())
            checkDigest(code, version, entry.getKey(), entry.getValue().sources().digest());
        if (!config.equals(ContentFieldConfigDigest.of(menu(siteId, request.menuId()))))
            throw new IllegalArgumentException("栏目字段配置已变化，请重新检查");
        return new Inspection(
                siteId,
                request.menuId(),
                version,
                config,
                request.listSourcePath(),
                input.sources().digest(),
                group.view(),
                group.cards().size(),
                items.size(),
                List.copyOf(items),
                List.copyOf(warnings), null);
    }

    private static boolean sameReviewedRecord(Item one, Item another) {
        return one.detail() != null && another.detail() != null
                && one.detail().sourcePath().equals(another.detail().sourcePath())
                && one.detail().digest().equals(another.detail().digest())
                && List.of(one, another).stream().allMatch(item ->
                        item.comparisons().stream().anyMatch(fact ->
                                "标题".equals(fact.name()) && fact.result() == ListPairingComparison.Result.MATCH)
                        && item.comparisons().stream().noneMatch(fact -> fact.result() == ListPairingComparison.Result.DIFFERENT));
    }

    private static String sharedTitle(Item item) {
        if (item.detailBodyHash() == null || !item.list().evidenceComplete()
                || item.list().evidence().stream().noneMatch(e -> "LINK".equals(e.kind()))) return null;
        try {
            if (item.hrefs().isEmpty() || item.hrefs().stream().anyMatch(href ->
                    !item.detail().sourcePath().equals(staticLinkPath(item.list().sourcePath(), href)))) return null;
        } catch (IllegalArgumentException unverified) {
            return null;
        }
        return item.comparisons().stream()
                .filter(fact -> "标题".equals(fact.name()) && fact.list() != null && fact.detail() != null)
                .map(fact -> fact.list().value()).findFirst().orElse(null);
    }

    private static Item sharedDetail(Item item, Map<String, Long> titles, String reason) {
        var title = sharedTitle(item);
        boolean eligible = title != null && titles.size() > 1 && titles.get(title) == 1;
        return new Item(item.id(), item.selector(), item.hrefs(), eligible ? "SHARED_DETAIL" : "PENDING",
                eligible ? "不同标题的卡片指向同一包内详情，可审核按卡片保留字段、复用正文。"
                        : Objects.requireNonNullElse(reason, "多个卡片共用详情，记录归属待核对"),
                item.list(), item.detail(), item.comparisons(), item.detailBodyHash(), item.recordCandidates());
    }

    private List<RecordCandidate> recordCandidates(Long site, Integer target,
            ListPairingComparison.Evidence evidence, java.util.function.IntPredicate canAccess) {
        if (contents == null || evidence.detailTitle() == null) return List.of();
        var rows = contents.selectPairingTitleCandidates(site, target, evidence.detailTitle());
        if (rows == null) return List.of();
        return rows.stream().filter(row -> canAccess.test(row.getClassid())).map(row -> {
            var record = contents.selectActiveArticleInSiteMenu(site, row.getClassid(), row.getId());
            boolean bodyExcerptMatched = record != null && !evidence.bodyParagraphs().isEmpty()
                    && evidence.bodyParagraphs().stream().limit(2).allMatch(paragraph ->
                            java.util.stream.Stream.concat(
                                            java.util.stream.Stream.of(record.getContent1(), record.getContent2(),
                                                    record.getContent3(), record.getContent4(), record.getContent5(),
                                                    record.getContent6()), record.getExtensionValues().values().stream())
                                    .filter(String.class::isInstance).map(String.class::cast)
                                    .map(value -> ListPairingComparison.clean(Jsoup.parse(value).text()))
                                    .anyMatch(text -> text.contains(paragraph)));
            return new RecordCandidate(row.getId(), row.getClassid(), row.getTitle(), row.getStatus(),
                    row.getCreateTime() == null ? null : row.getCreateTime().toLocalDate().toString(), bodyExcerptMatched);
        }).toList();
    }

    List<String> candidateDetailPaths(Long site, String code, String version, Request request) {
        var paths = new LinkedHashSet<String>();
        for (var item : inspect(site, code, version, request).items())
            for (var href : item.hrefs()) {
                try {
                    String path = staticLinkPath(request.listSourcePath(), href);
                    if (!path.equals(request.listSourcePath())) paths.add(path);
                    if (paths.size() == MAX_DETAILS) return List.copyOf(paths);
                } catch (IllegalArgumentException unsupported) {
                    // 未知动作依据原链接继续显示，不能作为详情候选。
                }
            }
        return List.copyOf(paths);
    }

    static String staticLinkPath(String listPath, String href) {
        if (href.isBlank() || href.contains("?") || href.contains("#"))
            throw new IllegalArgumentException("query或fragment身份未确认，不丢弃后猜配");
        String resolved = EditorContentPreparation.packageReference(listPath, href).substring(4);
        if (!StaticPageSource.supports(resolved)
                || resolved.contains("?")
                || resolved.contains("#")) throw new IllegalArgumentException("链接不是明确的包内静态详情页");
        return resolved;
    }

    private OneshotEntity menu(Long siteId, Long menuId) {
        if (siteId == null
                || siteId <= 0
                || menuId == null
                || menuId <= 0
                || menuId > Integer.MAX_VALUE) throw new IllegalArgumentException("站点或目标栏目无效");
        var menu = menus.selectActiveMenuModelBySite(siteId, menuId.intValue());
        if (menu == null
                || !Objects.equals(menu.getId(), menuId.intValue())
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getPageType(), 1))
            throw new IllegalArgumentException("目标必须是当前站点的有效内容列表栏目");
        return menu;
    }

    private Input input(String code, String version, String path) {
        if (path == null || path.length() > 1000 || !StaticPageSource.supports(path))
            throw new IllegalArgumentException("请选择包内静态页面，路径最多1000字符");
        var page = packages.readStaticPage(code, version, path);
        var sources = seeds.preview(code, version, path);
        if (!page.digest().equals(sources.digest()))
            throw new IllegalArgumentException("页面或包含片段在读取期间变化");
        var document = Jsoup.parse(page.html());
        var warnings = new ArrayList<>(sources.warnings());
        var groups = new ArrayList<Group>();
        var traversal = walk(document.body(), 2000, 16, true);
        var elements = traversal.elements();
        if (traversal.limited()) warnings.add("候选扫描节点或深度达到上限（2000节点/16层），其余区域未覆盖");
        for (var parent : elements) {
            if (parent.childrenSize() > 100) {
                String warning = "存在直接子元素超过100的容器，未纳入本次候选，该部分未覆盖";
                if (!warnings.contains(warning)) warnings.add(warning);
                continue;
            }
            if (parent.childrenSize() < 2) continue;
            Map<String, List<Element>> signatures = new LinkedHashMap<>();
            for (var child : parent.children()) {
                if (Set.of("script", "style", "link", "meta").contains(child.normalName()))
                    continue;
                signatures
                        .computeIfAbsent(
                                child.normalName() + "\n" + child.className(),
                                key -> new ArrayList<>())
                        .add(child);
            }
            for (var cards : signatures.values()) {
                if (cards.size() < 2
                        || cards.stream()
                                .noneMatch(
                                        c ->
                                                boundedElements(c, 100, 16).stream()
                                                        .anyMatch(
                                                                e ->
                                                                        e.normalName().equals("a")
                                                                                && e.hasAttr(
                                                                                        "href"))))
                    continue;
                var first = cards.getFirst();
                String selector = parent.cssSelector();
                if (selector.length() > 1000) continue;
                groups.add(
                        new Group(
                                new Container(
                                        hash(
                                                selector
                                                        + "\n"
                                                        + first.normalName()
                                                        + "\n"
                                                        + first.className()),
                                        selector,
                                        first.normalName(),
                                        first.className(),
                                        cards.size(),
                                        shorten(first.text(), 120)),
                                cards));
                if (groups.size() >= 50) break;
            }
            if (groups.size() >= 50) {
                warnings.add("仅提供前50组结构候选，其余未覆盖");
                break;
            }
        }
        return new Input(
                document,
                sources,
                page.html().getBytes(StandardCharsets.UTF_8).length,
                groups,
                warnings);
    }

    private Source source(Input input, Element card, int limit) {
        Set<String> selectors =
                card == null
                        ? Set.of()
                        : new HashSet<>(
                                boundedElements(card, 500, 32).stream()
                                        .map(Element::cssSelector)
                                        .toList());
        var selected =
                input.sources().items().stream()
                        .filter(i -> card == null || selectors.contains(i.selector()))
                        .toList();
        var evidence =
                selected.stream()
                        .limit(limit)
                        .map(
                                i ->
                                        new Evidence(
                                                i.id(),
                                                i.kind(),
                                                i.selector(),
                                                shorten(i.value(), 300),
                                                i.value() == null ? 0 : i.value().length()))
                        .toList();
        return new Source(
                input.sources().sourcePath(),
                input.sources().digest(),
                evidence,
                selected.size() <= limit);
    }

    private record Walk(List<Element> elements, boolean limited) {}

    private static List<Element> boundedElements(Element root, int limit, int maxDepth) {
        return walk(root, limit, maxDepth, false).elements();
    }

    private static Walk walk(Element root, int limit, int maxDepth, boolean scanCards) {
        var result = new ArrayList<Element>();
        boolean[] limited = {false};
        NodeTraversor.filter(
                new NodeFilter() {
                    public FilterResult head(Node node, int depth) {
                        if (scanCards && node instanceof Element e && skipCardSubtree(e)) {
                            return FilterResult.SKIP_ENTIRELY;
                        }
                        if (result.size() >= limit) {
                            limited[0] = true;
                            return FilterResult.STOP;
                        }
                        if (depth > maxDepth) {
                            limited[0] = true;
                            return FilterResult.SKIP_ENTIRELY;
                        }
                        if (node instanceof Element element) result.add(element);
                        return FilterResult.CONTINUE;
                    }
                },
                root);
        return new Walk(result, limited[0]);
    }

    static boolean skipCardSubtree(Element element) {
        if (TemplateSeedService.publicRegionOwner(element) != null
                || element.is("nav, [role=navigation], [role=tablist], form")) return true;
        // 包内分页没有统一类名。只排除有明显分页标识、且包含多个页码链接的控件。
        if (element.classNames().stream().noneMatch(name ->
                Set.of("page", "pages", "pagination", "pager", "pageout").contains(name.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        return element.select("a[href]").stream().filter(a -> a.text().matches("[0-9]{1,6}")).limit(2).count() == 2
                && element.selectFirst("article,h1,h2,h3,h4,img,picture") == null;
    }

    private boolean hasLayoutBoundary(Element card, List<Element> nodes) {
        for (Element ancestor = card; ancestor != null; ancestor = ancestor.parent())
            if (Set.of("nav", "header", "footer", "aside", "form").contains(ancestor.normalName())
                    || ancestor.attr("role").equals("navigation")) return true;
        return nodes.stream()
                .anyMatch(
                        e ->
                                Set.of("nav", "aside", "form", "button", "input", "select")
                                        .contains(e.normalName()));
    }

    private void checkDigest(String code, String version, String path, String digest) {
        if (!packages.readStaticPage(code, version, path).digest().equals(digest))
            throw new IllegalArgumentException("来源或包含片段已变化，配对证据失效");
    }

    private static String shorten(String value, int length) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), length));
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

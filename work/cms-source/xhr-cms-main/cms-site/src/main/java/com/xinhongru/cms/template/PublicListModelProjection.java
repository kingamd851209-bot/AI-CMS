package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.ContentExtensionHtml;
import com.xinhongru.cms.content.record.ContentExtensionResources;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.*;
import com.xinhongru.entity.*;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.site.search.PublicSearchRouteAccess;
import com.xinhongru.web.resonseVo.IdEntity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;

/** 只投影已确认的公开列表字段；不向模板暴露查询实体或私有草稿值。 */
@Service
public class PublicListModelProjection {
    private final QueryBindingRuntime runtime;
    private final ContentMapper contents;
    private final ContentExtensionResources resources;
    private final ContentLinkService links;
    private final PublicSearchRouteAccess routes;
    @org.springframework.beans.factory.annotation.Autowired
    private PublicDetailAvailability detailAvailability;

    public PublicListModelProjection(
            QueryBindingRuntime runtime,
            ContentMapper contents,
            ContentExtensionResources resources,
            ContentLinkService links,
            PublicSearchRouteAccess routes) {
        this.runtime = runtime;
        this.contents = contents;
        this.resources = resources;
        this.links = links;
        this.routes = routes;
    }

    public record Model(
            List<Map<String, Object>> rows,
            Map<String, Object> page,
            Map<String, Object> form,
            List<QueryBindingRuntime.PageLink> numbers) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(
            TemplateQueryBindingService.Snapshot snapshot, IdEntity context, OneshotEntity menu) {
        Long site = snapshot.siteId();
        assertListContext(site, context, menu);
        if (!Objects.equals(snapshot.menuId(), menu.getId().longValue())
                || !Objects.equals(snapshot.fieldConfigDigest(), ContentFieldConfigDigest.of(menu)))
            throw new IllegalArgumentException("时间轴计划与当前栏目配置不一致");
        var timeline = snapshot.presentation().request().timeline();
        var imageField =
                timeline.imageField() == null
                        ? null
                        : ContentModelCodec.read(menu.getModelField()).stream()
                                .filter(
                                        field ->
                                                Objects.equals(
                                                        field.getFieldName(),
                                                        timeline.imageField()))
                                .filter(QueryTimeline::imageField)
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("记录图片字段已停用或不支持"));
        Comparator<String> order = Comparator.naturalOrder();
        var groups =
                new TreeMap<String, List<Object>>(timeline.ascending() ? order : order.reversed());
        for (var row : contents.selectPublicTimelineRows(site, menu.getId())) {
            assertRow(site, menu, row, false);
            String group = value(row, timeline.groupField());
            String event = value(row, timeline.rowField());
            if (group == null || group.isBlank() || event == null || event.isBlank()) continue;
            String secondary = timeline.secondaryField() == null
                    ? null
                    : value(row, timeline.secondaryField());
            Object projected =
                    imageField == null && timeline.secondaryField() == null
                            ? event.strip()
                            : Map.of(
                                    "text",
                                    event.strip(),
                                    "secondary",
                                    secondary == null ? "" : secondary.strip(),
                                    "image",
                                    imageField == null ? "" : timelineImage(row, imageField, site));
            groups.computeIfAbsent(group.strip(), key -> new ArrayList<>()).add(projected);
        }
        return groups.entrySet().stream()
                .map(
                        entry ->
                                Map.<String, Object>of(
                                        "label",
                                        entry.getKey(),
                                        "rows",
                                        List.copyOf(entry.getValue())))
                .toList();
    }

    private String timelineImage(ContentEntity row, ContentFieldDefinition field, Long site) {
        try {
            return resources.prepareTemplateMedia(field, value(row, field.getFieldName()), site);
        } catch (IllegalArgumentException invalidImage) {
            // 旧记录的空值、失效或越权资源不阻断同组其他事件。
            return "";
        }
    }

    @Transactional(readOnly = true)
    public Model query(
            Long site,
            String code,
            String version,
            String queryId,
            IdEntity context,
            OneshotEntity menu) {
        assertListContext(site, context, menu);
        var result = runtime.query(site, code, version, queryId, context);
        var snapshot = result.snapshot();
        if (snapshot == null
                || !Objects.equals(snapshot.id(), queryId)
                || !Objects.equals(snapshot.siteId(), site)
                || !Objects.equals(snapshot.code(), code)
                || !Objects.equals(snapshot.packageVersion(), version)
                || !Objects.equals(snapshot.menuId(), menu.getId().longValue())
                || !Objects.equals(snapshot.fieldConfigDigest(), ContentFieldConfigDigest.of(menu))
                || snapshot.presentation() == null)
            throw new IllegalArgumentException("查询计划与公开栏目配置不一致");
        if (snapshot.sharedLayout() == null && !result.page().getRows().isEmpty() && !routes.isPublicRoute(site, code, menu))
            throw new IllegalArgumentException("当前栏目没有生效的公开详情路由");
        var definitions = ContentModelCodec.read(menu.getModelField());
        var selected = new LinkedHashMap<String, ContentFieldDefinition>();
        for (var field : snapshot.presentation().fields()) {
            var definition =
                    definitions.stream()
                            .filter(f -> Objects.equals(f.getFieldName(), field.fieldName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("显示字段不存在"));
            if (!ContentFieldDefinition.enabled(definition.getIsOpen())
                    || !Objects.equals(definition.getFieldType(), field.fieldType())
                    || !Set.of("SingleLine", "MultiLine", "Editor", "Image", "Video", "DateTime")
                            .contains(field.fieldType())
                    || "DateTime".equals(field.fieldType())
                            && !ContentExtensionFields.isName(field.fieldName())
                    || Set.of("Image", "Video").contains(field.fieldType())
                            && !ContentExtensionFields.isName(field.fieldName())
                            && !NativeImageFields.supports(definition))
                throw new IllegalArgumentException("显示字段已停用、类型变化或尚不支持");
            selected.put(field.fieldName(), definition);
        }
        boolean sharedDetailReady = snapshot.sharedLayout() != null && !result.page().getRows().isEmpty()
                && detailAvailability.detailTemplateAvailable(site, code, version, menu);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : result.page().getRows()) {
            assertRow(site, menu, row, false);
            ContentEntity full = null;
            if (selected.values().stream()
                    .anyMatch(
                            f ->
                                    "Editor".equals(f.getFieldType())
                                            && !ContentExtensionFields.isName(f.getFieldName()))) {
                full = contents.selectActiveArticleInSiteMenu(site, menu.getId(), row.getId());
                assertRow(site, menu, full, true);
                if (!Objects.equals(full.getId(), row.getId()))
                    throw new IllegalArgumentException("完整正文记录身份不一致");
            }
            var text = new LinkedHashMap<String, String>();
            var html = new LinkedHashMap<String, String>();
            var media = new LinkedHashMap<String, String>();
            for (var entry : selected.entrySet()) {
                var field = entry.getValue();
                String name = entry.getKey();
                var owner = full != null && !ContentExtensionFields.isName(name) ? full : row;
                String value = value(owner, name);
                switch (field.getFieldType()) {
                    case "DateTime" -> {
                        ContentDateTimes.validateValue(field, value);
                        text.put(name, value == null ? "" : value);
                    }
                    case "Editor" -> html.put(name, ContentExtensionHtml.clean(value));
                    case "Image", "Video" ->
                            media.put(name, resources.prepareTemplateMedia(field, value, site));
                    default -> text.put(name, value == null ? "" : value);
                }
            }
            var actionUrls = new LinkedHashMap<String, String>();
            if (snapshot.presentation().actions() != null)
                for (var action : snapshot.presentation().actions())
                    actionUrls.put(action.sourceId(),
                            formActionUrl(action, text.get(action.fieldName())));
            var view = new LinkedHashMap<String, Object>();
            view.put("text", Map.copyOf(text));
            view.put("html", Map.copyOf(html));
            view.put("media", Map.copyOf(media));
            if (snapshot.sharedLayout() == null)
                view.put("detailUrl", localUrl(links.getDetailUrl(row, menu)));
            else view.put("detailUrl", sharedDetailReady ? detailAvailability.sharedDetailUrl(site, code, menu, row) : null);
            if (!actionUrls.isEmpty()) view.put("actionUrls", Map.copyOf(actionUrls));
            rows.add(Collections.unmodifiableMap(view));
        }
        var page = result.page();
        var paging = new LinkedHashMap<String, Object>();
        paging.put("page", page.getPage());
        paging.put("total", page.getTotal());
        paging.put("pageCount", page.getPageCount());
        paging.put("firstUrl", localUrl(page.getFirstUrl()));
        paging.put("prevUrl", localUrl(page.getPrevUrl()));
        paging.put("nextUrl", localUrl(page.getNextUrl()));
        paging.put("lastUrl", localUrl(page.getLastUrl()));
        for (var number : result.numbers()) localUrl(number.url());
        return new Model(
                List.copyOf(rows),
                Map.copyOf(paging),
                result.form(),
                List.copyOf(result.numbers()));
    }

    private static void assertListContext(Long site, IdEntity context, OneshotEntity menu) {
        if (context == null
                || !Objects.equals(context.getServerSiteProjectId(), site)
                || !Objects.equals(context.getSiteProjectId(), site)
                || menu == null
                || !Objects.equals(menu.getSiteProjectId(), site)
                || !Objects.equals(context.getClassId(), menu.getId())
                || !Objects.equals(menu.getPageType(), 1)
                || !Boolean.TRUE.equals(menu.getHasFrontChannel()))
            throw new IllegalArgumentException("公开列表需要当前站点受信栏目身份");
    }

    private static void assertRow(Long site, OneshotEntity menu, ContentEntity row, boolean full) {
        if (row == null
                || row.getId() == null
                || row.getId() <= 0
                || !Objects.equals(row.getClassid(), menu.getId())
                || row.getSiteProjectId() != null && !Objects.equals(row.getSiteProjectId(), site)
                || !Objects.equals(row.getStatus(), 1))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        var now = LocalDateTime.now();
        if (full
                && (row.getEntryIntoForceTime() == null
                        || row.getExpirationTime() == null
                        || row.getEntryIntoForceTime().isAfter(now)
                        || row.getExpirationTime().isBefore(now)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
    }

    private static String value(ContentEntity row, String name) {
        Object value;
        if (ContentExtensionFields.isName(name)) value = row.getExtensionValues().get(name);
        else
            try {
                var field = EntityFields.find(ContentEntity.class, name);
                field.setAccessible(true);
                value = field.get(row);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("显示字段不可读取", error);
            }
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("显示字段须为文本");
        return (String) value;
    }

    static String formActionUrl(FormActionBinding.Bound action, String value) {
        if (!FormActionBinding.localRoute(action.route()) || !FormActionBinding.allowedParameter(action.parameterName()))
            throw new IllegalArgumentException("预填参数或公开路由无效");
        if (!FormActionBinding.acceptsValue(value)) return action.route();
        return action.route() + "?" + action.parameterName() + "="
                + org.springframework.web.util.UriUtils.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String localUrl(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("//")
                || value.indexOf('\\') >= 0
                || value.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("公开链接须为本地规范路径");
        try {
            var uri = new URI(value);
            if (uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().isBlank()
                    || uri.getPath().chars().anyMatch(c -> Character.isISOControl(c)))
                throw new IllegalArgumentException("公开链接须为本地规范路径");
            for (String segment : uri.getPath().split("/", -1))
                if (segment.equals("..")
                        || segment.equals(".")
                        || segment.contains("\\")
                        || segment.indexOf('%') >= 0)
                    throw new IllegalArgumentException("公开链接路径无效");
            return value.startsWith("/") ? value : "/" + value;
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("公开链接路径无效", error);
        }
    }
}

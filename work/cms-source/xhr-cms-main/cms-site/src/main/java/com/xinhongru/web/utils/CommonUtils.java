package com.xinhongru.web.utils;

import com.alibaba.fastjson.JSON;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.vo.list.Pager;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.utils.StringDeal;
import com.xinhongru.web.resonseVo.BannerEntity;
import com.xinhongru.web.resonseVo.ChildCompanyDTO;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.resonseVo.GroupByVo;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.service.InfoService;
import com.xinhongru.web.service.MenuService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模板公共工具：格式化、站点范围内的数据查询与链接生成。
 *
 * <p>保留 StringUtils 继承以兼容模板原有的字符串方法；页面专属组装放在对应 Controller。
 */
@Slf4j
@Component("CommonUtils")
public class CommonUtils extends StringUtils {
    @Autowired private ContentLinkService contentLinks;

    private MenuService menuService;
    private InfoService infoService;

    @Autowired
    public void setMenuService(MenuService menuService) {
        this.menuService = menuService;
    }

    @Autowired
    public void setInfoService(InfoService infoService) {
        this.infoService = infoService;
    }

    // 文本格式化

    /** CRLF、CR 和 Tab 统一为换行后转为 br；保留原有 HTML，不负责转义或净化。 */
    public String lineToBr(String str) {
        return isBlank(str) ? "" : normalizeLineBreaks(str).replace("\n", "<br/>");
    }

    /** 包装每行，startHtml 中的 {index} 从 1 开始；与原实现一致，忽略末尾空行。 */
    public String lineToBr(String str, String startHtml, String endHtml) {
        if (isBlank(str)) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        int index = 1;
        for (String line : normalizeLineBreaks(str).split("\n")) {
            html.append(startHtml.replace("{index}", String.valueOf(index++)))
                    .append(line)
                    .append(endHtml);
        }
        return html.toString();
    }

    /** 使用指定 HTML 标签包装每行。 */
    public String lineToBr(String str, String htmlLabel) {
        return lineToBr(str, "<" + htmlLabel + ">", "</" + htmlLabel + ">");
    }

    private String normalizeLineBreaks(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace('\t', '\n');
    }

    /**
     * 字符脱敏
     *
     * @param str 原始字符串
     * @param beginLength 保留前多少位
     * @param endLength 保留后多少位
     * @return 保留前多少位 + 星号 + 保留后多少位
     */
    public static String getSomeStars(String str, int beginLength, int endLength) {
        return getSomeStars(str, beginLength, endLength, WebConst.MAX_STAR_COUNT);
    }

    /**
     * 字符脱敏
     *
     * @param str 原始字符串
     * @param beginLength 保留前多少位
     * @param endLength 保留后多少位
     * @param starMaxCount 最多允许输出多少个*
     * @return 保留前多少位 + 星号 + 保留后多少位
     */
    public static String getSomeStars(
            String str, int beginLength, int endLength, int starMaxCount) {
        if (isBlank(str)) {
            return "";
        }
        StringBuilder masked = new StringBuilder(str.length());
        int starCount = 0;
        for (int i = 0; i < str.length(); i++) {
            if (i >= beginLength && i <= (str.length() - beginLength - endLength + 2)) {
                if (starCount < starMaxCount) {
                    masked.append('*');
                }
                starCount++;
            } else {
                masked.append(str.charAt(i));
            }
        }
        return masked.toString();
    }

    /** 根据ID获取单篇的数据 */
    public OneshotEntity getOneshotEntityById(int id) {
        return menuService.getModelById(id);
    }

    /** 公开聚合页只能读取同站、当前父页下已发布且没有独立路由的内容区块。 */
    public OneshotEntity getPublishedSection(int id, OneshotEntity parent, IdEntity context) {
        var section = menuService.getModelByIdIgnoreChannel(id);
        return publishedSection(section, parent, context) ? section : null;
    }

    private boolean currentPublishedPage(OneshotEntity parent, IdEntity context) {
        return parent != null && context != null && context.getServerSiteProjectId() != null
                && context.getServerSiteProjectId().equals(parent.getSiteProjectId())
                && parent.getId() != null && parent.getId() > 0
                && Objects.equals(context.getClassId(), parent.getId())
                && !Boolean.TRUE.equals(parent.getIsDeleted())
                && Integer.valueOf(0).equals(parent.getPageType())
                && Boolean.TRUE.equals(parent.getHasFrontChannel())
                && Integer.valueOf(3).equals(parent.getStatus());
    }

    private boolean publishedSection(OneshotEntity section, OneshotEntity parent, IdEntity context) {
        if (!currentPublishedPage(parent, context)) return false;
        return section != null && section.getParentId() == parent.getId()
                && Objects.equals(section.getSiteProjectId(), parent.getSiteProjectId())
                && Objects.equals(section.getSiteRootId(), parent.getSiteRootId())
                && !Boolean.TRUE.equals(section.getIsDeleted())
                && Integer.valueOf(0).equals(section.getPageType())
                && Integer.valueOf(3).equals(section.getStatus())
                && Boolean.TRUE.equals(section.getHasManageChannel())
                && Boolean.FALSE.equals(section.getHasFrontChannel())
                && (isBlank(section.getListPageUrl()) || "javascript:void(0);".equals(section.getListPageUrl()))
                && isBlank(section.getDetailPageUrl());
    }

    /** 由当前公开父页的已发布分区读取直属后台原生列表，列表顺序沿用 CMS 原生排序。 */
    public List<ContentEntity> getPublishedSectionItems(int id, OneshotEntity section,
            OneshotEntity parent, IdEntity context) {
        if (!publishedSection(section, parent, context)) return null;
        return publishedChildList(id, section);
    }

    /** 公开单页内的重复业务内容可直接由直属后台列表维护。 */
    public List<ContentEntity> getPublishedPageItems(int id, OneshotEntity parent, IdEntity context) {
        if (!currentPublishedPage(parent, context) && !currentPublishedHomeSource(parent, context)) return null;
        return publishedChildList(id, parent);
    }

    private boolean currentPublishedHomeSource(OneshotEntity parent, IdEntity context) {
        if (parent == null || context == null || context.getServerSiteProjectId() == null
                || !context.getServerSiteProjectId().equals(parent.getSiteProjectId())
                || parent.getId() == null || !Objects.equals(context.getClassId(), parent.getSiteRootId())
                || !Objects.equals(parent.getParentId(), context.getClassId())
                || Objects.equals(parent.getId(), context.getClassId())
                || Boolean.TRUE.equals(parent.getIsDeleted()) || !Integer.valueOf(0).equals(parent.getPageType())
                || !Integer.valueOf(3).equals(parent.getStatus()) || !Boolean.TRUE.equals(parent.getHasFrontChannel()))
            return false;
        var route = menuService.getModelByIdIgnoreChannel(context.getClassId());
        return route != null && Objects.equals(route.getId(), route.getSiteRootId())
                && Objects.equals(route.getSiteProjectId(), parent.getSiteProjectId())
                && Integer.valueOf(0).equals(route.getPageType()) && Boolean.TRUE.equals(route.getHasFrontChannel())
                && !Boolean.TRUE.equals(route.getIsDeleted());
    }

    private List<ContentEntity> publishedChildList(int id, OneshotEntity owner) {
        var list = menuService.getModelByIdIgnoreChannel(id);
        if (list == null || list.getParentId() != owner.getId()
                || !Objects.equals(list.getSiteProjectId(), owner.getSiteProjectId())
                || !Objects.equals(list.getSiteRootId(), owner.getSiteRootId())
                || Boolean.TRUE.equals(list.getIsDeleted()) || !Integer.valueOf(1).equals(list.getPageType())
                || !Boolean.TRUE.equals(list.getHasManageChannel()) || !Boolean.FALSE.equals(list.getHasFrontChannel())
                || !(isBlank(list.getListPageUrl()) || "javascript:void(0);".equals(list.getListPageUrl()))
                || !isBlank(list.getDetailPageUrl())) return null;
        return readPublishedSectionItems(infoService, list.getSiteProjectId(), list.getId());
    }

    public static List<ContentEntity> readPublishedSectionItems(InfoService info, Long siteId, Integer menuId) {
        var records = new ArrayList<ContentEntity>();
        for (int page = 1; ; page++) {
            var batch = info.getPublishedContentPage(siteId, menuId, page, 100).list();
            records.addAll(batch);
            if (batch.size() < 100) return List.copyOf(records);
        }
    }

    /**
     * 根据一组栏目 id 批量获取完整栏目实体（不附加 listPageUrl）
     *
     * <p>用于一次性取回多个栏目，避免逐列调用 {@link #getOneshotEntityById(int)} 造成的 N+1 查询。
     *
     * @param ids 栏目 id 集合
     * @return 匹配的栏目实体列表，未匹配时返回空集合
     */
    public List<OneshotEntity> getOneshotListByIdsRaw(List<Integer> ids) {
        return menuService.getOneshotListByIdsRaw(ids);
    }

    /**
     * 根据 bizcode 与站点ID获取当前站点的栏目（多站点场景推荐使用）
     *
     * <p>bizcode 为栏目的跨站点稳定标识：新增栏目时默认等于其 t_oneshot.id。 同一逻辑栏目在不同站点拥有不同的 t_oneshot.id，但共享相同的 bizcode，
     * 因此传入 bizcode 与<b>当前站点ID</b>即可取到当前站点对应的栏目实体， 避免直接写死 t_oneshot.id 在多站点下取到其他站点的栏目。
     *
     * <p>与 {@link #getOneshotEntityById(int)} 的区别：后者按主键 id 查询，仅在本站点上下文有效； 模板中使用 siteId 变量作为
     * siteProjectId 参数。
     *
     * @param bizcode 栏目业务编码，新增栏目时等于该栏目的 t_oneshot.id（如 "1001"）
     * @param siteProjectId 站点项目ID（模板中即 siteId 变量），用于限定当前站点
     * @return 当前站点下匹配的栏目实体；bizcode 或 siteProjectId 为空、或未匹配时返回 null
     */
    public OneshotEntity getOneshotEntityByBizcode(String bizcode, Long siteProjectId) {
        return menuService.getOneshotEntityByBizcode(bizcode, siteProjectId);
    }

    private List<String> parseCodes(String bizcodes) {
        if (isBlank(bizcodes)) {
            return Collections.emptyList();
        }
        return Arrays.stream(bizcodes.split(WebConst.COMMA_SPLIT))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toList());
    }

    // ==================== 多站点定位：以 (bizcode, siteId) 替代 t_oneshot.id 的查询重载 ====================
    // 说明：下列每个方法都是对应 t_oneshot.id 方法的“多站点版本”。第一个参数由栏目主键 id 改为
    // 栏目业务编码 bizcode，第二个参数为站点项目ID（模板中即 siteId 变量）；方法内部先把
    // (bizcode, siteId) 解析为当前站点对应栏目的 id，再复用原有逻辑，因此多站点下不会取到其它站点的栏目。

    /**
     * 【多站点】根据父栏目 bizcode 与唯一标识读取栏目。
     *
     * @param bizcode 父栏目业务编码（t_oneshot.biz_code）
     * @param siteProjectId 站点项目ID（模板中即 siteId），用于限定当前站点
     * @param identifies 栏目唯一标识
     * @return 当前站点下匹配的栏目；bizcode 无法解析时返回 null
     */
    public OneshotEntity getOneshotEntityByParentBizcodeAndIdentifies(
            String bizcode, Long siteProjectId, String identifies) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return null;
        }
        return menuService.getOneshotEntityByParentIdAndIdentifies(parentId, identifies);
    }

    /**
     * 【多站点】根据父栏目 bizcode 与唯一标识集合读取栏目列表。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param identifies 栏目唯一标识集合
     * @return 当前站点下匹配的栏目列表；bizcode 无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByParentBizcodeAndIdentifies(
            String bizcode, Long siteProjectId, String... identifies) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return menuService.getOneshotListByParentIdAndIdentifies(parentId, identifies);
    }

    /**
     * 【多站点】专题：根据父栏目 bizcode 与显示位置读取栏目列表。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param showPosition 显示位置
     * @return 当前站点下匹配的栏目列表；bizcode 无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByParentBizcodeAndShowPosition(
            String bizcode, Long siteProjectId, String showPosition) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return menuService.getOneshotListByParentIdAndShowPosition(parentId, showPosition);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取子栏目集合。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 子栏目列表；bizcode 无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByParentBizcode(String bizcode, Long siteProjectId) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return menuService.getOneshotListByParentId(parentId);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取子栏目集合的前 N 条。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @return 子栏目列表；bizcode 无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByParentBizcode(
            String bizcode, Long siteProjectId, int limit) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return menuService.getOneshotListByParentId(parentId, limit);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取子栏目集合，并排除指定 bizcode 的栏目。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param outBizcodes 需要排除的栏目 bizcode 集合（逗号分隔）
     * @return 子栏目列表；bizcode 无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByParentBizcode(
            String bizcode, Long siteProjectId, String outBizcodes) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        Set<String> excluded = new HashSet<>(parseCodes(outBizcodes));
        return menuService.getOneshotListByParentId(parentId).stream()
                .filter(column -> !excluded.contains(column.getBizCode()))
                .collect(Collectors.toList());
    }

    /**
     * 【多站点】根据一组栏目 bizcode 获取当前站点的栏目集合。
     *
     * @param bizcodes 栏目业务编码集合（逗号分隔）
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 当前站点下匹配的栏目列表；无法解析时返回空集合
     */
    public List<OneshotEntity> getOneshotListByBizcodes(String bizcodes, Long siteProjectId) {
        String ids = resolveMenuIds(bizcodes, siteProjectId);
        if (isBlank(ids)) {
            return Collections.emptyList();
        }
        return menuService.getOneshotListByIds(ids);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取其第一个子栏目的 id。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 第一个子栏目 id；bizcode 无法解析时返回 -1
     */
    public int getFirstOneshotListByParentBizcode(String bizcode, Long siteProjectId) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return -1;
        }
        return getFirstOneshotListByParentId(parentId);
    }

    /**
     * 【多站点】根据栏目 bizcode 与内容 id 获取内容。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param id 内容 id（t_contents.id）
     * @return 内容对象；bizcode 无法解析时返回 null
     */
    public ContentEntity getContentEntityByClassBizcode(
            String bizcode, Long siteProjectId, int id) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return null;
        }
        return infoService.getArticleById(classId, id);
    }

    /**
     * 【多站点】根据父栏目 bizcode 与唯一标识读取栏目内容。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param identifies 栏目唯一标识
     * @return 内容列表；bizcode 无法解析时返回 null
     */
    public List<ContentEntity> getContentByParentBizcodeAndIdentifies(
            String bizcode, Long siteProjectId, String identifies) {
        return getContentByParentBizcodeAndIdentifies(bizcode, siteProjectId, identifies, 100);
    }

    /**
     * 【多站点】根据父栏目 bizcode 与唯一标识读取栏目内容（限制条数）。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param identifies 栏目唯一标识
     * @param size 查询条数
     * @return 内容列表；bizcode 无法解析时返回 null
     */
    public List<ContentEntity> getContentByParentBizcodeAndIdentifies(
            String bizcode, Long siteProjectId, String identifies, int size) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return null;
        }
        return getContentByParentIdAndIdentifies(parentId, identifies, size);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取内容列表（默认 100 条）。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcode(String bizcode, Long siteProjectId) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentListByClassId(classId);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取内容列表（限制条数）。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcode(
            String bizcode, Long siteProjectId, int limit) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentListByClassId(classId, limit);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取内容列表（限制条数），并按其字段值附加等值过滤。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @param filters 附加过滤：key=字段名（如 singleLine1、title），value=字段值；空 map 或未识别字段忽略；多条件等值 AND
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcode(
            String bizcode, Long siteProjectId, int limit, Map<String, Object> filters) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return infoService.getArticleByClassIdWithFilters(classId, limit, 0, filters);
    }

    /** 仅可信模板以固定列表声明公开字段；不要将request参数名作为允许列表。 */
    public List<ContentEntity> getContentListByClassBizcode(
            String bizcode,
            IdEntity serverContext,
            int limit,
            Map<String, Object> filters,
            List<String> allowedFilterKeys,
            List<String> allowedSortKeys) {
        Long siteProjectId = requireStructuredSite(serverContext, null);
        var page = new IdEntity().setPageIndex(1).setPageSize(limit);
        page.bindServerSite(siteProjectId);
        var result =
                getContentPagerByClassBizcode(
                        bizcode, siteProjectId, page, filters, allowedFilterKeys, allowedSortKeys);
        return result == null ? Collections.emptyList() : result.getRows();
    }

    /** 公开结构化查询；普通四参列表和原分页方法语义保持不变。 */
    public Pager<ContentEntity> getContentPagerByClassBizcode(
            String bizcode,
            Long siteProjectId,
            IdEntity page,
            Map<String, Object> filters,
            List<String> allowedFilterKeys,
            List<String> allowedSortKeys) {
        if (siteProjectId == null) throw new IllegalArgumentException("当前服务器站点不能为空");
        Long trustedSite = requireStructuredSite(page, siteProjectId);
        Integer classId = resolveMenuId(bizcode, trustedSite);
        if (classId == null) return null;
        if (page == null) throw new IllegalArgumentException("分页参数不能为空");
        OneshotEntity routeMenu = contentLinks.getStructuredListMenu(trustedSite, classId);
        String firstRoute = contentLinks.getListUrl(routeMenu, 1);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (filters != null)
            for (var entry : filters.entrySet()) {
                if (entry.getValue() == null || "".equals(entry.getValue())) continue;
                Object value = entry.getValue();
                if (value instanceof Collection<?> members) {
                    if (members.isEmpty()) continue;
                    if (members.stream()
                            .anyMatch(
                                    member ->
                                            !(member instanceof String text)
                                                    || text.isEmpty()
                                                    || text.contains(",")))
                        throw new IllegalArgumentException("公开多选值须为文本");
                    value =
                            ","
                                    + members.stream()
                                            .map(Object::toString)
                                            .collect(Collectors.joining(","))
                                    + ",";
                }
                if (!(value instanceof String text))
                    throw new IllegalArgumentException("公开筛选值须为文本");
                values.put(entry.getKey(), text);
            }
        var result =
                infoService.getStructuredPublishedPage(
                        page,
                        classId,
                        page.getPageIndex() == null ? 1 : page.getPageIndex(),
                        page.getPageSize() == null ? 20 : page.getPageSize(),
                        values,
                        allowedFilterKeys,
                        allowedSortKeys);
        var pager = new Pager<ContentEntity>();
        pager.setRows(result.getList());
        pager.setTotal(Math.toIntExact(result.getTotal()));
        pager.setPage(result.getPageNum());
        pager.setSize(result.getPageSize());
        pager.setPageCount(Math.toIntExact(result.getPages()));
        pager.setPagePath(firstRoute);
        int last = Math.max(1, pager.getPageCount());
        Map<String, String> pageValues = new java.util.LinkedHashMap<>(values);
        var condition = page.getConditionVo();
        if (condition != null) {
            appendStructuredCondition(pageValues, "key", condition.getKey());
            appendStructuredCondition(pageValues, "keywords", condition.getKeywords());
            appendStructuredCondition(pageValues, "type", condition.getType());
            appendStructuredCondition(pageValues, "area", condition.getArea());
            appendStructuredCondition(pageValues, "year", condition.getYear());
            appendStructuredCondition(pageValues, "month", condition.getMonth());
            appendStructuredCondition(pageValues, "yearString", condition.getYearString());
            appendStructuredCondition(pageValues, "classIds", condition.getClassIds());
        }
        pager.setFirstUrl(structuredPageUrl(routeMenu, 1, pageValues));
        pager.setLastUrl(structuredPageUrl(routeMenu, last, pageValues));
        pager.setPrevUrl(
                structuredPageUrl(routeMenu, Math.max(1, pager.getPage() - 1), pageValues));
        pager.setNextUrl(
                structuredPageUrl(routeMenu, Math.min(last, pager.getPage() + 1), pageValues));
        String previous =
                pager.getPage() > 1
                        ? "<a class=\"prev\" href=\""
                                + org.springframework.web.util.HtmlUtils.htmlEscape(
                                        pager.getPrevUrl())
                                + "\">上一页</a>"
                        : "";
        String next =
                pager.getPage() < last
                        ? "<a class=\"next\" href=\""
                                + org.springframework.web.util.HtmlUtils.htmlEscape(
                                        pager.getNextUrl())
                                + "\">下一页</a>"
                        : "";
        pager.setPageHtml(
                "<div class=\"pageout\"><div class=\"page\">"
                        + previous
                        + "<span>"
                        + pager.getPage()
                        + " / "
                        + last
                        + "</span>"
                        + next
                        + "</div></div>");
        return pager;
    }

    private Long requireStructuredSite(IdEntity context, Long expectedSite) {
        Long site = context == null ? null : context.getServerSiteProjectId();
        if (site == null
                || !site.equals(context.getSiteProjectId())
                || expectedSite != null && !site.equals(expectedSite))
            throw new IllegalArgumentException("公开结构化查询必须使用当前服务器绑定的站点上下文");
        return site;
    }

    private void appendStructuredCondition(Map<String, String> query, String key, Object value) {
        if (value != null && !value.toString().isEmpty()) query.put(key, value.toString());
    }

    public String structuredPageUrl(
            OneshotEntity routeMenu, int page, Map<String, String> filters) {
        String base = contentLinks.getListUrl(routeMenu, page);
        StringJoiner query = new StringJoiner("&");
        query.add("__cms_filter=1");
        filters.forEach(
                (key, value) ->
                        query.add(
                                java.net.URLEncoder.encode(
                                                key, java.nio.charset.StandardCharsets.UTF_8)
                                        + "="
                                        + java.net.URLEncoder.encode(
                                                value, java.nio.charset.StandardCharsets.UTF_8)));
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    /**
     * 【多站点】根据栏目 bizcode 按推荐位获取内容列表。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @param setting 推荐位编号：8首页置顶、9首页热门、10首页推荐、11频道置顶、12频道热门、13频道推荐、
     *     14栏目置顶、15栏目热门、16栏目推荐、17导航推荐、18右侧推荐、19左侧推荐；0 表示不按推荐位过滤
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcode(
            String bizcode, Long siteProjectId, int limit, int setting) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentListByClassId(classId, limit, setting);
    }

    /**
     * 【多站点】根据栏目 bizcode 按推荐位分页获取内容列表。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 每页条数
     * @param setting 推荐位编号（见 getContentListByClassBizcode），0 表示不过滤
     * @param pageIndex 页码
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcode(
            String bizcode, Long siteProjectId, int limit, int setting, int pageIndex) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentListByClassId(classId, limit, setting, pageIndex);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取内容列表（默认 100 条）。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByParentBizcode(String bizcode, Long siteProjectId) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentListByParentId(parentId);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取内容列表（限制条数）。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByParentBizcode(
            String bizcode, Long siteProjectId, int limit) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentListByParentId(parentId, limit);
    }

    /**
     * 【多站点】根据父栏目 bizcode 按推荐位获取内容列表。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @param setting 推荐位编号（见 getContentListByClassBizcode），0 表示不过滤
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByParentBizcode(
            String bizcode, Long siteProjectId, int limit, int setting) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentListByParentId(parentId, limit, setting);
    }

    /**
     * 【多站点】根据父栏目 bizcode 按推荐位获取内容列表，并排除指定 bizcode 的栏目内容。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @param setting 推荐位编号（见 getContentListByClassBizcode），0 表示不过滤
     * @param outBizcodes 需要排除的栏目 bizcode 集合（逗号分隔）
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByParentBizcode(
            String bizcode, Long siteProjectId, int limit, int setting, String outBizcodes) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentListByParentId(
                parentId, limit, setting, resolveMenuIds(outBizcodes, siteProjectId));
    }

    /**
     * 【多站点】根据父栏目 bizcode 与显示位置获取内容列表。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param showPosition 显示位置
     * @param limit 查询条数
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByParentBizcodeAndShowPosition(
            String bizcode, Long siteProjectId, String showPosition, int limit) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentListByParentIdAndShowPosition(parentId, showPosition, limit);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取浏览量最高的内容列表。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentHotListByParentBizcode(
            String bizcode, Long siteProjectId, int limit) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return Collections.emptyList();
        }
        return getContentHotListByParentId(parentId, limit);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取浏览量最高的内容列表。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param limit 查询条数
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentHotListByClassBizcode(
            String bizcode, Long siteProjectId, int limit) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentHotListByClassId(classId, limit);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取未发布过的内容集合。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListNoPublishedByClassBizcode(
            String bizcode, Long siteProjectId) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentListNoPublishedByClassId(classId);
    }

    /**
     * 【多站点】根据栏目 bizcode 与数据关联 id 获取关联数据。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param relationId 数据关联 id（t_contents.xgid）
     * @return 内容列表；bizcode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByClassBizcodeAndRelationId(
            String bizcode, Long siteProjectId, Integer relationId) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return infoService.getContentListByClassIdAndRelationId(classId, relationId);
    }

    /**
     * 【多站点】根据栏目 bizcode 对指定字段分组取值。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param fieldName 列名
     * @param limit 数量
     * @param isGroup 是否以当前字段排序
     * @param sortType 排序类型 desc、asc
     * @return 字段值列表；bizcode 无法解析时返回空集合
     */
    public List<String> getContentFieldByClassBizcodeGroupBy(
            String bizcode,
            Long siteProjectId,
            String fieldName,
            int limit,
            boolean isGroup,
            String sortType) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getContentFieldByClassIdGroupBy(classId, fieldName, limit, isGroup, sortType);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取分页数据对象。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param idEntity 分页条件（其中的 classId 会被替换为当前站点解析出的栏目 id）
     * @return 分页对象；bizcode 无法解析时返回 null
     */
    public Pager<ContentEntity> getContentPagerByClassBizcode(
            String bizcode, Long siteProjectId, IdEntity idEntity) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return null;
        }
        return getContentPagerByClassId(copyIdEntity(idEntity, classId, null));
    }

    /**
     * 【多站点】根据一组栏目 bizcode 获取分页数据对象。
     *
     * @param bizcodes 栏目业务编码集合（逗号分隔）
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param idEntity 分页条件
     * @return 分页对象；bizcode 无法解析时返回 null
     */
    public Pager<ContentEntity> getContentPagerByClassBizcodes(
            String bizcodes, Long siteProjectId, IdEntity idEntity) {
        String ids = resolveMenuIds(bizcodes, siteProjectId);
        if (isBlank(ids)) {
            return null;
        }
        return getContentPagerByClassIds(ids, idEntity);
    }

    /**
     * 【多站点】根据父栏目 bizcode 获取分页数据对象。
     *
     * @param bizcode 父栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param idEntity 分页条件（其中的 parentId 会被替换为当前站点解析出的栏目 id）
     * @return 分页对象；bizcode 无法解析时返回 null
     */
    public Pager<ContentEntity> getContentPagerByParentBizcode(
            String bizcode, Long siteProjectId, IdEntity idEntity) {
        Integer parentId = resolveMenuId(bizcode, siteProjectId);
        if (parentId == null) {
            return null;
        }
        return getContentPagerByParentId(copyIdEntity(idEntity, null, parentId));
    }

    /**
     * 【多站点】根据栏目 bizcode 按条件 group by title 字段。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param idEntity 条件（其中的 classId 会被替换为当前站点解析出的栏目 id）
     * @param addAll 是否添加全部选项
     * @return 分组结果；bizcode 无法解析时返回空集合
     */
    public List<GroupByVo> getGroupByTitleByClassBizcode(
            String bizcode, Long siteProjectId, IdEntity idEntity, boolean addAll) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return Collections.emptyList();
        }
        return getGroupByTitleByIdEntity(copyIdEntity(idEntity, classId, null), addAll);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取上一条/下一条内容。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param idEntity 条件（其中的 classId 会被替换为当前站点解析出的栏目 id）
     * @return 上一篇/下一篇对象；bizcode 无法解析时返回 null
     */
    public DetailsVo getPrevAndNextByClassBizcode(
            String bizcode, Long siteProjectId, IdEntity idEntity) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return null;
        }
        return getPrevAndNextByIdEntity(copyIdEntity(idEntity, classId, null));
    }

    /**
     * 【多站点】根据栏目 bizcode 获取面包屑导航。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 面包屑 HTML；bizcode 无法解析时返回 null
     */
    public String getBreadcrumbByBizcode(String bizcode, Long siteProjectId) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return null;
        }
        return getBreadcrumb(classId);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取面包屑导航（带内容 id）。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @param id 内容 id
     * @return 面包屑 HTML；bizcode 无法解析时返回 null
     */
    public String getBreadcrumbByBizcode(String bizcode, Long siteProjectId, int id) {
        Integer classId = resolveMenuId(bizcode, siteProjectId);
        if (classId == null) {
            return null;
        }
        return getBreadcrumb(classId, id);
    }

    /**
     * 【多站点】根据栏目 bizcode 获取 Banner 对象。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return Banner 对象；bizcode 无法解析时返回 null
     */
    public BannerEntity getBannerEntityByBizcode(String bizcode, Long siteProjectId) {
        Integer bannerId = resolveMenuId(bizcode, siteProjectId);
        if (bannerId == null) {
            return null;
        }
        return getBannerEntity(bannerId);
    }

    // ==================== 多站点定位：内容（t_contents）以 (contentCode, siteId) 替代内容 id ====================

    /**
     * 【多站点】根据内容 contentCode 与站点ID获取内容。
     *
     * @param contentCode 内容业务编码（t_contents.content_code，区别于栏目表 t_oneshot.biz_code）
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容对象；contentCode 无法解析时返回 null
     */
    public ContentEntity getContentEntityByContentCode(String contentCode, Long siteProjectId) {
        Integer id = resolveContentId(contentCode, siteProjectId);
        if (id == null) {
            return null;
        }
        return getContentEntityById(id);
    }

    /**
     * 【多站点】根据内容 contentCode 与站点ID获取标题。
     *
     * @param contentCode 内容业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 标题；contentCode 无法解析时返回空字符串
     */
    public String getTitleByContentCode(String contentCode, Long siteProjectId) {
        Integer id = resolveContentId(contentCode, siteProjectId);
        if (id == null) {
            return "";
        }
        return getTitleById(String.valueOf(id));
    }

    /**
     * 【多站点】根据一组内容 contentCode 获取当前站点的内容集合。
     *
     * @param contentCodes 内容业务编码集合（逗号分隔）
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容列表；无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByContentCodes(
            String contentCodes, Long siteProjectId) {
        String ids = resolveContentIds(contentCodes, siteProjectId);
        if (isBlank(ids)) {
            return Collections.emptyList();
        }
        return getContentListByIds(ids);
    }

    /**
     * 【多站点】根据内容 contentCode 与站点ID获取内容标题。
     *
     * @param contentCode 内容业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 标题；contentCode 无法解析时返回空字符串
     */
    public String getTypeTitleByContentCode(String contentCode, Long siteProjectId) {
        Integer id = resolveContentId(contentCode, siteProjectId);
        if (id == null) {
            return "";
        }
        return getTypeTitleById(String.valueOf(id));
    }

    /**
     * 【多站点】在给定内容列表中根据 contentCode 获取标题（列表已按站点过滤，无需 siteId）。
     *
     * @param lists 内容列表
     * @param contentCode 内容业务编码
     * @return 标题；未匹配时返回空字符串
     */
    public String getTypeTitleByContentCode(List<ContentEntity> lists, String contentCode) {
        if (StringUtils.isBlank(contentCode) || CollectionUtils.isEmpty(lists)) {
            return "";
        }
        Optional<ContentEntity> first =
                lists.stream().filter(t -> contentCode.equals(t.getContentCode())).findFirst();
        return first.map(ContentEntity::getTitle).orElse("");
    }

    /**
     * 【多站点】根据内容 contentCode 与站点ID获取业务板块 Banner 对象。
     *
     * @param contentCode 内容业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return Banner 对象；contentCode 无法解析时返回 null
     */
    public BannerEntity getBusinessBannerEntityByContentCode(
            String contentCode, Long siteProjectId) {
        Integer id = resolveContentId(contentCode, siteProjectId);
        if (id == null) {
            return null;
        }
        return getBusinessBannerEntity(id);
    }

    /**
     * 【多站点】根据“关联内容”的 contentCode 与站点ID获取关联数据
     *
     * <p>先把 (contentCode, siteId) 解析为内容 id，再按 t_contents.xgid = 该 id 查询，等价于原
     * getContentListByRelationId(relationId)。
     *
     * @param contentCode 关联内容的业务编码
     * @param siteProjectId 站点项目ID（模板中即 siteId）
     * @return 内容列表；contentCode 无法解析时返回空集合
     */
    public List<ContentEntity> getContentListByRelationContentCode(
            String contentCode, Long siteProjectId) {
        Integer id = resolveContentId(contentCode, siteProjectId);
        if (id == null) {
            return Collections.emptyList();
        }
        return getContentListByRelationId(id);
    }

    /** 根据父栏目和唯一标识读取栏目 */
    public OneshotEntity getOneshotEntityByParentIdAndIdentifies(int parentId, String identifies) {
        return menuService.getOneshotEntityByParentIdAndIdentifies(parentId, identifies);
    }

    public List<OneshotEntity> getOneshotListByParentIdAndIdentifies(
            int parentId, String... identifies) {
        return menuService.getOneshotListByParentIdAndIdentifies(parentId, identifies);
    }

    /** 专题根据父栏目ID和显示位置获取所有栏目 */
    public List<OneshotEntity> getOneshotListByParentIdAndShowPosition(
            int parentId, String showPosition) {
        return menuService.getOneshotListByParentIdAndShowPosition(parentId, showPosition);
    }

    /** 根据parentId获取栏目集合 */
    public List<OneshotEntity> getOneshotListByParentId(int parentId) {
        return menuService.getOneshotListByParentId(parentId);
    }

    /** 根据parentId获取栏目集合的前N条 */
    public List<OneshotEntity> getOneshotListByParentId(int parentId, int limit) {
        List<OneshotEntity> oneshotListByParentId =
                menuService.getOneshotListByParentId(parentId, limit);
        return oneshotListByParentId;
    }

    /** 根据栏目id集合获取栏目集合 */
    public List<OneshotEntity> getOneshotListByIds(String ids) {
        return menuService.getOneshotListByIds(ids);
    }

    /** 产品内容页获取随机推荐产品 */
    public List<OneshotEntity> getRandomProduct() {
        return menuService.getRandomProduct();
    }

    /**
     * 根据parentId获取栏目集合
     *
     * @param parentId 栏目id
     * @param outClassId 排除的栏目id集合
     */
    public List<OneshotEntity> getOneshotListByParentId(int parentId, String outClassId) {
        return menuService.getOneshotListByParentId(parentId, outClassId);
    }

    /** 根据ID获取文章内容 */
    public ContentEntity getContentEntityById(int id) {
        return infoService.getArticleById(id);
    }

    /** 获取当前的上一篇和下一篇对象 */
    public DetailsVo getPrevAndNextByIdEntity(IdEntity idEntity) {
        return infoService.getPrevAndNextByIdEntity(idEntity);
    }

    /** 根据classId和id获取文章内容 */
    public ContentEntity getContentEntityById(int classId, int id) {
        return infoService.getArticleById(classId, id);
    }

    public int getFirstOneshotListByParentId(int parentId) {
        return menuService.getOneshotListByParentId(parentId).get(0).getId();
    }

    /** 根据父栏目和唯一标识读取栏目内容 */
    public List<ContentEntity> getContentByParentIdAndIdentifies(int parentId, String identifies) {
        return getContentByParentIdAndIdentifies(parentId, identifies, 100);
    }

    /** 根据父栏目和唯一标识读取栏目内容 */
    public List<ContentEntity> getContentByParentIdAndIdentifies(
            int parentId, String identifies, int size) {
        OneshotEntity oneshotEntity =
                menuService.getOneshotEntityByParentIdAndIdentifies(parentId, identifies);
        if (oneshotEntity != null) {
            return getContentListByClassId(oneshotEntity.getId(), size);
        }
        return null;
    }

    /** 根据classId获取列表集合 */
    public List<ContentEntity> getContentListByClassId(int classId) {
        return getContentListByClassId(classId, 100);
    }

    /** 获取带分页的数据对象 */
    public Pager<ContentEntity> getContentPagerByClassId(IdEntity idEntity) {
        return infoService.getNewsListByClassIdXgidAndPage(idEntity);
    }

    /** 获取带分页的数据对象 */
    public Pager<ContentEntity> getContentPagerByClassIds(String classIds, IdEntity idEntity) {
        return infoService.getNewsListByClassIdsPage(classIds, idEntity);
    }

    /** 根据唯一标识获取classId集合 */
    public List<Integer> getClassIdListByIdentifies(String identifies) {
        return menuService.getClassIdListByIdentifies(identifies);
    }

    /** 根据唯一标识获取指定站点的classId集合 */
    public List<Integer> getClassIdListByIdentifies(String identifies, Long siteProjectId) {
        return menuService.getClassIdListByIdentifies(identifies, siteProjectId);
    }

    @Autowired private com.xinhongru.cms.search.PublicSearchService publicSearchService;

    /** 全站结果保留content/singlePage两种明确类型；仅限服务器绑定的当前站点上下文。 */
    public com.xinhongru.common.page.PageResult<com.xinhongru.cms.search.PublicSearchService.Hit>
            getPublicSearchResults(IdEntity idEntity) {
        if (idEntity == null
                || idEntity.getServerSiteProjectId() == null
                || idEntity.getConditionVo() == null)
            throw new IllegalArgumentException("搜索须使用当前服务器提供的idEntity与搜索条件");
        var condition = idEntity.getConditionVo();
        if (org.apache.commons.lang3.StringUtils.isNotBlank(condition.getYearString())
                || org.apache.commons.lang3.StringUtils.isNotBlank(condition.getType())
                || org.apache.commons.lang3.StringUtils.isNotBlank(condition.getArea()))
            throw new IllegalArgumentException("旧语义筛选不适用于公开搜索，请使用显式模型过滤");
        var query =
                com.xinhongru.cms.search.PublicSearchQuery.of(
                                idEntity.getServerSiteProjectId(),
                                idEntity.getSiteProjectId(),
                                condition.getKey(),
                                idEntity.getPageIndex(),
                                idEntity.getPageSize())
                        .withDates(condition.getYear(), condition.getMonth());
        return publicSearchService.search(query, condition.getClassIds(), false);
    }

    /** 获取搜索页结果 */
    public Pager<ContentEntity> getContentPagerByKey(IdEntity idEntity) {
        return infoService.searchByKey(idEntity);
    }

    /** 根据父级ID获取带分页数据对象 */
    public Pager<ContentEntity> getContentPagerByParentId(IdEntity idEntity) {
        return infoService.getNewsListByParentIdAndPage(idEntity);
    }

    /**
     * 根据条件group by title 字段
     *
     * @param idEntity 条件
     * @param addAll 是否添加全部选项
     */
    public List<GroupByVo> getGroupByTitleByIdEntity(IdEntity idEntity, boolean addAll) {
        return infoService.getGroupByTitleByIdEntity(idEntity, addAll);
    }

    /** 获取跨页的行号 */
    public int getRowNumber(IdEntity idEntity, int count) {
        return (idEntity.getPageIndex() - 1) * idEntity.getPageSize() + count;
    }

    /**
     * 根据classId获取列表集合
     *
     * @param classId 栏目Id
     * @param limit 查询条数
     */
    public List<ContentEntity> getContentListByClassId(int classId, int limit) {
        return getContentListByClassId(classId, limit, 0);
    }

    /**
     * 根据classId获取列表集合
     *
     * <p>首页置顶8、首页热门9、首页推荐10、频道置顶11、频道热门12、频道推荐13、栏目置顶14、栏目热门15、栏目推荐16、导航推荐17、右侧推荐18、左侧推荐19
     */
    public List<ContentEntity> getContentListByClassId(int classId, int limit, int setting) {
        return getContentListByClassId(classId, limit, setting, 1);
    }

    /**
     * @param limit 每页显示条数
     *     首页置顶8、首页热门9、首页推荐10、频道置顶11、频道热门12、频道推荐13、栏目置顶14、栏目热门15、栏目推荐16、导航推荐17、右侧推荐18、左侧推荐19
     * @param pageIndex 页码
     */
    public List<ContentEntity> getContentListByClassId(
            int classId, int limit, int setting, int pageIndex) {
        return infoService.getArticleByClassId(classId, limit, setting, pageIndex);
    }

    /**
     * 根据parentId获取列表集合
     *
     * @param parentId 父级栏目Id
     */
    public List<ContentEntity> getContentListByParentId(int parentId) {
        return getContentListByParentId(parentId, 100);
    }

    /** 根据父栏目ID和显示位置获取数据集合 */
    public List<ContentEntity> getContentListByParentIdAndShowPosition(
            int parentId, String showPosition, int limit) {
        return infoService.getContentListByParentIdAndShowPosition(parentId, showPosition, limit);
    }

    /** 根据父级栏目ID获取所有列表中浏览量最高的数据 */
    public List<ContentEntity> getContentHotListByParentId(int parentId, int limit) {
        return infoService.getContentHotListByParentId(parentId, limit);
    }

    /** 根据栏目ID获取所有列表中浏览量最高的数据 */
    public List<ContentEntity> getContentHotListByClassId(int classId, int limit) {
        return infoService.getContentHotListByClassId(classId, limit);
    }

    /**
     * @param parentId 父级栏目Id
     * @param limit 查询条数
     */
    public List<ContentEntity> getContentListByParentId(int parentId, int limit) {
        return getContentListByParentId(parentId, limit, 0);
    }

    /**
     * 根据parentId获取列表集合
     *
     * @param parentId 父级栏目Id
     * @param limit 查询条数
     *     首页置顶8、首页热门9、首页推荐10、频道置顶11、频道热门12、频道推荐13、栏目置顶14、栏目热门15、栏目推荐16、导航推荐17、右侧推荐18、左侧推荐19
     */
    public List<ContentEntity> getContentListByParentId(int parentId, int limit, int setting) {
        return getContentListByParentId(parentId, limit, setting, null);
    }

    /**
     * 根据parentId获取列表集合
     *
     * @param parentId 父级栏目Id
     * @param limit 查询条数
     *     首页置顶8、首页热门9、首页推荐10、频道置顶11、频道热门12、频道推荐13、栏目置顶14、栏目热门15、栏目推荐16、导航推荐17、右侧推荐18、左侧推荐19
     * @param outClassIds 排除的classId集合
     */
    public List<ContentEntity> getContentListByParentId(
            int parentId, int limit, int setting, String outClassIds) {
        return infoService.getContentListByParentId(parentId, limit, setting, outClassIds);
    }

    /**
     * 获取内容页地址
     *
     * @return 内容页链接地址，如果外部链接字段不为空则跳外链、如果附件不为空则跳转附件、最终才跳内容
     */
    public String getDetailUrl(ContentEntity contentEntity) {
        return contentLinks.getDetailUrl(contentEntity);
    }

    /** 获取栏目页地址 */
    public String getListUrl(OneshotEntity oneshotEntity) {
        return contentLinks.getListUrl(oneshotEntity);
    }

    /** 获取最基础的内容页链接地址 */
    public String getDetailCommonUrl(ContentEntity contentEntity) {
        if (contentEntity == null) return "javascript:void(0);";
        if (isNotBlank(contentEntity.getSingleLine4())) return contentEntity.getSingleLine4();
        if (isNotBlank(contentEntity.getFile1())) return contentEntity.getFile1();
        OneshotEntity column = menuService.getModelById(contentEntity.getClassid());
        if (column == null || isBlank(column.getDetailPageUrl())) return "javascript:void(0);";
        return column.getDetailPageUrl()
                .replace("{id}", String.valueOf(contentEntity.getId()))
                .replace("{classId}", String.valueOf(column.getId()));
    }

    /** Date类型的时间 格式化日期 */
    public String getDateString(Date date, String pattern) {
        return StringDeal.getDateString(date, pattern);
    }

    /** LocalDateTime类型的时间 格式化日期 */
    public String getDateString(LocalDateTime localDateTime, String pattern) {
        return StringDeal.getDateString(localDateTime, pattern);
    }

    /** Date类型的时间 格式化日期 */
    public String getDateString(Date date) {
        return getDateString(date, "yyyy-MM-dd");
    }

    /** LocalDateTime类型的时间 格式化日期 */
    public String getDateString(LocalDateTime localDateTime) {
        return getDateString(localDateTime, "yyyy-MM-dd");
    }

    /** 根据关联ID读取关联数据 */
    public List<ContentEntity> getContentListByRelationId(Integer relationId) {
        return getContentListByClassIdAndRelationId(null, relationId);
    }

    /** 根据栏目Id和数据关联Id获取关联数据 */
    public List<ContentEntity> getContentListByClassIdAndRelationId(
            Integer classId, Integer relationId) {
        return infoService.getContentListByClassIdAndRelationId(classId, relationId);
    }

    /** 通过栏目ID获取当前栏目未发布过的数据集合 */
    public List<ContentEntity> getContentListNoPublishedByClassId(Integer classId) {
        return infoService.getContentListNoPublishedByClassId(classId);
    }

    /** 获取面包屑导航 */
    public String getBreadcrumb(int classId) {
        return menuService.getBreadcrumb(classId, 0);
    }

    /** 获取面包屑导航 */
    public String getBreadcrumb(int classId, int id) {
        return menuService.getBreadcrumb(classId, id);
    }

    /** 根据Id获取标题字段 */
    public String getTitleById(String id) {
        if (isBlank(id)) {
            return "";
        }
        return infoService.getTitleById(Integer.valueOf(id));
    }

    /** 根据id集合查询数据对象 */
    public List<ContentEntity> getContentListByIds(String ids) {
        if (isBlank(ids)) {
            return new ArrayList<>();
        }
        return infoService.getContentListByIds(Arrays.asList(ids.split(WebConst.COMMA_SPLIT)));
    }

    /** 获取banner对象 */
    public BannerEntity getBannerEntity(Integer bannerId) {
        BannerEntity bannerEntity = new BannerEntity();
        if (bannerId == null || bannerId.intValue() <= 0) {
            return null;
        }
        OneshotEntity modelById = menuService.getModelById(bannerId);
        if (modelById != null) {
            bannerEntity.setBannerTitle(modelById.getSingleLine1());
            bannerEntity.setBannerNote(lineToBr(modelById.getNote1()));
            bannerEntity.setBannerPhoto(modelById.getPhoto1());
            return bannerEntity;
        }
        return null;
    }

    /** 获取业务板块子分类banner对象 */
    public BannerEntity getBusinessBannerEntity(Integer bannerId) {
        BannerEntity bannerEntity = new BannerEntity();
        if (bannerId == null || bannerId.intValue() <= 0) {
            return null;
        }
        ContentEntity articleById = infoService.getArticleById(bannerId);
        if (articleById != null) {
            bannerEntity.setBannerTitle(articleById.getSingleLine5());
            bannerEntity.setBannerNote(lineToBr(articleById.getNote2()));
            bannerEntity.setBannerPhoto(articleById.getPhoto2());
            return bannerEntity;
        }
        return null;
    }

    /** 根据业务分类Id获取业务公司集合 */
    public List<OneshotEntity> getCompanyListByBusinessId(Integer businessId) {
        return menuService.getCompanyListByBusinessId(businessId);
    }

    /** 增加集合类型非空判断 */
    public Boolean isNotEmptyCollection(Collection<?> coll) {
        return CollectionUtils.isNotEmpty(coll);
    }

    public List<ChildCompanyDTO> getChildCompany(Integer categoryId) {
        return menuService.getChildCompany(categoryId);
    }

    /** 对象转json字符串 */
    public String toJsonString(Object obj) {
        return JSON.toJSONString(obj);
    }

    /** 元素 位于 list中百分比 */
    public int getPercentage(List<ContentEntity> list, String id, boolean completed) {
        if (CollectionUtils.isEmpty(list) || StringUtils.isBlank(id)) {
            return 0;
        }
        Integer contentId = Integer.valueOf(id);
        int index = 0;
        for (ContentEntity content : list) {
            if (contentId.equals(content.getId())) {
                return (int) ((double) (index + 1) / list.size() * 100 - (completed ? 0 : 10));
            }
            index++;
        }
        return 0;
    }

    public String getTypeTitleById(String contentId) {
        if (StringUtils.isBlank(contentId)) {
            return "";
        }

        ContentEntity content = getContentEntityById(Integer.parseInt(contentId));
        if (content != null) {
            return content.getTitle();
        }
        return "";
    }

    public String getTypeTitleById(List<ContentEntity> lists, String contentId) {
        if (StringUtils.isBlank(contentId) || CollectionUtils.isEmpty(lists)) {
            return "";
        }
        Integer id = Integer.valueOf(contentId);
        for (ContentEntity content : lists) {
            if (id.equals(content.getId())) return content.getTitle();
        }
        return "";
    }

    /**
     * 获取字段分组
     *
     * @param classId 栏目Id
     * @param limit 数量
     * @param fieldName 列名
     * @param isGroup 是否以当前字段排序
     * @param sortType 排序类型 desc 、asc
     */
    public List<String> getContentFieldByClassIdGroupBy(
            int classId, String fieldName, int limit, boolean isGroup, String sortType) {
        return infoService.getContentFieldByClassIdGroupBy(
                classId, fieldName, limit, isGroup, sortType);
    }

    /** 替换换行符为空字符串 */
    public String replaceLineToEmptyString(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    @Autowired private ResourceReferenceResolver resources;

    /** 统一解析 ui:/ 与 assets:/；普通UI相对路径保持现有模板语义。 */
    public String resolveUiUrl(String path, Long siteId) {
        if (isBlank(path)) return path;
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//"))
            return path;
        String reference =
                path.startsWith("ui:") || path.startsWith("assets:")
                        ? path
                        : "ui:/" + path.replaceFirst("^/+", "");
        return resources.resolveForSite(reference, siteId);
    }

    public String resolveUiUrl(String path) {
        return resolveUiUrl(path, null);
    }

    /**
     * 将 (bizcode, siteProjectId) 解析为当前站点对应栏目的主键 id。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID
     * @return 栏目 id；未匹配时返回 null
     */
    private Integer resolveMenuId(String bizcode, Long siteProjectId) {
        OneshotEntity column = getOneshotEntityByBizcode(bizcode, siteProjectId);
        return column != null ? column.getId() : null;
    }

    /**
     * 将逗号分隔的 bizcode 集合解析为当前站点对应栏目的 id 集合（逗号分隔）。
     *
     * @param bizcodes 栏目业务编码集合（逗号分隔）
     * @param siteProjectId 站点项目ID
     * @return 栏目 id 集合（逗号分隔）；无匹配时返回 null
     */
    private String resolveMenuIds(String bizcodes, Long siteProjectId) {
        return resolveCodeIds(
                bizcodes,
                siteProjectId,
                codes -> menuService.getOneshotListByBizcodes(codes, siteProjectId),
                OneshotEntity::getBizCode,
                OneshotEntity::getId);
    }

    /**
     * 将 (contentCode, siteProjectId) 解析为当前站点对应内容的主键 id。
     *
     * @param contentCode 内容业务编码
     * @param siteProjectId 站点项目ID
     * @return 内容 id；未匹配时返回 null
     */
    private Integer resolveContentId(String contentCode, Long siteProjectId) {
        ContentEntity content = infoService.getContentIdByContentCode(contentCode, siteProjectId);
        return content != null ? content.getId() : null;
    }

    /**
     * 将逗号分隔的内容 contentCode 集合解析为当前站点对应内容的 id 集合（逗号分隔）。
     *
     * @param contentCodes 内容业务编码集合（逗号分隔）
     * @param siteProjectId 站点项目ID
     * @return 内容 id 集合（逗号分隔）；无匹配时返回 null
     */
    private String resolveContentIds(String contentCodes, Long siteProjectId) {
        return resolveCodeIds(
                contentCodes,
                siteProjectId,
                codes -> infoService.getContentListByContentCodes(codes, siteProjectId),
                ContentEntity::getContentCode,
                ContentEntity::getId);
    }

    /** 统一编码到 ID 的映射；保留输入顺序、重复项及无匹配时返回 null 的约定。 */
    private <T> String resolveCodeIds(
            String value,
            Long siteId,
            Function<List<String>, List<T>> query,
            Function<T, String> codeOf,
            Function<T, Integer> idOf) {
        if (siteId == null) return null;
        List<String> codes = parseCodes(value);
        if (codes.isEmpty()) return null;
        List<T> rows = query.apply(codes);
        if (CollectionUtils.isEmpty(rows)) return null;
        Map<String, Integer> ids = new HashMap<>();
        for (T row : rows) {
            String code = codeOf.apply(row);
            if (code != null) ids.put(code, idOf.apply(row));
        }
        StringJoiner result = new StringJoiner(WebConst.COMMA_SPLIT);
        for (String code : codes) {
            if (ids.containsKey(code)) result.add(String.valueOf(ids.get(code)));
        }
        return result.length() == 0 ? null : result.toString();
    }

    /**
     * 复制一份 IdEntity 并替换其中的 classId / parentId，避免修改调用方传入的对象。
     *
     * @param source 原始分页条件，可为 null
     * @param classId 替换后的栏目 id，null 表示保持原值
     * @param parentId 替换后的父栏目 id，null 表示保持原值
     * @return 复制后的 IdEntity；source 为 null 时返回 null
     */
    private IdEntity copyIdEntity(IdEntity source, Integer classId, Integer parentId) {
        if (source == null) {
            return null;
        }
        IdEntity copy =
                new IdEntity(
                        classId != null ? classId : source.getClassId(),
                        parentId != null ? parentId : source.getParentId(),
                        source.getInfoId(),
                        source.getRelationId(),
                        source.getPageIndex(),
                        source.getPageSize(),
                        source.getProductId(),
                        source.getPagePath(),
                        source.getBizCode(),
                        source.getSiteProjectId(),
                        source.getConditionVo());
        if (source.getServerSiteProjectId() != null) {
            if (source.getSiteProjectId() != null
                    && !source.getSiteProjectId().equals(source.getServerSiteProjectId()))
                throw new IllegalArgumentException("请求站点与当前服务器不一致");
            copy.bindServerSite(source.getServerSiteProjectId());
        }
        return copy;
    }
}

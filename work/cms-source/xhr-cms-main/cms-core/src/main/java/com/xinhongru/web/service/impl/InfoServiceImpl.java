package com.xinhongru.web.service.impl;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.vo.list.Pager;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.persistence.Pages;
import com.xinhongru.utils.ContentColumnWhitelist;
import com.xinhongru.web.mapper.InfoMapper;
import com.xinhongru.web.mapper.MenuMapper;
import com.xinhongru.web.pojo.AjaxForm;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.resonseVo.GroupByVo;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.service.InfoService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * @Description @ClassName InfoServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16
 * 22:09
 */
@Slf4j
@Service
public class InfoServiceImpl implements InfoService {
    private InfoMapper infoMapper;
    private MenuMapper menuMapper;
    private ExecutorService executorService;

    private com.xinhongru.cms.search.PublicSearchService publicSearchService;

    @Autowired
    public void setPublicSearchService(com.xinhongru.cms.search.PublicSearchService service) {
        publicSearchService = service;
    }

    @Autowired
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Autowired
    public void setMenuMapper(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Autowired
    public void setInfoMapper(InfoMapper infoMapper) {
        this.infoMapper = infoMapper;
    }

    private com.xinhongru.mapper.OneShotMapper structuredMenus;
    private com.xinhongru.mapper.ContentMapper structuredContents;
    private com.xinhongru.cms.content.record.NativeContentAdminQueryService structuredQueries;

    @Autowired
    public void setStructuredQueryDependencies(
            com.xinhongru.mapper.OneShotMapper menus,
            com.xinhongru.mapper.ContentMapper contents,
            com.xinhongru.cms.content.record.NativeContentAdminQueryService queries) {
        this.structuredMenus = menus;
        this.structuredContents = contents;
        this.structuredQueries = queries;
    }

    @Override
    public PageResult<ContentEntity> getStructuredPublishedPage(
            com.xinhongru.web.resonseVo.IdEntity serverContext,
            int classId,
            int page,
            int size,
            Map<String, String> filters,
            List<String> allowedFilterKeys,
            List<String> allowedSortKeys) {
        Long siteId = serverContext == null ? null : serverContext.getServerSiteProjectId();
        if (siteId == null
                || siteId <= 0
                || !java.util.Objects.equals(serverContext.getSiteProjectId(), siteId)
                || classId <= 0
                || allowedFilterKeys == null
                || allowedSortKeys == null
                || allowedFilterKeys.size() > 20
                || allowedSortKeys.size() > 20)
            throw new IllegalArgumentException("公开模板查询须明确当前站点与字段允许列表");
        var menu = structuredMenus.selectActiveMenuModelBySite(siteId, classId);
        if (menu == null
                || !java.util.Objects.equals(menu.getSiteProjectId(), siteId)
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !java.util.Objects.equals(menu.getPageType(), 1))
            throw new IllegalArgumentException("当前站点栏目未开放前台内容查询");
        Map<String, String> accepted = filters == null ? Map.of() : new LinkedHashMap<>(filters);
        for (var entry : accepted.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null)
                throw new IllegalArgumentException("公开查询条件无效");
            if (key.equals("sortField")) {
                if (!allowedSortKeys.contains(entry.getValue()))
                    throw new IllegalArgumentException("模板未允许此公开排序字段");
            } else if (!key.equals("sortDirection")) {
                String field =
                        key.startsWith("op_")
                                ? key.substring(3)
                                : key.startsWith("startTime_")
                                        ? key.substring(10)
                                        : key.startsWith("endTime_")
                                                ? key.substring(8)
                                                : key.startsWith("min_") || key.startsWith("max_")
                                                        ? key.substring(4)
                                                        : key;
                if (!allowedFilterKeys.contains(field))
                    throw new IllegalArgumentException("模板未允许此公开筛选字段");
            }
        }
        var condition =
                new com.xinhongru.dto.cond.ContentCond()
                        .setClassid(classId)
                        .setSiteProjectId(siteId)
                        .setStatus(1)
                        .setPublishedTemplateQuery(true);
        var criteria = structuredQueries.buildModelCriteria(menu, accepted, condition);
        List<String> fields = new ArrayList<>(List.of("title", "create_time", "extension_fields"));
        for (var field : com.xinhongru.content.model.ContentModelCodec.read(menu.getModelField())) {
            String column = ContentColumnWhitelist.resolve(field.getFieldName());
            if (com.xinhongru.content.model.ContentFieldDefinition.enabled(field.getIsOpen())
                    && column != null
                    && !column.matches("content[0-9]+")
                    && !fields.contains(column)) fields.add(column);
        }
        return Pages.query(
                Math.max(1, page),
                Math.max(1, Math.min(100, size)),
                pagination ->
                        structuredContents.getArticlesByCond(
                                pagination, fields, condition, criteria));
    }

    //  参考手册 https://blog.csdn.net/qq359605040/article/details/124469004
    //  后期可以考虑加入redis缓存
    //    @Cacheable(value = "info", key = "'id:'+#id", unless = "#result==null")

    @Override
    @Cacheable(value = "atricleCache", key = "'atricleById_' + #id", unless = "#result == null")
    public ContentEntity getArticleById(int id) {
        ContentEntity articleById = infoMapper.getArticleById(id);
        if (articleById == null) {
            return null;
        }
        // 异步更新点击率
        executorService.execute(
                () -> {
                    infoMapper.updateHits(articleById.getClassid(), id);
                });

        articleById.setHits(articleById.getHits() + 1);
        return articleById;
    }

    @Override
    public ContentEntity getContentIdByContentCode(String contentCode, Long siteProjectId) {
        if (contentCode == null || contentCode.isBlank() || siteProjectId == null) {
            return null;
        }
        return infoMapper.getContentIdByContentCode(contentCode, siteProjectId);
    }

    @Override
    public List<ContentEntity> getContentListByContentCodes(
            List<String> contentCodes, Long siteProjectId) {
        if (contentCodes == null || contentCodes.isEmpty() || siteProjectId == null) {
            return new ArrayList<>();
        }
        List<ContentEntity> list =
                infoMapper.getContentListByContentCodes(contentCodes, siteProjectId);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    public String getTitleById(int id) {
        return infoMapper.getTitleById(id);
    }

    /** 根据classId获取列表集合 */
    //    @Cacheable(value = "articleByClassId", key =
    // "'articleByClassId'+#p0+'_'+#p1+'_'+#p2+'_'+#p3")
    @Override
    public PageResult<ContentEntity> getUnpinnedArticleSummaryPage(
            Long siteId, int classId, int page, int size) {
        return Pages.query(
                page,
                size,
                paging -> infoMapper.getUnpinnedArticleSummaryPage(paging, siteId, classId));
    }

    @Override
    public List<ContentEntity> getChannelTopArticleSummariesByIdentifies(
            Long siteId, String identifies, int limit) {
        if (siteId == null || identifies == null || identifies.isBlank() || limit <= 0) {
            return List.of();
        }
        return infoMapper.getChannelTopArticleSummariesByIdentifies(siteId, identifies, limit);
    }

    @Override
    public List<ContentEntity> getPopularArticleSummariesByIdentifies(
            Long siteId, String identifies, int limit) {
        if (siteId == null || identifies == null || identifies.isBlank() || limit <= 0) {
            return List.of();
        }
        return infoMapper.getPopularArticleSummariesByIdentifies(siteId, identifies, limit);
    }

    @Override
    public List<ContentEntity> getPublishedSummariesByIds(Long siteId, List<Integer> ids) {
        if (siteId == null || ids == null || ids.isEmpty()) {
            return List.of();
        }
        return infoMapper.getPublishedSummariesByIds(siteId, ids);
    }

    @Override
    public List<ContentEntity> getPublishedSummariesByRelations(
            Long siteId, List<Integer> classIds, List<Integer> relationIds) {
        if (siteId == null
                || classIds == null
                || classIds.isEmpty()
                || relationIds == null
                || relationIds.isEmpty()) {
            return List.of();
        }
        return infoMapper.getPublishedSummariesByRelations(siteId, classIds, relationIds);
    }

    @Override
    public List<ContentEntity> getPinnedArticleSummaries(Long siteId, int classId, int limit) {
        if (siteId == null || classId <= 0 || limit <= 0) {
            return List.of();
        }
        return infoMapper.getPinnedArticleSummaries(siteId, classId, limit);
    }

    @Override
    public List<ContentEntity> getArticleSummariesByClassId(int classId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return infoMapper.getArticleSummariesByClassId(classId, limit);
    }

    @Override
    public List<ContentEntity> getArticleByClassId(
            int classId, int limit, int setting, int pageIndex) {
        if (pageIndex < 1) {
            pageIndex = 1;
        }
        return infoMapper.getArticlePageByClassId(classId, limit, setting, (pageIndex - 1) * limit);
    }

    @Override
    public PageResult<ContentEntity> getEventSummaryPage(
            Long siteId, int classId, int page, int size) {
        return Pages.query(
                page, size, paging -> infoMapper.getEventSummaryPage(paging, siteId, classId));
    }

    @Override
    public PageResult<ContentEntity> getPublishedContentPage(
            Long siteId, int classId, int page, int size) {
        return Pages.query(
                page, size, paging -> infoMapper.getPublishedContentPage(paging, siteId, classId));
    }

    @Override
    public PageResult<ContentEntity> getNewsSummaryPage(
            Long siteId,
            int classId,
            List<String> categoryValues,
            LocalDateTime monthStart,
            LocalDateTime monthEnd,
            int page,
            int size) {
        return Pages.query(
                page,
                size,
                paging ->
                        infoMapper.getNewsSummaryPage(
                                paging, siteId, classId, categoryValues, monthStart, monthEnd));
    }

    @Override
    public ContentEntity getPublishedNewsSummary(Long siteId, int classId, int id) {
        return infoMapper.getPublishedNewsSummary(siteId, classId, id);
    }

    @Override
    public ContentEntity getNewsNeighbor(
            Long siteId, int classId, LocalDateTime time, int id, boolean previous) {
        return infoMapper.getNewsNeighbor(siteId, classId, time, id, previous);
    }

    @Override
    public List<ContentEntity> getArticleByClassIdWithFilters(
            int classId, int limit, int setting, Map<String, Object> fieldNameFilters) {
        // 字段名 → 列名（白名单校验），值走参数绑定，多条件等值 AND
        Map<String, String> columnFilters = new LinkedHashMap<>();
        if (fieldNameFilters != null) {
            for (Map.Entry<String, Object> entry : fieldNameFilters.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String column = ContentColumnWhitelist.resolve(entry.getKey());
                if (column == null) {
                    continue;
                }
                columnFilters.put(column, String.valueOf(entry.getValue()));
            }
        }
        if (columnFilters.isEmpty()) {
            return getArticleByClassId(classId, limit, setting, 1);
        }
        return infoMapper.getArticleByClassIdWithFilters(classId, limit, setting, columnFilters);
    }

    @Override
    public List<ContentEntity> getContentListByParentId(
            int parentId, int limit, int setting, String outClassIds) {
        List<Integer> classIdIntegerList = new ArrayList<>();
        if (StringUtils.isNotBlank(outClassIds)) {
            List<String> classIdList = Arrays.asList(outClassIds.split(WebConst.COMMA));
            for (String classId : classIdList) {
                classIdIntegerList.add(Integer.valueOf(classId));
            }
        }
        return infoMapper.getContentListByParentId(parentId, limit, setting, classIdIntegerList);
    }

    @Override
    public Pager<ContentEntity> getNewsListByClassIdXgidAndPage(IdEntity idEntity) {
        if (idEntity.getConditionVo() != null
                && (StringUtils.isNotEmpty(idEntity.getConditionVo().getYearString())
                        || StringUtils.isNotEmpty(idEntity.getConditionVo().getType())
                        || StringUtils.isNotEmpty(idEntity.getConditionVo().getArea()))) {
            throw new IllegalArgumentException("通用栏目分页不支持 yearString/type/area 客户字段映射，请使用显式模型字段过滤");
        }
        if (idEntity.getPageIndex() <= 1) {
            idEntity.setPageIndex(1);
        }
        Pager<ContentEntity> pager = new Pager<>();
        pager.setSize(idEntity.getPageSize()); // 每页显示条数
        pager.setPage(idEntity.getPageIndex()); // 当前页码
        pager.setConditionVo(idEntity.getConditionVo()); // 所有搜索条件

        // (page-1)*10 从第N条开始

        pager.setRows(
                infoMapper.getInfoListByClassIdXgidAndPageAndPageSize(
                        idEntity.getClassId(),
                        idEntity.getRelationId(),
                        idEntity.getPageSize(),
                        (idEntity.getPageIndex() - 1) * idEntity.getPageSize(),
                        idEntity.getConditionVo()));
        pager.setTotal(
                infoMapper.getInfoCountByClassIdXgidAndPage(
                        idEntity.getClassId(),
                        idEntity.getRelationId(),
                        idEntity.getConditionVo()));
        if (StringUtils.isNotBlank(idEntity.getPagePath())) {
            pager.setPagePath(idEntity.getPagePath());
        } else {
            pager.setPagePath("list_" + idEntity.getParentId() + "_" + idEntity.getClassId() + "");
        }
        pager.setPageHtml();

        return pager;
    }

    //  后期可以考虑加入redis缓存
    //    @Cacheable(value = "info", key = "'classId:'+#classId+'_id:'+#id")
    @Override
    public ContentEntity getArticleById(int classId, int id) {
        ContentEntity articleById = getArticleById(id);
        if (classId == articleById.getClassid()) {
            return articleById;
        } else {
            return null;
        }
    }

    @Override
    public DetailsVo getPrevAndNextByIdEntity(IdEntity idEntity) {
        List<Integer> integerList = infoMapper.getIdList(idEntity);
        Integer prevId = 0;
        Integer nextId = 0;
        boolean isSuccess = false;
        for (int i = 0; i < integerList.size(); i++) {
            if (isSuccess) {
                break;
            }
            if (integerList.get(i).equals(idEntity.getInfoId())) {
                isSuccess = true;
                if (i != 0) {
                    prevId = integerList.get(i - 1);
                }
                if (i != integerList.size() - 1) {
                    nextId = integerList.get(i + 1);
                }
            }
        }
        DetailsVo detailsVo = new DetailsVo();
        if (prevId == 0) {
            detailsVo.setPrevTitle("已是第一篇了");
            detailsVo.setPrevUrl("javascript:void(0);");
        } else {
            ContentEntity articleById = infoMapper.getArticleById(prevId);
            detailsVo.setPrevTitle(articleById.getTitle());
            detailsVo.setPrevUrl(getDetailUrl(articleById));
        }
        if (nextId == 0) {
            detailsVo.setNextTitle("已是最后一篇了");
            detailsVo.setNextUrl("javascript:void(0);");
        } else {
            ContentEntity articleById = infoMapper.getArticleById(nextId);
            detailsVo.setNextTitle(articleById.getTitle());
            detailsVo.setNextUrl(getDetailUrl(articleById));
        }
        return detailsVo;
    }

    @Override
    public List<GroupByVo> getGroupByTitleByIdEntity(IdEntity idEntity, boolean addAll) {
        List<ContentEntity> contentEntities =
                infoMapper.getGroupByTitle(idEntity.getClassId(), idEntity.getRelationId());
        List<GroupByVo> groupByVos = new ArrayList<>();
        GroupByVo groupByVo;
        if (addAll) {
            groupByVo = new GroupByVo();
            groupByVo.setTitle("不限");
            groupByVos.add(groupByVo);
        }
        for (ContentEntity contentEntity : contentEntities) {
            groupByVo = new GroupByVo();
            groupByVo.setTitle(contentEntity.getSingleLine8());
            groupByVos.add(groupByVo);
        }
        return groupByVos;
    }

    @Override
    public Boolean add(AjaxForm ajaxForm) {
        ajaxForm.setCreateTime(new Date());
        return infoMapper.add(ajaxForm);
    }

    @Override
    public Pager<ContentEntity> searchByKey(IdEntity idEntity) {
        if (idEntity == null || idEntity.getServerSiteProjectId() == null)
            throw new IllegalArgumentException("搜索须使用当前站点服务器提供的idEntity");
        var condition = idEntity.getConditionVo();
        if (condition == null) throw new IllegalArgumentException("请提供搜索词");
        if (StringUtils.isNotBlank(condition.getYearString())
                || StringUtils.isNotBlank(condition.getType())
                || StringUtils.isNotBlank(condition.getArea()))
            throw new IllegalArgumentException("旧语义筛选不适用于公开搜索，请使用显式模型过滤");
        String key =
                StringUtils.isNotBlank(condition.getKey())
                        ? condition.getKey()
                        : condition.getKeywords();
        var query =
                com.xinhongru.cms.search.PublicSearchQuery.forLegacy(
                                idEntity.getServerSiteProjectId(),
                                idEntity.getSiteProjectId(),
                                key,
                                idEntity.getPageIndex() == null ? 1 : idEntity.getPageIndex(),
                                idEntity.getPageSize() == null ? 10 : idEntity.getPageSize())
                        .withDates(condition.getYear(), condition.getMonth());
        Integer menu = condition.getClassIds();
        if (menu != null && menu <= 0) throw new IllegalArgumentException("搜索栏目无效");
        var results = publicSearchService.search(query, menu, true);
        List<ContentEntity> rows = new ArrayList<>();
        for (var hit : results.list()) {
            if (!"content".equals(hit.kind())) throw new IllegalStateException("旧搜索不接受单页结果");
            rows.add(
                    new ContentEntity()
                            .setId(hit.id())
                            .setClassid(hit.menuId())
                            .setSiteProjectId(query.siteId())
                            .setTitle(hit.title())
                            .setCreateTime(hit.createTime())
                            .setStatus(1));
        }
        Pager<ContentEntity> pager = new Pager<>();
        pager.setRows(rows);
        pager.setTotal(Math.toIntExact(results.total()));
        pager.setSize(query.size());
        pager.setPage(query.page());
        pager.setConditionVo(condition);
        if (StringUtils.isNotBlank(idEntity.getPagePath())) {
            if (!com.xinhongru.cms.search.PublicSearchService.safePath(idEntity.getPagePath()))
                throw new IllegalArgumentException("搜索分页路径无效");
            pager.setPagePath(idEntity.getPagePath());
            pager.setPageHtml();
        } else {
            var nav = org.jsoup.Jsoup.parseBodyFragment("").body().appendElement("nav");
            if (query.page() > 1)
                nav.appendElement("a")
                        .attr("href", searchPageUrl(query, menu, query.page() - 1))
                        .text("上一页");
            if ((long) query.page() * query.size() < results.total())
                nav.appendElement("a")
                        .attr("href", searchPageUrl(query, menu, query.page() + 1))
                        .text("下一页");
            pager.setPageHtml(nav.outerHtml());
            pager.setPageCount(Math.toIntExact(results.getPages()));
            pager.setPagePath("/__cms/search");
            if (results.total() > 0) {
                pager.setFirstUrl(searchPageUrl(query, menu, 1));
                pager.setLastUrl(searchPageUrl(query, menu, Math.toIntExact(results.getPages())));
            }
            if (query.page() > 1) pager.setPrevUrl(searchPageUrl(query, menu, query.page() - 1));
            if ((long) query.page() * query.size() < results.total())
                pager.setNextUrl(searchPageUrl(query, menu, query.page() + 1));
        }
        return pager;
    }

    private String searchPageUrl(
            com.xinhongru.cms.search.PublicSearchQuery query, Integer menu, int page) {
        String url =
                "/__cms/search?kind=content&q="
                        + java.net.URLEncoder.encode(
                                query.keyword(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&page="
                        + page
                        + "&size="
                        + query.size();
        if (query.keyword().isEmpty()) url += "&legacy=1";
        if (menu != null) url += "&menuId=" + menu;
        if (query.year() != null) url += "&year=" + query.year();
        if (query.month() != null) url += "&month=" + query.month();
        return url;
    }

    /** 根据关联ID读取关联数据 */
    @Override
    public List<ContentEntity> getContentListByClassIdAndRelationId(
            Integer classId, Integer relationId) {
        return infoMapper.getContentListByClassIdAndRelationId(classId, relationId);
    }

    @Override
    public List<ContentEntity> getContentListNoPublishedByClassId(Integer classId) {
        return infoMapper.getContentListNoPublishedByClassId(classId);
    }

    @Override
    public List<ContentEntity> getContentListByParentIdAndShowPosition(
            int parentId, String showPosition, int limit) {
        List<OneshotEntity> oneshotListByParentId = menuMapper.getOneshotListByParentId(parentId);
        oneshotListByParentId =
                oneshotListByParentId.stream()
                        .filter(
                                oneshotEntity ->
                                        oneshotEntity.getShowPosition() != null
                                                && oneshotEntity
                                                        .getShowPosition()
                                                        .contains(showPosition))
                        .collect(Collectors.toList());

        List<Integer> classIdIntegerList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(oneshotListByParentId)) {
            for (OneshotEntity oneshotEntity : oneshotListByParentId) {
                classIdIntegerList.add(oneshotEntity.getId());
            }
        }
        if (!classIdIntegerList.isEmpty()) {
            return infoMapper.getContentListByParentIdAndShowPosition(classIdIntegerList, limit);
        }
        return null;
    }

    @Override
    public Pager<ContentEntity> getNewsListByParentIdAndPage(IdEntity idEntity) {
        if (idEntity.getPageIndex() <= 1) {
            idEntity.setPageIndex(1);
        }
        Pager<ContentEntity> pager = new Pager<>();
        pager.setSize(idEntity.getPageSize()); // 每页显示条数
        pager.setPage(idEntity.getPageIndex()); // 当前页码

        // (page-1)*10 从第N条开始

        pager.setRows(
                infoMapper.getInfoListByParentIdAndPageAndPageSize(
                        idEntity.getParentId(),
                        idEntity.getPageSize(),
                        (idEntity.getPageIndex() - 1) * idEntity.getPageSize(),
                        idEntity.getConditionVo()));
        pager.setTotal(
                infoMapper.getInfoCountByParentIdAndPage(
                        idEntity.getParentId(), idEntity.getConditionVo()));
        if (StringUtils.isNotBlank(idEntity.getPagePath())) {
            pager.setPagePath(idEntity.getPagePath());
        } else {
            pager.setPagePath("list_" + idEntity.getParentId() + "_" + idEntity.getClassId() + "");
        }
        pager.setPageHtml();

        return pager;
    }

    @Override
    public List<ContentEntity> getContentHotListByParentId(int parentId, int limit) {
        List<OneshotEntity> oneshotListByParentId = menuMapper.getOneshotListByParentId(parentId);
        oneshotListByParentId =
                oneshotListByParentId.stream()
                        .filter(
                                oneshotEntity ->
                                        oneshotEntity.getShowPosition() != null
                                                && oneshotEntity
                                                        .getIdentifies()
                                                        .contains("aFs7w9fkCJnQtr6a"))
                        .collect(Collectors.toList());

        List<Integer> classIdIntegerList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(oneshotListByParentId)) {
            for (OneshotEntity oneshotEntity : oneshotListByParentId) {
                classIdIntegerList.add(oneshotEntity.getId());
            }
        }
        if (!classIdIntegerList.isEmpty()) {
            return infoMapper.getContentHotListByParentId(classIdIntegerList, limit);
        }
        return null;
    }

    @Override
    public List<ContentEntity> getContentHotListByClassId(int classId, int limit) {
        return infoMapper.getContentHotListByClassId(classId, limit);
    }

    @Override
    public List<ContentEntity> getContentListByIds(List<String> idList) {
        return infoMapper.getContentListByIds(idList);
    }

    @Override
    public ContentEntity getArticleByTitleAndClassId(String title, Integer classId) {
        return infoMapper.getArticleByTitleAndClassId(title, classId);
    }

    @Override
    public Boolean updateArticle(ContentEntity contentEntity) {
        return infoMapper.updateArticle(contentEntity);
    }

    @Override
    public List<String> getContentFieldByClassIdGroupBy(
            int classId, String fieldName, int limit, boolean isGroup, String sortType) {
        return infoMapper.getContentFieldByClassIdGroupBy(
                classId, fieldName, limit, isGroup, sortType);
    }

    @Override
    public Pager<ContentEntity> getNewsListByClassIdsPage(String classIds, IdEntity idEntity) {
        if (idEntity.getPageIndex() <= 1) {
            idEntity.setPageIndex(1);
        }
        Pager<ContentEntity> pager = new Pager<>();
        pager.setSize(idEntity.getPageSize()); // 每页显示条数
        pager.setPage(idEntity.getPageIndex()); // 当前页码
        pager.setConditionVo(idEntity.getConditionVo()); // 所有搜索条件

        // (page-1)*10 从第N条开始
        List<Integer> classIdList = new ArrayList<>();
        if (StringUtils.isNotBlank(classIds)) {
            classIdList =
                    Arrays.stream(classIds.split(","))
                            .map(item -> Integer.valueOf(item))
                            .collect(Collectors.toList());
        } else {
            return null;
        }

        pager.setRows(
                infoMapper.getInfoListByClassIdsAndPageAndPageSize(
                        classIdList,
                        idEntity.getRelationId(),
                        idEntity.getPageSize(),
                        (idEntity.getPageIndex() - 1) * idEntity.getPageSize(),
                        idEntity.getConditionVo()));
        pager.setTotal(
                infoMapper.getInfoCountByClassIdsAndPage(
                        classIdList, idEntity.getRelationId(), idEntity.getConditionVo()));
        if (StringUtils.isNotBlank(idEntity.getPagePath())) {
            pager.setPagePath(idEntity.getPagePath());
        } else {
            pager.setPagePath("list_" + idEntity.getParentId() + "_" + idEntity.getClassId() + "");
        }
        pager.setPageHtml();

        return pager;
    }

    private String getDetailUrl(ContentEntity contentEntity) {
        if (StringUtils.isNotBlank(contentEntity.getSingleLine4())) {
            return contentEntity.getSingleLine4();
        } else if (StringUtils.isNotBlank(contentEntity.getFile1())) {
            return contentEntity.getFile1();
        } else {
            OneshotEntity modelById = menuMapper.getModelById(contentEntity.getClassid());
            return modelById
                    .getDetailPageUrl()
                    .replace("{id}", String.valueOf(contentEntity.getId()))
                    .replace("{classid}", String.valueOf(modelById.getId()))
                    .replace("{classId}", String.valueOf(modelById.getId()));
        }
    }
}

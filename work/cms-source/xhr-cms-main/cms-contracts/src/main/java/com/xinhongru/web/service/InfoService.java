package com.xinhongru.web.service;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.dto.vo.list.Pager;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.web.pojo.AjaxForm;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.resonseVo.GroupByVo;
import com.xinhongru.web.resonseVo.IdEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 22:09
 */
public interface InfoService {
    /** 可信模板显式允许列表与模型授权交叉，查询已发布且在有效期内的内容。 */
    PageResult<ContentEntity> getStructuredPublishedPage(
            com.xinhongru.web.resonseVo.IdEntity serverContext,
            int classId,
            int page,
            int size,
            Map<String, String> filters,
            List<String> allowedFilterKeys,
            List<String> allowedSortKeys);

    /** 根据ID获取文章内容 */
    ContentEntity getArticleById(int id);

    /**
     * 根据内容 contentCode 与站点项目ID获取当前站点对应的内容（仅返回 id、contentCode，不统计点击数）
     *
     * <p>多站点场景下内容在不同站点的 t_contents.id 不同、但 contentCode 相同， 通过 (contentCode, siteProjectId)
     * 定位当前站点的内容。 注意与栏目表 t_oneshot.biz_code 区分。
     *
     * @param contentCode 内容业务编码
     * @param siteProjectId 站点项目ID
     * @return 匹配的内容（仅含 id、contentCode），未匹配时返回 null
     */
    ContentEntity getContentIdByContentCode(String contentCode, Long siteProjectId);

    /**
     * 根据一组内容 contentCode 与站点项目ID批量获取当前站点对应的内容（仅含 id、contentCode）
     *
     * @param contentCodes 内容业务编码集合
     * @param siteProjectId 站点项目ID
     * @return 匹配的内容列表，未匹配时返回空集合
     */
    List<ContentEntity> getContentListByContentCodes(List<String> contentCodes, Long siteProjectId);

    /** 根据Id获取Title字段 */
    String getTitleById(int id);

    /** 根据classId获取列表集合 */
    List<ContentEntity> getArticleByClassId(int classId, int limit, int setting, int pageIndex);

    /** 公开内容卡片：保留发布状态、有效期和排序，不加载 content1 至 content6 正文。 */
    List<ContentEntity> getArticleSummariesByClassId(int classId, int limit);

    /** Published, valid column-pinned summaries, scoped to the site and ordered by CMS sort. */
    List<ContentEntity> getPinnedArticleSummaries(Long siteId, int classId, int limit);

    /** Published summaries excluding menu_top = 1; filtering applies before counting and paging. */
    PageResult<ContentEntity> getUnpinnedArticleSummaryPage(
            Long siteId, int classId, int page, int size);

    /** Published, valid event summaries ordered by sort, create_time and id descending. */
    PageResult<ContentEntity> getEventSummaryPage(Long siteId, int classId, int page, int size);

    /** 当前站点直属内容聚合使用的已发布记录，保留正文、有效期和栏目默认排序。 */
    PageResult<ContentEntity> getPublishedContentPage(Long siteId, int classId, int page, int size);

    /** 已发布且在有效期内的新闻摘要，按发布时间和 ID 倒序分页。 */
    PageResult<ContentEntity> getNewsSummaryPage(
            Long siteId,
            int classId,
            List<String> categoryValues,
            LocalDateTime monthStart,
            LocalDateTime monthEnd,
            int page,
            int size);

    ContentEntity getPublishedNewsSummary(Long siteId, int classId, int id);

    /** previous=true 返回倒序列表中紧邻当前记录的上一篇。 */
    ContentEntity getNewsNeighbor(
            Long siteId, int classId, LocalDateTime time, int id, boolean previous);

    /**
     * 根据classId并按字段值过滤获取列表集合。
     *
     * @param classId 栏目ID
     * @param limit 查询条数
     * @param setting 推荐位编号，0 表示不过滤
     * @param fieldNameFilters key=字段名（如 singleLine1、title），value=字段值；未识别字段忽略；多条件等值 AND
     * @return 内容列表
     */
    List<ContentEntity> getArticleByClassIdWithFilters(
            int classId, int limit, int setting, Map<String, Object> fieldNameFilters);

    /** 根据parentId获取列表集合 */
    List<ContentEntity> getContentListByParentId(
            int parentId, int limit, int setting, String outClassIds);

    Pager<ContentEntity> getNewsListByClassIdXgidAndPage(IdEntity idEntity);

    /** 根据classId和id获取文章内容 */
    ContentEntity getArticleById(int classId, int id);

    /** 获取当前的上一篇和下一篇对象 */
    DetailsVo getPrevAndNextByIdEntity(IdEntity idEntity);

    /**
     * 根据条件group by title 字段
     *
     * @param idEntity 条件
     * @param addAll 是否添加全部选项
     */
    List<GroupByVo> getGroupByTitleByIdEntity(IdEntity idEntity, boolean addAll);

    /** 前台表单提交 */
    Boolean add(AjaxForm ajaxForm);

    /** 全站通过关键词搜索 */
    Pager<ContentEntity> searchByKey(IdEntity idEntity);

    /** 根据关联ID读取关联数据 */
    List<ContentEntity> getContentListByClassIdAndRelationId(Integer classId, Integer relationId);

    /** 通过栏目ID获取当前栏目未发布过的数据集合 */
    List<ContentEntity> getContentListNoPublishedByClassId(Integer classId);

    /** 根据父栏目ID和显示位置获取数据集合 */
    List<ContentEntity> getContentListByParentIdAndShowPosition(
            int parentId, String showPosition, int limit);

    /** 根据父级ID获取带分页数据对象 */
    Pager<ContentEntity> getNewsListByParentIdAndPage(IdEntity idEntity);

    /** 根据父级栏目ID获取所有列表中浏览量最高的数据 */
    List<ContentEntity> getContentHotListByParentId(int parentId, int limit);

    /** 根据栏目ID获取所有列表中浏览量最高的数据 */
    List<ContentEntity> getContentHotListByClassId(int classId, int limit);

    /** 根据id集合查询集合对象 */
    List<ContentEntity> getContentListByIds(List<String> idList);

    /** Published, valid summaries scoped to the site; no rich-text fields are loaded. */
    List<ContentEntity> getPublishedSummariesByIds(Long siteId, List<Integer> ids);

    /** Published channel-top summaries across matching columns in the current site. */
    List<ContentEntity> getChannelTopArticleSummariesByIdentifies(
            Long siteId, String identifies, int limit);

    /** Global hits ranking across all matching columns in the current site, without rich text. */
    List<ContentEntity> getPopularArticleSummariesByIdentifies(
            Long siteId, String identifies, int limit);

    /** Published summaries from the specified columns and related content IDs. */
    List<ContentEntity> getPublishedSummariesByRelations(
            Long siteId, List<Integer> classIds, List<Integer> relationIds);

    ContentEntity getArticleByTitleAndClassId(String title, Integer classId);

    /** 更新文章 */
    Boolean updateArticle(ContentEntity contentEntity);

    List<String> getContentFieldByClassIdGroupBy(
            int classId, String fieldName, int limit, boolean isGroup, String sortType);

    Pager<ContentEntity> getNewsListByClassIdsPage(String classIds, IdEntity idEntity);
}

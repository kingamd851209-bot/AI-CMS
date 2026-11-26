package com.xinhongru.web.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.web.pojo.AjaxForm;
import com.xinhongru.web.resonseVo.ConditionVo;
import com.xinhongru.web.resonseVo.IdEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 22:13
 */
@Mapper
public interface InfoMapper {

    List<ContentEntity> getEventSummaryPage(
            IPage<ContentEntity> page, @Param("siteId") Long siteId, @Param("classId") int classId);

    List<ContentEntity> getPublishedContentPage(
            IPage<ContentEntity> page, @Param("siteId") Long siteId, @Param("classId") int classId);

    List<ContentEntity> getNewsSummaryPage(
            IPage<ContentEntity> page,
            @Param("siteId") Long siteId,
            @Param("classId") int classId,
            @Param("categoryValues") List<String> categoryValues,
            @Param("monthStart") LocalDateTime monthStart,
            @Param("monthEnd") LocalDateTime monthEnd);

    ContentEntity getPublishedNewsSummary(
            @Param("siteId") Long siteId, @Param("classId") int classId, @Param("id") int id);

    ContentEntity getNewsNeighbor(
            @Param("siteId") Long siteId,
            @Param("classId") int classId,
            @Param("time") LocalDateTime time,
            @Param("id") int id,
            @Param("previous") boolean previous);

    List<ContentEntity> getUnpinnedArticleSummaryPage(
            IPage<ContentEntity> page, @Param("siteId") Long siteId, @Param("classId") int classId);

    List<ContentEntity> getChannelTopArticleSummariesByIdentifies(
            @Param("siteId") Long siteId,
            @Param("identifies") String identifies,
            @Param("limit") int limit);

    List<ContentEntity> getPopularArticleSummariesByIdentifies(
            @Param("siteId") Long siteId,
            @Param("identifies") String identifies,
            @Param("limit") int limit);

    List<ContentEntity> getPublishedSummariesByIds(
            @Param("siteId") Long siteId, @Param("ids") List<Integer> ids);

    List<ContentEntity> getPublishedSummariesByRelations(
            @Param("siteId") Long siteId,
            @Param("classIds") List<Integer> classIds,
            @Param("relationIds") List<Integer> relationIds);

    List<ContentEntity> getPinnedArticleSummaries(
            @Param("siteId") Long siteId, @Param("classId") int classId, @Param("limit") int limit);

    List<ContentEntity> getArticleSummariesByClassId(
            @Param("classId") int classId, @Param("limit") int limit);

    /**
     * 根据ID获取文章内容
     *
     * @param id
     * @return
     */
    ContentEntity getArticleById(@Param("id") int id);

    /**
     * 根据内容 contentCode 与站点项目ID获取当前站点对应的内容（仅返回 id、contentCode）
     *
     * <p>contentCode 为内容跨站点稳定标识（新增内容时默认等于其 id）， 多站点场景下通过 (content_code, site_project_id) 定位当前站点的内容。
     * 注意与栏目表 t_oneshot.biz_code 区分。
     *
     * @param contentCode 内容业务编码，不能为空
     * @param siteProjectId 站点项目ID，不能为空
     * @return 匹配的内容（仅含 id、contentCode），未匹配时返回 null
     */
    ContentEntity getContentIdByContentCode(
            @Param("contentCode") String contentCode, @Param("siteProjectId") Long siteProjectId);

    /**
     * 根据一组内容 contentCode 与站点项目ID批量获取当前站点对应的内容（仅返回 id、contentCode）
     *
     * @param contentCodes 内容业务编码集合，不能为空
     * @param siteProjectId 站点项目ID，不能为空
     * @return 匹配的内容列表（仅含 id、contentCode），未匹配时返回空集合
     */
    List<ContentEntity> getContentListByContentCodes(
            @Param("contentCodes") List<String> contentCodes,
            @Param("siteProjectId") Long siteProjectId);

    /**
     * 上一篇下一篇专用
     *
     * @param id
     * @return
     */
    ContentEntity getArticleForPrevAndNextById(@Param("id") int id);

    /**
     * 根据classId获取列表集合
     *
     * @param classId
     * @param limit
     * @param setting
     * @return
     */
    List<ContentEntity> getArticleByClassId(
            @Param("classId") int classId,
            @Param("limit") int limit,
            @Param("setting") int setting);

    /**
     * 按 classId + 动态列等值过滤获取内容列表。
     *
     * @param classId 栏目ID
     * @param limit 查询条数
     * @param setting 推荐位编号，0 表示不过滤
     * @param filters 列名 → 值（列名必须来自 {@code ContentColumnWhitelist} 白名单，等值 AND；值走参数绑定）
     */
    List<ContentEntity> getArticleByClassIdWithFilters(
            @Param("classId") int classId,
            @Param("limit") int limit,
            @Param("setting") int setting,
            @Param("filters") Map<String, String> filters);

    /**
     * 根据classId获取列表集合
     *
     * @param classId
     * @param limit
     * @param setting
     * @param start
     * @return
     */
    List<ContentEntity> getArticlePageByClassId(
            @Param("classId") int classId,
            @Param("limit") int limit,
            @Param("setting") int setting,
            @Param("start") int start);

    /**
     * 根据父级栏目Id获取列表内容
     *
     * @param parentId
     * @param limit
     * @param setting
     * @param outClassIds
     * @return
     */
    List<ContentEntity> getContentListByParentId(
            @Param("parentId") int parentId,
            @Param("limit") int limit,
            @Param("setting") int setting,
            @Param("outClassIds") List<Integer> outClassIds);

    /**
     * 按照分页读取数据
     *
     * @param classid
     * @param xgid
     * @param pageSize
     * @param page
     * @param conditionVo
     * @return
     */
    List<ContentEntity> getInfoListByClassIdXgidAndPageAndPageSize(
            @Param("classid") Integer classid,
            @Param("xgid") Integer xgid,
            @Param("pageSize") Integer pageSize,
            @Param("page") Integer page,
            @Param("conditionVo") ConditionVo conditionVo);

    /**
     * 按照读取符合条件的总记录数
     *
     * @param classid
     * @param xgid
     * @param conditionVo
     * @return
     */
    Integer getInfoCountByClassIdXgidAndPage(
            @Param("classid") Integer classid,
            @Param("xgid") Integer xgid,
            @Param("conditionVo") ConditionVo conditionVo);

    /**
     * 获取栏目的所有ID对象
     *
     * @param idEntity
     * @return
     */
    List<Integer> getIdList(IdEntity idEntity);

    /**
     * 根据条件group by title 字段
     *
     * @param classId
     * @param infoXgid
     * @return
     */
    List<ContentEntity> getGroupByTitle(
            @Param("classId") Integer classId, @Param("xgid") Integer infoXgid);

    /**
     * 前台表单数据提交
     *
     * @param ajaxForm
     * @return
     */
    Boolean add(AjaxForm ajaxForm);

    /**
     * 全站搜索
     *
     * @param pageSize
     * @param startNumber
     * @param conditionVo
     * @return
     */
    List<ContentEntity> getInfoListByKeyPageAndPageSize(
            @Param("pageSize") Integer pageSize,
            @Param("startNumber") int startNumber,
            @Param("conditionVo") ConditionVo conditionVo);

    /**
     * 全站搜索总记录数
     *
     * @param conditionVo
     * @return
     */
    Integer getInfoCountByKeyAndPage(@Param("conditionVo") ConditionVo conditionVo);

    /**
     * 更新内容页点击率
     *
     * @param classid
     * @param id
     */
    void updateHits(@Param("classid") Integer classid, @Param("id") int id);

    /**
     * 根据关联ID读取关联数据
     *
     * @param classId
     * @param relationId
     * @return
     */
    List<ContentEntity> getContentListByClassIdAndRelationId(
            @Param("classId") Integer classId, @Param("relationId") Integer relationId);

    /**
     * @param classId
     * @return
     */
    List<ContentEntity> getContentListNoPublishedByClassId(@Param("classId") Integer classId);

    /**
     * 根据Id获取标题
     *
     * @param id
     * @return
     */
    String getTitleById(@Param("id") int id);

    /**
     * 根据父栏目ID和显示位置获取数据集合
     *
     * @param classIdIntegerList
     * @param limit
     * @return
     */
    List<ContentEntity> getContentListByParentIdAndShowPosition(
            @Param("classIdIntegerList") List<Integer> classIdIntegerList,
            @Param("limit") int limit);

    List<ContentEntity> getInfoListByParentIdAndPageAndPageSize(
            @Param("parentId") Integer parentId,
            @Param("pageSize") Integer pageSize,
            @Param("page") Integer page,
            @Param("conditionVo") ConditionVo conditionVo);

    Integer getInfoCountByParentIdAndPage(
            @Param("parentId") Integer parentId, @Param("conditionVo") ConditionVo conditionVo);

    List<ContentEntity> getContentHotListByParentId(
            @Param("classIdList") List<Integer> classIdList, @Param("limit") int limit);

    /**
     * 根据手机号获取数据
     *
     * @param phone
     * @return
     */
    int addArticle(ContentEntity registerContentEntity);

    Boolean updateArticle(ContentEntity contentEntity);

    List<ContentEntity> getContentHotListByClassId(
            @Param("classId") int classId, @Param("limit") int limit);

    /**
     * 根据id集合查询数据对象
     *
     * @param idList
     * @return
     */
    List<ContentEntity> getContentListByIds(@Param("idList") List<String> idList);

    ContentEntity getArticleByTitleAndClassId(
            @Param("title") String title, @Param("classId") Integer classId);

    List<String> getContentFieldByClassIdGroupBy(
            @Param("classId") int classId,
            @Param("fieldName") String fieldName,
            @Param("limit") int limit,
            @Param("isGroup") boolean isGroup,
            @Param("sortType") String sortType);

    List<ContentEntity> getInfoListByClassIdsAndPageAndPageSize(
            @Param("classIdList") List<Integer> classIdList,
            @Param("relationId") Integer relationId,
            @Param("pageSize") Integer pageSize,
            @Param("page") Integer page,
            @Param("conditionVo") ConditionVo conditionVo);

    Integer getInfoCountByClassIdsAndPage(
            @Param("classIdList") List<Integer> classIdList,
            @Param("relationId") Integer relationId,
            @Param("conditionVo") ConditionVo conditionVo);

    List<ContentEntity> getInfoListByIdEntity(@Param("conditionVo") ConditionVo conditionVo);
}

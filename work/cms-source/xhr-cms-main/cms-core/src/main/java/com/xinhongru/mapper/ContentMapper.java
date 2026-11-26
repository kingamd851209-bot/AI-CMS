package com.xinhongru.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinhongru.dto.TopHitRow;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.dto.cond.ContentSearchCriterion;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.model.OutputDto;
import com.xinhongru.responseVo.model.ModelManageSeoVo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 文章持久层 Created at 2021/8/9. */
@Mapper
@Component
public interface ContentMapper {
    @Select(
            "SELECT COUNT(*) FROM t_contents WHERE site_project_id=#{siteId} AND class_id=#{menuId}"
                    + " AND title=#{title} AND deleted=0")
    long countSeedTitle(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("title") String title);

    @Select(
            """
            SELECT COUNT(*) FROM t_contents
            WHERE site_project_id=#{siteId} AND class_id=#{menuId} AND deleted=0
            """)
    int countNavigationRecords(@Param("siteId") Long siteId, @Param("menuId") Integer menuId);

    List<ContentEntity> getArticlesByCond(
            IPage<ContentEntity> page,
            @Param("fieldNameList") List<String> fields,
            @Param("contentCond") ContentCond condition,
            @Param("criteria") List<ContentSearchCriterion> criteria);

    List<ContentEntity> getRecentlyArticle(IPage<ContentEntity> page);

    List<ContentEntity> searchArticle(IPage<ContentEntity> page, @Param("param") String keyword);

    List<ContentEntity> getContentByCond(
            IPage<ContentEntity> page, @Param("fieldNameList") List<String> fields);

    /**
     * 添加文章
     *
     * @param contentEntity
     * @return
     */
    int addArticle(ContentEntity contentEntity);

    /**
     * 会员名录数据导入
     *
     * @param contentEntity
     * @return
     */
    int addArticleImport(ContentEntity contentEntity);

    /** 统计指定栏目下的内容数量（未删除） */
    @Select("SELECT COUNT(*) FROM t_contents WHERE class_id = #{classId} AND deleted = 0")
    int countByClassId(@Param("classId") Integer classId);

    /** 栏目类型修正需要保护活动及已删除记录，并在同一事务中读取当前值。 */
    @Select("SELECT id FROM t_contents WHERE class_id=#{menuId} LIMIT 1 FOR UPDATE")
    Integer lockAnyRecordForNavigationCorrection(@Param("menuId") Integer menuId);

    @Select("SELECT EXISTS(SELECT 1 FROM t_contents WHERE class_id=#{menuId})")
    boolean hasAnyRecordForNavigationCorrection(@Param("menuId") Integer menuId);

    /** 查询指定栏目下的全部内容（未删除），用于跨站点内容同步 */
    @Select("SELECT * FROM t_contents WHERE class_id = #{classId} AND deleted = 0 ORDER BY id ASC")
    List<ContentEntity> listByClassId(@Param("classId") Integer classId);

    /** 逻辑删除指定栏目下的全部内容（跨站点同步前清理目标栏目旧数据） */
    @Update("UPDATE t_contents SET deleted = 1 WHERE class_id = #{classId} AND deleted = 0")
    int softDeleteByClassId(@Param("classId") Integer classId);

    /**
     * 将指定内容的 contentCode 赋值为其自身 id（新增内容后调用）
     *
     * <p>contentCode 的语义：新增内容时默认取自身 id，作为跨站点稳定标识； 多站点场景下通过 (content_code, site_project_id)
     * 可定位到当前站点对应的内容。 因此该赋值必须在 addArticle 拿到自增 id 之后执行。
     *
     * @param id 新增内容的（t_contents）自增主键
     * @return 影响行数
     */
    int assignContentCodeToOwnId(@Param("id") Integer id);

    /**
     * 根据编号删除文章
     *
     * @param cid
     * @return
     */
    int deleteArticleById(@Param("cid") Integer cid);

    /**
     * 更新文章
     *
     * @param contentEntity
     * @return
     */
    int updateArticleById(ContentEntity contentEntity);

    int assignDraftGroup(
            @Param("siteId") Long siteId,
            @Param("record") ContentEntity record,
            @Param("column") String column,
            @Param("groupValue") String groupValue,
            @Param("version") int version);

    /**
     * 更新文章的评论数
     *
     * @param cid
     * @param commentsNum
     * @return
     */
    int updateArticleCommentCountById(
            @Param("cid") Integer cid, @Param("commentsNum") Integer commentsNum);

    /**
     * 根据编号获取文章
     *
     * @param id
     * @return
     */
    ContentEntity getArticleById(@Param("id") Integer id);

    /**
     * 根据编号获取未删除文章
     *
     * @param id
     * @return
     */
    ContentEntity getActiveArticleById(@Param("id") Integer id);

    @Select(
            """
            SELECT content.*
            FROM t_contents content
            JOIN t_oneshot menu ON menu.id = content.class_id
            WHERE content.id = #{contentId}
              AND content.class_id = #{menuId}
              AND content.deleted = 0
              AND menu.deleted = 0
              AND menu.site_project_id = #{siteId}
            LIMIT 1
            """)
    ContentEntity selectActiveArticleInSiteMenu(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("contentId") Integer contentId);

    @Select("""
            SELECT c.id, c.class_id AS classid
            FROM t_contents c JOIN t_oneshot m ON m.id = c.class_id
            WHERE m.site_project_id = #{siteId} AND m.deleted = 0 AND c.class_id = #{menuId}
              AND (c.site_project_id = #{siteId} OR c.site_project_id IS NULL)
              AND c.deleted = 0 AND c.status = 1
              AND c.entry_into_force_time <= CURRENT_TIMESTAMP AND c.expiration_time >= CURRENT_TIMESTAMP
            ORDER BY c.id
            LIMIT 201
            """)
    List<ContentEntity> selectPublicReleaseRecords(
            @Param("siteId") Long siteId, @Param("menuId") Integer menuId);

    @Select("""
            SELECT c.id, c.class_id AS classid, c.title, c.status, c.create_time
            FROM t_contents c JOIN t_oneshot m ON m.id = c.class_id
            WHERE m.site_project_id = #{siteId} AND m.deleted = 0
              AND (c.site_project_id = #{siteId} OR c.site_project_id IS NULL)
              AND c.deleted = 0 AND c.title = #{title}
            ORDER BY CASE WHEN c.class_id = #{menuId} THEN 0 ELSE 1 END, c.id
            LIMIT 12
            """)
    List<ContentEntity> selectPairingTitleCandidates(@Param("siteId") Long siteId,
            @Param("menuId") Integer menuId, @Param("title") String title);

    List<ContentEntity> selectPublicTimelineRows(@Param("siteId") Long siteId, @Param("menuId") Integer menuId);

    record NativeContentGroup(String value, long count) {}

    List<NativeContentGroup> selectNativeContentGroups(@Param("siteId") Long siteId, @Param("menuId") Integer menuId,
            @Param("c") ContentSearchCriterion criterion);

    @Select(
            """
            SELECT content.*
            FROM t_contents content
            JOIN t_oneshot menu ON menu.id = content.class_id
            WHERE content.id = #{contentId}
              AND content.class_id = #{menuId}
              AND content.deleted = 0
              AND menu.deleted = 0
              AND menu.site_project_id = #{siteId}
              AND (content.site_project_id = #{siteId} OR content.site_project_id IS NULL)
            LIMIT 1 FOR UPDATE
            """)
    @org.apache.ibatis.annotations.Options(
            useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    ContentEntity lockActiveArticleInSiteMenu(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("contentId") Integer contentId);

    /** Resolve shared detail routes without loading article bodies or unpublished content. */
    Integer selectPublishedArticleClassId(
            @Param("siteId") Long siteId, @Param("contentId") Integer contentId);

    /** 恢复检查专用当前读；不隐藏软删除或错属记录，调用者先锁所属栏目。 */
    @Select(
            "SELECT id,site_project_id,class_id,status,deleted FROM t_contents WHERE id=#{id} FOR"
                    + " UPDATE")
    @org.apache.ibatis.annotations.Options(
            useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Map<String, Object> lockTextDraftRecoveryIdentity(@Param("id") Integer id);

    /**
     * AI应用审计专用：读取ContentEntity未表达但回滚审计需要保留的旧表列。
     *
     * @param id 内容ID
     * @return 额外审计列
     */
    Map<String, Object> selectAiContentAuditExtraColumns(@Param("id") Integer id);

    /**
     * 根据条件获取文章列表
     *
     * @param contentCond
     * @return
     */
    List<ContentEntity> getArticlesByCond(
            @Param("fieldNameList") List<String> fieldNameList,
            @Param("contentCond") ContentCond contentCond,
            @Param("criteria") List<ContentSearchCriterion> criteria);

    /**
     * 根据条件获取文章记录数
     *
     * @param contentCond
     * @return
     */
    int getArticlesCountByCond(
            @Param("contentCond") ContentCond contentCond,
            @Param("criteria") List<ContentSearchCriterion> criteria);

    /**
     * 获取最新10条新闻数据
     *
     * @return
     */
    List<ContentEntity> getTop10();

    /**
     * 按站点ID获取访问量 Top10 文章
     *
     * @param siteId 站点ID
     * @return
     */
    List<ContentEntity> getTop10BySiteId(@Param("siteId") Long siteId);

    /**
     * 获取访问量 Top10（合并单篇栏目与列表内容，统一按访问量倒序）
     *
     * @param siteId 站点ID，null 表示全站
     * @return
     */
    List<TopHitRow> getTop10Hits(@Param("siteId") Long siteId);

    /**
     * 获取文章总数量
     *
     * @return
     */
    Long getArticleCount();

    /**
     * 按站点ID获取文章总数量
     *
     * @param siteId 站点ID
     * @return
     */
    Long getArticleCountBySiteId(@Param("siteId") Long siteId);

    /**
     * 按站点ID获取待审核文章数量
     *
     * @param siteId 站点ID
     * @return
     */
    Long getPendingReviewCountBySiteId(@Param("siteId") Long siteId);

    /**
     * 按站点ID获取草稿文章数量
     *
     * @param siteId 站点ID
     * @return
     */
    Long getDraftCountBySiteId(@Param("siteId") Long siteId);

    /**
     * 获取最近的文章（只包含id和title）
     *
     * @return
     */
    List<ContentEntity> getRecentlyArticle();

    /**
     * 搜索文章-根据标题 或 内容匹配
     *
     * @param param
     * @return
     */
    List<ContentEntity> searchArticle(@Param("param") String param);

    /**
     * 更新推荐状态
     *
     * @param ids
     * @param optionType 1首页置顶,2首页热门,3频道置顶,4频道推荐 5取消首页置顶,6取消首页热门,7取消频道置顶,8取消频道推荐
     * @return
     */
    int updateElite(
            @Param("ids") List<Integer> ids,
            @Param("classid") Integer classid,
            @Param("optionType") String optionType);

    /**
     * 通过id集合与classid删除文章
     *
     * @param cids
     * @param classid
     */
    void deleteArticleByIds(@Param("cids") List<Integer> cids, @Param("classid") Integer classid);

    /**
     * 通过ID获取对象
     *
     * @param id
     * @return
     */
    ContentEntity getModelById(@Param("id") Integer id);

    /**
     * 通过ID修改排序号
     *
     * @param sortDto
     * @return
     */
    Boolean updateSort(SortDto sortDto);

    /**
     * 根据条件查询数据
     *
     * @param fieldNameList
     * @param beginNumber
     * @param limit
     * @return
     */
    List<ContentEntity> getContentByCond(
            @Param("fieldNameList") List<String> fieldNameList,
            @Param("beginNumber") Integer beginNumber,
            @Param("limit") Integer limit);

    /**
     * 获取列表类型SEO信息
     *
     * @param classid
     * @param id
     * @return
     */
    ContentEntity getSeoById(@Param("classid") Integer classid, @Param("id") Integer id);

    /**
     * 更新SEO信息
     *
     * @param modelManageSeoVo
     * @return
     */
    Boolean updateSEO(ModelManageSeoVo modelManageSeoVo);

    /**
     * 检查是否存在关联数据
     *
     * @param idDto
     * @return
     */
    Integer getChildCount(IdDto idDto);

    /**
     * 逻辑删除数据
     *
     * @param idDto
     * @return
     */
    Boolean updateDeleted(IdDto idDto);

    /**
     * AI导入回滚专用：仅当插入内容核心快照未变化时逻辑删除。
     *
     * @param expected 期望快照字段
     * @return 是否删除成功
     */
    Boolean deleteAiInsertedContentIfUnchanged(ContentEntity expected);

    /**
     * 通过classid获取所有文章内容
     *
     * @param classId
     * @return
     */
    List<ContentEntity> getArticleByClassId(Integer classId);

    /**
     * @param classIdList
     * @return
     */
    List<ContentEntity> getArticleByClassIdForSelect(
            @Param("classIdList") List<Integer> classIdList);

    /**
     * 改版历史内容抽样专用：读取完整内容字段，允许未发布内容作为候选。
     *
     * @param classIdList 栏目ID集合
     * @return 历史内容候选
     */
    List<ContentEntity> selectRevisionPreviewCandidates(
            @Param("classIdList") List<Integer> classIdList);

    /**
     * 获取留言总数
     *
     * @return
     */
    Long getMessageCount();

    Long getMessageCountBySiteId(@Param("siteId") Long siteId);

    /**
     * 根据条件查询需要导出的数据集合
     *
     * @param fieldNameList
     * @param outputDto
     * @return
     */
    List<ContentEntity> getArticlesByCondForOutput(
            @Param("fieldNameList") List<String> fieldNameList,
            @Param("outputDto") OutputDto outputDto);

    /**
     * 更新数据静态页发布状态
     *
     * @param classId
     * @param id
     */
    void updatePublished(@Param("classId") Integer classId, @Param("id") Integer id);

    /**
     * 批量检查是否存在关联数据
     *
     * @param ids
     * @return
     */
    Integer getBatchChildCount(@Param("ids") List<Integer> ids);

    /**
     * 根据栏目ID获取已发布内容列表（SEO管理专用）
     *
     * @param classId 栏目ID
     * @return 内容列表（最多100条）
     */
    @Select(
            """
            SELECT id, title, class_id
            FROM t_contents
            WHERE class_id = #{classId}
              AND deleted = 0
              AND status = 1
            ORDER BY create_time DESC
            LIMIT 100
            """)
    List<ContentEntity> selectActiveContentsByClassId(@Param("classId") Integer classId);

    /** 关联字段专用：不加载正文，不跨站点，公开读取额外限制已发布内容和启用栏目。 */
    List<ContentEntity> selectReferenceArticles(
            @Param("siteId") Long siteId,
            @Param("menuIds") List<Integer> menuIds,
            @Param("ids") List<Integer> ids,
            @Param("publishedOnly") boolean publishedOnly);

    /** Agent只读身份候选：显式分页、同站栏目和精简文字，不加载正文。 */
    List<ContentEntity> selectSiteModelCandidates(
            IPage<ContentEntity> page,
            @Param("siteId") Long siteId,
            @Param("menuIds") List<Integer> menuIds,
            @Param("titlePattern") String titlePattern);
}

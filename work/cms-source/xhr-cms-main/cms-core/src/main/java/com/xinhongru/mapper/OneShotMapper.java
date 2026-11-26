package com.xinhongru.mapper;

import com.xinhongru.dto.member.MenuDTO;
import com.xinhongru.entity.BusinessEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.oneshot.OneshotSwitchDto;
import com.xinhongru.requestDto.oneshot.OneshotTitleDto;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.responseVo.oneshot.SeoDto;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
@Mapper
public interface OneShotMapper {
    /** 仅切换本票模板指针；栏目锁及预期旧值共同避免覆盖人工变更。 */
    @org.apache.ibatis.annotations.Update(
            """
            <script>
            UPDATE t_oneshot
            <choose><when test="pageType == 0">SET list_page_template=#{replacement}</when>
            <otherwise>SET detail_page_template=#{replacement}</otherwise></choose>
            WHERE id=#{menuId} AND site_project_id=#{siteId} AND deleted=0 AND page_type=#{pageType}
            <choose><when test="pageType == 0">AND list_page_template &lt;=&gt; #{expected}</when>
            <otherwise>AND detail_page_template &lt;=&gt; #{expected}</otherwise></choose>
            </script>
            """)
    int updatePublicBindingTemplate(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("pageType") int pageType,
            @Param("expected") String expected,
            @Param("replacement") String replacement);

    /** 公开应用的路由和模板在同一条更新语句内切换。 */
    @org.apache.ibatis.annotations.Update(
            """
            <script>
            UPDATE t_oneshot
            <choose><when test="pageType == 0">
            SET list_page_template=#{replacement}, list_page_url=#{route}
            </when><otherwise>
            SET detail_page_template=#{replacement}, detail_page_url=#{route}
            </otherwise></choose>
            WHERE id=#{menuId} AND site_project_id=#{siteId} AND deleted=0 AND page_type=#{pageType}
            <choose><when test="pageType == 0">
            AND list_page_template &lt;=&gt; #{expected} AND list_page_url &lt;=&gt; #{expectedRoute}
            </when><otherwise>
            AND detail_page_template &lt;=&gt; #{expected} AND detail_page_url &lt;=&gt; #{expectedRoute}
            </otherwise></choose>
            </script>
            """)
    int switchPublicBindingRoute(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("pageType") int pageType,
            @Param("expected") String expected,
            @Param("replacement") String replacement,
            @Param("expectedRoute") String expectedRoute,
            @Param("route") String route);

    /** 列表栏目只切换list指针，固定page_type=1，与单页/详情CAS分离。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_oneshot SET list_page_template=#{replacement} WHERE id=#{menuId} AND"
                + " site_project_id=#{siteId} AND deleted=0 AND page_type=1 AND list_page_template"
                + " <=> #{expected}")
    int updatePublicListBindingTemplate(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("expected") String expected,
            @Param("replacement") String replacement);

    /** 受控公开绑定每次渲染读取完整当前栏目，不复用权限scope的有限projection。 */
    @Select(
            "SELECT * FROM t_oneshot WHERE id=#{menuId} AND site_project_id=#{siteId} AND"
                    + " deleted=0")
    @org.apache.ibatis.annotations.Options(
            useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    OneshotEntity selectPublicBindingMenu(
            @Param("siteId") Long siteId, @Param("menuId") Long menuId);

    @org.apache.ibatis.annotations.Select(
            "SELECT id FROM t_oneshot WHERE id=#{menuId} AND site_project_id=#{siteId} FOR UPDATE")
    Integer lockSeedMenu(
            @org.apache.ibatis.annotations.Param("siteId") Long siteId,
            @org.apache.ibatis.annotations.Param("menuId") Long menuId);

    /** 字段配置写入专用当前读：直接返回锁定实体，避免普通SELECT快照或一级缓存。 */
    @Select(
            "SELECT * FROM t_oneshot WHERE id=#{menuId} AND site_project_id=#{siteId} AND deleted=0"
                    + " FOR UPDATE")
    @org.apache.ibatis.annotations.Options(
            useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    OneshotEntity lockFieldConfiguration(
            @Param("siteId") Long siteId, @Param("menuId") Long menuId);

    /** 扩展槽位可能在旧记录中留有值，包括已经软删除的记录。 */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM t_contents WHERE class_id=#{menuId}"
                    + " AND extension_fields IS NOT NULL AND JSON_CONTAINS_PATH(extension_fields,"
                    + " 'one', CONCAT('$.', #{fieldName})))")
    boolean hasStoredExtensionField(
            @Param("menuId") Integer menuId, @Param("fieldName") String fieldName);

    /**
     * 添加单篇栏目
     *
     * @param oneshotEntity
     * @return
     */
    int save(OneshotEntity oneshotEntity);

    /**
     * 更新单篇栏目
     *
     * @param oneshotEntity
     * @return
     */
    int update(OneshotEntity oneshotEntity);

    /**
     * 记录单页同步来源：目标栏目 → 源站点ID + 源栏目ID（供后续增量同步判定是否已存在）
     *
     * @param id 目标栏目ID
     * @param sourceSiteId 源站点ID
     * @param sourceMenuId 源栏目ID
     * @return 影响行数
     */
    int updateSourceMenu(
            @Param("id") Integer id,
            @Param("sourceSiteId") Long sourceSiteId,
            @Param("sourceMenuId") Integer sourceMenuId);

    /**
     * 更新栏目审核状态（单页工作流：0草稿 1待审 2已审核 3已发布）
     *
     * @param id
     * @param status
     * @return
     */
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /**
     * 更新栏目上线地址：kind=list 更新 list_page_url，kind=detail 更新 detail_page_url
     *
     * @param id 栏目ID
     * @param kind list/detail
     * @param pageUrl 页面地址，传 null 表示清空
     */
    int updatePageUrl(
            @Param("id") Integer id, @Param("kind") String kind, @Param("pageUrl") String pageUrl);

    /**
     * 通过ID获取单篇栏目数据
     *
     * @param id
     * @return
     */
    OneshotEntity getModelById(@Param("id") int id);

    /**
     * 批量通过ID获取单篇栏目数据
     *
     * @param ids
     * @return
     */
    List<OneshotEntity> getModelByIds(@Param("ids") List<Integer> ids);

    @Select(
            """
            SELECT id, parent_id AS parentId, site_project_id AS siteProjectId, site_root_id AS siteRootId,
                   class_name AS className, deleted AS isDeleted
            FROM t_oneshot
            WHERE id = #{menuId}
              AND site_project_id = #{siteId}
              AND deleted = 0
            LIMIT 1
            """)
    OneshotEntity selectActiveMenuBySite(
            @Param("siteId") Long siteId, @Param("menuId") Integer menuId);

    /** 模型/路由消费者专用快照；不扩大权限检查使用的最小selectActiveMenuBySite投影。 */
    @Select(
            """
            SELECT id, parent_id AS parentId, site_project_id AS siteProjectId,
                   site_root_id AS siteRootId, class_name AS className, deleted AS isDeleted,
                   page_type AS pageType, status, has_front_channel AS hasFrontChannel,
                   has_manage_channel AS hasManageChannel, list_page_url AS listPageUrl, detail_page_url AS detailPageUrl,
                   menu_field AS menuField, model_field AS modelField,
                   model_list AS modelList, model_search AS modelSearch, biz_code AS bizCode
            FROM t_oneshot
            WHERE id = #{menuId}
              AND site_project_id = #{siteId}
              AND deleted = 0
            LIMIT 1
            """)
    OneshotEntity selectActiveMenuModelBySite(
            @Param("siteId") Long siteId, @Param("menuId") Integer menuId);

    @Select(
            """
            SELECT id, parent_id AS parentId, site_project_id AS siteProjectId,
                   site_root_id AS siteRootId, class_name AS className, page_type AS pageType, identifies, sort,
                   is_sub_menu AS isSubMenu,
                   list_page_url AS listPageUrl, detail_page_url AS detailPageUrl,
                   menu_field AS menuField, model_field AS modelField
            FROM t_oneshot
            WHERE site_project_id = #{siteId} AND deleted = 0
            ORDER BY parent_id, sort, id
            LIMIT 1001
            """)
    List<OneshotEntity> selectActiveSiteModelMenus(@Param("siteId") Long siteId);

    /**
     * 查询所有符合条件的栏目列表 用在栏目管理
     *
     * @return
     */
    List<OneshotEntity> getAllMenu();

    /** Includes soft-deleted rows so a human deletion cannot be mistaken for a rollback. */
    @Select(
            """
            SELECT COUNT(*) FROM t_oneshot
            WHERE site_project_id=#{siteId} AND (id=#{menuId} OR identifies=#{marker})
            """)
    int countNavigationIdentity(
            @Param("siteId") Long siteId,
            @Param("menuId") Integer menuId,
            @Param("marker") String marker);

    Boolean updateSwitch(OneshotSwitchDto oneshotSwitchDto);

    Boolean updateSort(SortDto sortDto);

    Boolean updateTitle(OneshotTitleDto oneshotTitleDto);

    Boolean updateSEO(SeoDto seoDto);

    /**
     * 修改栏目
     *
     * @param editOneshotDto
     * @return
     */
    Boolean updateEdit(EditOneshotDto editOneshotDto);

    int updateAiMenuApply(
            @Param("patch") EditOneshotDto patch, @Param("fields") Set<String> fields);

    /**
     * 新建栏目
     *
     * @param editOneshotDto
     * @return
     */
    int insertMenu(EditOneshotDto editOneshotDto);

    int insertSiteRootMenu(OneshotEntity oneshotEntity);

    /**
     * 新增内容中台推送生成的子类别栏目（带 platform_source_ref 来源标识）
     *
     * @param oneshotEntity 栏目（siteProjectId/parentId/className/sort/pageType/platformSourceRef）
     * @return 影响行数
     */
    int insertPlatformColumn(OneshotEntity oneshotEntity);

    /**
     * 更新内容中台推送生成的子类别栏目（名称/层级/排序/类型）
     *
     * @param oneshotEntity 栏目
     * @return 影响行数
     */
    int updatePlatformColumn(OneshotEntity oneshotEntity);

    /**
     * 按站点 + 内容中台来源标识查询栏目，用于推送时判定新增或更新
     *
     * @param siteId 目标站点ID
     * @param ref 来源标识 dataType:categoryId
     * @return 栏目，不存在返回 null
     */
    @Select(
            """
            SELECT id, parent_id AS parentId, site_project_id AS siteProjectId, site_root_id AS siteRootId,
                   class_name AS className, page_type AS pageType, sort, biz_code AS bizCode,
                   platform_source_ref AS platformSourceRef
            FROM t_oneshot
            WHERE site_project_id = #{siteId}
              AND platform_source_ref = #{ref}
              AND deleted = 0
            ORDER BY id
            LIMIT 1
            """)
    OneshotEntity selectBySiteAndPlatformRef(
            @Param("siteId") Long siteId, @Param("ref") String ref);

    /**
     * 将指定栏目的 bizcode 赋值为其自身 id（新增栏目后调用）
     *
     * <p>bizcode 的语义：新增栏目时默认取自身 id，作为跨站点稳定标识； 多站点场景下通过 (biz_code, site_project_id) 可定位到当前站点对应的栏目。
     * 因此该赋值必须在 insert 拿到自增 id 之后执行。
     *
     * @param id 新增栏目的自增主键
     * @return 影响行数
     */
    int assignBizcodeToOwnId(@Param("id") Integer id);

    int updateSiteScopeByRoot(
            @Param("siteProjectId") Long siteProjectId, @Param("siteRootId") Integer siteRootId);

    int clearSiteScopeByRoot(
            @Param("siteProjectId") Long siteProjectId, @Param("siteRootId") Integer siteRootId);

    List<OneshotEntity> selectBySiteRoot(@Param("siteRootId") Integer siteRootId);

    /**
     * 按站点ID查询栏目列表
     *
     * @param siteProjectId 站点ID
     * @return
     */
    List<OneshotEntity> selectBySiteProjectId(@Param("siteProjectId") Long siteProjectId);

    /**
     * 覆盖指定栏目的字段设置配置（menu_field/model_field/model_list/model_search）
     *
     * @param id 栏目ID
     * @param menuField 单页字段配置
     * @param modelField 列表字段配置
     * @param modelList 列表展示字段配置
     * @param modelSearch 列表搜索字段配置
     * @return 影响行数
     */
    int updateFieldConfig(
            @Param("id") Integer id,
            @Param("menuField") String menuField,
            @Param("modelField") String modelField,
            @Param("modelList") String modelList,
            @Param("modelSearch") String modelSearch);

    /**
     * 逻辑删除栏目
     *
     * @param idDto
     * @return
     */
    Boolean updateIsDeleted(IdDto idDto);

    /**
     * 获取子栏目总数
     *
     * @param idDto
     * @return
     */
    Integer getChildCount(IdDto idDto);

    @Select("SELECT id FROM t_oneshot WHERE parent_id=#{menuId} AND deleted=0 LIMIT 1 FOR UPDATE")
    Integer lockAnyChildForNavigationCorrection(@Param("menuId") Integer menuId);

    /**
     * 获取子栏目总数不包含级联子栏目
     *
     * @param idDto
     * @return
     */
    Integer getChildCountExcludeCascade(IdDto idDto);

    /**
     * 获取左侧栏目对象
     *
     * @return
     */
    List<OneshotEntity> getAllLeftMenu();

    /**
     * 获取二级栏目
     *
     * @param parentId
     * @return
     */
    List<OneshotEntity> getChildMenu(Integer parentId);

    /**
     * 获取所有的前台显示的栏目
     *
     * @return
     */
    List<OneshotEntity> getAllFontMenu();

    /**
     * 获妈所有允许前台用户发布文章的栏目
     *
     * @return
     */
    List<MenuDTO> getAllowMenu();

    List<OneshotEntity> getAllFrontMenu();

    /**
     * 获取全部列表栏目
     *
     * @return
     */
    List<OneshotEntity> getAllNewsMenu();

    /**
     * 获取业务分类列表
     *
     * @return
     */
    List<BusinessEntity> getBusinessEntityList();

    /**
     * 更新子站的所属业务分类Id和域名
     *
     * @param businessId
     * @param siteDomain
     * @param menuId
     */
    void setBusinessIdAndDomain(
            @Param("businessId") String businessId,
            @Param("siteDomain") String siteDomain,
            @Param("menuId") Integer menuId);

    @Select(
            """
            SELECT id, parent_id, site_project_id, site_root_id, class_name, class_en_name,
                   page_type, sort, list_page_url, list_page_template, detail_page_url, detail_page_template,
                   has_manage_channel, has_front_channel, is_deleted, create_time, biz_code, status
            FROM t_oneshot
            WHERE site_project_id = #{siteProjectId}
              AND deleted = 0
            ORDER BY parent_id, sort, id
            """)
    List<OneshotEntity> selectListBySiteProjectId(@Param("siteProjectId") Long siteProjectId);
}

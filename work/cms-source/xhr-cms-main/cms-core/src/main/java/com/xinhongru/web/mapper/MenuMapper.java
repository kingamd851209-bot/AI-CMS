package com.xinhongru.web.mapper;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.web.resonseVo.ChildCompanyDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 22:13
 */
@Mapper
public interface MenuMapper {
    /**
     * 根据ID获取单篇的数据
     *
     * @param id
     * @return
     */
    OneshotEntity getModelById(@Param("id") int id);

    /** 按 id 获取栏目（不限制 has_front_channel，用于读取隐藏的前台区块栏目）。 */
    OneshotEntity getModelByIdIgnoreChannel(@Param("id") int id);

    List<OneshotEntity> getOneshotEntitiesByBizcodes(
            @Param("bizcodes") List<String> bizcodes, @Param("siteProjectId") Long siteProjectId);

    List<OneshotEntity> getOneshotChildrenByParentIds(
            @Param("parentIds") List<Integer> parentIds,
            @Param("siteProjectId") Long siteProjectId);

    /**
     * 读取栏目的可循环直接子栏目，但不限制 has_front_channel
     *
     * <p>用于获取被标记为隐藏（has_front_channel=0）但仍需渲染的区块栏目，如产品页的 Solutions。
     *
     * @param parentId 父栏目 id
     * @return 匹配的子栏目列表，未匹配时返回空列表
     */
    List<OneshotEntity> getOneshotChildrenByParentIdIgnoreChannel(@Param("parentId") int parentId);

    /**
     * 根据 bizcode 与站点项目ID获取当前站点对应的栏目
     *
     * <p>bizcode 为栏目跨站点稳定标识（新增栏目时默认等于其 id）， 多站点场景下同一逻辑栏目在不同站点拥有不同 id 但相同 bizcode， 通过 siteProjectId
     * 过滤即可拿到当前站点的栏目实体。
     *
     * @param bizcode 栏目业务编码，不能为空
     * @param siteProjectId 站点项目ID，不能为空
     * @return 匹配的栏目实体，未找到时返回 null
     */
    OneshotEntity getOneshotEntityByBizcode(
            @Param("bizcode") String bizcode, @Param("siteProjectId") Long siteProjectId);

    /**
     * 根据一组 bizcode 与站点项目ID批量获取当前站点对应的栏目（仅返回 id 与 bizcode）
     *
     * <p>用于把逗号分隔的 bizcode 集合解析为当前站点的栏目 id 集合。
     *
     * @param bizcodes bizcode 集合，不能为空
     * @param siteProjectId 站点项目ID，不能为空
     * @return 匹配的栏目列表（仅含 id、bizcode），未匹配时返回空集合
     */
    List<OneshotEntity> getOneshotListByBizcodes(
            @Param("bizcodes") List<String> bizcodes, @Param("siteProjectId") Long siteProjectId);

    /**
     * 根据parentId获取栏目集合
     *
     * @param parentId
     * @return
     */
    List<OneshotEntity> getOneshotListByParentId(@Param("parentId") int parentId);

    /**
     * 根据栏目id集合获取栏目集合
     *
     * @param ids
     * @return
     */
    List<OneshotEntity> getOneshotListByIds(@Param("ids") List<String> ids);

    /**
     * 根据一组栏目 id 批量获取完整栏目实体（不附加 listPageUrl，按 deleted/is_system/has_front_channel 过滤，与 getModelById
     * 语义一致）
     *
     * <p>用于一次性取回多个栏目，避免逐列调用 {@link #getModelById(int)} 造成的 N+1 查询。
     *
     * @param ids 栏目 id 集合，不能为空
     * @return 匹配的栏目实体列表，未匹配时返回空列表
     */
    List<OneshotEntity> getOneshotListByIdsRaw(@Param("ids") List<Integer> ids);

    /**
     * 更新访问次数
     *
     * @param classId
     */
    void updateHits(@Param("classId") Integer classId);

    /**
     * 产品内容页获取随机推荐产品
     *
     * @return
     */
    List<OneshotEntity> getRandomProduct();

    /**
     * 根据业务分类Id获取业务公司集合
     *
     * @param businessId
     * @return
     */
    List<OneshotEntity> getCompanyListByBusinessId(@Param("businessId") Integer businessId);

    /**
     * 根据单位分类获取单位列表
     *
     * @param categoryId
     * @return
     */
    List<ChildCompanyDTO> getChildCompany(@Param("categoryId") Integer categoryId);

    /**
     * 根据唯一标识获取栏目ID集合
     *
     * @param identifies
     * @return
     */
    List<Integer> getClassIdListByIdentifies(String identifies);

    /**
     * 根据唯一标识与站点获取栏目ID集合
     *
     * @param identifies
     * @param siteProjectId
     * @return
     */
    List<Integer> getClassIdListByIdentifiesAndSite(
            @Param("identifies") String identifies, @Param("siteProjectId") Long siteProjectId);
}

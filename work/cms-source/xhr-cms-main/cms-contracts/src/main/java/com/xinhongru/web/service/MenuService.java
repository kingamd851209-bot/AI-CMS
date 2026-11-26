package com.xinhongru.web.service;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.web.resonseVo.ChildCompanyDTO;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 22:08
 */
public interface MenuService {
    /** 根据ID获取单篇的数据 */
    OneshotEntity getModelById(int id);

    /** 按 id 获取栏目（不限制 has_front_channel，用于读取隐藏的前台区块栏目）。 */
    OneshotEntity getModelByIdIgnoreChannel(int id);

    /** 批量读取完整栏目，过滤条件与单个 bizcode 查询一致，按 sort、id 排序并解析链接。 */
    List<OneshotEntity> getOneshotEntitiesByBizcodes(List<String> bizcodes, Long siteProjectId);

    /** 同站点批量读取多个父栏目的可循环子栏目，按 sort、id 排序并解析链接。 */
    List<OneshotEntity> getOneshotChildrenByParentIds(List<Integer> parentIds, Long siteProjectId);

    /**
     * 读取栏目的可循环直接子栏目，但不限制 has_front_channel
     *
     * <p>用于获取被标记为隐藏（has_front_channel=0）但仍需渲染的区块栏目。
     *
     * @param parentId 父栏目 id
     * @return 匹配的子栏目列表，未匹配时返回空集合
     */
    List<OneshotEntity> getOneshotChildrenIgnoreChannel(int parentId);

    /**
     * 根据 bizcode 与站点项目ID获取当前站点对应的栏目
     *
     * <p>bizcode 为栏目跨站点稳定标识（新增栏目时默认等于其 id）。 多站点场景下同一逻辑栏目在不同站点拥有不同 id 但相同 bizcode， 通过 siteProjectId
     * 限定即可取到当前站点的栏目实体。
     *
     * @param bizcode 栏目业务编码
     * @param siteProjectId 站点项目ID
     * @return 当前站点下匹配的栏目实体，未匹配时返回 null
     */
    OneshotEntity getOneshotEntityByBizcode(String bizcode, Long siteProjectId);

    /**
     * 根据一组 bizcode 与站点项目ID批量获取当前站点对应的栏目（仅含 id、bizcode）
     *
     * <p>用于把逗号分隔的 bizcode 集合解析为当前站点的栏目 id 集合。
     *
     * @param bizcodes bizcode 集合
     * @param siteProjectId 站点项目ID
     * @return 匹配的栏目列表，未匹配时返回空集合
     */
    List<OneshotEntity> getOneshotListByBizcodes(List<String> bizcodes, Long siteProjectId);

    /** 根据parentId获取栏目集合 */
    List<OneshotEntity> getOneshotListByParentId(int parentId);

    /** 根据parentId获取栏目集合的前N条 */
    List<OneshotEntity> getOneshotListByParentId(int parentId, int limit);

    /**
     * 根据parentId获取栏目集合
     *
     * @param parentId 父级栏目id
     * @param outClassId 需要排除的栏目id集合
     */
    List<OneshotEntity> getOneshotListByParentId(int parentId, String outClassId);

    /** 根据parentId获取第一个子栏目 */
    OneshotEntity getFirstModelByParentId(Integer parentId);

    /** 根据栏目id集合获取栏目集合 */
    List<OneshotEntity> getOneshotListByIds(String ids);

    /**
     * 根据一组栏目 id 批量获取完整栏目实体（不附加 listPageUrl，过滤语义与 getModelById 一致）
     *
     * @param ids 栏目 id 集合
     * @return 匹配的栏目实体列表，未匹配时返回空集合
     */
    List<OneshotEntity> getOneshotListByIdsRaw(List<Integer> ids);

    /** 更新访问次数 */
    void updateHits(Integer classId);

    /** 产品内容页获取随机推荐产品 */
    List<OneshotEntity> getRandomProduct();

    /** 根据父栏目和唯一标识读取栏目 */
    OneshotEntity getOneshotEntityByParentIdAndIdentifies(int parentId, String identifies);

    /** 根据父栏目获取父栏目下的直接子栏目唯一标识读取栏目ID集合 */
    List<Integer> getClassIdListByParentIdAndIdentifies(int parentId, String identifies);

    /** 根据父栏目和唯一标识读取栏目集合 */
    List<OneshotEntity> getOneshotListByParentIdAndIdentifies(int parentId, String... identifies);

    /** 获取面包屑导航 */
    String getBreadcrumb(int classId, int id);

    /** 专题根据父栏目ID和显示位置获取所有栏目 */
    List<OneshotEntity> getOneshotListByParentIdAndShowPosition(int parentId, String showPosition);

    /** 根据业务分类Id获取业务公司集合 */
    List<OneshotEntity> getCompanyListByBusinessId(Integer businessId);

    /** 根据单位分类获取单位列表 */
    List<ChildCompanyDTO> getChildCompany(Integer categoryId);

    /** 根据唯一标识获取栏目ID集合 */
    List<Integer> getClassIdListByIdentifies(String identifies);

    /** 根据唯一标识获取指定站点下的栏目ID集合 */
    List<Integer> getClassIdListByIdentifies(String identifies, Long siteProjectId);
}

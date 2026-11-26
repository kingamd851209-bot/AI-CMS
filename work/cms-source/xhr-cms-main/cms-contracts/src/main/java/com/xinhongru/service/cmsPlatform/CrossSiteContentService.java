package com.xinhongru.service.cmsPlatform;

import java.util.List;
import java.util.Map;

/** 跨站点内容通用服务（产品中心/新闻中心共用，不关联站点） */
public interface CrossSiteContentService<T> {
    /** 按分类分页查询内容，filters 为按 modelSearch 配置的动态字段过滤条件（白名单 like） */
    List<T> listByClass(
            Integer classId,
            int page,
            int size,
            Integer status,
            String keyword,
            Map<String, Object> filters);

    /** 按分类统计内容数量 */
    long countByClass(Integer classId, Integer status, String keyword, Map<String, Object> filters);

    /** 按 id 查询 */
    T getById(Integer id);

    /** 新增内容 */
    T create(T entity);

    /** 更新内容（含动态字段池） */
    boolean update(T entity);

    /** 逻辑删除内容 */
    boolean delete(Integer id);

    /**
     * 批量操作：publish 发布 / draft 存草稿 / audit 提交审核 / approve 审核通过 / top 置顶 / recommend 推荐 / delete 删除
     * status 供审批时指定目标状态；note 为审批备注，写 audit_note 列
     */
    int batchOption(Integer classId, List<Integer> ids, String option, Integer status, String note);
}

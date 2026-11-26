package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.PlatformContentEntity;

import java.util.List;
import java.util.Map;

/** 平台内容服务（产品/新闻/案例/解决方案 共表 t_platform_contents）：按 dataType 隔离各类别内容。 */
public interface PlatformContentService {
    /** 按分类分页查询内容，filters 为按 modelSearch 配置的动态字段过滤条件（白名单 like） */
    List<PlatformContentEntity> listByClass(
            PlatformDataType dataType,
            Integer classId,
            int page,
            int size,
            Integer status,
            String keyword,
            Map<String, Object> filters);

    /** 按分类统计内容数量 */
    long countByClass(
            PlatformDataType dataType,
            Integer classId,
            Integer status,
            String keyword,
            Map<String, Object> filters);

    /** 按 id 查询（限本类别） */
    PlatformContentEntity getById(PlatformDataType dataType, Integer id);

    /** 新增内容 */
    PlatformContentEntity create(PlatformDataType dataType, PlatformContentEntity entity);

    /** 更新内容（含动态字段池） */
    boolean update(PlatformDataType dataType, PlatformContentEntity entity);

    /** 逻辑删除内容 */
    boolean delete(PlatformDataType dataType, Integer id);

    /**
     * 批量操作：publish 发布 / draft 存草稿 / audit 提交审核 / approve 审核通过 / top 置顶 / recommend 推荐 / delete 删除
     */
    int batchOption(
            PlatformDataType dataType,
            Integer classId,
            List<Integer> ids,
            String option,
            Integer status,
            String note);
}

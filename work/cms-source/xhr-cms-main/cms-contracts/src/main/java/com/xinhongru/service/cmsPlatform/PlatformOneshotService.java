package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.PlatformOneshotEntity;

import java.util.List;

/** 平台分类/元数据配置服务（产品/新闻/案例/解决方案 共表 t_platform_oneshot）：按 dataType 隔离各类别配置。 */
public interface PlatformOneshotService {
    /** 懒加载子树（顶级 parentId=0） */
    List<PlatformOneshotEntity> tree(PlatformDataType dataType, long parentId);

    /** 全部扁平列表 */
    List<PlatformOneshotEntity> all(PlatformDataType dataType);

    /** 按 id 查询（限本类别） */
    PlatformOneshotEntity getById(PlatformDataType dataType, Integer id);

    /** 新增分类（默认列表类型） */
    PlatformOneshotEntity create(PlatformDataType dataType, PlatformOneshotEntity entity);

    /** 更新分类（含元数据 model_field/model_list/model_search） */
    boolean update(PlatformDataType dataType, PlatformOneshotEntity entity);

    /** 删除分类及其所有子孙分类（逻辑删除 deleted/is_deleted） */
    int deleteCascade(PlatformDataType dataType, Integer id);

    /** 修改排序 */
    boolean updateSort(PlatformDataType dataType, Integer id, int sort);
}

package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.FormOneshotEntity;

import java.util.List;

/** 表单配置服务（按站点过滤，t_from_oneshot.site_project_id = 站点ID） */
public interface FormConfigService {
    /** 懒加载子树（顶级 parentId=0） */
    List<FormOneshotEntity> tree(long parentId, Long siteId);

    /** 站点下全部扁平列表 */
    List<FormOneshotEntity> all(Long siteId);

    /** 按 id 查询 */
    FormOneshotEntity getById(Integer id);

    /** 新增表单分类（写入 site_project_id） */
    FormOneshotEntity create(Long siteId, FormOneshotEntity entity);

    /** 更新分类（含元数据 model_field/model_list/model_search） */
    boolean update(FormOneshotEntity entity);

    /** 删除分类及其所有子孙分类（逻辑删除 deleted/is_deleted） */
    int deleteCascade(Integer id);

    /** 修改排序 */
    boolean updateSort(Integer id, int sort);
}

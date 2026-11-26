package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.UserEntity;

import java.util.List;

/** 内容中台 审批：更新内容状态并把审核信息写入与内容管理共用的 t_check_record */
public interface PlatformAuditService {
    /**
     * @param configType product/news
     * @param categoryId 内容中台分类ID（写入 submit_menu_id）
     * @param ids 选中内容ID
     * @param status 审核后的目标状态 0-3
     * @param note 审核备注
     * @return 处理条数
     */
    int audit(
            String configType,
            Integer categoryId,
            List<Integer> ids,
            Integer status,
            String note,
            UserEntity user);
}

package com.xinhongru.i18n.task.view;

import java.time.LocalDateTime;

/** 多语言站点任务明细视图：在明细表基础上补充栏目编码(bizCode)、上级栏目信息 与拆开的列表/详情模版路径，供前端树形展示与弹窗查看。 */
public record I18nSiteTaskDetailView(
        Long id,
        Long taskId,
        Integer sourceMenuId,
        String sourceBizCode,
        String sourceMenuName,
        Integer sourceMenuType,
        Integer sourceParentId,
        String sourceParentName,
        String sourceModelJson,
        String sourceListTemplate,
        String sourceDetailTemplate,
        Integer sourceContentCount,
        Integer targetMenuId,
        String targetBizCode,
        String targetMenuName,
        Integer targetMenuType,
        Integer targetParentId,
        String targetParentName,
        String targetModelJson,
        String targetListTemplate,
        String targetDetailTemplate,
        Integer targetContentCount,
        Boolean modelSynced,
        Boolean templateSynced,
        Boolean contentSynced,
        String syncNote,
        LocalDateTime syncTime) {}

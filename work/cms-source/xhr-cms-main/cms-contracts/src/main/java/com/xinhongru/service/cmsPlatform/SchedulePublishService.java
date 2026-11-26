package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.SchedulePublishEntity;
import com.xinhongru.entity.SchedulePublishHistoryEntity;

import java.util.List;

/** 定时发布配置服务（按站点过滤，本轮为管理页面，不含调度执行器） */
public interface SchedulePublishService {
    /** 站点下定时发布配置列表 */
    List<SchedulePublishEntity> list(Long siteId, String keyword);

    /** 按 id 查询 */
    SchedulePublishEntity getById(Long siteId, Long id);

    /** 新增配置（写 site_id，默认生效） */
    SchedulePublishEntity create(Long siteId, SchedulePublishEntity entity);

    /** 更新配置 */
    boolean update(Long siteId, SchedulePublishEntity entity);

    /** 逻辑删除 */
    boolean delete(Long siteId, Long id);

    /** 切换生效状态 */
    boolean toggleEnabled(Long siteId, Long id, Integer enabled);

    /** 某条配置的发布历史 */
    List<SchedulePublishHistoryEntity> history(Long siteId, Long scheduleId);

    /** 校验 cron 表达式是否合法 */
    boolean validateCron(String cron);
}

package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.PlatformPushRecordEntity;
import com.xinhongru.entity.UserEntity;

import java.util.List;

/** 一键推送：按推送配置的字段映射，把内容中台选中内容写入目标站点栏目 */
public interface PlatformPushService {
    /**
     * @param configType product/news/form/solution
     * @param pushConfigId 推送配置ID
     * @param contentIds 选中的内容中台内容ID
     * @param user 推送人（用于留痕）
     * @param request 当前登录操作人的来源操作及目标站点、栏目授权上下文
     * @return 成功推送条数
     */
    int push(
            String configType,
            Long pushConfigId,
            List<Integer> contentIds,
            UserEntity user,
            jakarta.servlet.http.HttpServletRequest request);

    /**
     * 查询某条中台内容的推送记录
     *
     * @param configType product/news/form/solution
     * @param sourceContentId 中台数据ID
     */
    List<PlatformPushRecordEntity> listRecords(
            String configType,
            Integer sourceContentId,
            jakarta.servlet.http.HttpServletRequest request);
}

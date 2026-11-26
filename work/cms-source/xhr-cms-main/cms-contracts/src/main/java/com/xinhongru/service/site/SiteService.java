package com.xinhongru.service.site;

import com.xinhongru.dto.StatisticsDto;
import com.xinhongru.dto.TopHitRow;
import com.xinhongru.entity.ContentEntity;

import java.util.List;

/** 站点服务 Created at 2021/4/30. */
public interface SiteService {
    /**
     * 获取最新的文章
     *
     * @param limit
     * @return
     */
    List<ContentEntity> getNewArticles(int limit);

    /**
     * 获取最新10条新闻数据
     *
     * @return
     */
    List<ContentEntity> getTop10();

    /**
     * 按站点ID获取访问量 Top10 文章
     *
     * @param siteId 站点ID
     * @return
     */
    List<ContentEntity> getTop10BySiteId(Long siteId);

    /**
     * 获取访问量 Top10（合并单篇栏目与列表内容）
     *
     * @param siteId 站点ID，null 表示全站
     * @return
     */
    List<TopHitRow> getTop10Hits(Long siteId);

    /**
     * 获取 后台统计数据
     *
     * @return
     */
    StatisticsDto getStatistics();

    /**
     * 按站点ID获取统计数据
     *
     * @param siteId 站点ID
     * @return
     */
    StatisticsDto getStatisticsBySiteId(Long siteId);
}

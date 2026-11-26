package com.xinhongru.service.site.impl;

import com.xinhongru.dto.StatisticsDto;
import com.xinhongru.dto.TopHitRow;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.persistence.Pages;
import com.xinhongru.service.site.SiteService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站点服务 Created at 2021/4/30. */
@Service
public class SiteServiceImpl implements SiteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteServiceImpl.class);

    @Autowired private ContentMapper contentDao;

    @Override
    @Cacheable(value = "siteCache", key = "'newArticles_' + #p0")
    public List<ContentEntity> getNewArticles(int limit) {
        LOGGER.debug("Enter recentArticles method:limit={}", limit);
        if (limit < 0 || limit > 10) {
            limit = 10;
        }
        return Pages.<ContentEntity>query(1, limit, page -> contentDao.getRecentlyArticle(page))
                .getList();
    }

    @Override
    public List<ContentEntity> getTop10() {
        return contentDao.getTop10();
    }

    @Override
    public List<ContentEntity> getTop10BySiteId(Long siteId) {
        if (siteId == null) {
            return contentDao.getTop10();
        }
        return contentDao.getTop10BySiteId(siteId);
    }

    @Override
    public List<TopHitRow> getTop10Hits(Long siteId) {
        return contentDao.getTop10Hits(siteId);
    }

    @Override
    //    @Cacheable(value = "siteCache", key = "'statistics_'")
    public StatisticsDto getStatistics() {
        LOGGER.debug("Enter recentStatistics method");
        // 文章总数
        Long artices = contentDao.getArticleCount();

        // 消息总数
        Long atts = contentDao.getMessageCount();

        StatisticsDto rs = new StatisticsDto();
        rs.setArticles(artices);
        rs.setAttachs(atts);

        LOGGER.debug("Exit recentStatistics method");
        return rs;
    }

    @Override
    public StatisticsDto getStatisticsBySiteId(Long siteId) {
        LOGGER.debug("Enter getStatisticsBySiteId method, siteId={}", siteId);
        StatisticsDto rs = new StatisticsDto();

        if (siteId == null) {
            // 如果站点ID为空，返回全站统计
            rs.setArticles(contentDao.getArticleCount());
            rs.setAttachs(contentDao.getMessageCount());
        } else {
            // 按站点统计
            rs.setArticles(contentDao.getArticleCountBySiteId(siteId));
            rs.setPendingReviewCount(contentDao.getPendingReviewCountBySiteId(siteId));
            rs.setDraftCount(contentDao.getDraftCountBySiteId(siteId));
            rs.setAttachs(contentDao.getMessageCountBySiteId(siteId));
        }

        LOGGER.debug("Exit getStatisticsBySiteId method");
        return rs;
    }
}

package com.xinhongru.dto;

import java.io.Serializable;

/** 后台统计对象 Created at 2021/4/30. */
public class StatisticsDto implements Serializable {
    /** 文章数 */
    private Long articles;

    /** 评论数 */
    private Long comments;

    /** 连接数 */

    /** 附件数 */
    private Long attachs;

    /** 待审核数 */
    private Long pendingReviewCount;

    /** 草稿数 */
    private Long draftCount;

    public Long getArticles() {
        return articles;
    }

    public void setArticles(Long articles) {
        this.articles = articles;
    }

    public Long getComments() {
        return comments;
    }

    public void setComments(Long comments) {
        this.comments = comments;
    }

    public Long getAttachs() {
        return attachs;
    }

    public void setAttachs(Long attachs) {
        this.attachs = attachs;
    }

    public Long getPendingReviewCount() {
        return pendingReviewCount;
    }

    public void setPendingReviewCount(Long pendingReviewCount) {
        this.pendingReviewCount = pendingReviewCount;
    }

    public Long getDraftCount() {
        return draftCount;
    }

    public void setDraftCount(Long draftCount) {
        this.draftCount = draftCount;
    }
}

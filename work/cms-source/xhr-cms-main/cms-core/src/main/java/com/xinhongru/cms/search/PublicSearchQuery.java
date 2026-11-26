package com.xinhongru.cms.search;

/** 站点参数来自服务器上下文，不能由公开请求扩大站点范围。 */
public record PublicSearchQuery(
        long siteId, String keyword, int page, int size, Integer year, Integer month) {
    public static PublicSearchQuery of(
            Long currentSiteId, Long requestedSiteId, String keyword, int page, int size) {
        if (currentSiteId == null || currentSiteId <= 0)
            throw new IllegalArgumentException("搜索缺少当前服务器站点");
        if (requestedSiteId != null && !requestedSiteId.equals(currentSiteId))
            throw new IllegalArgumentException("搜索站点与当前服务器站点不一致");
        String value = keyword == null ? "" : keyword.trim();
        if (value.isEmpty()
                || value.length() > 100
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("搜索词须为1至100字的普通文本");
        if (page < 1 || page > 100000 || size < 1 || size > 50)
            throw new IllegalArgumentException("搜索页码或页大小无效（每页最多50条）");
        return new PublicSearchQuery(currentSiteId, value, page, size, null, null);
    }

    /** 保留旧Pager空词枚举，但仍强制当前服务器站点和页界限。 */
    public static PublicSearchQuery forLegacy(
            Long currentSiteId, Long requestedSiteId, String keyword, int page, int size) {
        boolean blank = keyword == null || keyword.trim().isEmpty();
        PublicSearchQuery checked =
                of(currentSiteId, requestedSiteId, blank ? "_" : keyword, page, size);
        return blank
                ? new PublicSearchQuery(
                        checked.siteId(), "", checked.page(), checked.size(), null, null)
                : checked;
    }

    public PublicSearchQuery withDates(Integer year, Integer month) {
        if (year != null && (year < 1 || year > 9999)) throw new IllegalArgumentException("年份无效");
        if (month != null && (month < 1 || month > 12)) throw new IllegalArgumentException("月份无效");
        return new PublicSearchQuery(siteId, keyword, page, size, year, month);
    }

    public long offset() {
        return ((long) page - 1) * size;
    }

    public String likePattern() {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

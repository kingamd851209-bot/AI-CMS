package com.xinhongru.cms.template;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.persistence.Pages;

import org.springframework.stereotype.Service;

import java.util.*;

/** 给建模Agent提供真实记录候选，不推断身份、不保存关系。 */
@Service
public class SiteModelContentEvidence {
    public record Candidate(
            Integer id, Integer menuId, String title, String summary, Integer status) {}

    public record Result(
            List<Candidate> rows, int offset, int nextOffset, long total, boolean hasMore) {}

    private final ContentMapper contents;
    private final OneShotMapper menus;

    public SiteModelContentEvidence(ContentMapper contents, OneShotMapper menus) {
        this.contents = contents;
        this.menus = menus;
    }

    public Result read(Long siteId, List<Integer> menuIds, String keyword, int offset, int limit) {
        if (siteId == null
                || siteId <= 0
                || menuIds == null
                || menuIds.isEmpty()
                || menuIds.size() > 20
                || new HashSet<>(menuIds).size() != menuIds.size()
                || offset < 0
                || offset > 10000
                || limit < 1
                || limit > 20
                || offset % limit != 0)
            throw new IllegalArgumentException("候选读取需明确本站栏目，limit为1至20，offset为limit的整数倍且不超过10000");
        String term = keyword == null ? "" : keyword.strip();
        if (term.length() > 120 || term.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("候选标题查询过长或含控制字符");
        for (var id : menuIds) {
            if (id == null || id <= 0) throw new IllegalArgumentException("候选栏目无效");
            var menu = menus.selectActiveMenuModelBySite(siteId, id);
            if (menu == null
                    || !Objects.equals(menu.getSiteProjectId(), siteId)
                    || !Integer.valueOf(1).equals(menu.getPageType()))
                throw new IllegalArgumentException("候选来源必须是当前站点有效的列表栏目");
        }
        String pattern =
                term.isEmpty()
                        ? null
                        : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var page =
                Pages.<ContentEntity>query(
                        offset / limit + 1,
                        limit,
                        p -> contents.selectSiteModelCandidates(p, siteId, menuIds, pattern));
        if (page.list().size() > limit) throw new IllegalStateException("候选分页未按上限执行");
        var rows = new ArrayList<Candidate>();
        var ids = new HashSet<Integer>();
        for (var row : page.list()) {
            if (row.getId() == null
                    || !ids.add(row.getId())
                    || !menuIds.contains(row.getClassid())
                    || row.getSiteProjectId() != null
                            && !Objects.equals(row.getSiteProjectId(), siteId))
                throw new IllegalStateException("候选记录归属已变化");
            rows.add(
                    new Candidate(
                            row.getId(),
                            row.getClassid(),
                            excerpt(row.getTitle(), 200),
                            excerpt(row.getNote1(), 200),
                            row.getStatus()));
        }
        int next = offset + rows.size();
        return new Result(
                List.copyOf(rows),
                offset,
                next,
                page.total(),
                next < page.total() && next <= 10000);
    }

    private static String excerpt(String value, int size) {
        if (value == null) return "";
        return value.length() <= size ? value : value.substring(0, size);
    }
}

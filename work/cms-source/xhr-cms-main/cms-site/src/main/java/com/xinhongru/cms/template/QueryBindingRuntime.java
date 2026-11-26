package com.xinhongru.cms.template;

import com.xinhongru.dto.vo.list.Pager;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.utils.CommonUtils;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 唯一受信列表上下文上的固定查询适配；保留原Pager/SQL/URL流程，不负责公开行投影或发布。 */
@Service
public class QueryBindingRuntime {
    private final TemplateQueryBindingService bindings;
    private final CommonUtils common;
    private final OneShotMapper menus;

    public QueryBindingRuntime(
            TemplateQueryBindingService bindings, CommonUtils common, OneShotMapper menus) {
        this.bindings = bindings;
        this.common = common;
        this.menus = menus;
    }

    public record PageLink(int number, String url, boolean current) {}

    public record Result(
            TemplateQueryBindingService.Snapshot snapshot,
            Pager<ContentEntity> page,
            Map<String, Object> form,
            List<PageLink> numbers) {}

    public Result query(
            Long siteId, String code, String version, String queryId, IdEntity context) {
        TemplateQueryBindingService.Resolved resolved;
        try {
            resolved = bindings.resolveQuery(siteId, code, version, queryId, context);
        } catch (TemplateQueryBindingService.QueryInputException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询条件无效");
        }
        var snapshot = resolved.snapshot();
        if (snapshot.presentation() == null
                || snapshot.presentation().request().pagination() == null && !snapshot.presentation().request().fixedList())
            throw new IllegalArgumentException("此计划仅含控件接线，尚非完整LIST应用");
        boolean fixedList = snapshot.presentation().request().fixedList();
        var menu = menus.selectActiveMenuModelBySite(siteId, snapshot.menuId().intValue());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), siteId)
                || !Objects.equals(menu.getId().longValue(), snapshot.menuId())
                || !Boolean.TRUE.equals(menu.getHasFrontChannel())
                || !Objects.equals(menu.getPageType(), 1)
                || !Objects.equals(menu.getBizCode(), snapshot.bizCode()))
            throw new IllegalArgumentException("实际公开列表栏目或业务编码已变化");
        assertBizTarget(snapshot);
        int size = fixedList ? TemplateQueryBindingService.UNPAGED_LIST_LIMIT : snapshot.pageSize() == null ? 10 : snapshot.pageSize();
        if (size < 1 || size > 100) throw new IllegalArgumentException("计划分页容量无效");
        context.setPageSize(size);
        if (fixedList) context.setPageIndex(1);
        Pager<ContentEntity> page =
                common.getContentPagerByClassBizcode(
                        snapshot.bizCode(),
                        siteId,
                        context,
                        resolved.filters(),
                        snapshot.allowedFilterKeys(),
                        snapshot.allowedSortKeys());
        assertBizTarget(snapshot);
        if (page == null
                || page.getRows() == null
                || page.getPage() == null
                || page.getPageCount() == null
                || page.getTotal() == null) throw new IllegalArgumentException("真实查询结果缺少分页信息");
        for (var row : page.getRows())
            if (row == null
                    || !Objects.equals(row.getStatus(), 1)
                    || row.getClassid() != null
                            && !Objects.equals(row.getClassid().longValue(), snapshot.menuId()))
                throw new IllegalArgumentException("查询结果归属或发布状态不一致");
        if (fixedList && page.getTotal() > size)
            throw new IllegalArgumentException("无分页列表已超过 100 条已发布记录，请接通分页后再发布");
        int last = Math.max(1, page.getPageCount());
        if (page.getPage() > last)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码超出当前结果范围");
        var values = new LinkedHashMap<String, String>();
        resolved.filters().forEach((key, value) -> values.put(key, (String) value));
        var numbers = new ArrayList<PageLink>();
        int start = Math.max(1, page.getPage() - 2), end = Math.min(last, page.getPage() + 2);
        for (int index = start; index <= end; index++)
            numbers.add(
                    new PageLink(
                            index,
                            common.structuredPageUrl(menu, index, values),
                            index == page.getPage()));
        return new Result(snapshot, page, resolved.form(), List.copyOf(numbers));
    }

    private void assertBizTarget(TemplateQueryBindingService.Snapshot snapshot) {
        var resolved = common.getOneshotEntityByBizcode(snapshot.bizCode(), snapshot.siteId());
        if (resolved == null
                || !Objects.equals(resolved.getSiteProjectId(), snapshot.siteId())
                || !Objects.equals(resolved.getId().longValue(), snapshot.menuId()))
            throw new IllegalArgumentException("业务编码未定位到已确认的栏目");
    }
}

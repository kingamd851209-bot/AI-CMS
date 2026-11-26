package com.xinhongru.common.page;

import java.io.Serializable;
import java.util.List;

/** Stable pagination contract shared by services and HTTP adapters. */
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize)
        implements Serializable {
    public PageResult {
        list = List.copyOf(list);
        if (total < 0 || pageNum < 1 || pageSize < 1)
            throw new IllegalArgumentException("Invalid page metadata");
    }

    public List<T> getList() {
        return list;
    }

    public long getTotal() {
        return total;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getPages() {
        return (total + pageSize - 1) / pageSize;
    }
}

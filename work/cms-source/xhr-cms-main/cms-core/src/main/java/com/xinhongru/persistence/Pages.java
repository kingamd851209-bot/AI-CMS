package com.xinhongru.persistence;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinhongru.common.page.PageResult;

import java.util.List;
import java.util.function.Function;

/** Explicit MyBatis-Plus pagination; never attaches paging state to a thread. */
public final class Pages {
    private Pages() {}

    public static <T> PageResult<T> query(int number, int size, Function<Page<T>, List<T>> query) {
        Page<T> page = new Page<>(Math.max(1, number), Math.max(1, Math.min(1000, size)));
        page.setOptimizeJoinOfCountSql(false);
        List<T> rows = query.apply(page);
        return new PageResult<>(
                rows,
                page.getTotal(),
                Math.toIntExact(page.getCurrent()),
                Math.toIntExact(page.getSize()));
    }
}

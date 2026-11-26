package com.xinhongru.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.EqualsAndHashCode;

/** Shared long auto-increment primary key; preserves existing database types. */
@EqualsAndHashCode(callSuper = false)
public abstract class LongEntity<SELF extends LongEntity<SELF>> extends BaseEntity<Long> {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    protected Long id;

    @Override
    public Long getId() {
        return id;
    }

    @SuppressWarnings("unchecked")
    public SELF setId(Long id) {
        this.id = id;
        return (SELF) this;
    }
}

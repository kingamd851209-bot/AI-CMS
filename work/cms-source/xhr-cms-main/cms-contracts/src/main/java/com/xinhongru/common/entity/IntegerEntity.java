package com.xinhongru.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.EqualsAndHashCode;

/** Shared integer auto-increment primary key; preserves existing database types. */
@EqualsAndHashCode(callSuper = false)
public abstract class IntegerEntity<SELF extends IntegerEntity<SELF>> extends BaseEntity<Integer> {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    protected Integer id;

    @Override
    public Integer getId() {
        return id;
    }

    @SuppressWarnings("unchecked")
    public SELF setId(Integer id) {
        this.id = id;
        return (SELF) this;
    }
}

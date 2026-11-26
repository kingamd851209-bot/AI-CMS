package com.xinhongru.common.entity;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

/** Common identity contract. Audit and deletion columns belong only to tables that contain them. */
public abstract class BaseEntity<ID extends Serializable> implements Serializable {
    private static final long serialVersionUID = 1L;

    public abstract ID getId();

    @JSONField(serialize = false, deserialize = false)
    @JsonIgnore
    public final boolean isNew() {
        return getId() == null;
    }
}

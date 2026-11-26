package com.xinhongru.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinhongru.common.entity.BaseEntity;

/** Common MyBatis-Plus mapper contract for entities with a typed primary key. */
public interface CmsMapper<T extends BaseEntity<?>> extends BaseMapper<T> {}

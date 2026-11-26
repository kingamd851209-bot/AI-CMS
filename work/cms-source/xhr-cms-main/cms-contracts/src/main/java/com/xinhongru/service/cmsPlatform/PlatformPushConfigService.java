package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.PlatformPushConfigEntity;

import java.util.List;

/** 内容中台 推送配置服务（产品/新闻共用，按 configType 区分） */
public interface PlatformPushConfigService {
    List<PlatformPushConfigEntity> list(String configType, Integer categoryId);

    PlatformPushConfigEntity getById(Long id);

    PlatformPushConfigEntity create(PlatformPushConfigEntity entity);

    boolean update(PlatformPushConfigEntity entity);

    boolean delete(Long id);
}

package com.xinhongru.service.site.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.entity.SiteHeadlessAuthEntity;
import com.xinhongru.mapper.SiteHeadlessAuthMapper;
import com.xinhongru.service.site.SiteHeadlessAuthService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Headless 站点接入认证配置实现。 */
@Slf4j
@Service
public class SiteHeadlessAuthServiceImpl implements SiteHeadlessAuthService {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int APP_KEY_LENGTH = 32;
    private static final int APP_SECRET_LENGTH = 64;

    private final SiteHeadlessAuthMapper mapper;

    public SiteHeadlessAuthServiceImpl(SiteHeadlessAuthMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuthView get(Long siteId) {
        return toView(siteId, find(siteId));
    }

    @Override
    public AuthView rotate(Long siteId) {
        requireSiteId(siteId);
        LocalDateTime now = LocalDateTime.now();
        SiteHeadlessAuthEntity existing = find(siteId);
        if (existing == null) {
            SiteHeadlessAuthEntity entity =
                    new SiteHeadlessAuthEntity()
                            .setSiteId(siteId)
                            .setAppKey(randomToken(APP_KEY_LENGTH))
                            .setAppSecret(randomToken(APP_SECRET_LENGTH))
                            .setEnabled(1)
                            .setRotateTime(now)
                            .setCreateTime(now);
            mapper.insert(entity);
            return toView(siteId, entity);
        }
        existing.setAppKey(randomToken(APP_KEY_LENGTH))
                .setAppSecret(randomToken(APP_SECRET_LENGTH))
                .setRotateTime(now)
                .setUpdateTime(now);
        mapper.updateById(existing);
        return toView(siteId, existing);
    }

    @Override
    public AuthView update(Long siteId, Boolean enabled, String remark) {
        requireSiteId(siteId);
        SiteHeadlessAuthEntity existing = find(siteId);
        if (existing == null) {
            throw new IllegalStateException("该站点尚未生成 Headless 接入认证");
        }
        if (enabled != null) {
            existing.setEnabled(enabled ? 1 : 0);
        }
        if (remark != null) {
            existing.setRemark(remark);
        }
        existing.setUpdateTime(LocalDateTime.now());
        mapper.updateById(existing);
        return toView(siteId, existing);
    }

    @Override
    public AuthView ensure(Long siteId) {
        requireSiteId(siteId);
        SiteHeadlessAuthEntity existing = find(siteId);
        if (existing != null) {
            return toView(siteId, existing);
        }
        return rotate(siteId);
    }

    private SiteHeadlessAuthEntity find(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return mapper.selectOne(
                new QueryWrapper<SiteHeadlessAuthEntity>().eq("site_id", siteId).last("LIMIT 1"));
    }

    private void requireSiteId(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("siteId 不能为空");
        }
    }

    private AuthView toView(Long siteId, SiteHeadlessAuthEntity entity) {
        if (entity == null) {
            return new AuthView(siteId, false, null, null, false, null, null);
        }
        return new AuthView(
                siteId,
                true,
                entity.getAppKey(),
                entity.getAppSecret(),
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getRemark(),
                entity.getRotateTime() == null ? null : entity.getRotateTime().format(DATE_TIME));
    }

    private static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}

package com.xinhongru.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 登录失败计数使用的进程内缓存，过期时间按秒计算。 */
public final class MapCache {
    private static final MapCache INSTANCE = new MapCache();

    private final Map<String, CacheObject> cachePool = new ConcurrentHashMap<>(1024);

    private MapCache() {}

    public static MapCache single() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public <T> T hget(String key, String field) {
        CacheObject cached = cachePool.get(key + ":" + field);
        if (cached == null) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        return cached.expired() <= 0 || cached.expired() > now ? (T) cached.value() : null;
    }

    public void hset(String key, String field, Object value, long expired) {
        long expiresAt = expired > 0 ? System.currentTimeMillis() / 1000 + expired : expired;
        cachePool.put(key + ":" + field, new CacheObject(value, expiresAt));
    }

    private record CacheObject(Object value, long expired) {}
}

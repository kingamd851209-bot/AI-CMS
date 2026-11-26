package com.xinhongru.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.support.spring.FastJsonRedisSerializer;
import com.alibaba.fastjson.support.spring.GenericFastJsonRedisSerializer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

import java.time.Duration;

/**
 * @Description redis 工具类 @ClassName RedisConfig @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/5 14:23
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {
    private final boolean production;
    private RedisConnectionFactory redisConnectionFactory;

    public RedisConfig(Environment environment) {
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Autowired
    public void setRedisConnectionFactory(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Bean
    @SuppressWarnings("all")
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory redisConnectionFactory) {
        /*我们为了自己开发方便 直接使用<String, Object>类型*/
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        /*配置具体的序列号方式*/
        /*Json序列化*/
        FastJsonRedisSerializer<Object> fastJsonRedisSerializer =
                new FastJsonRedisSerializer<>(Object.class);

        /*String的序列化*/
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        /*key采用String的序列化方式*/
        template.setKeySerializer(stringRedisSerializer);
        /*hash的key也采用String的序列化方式*/
        template.setHashKeySerializer(stringRedisSerializer);
        /*value序列化采用jackson*/
        template.setValueSerializer(fastJsonRedisSerializer);
        /*hash的value也采用fastJson的序列化方式*/
        template.setHashValueSerializer(fastJsonRedisSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 自定义缓存key生成策略，默认将使用该策略 使用方法 @Cacheable
     *
     * @return
     */
    @Bean
    @Override
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> {
            StringBuilder sb = new StringBuilder();
            sb.append(target.getClass().getName());
            sb.append(method.getName());
            for (Object obj : params) {
                sb.append(JSON.toJSONString(obj).hashCode());
            }
            return sb.toString();
        };
    }

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        // 异常处理，当Redis发生异常时，打印日志，但是程序正常走
        log.info("初始化 -> [{}]", "Redis CacheErrorHandler");
        CacheErrorHandler cacheErrorHandler =
                new CacheErrorHandler() {
                    @Override
                    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                        log.error("Redis occur handleCacheGetError：key -> [{}]", key, e);
                        rethrowInProduction(e);
                    }

                    @Override
                    public void handleCachePutError(
                            RuntimeException e, Cache cache, Object key, Object value) {
                        log.error(
                                "Redis occur handleCachePutError：key -> [{}]；value -> [{}]",
                                key,
                                value,
                                e);
                        rethrowInProduction(e);
                    }

                    @Override
                    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                        log.error("Redis occur handleCacheEvictError：key -> [{}]", key, e);
                        rethrowInProduction(e);
                    }

                    @Override
                    public void handleCacheClearError(RuntimeException e, Cache cache) {
                        log.error("Redis occur handleCacheClearError：", e);
                        rethrowInProduction(e);
                    }
                };
        return cacheErrorHandler;
    }

    private void rethrowInProduction(RuntimeException exception) {
        if (production) {
            throw exception;
        }
    }

    @Override
    public CacheResolver cacheResolver() {
        return new SimpleCacheResolver(cacheManager());
    }

    @Override
    public CacheManager cacheManager() {
        // 初始化一个RedisCacheWriter
        RedisCacheWriter redisCacheWriter =
                RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory);
        // 设置CacheManager的值序列化方式为json序列化

        RedisSerializer<Object> jsonSerializer = new GenericFastJsonRedisSerializer();
        RedisSerializationContext.SerializationPair<Object> pair =
                RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer);

        RedisCacheConfiguration redisCacheConfiguration =
                RedisCacheConfiguration.defaultCacheConfig()
                        // 设置默认超过期时间是120秒
                        .entryTtl(Duration.ofSeconds(120))
                        .serializeValuesWith(pair);

        // 初始化RedisCacheManager
        return new RedisCacheManager(redisCacheWriter, redisCacheConfiguration);
    }
}

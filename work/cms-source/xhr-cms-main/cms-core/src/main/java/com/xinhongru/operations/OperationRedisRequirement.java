package com.xinhongru.operations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class OperationRedisRequirement implements ApplicationRunner {
    private final CmsOperationProperties properties;
    private final Optional<RedisConnectionFactory> connectionFactory;

    public OperationRedisRequirement(
            CmsOperationProperties properties, Optional<RedisConnectionFactory> connectionFactory) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    public void verify() {
        if (!properties.isRequireRedis()) {
            return;
        }
        RedisConnectionFactory factory =
                connectionFactory.orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "cms.operation.require-redis=true 但 Redis 连接工厂不存在"));
        try (RedisConnection connection = factory.getConnection()) {
            if (!"PONG".equalsIgnoreCase(connection.ping())) {
                throw new IllegalStateException(
                        "cms.operation.require-redis=true 但 Redis PING 未返回 PONG");
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cms.operation.require-redis=true 但 Redis 不可用", ex);
        }
    }
}

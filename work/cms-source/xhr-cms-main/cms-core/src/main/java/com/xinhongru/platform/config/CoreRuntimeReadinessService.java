package com.xinhongru.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class CoreRuntimeReadinessService {
    private final Optional<RedisConnectionFactory> redisConnectionFactory;
    private final Environment environment;
    private final String uploadRoot;

    public CoreRuntimeReadinessService(
            Optional<RedisConnectionFactory> redisConnectionFactory,
            Environment environment,
            @Value("${uploadFile.rootPath:}") String uploadRoot) {
        this.redisConnectionFactory = redisConnectionFactory;
        this.environment = environment;
        this.uploadRoot = uploadRoot;
    }

    public CoreRuntimeReadiness inspect() {
        List<String> blockers = new ArrayList<>();
        boolean databasePasswordSecure =
                StringUtils.hasText(
                        environment.getProperty(
                                "spring.datasource.druid.password",
                                environment.getProperty("spring.datasource.password")));
        boolean redisAvailable = inspectRedis();
        boolean aiSecretKeyAvailable =
                StringUtils.hasText(environment.getProperty("cms.ai.secret-master-key"));
        boolean fileStorageWritable = inspectFileStorage();

        if (!databasePasswordSecure) {
            blockers.add(isProduction() ? "生产数据库密码为空，禁止视为安全就绪" : "数据库密码为空，禁止视为安全就绪");
        }
        if (!redisAvailable) {
            blockers.add("Redis 不可用");
        }
        if (!aiSecretKeyAvailable) {
            blockers.add(isProduction() ? "生产环境缺少 CMS_AI_SECRET_MASTER_KEY" : "AI 主密钥未配置");
        }
        if (!fileStorageWritable) {
            blockers.add("上传目录不可写");
        }

        return new CoreRuntimeReadiness(
                blockers.isEmpty(),
                redisAvailable,
                aiSecretKeyAvailable,
                fileStorageWritable,
                blockers);
    }

    private boolean inspectRedis() {
        if (redisConnectionFactory.isEmpty()) {
            return false;
        }
        try (RedisConnection connection = redisConnectionFactory.get().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean inspectFileStorage() {
        if (!StringUtils.hasText(uploadRoot)) {
            return false;
        }
        try {
            Path path = Path.of(uploadRoot).toAbsolutePath().normalize();
            return Files.isDirectory(path) && Files.isWritable(path);
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    private boolean isProduction() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}

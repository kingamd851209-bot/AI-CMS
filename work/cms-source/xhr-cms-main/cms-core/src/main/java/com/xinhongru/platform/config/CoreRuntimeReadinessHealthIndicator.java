package com.xinhongru.platform.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component("coreRuntimeReadiness")
public class CoreRuntimeReadinessHealthIndicator implements HealthIndicator {
    private final CoreRuntimeReadinessService readinessService;
    private final Environment environment;

    public CoreRuntimeReadinessHealthIndicator(
            CoreRuntimeReadinessService readinessService, Environment environment) {
        this.readinessService = readinessService;
        this.environment = environment;
    }

    @Override
    public Health health() {
        CoreRuntimeReadiness readiness = readinessService.inspect();
        Health.Builder builder = readiness.ready() ? Health.up() : Health.down();
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            builder.withDetail("redisAvailable", readiness.redisAvailable())
                    .withDetail("aiSecretKeyAvailable", readiness.aiSecretKeyAvailable())
                    .withDetail("fileStorageWritable", readiness.fileStorageWritable())
                    .withDetail("blockers", readiness.blockers());
        }
        return builder.build();
    }
}

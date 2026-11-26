package com.xinhongru.platform.config;

import java.util.List;

public record CoreRuntimeReadiness(
        boolean ready,
        boolean redisAvailable,
        boolean aiSecretKeyAvailable,
        boolean fileStorageWritable,
        List<String> blockers) {
    public CoreRuntimeReadiness {
        blockers = List.copyOf(blockers);
    }
}

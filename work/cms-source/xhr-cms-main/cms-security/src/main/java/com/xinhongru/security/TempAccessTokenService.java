package com.xinhongru.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 临时访问令牌服务 为AI生成短期有效的访问令牌，用于访问模板内容和预览界面 */
@Service
public class TempAccessTokenService {
    private static final long TOKEN_TTL_SECONDS = 30 * 60; // 30分钟

    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    /** 生成临时访问令牌 */
    public String generateToken(
            String resourceType, Long siteId, String resourcePath, Long columnId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenStore.put(
                token,
                new TokenEntry(
                        resourceType,
                        siteId,
                        resourcePath,
                        columnId,
                        Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));
        return token;
    }

    /** 验证并消费令牌 */
    public TokenEntry validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TokenEntry entry = tokenStore.get(token);
        if (entry == null || Instant.now().isAfter(entry.expiresAt)) {
            if (entry != null) {
                tokenStore.remove(token);
            }
            return null;
        }
        return entry;
    }

    /** 清理过期令牌 */
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        tokenStore.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt));
    }

    public record TokenEntry(
            String resourceType,
            Long siteId,
            String resourcePath,
            Long columnId,
            Instant expiresAt) {}
}

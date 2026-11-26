package com.xinhongru.ai.agent.opencode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Server-side configuration only; never supplied by browser requests. */
@Component
@ConfigurationProperties(prefix = "cms.ai.agent.opencode")
public class OpenCodeProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:4096";
    private String username = "opencode";
    private String password = "";
    private String providerId = "cms";
    private String modelId = "";
    private String agent = "cms-template";
    private String workspaceRoot = System.getProperty("java.io.tmpdir") + "/cms-ai-agent";
    private String remoteWorkspaceRoot = "";
    private long timeoutMs = 600_000;
    private int maxConcurrent = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        baseUrl = value;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        username = value;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String value) {
        providerId = value;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String value) {
        modelId = value;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String value) {
        agent = value;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String value) {
        workspaceRoot = value;
    }

    public String getRemoteWorkspaceRoot() {
        return remoteWorkspaceRoot;
    }

    public void setRemoteWorkspaceRoot(String value) {
        remoteWorkspaceRoot = value;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long value) {
        timeoutMs = value;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int value) {
        maxConcurrent = value;
    }

    public void checkConfigured() {
        if (!enabled) throw new IllegalStateException("模板修复执行器尚未启用，请配置 cms.ai.agent.opencode");
        checkUrl(baseUrl, "base-url");
        if (username.isBlank()
                || username.contains(":")
                || password.isBlank()
                || providerId.isBlank()
                || modelId.isBlank()
                || agent.isBlank()) {
            throw new IllegalStateException(
                    "cms.ai.agent.opencode 需要填写服务认证、模型供应商 ID、模型 ID 和执行器名称；用户名不能含冒号");
        }
        if (workspaceRoot.isBlank() || !Path.of(workspaceRoot).isAbsolute()) {
            throw new IllegalStateException("cms.ai.agent.opencode.workspace-root 必须是绝对路径");
        }
        if (!remoteWorkspaceRoot.isBlank() && !Path.of(remoteWorkspaceRoot).isAbsolute()) {
            throw new IllegalStateException("cms.ai.agent.opencode.remote-workspace-root 必须是绝对路径");
        }
    }

    private static void checkUrl(String value, String property) {
        try {
            URI uri = URI.create(value);
            if (List.of("http", "https").contains(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null) return;
        } catch (IllegalArgumentException ignored) {
            // Do not include the configured URL in errors: it may contain credentials.
        }
        throw new IllegalStateException("cms.ai.agent.opencode." + property + " 必须是有效的 HTTP(S) 地址");
    }
}

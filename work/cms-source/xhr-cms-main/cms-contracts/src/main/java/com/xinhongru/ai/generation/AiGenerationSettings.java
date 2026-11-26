package com.xinhongru.ai.generation;

/** 普通模型调用的只读快照端口；没有系统记录时返回null以使用初始环境配置。 */
public interface AiGenerationSettings {
    Snapshot generationSnapshot();

    record Snapshot(
            boolean enabled,
            String baseUrl,
            String modelName,
            String apiKey,
            int timeoutMs,
            int maxRetries) {
        public boolean configured() {
            return enabled
                    && baseUrl != null
                    && !baseUrl.isBlank()
                    && modelName != null
                    && !modelName.isBlank()
                    && apiKey != null
                    && !apiKey.isBlank();
        }

        public void checkConfigured() {
            if (!configured()) throw new IllegalStateException("AI模型未启用或配置不完整，请由平台管理员维护系统AI配置");
        }

        @Override
        public String toString() {
            return "AiGenerationSnapshot[enabled="
                    + enabled
                    + ", modelName="
                    + modelName
                    + ", configured="
                    + configured()
                    + "]";
        }
    }
}

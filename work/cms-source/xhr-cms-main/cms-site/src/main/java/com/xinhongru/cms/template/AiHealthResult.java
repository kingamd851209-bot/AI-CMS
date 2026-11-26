package com.xinhongru.cms.template;

/** AI服务健康检查结果 */
public record AiHealthResult(
        /** 健康状态 */
        AiHealthStatus status,
        /** 用户友好的消息 */
        String message,
        /** Provider名称 */
        String providerName,
        /** 模型名称 */
        String modelName,
        /** 连接测试耗时(毫秒) */
        Long testDurationMs) {
    /** 健康状态枚举 */
    public enum AiHealthStatus {
        /** 服务可用 */
        HEALTHY,
        /** 配置不完整 */
        CONFIG_INCOMPLETE,
        /** 连接测试失败 */
        CONNECTION_FAILED,
        /** 未找到Provider */
        NO_PROVIDER
    }

    /** 创建健康状态结果 */
    public static AiHealthResult healthy(
            String providerName, String modelName, Long testDurationMs) {
        return new AiHealthResult(
                AiHealthStatus.HEALTHY, "AI服务正常", providerName, modelName, testDurationMs);
    }

    /** 创建未找到Provider结果 */
    public static AiHealthResult noProvider(String message) {
        return new AiHealthResult(AiHealthStatus.NO_PROVIDER, message, null, null, null);
    }

    /** 创建配置不完整结果 */
    public static AiHealthResult configIncomplete(
            String message, String providerName, String modelName) {
        return new AiHealthResult(
                AiHealthStatus.CONFIG_INCOMPLETE, message, providerName, modelName, null);
    }

    /** 创建连接失败结果 */
    public static AiHealthResult connectionFailed(
            String message, String providerName, String modelName) {
        return new AiHealthResult(
                AiHealthStatus.CONNECTION_FAILED, message, providerName, modelName, null);
    }
}

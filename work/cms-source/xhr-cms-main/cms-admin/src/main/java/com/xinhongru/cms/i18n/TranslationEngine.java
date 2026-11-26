package com.xinhongru.cms.i18n;

/**
 * 栏目翻译引擎。
 *
 * <p>新增引擎时：在此登记枚举值，并提供一个 {@link MenuTranslationEngine} 实现 Bean 即可， 调用方无需改动。
 */
public enum TranslationEngine {
    /** 已停用；仅保留以明确拒绝旧请求，不做静默回退。 */
    OPENCODE,
    /** LLM：直连大模型（AiTextGenerator）。 */
    SPRING_AI;

    public static final TranslationEngine DEFAULT = SPRING_AI;

    /** 按名称解析；未知或为空返回 null，由调用方决定回退策略。 */
    public static TranslationEngine parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TranslationEngine engine : values()) {
            if (engine.name().equalsIgnoreCase(value)) {
                return engine;
            }
        }
        return null;
    }
}

package com.xinhongru.cms.i18n;

import com.xinhongru.ai.generation.AiUsage;

/**
 * 通用文本补全引擎：输入 prompt，返回模型原始文本。
 *
 * <p>menu / content 等业务各自负责 prompt 构造与响应解析，引擎只负责「把 prompt 交给某个 AI 后端」。 一种引擎一个实现，由 {@link
 * TextTranslationEngineRegistry} 按引擎路由。
 */
public interface TextTranslationEngine {
    TranslationEngine engine();

    /**
     * 用指定 usage 的 system prompt 执行补全。
     *
     * @param usage 用途（决定 system prompt 指令）；OpenCode 实现可忽略
     * @param prompt 完整 user prompt
     * @return 模型原始响应文本
     */
    String complete(AiUsage usage, String prompt);
}

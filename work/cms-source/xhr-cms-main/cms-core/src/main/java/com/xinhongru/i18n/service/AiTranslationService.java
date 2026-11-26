package com.xinhongru.i18n.service;

import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用 Spring AI 批量翻译多语言站点内容与字段业务名称。 会话仅保存目标语言，同步任务可以复用，不依赖 OpenCode 进程或远端会话。 */
@Service
public class AiTranslationService {
    /** 换行占位符：让每个待翻译值保持单行，翻译后再还原 */
    private static final String LINE_BREAK_TOKEN = "{{BR}}";

    /** 匹配 "序号|译文" 输出行（兼容全角竖线） */
    private static final Pattern RESULT_LINE = Pattern.compile("^\\s*(\\d+)\\s*[|｜]\\s*(.*)$");

    private final AiTextGenerator generator;

    public AiTranslationService(AiTextGenerator generator) {
        this.generator = generator;
    }

    /** 返回 null 表示模型已配置，否则返回不可用原因。 */
    public String ensureAvailable() {
        try {
            generator.checkConfigured(AiUsage.CONTENT_TRANSLATION);
            return null;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "AI翻译服务不可用：" + e.getMessage();
        }
    }

    /** 为一次同步保留目标语言；配置不可用时在同步开始前失败。 */
    public Session openSession(String targetLanguage) {
        generator.checkConfigured(AiUsage.CONTENT_TRANSLATION);
        return new Session(languageName(targetLanguage));
    }

    private String languageName(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return "Chinese";
        }
        if (targetLanguage.startsWith("en")) {
            return "English";
        }
        if (targetLanguage.startsWith("zh")) {
            return targetLanguage.toLowerCase().contains("tw") || targetLanguage.contains("Hant")
                    ? "Traditional Chinese"
                    : "Simplified Chinese";
        }
        return targetLanguage;
    }

    /** 翻译上下文：同一次站点同步使用相同目标语言。 */
    public class Session implements AutoCloseable {
        private final String language;

        private Session(String language) {
            this.language = language;
        }

        /** 批量翻译，返回与入参等长、顺序一致的译文；未翻译成功的条目保留原文。 */
        public List<String> translate(List<String> texts) {
            if (texts == null || texts.isEmpty()) {
                return new ArrayList<>();
            }
            StringBuilder prompt = new StringBuilder();
            prompt.append("Translate each of the following values into ")
                    .append(language)
                    .append(".\n");
            prompt.append(
                            "Rules: keep numbers, URLs, file paths, HTML tags/attributes and"
                                    + " template variables ")
                    .append("like {$1_title_id$} unchanged; keep the placeholder ")
                    .append(LINE_BREAK_TOKEN)
                    .append(" unchanged; if a value needs no translation, output it as is.\n");
            prompt.append(
                    "Output one line per input, in the same order, exactly as: index|translated"
                            + " value\n");
            prompt.append("Do not output explanations, code fences or extra lines.\n\n");
            for (int i = 0; i < texts.size(); i++) {
                String value = texts.get(i) == null ? "" : texts.get(i);
                prompt.append(i)
                        .append('|')
                        .append(value.replace("\r\n", "\n").replace("\n", LINE_BREAK_TOKEN))
                        .append('\n');
            }

            List<String> result = new ArrayList<>(texts);
            String raw = generator.generate(AiUsage.CONTENT_TRANSLATION, prompt.toString());
            if (raw == null || raw.isBlank()) {
                return result;
            }
            for (String line : raw.split("\n")) {
                Matcher matcher = RESULT_LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                int index;
                try {
                    index = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                String translated = matcher.group(2).trim();
                if (index >= 0 && index < result.size() && !translated.isEmpty()) {
                    result.set(index, translated.replace(LINE_BREAK_TOKEN, "\n"));
                }
            }
            return result;
        }

        /** 翻译单个值；失败或为空时原样返回 */
        public String translateOne(String text) {
            if (text == null || text.isBlank()) {
                return text;
            }
            return translate(List.of(text)).get(0);
        }

        @Override
        public void close() {
            // Spring AI 请求随每次翻译结束，无远端会话需要清理。
        }
    }
}

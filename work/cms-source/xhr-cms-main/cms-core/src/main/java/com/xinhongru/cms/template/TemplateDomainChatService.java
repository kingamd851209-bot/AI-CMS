package com.xinhongru.cms.template;

import com.xinhongru.ai.generation.AiOutputValidator;
import com.xinhongru.ai.generation.AiTextGenerator;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.cms.template.TemplateContentUnitsService.Source;
import com.xinhongru.cms.template.TemplateContentUnitsService.Unit;
import com.xinhongru.cms.template.TemplateContentUnitsService.Workspace;

import org.springframework.stereotype.Service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class TemplateDomainChatService {
    private static final AiUsage USAGE = AiUsage.WORKBENCH_DOMAIN_CHAT;
    private final TemplateContentUnitsService content;
    private final AiTextGenerator generator;
    private final ObjectMapper json = new ObjectMapper();

    public TemplateDomainChatService(
            TemplateContentUnitsService content, AiTextGenerator generator) {
        this.content = content;
        this.generator = generator;
    }

    public record Message(String role, String content) {}

    public record TaskRef(String kind, String id) {}

    public record Request(
            String sourcePath,
            String packageVersion,
            String digest,
            Long revision,
            Unit unit,
            List<Message> messages,
            TaskRef taskRef) {}

    public record Proposal(Unit before, Unit after, String reason) {}

    public record Reply(String reply, Proposal proposal) {}

    public Reply reply(
            String code,
            String version,
            Request request,
            Runnable guard,
            Supplier<Map<String, Object>> taskContext) {
        if (request == null) throw new IllegalArgumentException("请提交有效对话");
        if (!Objects.equals(request.packageVersion(), version)) {
            throw new IllegalArgumentException("页面包版本已变化，请刷新后继续对话");
        }
        validateMessages(request.messages());
        Unit before = request.unit();
        if (before == null && request.taskRef() == null)
            throw new IllegalArgumentException("请选择当前页面的内容单元或分析任务");
        if (before != null
                && (request.sourcePath() == null
                        || request.sourcePath().isBlank()
                        || request.sourcePath().length() > 1024
                        || request.digest() == null
                        || !request.digest().matches("[a-f0-9]{64}")
                        || request.revision() == null
                        || request.revision() < 0)) {
            throw new IllegalArgumentException("当前页面作用域无效");
        }
        guard.run();
        var businessSources =
                before == null
                        ? List.<Source>of()
                        : current(code, version, request).sources().stream()
                                .filter(TemplateContentUnitsService::businessCandidate)
                                .toList();
        Map<String, Source> sources =
                businessSources.stream().collect(Collectors.toMap(Source::id, Function.identity()));
        var context = new LinkedHashMap<String, Object>();
        if (before != null) {
            TemplateContentUnitsService.validate(List.of(before), businessSources);
            context.put("sourcePath", request.sourcePath());
            context.put("unit", before);
            context.put("evidence", evidence(before, sources));
        }
        if (request.taskRef() != null) context.put("task", taskContext.get());
        context.put("messages", request.messages());
        String prompt =
                """
                延续 messages 中的对话，回答最后一条 user 消息。只有明确要求修改时才提出方案，
                不确定时先追问。只返回 JSON {"reply":"给用户的中文回复","proposal":null}
                或 {"reply":"给用户的中文回复","proposal":{"reason":"中文修改依据", ...修改项}}。
                修改项只允许 name（业务名）、fieldType（类型）、ignored（布尔值）、doubts（疑点说明），
                省略不变项，并在 reply 写清建议值，方便用户在下一轮继续修订。
                类型候选 SingleLine、MultiLine、Editor、Image、Video、File、DateTime，
                类型必须与现有引用兼容。无权操作其他页面、单元、来源、栏目或持久化数据。
                若提问关于分析进度，使用 task 的服务端快照报告实际状态和最近步骤，区分已完成与下一步建议。
                只根据快照中存在的字段汇报数量、阶段和检查点。无快照时说明没有选定的任务，勿猜测。
                没有选定内容单元时只回复对话，proposal 必须为 null，不声称可控制任务。
                """
                        + json.writeValueAsString(context);
        var engine = generator.forTask();
        engine.checkConfigured(USAGE);
        String output =
                engine.stream(USAGE, prompt)
                        .reduce(
                                new StringBuilder(),
                                (buffer, part) -> {
                                    if (buffer.length() + part.length() > 12_000)
                                        throw new IllegalArgumentException("Agent 对话输出超限");
                                    return buffer.append(part);
                                })
                        .map(StringBuilder::toString)
                        .block(Duration.ofSeconds(60));
        guard.run();
        if (before != null) current(code, version, request);
        return parseReply(output, before, sources, businessSources);
    }

    private static List<Map<String, String>> evidence(Unit before, Map<String, Source> sources) {
        List<Map<String, String>> evidence = new ArrayList<>();
        for (var reference : before.sources()) {
            Source source = sources.get(reference.sourceId());
            int end = Math.min(reference.end(), reference.start() + 300);
            if (end < reference.end()
                    && Character.isHighSurrogate(source.rawValue().charAt(end - 1))) end--;
            String value = source.rawValue().substring(reference.start(), end);
            evidence.add(
                    Map.of(
                            "sourceId",
                            source.id(),
                            "kind",
                            source.kind(),
                            "value",
                            value,
                            "truncated",
                            end < reference.end() ? "是" : "否"));
        }
        return evidence;
    }

    private Workspace current(String code, String version, Request request) {
        Workspace workspace = content.load(code, version, request.sourcePath());
        if (!Objects.equals(workspace.digest(), request.digest())
                || workspace.revision() != request.revision()) {
            throw new IllegalArgumentException("页面原文或已保存单元已变化，请刷新后继续对话");
        }
        return workspace;
    }

    private static void validateMessages(List<Message> messages) {
        if (messages == null
                || messages.isEmpty()
                || messages.size() > 11
                || messages.size() % 2 == 0) {
            throw new IllegalArgumentException("对话须以用户消息开头和结束，最多 11 条消息");
        }
        int length = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message == null
                    || !(i % 2 == 0 ? "user" : "assistant").equals(message.role())
                    || message.content() == null
                    || message.content().isBlank()
                    || message.content().length() > 2000
                    || (length += message.content().length()) > 12_000) {
                throw new IllegalArgumentException("对话消息顺序无效或长度超限");
            }
        }
    }

    private Reply parseReply(
            String output, Unit before, Map<String, Source> sources, List<Source> businessSources) {
        JsonNode data;
        try {
            data =
                    json.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(AiOutputValidator.stripFence(output));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Agent 对话结果格式无效，请重试");
        }
        requireOnly(data, Set.of("reply", "proposal"));
        String reply = readText(data.path("reply"), 2000, false);
        if (!data.hasNonNull("proposal")) return new Reply(reply, null);
        if (before == null) throw new IllegalArgumentException("Agent 对话不能修改未选定的内容单元");

        var patch = data.get("proposal");
        requireOnly(patch, Set.of("name", "fieldType", "ignored", "doubts", "reason"));
        String name = patch.has("name") ? readText(patch.get("name"), 100, false) : before.name();
        String type =
                patch.has("fieldType")
                        ? readText(patch.get("fieldType"), 20, false)
                        : before.fieldType();
        String doubts =
                patch.has("doubts") ? readText(patch.get("doubts"), 2000, true) : before.doubts();
        if (patch.has("ignored") && !patch.get("ignored").isBoolean())
            throw new IllegalArgumentException("Agent 建议的忽略状态无效");
        boolean ignored =
                patch.has("ignored") ? patch.get("ignored").asBoolean() : before.ignored();
        String reason = readText(patch.path("reason"), 500, false);
        Unit after = new Unit(before.id(), name, type, before.sources(), ignored, doubts);
        TemplateContentUnitsService.validate(List.of(after), businessSources);
        if (!type.equals(before.fieldType())
                && ("Editor".equals(type) && before.sources().size() != 1
                        || before.sources().stream()
                                .map(reference -> sources.get(reference.sourceId()).kind())
                                .anyMatch(
                                        kind ->
                                                !TemplateDomainAgentService.acceptsSourceKind(
                                                        type, kind)))) {
            throw new IllegalArgumentException("Agent 建议的字段类型与当前引用来源不兼容");
        }
        return new Reply(reply, before.equals(after) ? null : new Proposal(before, after, reason));
    }

    private static void requireOnly(JsonNode data, Set<String> allowed) {
        if (data == null || !data.isObject() || !allowed.containsAll(data.propertyNames()))
            throw new IllegalArgumentException("Agent 对话结果包含无效或未授权字段");
    }

    private static String readText(JsonNode value, int limit, boolean allowBlank) {
        if (!value.isTextual()
                || value.asText().length() > limit
                || !allowBlank && value.asText().isBlank())
            throw new IllegalArgumentException("Agent 对话结果中的文本无效或超限");
        return value.asText().strip();
    }
}

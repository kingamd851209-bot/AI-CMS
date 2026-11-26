package com.xinhongru.ai.generation;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 站级和单页Agent共用的简短状态流，不传模型产物。 */
public final class AgentTaskProgressSse {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentTaskProgressSse() {}

    public static SseEmitter open(AiGenerationTasks.Task task, String lastEventId, Runnable authorization) {
        if (lastEventId != null && !lastEventId.matches("[0-9]{1,18}"))
            throw new IllegalArgumentException("无效的任务事件序号");
        authorization.run();
        var emitter = new SseEmitter(task.timeoutMs + 30_000L);
        var closed = new AtomicBoolean();
        var first = new AtomicBoolean(true);
        var subscription = new AtomicReference<Disposable>();
        Runnable dispose = () -> {
            closed.set(true);
            var active = subscription.get();
            if (active != null) active.dispose();
        };
        emitter.onCompletion(dispose);
        emitter.onTimeout(dispose);
        emitter.onError(error -> dispose.run());
        subscription.set(task.progressUpdates().subscribe(update -> {
            try {
                authorization.run();
                String event = first.getAndSet(false) ? "snapshot" : switch (update.state()) {
                    case "completed" -> "done";
                    case "failed" -> "failed";
                    case "cancelled" -> "cancelled";
                    default -> "progress";
                };
                var data = new AiGenerationTasks.ProgressSnapshot(update.taskId(), update.state(),
                        brief(update.error(), 300), update.timeoutMs(), safeProgress(update.progress()), update.sequence());
                emitter.send(SseEmitter.event().id(String.valueOf(update.sequence())).name(event).data(data));
            } catch (Exception error) {
                emitter.completeWithError(error);
                dispose.run();
            }
        }, error -> {
            emitter.completeWithError(error);
            dispose.run();
        }, emitter::complete));
        if (closed.get()) subscription.get().dispose();
        return emitter;
    }

    static String safeProgress(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            var input = JSON.readTree(raw);
            var progress = new LinkedHashMap<String, Object>();
            for (String name : java.util.List.of("round", "observedPages", "observedValues", "totalPages"))
                if (input.path(name).isIntegralNumber()) progress.put(name, input.path(name).asInt());
            if (input.path("checkpointAvailable").isBoolean())
                progress.put("checkpointAvailable", input.path("checkpointAvailable").asBoolean());
            for (String name : java.util.List.of("tool", "status", "message"))
                if (input.path(name).isString()) progress.put(name, brief(input.path(name).asText(),
                        "message".equals(name) ? 300 : 40));
            var steps = new ArrayList<Map<String, Object>>();
            var source = input.path("steps");
            if (source.isArray()) {
                for (int i = Math.max(0, source.size() - 20); i < source.size(); i++) {
                    var step = source.get(i);
                    steps.add(Map.of("round", step.path("round").asInt(), "tool", brief(step.path("tool").asText(), 40),
                            "status", brief(step.path("status").asText(), 40),
                            "message", brief(step.path("message").asText(), 300)));
                }
            }
            progress.put("steps", steps);
            return JSON.writeValueAsString(progress);
        } catch (tools.jackson.core.JacksonException error) {
            return "";
        }
    }

    private static String brief(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, Math.min(maximum, value.length())).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    }
}

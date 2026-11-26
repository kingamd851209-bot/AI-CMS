package com.xinhongru.ai.generation;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;

/** Port for complex tasks. Implementations own remote sessions and cancellation cleanup. */
public interface AgentExecutor {
    void checkReady();

    long timeoutMillis();

    Flux<AiTaskEvent> execute(Request request);

    record Request(
            String taskId,
            Long siteId,
            String prompt,
            String instruction,
            Map<String, String> files,
            Set<String> editableFiles) {
        public Request {
            files = Map.copyOf(files);
            editableFiles = Set.copyOf(editableFiles);
            if (!files.keySet().containsAll(editableFiles))
                throw new IllegalArgumentException("编辑范围必须属于任务文件");
            for (String name : files.keySet()) {
                if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}")) {
                    throw new IllegalArgumentException("任务文件必须使用简单文件名");
                }
            }
        }
    }
}

package com.xinhongru.ai.generation;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/** Business routing is independent of model providers and coding-agent protocols. */
@Service
public class AiTaskRouter {
    private final AiTextGenerator generator;

    public AiTaskRouter(AiTextGenerator generator, AgentExecutor agent) {
        this.generator = generator;
    }

    public void checkReady(AiUsage usage) {
        rejectRetiredUsage(usage);
        generator.checkConfigured(usage);
    }

    public long timeoutMillis(AiUsage usage) {
        rejectRetiredUsage(usage);
        return 300_000L;
    }

    public Flux<AiTaskEvent> execute(String taskId, Long siteId, AiUsage usage, String prompt) {
        rejectRetiredUsage(usage);
        return generator.stream(usage, prompt).map(AiTaskEvent::delta);
    }

    private void rejectRetiredUsage(AiUsage usage) {
        if (usage == AiUsage.TEMPLATE_REPAIR) throw new RetiredUsageException();
    }

    public static final class RetiredUsageException extends RuntimeException {
        public RetiredUsageException() {
            this("该开发入口已停用");
        }

        public RetiredUsageException(String message) {
            super(message);
        }
    }
}

package com.xinhongru.ai.generation;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/admin/v2/ai-generation")
public class AiGenerationController {
    private final AiGenerationTasks tasks;
    private final AiTaskRouter router;
    private final AdminAuthenticationService authentication;
    private final SiteContextService sites;

    public AiGenerationController(
            AiGenerationTasks tasks,
            AiTaskRouter router,
            AdminAuthenticationService authentication,
            SiteContextService sites) {
        this.tasks = tasks;
        this.router = router;
        this.authentication = authentication;
        this.sites = sites;
    }

    @ExceptionHandler(AiTaskRouter.RetiredUsageException.class)
    public ResponseEntity<AdminApiResponse<Void>> retired(
            AiTaskRouter.RetiredUsageException error) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new AdminApiResponse<>(410, error.getMessage(), null));
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestParam(defaultValue = "TEMPLATE_GENERATION") AiUsage usage) {
        requireOrdinaryUsage(usage);
        try {
            router.checkReady(usage);
            return Map.of(
                    "status",
                    "running",
                    "message",
                    usage == AiUsage.TEMPLATE_REPAIR ? "模板修复服务已就绪" : "模型已配置",
                    "error",
                    "",
                    "url",
                    "");
        } catch (IllegalStateException error) {
            return Map.of(
                    "status",
                    "failed",
                    "message",
                    error.getMessage(),
                    "error",
                    error.getMessage(),
                    "url",
                    "");
        }
    }

    @PostMapping("/tasks")
    public Map<String, String> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        Integer owner = owner(request);
        if (body.siteId() == null || body.usage() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定站点和 AI 用途");
        }
        sites.assertCanAccessSite(request, body.siteId());
        requireOrdinaryUsage(body.usage());
        if ("OPENCODE".equalsIgnoreCase(body.engine()))
            throw new AiTaskRouter.RetiredUsageException("旧OPENCODE引擎已停用，请选择SPRING_AI");
        if (body.engine() != null
                && !body.engine().isBlank()
                && !"SPRING_AI".equalsIgnoreCase(body.engine()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "翻译引擎无效，请选择SPRING_AI");
        try {
            router.checkReady(body.usage());
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, error.getMessage());
        }
        var task = tasks.create(owner, body.siteId(), body.usage());
        return Map.of("id", task.id);
    }

    @PostMapping("/tasks/{id}/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiGenerationTasks.Snapshot message(
            @PathVariable String id, @RequestBody MessageRequest body, HttpServletRequest request) {
        var task = require(id, request);
        if (body.prompt() == null || body.prompt().isBlank() || body.prompt().length() > 200_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提示词不能为空且不能超过 200000 字符");
        }
        tasks.start(task, body.prompt());
        return task.snapshot();
    }

    @GetMapping("/tasks/{id}")
    public AiGenerationTasks.Snapshot result(@PathVariable String id, HttpServletRequest request) {
        return require(id, request).snapshot();
    }

    @GetMapping(value = "/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, HttpServletRequest request) {
        var task = require(id, request);
        SseEmitter emitter = new SseEmitter(task.timeoutMs + 30_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        Runnable dispose =
                () -> {
                    closed.set(true);
                    Disposable active = subscription.get();
                    if (active != null) active.dispose();
                };
        emitter.onCompletion(dispose);
        emitter.onTimeout(dispose);
        emitter.onError(error -> dispose.run());
        subscription.set(
                task.events()
                        .subscribe(
                                event -> {
                                    try {
                                        emitter.send(
                                                SseEmitter.event().name(event.type()).data(event));
                                    } catch (Exception error) {
                                        emitter.completeWithError(error);
                                        dispose.run();
                                    }
                                },
                                emitter::completeWithError,
                                emitter::complete));
        if (closed.get()) subscription.get().dispose();
        return emitter;
    }

    @PostMapping("/tasks/{id}/cancel")
    public Map<String, String> cancel(@PathVariable String id, HttpServletRequest request) {
        var task = require(id, request);
        tasks.cancel(task);
        return Map.of("status", task.state());
    }

    private AiGenerationTasks.Task require(String id, HttpServletRequest request) {
        var task = tasks.require(id, owner(request));
        sites.assertCanAccessSite(request, task.siteId);
        requireOrdinaryUsage(task.usage);
        return task;
    }

    private Integer owner(HttpServletRequest request) {
        return authentication.requireAuthenticatedUser(request).getId();
    }

    private void requireOrdinaryUsage(AiUsage usage) {
        if (usage != AiUsage.MENU_TRANSLATION && usage != AiUsage.CONTENT_TRANSLATION)
            throw new AiTaskRouter.RetiredUsageException();
    }

    public record CreateRequest(Long siteId, AiUsage usage, String engine) {
        public CreateRequest(Long siteId, AiUsage usage) {
            this(siteId, usage, null);
        }
    }

    public record MessageRequest(String prompt) {}
}

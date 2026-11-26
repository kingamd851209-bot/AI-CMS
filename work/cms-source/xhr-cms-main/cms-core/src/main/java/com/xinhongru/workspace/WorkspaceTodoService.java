package com.xinhongru.workspace;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkspaceTodoService {
    public WorkspaceTodoBoard loadTodos(HttpServletRequest request) {
        return new WorkspaceTodoBoard(List.of());
    }

    public record WorkspaceTodoBoard(List<WorkspaceTodoItem> items) {}

    public record WorkspaceTodoItem(
            String type,
            String title,
            String description,
            String actionLabel,
            String actionUrl,
            String severity) {}
}

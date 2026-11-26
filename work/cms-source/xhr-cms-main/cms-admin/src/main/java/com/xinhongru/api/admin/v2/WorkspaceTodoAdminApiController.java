package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.workspace.WorkspaceTodoService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v2/workspace/todos")
public class WorkspaceTodoAdminApiController {
    private final WorkspaceTodoService todoService;

    public WorkspaceTodoAdminApiController(WorkspaceTodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public AdminApiResponse<WorkspaceTodoService.WorkspaceTodoBoard> todos(
            HttpServletRequest request) {
        return AdminApiResponse.success(todoService.loadTodos(request));
    }
}

package com.xinhongru.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Controller
@RequestMapping("/admin/v2")
public class IndexVueController {
    private final String frontendBaseUrl;

    public IndexVueController(@Value("${cms.admin.frontend-base-url:}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping({"", "/", "/**"})
    public void shell(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String query = request.getQueryString();
        if (frontendBaseUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "管理端地址未配置，请设置 cms.admin.frontend-base-url");
        }
        response.sendRedirect(
                frontendBaseUrl.replaceAll("/+$", "") + path + (query == null ? "" : "?" + query));
    }
}

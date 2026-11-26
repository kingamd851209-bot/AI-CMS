package com.xinhongru.controller.admin;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理节点错误响应始终为 JSON，不渲染 HTML 错误页面。 */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        HttpStatus knownStatus = HttpStatus.resolve(status);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "success",
                                false,
                                "status",
                                status,
                                "message",
                                knownStatus == null
                                        ? "Request failed"
                                        : knownStatus.getReasonPhrase()));
    }
}

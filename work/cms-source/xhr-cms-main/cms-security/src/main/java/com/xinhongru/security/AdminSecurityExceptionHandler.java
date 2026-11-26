package com.xinhongru.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestControllerAdvice
public class AdminSecurityExceptionHandler {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final ObjectMapper objectMapper;

    public AdminSecurityExceptionHandler() {
        this(new ObjectMapper());
    }

    public AdminSecurityExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(AdminSecurityException.class)
    public ResponseEntity<AdminApiError> handle(
            HttpServletRequest request, AdminSecurityException exception) {
        String requestId = requestId(request);
        return ResponseEntity.status(exception.status())
                .header(REQUEST_ID_HEADER, requestId)
                .body(errorBody(exception, requestId));
    }

    public void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            AdminSecurityException exception)
            throws IOException {
        String requestId = requestId(request);
        response.setStatus(exception.status());
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), errorBody(exception, requestId));
    }

    private AdminApiError errorBody(AdminSecurityException exception, String requestId) {
        return new AdminApiError(
                exception.code(), exception.getMessage(), requestId, exception.details());
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()
                ? requestId
                : UUID.randomUUID().toString();
    }

    public record AdminApiError(
            String code, String message, String requestId, Map<String, Object> details) {}

    public static final class AdminSecurityException extends RuntimeException {
        private final int status;
        private final String code;
        private final Map<String, Object> details;

        private AdminSecurityException(
                int status, String code, String message, Map<String, Object> details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static AdminSecurityException unauthorized(String code, String message) {
            return new AdminSecurityException(401, code, message, Map.of());
        }

        public static AdminSecurityException badRequest(String code, String message) {
            return new AdminSecurityException(400, code, message, Map.of());
        }

        public static AdminSecurityException forbidden(String code, String message) {
            return forbidden(code, message, Map.of());
        }

        public static AdminSecurityException forbidden(
                String code, String message, Map<String, Object> details) {
            return new AdminSecurityException(403, code, message, details);
        }

        public static AdminSecurityException notFound(String code, String message) {
            return new AdminSecurityException(404, code, message, Map.of());
        }

        public static AdminSecurityException conflict(String code, String message) {
            return new AdminSecurityException(409, code, message, Map.of());
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> details() {
            return details;
        }
    }
}

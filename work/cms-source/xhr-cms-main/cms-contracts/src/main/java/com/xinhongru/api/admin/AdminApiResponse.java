package com.xinhongru.api.admin;

public record AdminApiResponse<T>(Integer code, String message, T data) {
    private static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> AdminApiResponse<T> success(T data) {
        return new AdminApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static AdminApiResponse<Void> success() {
        return success(null);
    }
}

package com.graduation.ragqa.common;

import org.slf4j.MDC;

public record ApiResponse<T>(boolean success, T data, ApiError error, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, MDC.get("traceId"));
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message), MDC.get("traceId"));
    }

    public record ApiError(String code, String message) {
    }
}


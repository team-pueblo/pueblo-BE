package com.pueblo.api;

import jakarta.servlet.http.HttpServletRequest;

public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> of(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, RequestIdFilter.id(request));
    }
}

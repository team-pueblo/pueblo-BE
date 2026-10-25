package com.pueblo.common.exception;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {
    INVALID_INPUT(400),
    INVALID_CREDENTIALS(401),
    ACCESS_DENIED(403),
    CSRF_INVALID(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    AUTH_STORE_UNAVAILABLE(503),
    DEPENDENCY_UNAVAILABLE(503);
    private final HttpStatus status;
    CommonErrorCode(int status) { this.status=HttpStatus.valueOf(status); }
    @Override public HttpStatus status() { return status; }
    @Override public String code() { return name(); }
    @Override public String messageKey() { return name(); }
}

package com.pueblo.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(400), CONSENT_REQUIRED(400), INVALID_CREDENTIALS(401), REFRESH_REUSE_DETECTED(401),
    ACCESS_DENIED(403), CSRF_INVALID(403), NOT_FOUND(404), METHOD_NOT_ALLOWED(405), NOT_ACCEPTABLE(406),
    EMAIL_ALREADY_EXISTS(409), EMAIL_ALREADY_VERIFIED(409), REFRESH_ALREADY_ROTATED(409), TOKEN_EXPIRED_OR_USED(410),
    PAYLOAD_TOO_LARGE(413), UNSUPPORTED_MEDIA_TYPE(415), RATE_LIMITED(429),
    INTERNAL_ERROR(500), AUTH_STORE_UNAVAILABLE(503);
    private final HttpStatus status;
    ErrorCode(int status) { this.status=HttpStatus.valueOf(status); }
    public HttpStatus status() { return status; }
}

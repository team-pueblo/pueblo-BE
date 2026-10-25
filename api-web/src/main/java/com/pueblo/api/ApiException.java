package com.pueblo.api;

public final class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Long retryAfterSeconds;
    public ApiException(ErrorCode code) { this(code,null); }
    public ApiException(ErrorCode code, Long retryAfterSeconds) {
        super(code.name()); this.code=code; this.retryAfterSeconds=retryAfterSeconds;
    }
    public ErrorCode code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public static ApiException unauthorized() { return new ApiException(ErrorCode.INVALID_CREDENTIALS); }
}

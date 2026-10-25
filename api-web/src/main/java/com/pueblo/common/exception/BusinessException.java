package com.pueblo.common.exception;



public final class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final Long retryAfterSeconds;
    public BusinessException(ErrorCode code) { this(code,null); }
    public BusinessException(ErrorCode code, Long retryAfterSeconds) {
        super(java.util.Objects.requireNonNull(code).code());
        if(retryAfterSeconds!=null && retryAfterSeconds<1) throw new IllegalArgumentException("Retry delay must be positive");
        this.code=code; this.retryAfterSeconds=retryAfterSeconds;
    }
    public ErrorCode code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public static BusinessException unauthorized() { return new BusinessException(CommonErrorCode.INVALID_CREDENTIALS); }
}

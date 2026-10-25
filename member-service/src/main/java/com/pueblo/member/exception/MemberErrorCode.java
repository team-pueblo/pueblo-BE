package com.pueblo.member.exception;

import com.pueblo.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MemberErrorCode implements ErrorCode {
    CONSENT_REQUIRED(400),
    EMAIL_ALREADY_EXISTS(409),
    EMAIL_ALREADY_VERIFIED(409),
    REFRESH_ALREADY_ROTATED(409),
    REFRESH_REUSE_DETECTED(401),
    TOKEN_EXPIRED_OR_USED(410);
    private final HttpStatus status;
    MemberErrorCode(int status) { this.status=HttpStatus.valueOf(status); }
    @Override public HttpStatus status() { return status; }
    @Override public String code() { return name(); }
    @Override public String messageKey() { return name(); }
}

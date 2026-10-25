package com.pueblo.common.exception;

import org.springframework.http.HttpStatus;

/** Domain enums implement this contract without adding domain state to the common module. */
public interface ErrorCode {
    String code();
    HttpStatus status();
    String messageKey();
}

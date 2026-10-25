package com.pueblo.order.exception;

import com.pueblo.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OrderErrorCode implements ErrorCode {
    CONSENT_REQUIRED(400),
    PRICE_CHANGED(409),
    QUOTE_EXPIRED(410),
    IDEMPOTENCY_CONFLICT(409),
    QUOTE_ALREADY_USED(409),
    PRODUCT_UNAVAILABLE(409),
    QUANTITY_LIMIT_EXCEEDED(409),
    ORDER_STATE_CONFLICT(409);
    private final HttpStatus status;
    OrderErrorCode(int status) { this.status=HttpStatus.valueOf(status); }
    @Override public HttpStatus status() { return status; }
    @Override public String code() { return name(); }
    @Override public String messageKey() { return name(); }
}

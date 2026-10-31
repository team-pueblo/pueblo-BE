package com.pueblo.payment.checkout;
import com.pueblo.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_REQUEST_CONFLICT, PAYMENT_ORDER_CONFLICT, PAYMENT_TOTAL_MISMATCH;
    public String code() { return name(); }
    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String messageKey() { return name(); }
}

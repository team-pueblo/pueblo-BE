package com.pueblo.common.exception;

import com.pueblo.common.logging.RequestIdFilter;
import com.pueblo.common.response.ApiErrorDetail;
import com.pueblo.common.response.ApiErrorResponse;
import com.pueblo.common.response.ApiErrorResponseFactory;
import com.pueblo.common.response.ApiMessageResolver;
import com.pueblo.common.security.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class GlobalExceptionHandler {
    private final ApiMessageResolver resolver;
    private final ApiErrorResponseFactory factory;
    public GlobalExceptionHandler(ApiMessageResolver resolver,ApiErrorResponseFactory factory) { this.resolver=resolver; this.factory=factory; }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> business(BusinessException ex,HttpServletRequest request) { return response(ex.code(),request,ex.retryAfterSeconds(),List.of()); }
    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiErrorResponse> binding(BindException ex,HttpServletRequest request) {
        var locale=resolver.locale(request);
        var details=ex.getBindingResult().getFieldErrors().stream().limit(100).map(error->resolver.detail(error.getField(),error.getCode(),locale)).toList();
        return response(CommonErrorCode.INVALID_INPUT,request,null,details);
    }
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> methodValidation(HandlerMethodValidationException ex,HttpServletRequest request) {
        if(ex.isForReturnValue()) return unexpected(ex,request);
        var locale=resolver.locale(request);
        var details=ex.getParameterValidationResults().stream().flatMap(result->result.getResolvableErrors().stream().map(error->{
            String code=error.getCodes()==null || error.getCodes().length==0?"invalid":error.getCodes()[0].split("\\.")[0];
            return resolver.detail(result.getMethodParameter().getParameterName(),code,locale);
        })).limit(100).toList();
        return response(CommonErrorCode.INVALID_INPUT,request,null,details);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> parameter(MethodArgumentTypeMismatchException ex,HttpServletRequest request) {
        return response(CommonErrorCode.INVALID_INPUT,request,null,List.of(resolver.detail(ex.getName(),"typeMismatch",resolver.locale(request))));
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missing(MissingServletRequestParameterException ex,HttpServletRequest request) {
        return response(CommonErrorCode.INVALID_INPUT,request,null,List.of(resolver.detail(ex.getParameterName(),"required",resolver.locale(request))));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class})
    ResponseEntity<ApiErrorResponse> invalid(Exception ex,HttpServletRequest request) { return response(CommonErrorCode.INVALID_INPUT,request,null,List.of()); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> denied(Exception ex,HttpServletRequest request) {
        return response(ex instanceof org.springframework.security.web.csrf.CsrfException?CommonErrorCode.CSRF_INVALID:CommonErrorCode.ACCESS_DENIED,request,null,List.of());
    }
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> unauthorized(Exception ex,HttpServletRequest request) {
        return response(ex instanceof org.springframework.security.authentication.AuthenticationServiceException?CommonErrorCode.AUTH_STORE_UNAVAILABLE:CommonErrorCode.INVALID_CREDENTIALS,request,null,List.of());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex,HttpServletRequest request) {
        if(ex instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            ErrorCode code=switch(error.getStatusCode().value()) {
                case 404 -> CommonErrorCode.NOT_FOUND; case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
                case 406 -> CommonErrorCode.NOT_ACCEPTABLE; case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
                default -> CommonErrorCode.INVALID_INPUT;
            };
            return response(code,request,null,List.of());
        }
        // Log category only: exception messages may contain SQL arguments or user input.
        LoggerFactory.getLogger(getClass()).error("API request failed requestId={} category={}",RequestIdFilter.id(request),ex.getClass().getName());
        return response(CommonErrorCode.INTERNAL_ERROR,request,null,List.of());
    }
    private ResponseEntity<ApiErrorResponse> response(ErrorCode code,HttpServletRequest request,Long retry,List<ApiErrorDetail> details) {
        var builder=ResponseEntity.status(code.status()).header("Content-Language",resolver.locale(request).getLanguage())
                .header("Vary","Accept-Language").header("Cache-Control","no-store");
        if(retry!=null) builder.header("Retry-After",retry.toString());
        SecurityEventLogger.error(request,code);
        return builder.body(factory.create(code,request,details));
    }
}

package com.pueblo.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException ex,HttpServletRequest request) { return response(ex.code(),request,ex.retryAfterSeconds()); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception ex,HttpServletRequest request) { return response(ErrorCode.INVALID_INPUT,request,null); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex,HttpServletRequest request) {
        if(ex instanceof ErrorResponse error) {
            ErrorCode code=switch(error.getStatusCode().value()) {
                case 404 -> ErrorCode.NOT_FOUND; case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
                case 406 -> ErrorCode.NOT_ACCEPTABLE; case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                default -> ErrorCode.INVALID_INPUT;
            };
            if(error.getStatusCode().is4xxClientError()) return response(code,request,null);
        }
        LoggerFactory.getLogger(getClass()).error("API request failed requestId={} category={}",RequestIdFilter.id(request),ex.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_ERROR,request,null);
    }
    private ResponseEntity<?> response(ErrorCode code,HttpServletRequest request,Long retry) {
        var response=ResponseEntity.status(code.status()).header("Content-Language",ApiErrors.locale(request).getLanguage())
                .header("Vary","Accept-Language").header("Cache-Control","no-store");
        if(retry!=null) response.header("Retry-After",retry.toString());
        return response.body(ApiErrors.body(code,request));
    }
}

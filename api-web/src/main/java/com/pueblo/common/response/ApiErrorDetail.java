package com.pueblo.common.response;



/** Never contains a rejected value or an exception's raw message. */
public record ApiErrorDetail(String field, String code, String message) { }

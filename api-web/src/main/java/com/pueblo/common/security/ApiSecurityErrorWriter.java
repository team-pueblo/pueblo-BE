package com.pueblo.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.ErrorCode;
import com.pueblo.common.response.ApiErrorResponseFactory;
import com.pueblo.common.response.ApiMessageResolver;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Shared wire contract for errors outside DispatcherServlet (security, CORS and body limits). */
public final class ApiSecurityErrorWriter {
    private static final ApiMessageResolver RESOLVER=new ApiMessageResolver();
    private static final ApiErrorResponseFactory FACTORY=new ApiErrorResponseFactory(RESOLVER);
    private ApiSecurityErrorWriter() { }
    public static void write(ObjectMapper mapper,HttpServletRequest request,HttpServletResponse response,ErrorCode code) throws IOException {
        write(mapper,request,response,code,null);
    }
    public static void write(ObjectMapper mapper,HttpServletRequest request,HttpServletResponse response,ErrorCode code,Long retryAfter) throws IOException {
        if(response.isCommitted()) return;
        response.setStatus(code.status().value()); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Language",RESOLVER.locale(request).getLanguage());
        response.addHeader("Vary","Accept-Language"); response.setHeader("Cache-Control","no-store");
        if(retryAfter!=null) response.setHeader("Retry-After",retryAfter.toString());
        SecurityEventLogger.error(request,code);
        mapper.writeValue(response.getOutputStream(),FACTORY.create(code,request));
    }
}

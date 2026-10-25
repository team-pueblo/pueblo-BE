package com.pueblo.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.CommonErrorCode;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

public final class ApiAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper mapper;
    public ApiAccessDeniedHandler(ObjectMapper mapper) { this.mapper=mapper; }
    @Override public void handle(HttpServletRequest request,HttpServletResponse response,AccessDeniedException exception) throws IOException {
        ApiSecurityErrorWriter.write(mapper,request,response,exception instanceof CsrfException?CommonErrorCode.CSRF_INVALID:CommonErrorCode.ACCESS_DENIED);
    }
}

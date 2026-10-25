package com.pueblo.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.CommonErrorCode;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;
    public ApiAuthenticationEntryPoint(ObjectMapper mapper) { this.mapper=mapper; }
    @Override public void commence(HttpServletRequest request,HttpServletResponse response,AuthenticationException exception) throws IOException {
        ApiSecurityErrorWriter.write(mapper,request,response,exception instanceof AuthenticationServiceException
                ?CommonErrorCode.AUTH_STORE_UNAVAILABLE:CommonErrorCode.INVALID_CREDENTIALS);
    }
}

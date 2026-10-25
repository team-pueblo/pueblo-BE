package com.pueblo.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.CommonErrorCode;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.cors.*;

public class ApiCorsProcessor extends DefaultCorsProcessor {
    private final ObjectMapper mapper;
    public ApiCorsProcessor(ObjectMapper mapper) { this.mapper=mapper; }
    @Override protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response) { response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN); }
    @Override public boolean processRequest(CorsConfiguration configuration,HttpServletRequest request,HttpServletResponse response) throws IOException {
        boolean accepted=super.processRequest(configuration,request,response);
        if(!accepted) ApiSecurityErrorWriter.write(mapper,request,response,CommonErrorCode.ACCESS_DENIED);
        return accepted;
    }
}

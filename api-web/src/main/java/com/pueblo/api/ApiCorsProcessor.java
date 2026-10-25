package com.pueblo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.cors.*;

public class ApiCorsProcessor extends DefaultCorsProcessor {
    private final ObjectMapper mapper;
    public ApiCorsProcessor(ObjectMapper mapper) { this.mapper=mapper; }
    @Override protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response) { response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN); }
    @Override public boolean processRequest(CorsConfiguration configuration,HttpServletRequest request,HttpServletResponse response) throws IOException {
        boolean accepted=super.processRequest(configuration,request,response);
        if(!accepted) ApiErrors.write(mapper,request,response,ErrorCode.ACCESS_DENIED);
        return accepted;
    }
}

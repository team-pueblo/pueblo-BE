package com.pueblo.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.common.security.ApiSecurityErrorWriter;
import com.pueblo.payment.gateway.ServiceClients;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class MemberAuthenticationFilter extends OncePerRequestFilter {
    private final ServiceClients clients;
    private final ObjectMapper mapper;
    MemberAuthenticationFilter(ServiceClients clients,ObjectMapper mapper) { this.clients=clients; this.mapper=mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/") || request.getMethod().equals("OPTIONS");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        String header=request.getHeader("Authorization");
        if(header!=null) {
            try {
                if(!header.startsWith("Bearer ") || header.length()>8192 || header.substring(7).isBlank()
                        || java.util.Collections.list(request.getHeaders("Authorization")).size()!=1)
                    throw new BusinessException(CommonErrorCode.INVALID_CREDENTIALS);
                var user=clients.member(header);
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,List.of()));
                SecurityContextHolder.setContext(context);
            } catch(BusinessException ex) { ApiSecurityErrorWriter.write(mapper,request,response,ex.code()); return; }
        }
        chain.doFilter(request,response);
    }
}

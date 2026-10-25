package com.pueblo.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.api.*;
import com.pueblo.order.checkout.ServiceClients;
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
                    throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
                var user=clients.member(header);
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,List.of()));
                SecurityContextHolder.setContext(context);
            } catch(ApiException ex) { ApiErrors.write(mapper,request,response,ex.code()); return; }
        }
        chain.doFilter(request,response);
    }
}

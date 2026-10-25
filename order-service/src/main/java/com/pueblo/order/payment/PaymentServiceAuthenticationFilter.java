package com.pueblo.order.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.*;
import com.pueblo.common.security.ApiSecurityErrorWriter;
import com.pueblo.order.checkout.ServiceClients;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class PaymentServiceAuthenticationFilter extends OncePerRequestFilter {
    static final String HEADER="X-Payment-Service-Token";
    private final byte[] digest;
    private final ServiceClients clients;
    private final ObjectMapper mapper;
    PaymentServiceAuthenticationFilter(String secret,ServiceClients clients,ObjectMapper mapper) {
        if(secret==null || !secret.matches("[!-~]{32,256}")) throw new IllegalArgumentException("Payment service token requires 32..256 visible ASCII characters");
        digest=hash(secret); this.clients=clients; this.mapper=mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        try {
            String secret=request.getHeader(HEADER);
            if(secret==null || secret.length()>256 || Collections.list(request.getHeaders(HEADER)).size()!=1
                    || !MessageDigest.isEqual(digest,hash(secret))) throw BusinessException.unauthorized();
            // These credentials belong to a server. Never accept an interactive browser-origin request.
            if(request.getHeader("Origin")!=null) throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
            String principal="payment-service";
            if(request.getMethod().equals("POST")) {
                String bearer=request.getHeader("Authorization");
                if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()>8192 || bearer.substring(7).isBlank()
                        || Collections.list(request.getHeaders("Authorization")).size()!=1) throw BusinessException.unauthorized();
                principal=clients.member(bearer).toString();
            }
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("PAYMENT_SERVICE"))));
            SecurityContextHolder.setContext(context);
        } catch(BusinessException ex) { ApiSecurityErrorWriter.write(mapper,request,response,ex.code()); return; }
        chain.doFilter(request,response);
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Digest unavailable"); }
    }
}

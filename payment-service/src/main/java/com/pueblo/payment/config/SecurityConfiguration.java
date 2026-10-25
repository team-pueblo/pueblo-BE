package com.pueblo.payment.config;

import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import com.pueblo.common.security.ApiCorsProcessor;
import com.pueblo.common.security.validation.ExternalUrlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper mapper,
            org.springframework.beans.factory.ObjectProvider<com.pueblo.payment.gateway.ServiceClients> clients,
            @org.springframework.beans.factory.annotation.Value("${pueblo.payments.allowed-origins:http://localhost:5173}") String origins) throws Exception {
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        var config=new org.springframework.web.cors.CorsConfiguration();
        var allowed=java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
        allowed.forEach(s->ExternalUrlPolicy.requireWebUrl(s,true,true));
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(java.util.List.of("GET","POST","OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("Authorization","Content-Type","Idempotency-Key","X-Request-ID","Accept-Language"));
        config.setAllowCredentials(false);
        source.registerCorsConfiguration("/**",config);
        var cors=new org.springframework.web.filter.CorsFilter(source);
        cors.setCorsProcessor(new ApiCorsProcessor(mapper));
        if(clients.getIfAvailable()!=null) http.addFilterBefore(new MemberAuthenticationFilter(clients.getObject(),mapper),
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http
                .csrf(c->c.disable()).requestCache(c->c.disable()) // Bearer-only routes never authenticate cookies.
                .cors(c->c.disable()).addFilterBefore(cors,org.springframework.security.web.csrf.CsrfFilter.class)
                .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .frameOptions(f->f.deny()).referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/payments/confirm","/api/v1/me/payments/orders/*").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(new ApiAccessDeniedHandler(mapper))
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper)))
                .build();
    }
}

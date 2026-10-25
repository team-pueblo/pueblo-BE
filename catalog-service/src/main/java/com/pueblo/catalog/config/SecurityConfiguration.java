package com.pueblo.catalog.config;

import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import com.pueblo.common.security.ApiCorsProcessor;
import com.pueblo.common.security.validation.ExternalUrlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper mapper, @org.springframework.beans.factory.annotation.Value("${pueblo.catalog.allowed-origins:}") java.util.List<String> allowedOrigins) throws Exception {
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        var policy=new org.springframework.web.cors.CorsConfiguration();
        allowedOrigins.stream().filter(origin->!origin.isBlank()).forEach(origin->ExternalUrlPolicy.requireWebUrl(origin,true,true));
        policy.setAllowedOrigins(allowedOrigins.stream().filter(origin->!origin.isBlank()).toList());
        policy.setAllowedMethods(java.util.List.of("GET","OPTIONS"));
        policy.setAllowedHeaders(java.util.List.of("Accept","Accept-Language","Content-Type","X-Request-ID"));
        policy.setAllowCredentials(false);
        source.registerCorsConfiguration("/**",policy);
        var cors=new org.springframework.web.filter.CorsFilter(source);
        cors.setCorsProcessor(new ApiCorsProcessor(mapper));
        return http
                .cors(c->c.disable()).addFilterBefore(cors,org.springframework.security.web.csrf.CsrfFilter.class)
                .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .frameOptions(f->f.deny()).referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/health", "/actuator/health/**",
                                "/api/v1/products","/api/v1/products/*","/api/v1/brands","/api/v1/brands/*",
                                "/api/v1/categories","/catalog-assets/demo.svg").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(new ApiAccessDeniedHandler(mapper))
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper)))
                .build();
    }
}

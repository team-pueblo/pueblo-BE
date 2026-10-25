package com.pueblo.payment.config;

import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import com.pueblo.common.security.ApiCorsProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        // No browser origins are allowed until this service exposes business APIs.
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**",new org.springframework.web.cors.CorsConfiguration());
        var cors=new org.springframework.web.filter.CorsFilter(source);
        cors.setCorsProcessor(new ApiCorsProcessor(mapper));
        return http
                .cors(c->c.disable()).addFilterBefore(cors,org.springframework.security.web.csrf.CsrfFilter.class)
                .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .frameOptions(f->f.deny()).referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(new ApiAccessDeniedHandler(mapper))
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper)))
                .build();
    }
}

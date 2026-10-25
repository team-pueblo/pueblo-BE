package com.pueblo.member.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.api.ApiErrors;
import com.pueblo.member.auth.AuthProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.*;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties properties, ObjectMapper mapper) throws Exception {
        CookieCsrfTokenRepository csrf=new CookieCsrfTokenRepository();
        csrf.setCookieName(properties.csrfCookieName());
        csrf.setCookieCustomizer(cookie->cookie.path("/").httpOnly(true).secure(properties.cookieSecure()).sameSite(properties.cookieSameSite()));
        var origins=new CorsConfiguration();
        origins.setAllowedOrigins(properties.allowedOrigins()); origins.setAllowCredentials(true);
        origins.setAllowedMethods(List.of("GET","POST","OPTIONS"));
        origins.setAllowedHeaders(List.of("Authorization","Content-Type","X-XSRF-TOKEN","X-Request-ID"));
        var cors=new UrlBasedCorsConfigurationSource(); cors.registerCorsConfiguration("/**",origins);
        var corsFilter=new org.springframework.web.filter.CorsFilter(cors);
        corsFilter.setCorsProcessor(new com.pueblo.api.ApiCorsProcessor(mapper));
        AuthenticationEntryPoint unauthorized=(request,response,error)->ApiErrors.write(mapper,request,response,error instanceof org.springframework.security.authentication.AuthenticationServiceException ? com.pueblo.api.ErrorCode.AUTH_STORE_UNAVAILABLE : com.pueblo.api.ErrorCode.INVALID_CREDENTIALS);
        AccessDeniedHandler denied=(request,response,error)->ApiErrors.write(mapper,request,response,error instanceof CsrfException ? com.pueblo.api.ErrorCode.CSRF_INVALID : com.pueblo.api.ErrorCode.ACCESS_DENIED);
        JwtGrantedAuthoritiesConverter roles=new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role"); roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwt=new JwtAuthenticationConverter(); jwt.setJwtGrantedAuthoritiesConverter(roles);
        return http.sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c->c.disable()).addFilterBefore(corsFilter,CsrfFilter.class)
                .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .frameOptions(f->f.deny()).referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter("Permissions-Policy","camera=(), microphone=(), geolocation=()")))
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .authorizeHttpRequests(a->a
                        .requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/**","/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/signup","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout",
                                "/api/v1/auth/email-verification-requests","/api/v1/auth/email-verifications",
                                "/api/v1/auth/password-reset-requests","/api/v1/auth/password-resets").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/v1/me").authenticated().anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(jwt)).authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .build();
    }
}

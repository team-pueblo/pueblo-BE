package com.pueblo.order.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import com.pueblo.order.checkout.ServiceClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name={"pueblo.orders.enabled","pueblo.orders.payment-preparation.enabled"},havingValue="true")
public class PaymentPreparationConfiguration {
    @Bean PaymentPreparationService paymentPreparationService(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        return new PaymentPreparationService(jdbc,manager);
    }
    @Bean @Order(1)
    SecurityFilterChain internalPaymentSecurity(HttpSecurity http,ObjectMapper mapper,ServiceClients clients,
            @Value("${pueblo.orders.payment-preparation.service-token:}") String token) throws Exception {
        return http.securityMatcher("/internal/**").csrf(c->c.disable()).cors(c->c.disable())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .addFilterBefore(new PaymentServiceAuthenticationFilter(token,clients,mapper),AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.POST,"/internal/v1/orders/*/payment-attempts").hasAuthority("PAYMENT_SERVICE")
                        .requestMatchers(HttpMethod.GET,"/internal/v1/orders/*/payment-attempts").hasAuthority("PAYMENT_SERVICE").anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper)).accessDeniedHandler(new ApiAccessDeniedHandler(mapper)))
                .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
                .build();
    }
}

package com.pueblo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="pueblo.docs.enabled",havingValue="true")
public class OpenApiAutoConfiguration {
    @Bean @Order(0)
    SecurityFilterChain documentationSecurity(HttpSecurity http,ObjectMapper mapper) throws Exception {
        return http.securityMatcher("/swagger-ui.html","/swagger-ui/**","/v3/api-docs","/v3/api-docs/**","/v3/api-docs.yaml")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,"/**").permitAll().anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper))
                        .accessDeniedHandler(new ApiAccessDeniedHandler(mapper)))
                .headers(h->h.frameOptions(f->f.deny()).contentSecurityPolicy(c->c.policyDirectives(
                        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")))
                .build();
    }
    @Bean
    OpenAPI serviceOpenApi(@Value("${spring.application.name}") String service) {
        String description=service.equals("member-service")
                ? "회원 인증 API. 회원 Swagger는 POST 요청 전 CSRF 토큰을 자동으로 발급받아 전송합니다. 별도 클라이언트는 GET /api/v1/auth/csrf 응답의 data.token과 쿠키를 함께 전송하세요. "
                  + "로그인 응답의 accessToken은 bearerAuth에 입력합니다. Refresh는 HttpOnly 쿠키로 자동 전송됩니다. "
                  + "이메일 인증을 통한 가입에는 .env의 SMTP 설정이 필요합니다."
                : service.equals("catalog-service")
                  ? "공개 상품·브랜드·카테고리 조회 API입니다. 가격은 KRW 정수이며 구매 시 재고를 다시 검사해야 합니다."
                  : service.equals("order-service")
                    ? "주문 기능 활성화 시 견적·주문 접수·본인 주문 조회·미결제 취소 API를 제공합니다. bearerAuth에 회원 accessToken을 입력하세요. 주문 생성에는 Idempotency-Key가 필요하며 재고 확보 결과는 주문 조회에서 확인합니다. 실제 결제 승인은 아직 연결하지 않았습니다."
                    : "업무 API 구현 전입니다. 구현된 /api/v1 경로만 문서화하며 현재 목록이 비어 있는 것은 정상입니다.";
        return new OpenAPI().info(new Info().title("pueblo · "+service).version("v1").description(description))
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}

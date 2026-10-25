package com.pueblo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.common.exception.ErrorCode;
import com.pueblo.common.logging.RequestLoggingFilter;
import com.pueblo.common.security.ApiAccessDeniedHandler;
import com.pueblo.common.security.ApiAuthenticationEntryPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=ApiErrorContractTest.App.class,properties="spring.application.name=common-test")
@AutoConfigureMockMvc
class ApiErrorContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @SpringBootConfiguration @EnableAutoConfiguration
    static class App {
        @Bean Controller controller() { return new Controller(); }
        @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper mapper) throws Exception {
            return http.csrf(c->c.ignoringRequestMatchers("/test/**"))
                    .authorizeHttpRequests(a->a.requestMatchers("/test/**").permitAll().anyRequest().authenticated())
                    .exceptionHandling(e->e.authenticationEntryPoint(new ApiAuthenticationEntryPoint(mapper)).accessDeniedHandler(new ApiAccessDeniedHandler(mapper)))
                    .build();
        }
    }
    enum TestCode implements ErrorCode {
        QUOTE;
        public String code() { return "DOMAIN_QUOTE"; }
        public HttpStatus status() { return HttpStatus.CONFLICT; }
        public String messageKey() { return "QUOTE_EXPIRED"; }
    }
    @RestController static class Controller {
        record Form(@NotBlank String password,@Email String email) { }
        @PostMapping("/test/validate") void validate(@Valid @RequestBody Form form) { }
        @GetMapping("/test/query") void query(@RequestParam int size) { }
        @GetMapping("/test/min") void minimum(@RequestParam @Min(1) int size) { }
        @GetMapping("/test/domain") void domain() { throw new BusinessException(TestCode.QUOTE); }
        @GetMapping("/test/rate") void rate() { throw new BusinessException(CommonErrorCode.RATE_LIMITED,17L); }
        @GetMapping("/test/failure") void failure() { throw new IllegalStateException("SQL password=hidden-secret test@example.com"); }
        @GetMapping("/test/item/{id}") String item(@PathVariable String id) { return "ok"; }
    }
    @Test void domainCodesUseMessageKeysAndKeepTheExistingWireContract() throws Exception {
        var response=mvc.perform(get("/test/domain").header("Accept-Language","en").header("X-Request-ID","untrusted"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DOMAIN_QUOTE"))
                .andExpect(jsonPath("$.error.message").value("The quote has expired. Request a new quote."))
                .andExpect(jsonPath("$.error.details").doesNotExist()).andExpect(header().string("Content-Language","en"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        String requestId=mapper.readTree(response.getContentAsString()).path("requestId").asText();
        assertThat(requestId).isEqualTo(response.getHeader("X-Request-ID")).isNotEqualTo("untrusted");
    }
    @Test void validationDetailsAreLocalizedAndNeverIncludeRejectedValues() throws Exception {
        mvc.perform(post("/test/validate").header("Accept-Language","en").contentType("application/json")
                .content("{\"password\":\"\",\"email\":\"private-invalid-value\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.details.length()").value(2))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-invalid-value"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enter a valid email address.")));
        mvc.perform(get("/test/query").param("size","private-not-a-number").header("Accept-Language","en"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("size"))
                .andExpect(jsonPath("$.error.details[0].code").value("typeMismatch"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-not-a-number"))));
        mvc.perform(get("/test/query")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].code").value("required"));
        mvc.perform(get("/test/min").param("size","0").header("Accept-Language","en"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].code").value("Min"));
    }
    @Test void securityErrorsAndBodyLimitUseTheSameResponseShape() throws Exception {
        mvc.perform(get("/private").header("Accept-Language","en")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS")).andExpect(jsonPath("$.requestId").isString());
        mvc.perform(post("/private").header("Accept-Language","en")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID")).andExpect(header().string("Content-Language","en"));
        mvc.perform(post("/test/validate").contentType("application/json").content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }
    @Test void retryHeaderAndUnexpectedFailuresNeverExposeInternalMessages() throws Exception {
        mvc.perform(get("/test/rate")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","17"));
        mvc.perform(get("/test/failure").header("Accept-Language","en")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.message").value("The request could not be processed."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hidden-secret"))));
        mvc.perform(post("/test/validate").contentType("application/json").content("{\"password\": \"hidden-secret\""))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hidden-secret"))));
    }
    @Test void requestLogContainsRouteTemplateInsteadOfPathOrQuery() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RequestLoggingFilter.class);
        var sink=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); sink.start(); logger.addAppender(sink);
        try {
            mvc.perform(get("/test/item/private-identifier").param("token","secret-query"));
            assertThat(sink.list).hasSize(1);
            assertThat(sink.list.getFirst().getFormattedMessage()).contains("route=/test/item/{id}","status=200","elapsedMs=")
                    .doesNotContain("private-identifier","secret-query");
            assertThat(org.slf4j.MDC.get("requestId")).isNull();
        } finally { logger.detachAppender(sink); sink.stop(); }
    }
}

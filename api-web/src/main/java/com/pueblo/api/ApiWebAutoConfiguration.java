package com.pueblo.api;

import com.pueblo.common.exception.GlobalExceptionHandler;
import com.pueblo.common.logging.RequestIdFilter;
import com.pueblo.common.logging.RequestLoggingFilter;
import com.pueblo.common.response.ApiErrorResponseFactory;
import com.pueblo.common.response.ApiMessageResolver;
import com.pueblo.common.security.RequestBodyLimitFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@Import({RequestIdFilter.class,RequestLoggingFilter.class,RequestBodyLimitFilter.class,GlobalExceptionHandler.class})
public class ApiWebAutoConfiguration {
    @Bean ApiMessageResolver apiMessageResolver() { return new ApiMessageResolver(); }
    @Bean ApiErrorResponseFactory apiErrorResponseFactory(ApiMessageResolver resolver) { return new ApiErrorResponseFactory(resolver); }
}

package com.pueblo.api;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;
@AutoConfiguration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@Import({RequestIdFilter.class,RequestBodyLimitFilter.class,ApiExceptionHandler.class})
public class ApiWebAutoConfiguration { }
